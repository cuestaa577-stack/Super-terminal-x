package dev.codesnack.linuxterm;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Barra de teclas especiales acoplada encima del teclado.
 *
 * Dos filas fijas, sin scroll horizontal:
 *   ESC  /  -  HOME  ↑  END  PGUP
 *   TAB  CTRL  ALT  ←  ↓  →  PGDN
 *
 * CTRL y ALT son modificadores de toque: se encienden, se aplican a la
 * siguiente tecla y se apagan solos. Las de navegación mandan las secuencias
 * ANSI del modo que esté activo (normal o aplicación, como en vim o nano).
 */
final class KeyBar extends LinearLayout {

    private static final int BG = Color.rgb(28, 30, 34);
    private static final int TECLA = Color.rgb(44, 47, 53);
    private static final int TECLA_PULSADA = Color.rgb(58, 62, 70);
    private static final int ACENTO = Color.rgb(90, 200, 255);
    private static final int TEXTO = Color.rgb(240, 244, 248);

    private final TerminalView term;
    private final TextView ctrl;
    private final TextView alt;
    private int rellenoIme;

    KeyBar(Context ctx, TerminalView term) {
        super(ctx);
        this.term = term;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        int pad = dp(2);
        setPadding(pad, pad, pad, pad);

        LinearLayout fila1 = fila();
        add(fila1, "ESC", () -> term.sendKey("\u001b"));
        add(fila1, "/", () -> term.sendText("/"));
        add(fila1, "-", () -> term.sendText("-"));
        add(fila1, "HOME", () -> term.sendKey(secuencia("H")));
        add(fila1, "↑", () -> term.sendKey(secuencia("A")));
        add(fila1, "END", () -> term.sendKey(secuencia("F")));
        add(fila1, "PGUP", () -> term.sendKey("\u001b[5~"));

        LinearLayout fila2 = fila();
        add(fila2, "TAB", () -> term.sendKey("\t"));
        ctrl = add(fila2, "CTRL", () -> {
            term.toggleCtrl();
            refresh();
        });
        alt = add(fila2, "ALT", () -> {
            term.toggleAlt();
            refresh();
        });
        add(fila2, "←", () -> term.sendKey(secuencia("D")));
        add(fila2, "↓", () -> term.sendKey(secuencia("B")));
        add(fila2, "→", () -> term.sendKey(secuencia("C")));
        add(fila2, "PGDN", () -> term.sendKey("\u001b[6~"));

        addView(fila1, new LayoutParams(-1, -2));
        addView(fila2, new LayoutParams(-1, -2));

        term.setStickyListener(this::refresh);
        refresh();
        ajustarAlTeclado();
    }

    /**
     * Deja la barra justo encima del teclado. Con adjustResize la ventana ya se
     * encoge, así que aquí solo se añade el espacio que falte: el inset del IME
     * menos lo que la ventana ya perdió. Así no se duplica el hueco.
     */
    private void ajustarAlTeclado() {
        setOnApplyWindowInsetsListener((v, insets) -> {
            int ime = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.ime());
                ime = i.bottom;
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

    /** Igual que TerminalView: en modo aplicación (vim, nano) las flechas usan ESC O. */
    private String secuencia(String letra) {
        return (term.appCursorKeys() ? "\u001bO" : "\u001b[") + letra;
    }

    private TextView add(LinearLayout fila, String texto, Runnable accion) {
        TextView b = new TextView(getContext());
        b.setText(texto);
        b.setTextColor(TEXTO);
        b.setTextSize(13);
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
            term.requestFocus();
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

    /** Resalta CTRL y ALT cuando están activos. */
    void refresh() {
        pintar(ctrl, term.isCtrlSticky());
        pintar(alt, term.isAltSticky());
    }

    private void pintar(TextView b, boolean activo) {
        if (b == null) return;
        b.setTextColor(activo ? ACENTO : TEXTO);
        b.setBackground(fondo(activo ? TECLA_PULSADA : TECLA));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
