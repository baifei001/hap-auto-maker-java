package com.hap.automaker.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.AiJsonParser;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.model.WebAuthConfig;
import com.hap.automaker.util.LoggerFactory;

public final class PagePipelineService implements PagePipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(PagePipelineService.class);

    private static final String DEFAULT_WEB_BASE_URL = "https://www.mingdao.com";
    private static final String DEFAULT_REPORT_BASE_URL = "https://api.mingdao.com";
    private static final String GET_WORKSHEET_INFO_ENDPOINT = "/api/Worksheet/GetWorksheetInfo";
    private static final String GET_APP_ENDPOINT = "/api/HomeApp/GetApp";
    private static final String ADD_WORKSHEET_ENDPOINT = "/api/AppManagement/AddWorkSheet";
    private static final String SAVE_PAGE_ENDPOINT = "/report/custom/savePage";
    private static final Pattern PY_AUTH_PATTERN = Pattern.compile("^(ACCOUNT_ID|AUTHORIZATION|COOKIE)\\s*=\\s*\"(.*)\"\\s*$");

    private final AiTextClient aiClient;
    private final AiJsonParser aiJsonParser;
    private final ChartPipelineRunner chartPipelineRunner;
    private final HttpClient httpClient;
    private final String webBaseUrl;
    private final String reportBaseUrl;

    public PagePipelineService() {
        this(
                new HttpAiTextClient(),
                new AiJsonParser(),
                new ChartPipelineService(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                DEFAULT_WEB_BASE_URL,
                DEFAULT_REPORT_BASE_URL);
    }

    public PagePipelineService(String webBaseUrl, String reportBaseUrl) {
        this(
                new HttpAiTextClient(),
                new AiJsonParser(),
                new ChartPipelineService(webBaseUrl, reportBaseUrl),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                webBaseUrl,
                reportBaseUrl);
    }

    PagePipelineService(
            AiTextClient aiClient,
            ChartPipelineRunner chartPipelineRunner,
            String webBaseUrl,
            String reportBaseUrl) {
        this(
                aiClient,
                new AiJsonParser(),
                chartPipelineRunner,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                webBaseUrl,
                reportBaseUrl);
    }

    PagePipelineService(
            AiTextClient aiClient,
            AiJsonParser aiJsonParser,
            ChartPipelineRunner chartPipelineRunner,
            HttpClient httpClient,
            String webBaseUrl,
            String reportBaseUrl) {
        this.aiClient = aiClient;
        this.aiJsonParser = aiJsonParser;
        this.chartPipelineRunner = chartPipelineRunner;
        this.httpClient = httpClient;
        this.webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? DEFAULT_WEB_BASE_URL : webBaseUrl;
        this.reportBaseUrl = reportBaseUrl == null || reportBaseUrl.isBlank() ? DEFAULT_REPORT_BASE_URL : reportBaseUrl;
    }

    @Override
    public PagePipelineResult run(Path repoRoot, String pageAppId, Path planOutput, Path outputJson) throws Exception {
        WebAuth webAuth = loadWebAuth(repoRoot);
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(new ConfigPaths(repoRoot).aiAuth().toFile(), AiAuthConfig.class);

        // Load projectId from app auth file as fallback
        Path appAuthPath = repoRoot.resolve("data").resolve("outputs").resolve("app_authorizations").resolve("app_authorize_java_phase1.json");
        String projectIdFromAuth = "";
        if (Files.exists(appAuthPath)) {
            JsonNode appAuth = Jacksons.mapper().readTree(appAuthPath.toFile());
            JsonNode authData = appAuth.path("data");
            if (authData.isArray() && authData.size() > 0) {
                projectIdFromAuth = authData.get(0).path("projectId").asText("");
            }
        }

        // Try to resolve app context by appId first
        AppContext appContext;
        try {
            appContext = resolveAppContextByAppId(webAuth, pageAppId, projectIdFromAuth);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("帐号已退出")) {
                logger.warn("⚠️ Web authentication expired, running PagePipeline in dry-run mode");
                return runDryRun(repoRoot, pageAppId, planOutput, outputJson, aiAuth);
            }
            throw e;
        }

        Files.createDirectories(planOutput.getParent());
        JsonNode plan = buildPagePlan(appContext, pageAppId, aiAuth);
        Jacksons.mapper().writeValue(planOutput.toFile(), plan);
        String appId = plan.path("appId").asText("");
        String appName = plan.path("appName").asText("");
        String projectId = plan.path("projectId").asText("");
        String appSectionId = plan.path("appSectionId").asText("");
        // Fallback: use appId as appSectionId if empty
        if (appSectionId.isBlank()) {
            appSectionId = appId;
        }
        ArrayNode pages = asArray(plan.path("pages"));
        if (appId.isBlank() || projectId.isBlank() || appSectionId.isBlank()) {
            throw new IllegalStateException("Page plan missing appId/projectId/appSectionId");
        }

        ArrayNode results = Jacksons.mapper().createArrayNode();
        int successCount = 0;
        for (JsonNode page : pages) {
            try {
                ObjectNode pageResult = processPage(repoRoot, webAuth, appId, appName, projectId, appSectionId, page);
                results.add(pageResult);
                if ("success".equals(pageResult.path("status").asText(""))) {
                    successCount++;
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("帐号已退出")) {
                    logger.warn("⚠️ Web authentication expired during page creation, running remaining in dry-run mode");
                    ObjectNode dryRunResult = Jacksons.mapper().createObjectNode();
                    dryRunResult.put("name", page.path("name").asText("Page"));
                    dryRunResult.put("status", "dry-run");
                    dryRunResult.put("reason", "web_auth_expired");
                    results.add(dryRunResult);
                } else {
                    throw e;
                }
            }
        }

        ObjectNode summary = Jacksons.mapper().createObjectNode();
        summary.put("createdAt", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        summary.put("appId", appId);
        summary.put("appName", appName);
        summary.put("planFile", planOutput.toAbsolutePath().normalize().toString());
        summary.put("totalPages", pages.size());
        summary.put("successCount", successCount);
        summary.put("logFile", "");
        summary.set("results", results);

        Files.createDirectories(outputJson.getParent());
        Jacksons.mapper().writeValue(outputJson.toFile(), summary);

        if (successCount == 0 && !pages.isEmpty()) {
            // Don't throw error if in dry-run mode due to auth expiration
            if (results.size() > 0 && results.get(0).path("status").asText("").equals("dry-run")) {
                logger.info("Page pipeline completed in dry-run mode due to authentication expiration");
            } else {
                throw new IllegalStateException("Page pipeline produced zero successful pages");
            }
        }
        return new PagePipelineResult(planOutput, outputJson, summary);
    }

    private PagePipelineResult runDryRun(Path repoRoot, String pageAppId, Path planOutput, Path outputJson, AiAuthConfig aiAuth) throws Exception {
        // Create a simple dry-run plan
        ObjectNode plan = Jacksons.mapper().createObjectNode();
        plan.put("schemaVersion", "page_plan_v1");
        plan.put("generatedAt", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        plan.put("appId", pageAppId);
        plan.put("appIdInput", pageAppId);
        plan.put("appName", "DryRun Mode");
        plan.put("projectId", "");
        plan.put("appSectionId", pageAppId);
        plan.put("language", "zh");
        ArrayNode pages = plan.putArray("pages");
        ObjectNode page = pages.addObject();
        page.put("name", "概览");
        page.put("icon", "sys_dashboard");
        page.put("iconColor", "#2196F3");
        page.put("desc", "业务概览 (Dry Run)");
        page.putArray("worksheetNames");
        page.putArray("worksheetIds");

        Files.createDirectories(planOutput.getParent());
        Jacksons.mapper().writeValue(planOutput.toFile(), plan);

        // Create dry-run results
        ArrayNode results = Jacksons.mapper().createArrayNode();
        ObjectNode dryRunResult = Jacksons.mapper().createObjectNode();
        dryRunResult.put("name", "概览");
        dryRunResult.put("status", "dry-run");
        dryRunResult.put("reason", "web_auth_expired");
        results.add(dryRunResult);

        ObjectNode summary = Jacksons.mapper().createObjectNode();
        summary.put("createdAt", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        summary.put("appId", pageAppId);
        summary.put("appName", "DryRun Mode");
        summary.put("planFile", planOutput.toAbsolutePath().normalize().toString());
        summary.put("totalPages", 1);
        summary.put("successCount", 0);
        summary.put("logFile", "");
        summary.set("results", results);

        Files.createDirectories(outputJson.getParent());
        Jacksons.mapper().writeValue(outputJson.toFile(), summary);

        logger.info("Page pipeline completed in dry-run mode due to authentication expiration");
        return new PagePipelineResult(planOutput, outputJson, summary);
    }

    private JsonNode buildPagePlan(AppContext appContext, String pageAppId, AiAuthConfig aiAuth) throws Exception {
        ArrayNode worksheetNames = Jacksons.mapper().createArrayNode();
        for (WorksheetRef worksheet : appContext.worksheets()) {
            worksheetNames.add(worksheet.name());
        }
        String prompt = """
                你是企业分析页面规划助手。请为应用规划 1 个分析页面，输出严格 JSON。

                appId: %s
                appName: %s
                worksheets: %s

                输出格式：
                {
                  "schemaVersion": "page_plan_v1",
                  "generatedAt": "2026-04-21 00:00:00",
                  "appId": "app-123",
                  "appIdInput": "%s",
                  "appName": "Demo App",
                  "projectId": "project-1",
                  "appSectionId": "section-1",
                  "language": "zh",
                  "pages": [
                    {
                      "name": "概览",
                      "icon": "sys_dashboard",
                      "iconColor": "#2196F3",
                      "desc": "业务概览",
                      "worksheetNames": ["工作表名称1", "工作表名称2"]
                    }
                  ]
                }
                只输出 JSON。
                """.formatted(appContext.appId(), appContext.appName(), worksheetNames.toString(), pageAppId);
        JsonNode parsed = aiJsonParser.parse(aiClient.generateJson(prompt, aiAuth));
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("Page planner AI response must be a JSON object");
        }
        if (!objectNode.has("schemaVersion")) {
            objectNode.put("schemaVersion", "page_plan_v1");
        }
        if (!objectNode.has("generatedAt")) {
            objectNode.put("generatedAt", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        }
        objectNode.put("appId", appContext.appId());
        objectNode.put("appIdInput", pageAppId);
        objectNode.put("appName", appContext.appName());
        objectNode.put("projectId", appContext.projectId());
        objectNode.put("appSectionId", appContext.appSectionId());
        objectNode.put("language", "zh");
        normalizePlannedPages(objectNode, appContext.worksheets());
        return objectNode;
    }

    private void normalizePlannedPages(ObjectNode plan, List<WorksheetRef> worksheets) {
        Map<String, String> idsByName = new LinkedHashMap<>();
        for (WorksheetRef worksheet : worksheets) {
            idsByName.put(worksheet.name(), worksheet.id());
        }
        ArrayNode pages = asArray(plan.path("pages"));
        for (JsonNode pageNode : pages) {
            if (!(pageNode instanceof ObjectNode page)) {
                continue;
            }
            ArrayNode names = asArray(page.path("worksheetNames"));
            ArrayNode ids = Jacksons.mapper().createArrayNode();
            for (JsonNode nameNode : names) {
                String id = idsByName.get(nameNode.asText(""));
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                for (WorksheetRef worksheet : worksheets) {
                    ids.add(worksheet.id());
                }
                names.removeAll();
                for (WorksheetRef worksheet : worksheets) {
                    names.add(worksheet.name());
                }
            }
            page.set("worksheetIds", ids);
        }
    }

    private AppContext resolveAppContextByAppId(WebAuth webAuth, String appIdInput, String fallbackProjectId) throws Exception {
        // Directly use GetApp API with appId (instead of going through GetWorksheetInfo)
        ObjectNode getAppRequest = Jacksons.mapper().createObjectNode();
        getAppRequest.put("appId", appIdInput);
        getAppRequest.put("getSection", true);
        JsonNode getAppResponse = postWebJson(webAuth, webBaseUrl + GET_APP_ENDPOINT, webBaseUrl + "/", getAppRequest);
        JsonNode appData = getAppResponse.path("data");

        // Get appId from response (might be different from input if input was worksheetId)
        String appId = appData.path("appId").asText("");
        if (appId.isBlank()) {
            // Fallback to input if response doesn't contain appId
            appId = appIdInput;
        }

        String appName = appData.path("appName").asText(appData.path("name").asText(appId));
        String projectId = appData.path("projectId").asText("");

        // Use fallback projectId from auth file if GetApp response doesn't have it
        if (projectId.isBlank() && !fallbackProjectId.isBlank()) {
            projectId = fallbackProjectId;
        }

        if (projectId.isBlank()) {
            throw new IllegalStateException("GetApp response missing projectId and no fallback available");
        }

        String appSectionId = "";
        List<WorksheetRef> worksheets = new ArrayList<>();
        for (JsonNode section : asArray(appData.path("sections"))) {
            String candidateSectionId = section.path("appSectionId").asText("");
            if (!candidateSectionId.isBlank() && appSectionId.isBlank()) {
                appSectionId = candidateSectionId;
            }
            for (JsonNode worksheet : asArray(section.path("workSheetInfo"))) {
                if (worksheet.path("type").asInt(-1) != 0) {
                    continue;
                }
                String id = worksheet.path("workSheetId").asText("");
                String name = worksheet.path("workSheetName").asText("");
                if (!id.isBlank() && !name.isBlank()) {
                    worksheets.add(new WorksheetRef(id, name));
                }
            }
        }

        if (appSectionId.isBlank()) {
            // Fallback: use first available section or empty
            appSectionId = "";
        }

        return new AppContext(appId, appName.isBlank() ? appId : appName, projectId, appSectionId, worksheets);
    }

    // Deprecated: kept for backward compatibility, but resolveAppContextByAppId is preferred
    private AppContext resolveAppContext(WebAuth webAuth, String seedWorksheetId) throws Exception {
        ObjectNode worksheetInfoRequest = Jacksons.mapper().createObjectNode();
        worksheetInfoRequest.put("worksheetId", seedWorksheetId);
        JsonNode worksheetInfo = postWebJson(webAuth, webBaseUrl + GET_WORKSHEET_INFO_ENDPOINT, webBaseUrl + "/", worksheetInfoRequest);
        JsonNode worksheetData = worksheetInfo.path("data");
        String appId = worksheetData.path("appId").asText("");
        String appName = worksheetData.path("appName").asText("");
        String projectId = worksheetData.path("projectId").asText("");
        String appSectionId = worksheetData.path("groupId").asText("");
        if (appId.isBlank() || projectId.isBlank() || appSectionId.isBlank()) {
            throw new IllegalStateException("GetWorksheetInfo missing app metadata");
        }

        ObjectNode getAppRequest = Jacksons.mapper().createObjectNode();
        getAppRequest.put("appId", appId);
        getAppRequest.put("getSection", true);
        JsonNode getAppResponse = postWebJson(webAuth, webBaseUrl + GET_APP_ENDPOINT, webBaseUrl + "/", getAppRequest);
        JsonNode appData = getAppResponse.path("data");
        List<WorksheetRef> worksheets = new ArrayList<>();
        for (JsonNode section : asArray(appData.path("sections"))) {
            String candidateSectionId = section.path("appSectionId").asText("");
            if (appSectionId.isBlank() && !candidateSectionId.isBlank()) {
                appSectionId = candidateSectionId;
            }
            for (JsonNode worksheet : asArray(section.path("workSheetInfo"))) {
                if (worksheet.path("type").asInt(-1) != 0) {
                    continue;
                }
                String id = worksheet.path("workSheetId").asText("");
                String name = worksheet.path("workSheetName").asText("");
                if (!id.isBlank() && !name.isBlank()) {
                    worksheets.add(new WorksheetRef(id, name));
                }
            }
        }
        return new AppContext(appId, appName.isBlank() ? appId : appName, projectId, appSectionId, worksheets);
    }

    private ObjectNode processPage(
            Path repoRoot,
            WebAuth webAuth,
            String appId,
            String appName,
            String projectId,
            String appSectionId,
            JsonNode page) throws Exception {
        String pageName = page.path("name").asText("Page");
        String icon = page.path("icon").asText("sys_dashboard");
        String iconColor = page.path("iconColor").asText("#2196F3");
        String desc = page.path("desc").asText("");
        List<String> worksheetIds = new ArrayList<>();
        for (JsonNode worksheetId : asArray(page.path("worksheetIds"))) {
            String value = worksheetId.asText("");
            if (!value.isBlank()) {
                worksheetIds.add(value);
            }
        }

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("pageName", pageName);
        result.put("icon", icon);
        result.put("iconColor", iconColor);
        result.put("desc", desc);
        ArrayNode worksheetIdsNode = result.putArray("worksheetIds");
        for (String worksheetId : worksheetIds) {
            worksheetIdsNode.add(worksheetId);
        }

        String pageId = createPage(webAuth, appId, projectId, appSectionId, pageName, icon, iconColor);
        initializePage(webAuth, pageId);

        Path chartPlan = repoRoot.resolve("data").resolve("outputs").resolve("chart_plans")
                .resolve("chart_plan_" + appId + "_page_" + pageId + ".json");
        Path chartCreate = repoRoot.resolve("data").resolve("outputs").resolve("page_create_results")
                .resolve("chart_create_" + appId + "_page_" + pageId + ".json");
        Files.createDirectories(chartPlan.getParent());
        Files.createDirectories(chartCreate.getParent());

        ChartPipelineResult chartResult = chartPipelineRunner.run(
                repoRoot,
                appId,
                appName + "-" + pageName,
                worksheetIds,
                pageId,
                chartPlan,
                chartCreate);

        result.put("pageId", pageId);
        result.put("skipped", false);
        result.put("createStatus", "created");
        result.put("status", "success");
        result.put("chartPlanFile", chartResult.planOutputPath().toAbsolutePath().normalize().toString());
        result.put("chartCreateFile", chartResult.outputJsonPath().toAbsolutePath().normalize().toString());
        return result;
    }

    private String createPage(
            WebAuth webAuth,
            String appId,
            String projectId,
            String appSectionId,
            String pageName,
            String icon,
            String iconColor) throws Exception {
        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("appId", appId);
        body.put("appSectionId", appSectionId);
        body.put("name", pageName);
        body.put("remark", "");
        body.put("iconColor", iconColor);
        body.put("projectId", projectId);
        body.put("icon", icon);
        body.put("iconUrl", "https://fp1.mingdaoyun.cn/customIcon/" + icon + ".svg");
        body.put("type", 1);
        body.put("createType", 0);

        JsonNode response = postWebJson(webAuth, webBaseUrl + ADD_WORKSHEET_ENDPOINT, "", body);
        if (response.path("state").asInt(0) != 1) {
            throw new IllegalStateException("AddWorkSheet failed: " + Jacksons.mapper().writeValueAsString(response));
        }
        String pageId = response.path("data").path("pageId").asText("");
        if (pageId.isBlank()) {
            throw new IllegalStateException("AddWorkSheet response missing pageId");
        }
        return pageId;
    }

    private void initializePage(WebAuth webAuth, String pageId) throws Exception {
        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("appId", pageId);
        body.put("version", 0);
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

        JsonNode response = postWebJson(webAuth, reportBaseUrl + SAVE_PAGE_ENDPOINT, "", body);
        boolean ok = response.path("status").asInt(0) == 1 || response.path("success").asBoolean(false);
        if (!ok) {
            throw new IllegalStateException("savePage init failed: " + Jacksons.mapper().writeValueAsString(response));
        }
    }

    private JsonNode postWebJson(WebAuth webAuth, String url, String referer, JsonNode body) throws Exception {
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

    private ArrayNode asArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : Jacksons.mapper().createArrayNode();
    }

    private record WebAuth(String accountId, String authorization, String cookie) {
    }

    private record WorksheetRef(String id, String name) {
    }

    private record AppContext(String appId, String appName, String projectId, String appSectionId, List<WorksheetRef> worksheets) {
    }
}
