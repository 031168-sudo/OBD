package com.example.obdmonitor;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Talks to a generic "ELM327 BLE" clone.
 *
 * These cheap adapters almost never implement a proper OBD BLE GATT
 * profile - instead they expose a transparent serial pipe (the same
 * pattern as an HM-10/HM-19 module) and you send plain ELM327 AT/OBD
 * ASCII commands through it, exactly like over classic Bluetooth SPP
 * or USB. So instead of hard-coding one vendor UUID, we scan the
 * discovered services for a characteristic that supports WRITE and
 * one that supports NOTIFY, preferring the well-known UUID pairs
 * used by almost every such clone (FFE0/FFE1, FFF0/FFF1/FFF2).
 */
public class BleObdManager {

    private static final String TAG = "BleObdManager";

    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onLine(String line);          // one parsed response line from the adapter
        /** Every line of one command's reply, once the '>' prompt closed it. */
        void onResponseComplete(String command, List<String> lines);
        void onError(String message);
    }

    // Known UUID prefixes for cheap "serial over BLE" ELM327 clones.
    private static final UUID[] KNOWN_WRITE = {
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
    };
    private static final UUID[] KNOWN_NOTIFY = {
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
    };
    private static final UUID CLIENT_CONFIG_DESCRIPTOR =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;

    private final StringBuilder rxBuffer = new StringBuilder();
    private final ArrayDeque<String> commandQueue = new ArrayDeque<>();
    private boolean waitingForResponse = false;
    private String pendingCommand = null;

    public BleObdManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @RequiresPermission(allOf = {android.Manifest.permission.BLUETOOTH_CONNECT})
    public void connect(BluetoothDevice device) {
        resetState();
        gatt = device.connectGatt(context, false, gattCallback);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void disconnect() {
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        resetState();
    }

    /**
     * Characteristics belong to the closed BluetoothGatt and the in-flight
     * flags describe a connection that no longer exists, so a reconnect
     * that inherits them would never send anything: pumpQueue() bails out
     * while waitingForResponse is still set from the old session.
     */
    private void resetState() {
        mainHandler.removeCallbacks(responseTimeoutRunnable);
        writeChar = null;
        notifyChar = null;
        waitingForResponse = false;
        pendingCommand = null;
        commandQueue.clear();
        rxBuffer.setLength(0);
    }

    /** True when nothing is queued or in flight - the caller may poll again. */
    public boolean isIdle() {
        return !waitingForResponse && commandQueue.isEmpty();
    }

    /** Queue an AT/OBD command; it is sent as soon as the adapter is free. */
    public void sendCommand(String command) {
        commandQueue.add(command);
        pumpQueue();
    }

    // ELM327 auto protocol search (ATSP0) can legitimately take several
    // seconds when the vehicle isn't responding (ignition off, no ECU on
    // the bus) - a short timeout here would otherwise interrupt a search
    // that was still in progress, which makes the adapter abort it with
    // "STOPPED" and immediately start searching again for the next queued
    // command, looping forever instead of ever reporting the real result
    // ("UNABLE TO CONNECT"/"NO DATA").
    private static final long RESPONSE_TIMEOUT_MS = 10000;
    private final Runnable responseTimeoutRunnable = this::onResponseTimeout;

    private void pumpQueue() {
        if (waitingForResponse || gatt == null || writeChar == null) return;
        String next = commandQueue.poll();
        if (next == null) return;
        pendingCommand = next;
        waitingForResponse = true;
        writeRaw(next + "\r");
        mainHandler.removeCallbacks(responseTimeoutRunnable);
        mainHandler.postDelayed(responseTimeoutRunnable, RESPONSE_TIMEOUT_MS);
    }

    /** A BLE write can silently get dropped by the stack; don't stall the queue forever. */
    private void onResponseTimeout() {
        if (!waitingForResponse) return;
        String abandonedCommand = pendingCommand;
        waitingForResponse = false;
        pendingCommand = null;
        rxBuffer.setLength(0);
        // Report the empty reply too: a caller waiting on this command to
        // finish a multi-step sequence would otherwise wait forever.
        listener.onResponseComplete(abandonedCommand, new ArrayList<>());
        pumpQueue();
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void writeRaw(String data) {
        if (gatt == null || writeChar == null) return;
        byte[] bytes = data.getBytes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(writeChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            writeChar.setValue(bytes);
            gatt.writeCharacteristic(writeChar);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Android only allows a limited number of open GATT clients,
                // so a link the adapter dropped on its own has to be closed
                // here or repeated reconnects eventually stop working.
                g.close();
                if (g == gatt) {
                    gatt = null;
                    mainHandler.post(BleObdManager.this::resetState);
                }
                mainHandler.post(listener::onDisconnected);
            }
        }

        @Override
        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> listener.onError("Ошибка получения сервисов GATT"));
                return;
            }
            findCharacteristics(g);
            if (writeChar == null || notifyChar == null) {
                mainHandler.post(() -> listener.onError(
                        "Не найден serial-сервис адаптера. Возможно, это другая модель ELM327."));
                return;
            }
            // onConnected() fires from onDescriptorWrite() below once notifications are
            // actually enabled - the adapter refuses a second GATT op (the first command
            // write) while the descriptor write from enableNotifications() is still pending.
            if (!enableNotifications(g, notifyChar)) {
                mainHandler.post(listener::onConnected);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (CLIENT_CONFIG_DESCRIPTOR.equals(descriptor.getUuid())) {
                mainHandler.post(listener::onConnected);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleIncoming(new String(value));
        }

        // Pre-API33 callback (still called on some OEM stacks)
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) handleIncoming(new String(value));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            // nothing to do; we wait for the '>' prompt in the notification stream
        }
    };

    private void findCharacteristics(BluetoothGatt g) {
        List<BluetoothGattService> services = g.getServices();
        // Pass 1: known UUID pairs
        for (BluetoothGattService service : services) {
            for (UUID w : KNOWN_WRITE) {
                BluetoothGattCharacteristic c = service.getCharacteristic(w);
                if (c != null) writeChar = c;
            }
            for (UUID n : KNOWN_NOTIFY) {
                BluetoothGattCharacteristic c = service.getCharacteristic(n);
                if (c != null) notifyChar = c;
            }
        }
        if (writeChar != null && notifyChar != null) return;

        // Pass 2: generic heuristic - any characteristic with WRITE/WRITE_NO_RESPONSE
        // and any with NOTIFY, in case this is an unlisted clone.
        for (BluetoothGattService service : services) {
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                int props = c.getProperties();
                boolean canWrite = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                        || (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                boolean canNotify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                if (canWrite && writeChar == null) writeChar = c;
                if (canNotify && notifyChar == null) notifyChar = c;
            }
        }
    }

    /** @return true if a descriptor write was issued (caller must wait for onDescriptorWrite). */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private boolean enableNotifications(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
        g.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR);
        if (descriptor == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(descriptor);
        }
        return true;
    }

    /**
     * ELM327 always terminates a response with '>' (the prompt for the
     * next command). We buffer bytes until we see it, then hand the
     * whole chunk to the listener line by line.
     */
    private void handleIncoming(String chunk) {
        rxBuffer.append(chunk);
        String content = rxBuffer.toString();
        if (content.indexOf('>') >= 0) {
            List<String> responseLines = new ArrayList<>();
            String[] frames = content.split(">");
            for (String frame : frames) {
                String cleaned = frame.replace("\r", "\n").trim();
                if (!cleaned.isEmpty()) {
                    for (String line : cleaned.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase(pendingCommand)) {
                            String finalLine = trimmed;
                            responseLines.add(finalLine);
                            mainHandler.post(() -> listener.onLine(finalLine));
                        }
                    }
                }
            }
            String answeredCommand = pendingCommand;
            rxBuffer.setLength(0);
            waitingForResponse = false;
            pendingCommand = null;
            mainHandler.removeCallbacks(responseTimeoutRunnable);
            mainHandler.post(() -> listener.onResponseComplete(answeredCommand, responseLines));
            mainHandler.postDelayed(this::pumpQueue, 40);
        }
    }
}
