package com.example.obdmonitor;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    // The dashboard PIDs this app knows how to display, without the "01" mode
    // prefix. Which of them are actually polled is decided at runtime from
    // the car's own support bitmasks.
    private static final String[] APP_PIDS = {"0C", "0D", "05", "04", "11", "2F", "0F"};
    private static final long POLL_INTERVAL_MS = 300;

    private BluetoothAdapter bluetoothAdapter;
    private BleObdManager obdManager;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private int pollIndex = 0;
    private boolean connected = false;

    private final Set<String> supportedPids = new LinkedHashSet<>();
    private final List<String> activePollPids = new ArrayList<>();
    private boolean discoveringPids = false;
    /** CAN replies carry an extra code-count byte; assume CAN until ATDPN says otherwise. */
    private boolean canProtocol = true;
    private boolean readingDtc = false;
    private final List<String> storedDtc = new ArrayList<>();
    private final List<String> pendingDtc = new ArrayList<>();

    private TextView statusText;
    private View statusDot;
    private Button connectButton;
    private Button dtcButton;
    private Button pidListButton;
    private String lastProtocol = "неизвестен";
    private GaugeView rpmGauge;
    private GaugeView speedGauge;
    private TextView logView;

    private final Map<String, BluetoothDevice> foundDevices = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private boolean scanning = false;

    // rows: pid -> (label view, value view, progress view, unit, maxForBar)
    private RowViews coolantRow, loadRow, throttleRow, fuelRow, intakeRow;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) allGranted &= Boolean.TRUE.equals(granted);
                if (allGranted) {
                    startScan();
                } else {
                    Toast.makeText(this, "Нужны разрешения на Bluetooth, чтобы подключиться к адаптеру", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        statusDot = findViewById(R.id.statusDot);
        connectButton = findViewById(R.id.connectButton);
        dtcButton = findViewById(R.id.dtcButton);
        pidListButton = findViewById(R.id.pidListButton);
        rpmGauge = findViewById(R.id.rpmGauge);
        speedGauge = findViewById(R.id.speedGauge);
        logView = findViewById(R.id.logView);
        logView.setMovementMethod(new ScrollingMovementMethod());

        rpmGauge.setMax(8000f);
        rpmGauge.setLabelAndUnit("ОБОРОТЫ", "об/мин");
        speedGauge.setMax(220f);
        speedGauge.setLabelAndUnit("СКОРОСТЬ", "км/ч");

        coolantRow = new RowViews(findViewById(R.id.rowCoolant), "Температура ОЖ", "°C", 130);
        loadRow = new RowViews(findViewById(R.id.rowLoad), "Нагрузка двигателя", "%", 100);
        throttleRow = new RowViews(findViewById(R.id.rowThrottle), "Положение дросселя", "%", 100);
        fuelRow = new RowViews(findViewById(R.id.rowFuel), "Уровень топлива", "%", 100);
        intakeRow = new RowViews(findViewById(R.id.rowIntakeTemp), "Темп. впуск. воздуха", "°C", 80);

        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = btManager.getAdapter();

        obdManager = new BleObdManager(this, obdListener);

        connectButton.setOnClickListener(v -> {
            if (connected) {
                disconnect();
            } else {
                requestPermissionsAndScan();
            }
        });

        dtcButton.setOnClickListener(v -> readDtc());
        pidListButton.setOnClickListener(v -> showPidListDialog());
    }

    // ---------- supported parameter list ----------

    private final ActivityResultLauncher<String> saveReportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri == null) return;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("не удалось открыть файл");
                    out.write(buildPidReport().getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Список сохранён", Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Toast.makeText(this, "Не удалось сохранить: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    private void showPidListDialog() {
        if (supportedPids.isEmpty()) {
            Toast.makeText(this, "Список параметров ещё не получен", Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder message = new StringBuilder();
        for (String pid : supportedPids) {
            boolean shown = activePollPids.contains(pid);
            message.append(shown ? "● " : "○ ")
                    .append(pid).append(" — ").append(PidCatalog.name(pid)).append('\n');
        }
        message.append("\n● — выводится на экран, ○ — доступен, но не выводится");

        new AlertDialog.Builder(this)
                .setTitle("Поддерживается: " + supportedPids.size())
                .setMessage(message.toString())
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("Сохранить в файл",
                        (dialog, which) -> saveReportLauncher.launch("obd-parameters.txt"))
                .show();
    }

    private String buildPidReport() {
        StringBuilder report = new StringBuilder();
        report.append("OBD Monitor — параметры, поддерживаемые автомобилем\n");
        report.append("Дата: ")
                .append(DateFormat.format("yyyy-MM-dd HH:mm", System.currentTimeMillis()))
                .append('\n');
        report.append("Протокол: ").append(lastProtocol).append('\n');
        report.append("Всего поддерживается: ").append(supportedPids.size()).append("\n\n");
        report.append("Запрос — это режим 01 плюс номер параметра, например 010C.\n");
        report.append("[+] отмечены те, что приложение сейчас выводит на экран.\n\n");
        for (String pid : supportedPids) {
            report.append(activePollPids.contains(pid) ? "[+] " : "[ ] ")
                    .append("01").append(pid).append("  ")
                    .append(PidCatalog.name(pid)).append('\n');
        }
        return report.toString();
    }

    // ---------- permissions & scanning ----------

    private void requestPermissionsAndScan() {
        List<String> needed = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            startScan();
        } else {
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_LONG).show();
            return;
        }
        foundDevices.clear();
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        appendLog("Поиск BLE-устройств...");
        scanning = true;
        try {
            scanner.startScan(scanCallback);
        } catch (SecurityException e) {
            appendLog("Нет разрешения на сканирование: " + e.getMessage());
            return;
        }
        pollHandler.postDelayed(this::stopScanAndShowResults, 6000);
    }

    private void stopScanAndShowResults() {
        if (!scanning) return;
        scanning = false;
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) { }

        if (foundDevices.isEmpty()) {
            appendLog("Устройства не найдены. Проверьте, что адаптер вставлен в разъём и мигает.");
            return;
        }
        String[] names = new String[foundDevices.size()];
        List<BluetoothDevice> devices = new ArrayList<>(foundDevices.values());
        for (int i = 0; i < devices.size(); i++) {
            names[i] = deviceLabel(devices.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("Выберите адаптер")
                .setItems(names, (dialog, which) -> connectTo(devices.get(which)))
                .show();
    }

    private String deviceLabel(BluetoothDevice device) {
        String name = "(без имени)";
        try {
            if (device.getName() != null) name = device.getName();
        } catch (SecurityException ignored) { }
        return name + "  " + device.getAddress();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            foundDevices.put(device.getAddress(), device);
        }
    };

    // ---------- connection lifecycle ----------

    private void connectTo(BluetoothDevice device) {
        appendLog("Подключение к " + deviceLabel(device) + "...");
        try {
            obdManager.connect(device);
        } catch (SecurityException e) {
            appendLog("Нет разрешения на подключение: " + e.getMessage());
        }
    }

    private void disconnect() {
        pollHandler.removeCallbacksAndMessages(null);
        try {
            obdManager.disconnect();
        } catch (SecurityException ignored) { }
        setConnectedUi(false);
    }

    private final BleObdManager.Listener obdListener = new BleObdManager.Listener() {
        @Override
        public void onConnected() {
            appendLog("GATT подключен, инициализация ELM327...");
            runInitSequence();
        }

        @Override
        public void onDisconnected() {
            appendLog("Соединение разорвано");
            setConnectedUi(false);
        }

        @Override
        public void onLine(String line) {
            appendLog("< " + line);
            handleResponseLine(line);
        }

        @Override
        public void onResponseComplete(String command, List<String> lines) {
            if (command == null) return;
            if (command.equals("ATDPN")) {
                readProtocolNumber(lines);
            } else if (discoveringPids && isSupportCommand(command)) {
                handleSupportReply(command, lines);
            } else if (readingDtc && (command.equals("03") || command.equals("07"))) {
                handleDtcReply(command, lines);
            }
        }

        @Override
        public void onError(String message) {
            appendLog("Ошибка: " + message);
        }
    };

    private void runInitSequence() {
        // Standard reset/config sequence for ELM327 clones.
        obdManager.sendCommand("ATZ");   // reset
        obdManager.sendCommand("ATE0");  // echo off
        obdManager.sendCommand("ATL0");  // linefeeds off
        obdManager.sendCommand("ATS0");  // spaces off
        obdManager.sendCommand("ATH0");  // headers off
        obdManager.sendCommand("ATSP0"); // auto protocol detect

        supportedPids.clear();
        activePollPids.clear();
        discoveringPids = true;
        obdManager.sendCommand(ObdParser.SUPPORT_PIDS[0]);

        pollHandler.postDelayed(() -> {
            setConnectedUi(true);
            appendLog("Определяем, какие параметры поддерживает машина...");
            pollIndex = 0;
            pollHandler.post(pollRunnable);
        }, 1200);
    }

    /** ATDPN answers with the protocol number, optionally prefixed by 'A' for auto. */
    private void readProtocolNumber(List<String> lines) {
        for (String line : lines) {
            String cleaned = line.replace(" ", "").trim().toUpperCase();
            if (cleaned.isEmpty() || cleaned.equals("OK")) continue;
            char last = cleaned.charAt(cleaned.length() - 1);
            int number = Character.digit(last, 16);
            if (number < 0) continue;
            // Protocols 6 and up are the CAN family (ISO 15765 / J1939).
            canProtocol = number >= 6;
            lastProtocol = cleaned + (canProtocol ? " (CAN)" : " (не CAN)");
            appendLog("Протокол: " + lastProtocol);
            return;
        }
    }

    private boolean isSupportCommand(String command) {
        for (String supportPid : ObdParser.SUPPORT_PIDS) {
            if (supportPid.equals(command)) return true;
        }
        return false;
    }

    private void handleSupportReply(String command, List<String> lines) {
        Set<String> found = null;
        for (String line : lines) {
            Set<String> parsed = ObdParser.parseSupportedPids(line);
            if (parsed != null) {
                found = parsed;
                break;
            }
        }
        if (found != null) supportedPids.addAll(found);

        int rangeIndex = -1;
        for (int i = 0; i < ObdParser.SUPPORT_PIDS.length; i++) {
            if (ObdParser.SUPPORT_PIDS[i].equals(command)) rangeIndex = i;
        }
        // The last bit of each bitmask tells whether the next range exists.
        String nextRangePid = String.format("%02X", (rangeIndex + 1) * 0x20);
        boolean hasNextRange = found != null && found.contains(nextRangePid)
                && rangeIndex + 1 < ObdParser.SUPPORT_PIDS.length;
        if (hasNextRange) {
            obdManager.sendCommand(ObdParser.SUPPORT_PIDS[rangeIndex + 1]);
        } else {
            finishPidDiscovery();
        }
    }

    private void finishPidDiscovery() {
        discoveringPids = false;
        activePollPids.clear();
        if (supportedPids.isEmpty()) {
            // Adapter or car didn't answer the bitmask - fall back to asking
            // for everything and let unsupported PIDs return NO DATA.
            activePollPids.addAll(Arrays.asList(APP_PIDS));
            appendLog("Список поддерживаемых параметров получить не удалось, опрашиваем все");
        } else {
            for (String pid : APP_PIDS) {
                if (supportedPids.contains(pid)) activePollPids.add(pid);
            }
            appendLog("Машина поддерживает " + supportedPids.size()
                    + " параметров, показываем " + activePollPids.size());
        }
        applyRowVisibility();
        dtcButton.setEnabled(true);
        pidListButton.setEnabled(!supportedPids.isEmpty());
        pollIndex = 0;
    }

    /** Hide the rows this car can't answer instead of leaving them at "--". */
    private void applyRowVisibility() {
        boolean knowSupport = !supportedPids.isEmpty();
        coolantRow.setVisible(!knowSupport || supportedPids.contains("05"));
        loadRow.setVisible(!knowSupport || supportedPids.contains("04"));
        throttleRow.setVisible(!knowSupport || supportedPids.contains("11"));
        fuelRow.setVisible(!knowSupport || supportedPids.contains("2F"));
        intakeRow.setVisible(!knowSupport || supportedPids.contains("0F"));
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected) return;
            // Only ask for the next PID once the adapter has answered the
            // previous one - a fixed-rate poller would otherwise pile up
            // requests behind a slow protocol search.
            if (!discoveringPids && !readingDtc && !activePollPids.isEmpty() && obdManager.isIdle()) {
                String pid = activePollPids.get(pollIndex % activePollPids.size());
                pollIndex++;
                obdManager.sendCommand("01" + pid);
            }
            pollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    // ---------- trouble codes ----------

    private void readDtc() {
        if (!connected || readingDtc) return;
        readingDtc = true;
        dtcButton.setEnabled(false);
        storedDtc.clear();
        pendingDtc.clear();
        appendLog("Запрашиваем коды ошибок...");
        // ATDPN only reports a real protocol once one has been negotiated, so
        // ask here rather than during init - and ahead of 03/07, whose replies
        // are decoded differently on CAN.
        obdManager.sendCommand("ATDPN");
        obdManager.sendCommand("03"); // stored codes
        obdManager.sendCommand("07"); // pending codes
    }

    private void handleDtcReply(String command, List<String> lines) {
        if (command.equals("03")) {
            storedDtc.addAll(DtcDecoder.decode(lines, 0x43, canProtocol));
            return;
        }
        pendingDtc.addAll(DtcDecoder.decode(lines, 0x47, canProtocol));
        readingDtc = false;
        dtcButton.setEnabled(true);
        showDtcDialog();
    }

    private void showDtcDialog() {
        if (isFinishing() || isDestroyed()) return;
        StringBuilder message = new StringBuilder();
        if (storedDtc.isEmpty() && pendingDtc.isEmpty()) {
            message.append("Ошибок не найдено.\n\nЕсли лампа Check Engine горит, "
                    + "код может быть в блоке, который не отвечает по стандартным режимам.");
        } else {
            appendCodes(message, "Сохранённые ошибки", storedDtc);
            appendCodes(message, "Ожидающие подтверждения", pendingDtc);
        }
        new AlertDialog.Builder(this)
                .setTitle("Коды ошибок")
                .setMessage(message.toString().trim())
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private void appendCodes(StringBuilder message, String title, List<String> codes) {
        if (codes.isEmpty()) return;
        message.append(title).append(":\n");
        for (String code : codes) {
            message.append("• ").append(code).append(" — ")
                    .append(DtcDecoder.describe(code)).append('\n');
            appendLog("DTC " + code + ": " + DtcDecoder.describe(code));
        }
        message.append('\n');
    }

    private void setConnectedUi(boolean isConnected) {
        connected = isConnected;
        statusText.setText(isConnected ? "Подключено" : "Не подключено");
        statusDot.setBackgroundResource(isConnected ? R.drawable.dot_green : R.drawable.dot_red);
        connectButton.setText(isConnected ? "Отключить" : "Подключить");
        if (!isConnected) {
            dtcButton.setEnabled(false);
            readingDtc = false;
            discoveringPids = false;
            // The discovered list stays valid after a disconnect, so keep the
            // report reachable; only a new connection replaces it.
            pidListButton.setEnabled(!supportedPids.isEmpty());
        }
    }

    // ---------- data handling ----------

    private void handleResponseLine(String line) {
        ObdParser.Result result = ObdParser.parse(line);
        if (result == null) return;
        switch (result.pid) {
            case "0C":
                rpmGauge.setValue((float) result.value);
                break;
            case "0D":
                speedGauge.setValue((float) result.value);
                break;
            case "05":
                coolantRow.update(result.value);
                break;
            case "04":
                loadRow.update(result.value);
                break;
            case "11":
                throttleRow.update(result.value);
                break;
            case "2F":
                fuelRow.update(result.value);
                break;
            case "0F":
                intakeRow.update(result.value);
                break;
        }
    }

    private void appendLog(String text) {
        logView.append(text + "\n");
        android.text.Layout layout = logView.getLayout();
        if (layout != null) {
            int scrollAmount = layout.getLineTop(logView.getLineCount()) - logView.getHeight();
            logView.scrollTo(0, Math.max(scrollAmount, 0));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollHandler.removeCallbacksAndMessages(null);
        try {
            if (obdManager != null) obdManager.disconnect();
        } catch (SecurityException ignored) { }
    }

    /** Small helper bundling the three views of one stat_row.xml include. */
    private static class RowViews {
        final View root;
        final TextView valueView;
        final ProgressBar progressView;
        final String unit;
        final int maxForBar;

        RowViews(View root, String label, String unit, int maxForBar) {
            ((TextView) root.findViewById(R.id.rowLabel)).setText(label);
            this.root = root;
            this.valueView = root.findViewById(R.id.rowValue);
            this.progressView = root.findViewById(R.id.rowProgress);
            this.unit = unit;
            this.maxForBar = maxForBar;
        }

        void setVisible(boolean visible) {
            root.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        void update(double value) {
            valueView.setText(String.format("%.0f %s", value, unit));
            int percent = (int) Math.max(0, Math.min(100, (value / maxForBar) * 100));
            progressView.setProgress(percent);
        }
    }
}
