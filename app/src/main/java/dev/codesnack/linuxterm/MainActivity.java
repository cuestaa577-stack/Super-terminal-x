package dev.codesnack.linuxterm;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int TEXT = Color.rgb(220, 230, 220);
    private static final int BG = Color.rgb(16, 18, 20);
    private static final int PROMPT = Color.rgb(90, 200, 255);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView output;
    private ScrollView scroll;
    private TextView monitorView;
    private EditText input;
    private Button stopButton;
    private Runnable monitorTick;
    private int monitorSeconds = 0;
    private final List<String> history = new ArrayList<>();
    private int historyPos = -1;
    private volatile boolean envReady = false;
    private Process server;
    private int serverPort = 8080;

    static final String ENV_HELP =
            "Entorno Linux (python3, pip, vim, nano, sqlite3):\n"
            + "  python3 archivo.py   ejecutar Python\n"
            + "  pip install paquete  instalar paquetes (necesita internet)\n"
            + "  vim / nano archivo   editores (ver aviso en el informe)\n"
            + "  sqlite3 base.db     base de datos ligera\n"
            + "  servidor [puerto]   servidor HTTP en 127.0.0.1 (por defecto 8080)\n"
            + "  servidor parar      detener el servidor\n"
            + "  probar [puerto] [ruta]  pedir una URL local y mostrar la respuesta\n"
            + "  entorno             estado de la instalación";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        output = new TextView(this);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextColor(TEXT);
        output.setTextSize(13);
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());

        scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        monitorView = new TextView(this);
        monitorView.setTypeface(android.graphics.Typeface.MONOSPACE);
        monitorView.setTextColor(Color.rgb(255, 220, 120));
        monitorView.setTextSize(12);
        monitorView.setBackgroundColor(Color.rgb(30, 32, 36));
        monitorView.setPadding(dp(6), dp(6), dp(6), dp(6));
        monitorView.setVisibility(View.GONE);
        root.addView(monitorView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        TextView prompt = new TextView(this);
        prompt.setText("$ ");
        prompt.setTypeface(android.graphics.Typeface.MONOSPACE);
        prompt.setTextColor(PROMPT);
        prompt.setTextSize(15);
        row.addView(prompt);

        input = new EditText(this);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.GRAY);
        input.setHint("escribe un comando, p. ej. diag");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setOnEditorActionListener((v, actionId, event) -> {
            submit();
            return true;
        });
        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                submit();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                recall(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                recall(1);
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));

        Button run = new Button(this);
        run.setText("Ejecutar");
        run.setOnClickListener(v -> submit());
        row.addView(run);

        stopButton = new Button(this);
        stopButton.setText("Parar");
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> stopMonitor());
        row.addView(stopButton);

        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        println("Terminal Linux " + Syscmd.VERSION + " — escribe help para ver los comandos.");
        println(Syscmd.HELP);
        println("");
        installEnvironment();
    }

    private void installEnvironment() {
        if (!Bootstrap.isArm64()) {
            println("Aviso: este teléfono no es arm64-v8a. El entorno Linux no funcionará aquí.");
            return;
        }
        println("Preparando el entorno Linux (solo la primera vez)...");
        new Thread(() -> {
            try {
                Bootstrap.ensureInstalled(this);
                envReady = true;
                ui.post(() -> println("Entorno listo: python3, pip, vim, nano, sqlite3. Escribe entorno."));
            } catch (Exception e) {
                ui.post(() -> println("No se pudo preparar el entorno: " + e.getMessage()));
            }
        }, "bootstrap").start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void println(String text) {
        output.append(text + "\n");
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void recall(int dir) {
        if (history.isEmpty()) return;
        historyPos = Math.max(0, Math.min(history.size(), (historyPos < 0 ? history.size() : historyPos) + dir));
        input.setText(historyPos < history.size() ? history.get(historyPos) : "");
        input.setSelection(input.getText().length());
    }

    private void submit() {
        String line = input.getText().toString().trim();
        input.setText("");
        if (line.isEmpty()) return;
        history.add(line);
        historyPos = -1;
        println("$ " + line);
        dispatch(line);
    }

    private void dispatch(String line) {
        String[] parts = line.split("\\s+");
        String name = parts[0].toLowerCase(java.util.Locale.ROOT);
        String[] args = java.util.Arrays.copyOfRange(parts, 1, parts.length);

        switch (name) {
            case "help":
            case "ayuda":
            case "?":
                println(Syscmd.HELP);
                println(ENV_HELP);
                return;
            case "entorno":
                println(envStatus());
                return;
            case "servidor":
                startServer(args);
                return;
            case "probar":
                probe(args);
                return;
            case "python":
                runShell("python3 " + rest(line));
                return;
            case "pip":
                runShell("python3 -m pip " + rest(line));
                return;
            case "limpiar":
            case "clear":
                output.setText("");
                return;
            case "diag":
                runBackground(() -> Syscmd.diag(deviceInfo(), getFilesDir()));
                return;
            case "procs":
            case "ps":
                runBackground(() -> Syscmd.procs(args));
                return;
            case "mon":
                startMonitor(args);
                return;
            case "parar":
            case "stop":
                stopMonitor();
                return;
            default:
                runShell(line);
        }
    }

    private String deviceInfo() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
    }

    private void runBackground(java.util.function.Supplier<String> work) {
        new Thread(() -> {
            String out;
            try {
                out = work.get();
            } catch (RuntimeException e) {
                out = "Error: " + e.getMessage();
            }
            String text = out;
            ui.post(() -> println(text));
        }, "syscmd").start();
    }

    private void startMonitor(String[] args) {
        int seconds = 2;
        if (args.length > 0) {
            try {
                seconds = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException e) {
                println("Uso: mon [segundos]");
                return;
            }
        }
        stopMonitor();
        monitorSeconds = seconds;
        Syscmd.monReset();
        monitorView.setVisibility(View.VISIBLE);
        stopButton.setVisibility(View.VISIBLE);
        println("Monitor cada " + seconds + " s. Pulsa Parar o escribe parar.");
        monitorTick = this::tickMonitor;
        ui.post(monitorTick);
    }

    private void tickMonitor() {
        final Runnable me = monitorTick;
        if (me == null) return;
        new Thread(() -> {
            String frame = Syscmd.monFrame();
            ui.post(() -> {
                if (monitorTick == me) monitorView.setText(frame);
            });
        }, "mon").start();
        ui.postDelayed(me, monitorSeconds * 1000L);
    }

    private void stopMonitor() {
        if (monitorTick == null) return;
        ui.removeCallbacks(monitorTick);
        monitorTick = null;
        monitorView.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);
        println("Monitor parado.");
    }

    private static String rest(String line) {
        int sp = line.indexOf(' ');
        return sp < 0 ? "" : line.substring(sp + 1);
    }

    private String envStatus() {
        StringBuilder b = new StringBuilder();
        b.append("ABI: ").append(android.os.Build.SUPPORTED_ABIS[0]).append('\n');
        b.append("Instalado: ").append(Bootstrap.isInstalled(this) ? "sí (" + Bootstrap.VERSION + ")" : "no").append('\n');
        b.append("Inicio: ").append(Bootstrap.home(this).getAbsolutePath()).append('\n');
        b.append("Servidor: ").append(server != null && server.isAlive() ? "activo en puerto " + serverPort : "parado");
        return b.toString();
    }

    private void startServer(String[] args) {
        if (args.length > 0 && args[0].equals("parar")) {
            if (server != null && server.isAlive()) {
                server.destroy();
                println("Servidor parado.");
            } else {
                println("No hay servidor activo.");
            }
            server = null;
            return;
        }
        if (!envReady) {
            println("El entorno todavía se está preparando. Espera un momento.");
            return;
        }
        if (server != null && server.isAlive()) {
            println("Ya hay un servidor en el puerto " + serverPort + ". Escribe servidor parar.");
            return;
        }
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                println("Uso: servidor [puerto] | servidor parar");
                return;
            }
        }
        if (port < 1024 || port > 65535) {
            println("El puerto debe estar entre 1024 y 65535.");
            return;
        }
        try {
            java.util.Map<String, String> env = Bootstrap.env(this);
            File home = Bootstrap.home(this);
            ProcessBuilder pb = new ProcessBuilder(
                    new File(Bootstrap.usr(this), "bin/python3").getAbsolutePath(),
                    "-m", "http.server", String.valueOf(port), "--bind", "127.0.0.1", "--directory", home.getAbsolutePath());
            pb.environment().putAll(env);
            pb.directory(home);
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(home, "servidor.log"));
            server = pb.start();
            serverPort = port;
            println("Servidor HTTP en http://127.0.0.1:" + port + " con la carpeta de inicio.");
            println("Registro en servidor.log. Pruébalo con: probar " + port);
        } catch (IOException e) {
            println("No se pudo arrancar el servidor: " + e.getMessage());
        }
    }

    private void probe(String[] args) {
        int port = serverPort;
        String path = "/";
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                println("Uso: probar [puerto] [ruta]");
                return;
            }
        }
        if (args.length > 1) path = args[1].startsWith("/") ? args[1] : "/" + args[1];
        final int p = port;
        final String url = "http://127.0.0.1:" + p + path;
        new Thread(() -> {
            String out;
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                int code = con.getResponseCode();
                InputStream body = code >= 400 ? con.getErrorStream() : con.getInputStream();
                String text = "";
                if (body != null) {
                    byte[] buf = new byte[1000];
                    int n = body.read(buf);
                    text = n > 0 ? new String(buf, 0, n, "UTF-8") : "";
                    body.close();
                }
                out = "GET " + url + " -> HTTP " + code + "\n" + text;
            } catch (Exception e) {
                out = "No responde " + url + ": " + e.getMessage() + "\n(¿está activo el servidor? escribe servidor)";
            }
            String text = out;
            ui.post(() -> println(text));
        }, "probar").start();
    }

    /** Cualquier otro comando: se ejecuta con la shell de Android, con límite de 5 minutos. */
    private void runShell(String line) {
        if (!envReady && !Bootstrap.isInstalled(this)) {
            println("El entorno todavía se está preparando. Espera un momento.");
            return;
        }
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", line);
                pb.environment().putAll(Bootstrap.env(this));
                pb.directory(Bootstrap.home(this));
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                Thread reader = new Thread(() -> {
                    try {
                        String l;
                        while ((l = r.readLine()) != null) {
                            synchronized (sb) {
                                sb.append(l).append('\n');
                            }
                        }
                    } catch (java.io.IOException ignored) {
                    }
                });
                reader.start();
                if (!p.waitFor(300, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    sb.append("(cortado: más de 5 minutos)\n");
                }
                reader.join(1000);
                int code = p.isAlive() ? -1 : p.exitValue();
                if (code != 0 && code != -1) sb.append("(código de salida ").append(code).append(")");
            } catch (Exception e) {
                sb.append("No se pudo ejecutar: ").append(e.getMessage());
            }
            String out;
            synchronized (sb) {
                out = Syscmd.trimEnd(sb.toString());
            }
            ui.post(() -> println(out.isEmpty() ? "(sin salida)" : out));
        }, "shell").start();
    }
}
