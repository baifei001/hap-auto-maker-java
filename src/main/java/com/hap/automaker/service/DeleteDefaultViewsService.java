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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.config.Jacksons;

public final class DeleteDefaultViewsService {

    private static final String DEFAULT_BASE_URL = "https://api.mingdao.com";
    private static final String APP_INFO_ENDPOINT = "/v3/app";
    private static final String WORKSHEET_DETAIL_ENDPOINT = "/v3/app/worksheets/";
    private static final Set<String> DEFAULT_VIEW_NAMES = Set.of("全部", "视图", "All", "View");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ViewAdminService viewAdminService;

    public DeleteDefaultViewsService() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                DEFAULT_BASE_URL,
                new ViewAdminService());
    }

    DeleteDefaultViewsService(String baseUrl, ViewAdminService viewAdminService) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                baseUrl,
                viewAdminService);
    }

    DeleteDefaultViewsService(HttpClient httpClient, String baseUrl, ViewAdminService viewAdminService) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.viewAdminService = viewAdminService;
    }

    public JsonNode deleteDefaultViews(Path repoRoot, String appId, boolean dryRun, boolean deleteAllViews) throws Exception {
        AppAuthorization authorization = resolveAppAuthorization(repoRoot, appId);
        List<WorksheetRef> worksheets = fetchWorksheets(authorization);

        ArrayNode matchedViews = Jacksons.mapper().createArrayNode();
        int deletedCount = 0;
        for (WorksheetRef worksheet : worksheets) {
            List<ViewRef> views = fetchViews(authorization, worksheet.worksheetId());
            List<ViewRef> targetViews = selectTargetViews(views, deleteAllViews);
            for (ViewRef view : targetViews) {
                ObjectNode item = matchedViews.addObject();
                item.put("worksheetId", worksheet.worksheetId());
                item.put("worksheetName", worksheet.worksheetName());
                item.put("viewId", view.viewId());
                item.put("viewName", view.viewName());
                item.put("viewType", view.viewType());
                if (!dryRun) {
                    viewAdminService.deleteView(repoRoot, appId, worksheet.worksheetId(), view.viewId());
                    deletedCount++;
                }
            }
        }

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("dryRun", dryRun);
        result.put("deleteAllViews", deleteAllViews);
        result.put("matchedCount", matchedViews.size());
        result.put("deletedCount", deletedCount);
        result.set("matchedViews", matchedViews);
        return result;
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

    private List<WorksheetRef> fetchWorksheets(AppAuthorization authorization) throws Exception {
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
            throw new IllegalStateException("Fetch worksheets failed: " + response.body());
        }
        List<WorksheetRef> worksheets = new ArrayList<>();
        for (JsonNode section : asArray(body.path("data").path("sections"))) {
            collectWorksheets(section, worksheets);
        }
        return worksheets;
    }

    private void collectWorksheets(JsonNode section, List<WorksheetRef> worksheets) {
        for (JsonNode item : asArray(section.path("items"))) {
            if (item.path("type").asInt(-1) != 0) {
                continue;
            }
            String worksheetId = item.path("id").asText("").trim();
            String worksheetName = item.path("name").asText("").trim();
            if (!worksheetId.isBlank()) {
                worksheets.add(new WorksheetRef(worksheetId, worksheetName));
            }
        }
        for (JsonNode child : asArray(section.path("childSections"))) {
            collectWorksheets(child, worksheets);
        }
    }

    private List<ViewRef> fetchViews(AppAuthorization authorization, String worksheetId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + WORKSHEET_DETAIL_ENDPOINT + worksheetId))
                .timeout(Duration.ofSeconds(30))
                .header("HAP-Appkey", authorization.appKey())
                .header("HAP-Sign", authorization.sign())
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = Jacksons.mapper().readTree(response.body());
        if (!body.path("success").asBoolean(false)) {
            return List.of();
        }
        List<ViewRef> views = new ArrayList<>();
        for (JsonNode view : asArray(body.path("data").path("views"))) {
            String viewId = firstNonBlank(view.path("viewId").asText(""), view.path("id").asText(""));
            String viewName = view.path("name").asText("").trim();
            String viewType = String.valueOf(view.path("viewType").asInt(view.path("type").asInt(0)));
            if (!viewId.isBlank()) {
                views.add(new ViewRef(viewId, viewName, viewType));
            }
        }
        return views;
    }

    private List<ViewRef> selectTargetViews(List<ViewRef> views, boolean deleteAllViews) {
        if (views.isEmpty()) {
            return List.of();
        }
        if (deleteAllViews) {
            return views.size() > 1 ? views.subList(0, views.size() - 1) : List.of();
        }
        List<ViewRef> defaultViews = new ArrayList<>();
        List<ViewRef> nonDefaultViews = new ArrayList<>();
        for (ViewRef view : views) {
            if ("0".equals(view.viewType()) && DEFAULT_VIEW_NAMES.contains(view.viewName())) {
                defaultViews.add(view);
            } else {
                nonDefaultViews.add(view);
            }
        }
        return nonDefaultViews.isEmpty() ? List.of() : defaultViews;
    }

    private ArrayNode asArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : Jacksons.mapper().createArrayNode();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record AppAuthorization(String appId, String appKey, String sign) {
    }

    private record WorksheetRef(String worksheetId, String worksheetName) {
    }

    private record ViewRef(String viewId, String viewName, String viewType) {
    }
}
