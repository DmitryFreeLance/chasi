package com.chasi.clockbot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

public class GeminiClient {
    private static final String ENDPOINT = "/gemini-3-pro/v1/chat/completions";
    private static final int MAX_UPLOAD_BYTES = 9 * 1024 * 1024;
    private static final int REQUEST_TIMEOUT_SECONDS = 600;
    private static final int MAX_ATTEMPTS = 5;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Config config;
    private final KieFileUploader fileUploader;

    public GeminiClient(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        this.mapper = new ObjectMapper();
        this.fileUploader = new KieFileUploader(config);
    }

    public GeminiResult extractTime(String imageUrl, byte[] imageBytes, String fileName) {
        String effectiveUrl = prepareImageUrl(imageUrl, imageBytes, fileName);
        String payload;
        try {
            payload = buildPayload(effectiveUrl);
        } catch (JsonProcessingException e) {
            return GeminiResult.error("Payload build failed: " + e.getMessage());
        }

        GeminiResult result = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            result = sendRequest(payload, attempt);
            if (result != null && "ok".equals(result.status())) {
                return result;
            }
            if (attempt < MAX_ATTEMPTS && isRetryable(result)) {
                log("Retrying after error (attempt " + attempt + "): " + result.errorMessage());
                sleepBeforeRetry(attempt);
                continue;
            }
            return result;
        }

        return result == null ? GeminiResult.error("Request failed") : result;
    }

    private GeminiResult sendRequest(String payload, int attempt) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.kieApiBaseUrl() + ENDPOINT))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Bearer " + config.kieApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> response;
        long startedAt = System.currentTimeMillis();
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return GeminiResult.error("Request timed out: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GeminiResult.error("Request interrupted: " + e.getMessage());
        } catch (IOException e) {
            return GeminiResult.error("Request failed: " + e.getMessage());
        }
        long durationMs = System.currentTimeMillis() - startedAt;

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log("Gemini API error status=" + response.statusCode() + " durationMs=" + durationMs
                + " body=" + truncate(response.body(), 1000));
            return GeminiResult.error("Bad response status: " + response.statusCode());
        }

        log("Gemini API response status=" + response.statusCode() + " durationMs=" + durationMs
            + " body=" + truncate(response.body(), 2000));
        return parseResponse(response.body());
    }

    private String prepareImageUrl(String fallbackUrl, byte[] imageBytes, String fileName) {
        if (imageBytes == null || imageBytes.length == 0) {
            log("Image bytes missing, using Telegram URL");
            return fallbackUrl;
        }

        if (imageBytes.length > MAX_UPLOAD_BYTES) {
            log("Image too large for base64 upload (" + imageBytes.length + " bytes), using Telegram URL");
            return fallbackUrl;
        }

        String mimeType = MimeTypeResolver.fromFileName(fileName);
        UploadResult upload = fileUploader.uploadBase64(imageBytes, fileName, mimeType);
        if (upload.success() && upload.downloadUrl() != null && !upload.downloadUrl().isBlank()) {
            log("Uploaded image to Kie.ai storage");
            return upload.downloadUrl();
        }

        String reason = upload.errorMessage() == null ? "unknown" : upload.errorMessage();
        log("Upload failed, using Telegram URL. reason=" + reason);
        return fallbackUrl;
    }

    private String buildPayload(String imageUrl) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        root.put("stream", false);

        ArrayNode messages = root.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        ArrayNode systemContent = systemMessage.putArray("content");
        ObjectNode systemText = systemContent.addObject();
        systemText.put("type", "text");
        systemText.put("text", config.systemPrompt());

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode userContent = userMessage.putArray("content");
        ObjectNode userText = userContent.addObject();
        userText.put("type", "text");
        userText.put("text", "Определи время на фотографии часов.");
        ObjectNode userImage = userContent.addObject();
        userImage.put("type", "image_url");
        ObjectNode imageUrlNode = userImage.putObject("image_url");
        imageUrlNode.put("url", imageUrl);

        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", "clock_time");
        ObjectNode schema = jsonSchema.putObject("schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode time = properties.putObject("time");
        time.put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("time");
        schema.put("additionalProperties", false);

        return mapper.writeValueAsString(root);
    }

    private GeminiResult parseResponse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode codeNode = root.get("code");
            if (codeNode != null && codeNode.isInt() && codeNode.asInt() != 200) {
                int code = codeNode.asInt();
                String msg = root.has("msg") ? root.get("msg").asText() : "API error";
                return GeminiResult.error("API error code=" + code + " msg=" + msg);
            }
            JsonNode successNode = root.get("success");
            if (successNode != null && successNode.isBoolean() && !successNode.asBoolean()) {
                String msg = root.has("msg") ? root.get("msg").asText() : "API error";
                return GeminiResult.error("API error: " + msg);
            }
            JsonNode contentNode = root.at("/choices/0/message/content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                return GeminiResult.error("Response missing content");
            }
            String extracted;
            String rawContent;
            if (contentNode.isObject()) {
                JsonNode timeNode = contentNode.get("time");
                rawContent = contentNode.toString();
                extracted = timeNode != null ? TimeNormalizer.normalize(timeNode.asText()) : "UNKNOWN";
            } else {
                rawContent = contentNode.asText();
                extracted = extractTimeFromContent(rawContent);
            }
            if ("UNKNOWN".equals(extracted)) {
                log("Gemini parsed UNKNOWN from content=" + truncate(rawContent, 1000));
            }
            return GeminiResult.ok(extracted, rawContent);
        } catch (IOException e) {
            return GeminiResult.error("Failed to parse response: " + e.getMessage());
        }
    }

    private void log(String message) {
        System.out.println("[GeminiClient] " + message);
    }

    private boolean isRetryable(GeminiResult result) {
        if (result == null || result.errorMessage() == null) {
            return false;
        }
        String msg = result.errorMessage().toLowerCase();
        return msg.contains("timed out")
            || msg.contains("timeout")
            || msg.contains("server exception")
            || msg.contains("bad response status: 429")
            || msg.contains("bad response status: 500")
            || msg.contains("bad response status: 502")
            || msg.contains("bad response status: 503")
            || msg.contains("bad response status: 504")
            || msg.contains("bad response status: 524")
            || msg.contains("api error code=429")
            || msg.contains("api error code=500")
            || msg.contains("api error code=502")
            || msg.contains("api error code=503")
            || msg.contains("api error code=504")
            || msg.contains("api error code=524");
    }

    private void sleepBeforeRetry(int attempt) {
        long delayMs = 30000L * attempt;
        if (delayMs > 120_000L) {
            delayMs = 120_000L;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    private String extractTimeFromContent(String content) {
        if (content == null) {
            return "UNKNOWN";
        }
        String timeCandidate = content.trim();
        try {
            JsonNode json = mapper.readTree(timeCandidate);
            JsonNode timeNode = json.get("time");
            if (timeNode != null && !timeNode.isNull()) {
                timeCandidate = timeNode.asText();
            }
        } catch (IOException ignored) {
        }

        return TimeNormalizer.normalize(timeCandidate);
    }
}
