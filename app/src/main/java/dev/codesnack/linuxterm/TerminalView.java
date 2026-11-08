package dev.codesnack.linuxterm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * Pantalla de un programa interactivo: dibuja el TerminalEmulator y envía las teclas
 * (teclado en pantalla, teclado físico o la barra de teclas especiales) al PTY.
 */
public class TerminalView extends View {

    interface Listener {
        /** La pantalla ha cambiado de tamaño (filas, columnas). */
        void onSize(int rows, int cols);

        /** El usuario ha pulsado algo: secuencia a enviar al programa. */
        void onInput(String seq);
    }

    static final int DEFAULT_FG = Color.rgb(220, 230, 220);
    static final int DEFAULT_BG = Color.rgb(16, 18, 20);
    static final int CURSOR = Color.rgb(90, 200, 255);
    private static final int[] ANSI16 = {
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final TerminalEmulator emu;
    private Listener listener;
    private float cellW;
    private float cellH;
    private float ascent;
    private int scrollOffset;
    private int lastRows = -1;
    private int lastCols = -1;
    private boolean ctrlSticky;
    private boolean altSticky;
    private Runnable stickyListener;
    private float touchLastY;
    private float touchDownY;
    private boolean touchMoved;

    public TerminalView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(sp(13));
        cellW = paint.measureText("M");
        Paint.FontMetrics fm = paint.getFontMetrics();
        cellH = fm.bottom - fm.top;
        ascent = -fm.top;
        emu = new TerminalEmulator(24, 80, this::sendRaw);
    }


    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Texto recibido del programa. */
    void feed(String text) {
        emu.feed(text);
        invalidate();
    }

    boolean appCursorKeys() { return emu.appCursorKeys(); }
    int rows() { return emu.rows(); }
    int cols() { return emu.cols(); }
    boolean isCtrlSticky() { return ctrlSticky; }
    boolean isAltSticky() { return altSticky; }

    void setStickyListener(Runnable r) {
        stickyListener = r;
    }

    private void stickyChanged() {
        if (stickyListener != null) stickyListener.run();
    }

    void toggleCtrl() {
        ctrlSticky = !ctrlSticky;
        stickyChanged();
    }

    void toggleAlt() {
        altSticky = !altSticky;
        stickyChanged();
    }

    /** Una tecla de la barra: se envía con ALT si está activo, y se suelta el modificador. */
    void sendKey(String seq) {
        String out = altSticky ? "\u001b" + seq : seq;
        altSticky = false;
        ctrlSticky = false;
        stickyChanged();
        sendRaw(out);
    }

    /** Envía al programa; aplica CTRL a un carácter si está activo. */
    void sendText(String text) {
        if (ctrlSticky && text.length() == 1) {
            text = ctrlCode(text.charAt(0));
        }
        if (altSticky) text = "\u001b" + text;
        ctrlSticky = false;
        altSticky = false;
        stickyChanged();
        sendRaw(text);
    }

    private void sendRaw(String seq) {
        scrollOffset = 0;
        invalidate();
        if (listener != null) listener.onInput(seq);
    }

    static String ctrlCode(char c) {
        char u = Character.toUpperCase(c);
        if (u >= '@' && u <= '_') return String.valueOf((char) (u - '@'));
        if (c == ' ') return String.valueOf((char) 0);
        return String.valueOf(c);
    }

