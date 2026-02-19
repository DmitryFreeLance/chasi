package com.chasi.clockbot;

public record GeminiResult(String time, String status, String errorMessage, String rawContent) {
    public static GeminiResult ok(String time, String rawContent) {
        String normalized = time == null ? "UNKNOWN" : time;
        return new GeminiResult(normalized, "ok", null, rawContent);
    }

    public static GeminiResult error(String message) {
        return new GeminiResult("UNKNOWN", "error", message, null);
    }
}
