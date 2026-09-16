package com.example.obdmonitor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.DateFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes one CSV per connection: a column per supported PID, a row per
 * completed polling round.
 *
 * The adapter answers one PID at a time, so a row is not an instantaneous
 * snapshot - it holds the newest value of each parameter as of the end of
 * that round. The round's start and end timestamps are both recorded so the
 * spread is visible.
 */
public class TripLogger {

    /** Excel opens ";"-separated files without an import dialog in most locales. */
    private static final String SEPARATOR = ";";

    private final Context context;
    private final List<String> pids;
    private final Map<String, String> currentRow = new LinkedHashMap<>();

    private Writer writer;
    private Uri mediaStoreUri;
    private String location;
    private long sessionStart;
    private long roundStart;
    private int rowCount;

    public TripLogger(Context context, List<String> pids) {
        this.context = context.getApplicationContext();
        this.pids = pids;
    }

    /** @return where the file was created, or null if it couldn't be created. */
    public String start(String protocol) throws IOException {
        sessionStart = System.currentTimeMillis();
        roundStart = sessionStart;
        String name = "obd-log-" + DateFormat.format("yyyy-MM-dd-HH-mm-ss", sessionStart) + ".csv";
        writer = new OutputStreamWriter(openStream(name), StandardCharsets.UTF_8);

        writer.write("# OBD Monitor\n");
        writer.write("# Начало записи: " + DateFormat.format("yyyy-MM-dd HH:mm:ss", sessionStart) + "\n");
        writer.write("# Протокол: " + protocol + "\n");
        writer.write("# Параметров в записи: " + pids.size() + "\n");
        writer.write("# Адаптер опрашивает параметры по очереди, поэтому значения в строке\n");
        writer.write("# собраны за один круг опроса, а не строго в один момент.\n");

        StringBuilder header = new StringBuilder("Время;Секунда от старта;Длительность круга, с");
        for (String pid : pids) {
            header.append(SEPARATOR).append("01").append(pid).append(' ').append(PidCatalog.name(pid));
        }
        writer.write(header.append('\n').toString());
        writer.flush();
        return location;
    }

    /** Remembers the newest value of one parameter for the row being built. */
    public void record(String pid, Double value, String rawHex) {
        if (writer == null) return;
        currentRow.put(pid, value != null
                ? String.format(Locale.US, "%.2f", value).replace('.', ',')
                : rawHex);
    }

    /** Called when the poller has been through every logged PID once. */
    public void endRound() {
        if (writer == null || currentRow.isEmpty()) return;
        long now = System.currentTimeMillis();
        StringBuilder row = new StringBuilder();
        row.append(DateFormat.format("HH:mm:ss", now));
        row.append(SEPARATOR).append(decimal((now - sessionStart) / 1000.0));
        row.append(SEPARATOR).append(decimal((now - roundStart) / 1000.0));
        for (String pid : pids) {
            String value = currentRow.get(pid);
            row.append(SEPARATOR).append(value == null ? "" : value);
        }
        try {
            writer.write(row.append('\n').toString());
            writer.flush(); // a disconnect can come at any moment
            rowCount++;
        } catch (IOException ignored) {
            // Losing a row is not worth interrupting the drive over.
        }
        roundStart = now;
        currentRow.clear();
    }

    public boolean isRunning() {
        return writer != null;
    }

    public int rowCount() {
        return rowCount;
    }

    public String location() {
        return location;
    }

    public void stop() {
        if (writer == null) return;
        try {
            writer.write("# Конец записи: "
                    + DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())
                    + ", строк: " + rowCount + "\n");
            writer.close();
        } catch (IOException ignored) {
        }
        writer = null;
        publish();
        currentRow.clear();
    }

    private String decimal(double value) {
        return String.format(Locale.US, "%.1f", value).replace('.', ',');
    }

    private OutputStream openStream(String name) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            mediaStoreUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (mediaStoreUri == null) throw new IOException("нет доступа к папке Загрузки");
            OutputStream stream = resolver.openOutputStream(mediaStoreUri);
            if (stream == null) throw new IOException("не удалось открыть файл");
            location = "Загрузки/" + name;
            return stream;
        }
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloads.exists() && !downloads.mkdirs()) {
            throw new IOException("нет папки Загрузки");
        }
        File file = new File(downloads, name);
        location = file.getAbsolutePath();
        return new FileOutputStream(file);
    }

    /** Until IS_PENDING is cleared the file stays invisible to other apps. */
    private void publish() {
        if (mediaStoreUri == null) return;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        context.getContentResolver().update(mediaStoreUri, values, null, null);
        mediaStoreUri = null;
    }
}
