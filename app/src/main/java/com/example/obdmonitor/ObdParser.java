package com.example.obdmonitor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Converts raw ELM327 hex frames like "41 0C 1A F8" into physical values.
 * Mode 01 (current data) PIDs only - the common dashboard set.
 */
public class ObdParser {

    public static class Result {
        public final String pid;
        public final double value;
        public Result(String pid, double value) {
            this.pid = pid;
            this.value = value;
        }
    }

    /**
     * @param line a single response line, e.g. "41 0C 1A F8" or "410C1AF8"
     * @return parsed value, or null if the line isn't a Mode-01 data frame
     *         we know how to decode (echoes, "NO DATA", "SEARCHING...", etc).
     */
    public static Result parse(String line) {
        String hex = line.replace(" ", "").replace("\r", "").trim().toUpperCase();
        if (hex.length() < 6) return null;
        if (!hex.startsWith("41")) return null; // 41 = positive response to Mode 01

        String pid = hex.substring(2, 4);
        try {
            switch (pid) {
                case "0C": { // Engine RPM
                    int a = hexByte(hex, 4);
                    int b = hexByte(hex, 6);
                    double rpm = ((a * 256) + b) / 4.0;
                    return new Result(pid, rpm);
                }
                case "0D": { // Vehicle speed, km/h
                    int a = hexByte(hex, 4);
                    return new Result(pid, a);
                }
                case "05": { // Coolant temperature, deg C
                    int a = hexByte(hex, 4);
                    return new Result(pid, a - 40);
                }
                case "04": { // Calculated engine load, %
                    int a = hexByte(hex, 4);
                    return new Result(pid, a * 100.0 / 255.0);
                }
                case "11": { // Throttle position, %
                    int a = hexByte(hex, 4);
                    return new Result(pid, a * 100.0 / 255.0);
                }
                case "2F": { // Fuel level, %
                    int a = hexByte(hex, 4);
                    return new Result(pid, a * 100.0 / 255.0);
                }
                case "0F": { // Intake air temperature, deg C
                    int a = hexByte(hex, 4);
                    return new Result(pid, a - 40);
                }
                case "0A": { // Fuel pressure, kPa
                    int a = hexByte(hex, 4);
                    return new Result(pid, a * 3.0);
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static int hexByte(String hex, int startIndex) {
        return Integer.parseInt(hex.substring(startIndex, startIndex + 2), 16);
    }

    /** Mode 01 PIDs whose reply is a bitmask of the 32 PIDs that follow them. */
    public static final String[] SUPPORT_PIDS = {"0100", "0120", "0140", "0160", "0180", "01A0", "01C0"};

    /**
     * Decodes a support bitmask reply like "41 00 BE 1F A8 13": four bytes,
     * MSB first, where bit 31 means "PID 01 is supported" and bit 0 means
     * "the next range (PID 20/40/...) is supported too".
     *
     * @return the supported PID numbers as two-digit hex ("0C", "1F"), or
     *         null if this isn't a support bitmask reply.
     */
    public static Set<String> parseSupportedPids(String line) {
        String hex = line.replace(" ", "").replace("\r", "").trim().toUpperCase();
        if (hex.length() < 12 || !hex.startsWith("41")) return null;

        int base;
        try {
            base = hexByte(hex, 2);
        } catch (Exception e) {
            return null;
        }
        // Support bitmasks live at 0x00, 0x20, 0x40 ... every 32 PIDs.
        if (base % 0x20 != 0) return null;

        Set<String> supported = new LinkedHashSet<>();
        try {
            for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                int value = hexByte(hex, 4 + byteIndex * 2);
                for (int bit = 0; bit < 8; bit++) {
                    boolean isSet = (value & (0x80 >> bit)) != 0;
                    if (!isSet) continue;
                    int pid = base + byteIndex * 8 + bit + 1;
                    supported.add(String.format("%02X", pid));
                }
            }
        } catch (Exception e) {
            return null;
        }
        return supported;
    }
}
