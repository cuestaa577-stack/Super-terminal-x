package dev.codesnack.linuxterm;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Barra de teclas especiales acoplada encima del teclado. Está siempre visible:
 * en la consola de comandos mueve el cursor y el historial, y dentro de una
 * terminal interactiva manda las secuencias reales al PTY.
 *
 *   ESC  /  -  HOME  ↑  END  PGUP
 *   TAB  CTRL  ALT  ←  ↓  →  PGDN  ENTER
 *
 * CTRL y ALT son modificadores de toque: se encienden, se aplican a la
 * siguiente tecla y se apagan solos.
 */
final class KeyBar extends LinearLayout {

    /** A quién van las teclas: la consola de comandos o la terminal interactiva. */
    interface Objetivo {
        /** Hay una terminal interactiva abierta. */
        boolean terminalActivo();

        /** Flechas, HOME, END, PGUP y PGDN. */
        void navegar(String tecla);

        /** TAB y ESC. */
        void control(String tecla);

        /** Texto literal, como "/" o "-". */
        void texto(String texto);

        void alternarCtrl();

        void alternarAlt();

        boolean ctrlActivo();

        boolean altActivo();
    }

    private static final int BG = Color.rgb(28, 30, 34);
    private static final int TECLA = Color.rgb(44, 47, 53);
    private static final int TECLA_PULSADA = Color.rgb(58, 62, 70);
    private static final int ACENTO = Color.rgb(90, 200, 255);
    private static final int TEXTO = Color.rgb(240, 244, 248);

    private final Objetivo objetivo;
    private final TextView ctrl;
    private final TextView alt;
    private int rellenoIme;

    KeyBar(Context ctx, Objetivo objetivo) {
        super(ctx);
        this.objetivo = objetivo;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        int pad = dp(2);
        setPadding(pad, pad, pad, pad);

        LinearLayout fila1 = fila();
        add(fila1, "ESC", () -> objetivo.control("ESC"));
        add(fila1, "/", () -> objetivo.texto("/"));
        add(fila1, "-", () -> objetivo.texto("-"));
        add(fila1, "HOME", () -> objetivo.navegar("H"));
        add(fila1, "↑", () -> objetivo.navegar("A"));
        add(fila1, "END", () -> objetivo.navegar("F"));
        add(fila1, "PGUP", () -> objetivo.navegar("5"));

        LinearLayout fila2 = fila();
        add(fila2, "TAB", () -> objetivo.control("TAB"));
        ctrl = add(fila2, "CTRL", () -> {
            objetivo.alternarCtrl();
            refresh();
        });
        alt = add(fila2, "ALT", () -> {
            objetivo.alternarAlt();
            refresh();
        });
        add(fila2, "←", () -> objetivo.navegar("D"));
        add(fila2, "↓", () -> objetivo.navegar("B"));
        add(fila2, "→", () -> objetivo.navegar("C"));
        add(fila2, "PGDN", () -> objetivo.navegar("6"));
        add(fila2, "ENTER", () -> objetivo.control("ENTER"));

        addView(fila1, new LayoutParams(-1, -2));
        addView(fila2, new LayoutParams(-1, -2));

        refresh();
        ajustarAlTeclado();
    }

    /**
     * Deja la barra justo encima del teclado. Con adjustResize la ventana ya se
     * encoge, así que solo se añade el espacio que falte: el inset del IME
     * menos lo que la ventana ya perdió. Así no se duplica el hueco.
     */
    private void ajustarAlTeclado() {
        setOnApplyWindowInsetsListener((v, insets) -> {
            int ime;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                ime = insets.getSystemWindowInsetBottom();
            }
            Rect visible = new Rect();
            v.getWindowVisibleDisplayFrame(visible);
            int alto = v.getRootView().getHeight();
            int tapado = Math.max(0, alto - visible.bottom);
            rellenoIme = Math.max(0, ime - tapado);
            aplicarRelleno();
            return insets;
        });
    }

    private void aplicarRelleno() {
        int pad = dp(2);
        setPadding(pad, pad, pad, pad + rellenoIme);
    }

    private LinearLayout fila() {
        LinearLayout fila = new LinearLayout(getContext());
        fila.setOrientation(HORIZONTAL);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        return fila;
    }

    private TextView add(LinearLayout fila, String texto, Runnable accion) {
        TextView b = new TextView(getContext());
        b.setText(texto);
        b.setTextColor(TEXTO);
        b.setTextSize(12);
        b.setTypeface(Typeface.MONOSPACE);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(2), dp(8), dp(2), dp(8));
        b.setBackground(fondo(TECLA));
        b.setClickable(true);
        b.setFocusable(false);
        b.setOnClickListener(v -> {
            accion.run();
            refresh();
        });
        LayoutParams lp = new LayoutParams(0, -2, 1f);
        int m = dp(2);
        lp.setMargins(m, m, m, m);
        fila.addView(b, lp);
        return b;
    }

    private GradientDrawable fondo(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(6));
        return d;
    }

    /** Resalta CTRL y ALT, y los apaga cuando no hay terminal interactiva. */
    void refresh() {
        boolean terminal = objetivo.terminalActivo();
        pintar(ctrl, objetivo.ctrlActivo() && terminal, terminal);
        pintar(alt, objetivo.altActivo() && terminal, terminal);
    }

    private void pintar(TextView b, boolean activo, boolean habilitado) {
        if (b == null) return;
        b.setTextColor(activo ? ACENTO : TEXTO);
        b.setBackground(fondo(activo ? TECLA_PULSADA : TECLA));
        b.setAlpha(habilitado ? 1f : 0.35f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
