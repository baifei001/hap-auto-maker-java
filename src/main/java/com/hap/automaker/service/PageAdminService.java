package com.hap.automaker.service;

import java.net.URI;
import java.net.URLEncoder;
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

public final class PageAdminService {

    private static final String DEFAULT_WEB_BASE_URL = "https://www.mingdao.com";
    private static final String DEFAULT_REPORT_BASE_URL = "https://api.mingdao.com";
    private static final String GET_PAGE_ENDPOINT = "/report/custom/getPage";
    private static final String SAVE_PAGE_ENDPOINT = "/report/custom/savePage";
    private static final String REMOVE_PAGE_ENDPOINT = "/api/AppManagement/RemoveWorkSheetForApp";
    private static final Pattern PY_AUTH_PATTERN = Pattern.compile("^(ACCOUNT_ID|AUTHORIZATION|COOKIE)\\s*=\\s*\"(.*)\"\\s*$");

    private final HttpClient httpClient;
    private final String webBaseUrl;
    private final String reportBaseUrl;

    public PageAdminService() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                DEFAULT_WEB_BASE_URL,
                DEFAULT_REPORT_BASE_URL);
    }

    PageAdminService(String webBaseUrl, String reportBaseUrl) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                webBaseUrl,
                reportBaseUrl);
    }

    PageAdminService(HttpClient httpClient, String webBaseUrl, String reportBaseUrl) {
        this.httpClient = httpClient;
        this.webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? DEFAULT_WEB_BASE_URL : webBaseUrl;
        this.reportBaseUrl = reportBaseUrl == null || reportBaseUrl.isBlank() ? DEFAULT_REPORT_BASE_URL : reportBaseUrl;
    }

    public JsonNode getPage(Path repoRoot, String pageId) throws Exception {
        WebAuth webAuth = loadWebAuth(repoRoot);
        String url = reportBaseUrl + GET_PAGE_ENDPOINT + "?appId=" + URLEncoder.encode(pageId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain, */*")
                .header("AccountId", webAuth.accountId())
                .header("Authorization", webAuth.authorization())
                .header("Cookie", webAuth.cookie())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", webBaseUrl)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = Jacksons.mapper().readTree(response.body());
        boolean ok = body.path("status").asInt(0) == 1 || body.path("success").asBoolean(false);
        if (!ok) {
            throw new IllegalStateException("getPage failed: " + Jacksons.mapper().writeValueAsString(body));
        }
        return body.path("data");
    }

    public JsonNode saveBlankPage(Path repoRoot, String pageId, int version) throws Exception {
        WebAuth webAuth = loadWebAuth(repoRoot);
        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("appId", pageId);
        body.put("version", version);
        body.putArray("components");
        body.put("adjustScreen", false);
        body.putArray("urlParams");
        ObjectNode config = body.putObject("config");
        config.put("pageStyleType", "light");
        config.put("pageBgColor", "#f5f6f7");
        config.put("chartColor", "");
        config.put("chartColorIndex", 1);
        config.put("numberChartColor", "");
        config.put("numberChartColorIndex", 1);
        config.put("pivoTableColor", "");
        config.put("refresh", 0);
        config.put("headerVisible", true);
        config.put("shareVisible", true);
        config.put("chartShare", true);
        config.put("chartExportExcel", true);
        config.put("downloadVisible", true);
        config.put("fullScreenVisible", true);
        config.putArray("customColors");
        config.put("webNewCols", 48);

        JsonNode response = postJson(webAuth, reportBaseUrl + SAVE_PAGE_ENDPOINT, "", body);
        boolean ok = response.path("status").asInt(0) == 1 || response.path("success").asBoolean(false);
        if (!ok) {
            throw new IllegalStateException("savePage failed: " + Jacksons.mapper().writeValueAsString(response));
        }
        return response;
    }

    public JsonNode deletePage(
            Path repoRoot,
            String appId,
            String appSectionId,
            String pageId,
            String projectId,
            boolean permanent) throws Exception {
        WebAuth webAuth = loadWebAuth(repoRoot);
        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("appId", appId);
        body.put("appSectionId", appSectionId);
        body.put("workSheetId", pageId);
        body.put("projectId", projectId);
        body.put("type", 1);
        body.put("isPermanentlyDelete", permanent);

        JsonNode response = postJson(webAuth, webBaseUrl + REMOVE_PAGE_ENDPOINT, "", body);
        boolean ok = response.path("state").asInt(0) == 1
                || response.path("status").asInt(0) == 1
                || response.path("success").asBoolean(false);
        if (!ok) {
            throw new IllegalStateException("RemoveWorkSheetForApp failed: " + Jacksons.mapper().writeValueAsString(response));
        }

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("ok", true);
        result.put("pageId", pageId);
        result.put("permanent", permanent);
        result.set("raw", response);
        return result;
    }

    private JsonNode postJson(WebAuth webAuth, String url, String referer, JsonNode body) throws Exception {
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
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(body)))
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

    private record WebAuth(String accountId, String authorization, String cookie) {
    }
}