    /** Filas y columnas que caben en la vista. */
    int rowsForHeight(int h) { return Math.max(1, (int) (h / cellH)); }
    int colsForWidth(int w) { return Math.max(1, (int) (w / cellW)); }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int rows = rowsForHeight(h);
        int cols = colsForWidth(w);
        if (rows == lastRows && cols == lastCols) return;
        lastRows = rows;
        lastCols = cols;
        emu.resize(rows, cols);
        if (listener != null) listener.onSize(rows, cols);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        bgPaint.setColor(DEFAULT_BG);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        int rows = emu.rows();
        int cols = emu.cols();
        boolean showCursor = emu.cursorVisible() && scrollOffset == 0;
        for (int r = 0; r < rows; r++) {
            TerminalEmulator.Line line = emu.lineForView(r, scrollOffset);
            float y = r * cellH;
            for (int c = 0; c < cols; c++) {
                float x = c * cellW;
                int fgIdx = line == null ? -1 : line.fg(c);
                int bgIdx = line == null ? -1 : line.bg(c);
                int attr = line == null ? 0 : line.attr(c);
                int cp = line == null ? ' ' : line.cp(c);
                boolean reverse = (attr & TerminalEmulator.REVERSE) != 0;
                boolean cursorHere = showCursor && r == emu.cursorRow() && c == emu.cursorCol();
                int fg = color(fgIdx, DEFAULT_FG);
                int bg = color(bgIdx, DEFAULT_BG);
                if (reverse != cursorHere) {
                    int t = fg;
                    fg = bg;
                    bg = t;
                }
                if (cursorHere) bg = CURSOR;
                if (bg != DEFAULT_BG || reverse || cursorHere) {
                    bgPaint.setColor(bg);
                    canvas.drawRect(x, y, x + cellW, y + cellH, bgPaint);
                }
                if (cp != ' ') {
                    paint.setColor(fg);
                    paint.setFakeBoldText((attr & TerminalEmulator.BOLD) != 0);
                    canvas.drawText(new String(Character.toChars(cp)), x, y + ascent, paint);
                }
                if ((attr & TerminalEmulator.UNDERLINE) != 0) {
                    paint.setColor(fg);
                    canvas.drawRect(x, y + cellH - 2, x + cellW, y + cellH - 1, paint);
                }
            }
        }
    }

    static int color(int idx, int def) {
        if (idx < 0) return def;
        if (idx < 16) return 0xFF000000 | ANSI16[idx];
        if (idx < 232) {
            int i = idx - 16;
            int r = i / 36, g = (i / 6) % 6, b = i % 6;
            return Color.rgb(cube(r), cube(g), cube(b));
        }
        int v = 8 + (idx - 232) * 10;
        return Color.rgb(v, v, v);
    }

    private static int cube(int v) {
        return v == 0 ? 0 : 55 + v * 40;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchLastY = y;
                touchDownY = y;
                touchMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = y - touchLastY;
                if (Math.abs(y - touchDownY) > 12 * getResources().getDisplayMetrics().density) touchMoved = true;
                if (touchMoved) {
                    int steps = Math.round(dy / cellH);
                    if (steps != 0) {
                        scrollOffset = Math.max(0, Math.min(emu.historySize(), scrollOffset + steps));
                        touchLastY = y;
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!touchMoved) {
                    requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                            getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
                    if (imm != null) imm.showSoftInput(this, 0);
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new TerminalInputConnection(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        String seq = keySequence(keyCode);
        if (seq != null) {
            sendKey(seq);
            return true;
        }
        int uc = event.getUnicodeChar();
        if (uc > 0) {
            String s = new String(Character.toChars(uc));
            if (event.isCtrlPressed()) s = ctrlCode(s.charAt(0));
            sendText(s);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private String keySequence(int keyCode) {
        boolean app = emu.appCursorKeys();
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                return "\r";
            case KeyEvent.KEYCODE_DEL:
                return "\u007f";
            case KeyEvent.KEYCODE_TAB:
                return "\t";
            case KeyEvent.KEYCODE_ESCAPE:
                return "\u001b";
            case KeyEvent.KEYCODE_DPAD_UP:
                return (app ? "\u001bO" : "\u001b[") + "A";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return (app ? "\u001bO" : "\u001b[") + "B";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return (app ? "\u001bO" : "\u001b[") + "C";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return (app ? "\u001bO" : "\u001b[") + "D";
            case KeyEvent.KEYCODE_MOVE_HOME:
                return (app ? "\u001bO" : "\u001b[") + "H";
            case KeyEvent.KEYCODE_MOVE_END:
                return (app ? "\u001bO" : "\u001b[") + "F";
            case KeyEvent.KEYCODE_PAGE_UP:
                return "\u001b[5~";
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return "\u001b[6~";
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return "\u001b[3~";
            default:
                return null;
        }
    }
}
