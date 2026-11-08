package dev.codesnack.linuxterm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Comandos de diagnóstico de la terminal (versión Java de syscmd).
 * Solo lee /proc, /sys y File: no depende de Android, así se puede probar en el escritorio.
 */
public final class Syscmd {
    public static final String VERSION = "1.0.0";
    static final int NCPU = Math.max(1, Runtime.getRuntime().availableProcessors());
    static final long PAGE = 4096L;

    static final String HELP =
            "Comandos de diagnóstico:\n"
            + "  diag                      diagnóstico del sistema y avisos\n"
            + "  mon [segundos]            monitor en tiempo real (botón Parar o escribe parar)\n"
            + "  procs [-m] [N] [filtro]   procesos por CPU (o por memoria con -m)\n"
            + "                            ejemplos: procs 10 | procs -m | procs java\n"
            + "  limpiar                   borra la pantalla\n"
            + "  ejemplos                  muestra la carpeta ~/examples\n"
            + "  help                      esta ayuda\n"
            + "Cualquier otro comando (ls, ps, cat, df...) se ejecuta en la shell de Android.";

    private static long[] lastCpu = null;

    private Syscmd() {}

    static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    static String human(long bytes) {
        double n = bytes;
        String[] u = {"B", "KiB", "MiB", "GiB", "TiB"};
        int i = 0;
        while (n >= 1024 && i < 4) {
            n /= 1024;
            i++;
        }
        return i == 0 ? (long) n + " B" : String.format(Locale.US, "%.1f %s", n, u[i]);
    }

    static String bar(double pct, int width) {
        pct = Math.max(0, Math.min(100, pct));
        int filled = (int) Math.round(width * pct / 100.0);
        return "[" + rep('#', filled) + rep('-', width - filled) + "]";
    }

