#!/usr/bin/env python3
"""Minero Stratum de prueba para solo mining, pensado para el telefono.

Arma trabajo real: cabecera = version + prevhash + merkle_root + ntime +
nbits + nonce, con la moneda base construida desde coinb1 + extranonce1 +
extranonce2 + coinb2 y la raiz de merkle con las ramas de la pool. Por eso una
share encontrada se puede enviar y la pool la puede aceptar de verdad.

Red:
  * El nombre de la pool se resuelve UNA vez y se guarda la IP. Asi un fallo
    momentaneo de DNS no tumba el bucle de reconexion (era el "errno 7":
    EAI_NODATA, no un corte de socket).
  * Ping cada 10 s por defecto, y cada 8 s si la ultima conexion duro menos
    de un minuto: se adapta solo cuando la red esta inestable. Cada ping
    muestra su ida y vuelta, asi se ve si el enlace se esta cayendo.
  * Un corte se cierra limpio y se reintenta a los 2 s, sin tocar el hilo de
    hashing: el contador de nonces sigue donde iba.
  * Cualquier error de red se cierra limpio y se reintenta con espera
    creciente. El hilo que calcula hashes nunca se queda bloqueado.

Uso:
    python3 prueba_minero.py <direccion_btc> [host] [puerto]
    PRUEBA_PING=10 python3 prueba_minero.py bc1...      (ping cada 10 s)
    PRUEBA_SUGERIR=64 python3 prueba_minero.py bc1...   (pide dificultad baja)

Ctrl+C lo detiene y muestra el resumen.
"""
import errno
import hashlib
import json
import os
import socket
import struct
import sys
import threading
import time

VERDE = "\033[92m"
ROJO = "\033[91m"
AMARILLO = "\033[93m"
AZUL = "\033[94m"
GRIS = "\033[90m"
RESET = "\033[0m"

BASE_TARGET = 0x0000FFFF * (1 << 208)

PING_EVERY = max(5, int(os.environ.get("PRUEBA_PING", "10")))
RECV_TIMEOUT = 2.0          # para poder pingear aunque la pool calle
CONNECT_TIMEOUT = 10.0
PING_RAPIDO = 8             # cuando la red viene cortando seguido
PING_ESTABLE = 10           # ritmo normal
ESPERA_INICIAL = 2
RESUMEN_EVERY = 15
LOTE_NONCES = 8192
DNS_TTL = 600               # segundos que se reutiliza la IP resuelta
ESPERA_MAX = 30

try:
    from prueba_stratum import normalizar_cuenta
except Exception:
    def normalizar_cuenta(cuenta):
        return cuenta.strip(), None


def doble_sha(datos):
    return hashlib.sha256(hashlib.sha256(datos).digest()).digest()


def objetivo(dificultad):
    """Target de una dificultad, con enteros (float perderia precision)."""
    d = dificultad if dificultad and dificultad > 0 else 1.0
    if d >= 1:
        return BASE_TARGET // int(d)
    return min((1 << 256) - 1, BASE_TARGET * int(round(1.0 / d)))


def duracion(segundos):
    if segundos < 90:
        return "%d s" % segundos
    if segundos < 5400:
        return "%d min" % (segundos / 60)
    if segundos < 172800:
        return "%.1f h" % (segundos / 3600)
    return "%.1f dias" % (segundos / 86400)


def log(etiqueta, texto, color=AZUL):
    print("%s[%s]%s %-12s %s" % (color, time.strftime("%H:%M:%S"), RESET, etiqueta, texto),
          flush=True)


