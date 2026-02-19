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
import java.time.Duration;

public class GeminiClient {
    private static final String ENDPOINT = "/gemini-3-pro/v1/chat/completions";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Config config;

    public GeminiClient(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        this.mapper = new ObjectMapper();
    }

    public GeminiResult extractTime(String imageUrl) {
        String payload;
        try {
            payload = buildPayload(imageUrl);
        } catch (JsonProcessingException e) {
            return GeminiResult.error("Payload build failed: " + e.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.kieApiBaseUrl() + ENDPOINT))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + config.kieApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GeminiResult.error("Request interrupted: " + e.getMessage());
        } catch (IOException e) {
            return GeminiResult.error("Request failed: " + e.getMessage());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return GeminiResult.error("Bad response status: " + response.statusCode());
        }

        return parseResponse(response.body());
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
        ObjectNode properties = responseFormat.putObject("properties");
        ObjectNode time = properties.putObject("time");
        time.put("type", "string");
        ArrayNode required = responseFormat.putArray("required");
        required.add("time");

        return mapper.writeValueAsString(root);
    }

    private GeminiResult parseResponse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode contentNode = root.at("/choices/0/message/content");
            if (contentNode.isMissingNode()) {
                return GeminiResult.error("Response missing content");
            }
            String content = contentNode.asText();
            String extracted = extractTimeFromContent(content);
            return GeminiResult.ok(extracted, content);
        } catch (IOException e) {
            return GeminiResult.error("Failed to parse response: " + e.getMessage());
        }
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
