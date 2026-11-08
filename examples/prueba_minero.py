#!/usr/bin/env python3
"""Minero Stratum de prueba para solo mining, pensado para el telefono.

A diferencia de prueba_stratum.py, este SI construye trabajo real:

    cabecera = version + prevhash + merkle_root + ntime + nbits + nonce

con la moneda base armada a partir de coinb1 + extranonce1 + extranonce2 +
coinb2 y la raiz de merkle calculada con las ramas que manda la pool. Por eso
una share encontrada se puede enviar y la pool la puede aceptar de verdad.

Lo que NO hace falta esperar: esto corre en Python sobre la CPU del telefono.
A dificultad 10000 la probabilidad de acertar una share es de una entre unos
6 500 millones de hashes, asi que veras muy pocas. Y encontrar un bloque
completo en solo mining con esta potencia es, en la practica, imposible. El
panel lo dice con numeros en vez de prometer ganancias.

Uso:
    python3 prueba_minero.py <direccion_btc> [host] [puerto]
    python3 prueba_minero.py bc1... solo.ckpool.org 3333
    PRUEBA_PING=5 python3 prueba_minero.py bc1...     (ping cada 5 s)
    PRUEBA_DIFICULTAD=64 python3 prueba_minero.py ... (solo si la pool no la fija)
    PRUEBA_SUGERIR=64 python3 prueba_minero.py ...    (pide dificultad baja a la pool)

Ctrl+C lo detiene y muestra el resumen.
"""
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

# Objetivo de dificultad 1 de Bitcoin.
BASE_TARGET = 0x0000FFFF * (1 << 208)

# Un socket mudo se muere con errno 103 cuando el NAT del operador recicla la
# conexion a los 20-30 s, asi que se manda ping muy seguido y se lee con
# tiempo de espera corto para poder hacerlo.
PING_EVERY = max(3, int(os.environ.get("PRUEBA_PING", "10")))
RECV_TIMEOUT = 2.0
RESUMEN_EVERY = 15
LOTE_NONCES = 8192

try:
    from prueba_stratum import normalizar_cuenta
except Exception:  # si se copia el archivo solo, sin el otro a mano
    def normalizar_cuenta(cuenta):
        return cuenta.strip(), None


def doble_sha(datos):
    return hashlib.sha256(hashlib.sha256(datos).digest()).digest()


