#!/usr/bin/env python3
"""
Prepara el entorno Linux (Python 3 + pip, Vim, Nano, SQLite) para aarch64 a partir de
los paquetes de Termux, y lo guarda como app/src/main/assets/usr.tar.gz.

Los binarios quedan con el prefijo /data/data/com.termux/files/usr. La app los usa
con PYTHONHOME, LD_LIBRARY_PATH, VIMRUNTIME y TERMINFO apuntando a su propia carpeta.

Uso: python3 tools/make_bundle.py   (necesita internet)
"""
import gzip
import io
import os
import re
import shutil
import subprocess
import tarfile
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
WORK = os.path.join(ROOT, "build", "bundle")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "usr.tar.gz")
PREFIX_IN_DEBS = "data/data/com.termux/files/usr"
ROOTS = ["python", "python-pip", "vim", "nano", "sqlite"]
ARCH = "aarch64"
# Partes que no hacen falta en el teléfono
SKIP = re.compile(r"(^|/)(include|share/doc|share/man|share/info|share/locale|__pycache__|test|tests)(/|$)")


def fetch(url, dest):
    if not os.path.exists(dest):
        urllib.request.urlretrieve(url, dest)
    return dest


def load_index():
    raw = urllib.request.urlopen(f"{REPO}/dists/stable/main/binary-{ARCH}/Packages.gz").read()
    text = gzip.decompress(raw).decode("utf-8", "replace")
    pk = {}
    for block in text.split("\n\n"):
        name = re.search(r"^Package: (.+)$", block, re.M)
        if not name:
            continue
        fname = re.search(r"^Filename: (.+)$", block, re.M)
        deps = re.search(r"^Depends: (.+)$", block, re.M)
        dep_names = []
        if deps:
            for part in deps.group(1).split(","):
                dep_names.append(part.strip().split(" ")[0].split("|")[0].strip())
        pk[name.group(1)] = (fname.group(1) if fname else None, dep_names)
    return pk


def resolve(pk):
    seen, stack = set(), list(ROOTS)
    while stack:
        p = stack.pop()
        if p in seen or p not in pk:
            continue
        seen.add(p)
        stack += pk[p][1]
    return sorted(p for p in seen if pk[p][0])


def extract_deb(deb, dest):
    # Un .deb es un "ar" con data.tar.(xz|zst|gz)
    subprocess.run(["ar", "x", os.path.abspath(deb)], cwd=WORK, check=True)
    data = next(f for f in os.listdir(WORK) if f.startswith("data.tar"))
    subprocess.run(["tar", "-xf", os.path.join(WORK, data), "-C", dest], check=True)
    for f in os.listdir(WORK):
        if f.startswith(("data.tar", "control.tar", "debian-binary")):
            os.remove(os.path.join(WORK, f))


def main():
    shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(WORK)
    pk = load_index()
    pkgs = resolve(pk)
    print("paquetes:", " ".join(pkgs))
    prefix = os.path.join(WORK, "prefix")
    os.makedirs(prefix)
    for p in pkgs:
        deb = fetch(f"{REPO}/{pk[p][0]}", os.path.join(WORK, os.path.basename(pk[p][0])))
        extract_deb(deb, prefix)
        os.remove(deb)

    usr = os.path.join(prefix, PREFIX_IN_DEBS)
    # Los enlaces "vim" y "vi" los crea el script de instalación de Termux
    bin_dir = os.path.join(usr, "bin")
    for link in ("vim", "vi"):
        target = os.path.join(bin_dir, link)
        if not os.path.lexists(target):
            os.symlink("../libexec/vim/vim", target)

    # Bajar el tamaño: quitar cabeceras, documentación y cachés
    for dirpath, dirnames, filenames in os.walk(usr, topdown=False):
        rel = os.path.relpath(dirpath, usr)
        for f in filenames:
            r = os.path.normpath(os.path.join(rel, f))
            if SKIP.search(r):
                os.remove(os.path.join(dirpath, f))
        for d in dirnames:
            r = os.path.normpath(os.path.join(rel, d))
            if SKIP.search(r + "/") and os.path.isdir(os.path.join(dirpath, d)):
                if not os.listdir(os.path.join(dirpath, d)) or SKIP.search(r + "/"):
                    shutil.rmtree(os.path.join(dirpath, d), ignore_errors=True)

    # El paquete empieza en "usr/" para que la app lo extraiga en su carpeta
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with tarfile.open(OUT, "w:gz", compresslevel=9) as tar:
        tar.add(usr, arcname="usr")
    print("creado", OUT, round(os.path.getsize(OUT) / 1e6, 1), "MB")


if __name__ == "__main__":
    main()