    static String rep(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    static String pct(double p) {
        return String.format(Locale.US, "%5.1f%%", p);
    }

    static String fmtUptime(long s) {
        return String.format(Locale.US, "%dd %02d:%02d:%02d", s / 86400, (s % 86400) / 3600, (s % 3600) / 60, s % 60);
    }

    /** Memoria en bytes desde /proc/meminfo. */
    static Map<String, Long> meminfo() {
        Map<String, Long> info = new HashMap<>();
        for (String line : read("/proc/meminfo").split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 2 && p[0].endsWith(":")) {
                try {
                    info.put(p[0].substring(0, p[0].length() - 1), Long.parseLong(p[1]) * 1024L);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return info;
    }

    /** {total, idle} acumulados de la CPU, en ticks. */
    static long[] cpuTimes() {
        for (String line : read("/proc/stat").split("\n")) {
            if (line.startsWith("cpu ")) {
                String[] p = line.trim().split("\\s+");
                long total = 0;
                for (int i = 1; i <= 8 && i < p.length; i++) total += Long.parseLong(p[i]);
                long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
                return new long[] {total, idle};
            }
        }
        return new long[] {0, 0};
    }

    static double cpuBetween(long[] a, long[] b) {
        long dt = b[0] - a[0];
        return dt <= 0 ? 0 : 100.0 * (1 - (double) (b[1] - a[1]) / dt);
    }

    static String loadAvg() {
        String[] p = read("/proc/loadavg").trim().split("\\s+");
        return p.length >= 3 ? p[0] + " " + p[1] + " " + p[2] : "no disponible";
    }

    static long uptimeSeconds() {
        String[] p = read("/proc/uptime").trim().split("\\s+");
        try {
            return (long) Double.parseDouble(p[0]);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** Diagnóstico completo. osInfo: texto corto del dispositivo; dataDir: carpeta para medir disco. */
    public static String diag(String osInfo, File dataDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("== Diagnóstico del sistema ==\n");
        sb.append("Sistema      : ").append(osInfo).append('\n');
        sb.append("Kernel       : ").append(System.getProperty("os.version")).append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("CPUs         : ").append(NCPU).append('\n');
        sb.append("Uptime       : ").append(fmtUptime(uptimeSeconds())).append('\n');
        sb.append("Carga (1/5/15): ").append(loadAvg()).append('\n');

        Map<String, Long> m = meminfo();
        long mt = m.getOrDefault("MemTotal", 0L);
        long ma = m.getOrDefault("MemAvailable", m.getOrDefault("MemFree", 0L));
        long mu = Math.max(0, mt - ma);
        double mp = mt > 0 ? 100.0 * mu / mt : 0;
        sb.append("Memoria      : ").append(bar(mp, 20)).append(' ').append(human(mu)).append(" / ").append(human(mt))
                .append(String.format(Locale.US, " (%.0f%%)", mp)).append('\n');
        long st = m.getOrDefault("SwapTotal", 0L);
        if (st > 0) {
            long su = Math.max(0, st - m.getOrDefault("SwapFree", 0L));
            sb.append("Swap         : ").append(human(su)).append(" / ").append(human(st)).append('\n');
        } else {
            sb.append("Swap         : no configurado\n");
        }

        if (dataDir != null) {
            long total = dataDir.getTotalSpace();
            long free = dataDir.getUsableSpace();
            long used = Math.max(0, total - free);
            double dp = total > 0 ? 100.0 * used / total : 0;
            sb.append("Almacenam.   : ").append(bar(dp, 20)).append(' ').append(human(used)).append(" / ").append(human(total))
                    .append(String.format(Locale.US, " (%.0f%%)", dp)).append('\n');
        }

        sb.append("\nRed (interfaces):\n");
        File[] nets = new File("/sys/class/net").listFiles();
        boolean any = false;
        if (nets != null) {
            Arrays.sort(nets);
            for (File n : nets) {
                String state = read(new File(n, "operstate").getPath()).trim();
                if (!state.isEmpty()) {
                    sb.append(String.format(Locale.US, "  %-12s %s%n", n.getName(), state));
                    any = true;
                }
            }
        }
        if (!any) sb.append("  no disponible en Android\n");

        sb.append("\nAvisos:\n");
        List<String> avisos = new ArrayList<>();
        double la1 = 0;
        try {
            la1 = Double.parseDouble(loadAvg().split(" ")[0]);
        } catch (RuntimeException ignored) {
        }
        if (la1 > NCPU) avisos.add(String.format(Locale.US, "Carga alta: %.2f con %d CPUs", la1, NCPU));
        if (mt > 0 && ma < 0.1 * mt) avisos.add("Memoria disponible por debajo del 10%");
        if (st > 0 && (st - m.getOrDefault("SwapFree", 0L)) > 0.5 * st) avisos.add("Swap por encima del 50% en uso");
        if (avisos.isEmpty()) sb.append("  Sin avisos.\n");
        for (String a : avisos) sb.append("  ! ").append(a).append('\n');
        return trimEnd(sb.toString());
    }

    /** Un proceso visible en /proc. */
    static final class Proc {
        int pid;
        int ppid;
        String state = "?";
        String comm = "";
        String cmd = "";
        long ticks;
        long rss;
        double cpu;
    }

    static Map<Integer, Proc> readProcesses() {
        Map<Integer, Proc> procs = new HashMap<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return procs;
        for (File d : dirs) {
            String name = d.getName();
            if (!name.matches("\\d+")) continue;
            String stat = read(new File(d, "stat").getPath());
            int lp = stat.indexOf('(');
            int rp = stat.lastIndexOf(')');
            if (lp < 0 || rp < 0) continue;
            Proc p = new Proc();
            p.pid = Integer.parseInt(name);
            p.comm = stat.substring(lp + 1, rp);
            String[] f = stat.substring(rp + 2).trim().split("\\s+");
            try {
                p.state = f[0];
                p.ppid = Integer.parseInt(f[1]);
                p.ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
            } catch (RuntimeException e) {
                continue;
            }
            String[] sm = read(new File(d, "statm").getPath()).trim().split("\\s+");
            try {
                p.rss = Long.parseLong(sm[1]) * PAGE;
            } catch (RuntimeException ignored) {
            }
            String cmd = read(new File(d, "cmdline").getPath()).replace('\0', ' ').trim();
            p.cmd = cmd.isEmpty() ? "[" + p.comm + "]" : cmd;
            procs.put(p.pid, p);
        }
        return procs;
    }

    /** procs [-m] [N] [filtro]. Tarda medio segundo en medir la CPU. */
    public static String procs(String[] args) {
        boolean byMem = false;
        int limit = 15;
        String filt = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-m") || a.equals("--mem")) {
                byMem = true;
            } else if ((a.equals("-n") || a.equals("--num")) && i + 1 < args.length) {
                try {
                    limit = Math.max(1, Integer.parseInt(args[++i]));
                } catch (NumberFormatException e) {
                    return "Número no válido.";
                }
            } else if (a.matches("\\d+")) {
                limit = Math.max(1, Integer.parseInt(a));
            } else {
                filt = a.toLowerCase(Locale.ROOT);
            }
        }

        long[] t1 = cpuTimes();
        Map<Integer, Proc> first = readProcesses();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long[] t2 = cpuTimes();
        Map<Integer, Proc> second = readProcesses();
        long dtTotal = Math.max(1, t2[0] - t1[0]);

        List<Proc> rows = new ArrayList<>();
        for (Proc p : second.values()) {
            Proc old = first.get(p.pid);
            long dt = p.ticks - (old != null ? old.ticks : p.ticks);
            p.cpu = 100.0 * dt * NCPU / dtTotal;
            if (filt != null && !(p.cmd.toLowerCase(Locale.ROOT).contains(filt)
                    || p.comm.toLowerCase(Locale.ROOT).contains(filt))) continue;
            rows.add(p);
        }
        final boolean sortByMem = byMem;
        rows.sort((a, b) -> sortByMem ? Long.compare(b.rss, a.rss) : Double.compare(b.cpu, a.cpu));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%7s  %7s  %6s  %10s  %-3s  %s%n", "PID", "PPID", "CPU%", "MEM", "EST", "COMANDO"));
        int shown = 0;
        for (Proc p : rows) {
            if (shown++ >= limit) break;
            String cmd = p.cmd.length() > 60 ? p.cmd.substring(0, 60) : p.cmd;
            sb.append(String.format(Locale.US, "%7d  %7d  %6.1f  %10s  %-3s  %s%n", p.pid, p.ppid, p.cpu, human(p.rss), p.state, cmd));
        }
        sb.append(String.format(Locale.US, "Mostrando %d de %d procesos", Math.min(limit, rows.size()), rows.size()));
        if (filt != null) sb.append(" que coinciden con '").append(filt).append('\'');
        sb.append('.');
        return sb.toString();
    }

    /** Reinicia la medida de CPU del monitor. */
    public static void monReset() {
        lastCpu = cpuTimes();
    }

    /** Una vista del monitor: CPU desde la vez anterior, memoria, carga y top 5 por memoria. */
    public static String monFrame() {
        long[] now = cpuTimes();
        double cpu = lastCpu == null ? 0 : cpuBetween(lastCpu, now);
        lastCpu = now;
        Map<String, Long> m = meminfo();
        long mt = m.getOrDefault("MemTotal", 0L);
        long ma = m.getOrDefault("MemAvailable", m.getOrDefault("MemFree", 0L));
        long mu = Math.max(0, mt - ma);
        double mp = mt > 0 ? 100.0 * mu / mt : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("== Monitor de recursos ==\n");
        sb.append(String.format(Locale.US, "CPU     %s %s  (%d CPUs)%n", bar(cpu, 20), pct(cpu), NCPU));
        sb.append(String.format(Locale.US, "Memoria %s %s  %s / %s%n", bar(mp, 20), pct(mp), human(mu), human(mt)));
        sb.append("Carga   ").append(loadAvg()).append("     Uptime ").append(fmtUptime(uptimeSeconds())).append('\n');
        sb.append("\nTop 5 por memoria:\n");
        sb.append(String.format(Locale.US, "%7s  %10s  %-6s  %s%n", "PID", "MEM", "ESTADO", "COMANDO"));
        List<Proc> top = new ArrayList<>(readProcesses().values());
        top.sort((a, b) -> Long.compare(b.rss, a.rss));
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            Proc p = top.get(i);
            String cmd = p.cmd.length() > 40 ? p.cmd.substring(0, 40) : p.cmd;
            sb.append(String.format(Locale.US, "%7d  %10s  %-6s  %s%n", p.pid, human(p.rss), p.state, cmd));
        }
        return trimEnd(sb.toString());
    }
}
