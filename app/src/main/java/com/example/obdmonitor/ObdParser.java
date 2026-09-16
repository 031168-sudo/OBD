package com.example.obdmonitor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Converts raw ELM327 hex frames like "41 0C 1A F8" into physical values,
 * using the mode 01 formulas from SAE J1979.
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
        String hex = normalize(line);
        if (hex.length() < 6 || !hex.startsWith("41")) return null;
        String pid = hex.substring(2, 4);
        Double value = decode(pid, dataBytes(hex));
        return value == null ? null : new Result(pid, value);
    }

    public static String normalize(String line) {
        return line.replace(" ", "").replace("\r", "").trim().toUpperCase();
    }

    /** The payload of a "41 XX ..." frame, i.e. everything after the PID. */
    public static int[] dataBytes(String hex) {
        int count = (hex.length() - 4) / 2;
        if (count < 0) return new int[0];
        int[] bytes = new int[count];
        for (int i = 0; i < count; i++) {
            try {
                bytes[i] = Integer.parseInt(hex.substring(4 + i * 2, 6 + i * 2), 16);
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return bytes;
    }

    /** Raw payload of a reply, for PIDs whose value has no single number. */
    public static String rawPayload(String hex) {
        return hex.length() > 4 ? hex.substring(4) : "";
    }

    /**
     * @return the physical value for this PID, or null when the PID carries
     *         something that isn't a single number (bitmasks, status words,
     *         sensor layouts) or the frame is too short.
     */
    public static Double decode(String pid, int[] d) {
        switch (pid) {
            // --- single byte, percent of 255 ---
            case "04": case "11": case "2C": case "2E": case "2F":
            case "45": case "47": case "48": case "49": case "4A":
            case "4B": case "4C": case "52": case "5A": case "5B":
                return need(d, 1) ? d[0] * 100.0 / 255.0 : null;

            // --- single byte, temperature offset by 40 ---
            case "05": case "0F": case "46": case "5C":
                return need(d, 1) ? d[0] - 40.0 : null;

            // --- single byte, percent centred on 128 ---
            case "06": case "07": case "08": case "09": case "2D":
            case "55": case "56": case "57": case "58":
                return need(d, 1) ? d[0] * 100.0 / 128.0 - 100.0 : null;

            case "0A": return need(d, 1) ? d[0] * 3.0 : null;          // kPa
            case "0B": return need(d, 1) ? (double) d[0] : null;        // kPa
            case "0D": return need(d, 1) ? (double) d[0] : null;        // km/h
            case "0E": return need(d, 1) ? d[0] / 2.0 - 64.0 : null;    // degrees
            case "30": return need(d, 1) ? (double) d[0] : null;        // count
            case "33": return need(d, 1) ? (double) d[0] : null;        // kPa
            case "50": return need(d, 1) ? (double) d[0] : null;        // g/s
            case "51": return need(d, 1) ? (double) d[0] : null;        // fuel type code
            case "61": case "62": case "64":
                return need(d, 1) ? d[0] - 125.0 : null;                // percent torque

            case "0C": return need(d, 2) ? word(d) / 4.0 : null;        // rpm
            case "10": return need(d, 2) ? word(d) / 100.0 : null;      // g/s
            case "1F": case "21": case "31": case "4D": case "4E":
                return need(d, 2) ? (double) word(d) : null;            // seconds/km/minutes
            case "22": return need(d, 2) ? word(d) * 0.079 : null;      // kPa
            case "23": case "59": return need(d, 2) ? word(d) * 10.0 : null; // kPa
            case "32": return need(d, 2) ? word(d) / 4.0 - 8192.0 : null;    // Pa
            case "42": return need(d, 2) ? word(d) / 1000.0 : null;     // volts
            case "43": return need(d, 2) ? word(d) * 100.0 / 255.0 : null;   // percent
            case "44": return need(d, 2) ? word(d) / 32768.0 : null;    // lambda
            case "53": return need(d, 2) ? word(d) / 200.0 : null;      // kPa
            case "54": return need(d, 2) ? word(d) - 32767.0 : null;    // Pa
            case "5D": return need(d, 2) ? (word(d) - 26880) / 128.0 : null; // degrees
            case "5E": return need(d, 2) ? word(d) / 20.0 : null;       // l/h
            case "63": return need(d, 2) ? (double) word(d) : null;     // Nm

            // Catalyst temperatures
            case "3C": case "3D": case "3E": case "3F":
                return need(d, 2) ? word(d) / 10.0 - 40.0 : null;

            // Narrowband oxygen sensors: first byte is the voltage
            case "14": case "15": case "16": case "17":
            case "18": case "19": case "1A": case "1B":
                return need(d, 1) ? d[0] / 200.0 : null;                // volts

            // Wideband oxygen sensors: first word is the lambda ratio
            case "24": case "25": case "26": case "27":
            case "28": case "29": case "2A": case "2B":
            case "34": case "35": case "36": case "37":
            case "38": case "39": case "3A": case "3B":
                return need(d, 2) ? word(d) / 32768.0 : null;           // lambda

            default:
                return null; // bitmasks, status words, sensor layouts
        }
    }

    private static boolean need(int[] d, int count) {
        return d.length >= count;
    }

    private static int word(int[] d) {
        return d[0] * 256 + d[1];
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
        String hex = normalize(line);
        if (hex.length() < 12 || !hex.startsWith("41")) return null;

        int base;
        try {
            base = Integer.parseInt(hex.substring(2, 4), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        // Support bitmasks live at 0x00, 0x20, 0x40 ... every 32 PIDs.
        if (base % 0x20 != 0) return null;

        Set<String> supported = new LinkedHashSet<>();
        int[] data = dataBytes(hex);
        if (data.length < 4) return null;
        for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
            for (int bit = 0; bit < 8; bit++) {
                if ((data[byteIndex] & (0x80 >> bit)) == 0) continue;
                int pid = base + byteIndex * 8 + bit + 1;
                supported.add(String.format("%02X", pid));
            }
        }
        return supported;
    }
}
