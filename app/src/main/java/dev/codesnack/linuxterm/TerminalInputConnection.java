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
        // Algunos teclados mandan \n al pulsar su tecla de envío: en el PTY
        // eso es un salto de línea, así que se convierte en retorno de carro.
        String s = text.toString().replace("\n", "\r");
        term.sendText(s);
        return true;
    }

    /** Botón de envío / flecha del teclado virtual: ejecuta la línea. */
    @Override
    public boolean performEditorAction(int actionId) {
        term.enviarEnter();
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
        if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            term.enviarEnter();
            return true;
        }
        return term.onKeyDown(event.getKeyCode(), event);
    }
}
