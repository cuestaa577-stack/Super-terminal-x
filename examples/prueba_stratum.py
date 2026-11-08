#!/usr/bin/env python3
"""Prueba de estabilidad Stratum contra CKPool, con salida legible.

No mina: se conecta, se autoriza, escucha y hace ping cada 45 s. Sirve para
comprobar que el socket sobrevive con la pantalla apagada y para ver, en
español y sin JSON crudo, lo que manda el pool.

Uso:
    python3 prueba_stratum.py <tu_direccion_btc>
    python3 prueba_stratum.py <tu_direccion_btc> solo.ckpool.org 3333

Deja la pantalla apagada 30 minutos. Si sigue apareciendo "TRABAJO" cada
cierto tiempo y no salta "RECONEXION", el socket aguanta.

Este script no calcula hashes, así que normalmente no enviará ninguna share.
El manejo de mining.submit está implementado para cuando lo uses con el
minero real: verás "SHARE aceptada" o "SHARE rechazada" con el motivo.
"""
import json
import signal
import socket
import sys
import threading
import time
from collections import deque

PING_EVERY = 45
RESUMEN_EVERY = 300
SILENCIO_AVISO = 120
RECONECTABLES = {32, 103, 104, 110}
ANCHO_ETIQUETA = 11

signal.signal(signal.SIGPIPE, signal.SIG_IGN)

_salida = threading.Lock()
_lock = threading.Lock()
_siguiente_id = [100]


def nuevo_id():
    with _lock:
        _siguiente_id[0] += 1
        return _siguiente_id[0]


# ---------------------------------------------------------------- presentación