class Minero:
    def __init__(self, cuenta, host, puerto):
        self.cuenta = cuenta
        self.host = host
        self.puerto = puerto
        self.sock = None
        self.socket_lock = threading.Lock()
        self.trabajo_lock = threading.Lock()
        self.trabajo = None
        self.extranonce1 = b""
        self.extranonce2_size = 4
        self.extranonce2_siguiente = 0
        self.dificultad = float(os.environ.get("PRUEBA_DIFICULTAD") or 10000)
        self.dificultad_fijada = False
        self.hashes = 0
        self.shares_enviadas = 0
        self.shares_aceptadas = 0
        self.shares_rechazadas = 0
        self.inicio = time.time()
        self.conectado = False
        self.ip = None
        self.ip_momento = 0.0
        self.ping_every = PING_EVERY
        self.ping_enviado = 0.0
        self.ultima_duracion = 0
        self.nonce_actual = 0
        self.corriendo = True

    # ---------------- red ----------------
    def resolver(self, forzar=False):
        """IP de la pool, cacheada. Evita que un fallo de DNS mate el bucle."""
        if not forzar and self.ip and time.time() - self.ip_momento < DNS_TTL:
            return self.ip
        info = socket.getaddrinfo(self.host, self.puerto, socket.AF_INET, socket.SOCK_STREAM)
        self.ip = info[0][4][0]
        self.ip_momento = time.time()
        log("DNS", "%s -> %s" % (self.host, self.ip), GRIS)
        return self.ip

    @staticmethod
    def afinar_keepalive(sock):
        """Sondas de vida cada 20 s en vez de las 2 h por defecto.

        Si el kernel del telefono no soporta alguna opcion, se ignora: es un
        extra, no un requisito.
        """
        for nombre, valor in (("TCP_KEEPIDLE", 20), ("TCP_KEEPINTVL", 5),
                              ("TCP_KEEPCNT", 3), ("TCP_USER_TIMEOUT", 30000)):
            opcion = getattr(socket, nombre, None)
            if opcion is None:
                continue
            try:
                sock.setsockopt(socket.IPPROTO_TCP, opcion, valor)
            except OSError:
                pass

    def enviar(self, objeto):
        datos = (json.dumps(objeto) + "\n").encode("utf-8")
        with self.socket_lock:
            sock = self.sock
            if sock is None:
                return
            try:
                sock.sendall(datos)
            except OSError:
                pass        # la reconexion se encarga; el motor no se entera

    def cerrar(self):
        with self.socket_lock:
            sock, self.sock = self.sock, None
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass
        self.conectado = False

    def motivo(self, e):
        """Traduce el error de red a algo entendible en el telefono."""
        if isinstance(e, socket.gaierror):
            codigo = getattr(e, "errno", None)
            if codigo in (7, -2, -3, -5):
                return "no se pudo resolver %s (DNS)" % self.host
            return "fallo de DNS (%s)" % (e.strerror or e)
        codigo = getattr(e, "errno", None)
        if codigo == errno.ECONNREFUSED:
            return "la pool rechazo la conexion"
        if codigo in (errno.ETIMEDOUT,):
            return "tiempo de espera agotado"
        if codigo in (errno.ECONNABORTED, errno.ECONNRESET, errno.EPIPE):
            return "la conexion se corto (errno %s)" % codigo
        if codigo in (errno.ENETUNREACH, errno.EHOSTUNREACH, errno.ENETDOWN):
            return "sin red en este momento (errno %s)" % codigo
        if codigo is None:
            return str(e)
        return "%s (errno %s)" % (e.strerror or "error de red", codigo)

    def conectar(self):
        espera = ESPERA_INICIAL
        while self.corriendo:
            try:
                ip = self.resolver()
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                self.afinar_keepalive(sock)
                sock.settimeout(CONNECT_TIMEOUT)
                sock.connect((ip, self.puerto))
                sock.settimeout(RECV_TIMEOUT)
                with self.socket_lock:
                    self.sock = sock
                self.conectado = True
                self.inicio_conexion = time.time()
                with self.trabajo_lock:
                    self.trabajo = None      # el de antes no sirve
                self.extranonce2_siguiente = 0
                self.ping_every = PING_RAPIDO if 0 < self.ultima_duracion < 60 else PING_EVERY
                log("CONECTADO", "%s:%s (%s)" % (self.host, self.puerto, ip), VERDE)
                self.enviar({"id": 1, "method": "mining.subscribe",
                             "params": ["prueba-minero/1.0"]})
                self.leer()

                # Salida limpia del bucle de lectura.
                duró = int(time.time() - self.inicio_conexion)
                self.ultima_duracion = duró
                log("DESCONECTADO", "la pool cerro la conexion tras %d s" % duró, AMARILLO)
            except (KeyboardInterrupt, SystemExit):
                raise
            except socket.gaierror as e:
                self.ip = None            # la proxima vez, resolver de nuevo
                log("SIN DNS", "%s; reintento en %d s" % (self.motivo(e), espera), ROJO)
            except OSError as e:
                if self.conectado:
                    duró = int(time.time() - getattr(self, "inicio_conexion", time.time()))
                    self.ultima_duracion = duró
                    espera = ESPERA_INICIAL   # habia conexion: reintento corto
                    pista = ""
                    if getattr(e, "errno", None) in (errno.ECONNABORTED, errno.ECONNRESET):
                        pista = " (la red aborto el flujo: cambios de Wi-Fi a datos, "
                        pista += "cobertura o filtrado del operador)"
                    log("RECONEXION", "%s tras %d s; reintento en %d s%s"
                        % (self.motivo(e), duró, espera, pista), ROJO)
                else:
                    log("SIN CONEXION", "%s; reintento en %d s" % (self.motivo(e), espera), ROJO)
                if isinstance(e, socket.gaierror) or getattr(e, "errno", None) in (
                        errno.ECONNREFUSED, errno.ENETUNREACH, errno.EHOSTUNREACH):
                    self.ip = None
            finally:
                self.cerrar()
            if not self.corriendo:
                break
            time.sleep(espera)
            espera = min(15, espera * 2)

    def leer(self):
        """Lee hasta que la conexion muere. No propaga excepciones."""
        buffer = b""
        ultimo_ping = 0.0
        while self.corriendo:
            sock = self.sock
            if sock is None:
                return
            try:
                datos = sock.recv(4096)
            except socket.timeout:
                datos = None                       # normal: toca pingear
            except (OSError, ConnectionError) as e:
                if getattr(e, "errno", None) == errno.ECONNABORTED:
                    log("AVISO", "errno 103: la red aborto el flujo; cierro y reintento "
                        "en %d s" % ESPERA_INICIAL, AMARILLO)
                elif isinstance(e, socket.timeout):
                    continue
                else:
                    log("AVISO", "lectura interrumpida: %s" % self.motivo(e), AMARILLO)
                return
            if datos == b"":
                return                             # cerrada por la pool
            if datos:
                buffer += datos
                while b"\n" in buffer:
                    linea, buffer = buffer.split(b"\n", 1)
                    linea = linea.strip()
                    if not linea:
                        continue
                    try:
                        self.procesar(json.loads(linea.decode("utf-8", "replace")))
                    except (ValueError, IndexError, KeyError, TypeError) as e:
                        log("AVISO", "mensaje raro de la pool: %s" % e, AMARILLO)
            ahora = time.time()
            if ahora - ultimo_ping >= self.ping_every:
                self.ping_enviado = ahora
                self.enviar({"id": 900, "method": "mining.ping", "params": []})
                ultimo_ping = ahora

    # ---------------- protocolo ----------------
    def procesar(self, msg):
        metodo = msg.get("method")
        if metodo == "mining.notify":
            p = msg["params"]
            with self.trabajo_lock:
                e2 = self.extranonce2_siguiente
                self.extranonce2_siguiente += 1
                self.trabajo = {
                    "job_id": p[0], "prevhash": p[1], "coinb1": p[2], "coinb2": p[3],
                    "merkle": list(p[4] or []), "version": p[5], "nbits": p[6],
                    "ntime": p[7], "clean": bool(p[8]),
                    "extranonce2": "%0*x" % (2 * self.extranonce2_size, e2),
                }
                cabecera, _ = self.preparar(self.trabajo)
            if cabecera:
                log("TRABAJO", "job %s · cabecera lista (%d bytes)" % (p[0], len(cabecera)))
        elif metodo == "mining.set_difficulty":
            self.dificultad = float(msg["params"][0] or 1)
            self.dificultad_fijada = True
            log("DIFICULTAD", "%g (fijada por la pool)" % self.dificultad)
        elif metodo == "mining.set_extranonce":
            params = msg.get("params") or []
            if len(params) >= 2 and params[0]:
                self.extranonce1 = bytes.fromhex(params[0])
                self.extranonce2_size = int(params[1])
                log("EXTRANONCE", "nuevo extranonce1 %s" % params[0])
        elif "result" in msg:
            self.respuesta(msg)

    def respuesta(self, msg):
        ident = msg.get("id")
        result = msg.get("result")
        if ident == 900:
            if self.ping_enviado:
                ms = int((time.time() - self.ping_enviado) * 1000)
                log("PING", "%d ms (cada %d s)" % (ms, self.ping_every), GRIS)
            return                                  # respuesta al ping
        if ident == 1:
            if not result:
                log("ERROR", "la pool rechazo la suscripcion: %s" % msg.get("error"), ROJO)
                return
            self.extranonce1 = bytes.fromhex(result[1])
            self.extranonce2_size = int(result[2])
            log("SUSCRIPCION", "aceptada · extranonce1 %s" % result[1], VERDE)
            self.enviar({"id": 2, "method": "mining.authorize",
                         "params": [self.cuenta, "x"]})
        elif ident == 2:
            if result is True:
                log("AUTORIZADO", "la pool acepto %s" % self.cuenta, VERDE)
                if not self.dificultad_fijada:
                    log("DIFICULTAD", "%g (por defecto)" % self.dificultad, GRIS)
                sugerida = os.environ.get("PRUEBA_SUGERIR")
                if sugerida:
                    self.enviar({"id": 3, "method": "mining.suggest_difficulty",
                                 "params": [float(sugerida)]})
                    log("SUGERENCIA", "pedida dificultad %s" % sugerida, GRIS)
            else:
                log("RECHAZADO", "la pool no acepto la cuenta: %s"
                    % (msg.get("error") or result), ROJO)
        elif isinstance(ident, int) and ident >= 100:
            if result is True:
                self.shares_aceptadas += 1
                log("SHARE", "aceptada por la pool (total %d)" % self.shares_aceptadas, VERDE)
            else:
                self.shares_rechazadas += 1
                log("SHARE", "rechazada: %s" % (msg.get("error") or result), ROJO)

    # ---------------- trabajo ----------------
    def preparar(self, t):
        try:
            coinbase = (bytes.fromhex(t["coinb1"]) + self.extranonce1
                        + bytes.fromhex(t["extranonce2"]) + bytes.fromhex(t["coinb2"]))
            raiz = doble_sha(coinbase)
            for rama in t["merkle"]:
                raiz = doble_sha(raiz + bytes.fromhex(rama))
            version = bytes.fromhex(t["version"])[::-1]
            prev = bytes.fromhex(t["prevhash"])
            prevhash = b"".join(prev[i:i + 4][::-1] for i in range(0, len(prev), 4))
            ntime = bytes.fromhex(t["ntime"])[::-1]
            nbits = bytes.fromhex(t["nbits"])[::-1]
            base = version + prevhash + raiz + ntime + nbits
            return (base, objetivo(self.dificultad)) if len(base) == 76 else (None, 0)
        except (ValueError, KeyError, TypeError):
            return None, 0

    def enviar_share(self, t, nonce):
        self.shares_enviadas += 1
        self.enviar({"id": 100 + self.shares_enviadas, "method": "mining.submit",
                     "params": [self.cuenta, t["job_id"], t["extranonce2"],
                                t["ntime"], "%08x" % nonce]})
        log("SHARE", "enviada nonce %08x (job %s)" % (nonce, t["job_id"]), AMARILLO)

    def minar(self):
        log("MOTOR", "hilo de hashing iniciado", GRIS)
        while self.corriendo:
            with self.trabajo_lock:
                t = self.trabajo
            if not t:
                time.sleep(0.2)
                continue
            cabecera, target = self.preparar(t)
            if not cabecera:
                time.sleep(0.2)
                continue
            desde = self.nonce_actual
            encontrado = None
            for i in range(LOTE_NONCES):
                nonce = (desde + i) & 0xFFFFFFFF
                h = doble_sha(cabecera + struct.pack("<I", nonce))
                self.hashes += 1
                if int.from_bytes(h, "little") <= target:
                    encontrado = nonce
                    break
            self.nonce_actual = (desde + LOTE_NONCES) & 0xFFFFFFFF
            with self.trabajo_lock:
                sigue = self.trabajo is t
            if encontrado is not None and sigue:
                self.enviar_share(t, encontrado)
            time.sleep(0.002)      # deja respirar al lector y al procesador

    def resumen(self):
        while self.corriendo:
            time.sleep(RESUMEN_EVERY)
            seg = max(1.0, time.time() - self.inicio)
            hps = self.hashes / seg
            ritmo = "%.0f H/s" % hps
            if hps >= 1000:
                ritmo = "%.1f kH/s" % (hps / 1000)
            print("%s--- panel ---%s" % (AZUL, RESET))
            print("  tiempo        %s" % duracion(seg))
            print("  conexion      %s" % ("abierta" if self.conectado else "reintentando"))
            print("  hashrate      %s" % ritmo)
            print("  hashes        %d" % self.hashes)
            print("  shares        %d enviadas · %d aceptadas · %d rechazadas"
                  % (self.shares_enviadas, self.shares_aceptadas, self.shares_rechazadas))
            print("  dificultad    %g" % self.dificultad)
            print("  una share     cada ~%s de media a este ritmo"
                  % duracion(self.dificultad * (1 << 32) / max(1.0, hps)))
            print("%s----------------%s" % (AZUL, RESET), flush=True)


