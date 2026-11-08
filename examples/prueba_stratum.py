#!/usr/bin/env python3
"""Prueba de estabilidad Stratum contra CKPool (solo para verificar el arreglo).

No mina de verdad: se conecta, se autoriza, escucha y hace ping cada 45 s.
Sirve para confirmar que el socket sobrevive con la pantalla apagada.

Uso:
    python3 prueba_stratum.py <tu_direccion_btc>
    python3 prueba_stratum.py <tu_direccion_btc> solo.ckpool.org 3333

Deja la pantalla apagada 30 minutos. Si el contador de líneas sigue subiendo
y no aparece "reconectando", el arreglo funciona.
"""
import json
import os
import signal
import socket
import sys
import time

signal.signal(signal.SIGPIPE, signal.SIG_IGN)

PING_EVERY = 45
RECONECTABLES = {32, 103, 104, 110}


def abrir(host, port):
    s = socket.create_connection((host, port), timeout=20)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    for opt, val in (("TCP_KEEPIDLE", 30), ("TCP_KEEPINTVL", 15), ("TCP_KEEPCNT", 4)):
        if hasattr(socket, opt):
            s.setsockopt(socket.IPPROTO_TCP, getattr(socket, opt), val)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return s


def enviar(s, obj):
    s.sendall((json.dumps(obj) + "\n").encode())


def main():
    if len(sys.argv) < 2:
        print("uso: prueba_stratum.py <direccion_btc> [host] [puerto]")
        return 2
    user = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else "solo.ckpool.org"
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 3333

    intento = 0
    lineas = 0
    inicio = time.time()

    while True:
        try:
            s = abrir(host, port)
            enviar(s, {"id": 1, "method": "mining.subscribe", "params": ["prueba/1.0"]})
            enviar(s, {"id": 2, "method": "mining.authorize", "params": [user + ".prueba", "x"]})
            s.settimeout(5)
            ultimo_ping = time.time()
            buf = b""
            print("conectado a %s:%s como %s.prueba" % (host, port, user))
            intento = 0

            while True:
                try:
                    datos = s.recv(4096)
                except socket.timeout:
                    datos = b""
                if datos:
                    buf += datos
                    while b"\n" in buf:
                        linea, buf = buf.split(b"\n", 1)
                        lineas += 1
                        txt = linea.decode("utf-8", "replace")
                        if "notify" in txt or "difficulty" in txt or "result" in txt:
                            mins = (time.time() - inicio) / 60
                            print("[%.1f min] %d lineas | %s" % (mins, lineas, txt[:110]))

                if time.time() - ultimo_ping >= PING_EVERY:
                    enviar(s, {"id": 100, "method": "mining.ping", "params": []})
                    ultimo_ping = time.time()
                    print("ping enviado")

        except OSError as e:
            if e.errno in RECONECTABLES:
                intento += 1
                espera = min(60, 2 ** min(intento, 5))
                print("socket abortado (errno %s). Reconectando en %ss" % (e.errno, espera))
                time.sleep(espera)
                continue
            print("error no recuperable: %s" % e)
            return 1
        finally:
            try:
                s.close()
            except Exception:
                pass


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrumpido a mano")