def transcurrido():
    segundos = int(max(0, time.time() - ESTADO.inicio))
    return "%d:%02d:%02d" % (segundos // 3600, (segundos % 3600) // 60, segundos % 60)


def log(etiqueta, mensaje):
    """Una sola escritura por línea: sin salidas cruzadas entre hilos."""
    linea = "[%s] %-*s %s" % (transcurrido(), ANCHO_ETIQUETA, etiqueta, mensaje)
    with _salida:
        sys.stdout.write(linea + "\n")
        sys.stdout.flush()


def numero(valor):
    """Dificultad legible: 0.00000500, 0.001000, 1.5000, 12 000."""
    try:
        v = float(valor)
    except (TypeError, ValueError):
        return str(valor)
    if v == 0:
        return "0"
    if v < 1:
        texto = "%.8f" % v
        return texto.rstrip("0").rstrip(".")
    if v < 1000:
        texto = "%.4f" % v
        return texto.rstrip("0").rstrip(".")
    return "{:,}".format(int(v)).replace(",", " ")


def corto(texto, ancho=12):
    texto = str(texto)
    return texto if len(texto) <= ancho else texto[:ancho] + "…"


# ------------------------------------------------------------------- estado

class Estado:
    def __init__(self):
        self.inicio = time.time()
        self.trabajos = 0
        self.dificultad = None
        self.aceptados = 0
        self.rechazados = 0
        self.conexiones = 0
        self.pings = 0
        self.ultima_latencia = None
        self.ultimo_mensaje = time.time()
        self.jobs_vistos = deque(maxlen=40)
        self.peticiones = {}

    def limpiar_peticiones(self):
        self.peticiones.clear()


ESTADO = Estado()


# ------------------------------------------------------------------- protocolo

def abrir(host, port):
    """Conexión con keep-alive estándar.

    Solo SO_KEEPALIVE. Las opciones de bajo nivel (TCP_KEEPIDLE, TCP_KEEPINTVL,
    TCP_KEEPCNT, TCP_NODELAY) van por IPPROTO_TCP y en el kernel de Android
    devuelven [Errno 22] Invalid argument, así que no se tocan.
    """
    s = socket.create_connection((host, port), timeout=20)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    return s


def enviar(s, obj, descripcion=None):
    with _lock:
        if descripcion:
            ESTADO.peticiones[obj["id"]] = (descripcion, time.time())
    s.sendall((json.dumps(obj) + "\n").encode())


def describir_error(error):
    """Traduce el formato de error de Stratum a texto corto."""
    if isinstance(error, list) and error:
        # Formato Stratum: [codigo, "mensaje", datos]. Solo interesa el mensaje.
        if isinstance(error[0], int) and len(error) >= 2:
            return str(error[1])
        partes = []
        for item in error:
            if isinstance(item, (list, tuple)) and len(item) >= 2:
                partes.append(str(item[1]))
            else:
                partes.append(str(item))
        return " · ".join(partes)
    if isinstance(error, dict):
        return str(error.get("message") or error)
    if error is None:
        return "sin motivo indicado"
    return str(error)


def trabajo(params):
    job = params[0] if params else "?"
    limpio = bool(params[8]) if len(params) > 8 else False
    with _lock:
        if job in ESTADO.jobs_vistos:
            return          # el pool lo repitió, no lo imprimimos otra vez
        ESTADO.jobs_vistos.append(job)
        ESTADO.trabajos += 1
        total = ESTADO.trabajos
    detalle = "job %s" % corto(job)
    if limpio:
        detalle += " · bloque nuevo, descarta el anterior"
    log("TRABAJO", "%s (van %d)" % (detalle, total))


def dificultad(params):
    valor = params[0] if params else "?"
    with _lock:
        anterior = ESTADO.dificultad
        if anterior is not None and numero(anterior) == numero(valor):
            return
        ESTADO.dificultad = valor
    if anterior is None:
        log("DIFICULTAD", "%s (fijada por la pool)" % numero(valor))
    else:
        log("DIFICULTAD", "%s (antes %s)" % (numero(valor), numero(anterior)))


def respuesta(msg):
    ident = msg.get("id")
    resultado = msg.get("result")
    error = msg.get("error")

    with _lock:
        peticion = ESTADO.peticiones.pop(ident, None)
    tipo = peticion[0] if peticion else None
    enviada_en = peticion[1] if peticion else None

    if tipo == "suscripcion":
        if isinstance(resultado, list) and len(resultado) >= 2:
            log("POOL", "suscripción aceptada · extranonce %s" % corto(resultado[1], 16))
        else:
            log("ERROR", "suscripción rechazada: %s" % describir_error(error or resultado))
        return

    if tipo == "autorizacion":
        if resultado is True:
            log("AUTORIZADO", "la pool aceptó la dirección")
        else:
            log("ERROR", "autorización rechazada: %s" % describir_error(error or resultado))
        return

    if tipo == "ping":
        if enviada_en:
            ms = int((time.time() - enviada_en) * 1000)
            with _lock:
                ESTADO.ultima_latencia = ms
                ESTADO.pings += 1
            log("PING", "%d ms" % ms)
        else:
            log("PING", "respondido")
        return

    if tipo == "share":
        if resultado is True:
            with _lock:
                ESTADO.aceptados += 1
                total = ESTADO.aceptados
            log("SHARE", "aceptada por la pool (total %d)" % total)
        else:
            with _lock:
                ESTADO.rechazados += 1
                total = ESTADO.rechazados
            log("SHARE", "rechazada: %s (total %d)" % (describir_error(error), total))
        return

    if error:
        log("ERROR", "la pool respondió con error: %s" % describir_error(error))


def procesar(texto):
    with _lock:
        ESTADO.ultimo_mensaje = time.time()
    try:
        msg = json.loads(texto)
    except ValueError:
        log("AVISO", "llegó algo que no es JSON, se ignora")
        return

    metodo = msg.get("method")
    if metodo == "mining.notify":
        trabajo(msg.get("params") or [])
    elif metodo == "mining.set_difficulty":
        dificultad(msg.get("params") or [])
    elif metodo == "client.show_message":
        params = msg.get("params") or []
        if params:
            log("MENSAJE", str(params[0])[:120])
    elif msg.get("id") is not None:
        respuesta(msg)


# ------------------------------------------------------------------- hilos

def vigilante():
    """Avisa si el pool se queda callado, sin repetirlo cada pocos segundos."""
    ultimo_aviso = 0
    while True:
        time.sleep(5)
        with _lock:
            silencio = time.time() - ESTADO.ultimo_mensaje
        if silencio >= SILENCIO_AVISO and time.time() - ultimo_aviso >= SILENCIO_AVISO:
            ultimo_aviso = time.time()
            log("AVISO", "el pool lleva %d s sin enviar nada" % int(silencio))


def resumen():
    with _lock:
        partes = [
            "conexiones %d" % ESTADO.conexiones,
            "trabajos %d" % ESTADO.trabajos,
            "dificultad %s" % (numero(ESTADO.dificultad) if ESTADO.dificultad is not None else "sin dato"),
            "pings %d" % ESTADO.pings,
        ]
        if ESTADO.ultima_latencia is not None:
            partes.append("latencia %d ms" % ESTADO.ultima_latencia)
        if ESTADO.aceptados or ESTADO.rechazados:
            partes.append("%d shares ok / %d rechazadas" % (ESTADO.aceptados, ESTADO.rechazados))
    log("RESUMEN", " · ".join(partes))


# --------------------------------------------------------------------- main

def main():
    if len(sys.argv) < 2:
        print("uso: prueba_stratum.py <direccion_btc> [host] [puerto]")
        return 2
    usuario = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else "solo.ckpool.org"
    puerto = int(sys.argv[3]) if len(sys.argv) > 3 else 3333

    log("INICIO", "conectando a %s:%s" % (host, puerto))
    threading.Thread(target=vigilante, name="vigilante", daemon=True).start()

    intento = 0
    while True:
        s = None
        try:
            s = abrir(host, puerto)
            with _lock:
                ESTADO.limpiar_peticiones()
                ESTADO.conexiones += 1
                ESTADO.ultimo_mensaje = time.time()
            enviar(s, {"id": nuevo_id(), "method": "mining.subscribe",
                       "params": ["prueba/2.0"]}, "suscripcion")
            enviar(s, {"id": nuevo_id(), "method": "mining.authorize",
                       "params": [usuario + ".prueba", "x"]}, "autorizacion")
            log("CONECTADO", "%s:%s como %s.prueba" % (host, puerto, usuario))
            intento = 0
            s.settimeout(5)
            buffer = b""
            ultimo_ping = time.time()
            ultimo_resumen = time.time()

            while True:
                try:
                    datos = s.recv(4096)
                except socket.timeout:
                    datos = None

                if datos == b"":
                    log("AVISO", "la pool cerró la conexión")
                    break
                if datos:
                    buffer += datos
                    while b"\n" in buffer:
                        linea, buffer = buffer.split(b"\n", 1)
                        linea = linea.strip()
                        if linea:
                            procesar(linea.decode("utf-8", "replace"))

                ahora = time.time()
                if ahora - ultimo_ping >= PING_EVERY:
                    enviar(s, {"id": nuevo_id(), "method": "mining.ping", "params": []}, "ping")
                    ultimo_ping = ahora
                if ahora - ultimo_resumen >= RESUMEN_EVERY:
                    resumen()
                    ultimo_resumen = ahora

            time.sleep(2)

        except OSError as e:
            if e.errno in RECONECTABLES:
                intento += 1
                espera = min(60, 2 ** min(intento, 5))
                log("RECONEXION", "socket abortado (errno %s), reintento en %d s" % (e.errno, espera))
                time.sleep(espera)
                continue
            log("ERROR", "fallo no recuperable: %s" % e)
            return 1
        finally:
            if s is not None:
                try:
                    s.close()
                except OSError:
                    pass


if __name__ == "__main__":
    try:
        codigo = main()
    except KeyboardInterrupt:
        log("FIN", "interrumpido a mano")
        resumen()
        codigo = 0
    sys.exit(codigo)
