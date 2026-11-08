package dev.codesnack.linuxterm;

import android.os.Handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Un programa interactivo dentro de un PTY real (ptyrun.py): nano, vim, python, shell...
 * La salida llega a la pantalla de la app (TerminalView) y las teclas se escriben en el PTY.
 */
final class PtySession {
    interface Listener {
        void onOutput(String text);

        void onExit(int code);
    }

    private final Process process;
    private final OutputStream stdin;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Handler ui;
    private final Listener listener;
    private volatile boolean closed;

    private PtySession(Process process, Handler ui, Listener listener) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.ui = ui;
        this.listener = listener;
    }

    static PtySession start(File python, File ptyScript, Map<String, String> env, File dir, String cmd,
                            int rows, int cols, Handler ui, Listener listener) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(python.getAbsolutePath(), ptyScript.getAbsolutePath(), cmd);
        pb.environment().putAll(env);
        pb.environment().put("CS_ROWS", String.valueOf(rows));
        pb.environment().put("CS_COLS", String.valueOf(cols));
        pb.directory(dir);
        pb.redirectErrorStream(true);
        PtySession s = new PtySession(pb.start(), ui, listener);
        s.startReader();
        return s;
    }

    private void startReader() {
        Thread t = new Thread(() -> {
            int code = -1;
            try (Reader r = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    String chunk = new String(buf, 0, n);
                    ui.post(() -> listener.onOutput(chunk));
                }
            } catch (IOException ignored) {
            }
            try {
                code = process.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            closed = true;
            writer.shutdownNow();
            final int finalCode = code;
            ui.post(() -> listener.onExit(finalCode));
        }, "pty-reader");
        t.setDaemon(true);
        t.start();
    }

    boolean isRunning() {
        return !closed;
    }

    /** Envía teclas (texto o secuencias ESC) al programa. */
    void write(String text) {
        if (closed) return;
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writer.execute(() -> {
            try {
                stdin.write(data);
                stdin.flush();
            } catch (IOException ignored) {
            }
        });
    }

    /** Cambia el tamaño del PTY (ptyrun.py lo aplica con ioctl TIOCSWINSZ). */
    void resize(int rows, int cols) {
        write((char) 0 + "R" + rows + ";" + cols + (char) 0);
    }

    void close() {
        closed = true;
        process.destroy();
        writer.shutdownNow();
    }
}
