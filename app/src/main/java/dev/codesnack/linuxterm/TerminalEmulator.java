package dev.codesnack.linuxterm;

import java.util.ArrayList;
import java.util.List;

/**
 * Emulador de terminal VT100/xterm: interpreta la salida de un PTY (texto, colores,
 * movimiento del cursor, pantalla alternativa de nano/vim/less/top...).
 * No depende de Android: se puede probar en el escritorio.
 */
public final class TerminalEmulator {

    /** Respuestas que el programa espera de la terminal (posición del cursor, atributos). */
    public interface Responder {
        void send(String text);
    }

    public static final int BOLD = 1;
    public static final int UNDERLINE = 2;
    public static final int REVERSE = 4;
    static final int MAX_HISTORY = 2000;

    /** Una fila de celdas. Color -1 = por defecto; 0..255 = paleta xterm. */
    public static final class Line {
        final int[] cp;
        final int[] fg;
        final int[] bg;
        final int[] attr;

        Line(int cols) {
            cp = new int[cols];
            fg = new int[cols];
            bg = new int[cols];
            attr = new int[cols];
            java.util.Arrays.fill(cp, ' ');
            java.util.Arrays.fill(fg, -1);
            java.util.Arrays.fill(bg, -1);
        }

        /** Copia de una fila ajustada a un ancho nuevo. */
        Line(Line o, int cols) {
            this(cols);
            int n = Math.min(cols, o.cp.length);
            System.arraycopy(o.cp, 0, cp, 0, n);
            System.arraycopy(o.fg, 0, fg, 0, n);
            System.arraycopy(o.bg, 0, bg, 0, n);
            System.arraycopy(o.attr, 0, attr, 0, n);
        }

        public int cp(int c) { return c < cp.length ? cp[c] : ' '; }
        public int fg(int c) { return c < fg.length ? fg[c] : -1; }
        public int bg(int c) { return c < bg.length ? bg[c] : -1; }
        public int attr(int c) { return c < attr.length ? attr[c] : 0; }
    }

    private static final int GROUND = 0, ESC = 1, CSI = 2, STR = 3, STR_ESC = 4, CHARSET = 5;

    private final Responder responder;
    private int rows;
    private int cols;
    private Line[] screen;
    private Line[] mainScreen;
    private final List<Line> history = new ArrayList<>();
    private int row;
    private int col;
    private boolean pendingWrap;
    private int top;
    private int bottom;
    private int curFg = -1;
    private int curBg = -1;
    private int curAttr;
    private boolean autowrap = true;
    private boolean cursorVisible = true;
    private boolean appCursor;
    private boolean altScreen;
    private int savedRow;
    private int savedCol;
    private int highSurrogate;

    private int state = GROUND;
    private final StringBuilder params = new StringBuilder();
    private char prefix;

    public TerminalEmulator(int rows, int cols, Responder responder) {
        this.responder = responder;
        this.rows = Math.max(1, rows);
        this.cols = Math.max(1, cols);
        this.screen = blankScreen(this.rows, this.cols);
        this.bottom = this.rows - 1;
    }

    private Line[] blankScreen(int r, int c) {
        Line[] s = new Line[r];
        for (int i = 0; i < r; i++) s[i] = new Line(c);
        return s;
    }

    public int rows() { return rows; }
    public int cols() { return cols; }
    public int cursorRow() { return row; }
    public int cursorCol() { return col; }
    public boolean cursorVisible() { return cursorVisible; }
    public boolean appCursorKeys() { return appCursor; }
    public boolean altScreen() { return altScreen; }
    public int historySize() { return altScreen ? 0 : history.size(); }

    /** Fila que se ve en la pantalla, desplazada hacia atrás "offset" filas (historial). */
    public Line lineForView(int viewRow, int offset) {
        int idx = history.size() - offset + viewRow;
        if (idx < 0) return null;
        if (idx < history.size()) return history.get(idx);
        int r = idx - history.size();
        return r < screen.length ? screen[r] : null;
    }

    public void resize(int newRows, int newCols) {
        newRows = Math.max(1, newRows);
        newCols = Math.max(1, newCols);
        screen = resizeScreen(screen, newRows, newCols);
        if (mainScreen != null) mainScreen = resizeScreen(mainScreen, newRows, newCols);
        rows = newRows;
        cols = newCols;
        top = 0;
        bottom = rows - 1;
        row = Math.min(row, rows - 1);
        col = Math.min(col, cols - 1);
        pendingWrap = false;
    }

    private Line[] resizeScreen(Line[] old, int r, int c) {
        Line[] s = blankScreen(r, c);
        for (int i = 0; i < Math.min(r, old.length); i++) s[i] = new Line(old[i], c);
        return s;
    }

