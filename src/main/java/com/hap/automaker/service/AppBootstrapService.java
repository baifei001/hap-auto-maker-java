package com.hap.automaker.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.OrganizationAuthConfig;

public final class AppBootstrapService implements AppBootstrapper {

    private static final String DEFAULT_BASE_URL = "https://api.mingdao.com";
    private static final String CREATE_ENDPOINT = "/v1/open/app/create";
    private static final String AUTHORIZE_ENDPOINT = "/v1/open/app/getAppAuthorize";
    private static final String FALLBACK_ICON = "sys_dashboard";
    private static final String FALLBACK_COLOR = "#2196F3";

    private final HttpClient httpClient;
    private final String baseUrl;

    public AppBootstrapService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), DEFAULT_BASE_URL);
    }

    public AppBootstrapService(String baseUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), baseUrl);
    }

    public AppBootstrapService(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }

    @Override
    public AppBootstrapResult createAndAuthorize(Path repoRoot, String appName, String groupIds) throws Exception {
        return createAndAuthorize(repoRoot, appName, groupIds, pickIcon(repoRoot), FALLBACK_COLOR);
    }

    AppBootstrapResult createAndAuthorize(
            Path repoRoot,
            String appName,
            String groupIds,
            String icon,
            String color) throws Exception {
        ConfigPaths configPaths = new ConfigPaths(repoRoot);
        OrganizationAuthConfig organizationAuth = Jacksons.mapper().readValue(
                configPaths.organizationAuth().toFile(),
                OrganizationAuthConfig.class);

        JsonNode createResponse = createApp(organizationAuth, appName, groupIds, icon, color);
        String appId = createResponse.path("data").path("appId").asText("");
        if (appId.isBlank()) {
            throw new IllegalStateException("Create app response missing appId");
        }

        Path authOutput = repoRoot.resolve("data").resolve("outputs").resolve("app_authorizations")
                .resolve("app_authorize_java_phase1.json");
        Files.createDirectories(authOutput.getParent());
        JsonNode authorizeResponse = getAppAuthorize(organizationAuth, appId);
        Jacksons.mapper().writeValue(authOutput.toFile(), authorizeResponse);

        return new AppBootstrapResult(appId, authOutput, createResponse, authorizeResponse);
    }

    private JsonNode createApp(
            OrganizationAuthConfig organizationAuth,
            String appName,
            String groupIds,
            String icon,
            String color) throws Exception {
        long timestamp = System.currentTimeMillis();
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("appKey", organizationAuth.appKey());
        payload.put("sign", buildSign(organizationAuth.appKey(), organizationAuth.secretKey(), timestamp));
        payload.put("timestamp", timestamp);
        payload.put("projectId", organizationAuth.projectId());
        payload.put("name", appName);
        payload.put("icon", icon);
        payload.put("color", color);
        payload.put("ownerId", organizationAuth.ownerId());

        List<String> groups = parseGroupIds(groupIds == null || groupIds.isBlank() ? organizationAuth.groupIds() : groupIds);
        if (!groups.isEmpty()) {
            ArrayNode groupIdsNode = payload.putArray("groupIds");
            for (String group : groups) {
                groupIdsNode.add(group);
            }
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + CREATE_ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = Jacksons.mapper().readTree(response.body());
        if (!body.path("success").asBoolean(false)) {
            throw new IllegalStateException("Create app failed: " + response.body());
        }
        return body;
    }

    private JsonNode getAppAuthorize(OrganizationAuthConfig organizationAuth, String appId) throws Exception {
        long timestamp = System.currentTimeMillis();
        String query = buildAuthorizeQuery(organizationAuth, appId, timestamp);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + AUTHORIZE_ENDPOINT + "?" + query))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = Jacksons.mapper().readTree(response.body());
        if (!body.path("success").asBoolean(false)) {
            throw new IllegalStateException("Get app authorize failed: " + response.body());
        }
        return body;
    }

    private String buildAuthorizeQuery(OrganizationAuthConfig organizationAuth, String appId, long timestamp) {
        List<String> parts = new ArrayList<>();
        parts.add("appKey=" + encode(organizationAuth.appKey()));
        parts.add("sign=" + encode(buildSign(organizationAuth.appKey(), organizationAuth.secretKey(), timestamp)));
        parts.add("timestamp=" + timestamp);
        parts.add("projectId=" + encode(organizationAuth.projectId()));
        parts.add("appId=" + encode(appId));
        return String.join("&", parts);
    }

    String buildSign(String appKey, String secretKey, long timestampMs) {
        try {
            String raw = "AppKey=" + appKey + "&SecretKey=" + secretKey + "&Timestamp=" + timestampMs;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return Base64.getEncoder().encodeToString(hex.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private List<String> parseGroupIds(String groupIds) {
        if (groupIds == null || groupIds.isBlank()) {
            return List.of();
        }
        String[] parts = groupIds.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String pickIcon(Path repoRoot) {
        Path iconJson = repoRoot.resolve("data").resolve("assets").resolve("icons").resolve("icon.json");
        if (!Files.exists(iconJson)) {
            return FALLBACK_ICON;
        }
        try {
            JsonNode root = Jacksons.mapper().readTree(iconJson.toFile());
            String found = findFirstFileName(root);
            return found == null || found.isBlank() ? FALLBACK_ICON : found;
        } catch (Exception ignored) {
            return FALLBACK_ICON;
        }
    }

    private String findFirstFileName(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode fileName = node.get("fileName");
            if (fileName != null && fileName.isTextual() && !fileName.asText().isBlank()) {
                return fileName.asText();
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                String found = findFirstFileName(fields.next().getValue());
                if (found != null && !found.isBlank()) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findFirstFileName(child);
                if (found != null && !found.isBlank()) {
                    return found;
                }
            }
        }
        return null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
