#!/usr/bin/env python3
"""Mide por que se corta la conexion con la pool. No mina nada.

Hace tres pruebas por separado y al final da un veredicto:

  1. DNS: cuanto tarda en resolver el nombre.
  2. Handshake TCP: cinco conexiones seguidas, con su tiempo en ms. Si unas
     van rapido y otras se quedan colgadas, el enlace pierde paquetes.
  3. Supervivencia: una conexion Stratum de verdad, con ping cada 5 s
     mostrando la ida y vuelta. Asi se ve el segundo exacto en que muere y
     si el enlace estaba respondiendo justo antes.

Uso:
    python3 diagnostico_red.py
    python3 diagnostico_red.py solo.ckpool.org 3333
    SEGUNDOS=120 python3 diagnostico_red.py     (supervivencia mas larga)
"""
import json
import os
import socket
import sys
import time

SEGUNDOS = int(os.environ.get("SEGUNDOS", "60"))
PING = 5


def ms(t0):
    return int((time.time() - t0) * 1000)


def prueba_dns(host, puerto):
    print("\n--- 1. DNS ---")
    tiempos = []
    for i in range(3):
        t0 = time.time()
        try:
            ip = socket.getaddrinfo(host, puerto, socket.AF_INET, socket.SOCK_STREAM)[0][4][0]
            tiempos.append(ms(t0))
            print("  intento %d: %s en %d ms" % (i + 1, ip, tiempos[-1]))
        except socket.gaierror as e:
            print("  intento %d: FALLO %s" % (i + 1, e))
        time.sleep(0.3)
    if not tiempos:
        print("  veredicto: el DNS no resuelve este nombre.")
        return None
    ip = socket.getaddrinfo(host, puerto, socket.AF_INET, socket.SOCK_STREAM)[0][4][0]
    print("  veredicto: resuelve, media %d ms" % (sum(tiempos) // len(tiempos)))
    return ip


def prueba_conexiones(ip, puerto):
    print("\n--- 2. handshake TCP (5 intentos) ---")
    bien = 0
    for i in range(5):
        t0 = time.time()
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(10)
        try:
            s.connect((ip, puerto))
            print("  intento %d: conecta en %d ms" % (i + 1, ms(t0)))
            bien += 1
        except OSError as e:
            print("  intento %d: FALLO tras %d ms (%s)" % (i + 1, ms(t0), e))
        finally:
            try:
                s.close()
            except OSError:
                pass
        time.sleep(1)
    print("  veredicto: %d de 5 conexiones" % bien)
    return bien


def prueba_supervivencia(host, ip, puerto):
    print("\n--- 3. supervivencia (%d s, ping cada %d s) ---" % (SEGUNDOS, PING))
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(10)
    t_ini = time.time()
    try:
        s.connect((ip, puerto))
    except OSError as e:
        print("  no conecta: %s" % e)
        return
    s.settimeout(1.0)
    print("  conectado en %d ms" % ms(t_ini))

    pendientes = {}
    pings = 0
    respondidos = 0
    peor = 0
    corte = None
    enviar = lambda o: s.sendall((json.dumps(o) + "\n").encode())
    enviar({"id": 1, "method": "mining.subscribe", "params": ["diagnostico/1.0"]})
    ultimo_ping = 0.0
    buffer = b""

    while time.time() - t_ini < SEGUNDOS:
        try:
            datos = s.recv(4096)
        except socket.timeout:
            datos = None
        except OSError as e:
            corte = "%s (errno %s) a los %d s" % (e.strerror or e, getattr(e, "errno", "?"),
                                                  int(time.time() - t_ini))
            break
        if datos == b"":
            corte = "la pool cerro la conexion a los %d s" % int(time.time() - t_ini)
            break
        if datos:
            buffer += datos
            while b"\n" in buffer:
                linea, buffer = buffer.split(b"\n", 1)
                if not linea.strip():
                    continue
                try:
                    msg = json.loads(linea)
                except ValueError:
                    continue
                if msg.get("id") in pendientes:
                    rtt = ms(pendientes.pop(msg["id"]))
                    respondidos += 1
                    peor = max(peor, rtt)
                    print("  ping %d responde en %d ms (a los %d s)"
                          % (pings, rtt, int(time.time() - t_ini)))
                elif msg.get("method") == "mining.notify":
                    print("  trabajo nuevo (a los %d s)" % int(time.time() - t_ini))
        ahora = time.time()
        if ahora - ultimo_ping >= PING:
            pings += 1
            pendientes[900 + pings] = ahora
            enviar({"id": 900 + pings, "method": "mining.ping", "params": []})
            ultimo_ping = ahora

    duró = int(time.time() - t_ini)
    try:
        s.close()
    except OSError:
        pass

    print("\n--- veredicto ---")
    print("  aguanto           %d s" % duró)
    print("  pings respondidos %d de %d" % (respondidos, pings))
    if peor:
        print("  peor ida y vuelta %d ms" % peor)
    if corte:
        print("  corte             %s" % corte)
    elif duró >= SEGUNDOS:
        print("  corte             ninguno en la ventana de prueba")
    if corte and respondidos >= 2:
        print("  lectura: el enlace respondia y aun asi se abrio. No es inactividad:")
        print("           apunta a la red movil/Wi-Fi cambiando o filtrado del operador.")
    elif corte and respondidos < 2:
        print("  lectura: casi ningun ping volvio. El enlace pierde paquetes de salida.")
    elif pings and respondidos < pings // 2:
        print("  lectura: los pings no vuelven. La pool o el camino los esta filtrando.")


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "solo.ckpool.org"
    puerto = int(sys.argv[2]) if len(sys.argv) > 2 else 3333
    print("Diagnostico contra %s:%s" % (host, puerto))
    ip = prueba_dns(host, puerto)
    if not ip:
        return 1
    if prueba_conexiones(ip, puerto) == 0:
        print("\nSin handshake no se puede seguir: el puerto esta cerrado o filtrado.")
        return 1
    prueba_supervivencia(host, ip, puerto)
    print("\nRepite esto con Wi-Fi y con datos moviles para comparar.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrumpido")
        sys.exit(0)
