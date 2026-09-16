package com.example.obdmonitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a mode 03/07 reply into human readable trouble codes.
 *
 * Each code is two bytes: the top two bits pick the letter (P/C/B/U),
 * the next two the first digit, and the remaining twelve bits are the
 * last three hex digits - so 0x0133 reads as P0133.
 */
public class DtcDecoder {

    public static class Dtc {
        public final String code;
        public final String description;
        public final boolean pending;

        Dtc(String code, String description, boolean pending) {
            this.code = code;
            this.description = description;
            this.pending = pending;
        }
    }

    private static final char[] LETTERS = {'P', 'C', 'B', 'U'};

    /**
     * @param lines        the reply lines for one mode 03/07 command
     * @param responseByte 0x43 for mode 03, 0x47 for mode 07
     * @param canProtocol  CAN replies carry a code-count byte the older
     *                     protocols don't, and it must not be read as a code
     */
    public static List<String> decode(List<String> lines, int responseByte, boolean canProtocol) {
        String payload = joinPayload(lines);
        String marker = String.format("%02X", responseByte);
        int start = payload.indexOf(marker);
        if (start < 0) return new ArrayList<>();
        payload = payload.substring(start + 2);

        if (canProtocol && payload.length() >= 2) {
            payload = payload.substring(2); // number of codes, not a code
        }

        List<String> codes = new ArrayList<>();
        for (int i = 0; i + 4 <= payload.length(); i += 4) {
            String raw = payload.substring(i, i + 4);
            int value;
            try {
                value = Integer.parseInt(raw, 16);
            } catch (NumberFormatException e) {
                continue;
            }
            if (value == 0) continue; // padding
            char letter = LETTERS[(value >> 14) & 0x03];
            int firstDigit = (value >> 12) & 0x03;
            String code = String.format("%c%d%03X", letter, firstDigit, value & 0x0FFF);
            if (!codes.contains(code)) codes.add(code);
        }
        return codes;
    }

