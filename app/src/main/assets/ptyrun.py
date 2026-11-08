#!/usr/bin/env python3
"""Ejecuta un comando dentro de un pseudoterminal (PTY) real.

Uso: python3 ptyrun.py "<comando de shell>"

El comando ve un TTY de verdad (isatty() == True), así que nano, vim y
otros programas de pantalla completa dejan de quejarse de que la salida
no es una terminal. La entrada de la app llega por stdin y la salida del
PTY se devuelve por stdout. El código de salida del comando se devuelve
como código de salida de este script.
"""
import fcntl
import os
import pty
import re
import select
import struct
import sys
import termios

LINES = int(os.environ.get("CS_ROWS", "40"))
COLUMNS = int(os.environ.get("CS_COLS", "120"))

# La app avisa del tamaño de pantalla con \0R<filas>;<columnas>\0 por la entrada.
RESIZE = re.compile(rb"\x00R(\d+);(\d+)\x00")


def set_size(fd, rows, cols):
    try:
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    except OSError:
        pass


def main():
    if len(sys.argv) < 2:
        sys.stderr.write("uso: ptyrun.py <comando>\n")
        return 2
    cmd = sys.argv[1]

    pid, master = pty.fork()
    if pid == 0:
        # Proceso hijo: ya tiene el PTY como terminal de control.
        os.environ.setdefault("TERM", "xterm-256color")
        os.environ.setdefault("HOME", os.getcwd())
        shell = "/system/bin/sh" if os.path.exists("/system/bin/sh") else "/bin/sh"
        try:
            os.execv(shell, ["sh", "-c", cmd])
        except OSError as e:
            sys.stderr.write("No se pudo ejecutar la shell: %s\n" % e)
            os._exit(127)

    # Tamaño de pantalla inicial (la app lo puede cambiar después).
    set_size(master, LINES, COLUMNS)

    out = sys.stdout.buffer
    stdin_open = True
    while True:
        watch = [master]
        if stdin_open:
            watch.append(0)
        try:
            ready, _, _ = select.select(watch, [], [])
        except InterruptedError:
            continue

        if 0 in ready:
            data = os.read(0, 4096)
            if not data:
                stdin_open = False
            else:
                for m in RESIZE.finditer(data):
                    set_size(master, int(m.group(1)), int(m.group(2)))
                data = RESIZE.sub(b"", data)
                try:
                    if data:
                        os.write(master, data)
                except OSError:
                    stdin_open = False

        if master in ready:
            try:
                data = os.read(master, 4096)
            except OSError:
                break  # En Linux, leer el PTY da error al cerrar el hijo.
            if not data:
                break
            out.write(data)
            out.flush()

    _, status = os.waitpid(pid, 0)
    if os.WIFEXITED(status):
        return os.WEXITSTATUS(status)
    if os.WIFSIGNALED(status):
        return 128 + os.WTERMSIG(status)
    return 1


if __name__ == "__main__":
    sys.exit(main())
