package com.hap.automaker.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.WebAuthConfig;

public final class UpdateWorksheetIconsService {

    private static final String DEFAULT_BASE_URL = "https://api.mingdao.com";
    private static final String DEFAULT_WEB_BASE_URL = "https://www.mingdao.com";
    private static final String APP_INFO_ENDPOINT = "/v3/app";
    private static final String EDIT_WORKSHEET_INFO_ENDPOINT = "/api/AppManagement/EditWorkSheetInfoForApp";
    private static final Pattern PY_AUTH_PATTERN = Pattern.compile("^(ACCOUNT_ID|AUTHORIZATION|COOKIE)\\s*=\\s*\"(.*)\"\\s*$");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String webBaseUrl;

    public UpdateWorksheetIconsService() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                DEFAULT_BASE_URL,
                DEFAULT_WEB_BASE_URL);
    }

    UpdateWorksheetIconsService(String baseUrl, String webBaseUrl) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                baseUrl,
                webBaseUrl);
    }

    UpdateWorksheetIconsService(HttpClient httpClient, String baseUrl, String webBaseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? DEFAULT_WEB_BASE_URL : webBaseUrl;
    }

    public JsonNode updateIcons(Path repoRoot, String appId, List<IconMapping> mappings, boolean dryRun) throws Exception {
        AppAuthorization authorization = resolveAppAuthorization(repoRoot, appId);
        WebAuth webAuth = loadWebAuth(repoRoot);
        Map<String, WorksheetMeta> metaByWorksheetId = fetchWorksheetMeta(authorization);

        ArrayNode results = Jacksons.mapper().createArrayNode();
        for (IconMapping mapping : mappings) {
            WorksheetMeta meta = metaByWorksheetId.get(mapping.worksheetId());
            if (meta == null) {
                throw new IllegalArgumentException("Worksheet not found in app: " + mapping.worksheetId());
            }
            ObjectNode payload = Jacksons.mapper().createObjectNode();
            payload.put("appId", appId);
            payload.put("appSectionId", meta.appSectionId());
            payload.put("workSheetId", mapping.worksheetId());
            payload.put("workSheetName", meta.worksheetName());
            payload.put("icon", mapping.icon());

            ObjectNode item = results.addObject();
            item.set("payload", payload);
            if (dryRun) {
                item.put("dryRun", true);
                continue;
            }

            JsonNode response = postJson(
                    webAuth,
                    webBaseUrl + EDIT_WORKSHEET_INFO_ENDPOINT,
                    webBaseUrl + "/app/" + appId + "/" + meta.appSectionId() + "/" + mapping.worksheetId(),
                    payload);
            item.put("statusCode", 200);
            item.set("response", response);
        }

        ObjectNode summary = Jacksons.mapper().createObjectNode();
        summary.put("appId", appId);
        summary.put("dryRun", dryRun);
        summary.put("total", results.size());
        summary.set("results", results);
        return summary;
    }

    private AppAuthorization resolveAppAuthorization(Path repoRoot, String appId) throws Exception {
        Path authDir = repoRoot.resolve("data").resolve("outputs").resolve("app_authorizations");
        if (!Files.isDirectory(authDir)) {
            throw new IllegalStateException("App authorization directory not found: " + authDir);
        }
        try (var stream = Files.list(authDir)) {
            for (Path candidate : stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonNode root = Jacksons.mapper().readTree(candidate.toFile());
                for (JsonNode row : asArray(root.path("data"))) {
                    if (!appId.equals(row.path("appId").asText(""))) {
                        continue;
                    }
                    String appKey = row.path("appKey").asText("");
                    String sign = row.path("sign").asText("");
                    if (!appKey.isBlank() && !sign.isBlank()) {
                        return new AppAuthorization(appId, appKey, sign);
                    }
                }
            }
        }
        throw new IllegalStateException("No app authorization found for appId=" + appId);
    }

    private Map<String, WorksheetMeta> fetchWorksheetMeta(AppAuthorization authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + APP_INFO_ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("HAP-Appkey", authorization.appKey())
                .header("HAP-Sign", authorization.sign())
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = Jacksons.mapper().readTree(response.body());
        if (!body.path("success").asBoolean(false)) {
            throw new IllegalStateException("Fetch worksheet meta failed: " + response.body());
        }

        Map<String, WorksheetMeta> result = new LinkedHashMap<>();
        for (JsonNode section : asArray(body.path("data").path("sections"))) {
            collectWorksheetMeta(section, result);
        }
        return result;
    }

    private void collectWorksheetMeta(JsonNode section, Map<String, WorksheetMeta> result) {
        String sectionId = section.path("id").asText("");
        for (JsonNode item : asArray(section.path("items"))) {
            if (item.path("type").asInt(-1) != 0) {
                continue;
            }
            String worksheetId = item.path("id").asText("");
            String worksheetName = item.path("name").asText("");
            if (!worksheetId.isBlank()) {
                result.put(worksheetId, new WorksheetMeta(worksheetName, sectionId));
            }
        }
        for (JsonNode child : asArray(section.path("childSections"))) {
            collectWorksheetMeta(child, result);
        }
    }

    private JsonNode postJson(WebAuth webAuth, String url, String referer, JsonNode payload) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("AccountId", webAuth.accountId())
                .header("Authorization", webAuth.authorization())
                .header("Cookie", webAuth.cookie())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", webBaseUrl);
        if (!referer.isBlank()) {
            requestBuilder.header("Referer", referer);
        }
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Jacksons.mapper().readTree(response.body());
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

    private ArrayNode asArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : Jacksons.mapper().createArrayNode();
    }

    public record IconMapping(String worksheetId, String icon) {
    }

    private record AppAuthorization(String appId, String appKey, String sign) {
    }

    private record WorksheetMeta(String worksheetName, String appSectionId) {
    }

    private record WebAuth(String accountId, String authorization, String cookie) {
    }
}
