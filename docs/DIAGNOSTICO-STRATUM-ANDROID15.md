# Sockets Stratum que abortan en Android 15 (arm64)

Documento de diagnóstico y corrección para la app **Terminal Linux**.
Cubre el error `[Errno 103] Software caused connection abort` al ejecutar
scripts de Python con sockets persistentes (minería Stratum, CKPool, etc.).

---

## 1. El síntoma

El script conecta, recibe `mining.notify` y `mining.set_difficulty`, y a los
pocos segundos muere con `[Errno 103] Software caused connection abort`.
El bucle se reinicia y vuelve a pasar.

`errno 103` es `ECONNABORTED`: lo lanza el **kernel de Android**, no Python y no
el pool. Significa que la conexión TCP fue abortada desde el lado local. El
pool no la cerró.

## 2. Qué NO es el problema

No es falta de pseudoterminal. La app ya ejecuta los comandos dentro de un PTY
real (`app/src/main/assets/ptyrun.py`, con `pty.fork()`), así que `isatty()`
devuelve `True` y el TTY no tiene nada que ver con un socket que se aborta.

Tampoco es falta de `threading` en Python: un bucle con `select()` o con un
hilo de lectura no impide que Android congele el proceso. El sistema operativo
no negocia con el script.

## 3. Causas reales, encontradas en el código

### 3.1 Los comandos interactivos no arrancan el servicio en primer plano

En `MainActivity.runShell()` el comando se lanza con `ProcessBuilder`, pero
**nunca se registra en `Jobs`**. Y `KeepAliveService` solo se activa cuando
`Jobs.runningCount() > 0`:

```java
static void sync(Context ctx) {
    if (Jobs.runningCount() > 0) { ... startForegroundService ... }
    else { ... stopSelf ... }
}
```

Consecuencia: si lanzas el script desde la terminal normal, no hay servicio en
primer plano. Al cambiar de app o apagar la pantalla, Android pasa el proceso a
estado *cached*, lo congela y los sockets abiertos se abortan. Ese es el
`Errno 103` a los pocos segundos.

### 3.2 Corte duro a los 5 minutos

```java
if (!p.waitFor(300, TimeUnit.SECONDS)) {
    p.destroyForcibly();
    sb.append("(cortado: más de 5 minutos)\n");
}
```

Cualquier proceso vivo a los 300 s muere con `SIGKILL`. Para un minero o una
sesión SSH es letal.

### 3.3 No hay WakeLock ni WifiLock

El manifest no declara `WAKE_LOCK` y en todo el proyecto no se adquiere ni
`PowerManager.PARTIAL_WAKE_LOCK` ni `WifiManager.WifiLock`. Con la pantalla
apagada, el SoC y el Wi-Fi entran en ahorro de energía y las conexiones
inactivas se caen.

### 3.4 La salida no se ve en vivo

`runShell` acumula todo en un `StringBuilder` y lo imprime al terminar:

```java
ui.post(() -> println(out.isEmpty() ? "(sin salida)" : out));
```

Por eso el usuario ve el panel "colgado" y no el bucle del script. Además
impide distinguir entre "está trabajando" y "murió".

### 3.5 El *phantom process killer* de Android 12+

Desde Android 12 el sistema mata procesos hijos (los fork por la app, como
`python3` o `sh`) cuando la app no está visible y se superan los límites del
sistema. La app compila con `--target-sdk-version 28`, así que arrastra
comportamientos heredados y queda expuesta a este recorte.

---

## 4. Correcciones, en orden de impacto

### 4.1 Manifest: permisos que faltan

En `app/src/main/AndroidManifest.xml`, añadir:

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

`INTERNET` y `FOREGROUND_SERVICE` ya están. No hace falta
`foregroundServiceType` mientras `targetSdk` sea menor que 34 (ver 4.6).

### 4.2 `KeepAliveService`: mantener la red despierta

Añadir campos y liberarlos siempre en `onDestroy`:

```java
private android.os.PowerManager.WakeLock wake;
private android.net.wifi.WifiManager.WifiLock wifi;

@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    int running = Jobs.runningCount();
    if (running == 0) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }

    if (wake == null) {
        android.os.PowerManager pm =
            (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "linuxterm:trabajos");
        wake.setReferenceCounted(false);
        wake.acquire(12 * 60 * 60 * 1000L); // tope de 12 h
    }
    if (wifi == null) {
        android.net.wifi.WifiManager wm =
            (android.net.wifi.WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                                  "linuxterm:trabajos");
        wifi.setReferenceCounted(false);
        wifi.acquire();
    }
    // ... el resto del método actual (notificación, startForeground) ...
    return START_STICKY;   // en vez de START_NOT_STICKY
}

@Override
public void onDestroy() {
    if (wake != null && wake.isHeld()) wake.release();
    if (wifi != null && wifi.isHeld()) wifi.release();
    super.onDestroy();
}
```

`START_STICKY` hace que Android vuelva a crear el servicio si lo mata.

### 4.3 Todo comando largo debe ser un trabajo

En `MainActivity.runShell()`, sustituir el arranque suelto por el registro en
`Jobs`, que ya escribe a `~/.jobs/<id>.log` y mantiene el servicio vivo:

```java
private void runShell(String line) {
    if (!envReady && !Bootstrap.isInstalled(this)) {
        println("El entorno todavía se está preparando. Espera un momento.");
        return;
    }
    try {
        int id = Jobs.start(this, line);
        println("Trabajo " + id + " en marcha. Ver en vivo: log " + id);
        println("Detener: kill " + id);
    } catch (Exception e) {
        println("No se pudo ejecutar: " + e.getMessage());
    }
}
```