    /** Procesa texto recibido del PTY (ya decodificado como UTF-8). */
    public void feed(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                highSurrogate = ch;
                continue;
            }
            if (Character.isLowSurrogate(ch) && highSurrogate != 0) {
                process(Character.toCodePoint((char) highSurrogate, ch));
                highSurrogate = 0;
                continue;
            }
            highSurrogate = 0;
            process(ch);
        }
    }

    private void process(int c) {
        switch (state) {
            case GROUND:
                ground(c);
                break;
            case ESC:
                escape(c);
                break;
            case CSI:
                csi(c);
                break;
            case STR:
                if (c == 0x07) state = GROUND;
                else if (c == 0x1B) state = STR_ESC;
                break;
            case STR_ESC:
                state = GROUND;
                break;
            case CHARSET:
                state = GROUND;
                break;
            default:
                state = GROUND;
        }
    }

    private void ground(int c) {
        switch (c) {
            case 0x1B:
                state = ESC;
                break;
            case 0x08:
                if (col > 0) col--;
                pendingWrap = false;
                break;
            case 0x09:
                col = Math.min(cols - 1, (col / 8 + 1) * 8);
                pendingWrap = false;
                break;
            case 0x0A:
            case 0x0B:
            case 0x0C:
                lineFeed();
                break;
            case 0x0D:
                col = 0;
                pendingWrap = false;
                break;
            default:
                if (c < 0x20 || c == 0x7F) break;
                if (Character.getType(c) == Character.NON_SPACING_MARK) break;
                putChar(c);
        }
    }

    private void escape(int c) {
        state = GROUND;
        switch (c) {
            case '[':
                state = CSI;
                params.setLength(0);
                prefix = 0;
                break;
            case ']':
            case 'P':
            case 'X':
            case '^':
            case '_':
                state = STR;
                break;
            case '(':
            case ')':
            case '*':
            case '+':
                state = CHARSET;
                break;
            case '7':
                savedRow = row;
                savedCol = col;
                break;
            case '8':
                row = Math.min(savedRow, rows - 1);
                col = Math.min(savedCol, cols - 1);
                pendingWrap = false;
                break;
            case 'D':
                lineFeed();
                break;
            case 'E':
                lineFeed();
                col = 0;
                break;
            case 'M':
                reverseIndex();
                break;
            case 'c':
                reset();
                break;
            default:
                break; // ESC = y ESC > (teclado numérico) se ignoran
        }
    }

    private void csi(int c) {
        if (c >= 0x30 && c <= 0x3F) {
            if (params.length() == 0 && c >= 0x3C) prefix = (char) c;
            else params.append((char) c);
        } else if (c >= 0x20 && c <= 0x2F) {
            // Caracteres intermedios: se ignoran.
        } else if (c >= 0x40 && c <= 0x7E) {
            state = GROUND;
            execCsi((char) c);
        } else {
            state = GROUND;
        }
    }

    /** Parámetros CSI: -1 si falta. */
    private int[] parseParams() {
        if (params.length() == 0) return new int[0];
        String[] parts = params.toString().split("[;:]", -1);
        int[] p = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            p[i] = parts[i].isEmpty() ? -1 : parsePart(parts[i]);
        }
        return p;
    }

    private static int parsePart(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int p(int[] ps, int i, int def) {
        return i < ps.length && ps[i] > 0 ? ps[i] : def;
    }

    private void execCsi(char f) {
        int[] ps = parseParams();
        int n = p(ps, 0, 1);
        switch (f) {
            case 'A':
                row = Math.max(0, row - n);
                pendingWrap = false;
                break;
            case 'B':
            case 'e':
                row = Math.min(rows - 1, row + n);
                pendingWrap = false;
                break;
            case 'C':
            case 'a':
                col = Math.min(cols - 1, col + n);
                pendingWrap = false;
                break;
            case 'D':
                col = Math.max(0, col - n);
                pendingWrap = false;
                break;
            case 'E':
                row = Math.min(rows - 1, row + n);
                col = 0;
                pendingWrap = false;
                break;
            case 'F':
                row = Math.max(0, row - n);
                col = 0;
                pendingWrap = false;
                break;
            case 'G':
            case '`':
                col = Math.max(0, Math.min(cols - 1, p(ps, 0, 1) - 1));
                pendingWrap = false;
                break;
            case 'H':
            case 'f':
                row = Math.max(0, Math.min(rows - 1, p(ps, 0, 1) - 1));
                col = Math.max(0, Math.min(cols - 1, p(ps, 1, 1) - 1));
                pendingWrap = false;
                break;
            case 'd':
                row = Math.max(0, Math.min(rows - 1, p(ps, 0, 1) - 1));
                pendingWrap = false;
                break;
            case 'J':
                eraseDisplay(ps.length == 0 || ps[0] < 0 ? 0 : ps[0]);
                break;
            case 'K':
                eraseLine(p(ps, 0, 0));
                break;
            case 'L':
                insertLines(n);
                break;
            case 'M':
                deleteLines(n);
                break;
            case '@':
                insertChars(n);
                break;
            case 'P':
                deleteChars(n);
                break;
            case 'X':
                eraseChars(n);
                break;
            case 'S':
                scrollUp(n);
                break;
            case 'T':
                scrollDown(n);
                break;
            case 'c':
                if (prefix == 0 || prefix == '>') respond("\u001b[?1;2c");
                break;
            case 'n':
                if (p(ps, 0, 0) == 6) respond("\u001b[" + (row + 1) + ";" + (col + 1) + "R");
                else if (p(ps, 0, 0) == 5) respond("\u001b[0n");
                break;
            case 'm':
                sgr(ps);
                break;
            case 'r':
                int t = p(ps, 0, 1) - 1;
                int b = p(ps, 1, rows) - 1;
                if (t >= 0 && b > t && b < rows) {
                    top = t;
                    bottom = b;
                } else {
                    top = 0;
                    bottom = rows - 1;
                }
                row = 0;
                col = 0;
                pendingWrap = false;
                break;
            case 'h':
            case 'l':
                if (prefix == '?') {
                    for (int m : ps) if (m > 0) mode(m, f == 'h');
                }
                break;
            case 's':
                savedRow = row;
                savedCol = col;
                break;
            case 'u':
                row = Math.min(savedRow, rows - 1);
                col = Math.min(savedCol, cols - 1);
                break;
            default:
                break;
        }
    }

    private void respond(String s) {
        if (responder != null) responder.send(s);
    }

    private void mode(int m, boolean on) {
        switch (m) {
            case 1:
                appCursor = on;
                break;
            case 7:
                autowrap = on;
                break;
            case 25:
                cursorVisible = on;
                break;
            case 47:
            case 1047:
            case 1049:
                switchScreen(on, m == 1049);
                break;
            default:
                break;
        }
    }

    private void switchScreen(boolean on, boolean saveCursor) {
        if (on && !altScreen) {
            if (saveCursor) {
                savedRow = row;
                savedCol = col;
            }
            mainScreen = screen;
            screen = blankScreen(rows, cols);
            altScreen = true;
        } else if (!on && altScreen) {
            screen = mainScreen;
            mainScreen = null;
            altScreen = false;
            if (saveCursor) {
                row = Math.min(savedRow, rows - 1);
                col = Math.min(savedCol, cols - 1);
            }
        }
        top = 0;
        bottom = rows - 1;
        row = on && !saveCursor ? 0 : row;
        col = on && !saveCursor ? 0 : col;
        pendingWrap = false;
    }

    private void sgr(int[] ps) {
        if (ps.length == 0) {
            resetAttrs();
            return;
        }
        for (int i = 0; i < ps.length; i++) {
            int v = ps[i] < 0 ? 0 : ps[i];
            if (v == 0) resetAttrs();
            else if (v == 1) curAttr |= BOLD;
            else if (v == 4) curAttr |= UNDERLINE;
            else if (v == 7) curAttr |= REVERSE;
            else if (v == 22) curAttr &= ~BOLD;
            else if (v == 24) curAttr &= ~UNDERLINE;
            else if (v == 27) curAttr &= ~REVERSE;
            else if (v >= 30 && v <= 37) curFg = v - 30;
            else if (v == 39) curFg = -1;
            else if (v >= 40 && v <= 47) curBg = v - 40;
            else if (v == 49) curBg = -1;
            else if (v >= 90 && v <= 97) curFg = v - 90 + 8;
            else if (v >= 100 && v <= 107) curBg = v - 100 + 8;
            else if (v == 38 || v == 48) {
                boolean fgSel = v == 38;
                if (i + 1 < ps.length && ps[i + 1] == 5 && i + 2 < ps.length) {
                    int color = Math.max(0, Math.min(255, ps[i + 2]));
                    if (fgSel) curFg = color;
                    else curBg = color;
                    i += 2;
                } else if (i + 1 < ps.length && ps[i + 1] == 2) {
                    i = Math.min(ps.length - 1, i + 4); // truecolor: se ignora
                }
            }
        }
    }

    private void resetAttrs() {
        curFg = -1;
        curBg = -1;
        curAttr = 0;
    }

    private void reset() {
        screen = blankScreen(rows, cols);
        mainScreen = null;
        altScreen = false;
        history.clear();
        row = 0;
        col = 0;
        top = 0;
        bottom = rows - 1;
        pendingWrap = false;
        resetAttrs();
        cursorVisible = true;
        appCursor = false;
    }

    private void putChar(int c) {
        if (pendingWrap) {
            if (autowrap) {
                col = 0;
                lineFeed();
            }
            pendingWrap = false;
        }
        if (col >= cols) col = cols - 1;
        Line l = screen[row];
        l.cp[col] = c;
        l.fg[col] = curFg;
        l.bg[col] = curBg;
        l.attr[col] = curAttr;
        col++;
        if (col >= cols) {
            col = cols - 1;
            pendingWrap = autowrap;
        }
    }

    private void lineFeed() {
        pendingWrap = false;
        if (row == bottom) scrollUp(1);
        else if (row < rows - 1) row++;
    }

    private void reverseIndex() {
        pendingWrap = false;
        if (row == top) scrollDown(1);
        else if (row > 0) row--;
    }

    private void scrollUp(int n) {
        n = Math.min(n, bottom - top + 1);
        boolean keep = top == 0 && bottom == rows - 1 && !altScreen;
        for (int k = 0; k < n; k++) {
            Line removed = screen[top];
            if (keep) {
                history.add(removed);
                if (history.size() > MAX_HISTORY) history.remove(0);
            }
            for (int i = top; i < bottom; i++) screen[i] = screen[i + 1];
            screen[bottom] = new Line(cols);
        }
    }

    private void scrollDown(int n) {
        n = Math.min(n, bottom - top + 1);
        for (int k = 0; k < n; k++) {
            for (int i = bottom; i > top; i--) screen[i] = screen[i - 1];
            screen[top] = new Line(cols);
        }
    }

    private void eraseDisplay(int mode) {
        if (mode == 0) {
            eraseLine(0);
            for (int i = row + 1; i < rows; i++) screen[i] = new Line(cols);
        } else if (mode == 1) {
            for (int i = 0; i < row; i++) screen[i] = new Line(cols);
            eraseLine(1);
        } else {
            for (int i = 0; i < rows; i++) screen[i] = new Line(cols);
            if (mode == 3) history.clear();
        }
    }

    private void eraseLine(int mode) {
        Line l = screen[row];
        int from = mode == 1 ? 0 : col;
        int to = mode == 0 ? cols - 1 : (mode == 1 ? col : cols - 1);
        if (mode == 2) {
            from = 0;
            to = cols - 1;
        }
        for (int c = from; c <= to; c++) blank(l, c);
    }

    private void blank(Line l, int c) {
        l.cp[c] = ' ';
        l.fg[c] = -1;
        l.bg[c] = curBg;
        l.attr[c] = 0;
    }

    private void insertLines(int n) {
        if (row < top || row > bottom) return;
        n = Math.min(n, bottom - row + 1);
        for (int i = bottom; i >= row + n; i--) screen[i] = screen[i - n];
        for (int i = row; i < row + n; i++) screen[i] = new Line(cols);
        col = 0;
        pendingWrap = false;
    }

    private void deleteLines(int n) {
        if (row < top || row > bottom) return;
        n = Math.min(n, bottom - row + 1);
        for (int i = row; i <= bottom - n; i++) screen[i] = screen[i + n];
        for (int i = bottom - n + 1; i <= bottom; i++) screen[i] = new Line(cols);
        col = 0;
        pendingWrap = false;
    }

    private void insertChars(int n) {
        Line l = screen[row];
        n = Math.min(n, cols - col);
        for (int c = cols - 1; c >= col + n; c--) {
            l.cp[c] = l.cp[c - n];
            l.fg[c] = l.fg[c - n];
            l.bg[c] = l.bg[c - n];
            l.attr[c] = l.attr[c - n];
        }
        for (int c = col; c < col + n; c++) blank(l, c);
    }

    private void deleteChars(int n) {
        Line l = screen[row];
        n = Math.min(n, cols - col);
        for (int c = col; c < cols - n; c++) {
            l.cp[c] = l.cp[c + n];
            l.fg[c] = l.fg[c + n];
            l.bg[c] = l.bg[c + n];
            l.attr[c] = l.attr[c + n];
        }
        for (int c = cols - n; c < cols; c++) blank(l, c);
    }

    private void eraseChars(int n) {
        Line l = screen[row];
        for (int c = col; c < Math.min(cols, col + n); c++) blank(l, c);
    }
}
