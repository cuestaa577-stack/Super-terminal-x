package dev.codesnack.linuxterm;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** Barra de teclas especiales para la terminal: ESC, TAB, CTRL, ALT, flechas, HOME, END... */
final class KeyBar extends HorizontalScrollView {
    private final TerminalView term;
    private final Button ctrl;
    private final Button alt;

    KeyBar(Context ctx, TerminalView term) {
        super(ctx);
        this.term = term;
        setBackgroundColor(Color.rgb(30, 32, 36));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row);

        add(row, "ESC", () -> term.sendKey("\u001b"));
        add(row, "TAB", () -> term.sendKey("\t"));
        ctrl = add(row, "CTRL", () -> {
            term.toggleCtrl();
            refresh();
        });
        alt = add(row, "ALT", () -> {
            term.toggleAlt();
            refresh();
        });
        add(row, "↑", () -> term.sendKey(arrow("A")));
        add(row, "↓", () -> term.sendKey(arrow("B")));
        add(row, "←", () -> term.sendKey(arrow("D")));
        add(row, "→", () -> term.sendKey(arrow("C")));
        add(row, "HOME", () -> term.sendKey(arrow("H")));
        add(row, "END", () -> term.sendKey(arrow("F")));
        add(row, "PGUP", () -> term.sendKey("\u001b[5~"));
        add(row, "PGDN", () -> term.sendKey("\u001b[6~"));
        add(row, "DEL", () -> term.sendKey("\u001b[3~"));
        add(row, "^C", () -> term.sendKey("\u0003"));
        add(row, "^Z", () -> term.sendKey("\u001a"));
        add(row, "|", () -> term.sendText("|"));
        add(row, "/", () -> term.sendText("/"));
        add(row, "~", () -> term.sendText("~"));
        add(row, "-", () -> term.sendText("-"));
        term.setStickyListener(this::refresh);
        refresh();
    }

    private String arrow(String letter) {
        // Igual que TerminalView: en modo de aplicación (nano, vim) las flechas usan ESC O.
        return (term.appCursorKeys() ? "\u001bO" : "\u001b[") + letter;
    }

    private Button add(LinearLayout row, String text, Runnable action) {
        Button b = new Button(getContext());
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setText(text);
        b.setOnClickListener(v -> {
            action.run();
            term.requestFocus();
        });
        row.addView(b);
        return b;
    }

    /** Resalta CTRL y ALT cuando están activos. */
    void refresh() {
        ctrl.setTextColor(term.isCtrlSticky() ? Color.rgb(90, 200, 255) : Color.WHITE);
        alt.setTextColor(term.isAltSticky() ? Color.rgb(90, 200, 255) : Color.WHITE);
    }
}
