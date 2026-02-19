package com.chasi.clockbot;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeNormalizer {
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2})\\s*[:.\\-]\\s*(\\d{2})");

    public static String normalize(String input) {
        if (input == null) {
            return "UNKNOWN";
        }
        String sanitized = input.replace("*", "").replace("#", "");
        sanitized = sanitized.trim().toUpperCase(Locale.ROOT);
        if (sanitized.equals("UNKNOWN")) {
            return "UNKNOWN";
        }

        Matcher matcher = TIME_PATTERN.matcher(sanitized);
        if (!matcher.find()) {
            return "UNKNOWN";
        }

        int hours = parseIntSafe(matcher.group(1));
        int minutes = parseIntSafe(matcher.group(2));
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
            return "UNKNOWN";
        }

        return String.format("%02d:%02d", hours, minutes);
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
