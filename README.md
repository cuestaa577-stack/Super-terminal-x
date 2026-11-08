# Super-terminal-x
Código abierto

## Novedades

- **Terminal con TTY real (PTY):** los comandos se ejecutan dentro de un pseudoterminal (`assets/ptyrun.py`), así que programas como `nano` y `vim` ya no fallan con "Standard output is not a terminal". Limitación: la terminal muestra texto plano; los editores de pantalla completa se manejan mejor con el editor táctil.
- **Trabajos en segundo plano:** `bg <comando>` (por ejemplo `bg python3 servidor.py`), `jobs`, `log <n>` y `kill <n>`. La salida se guarda en `~/.jobs/<n>.log`. Mientras haya trabajos activos, un servicio en primer plano mantiene viva la app. Limitación: si Android cierra la app por completo, los trabajos también se detienen; los registros quedan en disco.
- **Editor de archivos:** botón **Archivos** o el comando `archivos` para explorar la carpeta de inicio, crear y editar archivos. `editar <nombre>` abre un archivo directamente. **Guardar y ejecutar** guarda y lanza `.py` o `.sh` en la terminal.

## Compilar

Requiere el SDK de Android con platform 34 y build-tools 34.0.0 en `~/android-sdk` (o en `ANDROID_HOME`):

    bash build.sh

El APK queda en la carpeta padre como `Terminal-Linux.apk`.