    /**
     * Strips the framing the adapter adds around multi-frame replies: an
     * ISO-TP length header on its own line and a "0:"/"1:" index in front
     * of each frame.
     */
    private static String joinPayload(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            String cleaned = line.replace(" ", "").trim().toUpperCase();
            if (cleaned.isEmpty()) continue;
            if (cleaned.contains("NODATA") || cleaned.contains("SEARCHING")
                    || cleaned.contains("UNABLE") || cleaned.contains("ERROR")
                    || cleaned.contains("STOPPED") || cleaned.equals("OK")) {
                continue;
            }
            int colon = cleaned.indexOf(':');
            if (colon == 1) cleaned = cleaned.substring(2);
            else if (cleaned.length() == 3) continue; // bare ISO-TP length header
            joined.append(cleaned);
        }
        return joined.toString();
    }

    public static String describe(String code) {
        String known = DESCRIPTIONS.get(code);
        if (known != null) return known;
        return genericDescription(code);
    }

    /** Falls back to the system the code belongs to when the exact code is unknown. */
    private static String genericDescription(String code) {
        if (code.length() < 3) return "Неизвестный код";
        char letter = code.charAt(0);
        char first = code.charAt(1);
        switch (letter) {
            case 'C': return "Шасси (ABS, подвеска, рулевое)";
            case 'B': return "Кузов (подушки, климат, свет)";
            case 'U': return "Обмен по шине данных между блоками";
            case 'P':
                if (first == '1') return "Код производителя (двигатель/трансмиссия)";
                String group = code.substring(1, 3);
                switch (group) {
                    case "00": case "01": case "02":
                        return "Топливоподача и подача воздуха";
                    case "03":
                        return "Система зажигания, пропуски воспламенения";
                    case "04":
                        return "Доп. системы (EGR, катализатор, адсорбер)";
                    case "05":
                        return "Холостой ход, скорость, входные сигналы";
                    case "06":
                        return "ЭБУ и выходные сигналы";
                    case "07": case "08": case "09":
                        return "Трансмиссия";
                    default:
                        return "Двигатель/трансмиссия";
                }
            default: return "Неизвестный код";
        }
    }

    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();
    static {
        // Misfires
        DESCRIPTIONS.put("P0300", "Случайные пропуски воспламенения в нескольких цилиндрах");
        DESCRIPTIONS.put("P0301", "Пропуски воспламенения в 1-м цилиндре");
        DESCRIPTIONS.put("P0302", "Пропуски воспламенения во 2-м цилиндре");
        DESCRIPTIONS.put("P0303", "Пропуски воспламенения в 3-м цилиндре");
        DESCRIPTIONS.put("P0304", "Пропуски воспламенения в 4-м цилиндре");
        DESCRIPTIONS.put("P0305", "Пропуски воспламенения в 5-м цилиндре");
        DESCRIPTIONS.put("P0306", "Пропуски воспламенения в 6-м цилиндре");
        DESCRIPTIONS.put("P0307", "Пропуски воспламенения в 7-м цилиндре");
        DESCRIPTIONS.put("P0308", "Пропуски воспламенения в 8-м цилиндре");
        DESCRIPTIONS.put("P0313", "Пропуски воспламенения при низком уровне топлива");
        DESCRIPTIONS.put("P0316", "Пропуски воспламенения сразу после запуска");

        // Fuel trim / mixture
        DESCRIPTIONS.put("P0171", "Слишком бедная смесь (банка 1)");
        DESCRIPTIONS.put("P0172", "Слишком богатая смесь (банка 1)");
        DESCRIPTIONS.put("P0174", "Слишком бедная смесь (банка 2)");
        DESCRIPTIONS.put("P0175", "Слишком богатая смесь (банка 2)");
        DESCRIPTIONS.put("P0170", "Ошибка коррекции топливоподачи (банка 1)");
        DESCRIPTIONS.put("P0173", "Ошибка коррекции топливоподачи (банка 2)");

        // Air intake / MAF / MAP
        DESCRIPTIONS.put("P0100", "Неисправность датчика массового расхода воздуха (ДМРВ)");
        DESCRIPTIONS.put("P0101", "ДМРВ: показания вне допустимого диапазона");
        DESCRIPTIONS.put("P0102", "ДМРВ: слишком низкий сигнал");
        DESCRIPTIONS.put("P0103", "ДМРВ: слишком высокий сигнал");
        DESCRIPTIONS.put("P0106", "Датчик давления во впуске (MAP): некорректные показания");
        DESCRIPTIONS.put("P0107", "Датчик давления во впуске (MAP): низкий сигнал");
        DESCRIPTIONS.put("P0108", "Датчик давления во впуске (MAP): высокий сигнал");
        DESCRIPTIONS.put("P0111", "Датчик температуры впускного воздуха: вне диапазона");
        DESCRIPTIONS.put("P0112", "Датчик температуры впускного воздуха: низкий сигнал");
        DESCRIPTIONS.put("P0113", "Датчик температуры впускного воздуха: высокий сигнал");

        // Coolant temp
        DESCRIPTIONS.put("P0115", "Неисправность датчика температуры ОЖ");
        DESCRIPTIONS.put("P0116", "Датчик температуры ОЖ: показания вне диапазона");
        DESCRIPTIONS.put("P0117", "Датчик температуры ОЖ: низкий сигнал");
        DESCRIPTIONS.put("P0118", "Датчик температуры ОЖ: высокий сигнал");
        DESCRIPTIONS.put("P0128", "Двигатель не прогревается до рабочей температуры (термостат)");
        DESCRIPTIONS.put("P0125", "Недостаточная температура ОЖ для замкнутого контура");

        // Throttle / pedal
        DESCRIPTIONS.put("P0120", "Датчик положения дроссельной заслонки: неисправность");
        DESCRIPTIONS.put("P0121", "Датчик положения дросселя: вне диапазона");
        DESCRIPTIONS.put("P0122", "Датчик положения дросселя: низкий сигнал");
        DESCRIPTIONS.put("P0123", "Датчик положения дросселя: высокий сигнал");
        DESCRIPTIONS.put("P0221", "Датчик положения дросселя B: вне диапазона");
        DESCRIPTIONS.put("P2135", "Рассогласование датчиков положения дросселя A/B");
        DESCRIPTIONS.put("P2138", "Рассогласование датчиков педали газа D/E");

        // Oxygen sensors
        DESCRIPTIONS.put("P0130", "Лямбда-зонд 1 (банка 1): неисправность");
        DESCRIPTIONS.put("P0131", "Лямбда-зонд 1 (банка 1): низкое напряжение");
        DESCRIPTIONS.put("P0132", "Лямбда-зонд 1 (банка 1): высокое напряжение");
        DESCRIPTIONS.put("P0133", "Лямбда-зонд 1 (банка 1): замедленный отклик");
        DESCRIPTIONS.put("P0134", "Лямбда-зонд 1 (банка 1): нет активности");
        DESCRIPTIONS.put("P0135", "Лямбда-зонд 1 (банка 1): неисправен подогрев");
        DESCRIPTIONS.put("P0136", "Лямбда-зонд 2 (банка 1): неисправность");
        DESCRIPTIONS.put("P0137", "Лямбда-зонд 2 (банка 1): низкое напряжение");
        DESCRIPTIONS.put("P0138", "Лямбда-зонд 2 (банка 1): высокое напряжение");
        DESCRIPTIONS.put("P0139", "Лямбда-зонд 2 (банка 1): замедленный отклик");
        DESCRIPTIONS.put("P0140", "Лямбда-зонд 2 (банка 1): нет активности");
        DESCRIPTIONS.put("P0141", "Лямбда-зонд 2 (банка 1): неисправен подогрев");
        DESCRIPTIONS.put("P0150", "Лямбда-зонд 1 (банка 2): неисправность");
        DESCRIPTIONS.put("P0155", "Лямбда-зонд 1 (банка 2): неисправен подогрев");
        DESCRIPTIONS.put("P0161", "Лямбда-зонд 2 (банка 2): неисправен подогрев");

        // Catalyst / EVAP / EGR
        DESCRIPTIONS.put("P0420", "Низкая эффективность катализатора (банка 1)");
        DESCRIPTIONS.put("P0430", "Низкая эффективность катализатора (банка 2)");
        DESCRIPTIONS.put("P0441", "Неверный расход при продувке адсорбера");
        DESCRIPTIONS.put("P0442", "Малая утечка в системе улавливания паров (EVAP)");
        DESCRIPTIONS.put("P0443", "Клапан продувки адсорбера: неисправность цепи");
        DESCRIPTIONS.put("P0446", "Вентиляция адсорбера: неисправность");
        DESCRIPTIONS.put("P0455", "Большая утечка EVAP (часто — крышка бака)");
        DESCRIPTIONS.put("P0456", "Очень малая утечка EVAP");
        DESCRIPTIONS.put("P0401", "Недостаточный поток EGR");
        DESCRIPTIONS.put("P0402", "Избыточный поток EGR");
        DESCRIPTIONS.put("P0403", "Клапан EGR: неисправность цепи");
        DESCRIPTIONS.put("P0404", "Клапан EGR: работа вне диапазона");
        DESCRIPTIONS.put("P0405", "Датчик положения EGR: низкий сигнал");

        // Ignition / knock / crank / cam
        DESCRIPTIONS.put("P0325", "Датчик детонации: неисправность (банка 1)");
        DESCRIPTIONS.put("P0327", "Датчик детонации: низкий сигнал");
        DESCRIPTIONS.put("P0328", "Датчик детонации: высокий сигнал");
        DESCRIPTIONS.put("P0335", "Датчик положения коленвала: неисправность");
        DESCRIPTIONS.put("P0336", "Датчик коленвала: показания вне диапазона");
        DESCRIPTIONS.put("P0340", "Датчик положения распредвала: неисправность");
        DESCRIPTIONS.put("P0341", "Датчик распредвала: показания вне диапазона");
        DESCRIPTIONS.put("P0344", "Датчик распредвала: прерывистый сигнал");
        DESCRIPTIONS.put("P0350", "Катушка зажигания: неисправность цепи");
        DESCRIPTIONS.put("P0351", "Катушка зажигания 1: неисправность цепи");
        DESCRIPTIONS.put("P0352", "Катушка зажигания 2: неисправность цепи");
        DESCRIPTIONS.put("P0353", "Катушка зажигания 3: неисправность цепи");
        DESCRIPTIONS.put("P0354", "Катушка зажигания 4: неисправность цепи");
        DESCRIPTIONS.put("P0011", "Фазы распредвала: опережение сверх нормы (банка 1)");
        DESCRIPTIONS.put("P0014", "Фазы выпускного распредвала: опережение (банка 1)");
        DESCRIPTIONS.put("P0016", "Рассогласование коленвала и распредвала (банка 1)");

        // Injectors / fuel system
        DESCRIPTIONS.put("P0201", "Форсунка цилиндра 1: неисправность цепи");
        DESCRIPTIONS.put("P0202", "Форсунка цилиндра 2: неисправность цепи");
        DESCRIPTIONS.put("P0203", "Форсунка цилиндра 3: неисправность цепи");
        DESCRIPTIONS.put("P0204", "Форсунка цилиндра 4: неисправность цепи");
        DESCRIPTIONS.put("P0087", "Слишком низкое давление в топливной рампе");
        DESCRIPTIONS.put("P0088", "Слишком высокое давление в топливной рампе");
        DESCRIPTIONS.put("P0190", "Датчик давления топлива: неисправность");
        DESCRIPTIONS.put("P0230", "Цепь топливного насоса: неисправность");
        DESCRIPTIONS.put("P0261", "Форсунка 1: замыкание на массу");
        DESCRIPTIONS.put("P0263", "Цилиндр 1: отклонение вклада в крутящий момент");

        // Idle / speed / sensors
        DESCRIPTIONS.put("P0500", "Датчик скорости автомобиля: неисправность");
        DESCRIPTIONS.put("P0501", "Датчик скорости: показания вне диапазона");
        DESCRIPTIONS.put("P0505", "Система холостого хода: неисправность");
        DESCRIPTIONS.put("P0506", "Обороты холостого хода ниже нормы");
        DESCRIPTIONS.put("P0507", "Обороты холостого хода выше нормы (подсос воздуха)");
        DESCRIPTIONS.put("P0562", "Низкое напряжение бортсети");
        DESCRIPTIONS.put("P0563", "Высокое напряжение бортсети");

        // Turbo / boost
        DESCRIPTIONS.put("P0234", "Превышение давления наддува");
        DESCRIPTIONS.put("P0299", "Недостаточное давление наддува");
        DESCRIPTIONS.put("P0245", "Клапан управления турбиной: низкий сигнал");

        // ECU / memory / bus
        DESCRIPTIONS.put("P0600", "Ошибка обмена внутри ЭБУ");
        DESCRIPTIONS.put("P0601", "Ошибка контрольной суммы памяти ЭБУ");
        DESCRIPTIONS.put("P0606", "Отказ процессора ЭБУ");
        DESCRIPTIONS.put("U0100", "Потеряна связь с ЭБУ двигателя");
        DESCRIPTIONS.put("U0101", "Потеряна связь с блоком АКПП");
        DESCRIPTIONS.put("U0121", "Потеряна связь с блоком ABS");
        DESCRIPTIONS.put("U0155", "Потеряна связь с приборной панелью");

        // Transmission
        DESCRIPTIONS.put("P0700", "Неисправность системы управления АКПП");
        DESCRIPTIONS.put("P0705", "Датчик положения селектора АКПП");
        DESCRIPTIONS.put("P0740", "Муфта блокировки гидротрансформатора");
        DESCRIPTIONS.put("P0741", "Блокировка гидротрансформатора: пробуксовка");
        DESCRIPTIONS.put("P0730", "Неверное передаточное отношение");
    }
}