Y en el comando `log`, hacer `tail` incremental en lugar de volcar el archivo
completo, para que se vea el bucle en tiempo real.

Si se prefiere mantener la ejecución en primer plano, entonces hay que llamar a
`KeepAliveService.sync(this)` antes de `pb.start()` y quitar el
`waitFor(300, ...)`. Las dos cosas, no una.

### 4.4 Menos procesos hijos

Cada proceso extra cuenta para el *phantom process killer*. Hoy la ruta es
`python3 ptyrun.py "<line>"` que a su vez lanza `sh -c "<line>"` que a su vez
lanza `python3 script.py`. Son tres.

Si la línea ya es un script de Python, ejecutarlo directo:

```java
private ProcessBuilder shellCommand(String line) {
    File py = new File(Bootstrap.usr(this), "bin/python3");
    if (line.trim().endsWith(".py") && py.exists()) {
        return new ProcessBuilder(py.getAbsolutePath(), line.trim());
    }
    File script = ptyScript();
    if (py.exists() && script != null) {
        return new ProcessBuilder(py.getAbsolutePath(), script.getAbsolutePath(), line);
    }
    return new ProcessBuilder("/system/bin/sh", "-c", line);
}
```

Opcional, solo para pruebas por cable: desactivar el recorte de procesos
fantasma.

```bash
adb shell settings put global settings_enable_monitor_phantom_procs false
```

Esto no debe documentarse como solución para el usuario final, porque requiere
PC y cable.

### 4.5 Lado Python: keepalive de verdad y reconexión

Un socket TCP sin tráfico se puede abortar. Hay que mantenerlo vivo en dos
capas:

```python
import signal, socket

signal.signal(signal.SIGPIPE, signal.SIG_IGN)   # evita muerte por EPIPE

sock = socket.create_connection((host, port), timeout=15)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 30)
sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 15)
sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 4)
sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
```

Y un latido a nivel de protocolo, cada 45 s, aunque no llegue nada:

```python
{"id": next_id(), "method": "mining.ping", "params": []}
```

Tratar `errno 103` (y `104`, `110`) como reconectable, nunca como error fatal:

```python
except OSError as e:
    if e.errno in (103, 104, 110, 32):
        time.sleep(backoff)   # 2, 4, 8, ... hasta 60 s
        continue
    raise
```

Un minero real nunca muere por un socket abortado: reconecta y sigue.

### 4.6 Si algún día se sube `targetSdk` a 34

En `build.sh` está `--target-sdk-version 28`. Al subirlo a 34 hay que añadir,
o el servicio no arranca:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".KeepAliveService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

Y pedir `POST_NOTIFICATIONS` en tiempo de ejecución. Mientras siga en 28, esto
no es necesario, pero tampoco se puede subir sin hacerlo.

---

## 5. Cómo verificar que quedó arreglado

Los scripts de ejemplo viajan dentro del APK y se copian solos a la carpeta de
trabajo del usuario la primera vez que abre la app. La ruta es:

```
/data/user/0/dev.codesnack.linuxterm/files/home/examples/
```

Y se pueden ejecutar con ruta corta desde la terminal, porque el directorio de
trabajo ya es `files/home/`:

```bash
ejemplos                                                  # lista los scripts
python3 examples/prueba_stratum.py <tu_direccion_btc>      # ruta relativa
python3 ~/examples/prueba_stratum.py <tu_direccion_btc>    # ruta absoluta
```

Si en algún momento falta la carpeta, basta con abrir la app de nuevo o escribir
`ejemplos` en la terminal: `Bootstrap.ensureExtras()` la vuelve a copiar desde
el APK, sin pisar archivos que ya existan con otra versión.

Pasos de la prueba:

1. Lanzar un script que imprima la hora cada 5 s y mantenga el socket abierto.
2. Apagar la pantalla y dejarlo **30 minutos**.
3. Revisar que siga vivo: `jobs` debe mostrar el trabajo en marcha.
4. Confirmar que el socket no se cayó, o que si se cayó, reconectó solo y el
   contador de reintentos subió.

Para ver qué hace el sistema con el proceso:

```bash
adb logcat | grep -Ei "phantom|linuxterm|ActivityManager.*kill|freeze"
```

Si aparece `Removing phantom process` o `killProcessGroup`, la causa es 3.5 y
hay que reducir procesos hijos o mantener el servicio en primer plano.

## 6. Resumen

| # | Causa | Arreglo | Impacto |
|---|-------|---------|---------|
| 3.1 | Comandos interactivos sin servicio en primer plano | Registrar en `Jobs` o llamar a `KeepAliveService.sync` | Alto: es la causa del `Errno 103` a los pocos segundos |
| 3.2 | Corte a los 5 minutos | Quitar `waitFor(300)` en comandos largos | Alto: mata el proceso |
| 3.3 | Sin WakeLock ni WifiLock | Permisos y bloqueo mientras hay trabajos | Alto: caídas con pantalla apagada |
| 3.4 | Salida en diferido | Streaming o `log` incremental | Medio: diagnóstico |
| 3.5 | Phantom process killer | Menos procesos hijos, servicio en primer plano | Medio |
| 4.5 | Socket sin latido | `SO_KEEPALIVE` + `mining.ping` + reconexión | Alto: robustez del protocolo |

El orden recomendado de implementación es 3.1, 3.2, 3.3 y luego 4.5. Con esos
cuatro el script sobrevive a la pantalla apagada y a los cambios de red.
