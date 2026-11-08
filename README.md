# Super-terminal-x
Código abierto

## Descargar el APK

Enlace permanente, siempre apunta a la última versión publicada:

**https://github.com/cuestaa577-stack/Super-terminal-x/releases/latest/download/Terminal-Linux.apk**

Cada versión conserva además su archivo propio en
`https://github.com/cuestaa577-stack/Super-terminal-x/releases/tag/vX.Y.Z`,
por si hace falta volver a una anterior. Todas las versiones van firmadas con
la misma clave de depuración, así que se instalan encima sin desinstalar.

## Novedades

- **Terminal con emulación de pantalla:** `nano`, `vim`, `vi`, `less`, `top`, `htop`, `python3` y `consola` se abren a pantalla completa dentro de un pseudoterminal real (`assets/ptyrun.py`) con emulador VT100/xterm (`TerminalEmulator.java`, `TerminalView.java`). Atrás cierra el programa.
- **Barra de teclas especiales:** ESC, TAB, CTRL y ALT (pulsación para activar y combinar con la siguiente tecla), flechas, HOME, END, RePág/AvPág, SUPR, ^C y ^Z, sobre el teclado virtual (`KeyBar.java`).
- **Editor de archivos:** botón **Archivos** o el comando `archivos` para explorar la carpeta de inicio, crear y editar archivos. `editar <nombre>` abre un archivo directamente. **Guardar y ejecutar** guarda y lanza `.py` o `.sh` en la terminal.

## Compilar

Requiere el SDK de Android con platform 34 y build-tools 34.0.0 en `~/android-sdk` (o en `ANDROID_HOME`):

    bash build.sh

El APK queda en la carpeta padre como `Terminal-Linux.apk`.
