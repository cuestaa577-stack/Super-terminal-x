package dev.codesnack.linuxterm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Explorador de la carpeta de inicio: entra en carpetas, abre archivos en el
 * editor y crea archivos nuevos. Si el editor pide ejecutar algo, lo devuelve
 * a la terminal.
 */
public class FilesActivity extends Activity {
    private static final int REQ_EDIT = 1;

    private File dir;
    private TextView pathView;
    private ArrayAdapter<String> adapter;
    private final List<File> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dir = Bootstrap.home(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 18, 20));
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        pathView = new TextView(this);
        pathView.setTextColor(Color.rgb(90, 200, 255));
        pathView.setTextSize(14);
        pathView.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(pathView);

        ListView list = new ListView(this);
        adapter = new Row(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(position));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pad, 0, 0);

        Button up = new Button(this);
        up.setText("Subir");
        up.setOnClickListener(v -> goUp());
        row.addView(up, new LinearLayout.LayoutParams(0, -2, 1f));

        Button create = new Button(this);
        create.setText("Nuevo archivo");
        create.setOnClickListener(v -> askNewFile());
        row.addView(create, new LinearLayout.LayoutParams(0, -2, 1f));

        Button back = new Button(this);
        back.setText("Volver");
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(0, -2, 1f));

        root.addView(row);
        setContentView(root);
        refresh();
    }

    /** Fila de la lista con texto claro y monoespaciado. */
    static final class Row extends ArrayAdapter<String> {
        Row(Context ctx) {
            super(ctx, android.R.layout.simple_list_item_1, new ArrayList<String>());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) super.getView(position, convertView, parent);
            tv.setTextColor(Color.rgb(220, 230, 220));
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            return tv;
        }
    }

    private void refresh() {
        pathView.setText(dir.getAbsolutePath());
        File[] files = dir.listFiles();
        List<File> entries = new ArrayList<>();
        if (files != null) entries.addAll(Arrays.asList(files));
        // Carpetas primero, luego archivos, sin ocultos (.jobs, etc.).
        entries.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        shown.clear();
        adapter.clear();
        for (File f : entries) {
            if (f.getName().startsWith(".")) continue;
            shown.add(f);
            adapter.add(f.isDirectory() ? f.getName() + "/" : f.getName());
        }
        if (shown.isEmpty()) adapter.add("(carpeta vacía)");
        adapter.notifyDataSetChanged();
    }

    private void open(int position) {
        if (position >= shown.size()) return;
        File f = shown.get(position);
        if (f.isDirectory()) {
            dir = f;
            refresh();
        } else {
            Intent i = new Intent(this, EditorActivity.class);
            i.putExtra(EditorActivity.EXTRA_PATH, f.getAbsolutePath());
            startActivityForResult(i, REQ_EDIT);
        }
    }

    private void goUp() {
        File home = Bootstrap.home(this);
        if (dir.equals(home) || dir.getParentFile() == null) return;
        dir = dir.getParentFile();
        refresh();
    }

    private void askNewFile() {
        EditText name = new EditText(this);
        name.setHint("nombre.py");
        name.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Nuevo archivo en " + dir.getName())
                .setView(name)
                .setPositiveButton("Crear", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty() || n.contains("/")) {
                        Toast.makeText(this, "Nombre no válido", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(this, EditorActivity.class);
                    i.putExtra(EditorActivity.EXTRA_PATH, new File(dir, n).getAbsolutePath());
                    startActivityForResult(i, REQ_EDIT);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT) {
            refresh();
            if (resultCode == RESULT_OK && data != null && data.hasExtra(EditorActivity.RESULT_RUN)) {
                // Devuelve el comando a la terminal y cierra el explorador.
                Intent out = new Intent();
                out.putExtra(EditorActivity.RESULT_RUN, data.getStringExtra(EditorActivity.RESULT_RUN));
                setResult(RESULT_OK, out);
                finish();
            }
        }
    }
}