def main():
    if len(sys.argv) < 2:
        print("Uso: python3 prueba_minero.py <direccion_btc> [host] [puerto]")
        return 2
    cuenta, aviso = normalizar_cuenta(sys.argv[1])
    if aviso:
        log("AVISO", aviso, AMARILLO)
    host = sys.argv[2] if len(sys.argv) > 2 else "solo.ckpool.org"
    puerto = int(sys.argv[3]) if len(sys.argv) > 3 else 3333

    minero = Minero(cuenta, host, puerto)
    print("%sSolo mining real, pero en CPU.%s A dificultad %g una share pide unos "
          "%s hashes de media, y un bloque muchisimo mas: esto prueba el circuito, "
          "no da ganancias."
          % (GRIS, RESET, minero.dificultad,
             "{:,}".format(int(minero.dificultad * (1 << 32))).replace(",", ".")), flush=True)

    threading.Thread(target=minero.minar, name="motor", daemon=True).start()
    threading.Thread(target=minero.resumen, name="panel", daemon=True).start()
    try:
        minero.conectar()
    except KeyboardInterrupt:
        minero.corriendo = False
        minero.cerrar()
        log("FIN", "detenido · %d hashes, %d shares enviadas"
            % (minero.hashes, minero.shares_enviadas), AMARILLO)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
