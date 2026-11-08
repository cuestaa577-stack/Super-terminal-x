package dev.codesnack.linuxterm;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gestor de procesos en segundo plano (comandos: bg, jobs, log, kill).
 *
 * Cada trabajo escribe su salida en ~/.jobs/<id>.log, así que puedes leerla
 * aunque el comando ya haya terminado. Mientras haya trabajos activos, la app
 * se mantiene en primer plano con KeepAliveService para que Android no los mate.
 */
final class Jobs {
    private static final Map<Integer, Job> JOBS = new LinkedHashMap<>();
    private static int nextId = 1;
    /** Comandos de la terminal que siguen vivos aunque no sean trabajos `bg`. */
    private static int foreground = 0;

    static final class Job {
        final int id;
        final String cmd;
        final Process proc;
        final File log;
        final long started;

        Job(int id, String cmd, Process proc, File log) {
            this.id = id;
            this.cmd = cmd;
            this.proc = proc;
            this.log = log;
            this.started = System.currentTimeMillis();
        }
    }

    private Jobs() {
    }

    static File dir(Context ctx) {
        File d = new File(Bootstrap.home(ctx), ".jobs");
        d.mkdirs();
        return d;
    }

    /** Arranca un comando en segundo plano y devuelve su número de trabajo. */
    static synchronized int start(Context ctx, String cmd) throws IOException {
        int id = nextId++;
        File log = new File(dir(ctx), id + ".log");
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
        pb.environment().putAll(Bootstrap.env(ctx));
        pb.directory(Bootstrap.home(ctx));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        Process p = pb.start();
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        JOBS.put(id, new Job(id, cmd, p, log));
        KeepAliveService.sync(ctx);

        final Context app = ctx.getApplicationContext();
        Thread watcher = new Thread(() -> {
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
            }
            try {
                String line = "\n[trabajo " + id + " terminado con código " + p.exitValue() + "]\n";
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(log, true)) {
                    out.write(line.getBytes("UTF-8"));
                }
            } catch (IOException ignored) {
            }
            KeepAliveService.sync(app);
        }, "job-" + id);
        watcher.setDaemon(true);
        watcher.start();
        return id;
    }

    static synchronized int runningCount() {
        int n = foreground;
        for (Job j : JOBS.values()) {
            if (j.proc.isAlive()) n++;
        }
        return n;
    }

    /**
     * Marca que hay un comando corriendo en la terminal. Mientras el contador
     * esté por encima de cero, KeepAliveService mantiene el proceso en primer
     * plano y con la red despierta, que es lo que evita los cortes de socket
     * (Errno 103) al apagar la pantalla o cambiar de app.
     */
    static synchronized void beginForeground() {
        foreground++;
    }

    static synchronized void endForeground() {
        if (foreground > 0) foreground--;
    }

    static synchronized String list() {
        if (JOBS.isEmpty()) return "No hay trabajos en segundo plano. Usa: bg <comando>";
        StringBuilder b = new StringBuilder();
        for (Job j : JOBS.values()) {
            b.append(j.id).append("  ");
            if (j.proc.isAlive()) {
                b.append("activo");
            } else {
                b.append("terminado (código ").append(j.proc.exitValue()).append(")");
            }
            b.append("  ").append(j.cmd).append('\n');
        }
        return b.toString().trim();
    }

    /** Devuelve las últimas {@code lines} líneas del registro del trabajo. */
    static synchronized String tail(int id, int lines) {
        Job j = JOBS.get(id);
        if (j == null) return "No existe el trabajo " + id + ". Usa: jobs";
        try {
            byte[] bytes = new byte[(int) j.log.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(j.log)) {
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            String[] all = new String(bytes, "UTF-8").split("\n");
            int from = Math.max(0, all.length - lines);
            StringBuilder b = new StringBuilder();
            for (int i = from; i < all.length; i++) b.append(all[i]).append('\n');
            return b.length() == 0 ? "(sin salida todavía)" : b.toString().trim();
        } catch (IOException e) {
            return "No se pudo leer el registro: " + e.getMessage();
        }
    }

    static synchronized String kill(int id) {
        Job j = JOBS.get(id);
        if (j == null) return "No existe el trabajo " + id + ". Usa: jobs";
        if (!j.proc.isAlive()) return "El trabajo " + id + " ya había terminado.";
        j.proc.destroy();
        return "Trabajo " + id + " detenido.";
    }
}
