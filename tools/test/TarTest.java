import dev.codesnack.linuxterm.TarExtractor;
import java.io.*;
import java.nio.file.*;

/** Prueba de escritorio: extrae el paquete real y comprueba enlaces y permisos. */
public class TarTest {
    public static void main(String[] a) throws Exception {
        File dest = new File(a[1]);
        try (InputStream in = new FileInputStream(a[0])) {
            TarExtractor.extractGzip(in, dest, (t, l) -> Files.createSymbolicLink(l.toPath(), Paths.get(t)));
        }
        File py = new File(dest, "usr/bin/python3.14");
        File vim = new File(dest, "usr/libexec/vim/vim");
        File vi = new File(dest, "usr/bin/vim");
        System.out.println("python3.14 ejecutable: " + py.canExecute() + " size=" + py.length());
        System.out.println("vim ejecutable: " + vim.canExecute() + " size=" + vim.length());
        System.out.println("vim enlace a: " + Files.readSymbolicLink(vi.toPath()) + " -> existe=" + vi.exists());
        System.out.println("libpython: " + new File(dest, "usr/lib/libpython3.14.so").length());
        System.out.println("cert.pem: " + new File(dest, "usr/etc/tls/cert.pem").length());
    }
}
