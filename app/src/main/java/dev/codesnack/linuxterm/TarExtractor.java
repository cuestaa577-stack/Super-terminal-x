package dev.codesnack.linuxterm;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Extractor mínimo de tar.gz (formato ustar, pax y GNU long names).
 * No depende de Android: los enlaces simbólicos los crea un callback,
 * así se puede probar en el escritorio.
 */
public final class TarExtractor {

    /** Crea un enlace simbólico. En Android usa android.system.Os.symlink. */
    public interface Links {
        void symlink(String target, File link) throws IOException;
    }

    private TarExtractor() {
    }

    public static void extractGzip(InputStream raw, File dest, Links links) throws IOException {
        try (InputStream in = new GZIPInputStream(raw, 1 << 16)) {
            extract(in, dest, links);
        }
    }

    public static void extract(InputStream in, File dest, Links links) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("No se pudo crear " + dest);
        byte[] header = new byte[512];
        String longName = null;
        String longLink = null;
        long paxSize = -1;
        String paxPath = null;
        String paxLink = null;

        while (true) {
            if (!readFully(in, header)) break;
            if (isZero(header)) break;

            String name = str(header, 0, 100);
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            String linkName = str(header, 157, 100);
            if (new String(header, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) {
                String prefix = str(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }

            if (type == 'x' || type == 'g') {
                byte[] data = readData(in, size);
                Pax pax = parsePax(data);
                if (pax.path != null) paxPath = pax.path;
                if (pax.linkpath != null) paxLink = pax.linkpath;
                if (pax.size >= 0) paxSize = pax.size;
                continue;
            }
            if (type == 'L') {
                longName = new String(readData(in, size), StandardCharsets.UTF_8).replace("\0", "");
                continue;
            }
            if (type == 'K') {
                longLink = new String(readData(in, size), StandardCharsets.UTF_8).replace("\0", "");
                continue;
            }

            if (paxPath != null) name = paxPath;
            else if (longName != null) name = longName;
            if (paxLink != null) linkName = paxLink;
            else if (longLink != null) linkName = longLink;
            if (paxSize >= 0) size = paxSize;
            paxPath = null;
            paxLink = null;
            paxSize = -1;
            longName = null;
            longLink = null;

            String clean = clean(name);
            if (clean == null) {
                skip(in, size);
                continue;
            }
            File out = new File(dest, clean);

            if (type == '5') {
                if (!out.isDirectory() && !out.mkdirs()) throw new IOException("No se pudo crear " + out);
                skip(in, size);
            } else if (type == '2') {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                if (out.exists() || isLink(out)) out.delete();
                links.symlink(linkName, out);
                skip(in, size);
            } else if (type == '1') {
                // Enlace duro: copiar el archivo ya extraído
                String target = clean(linkName);
                File src = target == null ? null : new File(dest, target);
                if (src == null || !src.isFile()) throw new IOException("Enlace duro roto: " + name);
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                copyFile(src, out);
                skip(in, size);
            } else if (type == '0' || type == '\0' || type == '7') {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                if (out.exists() || isLink(out)) out.delete();
                writeFile(in, size, out);
                int mode = (int) octal(header, 100, 8);
                out.setReadable(true, false);
                out.setWritable((mode & 0200) != 0, true);
                out.setExecutable((mode & 0111) != 0, false);
            } else {
                // Tipos que no hacen falta en el teléfono (dispositivos, FIFOs...)
                skip(in, size);
            }
            // Relleno hasta múltiplo de 512
            long pad = (512 - (size % 512)) % 512;
            skip(in, pad);
        }
    }

    /** Devuelve la ruta relativa segura o null si intenta salir de la carpeta. */
    static String clean(String name) {
        String n = name;
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        if (n.isEmpty() || n.equals(".")) return null;
        for (String part : n.split("/")) {
            if (part.equals("..")) return null;
        }
        return n;
    }

    private static boolean isLink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        dst.setExecutable(src.canExecute(), false);
    }

    private static void writeFile(InputStream in, long size, File out) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            long left = size;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("tar truncado");
                os.write(buf, 0, n);
                left -= n;
            }
        }
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > Integer.MAX_VALUE) throw new IOException("entrada demasiado grande");
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) size);
        byte[] buf = new byte[(int) Math.min(size, 1 << 16)];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("tar truncado");
            bo.write(buf, 0, n);
            left -= n;
        }
        long pad = (512 - (size % 512)) % 512;
        skip(in, pad);
        return bo.toByteArray();
    }

    private static final class Pax {
        String path;
        String linkpath;
        long size = -1;
    }

    private static Pax parsePax(byte[] data) {
        Pax p = new Pax();
        int i = 0;
        while (i < data.length) {
            int sp = i;
            while (sp < data.length && data[sp] != ' ') sp++;
            int len = Integer.parseInt(new String(data, i, sp - i, StandardCharsets.US_ASCII));
            String rec = new String(data, sp + 1, Math.max(0, i + len - sp - 2), StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String key = rec.substring(0, eq);
                String val = rec.substring(eq + 1);
                if (key.equals("path")) p.path = val;
                else if (key.equals("linkpath")) p.linkpath = val;
                else if (key.equals("size")) p.size = Long.parseLong(val);
            }
            if (len <= 0) break;
            i += len;
        }
        return p;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return off == 0 ? false : throwTruncated();
            off += n;
        }
        return true;
    }

    private static boolean throwTruncated() throws IOException {
        throw new IOException("tar truncado");
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new IOException("tar truncado");
            left -= r;
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') {
                if (v != 0) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }
}
