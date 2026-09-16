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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // Cycle through these PIDs while connected - the common dashboard set.
    private static final String[] POLL_PIDS = {"010C", "010D", "0105", "0104", "0111", "012F"};
    private static final long POLL_INTERVAL_MS = 300;

    private BluetoothAdapter bluetoothAdapter;
    private BleObdManager obdManager;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private int pollIndex = 0;
    private boolean connected = false;

    private TextView statusText;
    private View statusDot;
    private Button connectButton;
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

        pollHandler.postDelayed(() -> {
            setConnectedUi(true);
            appendLog("Готово, опрашиваем параметры двигателя");
            pollIndex = 0;
            pollHandler.post(pollRunnable);
        }, 1200);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected) return;
            String pid = POLL_PIDS[pollIndex % POLL_PIDS.length];
            pollIndex++;
            obdManager.sendCommand(pid);
            pollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void setConnectedUi(boolean isConnected) {
        connected = isConnected;
        statusText.setText(isConnected ? "Подключено" : "Не подключено");
        statusDot.setBackgroundResource(isConnected ? R.drawable.dot_green : R.drawable.dot_red);
        connectButton.setText(isConnected ? "Отключить" : "Подключить");
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
        final TextView valueView;
        final ProgressBar progressView;
        final String unit;
        final int maxForBar;

        RowViews(View root, String label, String unit, int maxForBar) {
            ((TextView) root.findViewById(R.id.rowLabel)).setText(label);
            this.valueView = root.findViewById(R.id.rowValue);
            this.progressView = root.findViewById(R.id.rowProgress);
            this.unit = unit;
            this.maxForBar = maxForBar;
        }

        void update(double value) {
            valueView.setText(String.format("%.0f %s", value, unit));
            int percent = (int) Math.max(0, Math.min(100, (value / maxForBar) * 100));
            progressView.setProgress(percent);
        }
    }
}
