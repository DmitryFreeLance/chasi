package com.chasi.clockbot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public class KieFileUploader {
    private static final String ENDPOINT = "/api/file-base64-upload";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Config config;

    public KieFileUploader(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        this.mapper = new ObjectMapper();
    }

    public UploadResult uploadBase64(byte[] bytes, String fileName, String mimeType) {
        String payload;
        try {
            payload = buildPayload(bytes, fileName, mimeType);
        } catch (JsonProcessingException e) {
            return UploadResult.error("Upload payload build failed: " + e.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.kieUploadBaseUrl() + ENDPOINT))
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
            return UploadResult.error("Upload interrupted: " + e.getMessage());
        } catch (IOException e) {
            return UploadResult.error("Upload failed: " + e.getMessage());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return UploadResult.error("Upload bad status: " + response.statusCode());
        }

        return parseResponse(response.body());
    }

    private String buildPayload(byte[] bytes, String fileName, String mimeType) throws JsonProcessingException {
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        ObjectNode root = mapper.createObjectNode();
        root.put("base64Data", dataUrl);
        root.put("uploadPath", "telegram/clock-photos");
        root.put("fileName", fileName == null ? "photo.jpg" : fileName);
        return mapper.writeValueAsString(root);
    }

    private UploadResult parseResponse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode codeNode = root.get("code");
            if (codeNode != null && codeNode.isInt() && codeNode.asInt() != 200) {
                String msg = root.has("msg") ? root.get("msg").asText() : "Upload error";
                return UploadResult.error(msg);
            }
            JsonNode successNode = root.get("success");
            if (successNode != null && successNode.isBoolean() && !successNode.asBoolean()) {
                String msg = root.has("msg") ? root.get("msg").asText() : "Upload error";
                return UploadResult.error(msg);
            }
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                String msg = root.has("msg") ? root.get("msg").asText() : "Upload data missing";
                return UploadResult.error(msg);
            }
            String downloadUrl = data.has("downloadUrl") ? data.get("downloadUrl").asText() : null;
            String fileUrl = data.has("fileUrl") ? data.get("fileUrl").asText() : null;
            if ((downloadUrl == null || downloadUrl.isBlank()) && (fileUrl == null || fileUrl.isBlank())) {
                return UploadResult.error("Upload URL missing");
            }
            return UploadResult.ok(downloadUrl, fileUrl);
        } catch (IOException e) {
            return UploadResult.error("Upload parse failed: " + e.getMessage());
        }
    }
}
