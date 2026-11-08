package dev.codesnack.linuxterm;

import android.app.Activity;
import android.content.Intent;
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
import java.io.FileOutputStream;
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

    /** Secuencias de color y control de cursor que devuelve un TTY. */
    private static final java.util.regex.Pattern ANSI =
            java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[()][0-9A-Za-z]|\u001B[=>]");
    private static final int REQ_EDIT = 10;
    private static final int REQ_FILES = 11;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView output;
    private ScrollView scroll;
    private TextView monitorView;
    private EditText input;
    private Button stopButton;
    private View inputRow;
    private KeyBar keyBar;
    private LinearLayout termPanel;
    private TerminalView term;
    private PtySession session;
    private String pendingCmd;
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
            + "  archivos            explorador y editor de la carpeta de inicio\n"
            + "  editar archivo.py   abrir un archivo en el editor táctil\n"
            + "  consola             shell interactiva a pantalla completa\n"
            + "  nano / vim / less / top / python3 (sin archivo)  abren el programa a pantalla completa\n"
            + "                      (barra de teclas: ESC, TAB, CTRL, ALT, flechas; Atrás lo cierra)\n"
            + "  bg <comando>        ejecutar en segundo plano (sigue aunque termine el comando)\n"
            + "  jobs                ver los trabajos en segundo plano\n"
            + "  log <n>             ver la salida del trabajo n\n"
            + "  kill <n>            detener el trabajo n\n"
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

        term = new TerminalView(this);
        term.setListener(new TerminalView.Listener() {
            @Override
            public void onSize(int rows, int cols) {
                if (session != null) session.resize(rows, cols);
                else if (pendingCmd != null) startPending();
            }

            @Override
            public void onInput(String seq) {
                if (session != null) session.write(seq);
            }
        });
        termPanel = new LinearLayout(this);
        termPanel.setOrientation(LinearLayout.VERTICAL);
        termPanel.setVisibility(View.GONE);
        termPanel.addView(term, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(termPanel, new LinearLayout.LayoutParams(-1, 0, 1f));

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

        Button files = new Button(this);
        files.setText("Archivos");
        files.setOnClickListener(v -> openFiles());
        row.addView(files);

        stopButton = new Button(this);
        stopButton.setText("Parar");
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> stopMonitor());
        row.addView(stopButton);

        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        inputRow = row;

        // Barra de teclas siempre visible, pegada al teclado. En la consola
        // mueve el cursor y el historial; dentro de la terminal, manda al PTY.
        keyBar = new KeyBar(this, teclas);
        root.addView(keyBar, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);

        println("Terminal Linux " + versionInstalada() + " — escribe help para ver los comandos.");
        println(Syscmd.HELP);
        println("");
        installEnvironment();
    }

    /** Qué hace cada tecla de la barra según dónde esté el usuario. */
    private final KeyBar.Objetivo teclas = new KeyBar.Objetivo() {
        @Override
        public boolean terminalActivo() {
            return session != null && termPanel.getVisibility() == View.VISIBLE;
        }

        @Override
        public void navegar(String tecla) {
            if (terminalActivo()) {
                term.sendKey(secuencia(tecla));
                return;
            }
            // Consola de comandos: mover el cursor, el historial o la salida.
            int largo = input.getText().length();
            int pos = Math.max(0, Math.min(largo, input.getSelectionStart()));
            switch (tecla) {
                case "A":
                    recall(-1);
                    break;
                case "B":
                    recall(1);
                    break;
                case "C":
                    input.setSelection(Math.min(largo, pos + 1));
                    break;
                case "D":
                    input.setSelection(Math.max(0, pos - 1));
                    break;
                case "H":
                    input.setSelection(0);
                    break;
                case "F":
                    input.setSelection(largo);
                    break;
                case "5":
                    scroll.scrollBy(0, -scroll.getHeight() * 3 / 4);
                    break;
                case "6":
                    scroll.scrollBy(0, scroll.getHeight() * 3 / 4);
                    break;
                default:
                    break;
            }
        }

        @Override
        public void control(String tecla) {
            if (!terminalActivo()) return;   // TAB y ESC no significan nada aquí
            term.sendKey("TAB".equals(tecla) ? "\t" : "\u001b");
        }

        @Override
        public void texto(String texto) {
            if (terminalActivo()) {
                term.sendText(texto);
                return;
            }
            int pos = Math.max(0, input.getSelectionStart());
            input.getText().insert(pos, texto);
        }

        @Override
        public void alternarCtrl() {
            if (terminalActivo()) term.toggleCtrl();
        }

        @Override
        public void alternarAlt() {
            if (terminalActivo()) term.toggleAlt();
        }

        @Override
        public boolean ctrlActivo() {
            return terminalActivo() && term.isCtrlSticky();
        }

        @Override
        public boolean altActivo() {
            return terminalActivo() && term.isAltSticky();
        }
    };

    /** Secuencia ANSI de una tecla de navegación, según el modo de cursor. */
    private String secuencia(String tecla) {
        switch (tecla) {
            case "5":
                return "\u001b[5~";
            case "6":
                return "\u001b[6~";
            default:
                return (term.appCursorKeys() ? "\u001bO" : "\u001b[") + tecla;
        }
    }

    /** Versión real del APK instalado, para que la cabecera no mienta. */
    private String versionInstalada() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return Syscmd.VERSION;
        }
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
                if (args.length == 0) openTerminal("python3");
                else runShell("python3 " + rest(line));
                return;
            case "python3":
            case "sqlite3":
            case "bash":
            case "sh":
                if (args.length == 0) openTerminal(line);
                else runShell(line);
                return;
            case "consola":
            case "terminal":
                openTerminal(shellCommand());
                return;
            case "nano":
            case "vim":
            case "vi":
            case "nvim":
            case "less":
            case "top":
            case "htop":
                openTerminal(line);
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
            case "archivos":
            case "files":
                openFiles();
                return;
            case "editar":
            case "edit":
                openEditor(rest(line));
                return;
            case "bg":
                startJob(rest(line));
                return;
            case "jobs":
                println(Jobs.list());
                return;
            case "bateria":
            case "battery":
                pedirSinOptimizacion();
                return;
            case "ejemplos":
            case "examples":
                mostrarEjemplos();
                return;
            case "log":
                showLog(args);
                return;
            case "kill":
                killJob(args);
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

    /**
     * Cualquier otro comando: se ejecuta dentro del PTY, sin límite de tiempo y
     * con la app en primer plano mientras dure. La salida se va pintando en
     * vivo para que se vea el progreso de scripts largos (mineros, SSH, etc.).
     */
    private void runShell(String line) {
        if (!envReady && !Bootstrap.isInstalled(this)) {
            println("El entorno todavía se está preparando. Espera un momento.");
            return;
        }
        // Mientras haya un comando vivo, la app queda en primer plano con la
        // red despierta. Es lo que evita el Errno 103 al apagar la pantalla.
        Jobs.beginForeground();
        KeepAliveService.sync(this);
        new Thread(() -> {
            Process p = null;
            try {
                ProcessBuilder pb = shellCommand(line);
                pb.environment().putAll(Bootstrap.env(this));
                pb.directory(Bootstrap.home(this));
                pb.redirectErrorStream(true);
                p = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                StringBuilder pending = new StringBuilder();
                long lastPost = 0;
                String l;
                while ((l = r.readLine()) != null) {
                    pending.append(ANSI.matcher(l).replaceAll("").replace("\r", "")).append('\n');
                    long now = System.currentTimeMillis();
                    if (now - lastPost >= 200) {
                        String chunk = pending.toString();
                        pending.setLength(0);
                        ui.post(() -> println(chunk));
                        lastPost = now;
                    }
                }
                if (pending.length() > 0) {
                    String chunk = pending.toString();
                    pending.setLength(0);
                    ui.post(() -> println(chunk));
                }
                int code = p.waitFor();
                if (code != 0) {
                    final int c = code;
                    ui.post(() -> println("(código de salida " + c + ")"));
                }
            } catch (Exception e) {
                final String msg = String.valueOf(e.getMessage());
                ui.post(() -> println("No se pudo ejecutar: " + msg));
            } finally {
                if (p != null && p.isAlive()) p.destroy();
                Jobs.endForeground();
                KeepAliveService.sync(MainActivity.this);
            }
        }, "shell").start();
    }

    /** Comando de shell dentro de un PTY real si el entorno está instalado; si no, la shell de Android. */
    private ProcessBuilder shellCommand(String line) {
        File py = new File(Bootstrap.usr(this), "bin/python3");
        File script = ptyScript();
        if (py.exists() && script != null) {
            return new ProcessBuilder(py.getAbsolutePath(), script.getAbsolutePath(), line);
        }
        return new ProcessBuilder("/system/bin/sh", "-c", line);
    }

    /** Copia ptyrun.py de los recursos a la carpeta de la app (una vez por versión). */
    private File ptyScript() {
        File out = new File(getFilesDir(), "ptyrun.py");
        try (InputStream in = getAssets().open("ptyrun.py")) {
            byte[] data = readAll(in);
            if (!out.exists() || out.length() != data.length) {
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    fo.write(data);
                }
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    /** Shell interactiva del entorno Linux (bash si está instalado, si no sh). */
    private String shellCommand() {
        return new File(Bootstrap.usr(this), "bin/bash").exists() ? "bash -i" : "sh -i";
    }

    /** Abre un programa interactivo a pantalla completa: se inicia cuando la pantalla conoce su tamaño. */
    private void openTerminal(String cmd) {
        if (!envReady && !Bootstrap.isInstalled(this)) {
            println("El entorno todavía se está preparando. Espera un momento.");
            return;
        }
        if (session != null && session.isRunning()) {
            println("Ya hay un programa interactivo abierto. Sal de él (o pulsa Atrás) para abrir otro.");
            return;
        }
        pendingCmd = cmd;
        scroll.setVisibility(View.GONE);
        inputRow.setVisibility(View.GONE);
        termPanel.setVisibility(View.VISIBLE);
        term.requestFocus();
        if (term.rows() > 1 && term.cols() > 1) startPending();
    }

    private void startPending() {
        String cmd = pendingCmd;
        pendingCmd = null;
        if (cmd == null) return;
        File py = new File(Bootstrap.usr(this), "bin/python3");
        File script = ptyScript();
        if (!py.exists() || script == null) {
            endTerminal();
            println("El entorno Linux no está listo: no se puede abrir " + cmd + ".");
            return;
        }
        try {
            session = PtySession.start(py, script, Bootstrap.env(this), Bootstrap.home(this), cmd,
                    term.rows(), term.cols(), ui, new PtySession.Listener() {
                        @Override
                        public void onOutput(String text) {
                            term.feed(text);
                        }

                        @Override
                        public void onExit(int code) {
                            session = null;
                            endTerminal();
                            println("[" + cmd + " terminado con código " + code + "]");
                        }
                    });
        } catch (IOException e) {
            endTerminal();
            println("No se pudo abrir " + cmd + ": " + e.getMessage());
        }
    }

    /** Vuelve a la consola normal. */
    private void endTerminal() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(term.getWindowToken(), 0);
        termPanel.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        inputRow.setVisibility(View.VISIBLE);
        input.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (session != null && session.isRunning()) {
            session.close();
            session = null;
            endTerminal();
            println("Programa interactivo cerrado.");
            return;
        }
        super.onBackPressed();
    }

    private void openFiles() {
        startActivityForResult(new Intent(this, FilesActivity.class), REQ_FILES);
    }

    private void openEditor(String name) {
        String n = name.trim();
        File f = n.isEmpty() ? new File(Bootstrap.home(this), "nuevo.py") : new File(Bootstrap.home(this), n);
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_PATH, f.getAbsolutePath());
        startActivityForResult(i, REQ_EDIT);
    }

    private void startJob(String cmd) {
        if (cmd.trim().isEmpty()) {
            println("Uso: bg <comando>   (ejemplo: bg python3 servidor.py)");
            return;
        }
        if (!envReady && !Bootstrap.isInstalled(this)) {
            println("El entorno todavía se está preparando. Espera un momento.");
            return;
        }
        try {
            int id = Jobs.start(this, cmd);
            println("Trabajo " + id + " en segundo plano. Ver salida: log " + id + "  ·  lista: jobs");
        } catch (IOException e) {
            println("No se pudo arrancar el trabajo: " + e.getMessage());
        }
    }

    /**
     * Pide a Android que no optimice la batería de esta app. Con la optimización
     * activa, el sistema puede congelar el proceso en segundo plano y cortar los
     * sockets abiertos aunque haya un servicio en primer plano.
     */
    /** Lista los scripts que se copian del APK a ~/examples. */
    private void mostrarEjemplos() {
        Bootstrap.ensureExtras(this);
        java.io.File dir = new java.io.File(Bootstrap.home(this), "examples");
        java.io.File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            println("Esta versión del APK no trae ejemplos.");
            return;
        }
        println("Ejemplos en " + dir.getAbsolutePath() + ":");
        for (java.io.File f : files) {
            if (f.getName().startsWith(".")) continue;
            println("  " + f.getName());
        }
        println("Ejecuta: python3 ~/examples/prueba_stratum.py <tu_direccion_btc>");
    }

    private void pedirSinOptimizacion() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                println("La app ya está exenta de la optimización de batería.");
                return;
            }
            Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
            println("Acepta la excepción de batería para que Android no corte los trabajos.");
        } catch (RuntimeException e) {
            println("No se pudo abrir el ajuste de batería: " + e.getMessage());
            println("Hazlo a mano en Ajustes > Aplicaciones > Terminal Linux > Batería > Sin restricciones.");
        }
    }

    private void showLog(String[] args) {
        if (args.length == 0) {
            println("Uso: log <número de trabajo>");
            return;
        }
        try {
            println(Jobs.tail(Integer.parseInt(args[0]), 40));
        } catch (NumberFormatException e) {
            println("Uso: log <número de trabajo>");
        }
    }

    private void killJob(String[] args) {
        if (args.length == 0) {
            println("Uso: kill <número de trabajo>");
            return;
        }
        try {
            println(Jobs.kill(Integer.parseInt(args[0])));
        } catch (NumberFormatException e) {
            println("Uso: kill <número de trabajo>");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQ_EDIT || requestCode == REQ_FILES) && resultCode == RESULT_OK
                && data != null && data.hasExtra(EditorActivity.RESULT_RUN)) {
            String cmd = data.getStringExtra(EditorActivity.RESULT_RUN);
            println("$ " + cmd);
            runShell(cmd);
        }
    }
}
