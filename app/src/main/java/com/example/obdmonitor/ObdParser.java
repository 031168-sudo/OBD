package com.example.obdmonitor;

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
}
