package dev.codesnack.linuxterm;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Instala el entorno Linux (python, pip, vim, nano, sqlite) en el almacenamiento
 * privado de la app y arma el entorno de los procesos.
 *
 * Aislamiento: todo vive en files/ de la app. No se toca el almacenamiento compartido.
 */
public final class Bootstrap {
    /** Súbelo cuando cambie usr.tar.gz para reinstalar el entorno. */
    static final String VERSION = "2026.11.08-1";
    static final String ABI = "arm64-v8a";

    private Bootstrap() {
    }

    public static boolean isArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (ABI.equals(abi)) return true;
        }
        return false;
    }

    public static File home(Context ctx) {
        return new File(ctx.getFilesDir(), "home");
    }

    public static File usr(Context ctx) {
        return new File(ctx.getFilesDir(), "usr");
    }

    public static boolean isInstalled(Context ctx) {
        File marker = new File(usr(ctx), ".version");
        if (!marker.isFile()) return false;
        try (InputStream in = new java.io.FileInputStream(marker)) {
            byte[] b = new byte[64];
            int n = in.read(b);
            return n > 0 && new String(b, 0, n, "UTF-8").trim().equals(VERSION);
        } catch (IOException e) {
            return false;
        }
    }

    /** Instala el entorno si hace falta. Bloqueante: llamar desde un hilo de fondo. */
    public static synchronized void ensureInstalled(Context ctx) throws IOException {
        if (!isArm64()) throw new IOException("Este entorno necesita un teléfono de 64 bits (arm64-v8a).");
        home(ctx).mkdirs();
        new File(ctx.getFilesDir(), "tmp").mkdirs();
        // Los ejemplos viven en el APK y se copian a ~/examples. Se hace siempre,
        // también cuando usr/ ya estaba instalado de una versión anterior.
        ensureExtras(ctx);
        if (isInstalled(ctx)) return;

        // Instalación limpia: el paquete trae su propia carpeta "usr/" dentro de files/
        deleteRecursive(usr(ctx));
        try (InputStream asset = ctx.getAssets().open("usr.tar.gz")) {
            TarExtractor.extractGzip(asset, ctx.getFilesDir(), (target, link) -> {
                try {
                    Os.symlink(target, link.getPath());
                } catch (android.system.ErrnoException e) {
                    throw new IOException("No se pudo crear el enlace " + link + ": " + e.getMessage(), e);
                }
            });
        }
        if (!usr(ctx).isDirectory()) {
            throw new IOException("El paquete no contiene la carpeta usr");
        }
        try (FileOutputStream out = new FileOutputStream(new File(usr(ctx), ".version"))) {
            out.write(VERSION.getBytes("UTF-8"));
        }
    }

    /** Versión de los ejemplos y utilidades que viajan dentro del APK. */
    private static final String EXTRAS_VERSION = "6";

    /**
     * Copia assets/examples/ a la carpeta de trabajo del usuario (~/examples) y
     * deja un lanzador de cada script en la raíz de ~ y en usr/bin, para que
     * funcione desde cualquier ruta inicial:
     *   python3 prueba_stratum.py <direccion>      (desde ~, sin cd examples)
     *   prueba_stratum <direccion>                 (desde cualquier carpeta)
     * Es idempotente: solo reescribe cuando cambia EXTRAS_VERSION, así no pisa
     * los archivos que el usuario haya editado.
     */
    public static void ensureExtras(Context ctx) {
        File dest = new File(home(ctx), "examples");
        File marker = new File(dest, ".version");
        if (marker.isFile()) {
            try (InputStream in = new java.io.FileInputStream(marker)) {
                byte[] b = new byte[32];
                int n = in.read(b);
                if (n > 0 && new String(b, 0, n, "UTF-8").trim().equals(EXTRAS_VERSION)) return;
            } catch (IOException ignored) {
                // Sigue y vuelve a copiar.
            }
        }
        if (!dest.isDirectory() && !dest.mkdirs()) return;
        try {
            String[] names = ctx.getAssets().list("examples");
            if (names == null || names.length == 0) return;
            for (String name : names) {
                File out = new File(dest, name);
                try (InputStream in = ctx.getAssets().open("examples/" + name);
                     FileOutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                out.setReadable(true, false);
                out.setExecutable(true, false);
            }
            instalarLanzadores(ctx, dest);
            try (FileOutputStream m = new FileOutputStream(marker)) {
                m.write(EXTRAS_VERSION.getBytes("UTF-8"));
            }
        } catch (IOException ignored) {
            // Un APK sin ejemplos no debe romper el arranque del entorno.
        }
    }

    /**
     * Deja cada ejemplo ejecutable desde la raíz de ~ y desde usr/bin.
     *
     * En ~ se escribe un lanzador con el mismo nombre del script, que llama al
     * archivo real de ~/examples con los mismos argumentos. Así
     * `python3 prueba_stratum.py ...` funciona recién abierto el terminal, sin
     * `cd` previo, y sigue habiendo una sola copia del código.
     */
    private static void instalarLanzadores(Context ctx, File ejemplos) {
        String[] scripts = ejemplos.list();
        if (scripts == null) return;
        File bin = new File(usr(ctx), "bin");
        bin.mkdirs();
        for (String script : scripts) {
            if (!script.endsWith(".py") || script.equals(".version")) continue;
            String base = script.substring(0, script.length() - 3);

            String lanzador = "#!/usr/bin/env python3\n"
                    + "# Lanzador: ejecuta el ejemplo real de ~/examples sin tener que entrar ahí.\n"
                    + "import os, runpy, sys\n\n"
                    + "_ruta = os.path.join(os.path.dirname(os.path.abspath(__file__)), "
                    + "\"examples\", \"" + script + "\")\n"
                    + "if not os.path.isfile(_ruta):\n"
                    + "    sys.exit(\"no encuentro examples/" + script
                    + "; reinstala la app o revisa 'ejemplos'\")\n"
                    + "sys.argv[0] = _ruta\n"
                    + "runpy.run_path(_ruta, run_name=\"__main__\")\n";

            escribir(new File(home(ctx), script), lanzador);

            String atajo = "#!/system/bin/sh\n"
                    + "# Atajo: ejecuta ~/examples/" + script + " desde cualquier carpeta.\n"
                    + "exec python3 \"$HOME/examples/" + script + "\" \"$@\"\n";
            escribir(new File(bin, base), atajo);
        }
    }

    /** Crea o reemplaza un archivo de texto pequeño. */
    private static void escribir(File destino, String texto) {
        try (FileOutputStream out = new FileOutputStream(destino)) {
            out.write(texto.getBytes("UTF-8"));
        } catch (IOException ignored) {
            return;   // un extra que no se pudo escribir no debe romper el arranque
        }
        destino.setReadable(true, false);
        destino.setExecutable(true, false);
    }

    /**
     * Texto que se muestra al abrir la terminal, al estilo de Termux pero con
     * la identidad de la app. Vive en ~/.bienvenida, así que se puede editar
     * sin recompilar: si el archivo ya existe no se toca.
     */
    private static final String BIENVENIDA =
            "\033[1;32mWelcome Linux\033[0m\n"
            + "Terminal Linux, tu entorno local.\n"
            + "\n"
            + "Docs e incidencias:\n"
            + "  github.com/cuestaa577-stack/Super-terminal-x\n"
            + "\n"
            + "Comandos utiles:\n"
            + "  help                 lista de comandos\n"
            + "  ejemplos             scripts de prueba\n"
            + "  nano / vim archivo   editar texto\n"
            + "  python3 script.py    ejecutar Python\n"
            + "\n"
            + "Probar la pool:\n"
            + "  python3 prueba_stratum.py <direccion_btc>\n"
            + "\n";

    /** Archivo con la bienvenida que se imprime al abrir la terminal. */
    public static File bienvenida(Context ctx) {
        return new File(home(ctx), ".bienvenida");
    }

    /** Crea ~/.bienvenida si no existe. Nunca pisa uno editado por el usuario. */
    public static void asegurarBienvenida(Context ctx) {
        File f = bienvenida(ctx);
        if (f.isFile()) return;
        home(ctx).mkdirs();
        escribir(f, BIENVENIDA);
    }

    /** Variables de entorno de los procesos del entorno Linux. */
    public static Map<String, String> env(Context ctx) {
        File usr = usr(ctx);
        File home = home(ctx);
        File tmp = new File(ctx.getFilesDir(), "tmp");
        Map<String, String> e = new HashMap<>();
        e.put("HOME", home.getAbsolutePath());
        e.put("PREFIX", usr.getAbsolutePath());
        e.put("PATH", new File(usr, "bin").getAbsolutePath() + ":/system/bin:/system/xbin");
        e.put("LD_LIBRARY_PATH", new File(usr, "lib").getAbsolutePath());
        e.put("PYTHONHOME", usr.getAbsolutePath());
        e.put("PYTHONDONTWRITEBYTECODE", "1");
        e.put("VIMRUNTIME", new File(usr, "share/vim/vim92").getAbsolutePath());
        e.put("TERMINFO", new File(usr, "share/terminfo").getAbsolutePath());
        e.put("TERM", "xterm-256color");
        e.put("TMPDIR", tmp.getAbsolutePath());
        e.put("LANG", "C.UTF-8");
        e.put("SSL_CERT_FILE", new File(usr, "etc/tls/cert.pem").getAbsolutePath());
        e.put("REQUESTS_CA_BUNDLE", new File(usr, "etc/tls/cert.pem").getAbsolutePath());
        return e;
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
