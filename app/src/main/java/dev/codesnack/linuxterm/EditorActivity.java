package dev.codesnack.linuxterm;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Editor de texto táctil para los archivos de la carpeta de inicio.
 * Extra "path": ruta absoluta del archivo (puede no existir todavía).
 * Al pulsar "Guardar y ejecutar" devuelve el comando a ejecutar en la terminal.
 */
public class EditorActivity extends Activity {
    static final String EXTRA_PATH = "path";
    static final String RESULT_RUN = "run";

    private File file;
    private EditText text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        file = new File(path == null ? new File(Bootstrap.home(this), "nuevo.txt").getAbsolutePath() : path);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 18, 20));
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(file.getName());
        title.setTextColor(Color.rgb(90, 200, 255));
        title.setTextSize(15);
        root.addView(title);

        text = new EditText(this);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextColor(Color.rgb(220, 230, 220));
        text.setTextSize(14);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text.setBackgroundColor(Color.rgb(24, 26, 30));
        text.setText(readFile());
        text.setSelection(0);

        ScrollView sv = new ScrollView(this);
        sv.addView(text);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, pad, 0, 0);

        Button save = new Button(this);
        save.setText("Guardar");
        save.setOnClickListener(v -> save());
        row.addView(save, new LinearLayout.LayoutParams(0, -2, 1f));

        Button run = new Button(this);
        run.setText("Guardar y ejecutar");
        run.setOnClickListener(v -> saveAndRun());
        row.addView(run, new LinearLayout.LayoutParams(0, -2, 1f));

        Button close = new Button(this);
        close.setText("Cerrar");
        close.setOnClickListener(v -> finish());
        row.addView(close, new LinearLayout.LayoutParams(0, -2, 1f));

        root.addView(row);
        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String readFile() {
        if (!file.exists()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        } catch (IOException e) {
            toast("No se pudo leer: " + e.getMessage());
            return "";
        }
    }

    private boolean save() {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getText().toString().getBytes("UTF-8"));
            toast("Guardado: " + file.getName());
            return true;
        } catch (IOException e) {
            toast("No se pudo guardar: " + e.getMessage());
            return false;
        }
    }

    private void saveAndRun() {
        if (!save()) return;
        String name = file.getName();
        String cmd;
        if (name.endsWith(".py")) {
            cmd = "python3 " + quote(relative());
        } else if (name.endsWith(".sh")) {
            cmd = "sh " + quote(relative());
        } else {
            toast("Solo se pueden ejecutar archivos .py y .sh");
            return;
        }
        Intent data = new Intent();
        data.putExtra(RESULT_RUN, cmd);
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    /** Ruta relativa a la carpeta de inicio, para que la terminal la use tal cual. */
    private String relative() {
        String home = Bootstrap.home(this).getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.startsWith(home + "/") ? path.substring(home.length() + 1) : path;
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
