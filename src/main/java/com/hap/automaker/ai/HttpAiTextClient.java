package com.hap.automaker.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;

public final class HttpAiTextClient implements AiTextClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String generateJson(String prompt, AiAuthConfig config) throws Exception {
        if ("gemini".equalsIgnoreCase(config.provider())) {
            return callGemini(prompt, config);
        }
        return callOpenAiCompatible(prompt, config);
    }

    private String callGemini(String prompt, AiAuthConfig config) throws Exception {
        String model = config.model();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model
                + ":generateContent?key=" + config.apiKey();

        ObjectNode body = Jacksons.mapper().createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", prompt);
        body.putObject("generationConfig")
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json");

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = Jacksons.mapper().readTree(response.body());
        return json.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
    }

    private String callOpenAiCompatible(String prompt, AiAuthConfig config) throws Exception {
        String endpoint = config.baseUrl().isBlank()
                ? "https://api.deepseek.com/chat/completions"
                : config.baseUrl().replaceAll("/$", "") + "/chat/completions";

        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("model", config.model());
        body.put("temperature", 0.2);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", prompt);
        body.putObject("response_format").put("type", "json_object");

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = Jacksons.mapper().readTree(response.body());
        return json.path("choices").path(0).path("message").path("content").asText();
    }
}
