package com.hap.automaker.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.WebAuthConfig;

public final class ViewAdminService {

    private static final String DEFAULT_WEB_BASE_URL = "https://www.mingdao.com";
    private static final String DELETE_VIEW_ENDPOINT = "/api/Worksheet/DeleteWorksheetView";
    private static final Pattern PY_AUTH_PATTERN = Pattern.compile("^(ACCOUNT_ID|AUTHORIZATION|COOKIE)\\s*=\\s*\"(.*)\"\\s*$");

    private final HttpClient httpClient;
    private final String webBaseUrl;

    public ViewAdminService() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                DEFAULT_WEB_BASE_URL);
    }

    ViewAdminService(String webBaseUrl) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                webBaseUrl);
    }

    ViewAdminService(HttpClient httpClient, String webBaseUrl) {
        this.httpClient = httpClient;
        this.webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? DEFAULT_WEB_BASE_URL : webBaseUrl;
    }

    public JsonNode deleteView(Path repoRoot, String appId, String worksheetId, String viewId) throws Exception {
        WebAuth webAuth = loadWebAuth(repoRoot);
        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("appId", appId);
        body.put("viewId", viewId);
        body.put("worksheetId", worksheetId);
        body.put("status", 9);

        HttpRequest request = HttpRequest.newBuilder(URI.create(webBaseUrl + DELETE_VIEW_ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("AccountId", webAuth.accountId())
                .header("Authorization", webAuth.authorization())
                .header("Cookie", webAuth.cookie())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", webBaseUrl)
                .header("Referer", webBaseUrl + "/app/" + appId + "/" + worksheetId)
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode payload = Jacksons.mapper().readTree(response.body());
        boolean ok = payload.path("state").asInt(0) == 1 || payload.path("data").asBoolean(false);
        if (!ok) {
            throw new IllegalStateException("DeleteWorksheetView failed: " + Jacksons.mapper().writeValueAsString(payload));
        }

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("ok", true);
        result.put("viewId", viewId);
        result.put("worksheetId", worksheetId);
        result.set("raw", payload);
        return result;
    }

    private WebAuth loadWebAuth(Path repoRoot) throws Exception {
        ConfigPaths configPaths = new ConfigPaths(repoRoot);
        Path webAuthJson = configPaths.webAuth();
        if (Files.exists(webAuthJson)) {
            WebAuthConfig config = Jacksons.mapper().readValue(webAuthJson.toFile(), WebAuthConfig.class);
            return new WebAuth(config.accountId(), config.authorization(), config.cookie());
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(configPaths.authConfigPy(), StandardCharsets.UTF_8)) {
            Matcher matcher = PY_AUTH_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                values.put(matcher.group(1), matcher.group(2));
            }
        }
        return new WebAuth(
                values.getOrDefault("ACCOUNT_ID", ""),
                values.getOrDefault("AUTHORIZATION", ""),
                values.getOrDefault("COOKIE", ""));
    }

    private record WebAuth(String accountId, String authorization, String cookie) {
    }
}