def objetivo(dificultad):
    """Target para una dificultad dada, sin perder precision con float."""
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
        self.dificultad = float(os.environ.get("PRUEBA_DIFICULTAD", "0") or 0) or 10000.0
        self.dificultad_fijada_por_pool = False
        self.hashes = 0
        self.shares_enviadas = 0
        self.shares_aceptadas = 0
        self.shares_rechazadas = 0
        self.inicio = time.time()
        self.conectado_desde = 0.0
        self.corriendo = True

    # ---------- red ----------
    def enviar(self, objeto):
        datos = (json.dumps(objeto) + "\n").encode("utf-8")
        with self.socket_lock:
            sock = self.sock
            if sock is None:
                return
            try:
                sock.sendall(datos)
            except OSError:
                pass

    def cerrar_socket(self):
        with self.socket_lock:
            if self.sock is not None:
                try:
                    self.sock.close()
                except OSError:
                    pass
                self.sock = None

    def conectar(self):
        while self.corriendo:
            try:
                sock = socket.create_connection((self.host, self.puerto), timeout=20)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                sock.settimeout(RECV_TIMEOUT)
                with self.socket_lock:
                    self.sock = sock
                self.conectado_desde = time.time()
                with self.trabajo_lock:
                    self.trabajo = None      # el de la conexion anterior no sirve
                self.extranonce2_siguiente = 0
                log("CONECTADO", "%s:%s" % (self.host, self.puerto), VERDE)
                self.enviar({"id": 1, "method": "mining.subscribe",
                             "params": ["prueba-minero/1.0"]})
                self.leer()
            except OSError as e:
                duró = int(time.time() - self.conectado_desde) if self.conectado_desde else 0
                log("RECONEXION", "socket abortado (errno %s) tras %d s; reintento en 3 s"
                    % (getattr(e, "errno", "?"), duró), ROJO)
            finally:
                self.cerrar_socket()
            if self.corriendo:
                time.sleep(3)

    def leer(self):
        buffer = b""
        ultimo_ping = 0.0
        while self.corriendo:
            try:
                datos = self.sock.recv(4096)
            except socket.timeout:
                datos = None
            except OSError:
                return
            if datos == b"":
                log("AVISO", "la pool cerro la conexion", AMARILLO)
                return
            if datos:
                buffer += datos
                while b"\n" in buffer:
                    linea, buffer = buffer.split(b"\n", 1)
                    linea = linea.strip()
                    if not linea:
                        continue
                    try:
                        self.procesar(json.loads(linea.decode("utf-8", "replace")))
                    except (ValueError, IndexError, TypeError) as e:
                        log("AVISO", "mensaje raro de la pool: %s" % e, AMARILLO)
            ahora = time.time()
            if ahora - ultimo_ping >= PING_EVERY:
                self.enviar({"id": 900, "method": "mining.ping", "params": []})
                ultimo_ping = ahora

    # ---------- protocolo ----------
    def procesar(self, msg):
        metodo = msg.get("method")
        if metodo == "mining.notify":
            p = msg["params"]
            with self.trabajo_lock:
                e2 = self.extranonce2_siguiente
                self.extranonce2_siguiente += 1
                self.trabajo = {
                    "job_id": p[0],
                    "prevhash": p[1],
                    "coinb1": p[2],
                    "coinb2": p[3],
                    "merkle": list(p[4] or []),
                    "version": p[5],
                    "nbits": p[6],
                    "ntime": p[7],
                    "clean": bool(p[8]),
                    "extranonce2": ("%0*x" % (2 * self.extranonce2_size, e2)),
                }
                cabecera, _ = self.preparar(self.trabajo)
            if cabecera:
                log("TRABAJO", "job %s · cabecera lista (%d bytes)" % (p[0], len(cabecera)))
        elif metodo == "mining.set_difficulty":
            self.dificultad = float(msg["params"][0] or 1)
            self.dificultad_fijada_por_pool = True
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
            return                      # respuesta a nuestro mining.ping
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
                log("AUTORIZADO", "la pool acepto la cuenta %s" % self.cuenta, VERDE)
                if not self.dificultad_fijada_por_pool:
                    log("DIFICULTAD", "%g (por defecto del script)" % self.dificultad, GRIS)
                sugerida = os.environ.get("PRUEBA_SUGERIR")
                if sugerida:
                    # Atajo para pruebas: pedir dificultad baja y ver shares
                    # aceptadas de verdad. Si la pool lo ignora, no pasa nada.
                    self.enviar({"id": 3, "method": "mining.suggest_difficulty",
                                 "params": [float(sugerida)]})
                    log("SUGERENCIA", "pedida dificultad %s" % sugerida, GRIS)
            else:
                log("RECHAZADO", "la pool no acepto la cuenta: %s" % (msg.get("error") or result), ROJO)
        elif isinstance(ident, int) and ident >= 100:
            if result is True:
                self.shares_aceptadas += 1
                log("SHARE", "aceptada por la pool (total %d)" % self.shares_aceptadas, VERDE)
            else:
                self.shares_rechazadas += 1
                log("SHARE", "rechazada: %s" % (msg.get("error") or result), ROJO)

    # ---------- trabajo ----------
    def preparar(self, t):
        """Devuelve (cabecera sin nonce, target). Cabecera = 76 bytes."""
        try:
            coinbase = (bytes.fromhex(t["coinb1"]) + self.extranonce1
                        + bytes.fromhex(t["extranonce2"]) + bytes.fromhex(t["coinb2"]))
            raiz = doble_sha(coinbase)
            for rama in t["merkle"]:
                raiz = doble_sha(raiz + bytes.fromhex(rama))
            version = bytes.fromhex(t["version"])[::-1]
            prev = bytes.fromhex(t["prevhash"])
            # Stratum manda el prevhash como 8 palabras de 4 bytes; la cabecera
            # las quiere invertidas palabra por palabra, no el bloque entero.
            prevhash = b"".join(prev[i:i + 4][::-1] for i in range(0, len(prev), 4))
            ntime = bytes.fromhex(t["ntime"])[::-1]
            nbits = bytes.fromhex(t["nbits"])[::-1]
            base = version + prevhash + raiz + ntime + nbits
            if len(base) != 76:
                return None, 0
            return base, objetivo(self.dificultad)
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
            encontrado = None
            for i in range(LOTE_NONCES):
                nonce = (t["nonce"] + i) & 0xFFFFFFFF if "nonce" in t else i
                h = doble_sha(cabecera + struct.pack("<I", nonce))
                self.hashes += 1
                if int.from_bytes(h, "little") <= target:
                    encontrado = nonce
                    break
            with self.trabajo_lock:
                if self.trabajo is t:
                    t["nonce"] = ((t.get("nonce", 0) + LOTE_NONCES) & 0xFFFFFFFF)
                sigue = self.trabajo is t
            if encontrado is not None and sigue:
                self.enviar_share(t, encontrado)
            if not sigue:
                continue

    def resumen(self):
        while self.corriendo:
            time.sleep(RESUMEN_EVERY)
            seg = max(1.0, time.time() - self.inicio)
            hps = self.hashes / seg
            ritmo = "%.0f H/s" % hps
            if hps >= 1000:
                ritmo = "%.1f kH/s" % (hps / 1000)
            espera = self.dificultad * (1 << 32) / max(1.0, hps)
            print("%s--- panel ---%s" % (AZUL, RESET))
            print("  tiempo        %s" % duracion(seg))
            print("  hashrate      %s" % ritmo)
            print("  hashes        %d" % self.hashes)
            print("  shares        %d enviadas · %d aceptadas · %d rechazadas"
                  % (self.shares_enviadas, self.shares_aceptadas, self.shares_rechazadas))
            print("  dificultad    %g" % self.dificultad)
            print("  una share     cada ~%s de media a este ritmo" % duracion(espera))
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
    print("%sSolo mining real, pero en CPU.%s A dificultad %g hace falta una media de "
          "%s hashes por share, y un bloque es mucho mas: esto sirve para probar el "
          "circuito, no para ganar dinero."
          % (GRIS, RESET, minero.dificultad,
             "{:,}".format(int(minero.dificultad * (1 << 32))).replace(",", ".")), flush=True)

    threading.Thread(target=minero.minar, name="motor", daemon=True).start()
    threading.Thread(target=minero.resumen, name="panel", daemon=True).start()
    try:
        minero.conectar()
    except KeyboardInterrupt:
        minero.corriendo = False
        log("FIN", "detenido a mano · %d hashes, %d shares enviadas"
            % (minero.hashes, minero.shares_enviadas), AMARILLO)
    finally:
        minero.cerrar_socket()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
