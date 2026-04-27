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

public final class ChartPipelineService implements ChartPipelineRunner {

    private static final String DEFAULT_WEB_BASE_URL = "https://www.mingdao.com";
    private static final String DEFAULT_REPORT_BASE_URL = "https://api.mingdao.com";
    private static final String GET_APP_ENDPOINT = "/api/HomeApp/GetApp";
    private static final String SAVE_REPORT_CONFIG_ENDPOINT = "/report/reportConfig/saveReportConfig";
    private static final String GET_PAGE_ENDPOINT = "/report/custom/getPage";
    private static final String SAVE_PAGE_ENDPOINT = "/report/custom/savePage";
    private static final Pattern PY_AUTH_PATTERN = Pattern.compile("^(ACCOUNT_ID|AUTHORIZATION|COOKIE)\\s*=\\s*\"(.*)\"\\s*$");

    private final AiTextClient aiClient;
    private final AiJsonParser aiJsonParser;
    private final HttpClient httpClient;
    private final String webBaseUrl;
    private final String reportBaseUrl;

    public ChartPipelineService() {
        this(
                new HttpAiTextClient(),
                new AiJsonParser(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                DEFAULT_WEB_BASE_URL,
                DEFAULT_REPORT_BASE_URL);
    }

    public ChartPipelineService(String webBaseUrl, String reportBaseUrl) {
        this(
                new HttpAiTextClient(),
                new AiJsonParser(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                webBaseUrl,
                reportBaseUrl);
    }

    ChartPipelineService(AiTextClient aiClient, String webBaseUrl, String reportBaseUrl) {
        this(
                aiClient,
                new AiJsonParser(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                webBaseUrl,
                reportBaseUrl);
    }

    ChartPipelineService(
            AiTextClient aiClient,
            AiJsonParser aiJsonParser,
            HttpClient httpClient,
            String webBaseUrl,
            String reportBaseUrl) {
        this.aiClient = aiClient;
        this.aiJsonParser = aiJsonParser;
        this.httpClient = httpClient;
        this.webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? DEFAULT_WEB_BASE_URL : webBaseUrl;
        this.reportBaseUrl = reportBaseUrl == null || reportBaseUrl.isBlank() ? DEFAULT_REPORT_BASE_URL : reportBaseUrl;
    }

    @Override
    public ChartPipelineResult run(
            Path repoRoot,
            String appId,
            String appName,
            List<String> worksheetIds,
            String pageId,
            Path planOutput,
            Path outputJson) throws Exception {
        WebAuth webAuth = loadWebAuth(repoRoot);
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(new ConfigPaths(repoRoot).aiAuth().toFile(), AiAuthConfig.class);
        Files.createDirectories(planOutput.getParent());
        JsonNode plan = buildChartPlan(aiAuth, appId, appName, worksheetIds, "", 6);
        Jacksons.mapper().writeValue(planOutput.toFile(), plan);
        ArrayNode charts = asArray(plan.path("charts"));
        ArrayNode results = Jacksons.mapper().createArrayNode();
        int successCount = 0;
        for (JsonNode chart : charts) {
            ObjectNode one = createChart(webAuth, appId, pageId, chart);
            results.add(one);
            if ("success".equals(one.path("status").asText(""))) {
                successCount++;
            }
        }

        if (!pageId.isBlank() && successCount > 0) {
            JsonNode currentPage = getPage(webAuth, pageId);
            int version = currentPage.path("version").asInt(1);
            ArrayNode components = buildPageComponents(results, appId, asArray(currentPage.path("components")));
            savePage(webAuth, pageId, version, components);
        }

        ObjectNode summary = Jacksons.mapper().createObjectNode();
        summary.put("createdAt", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        summary.put("appId", appId);
        summary.put("appName", appName);
        summary.put("planFile", planOutput.toAbsolutePath().normalize().toString());
        summary.put("totalCharts", charts.size());
        summary.put("successCount", successCount);
        summary.put("skippedCount", 0);
        summary.set("results", results);

        Files.createDirectories(outputJson.getParent());
        Jacksons.mapper().writeValue(outputJson.toFile(), summary);
        return new ChartPipelineResult(planOutput, outputJson, summary);
    }

    public JsonNode addCharts(
            Path repoRoot,
            String appId,
            String worksheetId,
            String pageId,
            String description,
            int targetCount,
            boolean execute) throws Exception {
        WebAuth webAuth = loadWebAuth(repoRoot);
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(new ConfigPaths(repoRoot).aiAuth().toFile(), AiAuthConfig.class);
        AppMetadata appMetadata = resolveAppMetadata(webAuth, appId, worksheetId);

        Path incrementalDir = repoRoot.resolve("data").resolve("outputs").resolve("incremental");
        Files.createDirectories(incrementalDir);
        String timestamp = nowTs();
        Path planOutput = incrementalDir.resolve("chart_plan_" + appId + "_" + timestamp + ".json");
        Path outputJson = incrementalDir.resolve("chart_add_" + appId + "_" + timestamp + ".json");

        JsonNode plan = buildChartPlan(
                aiAuth,
                appId,
                appMetadata.appName(),
                appMetadata.worksheetIds(),
                description == null ? "" : description,
                Math.max(1, targetCount));
        Jacksons.mapper().writeValue(planOutput.toFile(), plan);

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("status", execute ? "success" : "plan_only");
        result.put("appId", appId);
        result.put("appName", appMetadata.appName());
        ArrayNode worksheetIdsNode = result.putArray("worksheetIds");
        for (String resolvedWorksheetId : appMetadata.worksheetIds()) {
            worksheetIdsNode.add(resolvedWorksheetId);
        }
        result.put("pageId", pageId == null ? "" : pageId);
        result.put("planFile", planOutput.toAbsolutePath().normalize().toString());
        result.set("plan", plan);

        if (!execute) {
            return result;
        }

        ArrayNode charts = asArray(plan.path("charts"));
        ArrayNode results = Jacksons.mapper().createArrayNode();
        int successCount = 0;
        for (JsonNode chart : charts) {
            ObjectNode one = createChart(webAuth, appId, pageId, chart);
            results.add(one);
            if ("success".equals(one.path("status").asText(""))) {
                successCount++;
            }
        }

        if (pageId != null && !pageId.isBlank() && successCount > 0) {
            JsonNode currentPage = getPage(webAuth, pageId);
            int version = currentPage.path("version").asInt(1);
            ArrayNode components = buildPageComponents(results, appId, asArray(currentPage.path("components")));
            savePage(webAuth, pageId, version, components);
        }

        ObjectNode summary = Jacksons.mapper().createObjectNode();
        summary.put("createdAt", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        summary.put("appId", appId);
        summary.put("appName", appMetadata.appName());
        summary.put("planFile", planOutput.toAbsolutePath().normalize().toString());
        summary.put("outputFile", outputJson.toAbsolutePath().normalize().toString());
        summary.put("totalCharts", charts.size());
        summary.put("successCount", successCount);
        summary.put("skippedCount", 0);
        summary.set("results", results);
        Jacksons.mapper().writeValue(outputJson.toFile(), summary);

        result.put("outputFile", outputJson.toAbsolutePath().normalize().toString());
        result.put("successCount", successCount);
        result.set("results", results);
        return result;
    }

    private JsonNode buildChartPlan(
            AiAuthConfig aiAuth,
            String appId,
            String appName,
            List<String> worksheetIds,
            String description,
            int targetCount) throws Exception {
        String prompt = """
                You are an enterprise analytics assistant. Plan %d business charts for app "%s".
                worksheetIds: %s
                extraDescription: %s

                Output strict JSON:
                {
                  "appId": "%s",
                  "appName": "%s",
                  "charts": [
                    {
                      "name": "Chart name",
                      "desc": "One line description",
                      "reportType": 10,
                      "worksheetId": "worksheet-id",
                      "xaxes": {
                        "controlId": "",
                        "controlType": 0,
                        "particleSizeType": 0,
                        "sortType": 0,
                        "emptyType": 0
                      },
                      "yaxisList": [
                        {
                          "controlId": "record_count",
                          "controlType": 0,
                          "rename": "Count"
                        }
                      ],
                      "filter": {
                        "filterRangeId": "ctime",
                        "filterRangeName": "Created Time",
                        "rangeType": 18,
                        "rangeValue": 365,
                        "today": true
                      }
                    }
                  ]
                }

                Rules:
                1) Include at least one number chart (reportType=10)
                2) Include at least one pie chart (reportType=3)
                3) Include at least one line chart (reportType=2)
                4) Every worksheetId must come from the provided list
                5) Output JSON only
                """.formatted(targetCount, appName, String.join(",", worksheetIds), description, appId, appName);
        JsonNode parsed = aiJsonParser.parse(aiClient.generateJson(prompt, aiAuth));
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("Chart planner AI response must be a JSON object");
        }
        return objectNode;
    }

    private JsonNode buildChartPlan(
            AiAuthConfig aiAuth,
            String appId,
            String appName,
            List<String> worksheetIds) throws Exception {
        String prompt = """
                你是企业数据分析助手。请为应用“%s”规划 6 个业务图表，输出严格 JSON。

                worksheetIds: %s

                输出格式：
                {
                  "appId": "%s",
                  "appName": "%s",
                  "charts": [
                    {
                      "name": "图表名称",
                      "desc": "一句话描述",
                      "reportType": 10,
                      "worksheetId": "工作表ID",
                      "xaxes": {
                        "controlId": "",
                        "controlType": 0,
                        "particleSizeType": 0,
                        "sortType": 0,
                        "emptyType": 0
                      },
                      "yaxisList": [
                        {
                          "controlId": "record_count",
                          "controlType": 0,
                          "rename": "记录数量"
                        }
                      ],
                      "filter": {
                        "filterRangeId": "ctime",
                        "filterRangeName": "创建时间",
                        "rangeType": 18,
                        "rangeValue": 365,
                        "today": true
                      }
                    }
                  ]
                }

                规则：
                1) 至少包含 1 个数字图(reportType=10)
                2) 至少包含 1 个饼图(reportType=3)
                3) 至少包含 1 个折线图(reportType=2)
                4) 所有 worksheetId 必须从给定列表中选择
                5) 只输出 JSON
                """.formatted(appName, String.join(",", worksheetIds), appId, appName);
        JsonNode parsed = aiJsonParser.parse(aiClient.generateJson(prompt, aiAuth));
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("Chart planner AI response must be a JSON object");
        }
        return objectNode;
    }

    private ObjectNode createChart(WebAuth webAuth, String appId, String pageId, JsonNode chart) throws Exception {
        String chartName = chart.path("name").asText("Chart");
        int reportType = chart.path("reportType").asInt(1);
        JsonNode body = buildReportBody(chart, appId);
        JsonNode response = postReportJson(webAuth, reportBaseUrl + SAVE_REPORT_CONFIG_ENDPOINT, appId, pageId, body);
        String reportId = response.path("data").path("reportId").asText(response.path("data").path("id").asText(""));
        boolean success = response.path("status").asInt(0) == 1 || response.path("success").asBoolean(false) || !reportId.isBlank();

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("chartName", chartName);
        result.put("reportType", reportType);
        result.put("worksheetId", chart.path("worksheetId").asText(""));
        result.put("status", success ? "success" : "failed");
        result.put("reportId", reportId);
        result.set("response", response);
        return result;
    }

    private JsonNode buildReportBody(JsonNode chart, String appId) {
        int reportType = chart.path("reportType").asInt(1);
        ObjectNode body = baseBody(chart, appId, reportType);
        if (reportType == 3 || reportType == 6) {
            ((ObjectNode) body.path("displaySetup")).put("showPercent", true);
        }
        if (reportType == 10 || reportType == 14 || reportType == 15) {
            ObjectNode xaxes = (ObjectNode) body.path("xaxes");
            xaxes.put("controlId", "");
            xaxes.put("cid", "");
            xaxes.put("c_Id", "");
            xaxes.put("controlName", "");
            xaxes.put("cname", "");
            if (reportType != 10) {
                xaxes.put("controlType", 0);
            }
        }
        if (reportType == 14) {
            body.put("config", chart.path("config").isObject()
                    ? compactObject(chart.path("config"))
                    : Jacksons.mapper().createObjectNode());
            ((ObjectNode) body.path("displaySetup")).put("showChartType", 3);
        }
        if (reportType == 15) {
            body.put("config", chart.path("config").isObject()
                    ? compactObject(chart.path("config"))
                    : Jacksons.mapper().createObjectNode());
        }
        if (reportType == 16) {
            ObjectNode style = body.putObject("style");
            style.put("topStyle", "crown");
            style.put("valueProgressVisible", true);
            ArrayNode sorts = body.putArray("sorts");
            sorts.addObject().put("record_count", 2);
        }
        if ((reportType == 9 || reportType == 17) && chart.path("country").isObject()) {
            body.set("country", chart.path("country").deepCopy());
            if (reportType == 9) {
                ObjectNode style = body.putObject("style");
                style.put("isDrillDownLayer", true);
            }
        }
        if (reportType == 7 && chart.path("rightY").isObject()) {
            ObjectNode rightY = body.putObject("rightY");
            rightY.put("reportType", chart.path("rightY").path("reportType").asInt(2));
            ArrayNode yaxisList = rightY.putArray("yaxisList");
            for (JsonNode y : asArray(chart.path("rightY").path("yaxisList"))) {
                yaxisList.add(buildYaxis(y));
            }
            body.put("yreportType", chart.path("yreportType").asInt(2));
        }
        return body;
    }

    private ObjectNode baseBody(JsonNode chart, String appId, int reportType) {
        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("splitId", chart.path("splitId").asText(chart.path("split").path("controlId").asText("")));
        body.set("split", chart.path("split").isObject() ? chart.path("split").deepCopy() : Jacksons.mapper().createObjectNode());
        body.set("displaySetup", baseDisplaySetup(reportType, chart.path("xaxes")));
        body.put("name", chart.path("name").asText(""));
        body.put("desc", chart.path("desc").asText(""));
        body.put("reportType", reportType);
        body.set("filter", buildFilter(chart.path("filter")));
        body.put("createdDate", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        ObjectNode account = body.putObject("account");
        account.put("accountId", "");
        account.putNull("fullName");
        account.putNull("avatar");
        account.putNull("status");
        body.put("appId", chart.path("worksheetId").asText(appId));
        body.put("appType", 1);
        body.putArray("sorts");
        ObjectNode summary = body.putObject("summary");
        summary.put("controlId", "");
        summary.put("type", 1);
        summary.put("name", "总计");
        summary.put("number", true);
        summary.put("percent", false);
        summary.put("sum", 0);
        summary.put("contrastSum", 0);
        summary.put("contrastMapSum", 0);
        summary.put("rename", "");
        body.putObject("style");
        body.putArray("formulas");
        body.set("views", chart.path("views").isArray() ? chart.path("views").deepCopy() : Jacksons.mapper().createArrayNode());
        body.put("auth", 1);
        body.putNull("yreportType");
        ArrayNode yaxisList = body.putArray("yaxisList");
        for (JsonNode y : asArray(chart.path("yaxisList"))) {
            yaxisList.add(buildYaxis(y));
        }
        body.set("xaxes", buildXaxes(chart.path("xaxes")));
        body.put("sourceType", 1);
        body.put("isPublic", true);
        body.put("id", chart.path("id").asText(""));
        body.put("version", "6.5");
        return body;
    }

    private ObjectNode baseDisplaySetup(int reportType, JsonNode xaxes) {
        ObjectNode setup = Jacksons.mapper().createObjectNode();
        setup.put("isPerPile", false);
        setup.put("isPile", false);
        setup.put("isAccumulate", false);
        setup.putNull("accumulatePerPile");
        setup.put("isToday", false);
        setup.put("isLifecycle", false);
        setup.put("lifecycleValue", 0);
        setup.put("contrastType", 0);
        setup.put("fontStyle", 1);
        setup.put("showTotal", false);
        setup.put("showTitle", true);
        setup.put("showLegend", true);
        setup.put("legendType", 1);
        setup.put("showDimension", true);
        setup.put("showNumber", true);
        setup.put("showPercent", reportType == 3 || reportType == 6);
        setup.put("showXAxisCount", 0);
        setup.put("showChartType", 1);
        setup.put("showPileTotal", true);
        setup.put("hideOverlapText", false);
        setup.put("showRowList", true);
        setup.putArray("showControlIds");
        setup.putArray("auxiliaryLines");
        setup.putArray("showOptionIds");
        setup.put("contrast", false);
        setup.putArray("colorRules");
        ObjectNode percent = setup.putObject("percent");
        percent.put("enable", false);
        percent.put("type", 2);
        percent.put("dot", "2");
        percent.put("dotFormat", "1");
        percent.put("roundType", 2);
        setup.put("mergeCell", true);
        setup.putNull("previewUrl");
        setup.putNull("imageUrl");
        ObjectNode xdisplay = setup.putObject("xdisplay");
        xdisplay.put("showDial", true);
        xdisplay.put("showTitle", false);
        xdisplay.put("title", xaxes.path("controlName").asText(""));
        xdisplay.putNull("minValue");
        xdisplay.putNull("maxValue");
        setup.put("xaxisEmpty", false);
        ObjectNode ydisplay = setup.putObject("ydisplay");
        ydisplay.put("showDial", true);
        ydisplay.put("showTitle", false);
        ydisplay.put("title", "记录数量");
        ydisplay.putNull("minValue");
        ydisplay.putNull("maxValue");
        ydisplay.put("lineStyle", 1);
        ydisplay.putNull("showNumber");
        if (reportType == 10) {
            setup.put("showLegend", false);
            setup.put("showDimension", false);
        }
        if (reportType == 13) {
            setup.put("mergeCell", true);
            setup.put("showRowList", true);
        }
        return setup;
    }

    private ObjectNode buildXaxes(JsonNode xaxes) {
        String controlId = xaxes.path("controlId").asText("");
        String controlName = xaxes.path("controlName").asText("");
        int controlType = xaxes.path("controlType").asInt(16);
        String cid = controlId.isBlank() ? "" : controlId + "-1";

        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("controlId", controlId);
        payload.put("sortType", xaxes.path("sortType").asInt(0));
        payload.put("particleSizeType", xaxes.path("particleSizeType").asInt(0));
        payload.put("rename", xaxes.path("rename").asText(""));
        payload.put("emptyType", xaxes.path("emptyType").asInt(0));
        payload.putNull("fields");
        payload.put("subTotal", false);
        payload.putNull("subTotalName");
        payload.put("showFormat", "4");
        payload.put("displayMode", "text");
        payload.put("controlName", controlName);
        payload.put("controlType", controlType);
        payload.put("dataSource", "");
        payload.putArray("options");
        payload.putObject("advancedSetting");
        payload.putNull("relationControl");
        payload.put("cid", cid);
        payload.put("cname", controlName);
        payload.put("xaxisEmptyType", xaxes.path("xaxisEmptyType").asInt(0));
        payload.put("xaxisEmpty", xaxes.path("xaxisEmpty").asBoolean(false));
        payload.put("c_Id", cid);
        return payload;
    }

    private ObjectNode buildYaxis(JsonNode y) {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("controlId", y.path("controlId").asText("record_count"));
        payload.put("controlName", y.path("controlName").asText("记录数量"));
        payload.put("controlType", y.path("controlType").asInt(10000000));
        payload.put("magnitude", 0);
        payload.put("roundType", 2);
        payload.put("dotFormat", "1");
        payload.put("suffix", "");
        payload.put("ydot", 2);
        payload.put("fixType", 0);
        payload.put("showNumber", true);
        payload.put("hide", false);
        ObjectNode percent = payload.putObject("percent");
        percent.put("enable", false);
        percent.put("type", 2);
        percent.put("dot", "2");
        percent.put("dotFormat", "1");
        percent.put("roundType", 2);
        payload.put("normType", 5);
        payload.put("emptyShowType", 0);
        payload.put("dot", 0);
        payload.put("rename", y.path("rename").asText(""));
        payload.putObject("advancedSetting");
        return payload;
    }

    private ObjectNode buildFilter(JsonNode filter) {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("filterRangeId", filter.path("filterRangeId").asText("ctime"));
        payload.put("filterRangeName", filter.path("filterRangeName").asText("创建时间"));
        payload.put("rangeType", filter.path("rangeType").asInt(18));
        payload.put("rangeValue", filter.path("rangeValue").asInt(365));
        payload.put("today", filter.path("today").asBoolean(true));
        return payload;
    }

    private JsonNode getPage(WebAuth webAuth, String pageId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(reportBaseUrl + GET_PAGE_ENDPOINT + "?appId=" + pageId))
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
        if (body.path("status").asInt(0) != 1) {
            throw new IllegalStateException("getPage failed: " + Jacksons.mapper().writeValueAsString(body));
        }
        return body.path("data");
    }

    private void savePage(WebAuth webAuth, String pageId, int version, ArrayNode components) throws Exception {
        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("appId", pageId);
        body.put("version", version);
        body.set("components", components);
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
        config.put("orightWebCols", 48);

        JsonNode response = postReportJson(webAuth, reportBaseUrl + SAVE_PAGE_ENDPOINT, "", pageId, body);
        boolean ok = response.path("status").asInt(0) == 1 || response.path("success").asBoolean(false);
        if (!ok) {
            throw new IllegalStateException("savePage failed: " + Jacksons.mapper().writeValueAsString(response));
        }
    }

    private ArrayNode buildPageComponents(ArrayNode results, String appId, ArrayNode existingComponents) {
        ArrayNode all = existingComponents.deepCopy();
        int maxY = 0;
        for (JsonNode component : existingComponents) {
            JsonNode layout = component.path("web").path("layout");
            int bottom = layout.path("y").asInt(0) + layout.path("h").asInt(0);
            if (bottom > maxY) {
                maxY = bottom;
            }
        }

        int successIndex = 0;
        for (JsonNode result : results) {
            if (!"success".equals(result.path("status").asText("")) || result.path("reportId").asText("").isBlank()) {
                continue;
            }
            int x = (successIndex % 2) * 24;
            int y = maxY + (successIndex / 2) * 12;
            successIndex++;
            ObjectNode component = all.addObject();
            component.put("id", "component-" + successIndex);
            component.put("type", 1);
            component.put("value", result.path("reportId").asText(""));
            component.put("valueExtend", result.path("reportId").asText(""));
            component.putObject("config").put("objectId", "object-" + successIndex);
            ObjectNode web = component.putObject("web");
            web.put("titleVisible", false);
            web.put("title", "");
            web.put("visible", true);
            ObjectNode layout = web.putObject("layout");
            layout.put("x", x);
            layout.put("y", y);
            layout.put("w", 24);
            layout.put("h", 12);
            layout.put("minW", 2);
            layout.put("minH", 4);
            ObjectNode mobile = component.putObject("mobile");
            mobile.put("titleVisible", false);
            mobile.put("title", "");
            mobile.put("visible", true);
            mobile.putNull("layout");
            component.put("name", result.path("chartName").asText(""));
            component.put("reportDesc", "");
            component.put("reportType", result.path("reportType").asInt(1));
            component.put("showChartType", 1);
            component.put("title", "");
            component.put("titleVisible", false);
            component.put("needUpdate", true);
            component.put("worksheetId", appId);
        }
        return all;
    }

    private AppMetadata resolveAppMetadata(WebAuth webAuth, String appId, String worksheetId) throws Exception {
        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("appId", appId);
        body.put("getSection", true);
        JsonNode response = postWebJson(webAuth, webBaseUrl + GET_APP_ENDPOINT, webBaseUrl + "/", body);
        JsonNode data = response.path("data");
        String appName = data.path("name").asText(appId);

        List<String> worksheetIds = new ArrayList<>();
        for (JsonNode section : asArray(data.path("sections"))) {
            for (JsonNode worksheet : asArray(section.path("workSheetInfo"))) {
                if (worksheet.path("type").asInt(-1) != 0) {
                    continue;
                }
                String resolvedWorksheetId = worksheet.path("workSheetId").asText("");
                if (resolvedWorksheetId.isBlank()) {
                    continue;
                }
                if (!worksheetId.isBlank() && !worksheetId.equals(resolvedWorksheetId)) {
                    continue;
                }
                worksheetIds.add(resolvedWorksheetId);
            }
        }
        if (worksheetIds.isEmpty()) {
            throw new IllegalStateException("No worksheet found for appId=" + appId + " worksheetId=" + worksheetId);
        }
        return new AppMetadata(appName.isBlank() ? appId : appName, worksheetIds);
    }

    private JsonNode postReportJson(WebAuth webAuth, String url, String appId, String pageId, JsonNode body) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("AccountId", webAuth.accountId())
                .header("Authorization", webAuth.authorization())
                .header("Cookie", webAuth.cookie())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", webBaseUrl);
        if (!pageId.isBlank()) {
            requestBuilder.header("Referer", webBaseUrl + "/app/" + appId + "/" + pageId);
        }
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Jacksons.mapper().readTree(response.body());
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

    private ObjectNode compactObject(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : Jacksons.mapper().createObjectNode();
    }

    private String nowTs() {
        return DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
    }

    private record WebAuth(String accountId, String authorization, String cookie) {
    }

    private record AppMetadata(String appName, List<String> worksheetIds) {
    }
}
