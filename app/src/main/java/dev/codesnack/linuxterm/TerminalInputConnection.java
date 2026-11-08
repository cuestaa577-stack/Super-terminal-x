package dev.codesnack.linuxterm;

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;

/** Conexión de entrada de TerminalView: texto del teclado virtual y teclas. */
final class TerminalInputConnection extends BaseInputConnection {
    private final TerminalView term;

    TerminalInputConnection(TerminalView term) {
        super((View) term, true);
        this.term = term;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        String s = text.toString();
        if (s.equals("\n")) s = "\r";
        term.sendText(s);
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        for (int i = 0; i < beforeLength; i++) term.sendKey(String.valueOf((char) 127));
        return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        return term.onKeyDown(event.getKeyCode(), event);
    }
}
