package com.chasi.clockbot;

import java.nio.file.Path;
import java.util.Locale;

public record Config(String telegramToken,
                     String kieApiKey,
                     String kieApiBaseUrl,
                     String kieUploadBaseUrl,
                     String dbPath,
                     String systemPrompt) {

    private static final String DEFAULT_PROMPT = "Ты специализированная модель. Твоя единственная задача определить время на фотографии часов и вернуть только время. Отвечай строго в формате HH:MM в 24-часовом виде с ведущим нулем. Если время определить невозможно, ответь UNKNOWN. Не используй символы звездочка и решетка. Не добавляй других слов.";

    public static Config fromEnv() {
        String telegramToken = readRequired("TELEGRAM_BOT_TOKEN");
        String kieApiKey = readRequired("KIE_API_KEY");
        String kieApiBaseUrl = readOptional("KIE_API_BASE_URL", "https://api.kie.ai");
        String kieUploadBaseUrl = readOptional("KIE_UPLOAD_BASE_URL", "https://kieai.redpandaai.co");
        String dbPath = readOptional("DB_PATH", Path.of("data", "bot.db").toString());
        String systemPrompt = readOptional("GEMINI_SYSTEM_PROMPT", DEFAULT_PROMPT);

        if (systemPrompt.contains("*") || systemPrompt.contains("#")) {
            throw new IllegalArgumentException("System prompt must not contain '*' or '#'");
        }

        return new Config(
            telegramToken,
            kieApiKey,
            normalizeBaseUrl(kieApiBaseUrl),
            normalizeBaseUrl(kieUploadBaseUrl),
            dbPath,
            systemPrompt
        );
    }

    private static String readRequired(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            System.err.println("Missing required environment variable: " + key);
            System.exit(1);
        }
        return value.trim();
    }

    private static String readOptional(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }
}
