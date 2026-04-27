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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.hap.automaker.ai.AiJsonParser;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.model.WebAuthConfig;

public final class ViewPipelineService implements ViewPipelineRunner {

    private static final String DEFAULT_API_BASE_URL = "https://api.mingdao.com";
    private static final String DEFAULT_WEB_BASE_URL = "https://www.mingdao.com";
    private static final String APP_INFO_ENDPOINT = "/v3/app";
    private static final String GET_CONTROLS_ENDPOINT = "/api/Worksheet/GetWorksheetControls";
    private static final String GET_WORKSHEET_VIEWS_ENDPOINT = "/api/Worksheet/GetWorksheetViews";
    private static final String SAVE_VIEW_ENDPOINT = "/api/Worksheet/SaveWorksheetView";
    private static final Pattern PY_AUTH_PATTERN = Pattern.compile("^(ACCOUNT_ID|AUTHORIZATION|COOKIE)\\s*=\\s*\"(.*)\"\\s*$");
    private static final int MAX_VIEWS_PER_WORKSHEET = 7;

    private final AiTextClient aiClient;
    private final AiJsonParser aiJsonParser;
    private final HttpClient httpClient;
    private final String apiBaseUrl;
    private final String webBaseUrl;
    private final int viewConcurrency;

    public ViewPipelineService() {
        this(
                new HttpAiTextClient(),
                new AiJsonParser(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                DEFAULT_API_BASE_URL,
                DEFAULT_WEB_BASE_URL,
                8);
    }

    public ViewPipelineService(String apiBaseUrl, String webBaseUrl) {
        this(
                new HttpAiTextClient(),
                new AiJsonParser(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                apiBaseUrl,
                webBaseUrl,
                8);
    }

    ViewPipelineService(AiTextClient aiClient, String apiBaseUrl, String webBaseUrl) {
        this(
                aiClient,
                new AiJsonParser(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                apiBaseUrl,
                webBaseUrl,
                8);
    }

    ViewPipelineService(
            AiTextClient aiClient,
            AiJsonParser aiJsonParser,
            HttpClient httpClient,
            String apiBaseUrl,
            String webBaseUrl,
            int viewConcurrency) {
        this.aiClient = aiClient;
        this.aiJsonParser = aiJsonParser;
        this.httpClient = httpClient;
        this.apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? DEFAULT_API_BASE_URL : apiBaseUrl;
        this.webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? DEFAULT_WEB_BASE_URL : webBaseUrl;
        this.viewConcurrency = Math.max(1, viewConcurrency);
    }

    @Override
    public ViewPipelineResult run(Path repoRoot, Path appAuthJson, Path outputJson) throws Exception {
        AppAuthorization appAuth = loadAppAuthorization(appAuthJson);
        WebAuth webAuth = loadWebAuth(repoRoot);
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(new ConfigPaths(repoRoot).aiAuth().toFile(), AiAuthConfig.class);
        this.cachedWebAuth = webAuth;
        List<WorksheetInfo> worksheets = fetchWorksheets(appAuth);
        List<String> worksheetNames = worksheets.stream().map(WorksheetInfo::worksheetName).toList();

        long startedAt = System.nanoTime();
        ArrayNode worksheetResults = Jacksons.mapper().createArrayNode();
        for (WorksheetInfo worksheet : worksheets) {
            worksheetResults.add(processWorksheet(repoRoot, appAuth, webAuth, aiAuth, worksheet, worksheetNames));
        }

        ObjectNode summary = Jacksons.mapper().createObjectNode();
        summary.put("timestamp", nowTs());
        summary.put("total_elapsed_s", roundSeconds(startedAt));
        summary.put("dry_run", false);
        summary.set("worksheets", worksheetResults);

        Files.createDirectories(outputJson.getParent());
        Jacksons.mapper().writeValue(outputJson.toFile(), summary);
        return new ViewPipelineResult(outputJson, summary);
    }

    public JsonNode addSingleView(
            Path repoRoot,
            String appId,
            String worksheetId,
            String viewName,
            Integer viewType,
            String viewControl,
            String description,
            List<String> displayControls,
            boolean execute) throws Exception {
        WebAuth webAuth = loadWebAuth(repoRoot);
        this.cachedWebAuth = webAuth;
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(new ConfigPaths(repoRoot).aiAuth().toFile(), AiAuthConfig.class);

        JsonNode controls = fetchControls(webAuth, worksheetId);
        ArrayNode rawFields = asArray(resolveControlArray(controls));
        ArrayNode simplifiedFields = simplifyFields(rawFields);
        String worksheetName = firstNonBlank(
                controls.path("data").path("data").path("worksheetName").asText(""),
                controls.path("data").path("worksheetName").asText(""),
                worksheetId);
        WorksheetInfo worksheet = new WorksheetInfo(appId, worksheetId, worksheetName, rawFields, simplifiedFields);

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("worksheetId", worksheetId);
        result.put("worksheetName", worksheetName);

        JsonNode recommendationView = null;
        JsonNode config;
        if (viewType == null || viewName == null || viewName.isBlank()) {
            JsonNode recommendation = recommendViews(appId, "", worksheet, List.of(), aiAuth);
            result.set("recommended", recommendation);
            ArrayNode views = asArray(recommendation.path("views"));
            if (views.isEmpty()) {
                throw new IllegalStateException("AI did not return any view recommendations");
            }
            recommendationView = views.get(0);
            config = configureSingleView(worksheet, recommendationView, aiAuth);
        } else {
            ObjectNode manualConfig = Jacksons.mapper().createObjectNode();
            manualConfig.put("name", viewName);
            manualConfig.put("viewType", viewType);
            manualConfig.put("viewControl", viewControl == null ? "" : viewControl);
            ArrayNode displayControlsNode = manualConfig.putArray("displayControls");
            if (displayControls != null) {
                for (String control : displayControls) {
                    if (control != null && !control.isBlank()) {
                        displayControlsNode.add(control);
                    }
                }
            }
            manualConfig.set("advancedSetting", Jacksons.mapper().createObjectNode());
            manualConfig.set("postCreateUpdates", Jacksons.mapper().createArrayNode());
            config = validateConfig(manualConfig, worksheet.simplifiedFields());
        }

        result.set("view", config);
        if (recommendationView != null) {
            result.set("selectedRecommendation", recommendationView.deepCopy());
        }

        if (!execute) {
            result.put("status", "plan_only");
            return result;
        }

        JsonNode created = createSingleView(webAuth, new AppAuthorization(appId, "", "", appId, repoRoot), worksheet, config);
        result.put("status", created.path("success").asBoolean(false) ? "success" : "failed");
        result.set("view", created);
        return result;
    }

    public JsonNode modifySingleView(
            Path repoRoot,
            String appId,
            String worksheetId,
            String viewId,
            String description,
            boolean execute) throws Exception {
        WebAuth webAuth = loadWebAuth(repoRoot);
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(new ConfigPaths(repoRoot).aiAuth().toFile(), AiAuthConfig.class);
        AppAuthorization appAuth = resolveAppAuthorization(repoRoot, appId);
        JsonNode worksheetDetail = fetchWorksheetDetail(appAuth, worksheetId);
        ArrayNode rawFields = asArray(worksheetDetail.path("controls"));
        ArrayNode simplifiedFields = simplifyFields(rawFields);
        String worksheetName = firstNonBlank(
                worksheetDetail.path("name").asText(""),
                worksheetDetail.path("worksheetName").asText(""),
                worksheetId);
        JsonNode currentView = findViewById(asArray(worksheetDetail.path("views")), viewId);
        if (currentView == null) {
            throw new IllegalArgumentException("View not found: " + viewId);
        }
        if (!hasFullViewConfig(currentView)) {
            JsonNode fetchedView = findViewById(fetchWorksheetViews(webAuth, worksheetId, appId), viewId);
            if (fetchedView != null) {
                currentView = fetchedView;
            }
        }
        boolean currentViewComplete = hasFullViewConfig(currentView);

        ObjectNode plan = planViewModification(worksheetName, simplifiedFields, currentView, description, aiAuth);
        ObjectNode payload = buildModifyPayload(appId, worksheetId, viewId, currentView, plan);

        Path incrementalDir = repoRoot.resolve("data").resolve("outputs").resolve("incremental");
        Files.createDirectories(incrementalDir);
        Path planFile = incrementalDir.resolve("modify_view_" + worksheetId + "_" + viewId + "_" + nowTs() + ".json");
        ObjectNode audit = Jacksons.mapper().createObjectNode();
        audit.set("currentView", currentView.deepCopy());
        audit.set("plan", plan.deepCopy());
        audit.set("payload", payload.deepCopy());
        Jacksons.mapper().writeValue(planFile.toFile(), audit);

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("worksheetId", worksheetId);
        result.put("worksheetName", worksheetName);
        result.put("viewId", viewId);
        result.put("planFile", planFile.toAbsolutePath().normalize().toString());
        result.put("currentViewComplete", currentViewComplete);
        result.set("currentView", currentView.deepCopy());
        result.set("plan", plan);
        result.set("payload", payload);

        if (!execute) {
            if (!currentViewComplete) {
                result.put("warning", "Current view payload is incomplete; execute mode is blocked until richer view detail is available");
            }
            result.put("status", "plan_only");
            return result;
        }

        if (!currentViewComplete) {
            throw new IllegalStateException("Current view payload is incomplete; use --no-execute until richer view detail endpoint is wired");
        }

        JsonNode saveResponse = saveView(webAuth, appId, worksheetId, viewId, payload);
        boolean success = saveResponse.path("state").asInt(0) == 1;
        result.put("status", success ? "success" : "failed");
        result.set("saveResponse", saveResponse);
        return result;
    }

    private ObjectNode processWorksheet(
            Path repoRoot,
            AppAuthorization appAuth,
            WebAuth webAuth,
            AiAuthConfig aiAuth,
            WorksheetInfo worksheet,
            List<String> allWorksheetNames) throws Exception {
        long startedAt = System.nanoTime();
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("worksheetId", worksheet.worksheetId());
        result.put("worksheetName", worksheet.worksheetName());

        List<String> otherWorksheetNames = allWorksheetNames.stream()
                .filter(name -> !name.equals(worksheet.worksheetName()))
                .toList();

        Path tempDir = repoRoot.resolve("data").resolve("outputs").resolve("java_phase1").resolve("view_temp")
                .resolve(worksheet.worksheetId());
        Files.createDirectories(tempDir);
        Path simplifiedFieldsJson = tempDir.resolve("fields.json");
        Jacksons.mapper().writeValue(simplifiedFieldsJson.toFile(), worksheet.simplifiedFields());

        JsonNode recommendation = recommendViews(
                appAuth.appName(),
                "",
                worksheet,
                otherWorksheetNames,
                aiAuth);
        result.set("recommendation", recommendation);

        ArrayNode configs = Jacksons.mapper().createArrayNode();
        ArrayNode creates = Jacksons.mapper().createArrayNode();
        result.set("configs", configs);
        result.set("creates", creates);

        ArrayNode views = asArray(recommendation.path("views"));
        List<JsonNode> configResults = configureViews(worksheet, views, aiAuth);
        for (JsonNode config : configResults) {
            configs.add(config);
        }

        List<JsonNode> createResults = createViews(webAuth, appAuth, worksheet, configResults);
        for (JsonNode createResult : createResults) {
            creates.add(createResult);
        }

        ObjectNode stats = result.putObject("stats");
        stats.put("elapsed_s", roundSeconds(startedAt));
        return result;
    }

    private List<JsonNode> configureViews(
            WorksheetInfo worksheet,
            ArrayNode views,
            AiAuthConfig aiAuth) throws Exception {
        List<JsonNode> configs = new ArrayList<>();
        if (views.isEmpty()) {
            return configs;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(viewConcurrency, views.size()));
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < views.size(); i++) {
                final int index = i;
                final JsonNode view = views.get(i);
                futures.add(executor.submit(new Callable<>() {
                    @Override
                    public JsonNode call() throws Exception {
                        return configureSingleView(worksheet, view, aiAuth);
                    }
                }));
            }
            for (Future<JsonNode> future : futures) {
                configs.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }
        return configs;
    }

    private JsonNode recommendViews(
            String appName,
            String appBackground,
            WorksheetInfo worksheet,
            List<String> otherWorksheetNames,
            AiAuthConfig aiAuth) throws Exception {
        ObjectNode available = getAvailableViewTypes(worksheet.simplifiedFields());
        String raw = aiClient.generateJson(
                buildRecommendPrompt(appName, appBackground, worksheet, otherWorksheetNames, available),
                aiAuth);
        JsonNode parsed = aiJsonParser.parse(raw);
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("View recommendation AI response must be a JSON object");
        }
        ArrayNode filteredViews = validateRecommendation(objectNode, available);
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("worksheetId", worksheet.worksheetId());
        result.put("worksheetName", worksheet.worksheetName());
        ArrayNode availableTypes = result.putArray("available_view_types");
        available.fields().forEachRemaining(entry -> availableTypes.add(Integer.parseInt(entry.getKey())));
        result.set("views", filteredViews);
        ObjectNode stats = result.putObject("stats");
        stats.put("elapsed_s", 0);
        stats.put("ai_called", true);
        return result;
    }

    private JsonNode configureSingleView(
            WorksheetInfo worksheet,
            JsonNode recommendation,
            AiAuthConfig aiAuth) throws Exception {
        String raw = aiClient.generateJson(
                buildConfigPrompt(recommendation, worksheet.worksheetName(), worksheet.simplifiedFields()),
                aiAuth);
        JsonNode parsed = aiJsonParser.parse(raw);
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("View config AI response must be a JSON object");
        }
        return validateConfig(objectNode, worksheet.simplifiedFields());
    }

    private ObjectNode getAvailableViewTypes(ArrayNode fields) {
        ObjectNode available = Jacksons.mapper().createObjectNode();
        JsonNode selectField = firstField(fields, Set.of("9", "11"), true);
        JsonNode dateField = firstField(fields, Set.of("15", "16"), false);
        JsonNode secondDateField = secondField(fields, Set.of("15", "16"));
        JsonNode memberField = firstField(fields, Set.of("26"), false);
        JsonNode imageAttachment = firstImageAttachment(fields);
        JsonNode locationField = firstField(fields, Set.of("40"), false);

        if (selectField != null) {
            available.set("0", fieldRole("select", selectField));
            available.set("1", fieldRole("select", selectField));
        }
        if (imageAttachment != null) {
            available.set("3", fieldRole("image_attachment", imageAttachment));
        }
        if (dateField != null) {
            available.set("4", fieldRole("date", dateField));
        }
        if (dateField != null && secondDateField != null) {
            ObjectNode gantt = Jacksons.mapper().createObjectNode();
            gantt.set("begin_date", slimField(dateField));
            gantt.set("end_date", slimField(secondDateField));
            available.set("5", gantt);
        }
        if (memberField != null && dateField != null && secondDateField != null) {
            ObjectNode resource = Jacksons.mapper().createObjectNode();
            resource.set("member", slimField(memberField));
            resource.set("begin_date", slimField(dateField));
            resource.set("end_date", slimField(secondDateField));
            available.set("7", resource);
        }
        if (locationField != null) {
            available.set("8", fieldRole("location", locationField));
        }
        return available;
    }

    private ObjectNode fieldRole(String role, JsonNode field) {
        ObjectNode node = Jacksons.mapper().createObjectNode();
        node.set(role, slimField(field));
        return node;
    }

    private ObjectNode slimField(JsonNode field) {
        ObjectNode node = Jacksons.mapper().createObjectNode();
        node.put("id", field.path("id").asText(""));
        node.put("name", field.path("name").asText(""));
        node.put("type", field.path("type").asText(""));
        return node;
    }

    private JsonNode firstField(ArrayNode fields, Set<String> allowedTypes, boolean requireOptions) {
        for (JsonNode field : fields) {
            if (!allowedTypes.contains(field.path("type").asText(""))) {
                continue;
            }
            if (requireOptions && asArray(field.path("options")).isEmpty()) {
                continue;
            }
            return field;
        }
        return null;
    }

    private JsonNode secondField(ArrayNode fields, Set<String> allowedTypes) {
        boolean seen = false;
        for (JsonNode field : fields) {
            if (!allowedTypes.contains(field.path("type").asText(""))) {
                continue;
            }
            if (!seen) {
                seen = true;
                continue;
            }
            return field;
        }
        return null;
    }

    private JsonNode firstImageAttachment(ArrayNode fields) {
        for (JsonNode field : fields) {
            if (!"14".equals(field.path("type").asText(""))) {
                continue;
            }
            String name = field.path("name").asText("").toLowerCase();
            if (name.contains("图") || name.contains("image") || name.contains("cover")) {
                return field;
            }
        }
        return null;
    }

    private String buildRecommendPrompt(
            String appName,
            String appBackground,
            WorksheetInfo worksheet,
            List<String> otherWorksheetNames,
            ObjectNode available) throws Exception {
        StringBuilder availableLines = new StringBuilder();
        available.fields().forEachRemaining(entry -> {
            availableLines.append("- ").append(entry.getKey()).append(": ").append(entry.getValue().toString()).append("\n");
        });

        StringBuilder fieldLines = new StringBuilder();
        for (JsonNode field : worksheet.simplifiedFields()) {
            fieldLines.append("- ")
                    .append(field.path("id").asText(""))
                    .append(" | type=").append(field.path("type").asText(""))
                    .append(" | ").append(field.path("name").asText(""));
            ArrayNode options = asArray(field.path("options"));
            if (!options.isEmpty()) {
                List<String> values = new ArrayList<>();
                for (JsonNode option : options) {
                    values.add(option.path("value").asText(""));
                }
                fieldLines.append(" | 选项: ").append(String.join(", ", values));
            }
            fieldLines.append("\n");
        }

        return """
                你是企业应用视图设计专家。请根据业务背景和字段，为工作表推荐最有业务价值的视图。

                应用名称：%s
                业务背景：%s
                当前工作表：%s
                其他工作表：%s

                字段列表：
                %s

                可选视图类型：
                %s

                规则：
                1) 只推荐有明确业务价值的视图
                2) 每种 viewType 最多一个
                3) 最多 %d 个视图
                4) 只输出 JSON

                输出格式：
                {
                  "views": [
                    {
                      "viewType": 4,
                      "name": "视图名称",
                      "reason": "推荐原因"
                    }
                  ]
                }
                """.formatted(
                appName,
                appBackground,
                worksheet.worksheetName(),
                String.join(", ", otherWorksheetNames),
                fieldLines.toString(),
                availableLines.toString(),
                MAX_VIEWS_PER_WORKSHEET);
    }

    private ArrayNode validateRecommendation(ObjectNode raw, ObjectNode available) {
        ArrayNode views = asArray(raw.path("views"));
        ArrayNode filtered = Jacksons.mapper().createArrayNode();
        Set<Integer> seen = new java.util.HashSet<>();
        for (JsonNode view : views) {
            int viewType = view.path("viewType").asInt(-1);
            if (viewType < 0 || !available.has(String.valueOf(viewType)) || !seen.add(viewType)) {
                continue;
            }
            filtered.add(view.deepCopy());
            if (filtered.size() >= MAX_VIEWS_PER_WORKSHEET) {
                break;
            }
        }
        return filtered;
    }

    private String buildConfigPrompt(JsonNode recommendation, String worksheetName, ArrayNode fields) {
        StringBuilder fieldLines = new StringBuilder();
        for (JsonNode field : fields) {
            fieldLines.append("- ")
                    .append(field.path("id").asText(""))
                    .append(" | type=").append(field.path("type").asText(""))
                    .append(" | ").append(field.path("name").asText(""))
                    .append("\n");
        }
        return """
                你是明道云视图配置专家。请为以下视图生成完整配置，只输出 JSON。

                工作表：%s
                视图推荐：
                %s

                字段列表：
                %s

                规则：
                1) displayControls 选择 5-8 个重要字段 ID
                2) 看板/资源/地图等需要 viewControl 时必须使用真实字段 ID
                3) postCreateUpdates 中的字段引用必须使用真实字段 ID
                4) enablerules 默认 "1"
                5) 只输出 JSON
                """.formatted(
                worksheetName,
                recommendation.toString(),
                fieldLines.toString());
    }

    private JsonNode validateConfig(ObjectNode config, ArrayNode fields) {
        Set<String> fieldIds = new java.util.HashSet<>();
        for (JsonNode field : fields) {
            fieldIds.add(field.path("id").asText(""));
        }
        int viewType = config.path("viewType").asInt(0);
        String viewControl = config.path("viewControl").asText("");
        if ((viewType == 1 || viewType == 7 || viewType == 8) && !viewControl.isBlank() && !fieldIds.contains(viewControl)) {
            throw new IllegalArgumentException("Invalid viewControl field reference: " + viewControl);
        }
        if ("Collaborator".equals(config.path("type").asText(""))) {
            config.put("required", false);
        }
        return config;
    }

    private boolean hasFullViewConfig(JsonNode currentView) {
        return currentView.path("displayControls").isArray()
                || currentView.path("advancedSetting").isObject()
                || currentView.path("filters").isArray()
                || currentView.has("sortCid")
                || currentView.has("sortType");
    }

    private ObjectNode planViewModification(
            String worksheetName,
            ArrayNode fields,
            JsonNode currentView,
            String description,
            AiAuthConfig aiAuth) throws Exception {
        String raw = aiClient.generateJson(
                buildModifyViewPrompt(worksheetName, fields, currentView, description),
                aiAuth);
        JsonNode parsed = aiJsonParser.parse(raw);
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("Modify view AI response must be a JSON object");
        }
        return sanitizeModifyPlan(objectNode, fields);
    }

    private String buildModifyViewPrompt(
            String worksheetName,
            ArrayNode fields,
            JsonNode currentView,
            String description) throws Exception {
        StringBuilder fieldLines = new StringBuilder();
        for (JsonNode field : fields) {
            fieldLines.append("- ")
                    .append(field.path("id").asText(""))
                    .append(" | type=").append(field.path("type").asText(""))
                    .append(" | ").append(field.path("name").asText(""))
                    .append("\n");
        }
        return """
                浣犳槸鏄庨亾浜戣鍥鹃厤缃笓瀹躲€傝鏍规嵁鐢ㄦ埛闇€姹傦紝淇敼宸叉湁瑙嗗浘閰嶇疆锛屽彧杈撳嚭 JSON銆?
                宸ヤ綔琛細%s
                褰撳墠瑙嗗浘锛?                %s

                瀛楁鍒楄〃锛?                %s

                鐢ㄦ埛闇€姹傦細%s

                杈撳嚭鏍煎紡锛?                {
                  "displayControls": ["瀛楁ID1", "瀛楁ID2"],
                  "advancedSetting": {},
                  "filters": [],
                  "sortCid": "",
                  "sortType": 0,
                  "reason": "淇敼鐞嗙敱"
                }

                瑙勫垯锛?                1) displayControls 鍙兘浣跨敤鐪熷疄瀛楁 ID
                2) filters 鍙兘浣跨敤鐪熷疄瀛楁 ID
                3) sortCid 鍙兘浣跨敤鐪熷疄瀛楁 ID
                4) 鍙緭鍑?JSON
                """.formatted(
                worksheetName,
                compactJson(currentView),
                fieldLines.toString(),
                description == null ? "" : description);
    }

    private ObjectNode sanitizeModifyPlan(ObjectNode plan, ArrayNode fields) {
        Set<String> fieldIds = new java.util.HashSet<>();
        for (JsonNode field : fields) {
            fieldIds.add(field.path("id").asText(""));
        }
        JsonNode displayControls = plan.path("displayControls");
        if (displayControls.isArray()) {
            ArrayNode sanitized = Jacksons.mapper().createArrayNode();
            for (JsonNode displayControl : displayControls) {
                String fieldId = displayControl.asText("");
                if (fieldIds.contains(fieldId)) {
                    sanitized.add(fieldId);
                }
            }
            plan.set("displayControls", sanitized);
        }
        JsonNode filters = plan.path("filters");
        if (filters.isArray()) {
            ArrayNode sanitized = Jacksons.mapper().createArrayNode();
            for (JsonNode filter : filters) {
                String controlId = filter.path("controlId").asText("");
                if (fieldIds.contains(controlId)) {
                    sanitized.add(filter.deepCopy());
                }
            }
            plan.set("filters", sanitized);
        }
        String sortCid = plan.path("sortCid").asText("");
        if (!sortCid.isBlank() && !fieldIds.contains(sortCid)) {
            plan.put("sortCid", "");
        }
        return plan;
    }

    private ObjectNode buildModifyPayload(
            String appId,
            String worksheetId,
            String viewId,
            JsonNode currentView,
            ObjectNode plan) throws Exception {
        String viewType = String.valueOf(currentView.path("viewType").asInt(currentView.path("type").asInt(0)));
        ArrayNode displayControls = plan.path("displayControls").isArray()
                ? copyArray(plan.path("displayControls"))
                : copyArray(currentView.path("displayControls"));
        JsonNode filters = plan.path("filters").isArray()
                ? plan.path("filters").deepCopy()
                : copyArray(currentView.path("filters"));

        ObjectNode mergedAdvancedSetting = currentView.path("advancedSetting").isObject()
                ? ((ObjectNode) currentView.path("advancedSetting").deepCopy())
                : Jacksons.mapper().createObjectNode();
        if (plan.path("advancedSetting").isObject()) {
            plan.path("advancedSetting").fields().forEachRemaining(entry -> mergedAdvancedSetting.set(entry.getKey(), entry.getValue()));
        }
        ObjectNode normalizedAdvancedSetting = normalizeAdvancedSetting(viewType, mergedAdvancedSetting);
        if (normalizedAdvancedSetting.has("groupView")) {
            try {
                JsonNode groupView = Jacksons.mapper().readTree(normalizedAdvancedSetting.path("groupView").asText(""));
                if (groupView.isObject() && groupView.path("viewId").asText("").isBlank()) {
                    ((ObjectNode) groupView).put("viewId", viewId);
                    normalizedAdvancedSetting.put("groupView", compactJson(groupView));
                }
            } catch (Exception ignored) {
            }
        }

        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("viewId", viewId);
        payload.put("appId", appId);
        payload.put("worksheetId", worksheetId);
        payload.put("viewType", viewType);
        payload.put("name", currentView.path("name").asText(""));
        payload.set("displayControls", displayControls);
        payload.put("sortType", plan.has("sortType") ? plan.path("sortType").asInt(0) : currentView.path("sortType").asInt(0));
        payload.put("coverType", currentView.path("coverType").asInt(0));
        payload.set("controls", copyArray(currentView.path("controls")));
        payload.set("filters", filters);
        payload.put("sortCid", plan.has("sortCid") ? plan.path("sortCid").asText("") : currentView.path("sortCid").asText(""));
        payload.put("showControlName", currentView.path("showControlName").asBoolean(true));
        payload.set("advancedSetting", normalizedAdvancedSetting);

        String viewControl = currentView.path("viewControl").asText("");
        if (!viewControl.isBlank()) {
            payload.put("viewControl", viewControl);
        }
        String coverCid = currentView.path("coverCid").asText("");
        if (!coverCid.isBlank()) {
            payload.put("coverCid", coverCid);
        }
        return payload;
    }

    private List<JsonNode> createViews(
            WebAuth webAuth,
            AppAuthorization appAuth,
            WorksheetInfo worksheet,
            List<JsonNode> configs) throws Exception {
        List<JsonNode> results = new ArrayList<>();
        if (configs.isEmpty()) {
            return results;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(viewConcurrency, configs.size()));
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (JsonNode config : configs) {
                futures.add(executor.submit(new Callable<>() {
                    @Override
                    public JsonNode call() throws Exception {
                        return createSingleView(webAuth, appAuth, worksheet, config);
                    }
                }));
            }
            for (Future<JsonNode> future : futures) {
                results.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }
        return results;
    }

    private JsonNode createSingleView(
            WebAuth webAuth,
            AppAuthorization appAuth,
            WorksheetInfo worksheet,
            JsonNode viewConfig) throws Exception {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("name", viewConfig.path("name").asText(""));
        result.put("viewType", String.valueOf(viewConfig.path("viewType").asInt(0)));
        result.put("viewId", "");
        result.put("success", false);
        result.put("error", "");

        JsonNode createPayload = buildCreatePayload(appAuth.appId(), worksheet.worksheetId(), viewConfig);
        JsonNode createResponse = saveView(webAuth, appAuth.appId(), worksheet.worksheetId(), "", createPayload);
        result.set("createResponse", createResponse);
        String viewId = createResponse.path("data").path("viewId").asText("");
        result.put("viewId", viewId);
        ArrayNode updates = result.putArray("updates");

        boolean success = createResponse.path("state").asInt(0) == 1 && !viewId.isBlank();
        if (success) {
            for (JsonNode updateNode : asArray(viewConfig.path("postCreateUpdates"))) {
                JsonNode updatePayload = buildUpdatePayload(appAuth.appId(), worksheet.worksheetId(), viewId, updateNode);
                JsonNode updateResponse = saveView(webAuth, appAuth.appId(), worksheet.worksheetId(), viewId, updatePayload);
                ObjectNode updateResult = updates.addObject();
                updateResult.set("payload", updatePayload);
                updateResult.set("response", updateResponse);
                if (updateResponse.path("state").asInt(0) != 1) {
                    success = false;
                }
            }
        } else {
            result.put("error", "Create view failed");
        }

        result.put("success", success);
        return result;
    }

    private JsonNode buildCreatePayload(String appId, String worksheetId, JsonNode viewConfig) {
        String viewType = String.valueOf(viewConfig.path("viewType").asInt(0));
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("viewId", "");
        payload.put("appId", appId);
        payload.put("worksheetId", worksheetId);
        payload.put("viewType", viewType);
        payload.put("name", viewConfig.path("name").asText("视图_" + viewType));
        payload.set("displayControls", copyArray(viewConfig.path("displayControls")));
        payload.put("sortType", 0);
        payload.put("coverType", 0);
        payload.putArray("controls");
        payload.putArray("filters");
        payload.put("sortCid", "");
        payload.put("showControlName", true);
        payload.set("advancedSetting", normalizeAdvancedSetting(viewType, viewConfig.path("advancedSetting")));

        String coverCid = viewConfig.path("coverCid").asText("");
        if (!coverCid.isBlank()) {
            payload.put("coverCid", coverCid);
        }
        String viewControl = viewConfig.path("viewControl").asText("");
        if (!viewControl.isBlank()) {
            payload.put("viewControl", viewControl);
        }
        return payload;
    }

    private JsonNode buildUpdatePayload(String appId, String worksheetId, String viewId, JsonNode updateNode) throws Exception {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("appId", appId);
        payload.put("worksheetId", worksheetId);
        payload.put("viewId", viewId);
        Iterator<Map.Entry<String, JsonNode>> fields = updateNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if ("advancedSetting".equals(key)) {
                ObjectNode normalized = normalizeAdvancedSetting("", value);
                if (normalized.has("calendarcids")) {
                    String fixed = normalizeCalendarcids(normalized.path("calendarcids").asText(""));
                    if (fixed.isBlank()) {
                        normalized.remove("calendarcids");
                    } else {
                        normalized.put("calendarcids", fixed);
                    }
                }
                if (normalized.has("groupView")) {
                    try {
                        JsonNode groupView = Jacksons.mapper().readTree(normalized.path("groupView").asText(""));
                        if (groupView.isObject() && groupView.path("viewId").asText("").isBlank()) {
                            ((ObjectNode) groupView).put("viewId", viewId);
                            normalized.put("groupView", compactJson(groupView));
                        }
                    } catch (Exception ignored) {
                    }
                }
                payload.set("advancedSetting", normalized);
            } else if (value.isArray()) {
                payload.set(key, value.deepCopy());
            } else if (value.isBoolean()) {
                payload.put(key, value.asBoolean());
            } else if (value.isNumber()) {
                payload.put(key, value.asInt());
            } else if (value.isTextual()) {
                payload.put(key, value.asText());
            } else {
                payload.set(key, value.deepCopy());
            }
        }
        if (payload.has("advancedSetting")) {
            JsonNode advancedSetting = payload.path("advancedSetting");
            for (String key : List.of("begindate", "enddate")) {
                String value = advancedSetting.path(key).asText("");
                if (!value.isBlank()) {
                    payload.put(key, value);
                }
            }
        }
        return payload;
    }

    private ObjectNode normalizeAdvancedSetting(String viewType, JsonNode value) {
        ObjectNode raw = value != null && value.isObject()
                ? (ObjectNode) value.deepCopy()
                : Jacksons.mapper().createObjectNode();
        if (!raw.has("enablerules")) {
            raw.put("enablerules", "1");
        }
        if (!raw.has("coverstyle")) {
            if ("3".equals(viewType)) {
                raw.put("coverstyle", "{\"position\":\"2\"}");
            } else if (!"2".equals(viewType) && !"5".equals(viewType)) {
                raw.put("coverstyle", "{\"position\":\"1\",\"style\":3}");
            }
        }

        ObjectNode normalized = Jacksons.mapper().createObjectNode();
        raw.fields().forEachRemaining(entry -> {
            JsonNode fieldValue = entry.getValue();
            if (fieldValue.isObject() || fieldValue.isArray()) {
                try {
                    normalized.put(entry.getKey(), compactJson(fieldValue));
                } catch (Exception e) {
                    normalized.put(entry.getKey(), fieldValue.toString());
                }
            } else if (fieldValue.isBoolean()) {
                normalized.put(entry.getKey(), fieldValue.asBoolean() ? "1" : "0");
            } else if (fieldValue.isNull()) {
                normalized.put(entry.getKey(), "");
            } else {
                normalized.put(entry.getKey(), fieldValue.asText(""));
            }
        });
        return normalized;
    }

    private String normalizeCalendarcids(String value) throws Exception {
        if (value == null || value.isBlank()) {
            return "";
        }
        JsonNode raw = Jacksons.mapper().readTree(value);
        ArrayNode items = raw.isArray() ? (ArrayNode) raw : Jacksons.mapper().createArrayNode().add(raw);
        ArrayNode normalized = Jacksons.mapper().createArrayNode();
        for (JsonNode item : items) {
            if (item.isTextual()) {
                ObjectNode one = normalized.addObject();
                one.put("begin", item.asText(""));
                one.put("end", "");
                continue;
            }
            if (!item.isObject()) {
                continue;
            }
            String begin = firstNonBlank(
                    item.path("begin").asText(""),
                    item.path("begindate").asText(""),
                    item.path("start").asText(""),
                    item.path("cid").asText(""));
            if (begin.isBlank()) {
                continue;
            }
            ObjectNode one = normalized.addObject();
            one.put("begin", begin);
            one.put("end", firstNonBlank(
                    item.path("end").asText(""),
                    item.path("enddate").asText(""),
                    item.path("endCid").asText("")));
        }
        return normalized.isEmpty()
                ? ""
                : compactJson(normalized);
    }

    private JsonNode saveView(
            WebAuth webAuth,
            String appId,
            String worksheetId,
            String viewId,
            JsonNode payload) throws Exception {
        String referer = "app/" + appId + "/" + worksheetId;
        if (!viewId.isBlank()) {
            referer = referer + "/" + viewId;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(webBaseUrl + SAVE_VIEW_ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("AccountId", webAuth.accountId())
                .header("Authorization", webAuth.authorization())
                .header("Cookie", webAuth.cookie())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", webBaseUrl)
                .header("Referer", webBaseUrl + "/" + referer)
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Jacksons.mapper().readTree(response.body());
    }

    private JsonNode postWebJson(WebAuth webAuth, String url, String referer, JsonNode payload) throws Exception {
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

    private AppAuthorization loadAppAuthorization(Path appAuthJson) throws Exception {
        JsonNode root = Jacksons.mapper().readTree(appAuthJson.toFile());
        JsonNode row = root.path("data").path(0);
        return new AppAuthorization(
                row.path("appId").asText(""),
                row.path("appKey").asText(""),
                row.path("sign").asText(""),
                row.path("name").asText(""),
                appAuthJson);
    }

    private WebAuth loadWebAuth(Path repoRoot) throws Exception {
        Path webAuthJson = new ConfigPaths(repoRoot).webAuth();
        if (Files.exists(webAuthJson)) {
            WebAuthConfig config = Jacksons.mapper().readValue(webAuthJson.toFile(), WebAuthConfig.class);
            return new WebAuth(config.accountId(), config.authorization(), config.cookie());
        }
        Path authConfigPy = new ConfigPaths(repoRoot).authConfigPy();
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(authConfigPy, StandardCharsets.UTF_8)) {
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

    private List<WorksheetInfo> fetchWorksheets(AppAuthorization appAuth) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + APP_INFO_ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("HAP-Appkey", appAuth.appKey())
                .header("HAP-Sign", appAuth.sign())
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = Jacksons.mapper().readTree(response.body());
        ArrayNode sections = body.path("data").path("sections").isArray()
                ? (ArrayNode) body.path("data").path("sections")
                : Jacksons.mapper().createArrayNode();
        List<WorksheetInfo> worksheets = new ArrayList<>();
        for (JsonNode section : sections) {
            collectWorksheets(appAuth, section, worksheets);
        }
        return worksheets;
    }

    private void collectWorksheets(AppAuthorization appAuth, JsonNode section, List<WorksheetInfo> worksheets) throws Exception {
        for (JsonNode item : asArray(section.path("items"))) {
            if (item.path("type").asInt(-1) != 0) {
                continue;
            }
            String worksheetId = item.path("id").asText("");
            String worksheetName = item.path("name").asText("");
            JsonNode controls = fetchControls(loadCachedWebAuth(), worksheetId);
            ArrayNode rawFields = asArray(resolveControlArray(controls));
            ArrayNode simplifiedFields = simplifyFields(rawFields);
            worksheets.add(new WorksheetInfo(appAuth.appId(), worksheetId, worksheetName, rawFields, simplifiedFields));
        }
        for (JsonNode child : asArray(section.path("childSections"))) {
            collectWorksheets(appAuth, child, worksheets);
        }
    }

    private JsonNode fetchControls(WebAuth webAuth, String worksheetId) throws Exception {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("worksheetId", worksheetId);
        HttpRequest request = HttpRequest.newBuilder(URI.create(webBaseUrl + GET_CONTROLS_ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("AccountId", webAuth.accountId())
                .header("Authorization", webAuth.authorization())
                .header("Cookie", webAuth.cookie())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", webBaseUrl)
                .header("Referer", webBaseUrl + "/worksheet/field/edit?sourceId=" + worksheetId)
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Jacksons.mapper().readTree(response.body());
    }

    private AppAuthorization resolveAppAuthorization(Path repoRoot, String appId) throws Exception {
        Path authDir = repoRoot.resolve("data").resolve("outputs").resolve("app_authorizations");
        if (!Files.isDirectory(authDir)) {
            throw new IllegalStateException("App authorization directory not found: " + authDir);
        }
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.list(authDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(candidates::add);
        }
        for (Path candidate : candidates) {
            JsonNode root = Jacksons.mapper().readTree(candidate.toFile());
            for (JsonNode row : asArray(root.path("data"))) {
                if (!appId.equals(row.path("appId").asText(""))) {
                    continue;
                }
                String appKey = row.path("appKey").asText("");
                String sign = row.path("sign").asText("");
                if (!appKey.isBlank() && !sign.isBlank()) {
                    return new AppAuthorization(appId, appKey, sign, row.path("name").asText(appId), candidate);
                }
            }
        }
        throw new IllegalStateException("No app authorization found for appId=" + appId);
    }

    private JsonNode fetchWorksheetDetail(AppAuthorization appAuth, String worksheetId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/v3/app/worksheets/" + worksheetId))
                .timeout(Duration.ofSeconds(30))
                .header("HAP-Appkey", appAuth.appKey())
                .header("HAP-Sign", appAuth.sign())
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = Jacksons.mapper().readTree(response.body());
        if (!body.path("success").asBoolean(false)) {
            throw new IllegalStateException("Fetch worksheet detail failed: " + Jacksons.mapper().writeValueAsString(body));
        }
        return body.path("data");
    }

    private ArrayNode fetchWorksheetViews(WebAuth webAuth, String worksheetId, String appId) throws Exception {
        ObjectNode body = Jacksons.mapper().createObjectNode();
        body.put("worksheetId", worksheetId);
        body.put("appId", appId);
        JsonNode response = postWebJson(webAuth, webBaseUrl + GET_WORKSHEET_VIEWS_ENDPOINT, webBaseUrl + "/", body);
        return asArray(response.path("data"));
    }

    private JsonNode findViewById(ArrayNode views, String viewId) {
        for (JsonNode view : views) {
            String candidate = firstNonBlank(view.path("viewId").asText(""), view.path("id").asText(""));
            if (viewId.equals(candidate)) {
                return view;
            }
        }
        return null;
    }

    private JsonNode resolveControlArray(JsonNode payload) {
        JsonNode wrapped = payload.path("data");
        if (wrapped.isObject() && wrapped.path("data").isObject()) {
            return wrapped.path("data").path("controls");
        }
        return wrapped.path("controls");
    }

    private ArrayNode simplifyFields(ArrayNode rawFields) {
        ArrayNode simplified = Jacksons.mapper().createArrayNode();
        for (JsonNode field : rawFields) {
            ObjectNode item = simplified.addObject();
            item.put("id", firstNonBlank(field.path("id").asText(""), field.path("controlId").asText("")));
            item.put("name", firstNonBlank(field.path("name").asText(""), field.path("controlName").asText("")));
            item.put("type", field.path("type").asText(""));
            item.put("subType", field.path("subType").asInt(0));
            item.put("isTitle", field.path("isTitle").asBoolean(false));
            item.put("required", field.path("required").asBoolean(false));
            boolean isSystem = field.path("isSystemControl").asBoolean(false)
                    || field.path("attribute").asInt(0) == 1;
            item.put("isSystem", isSystem);
            ArrayNode options = item.putArray("options");
            for (JsonNode option : asArray(field.path("options"))) {
                if (option.path("isDeleted").asBoolean(false)) {
                    continue;
                }
                ObjectNode out = options.addObject();
                out.put("key", option.path("key").asText(""));
                out.put("value", option.path("value").asText(""));
            }
        }
        return simplified;
    }

    private ArrayNode copyArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node.deepCopy() : Jacksons.mapper().createArrayNode();
    }

    private ArrayNode asArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : Jacksons.mapper().createArrayNode();
    }

    private String nowTs() {
        return DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
    }

    private double roundSeconds(long startedAtNanos) {
        return Math.round((System.nanoTime() - startedAtNanos) / 1_000_000_000.0 * 10.0) / 10.0;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String compactJson(JsonNode value) throws Exception {
        return Jacksons.mapper().copy()
                .disable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(value);
    }

    private WebAuth cachedWebAuth;

    private WebAuth loadCachedWebAuth() {
        if (cachedWebAuth == null) {
            throw new IllegalStateException("Web auth not loaded");
        }
        return cachedWebAuth;
    }

    private record AppAuthorization(String appId, String appKey, String sign, String appName, Path authPath) {
    }

    private record WebAuth(String accountId, String authorization, String cookie) {
    }

    private record WorksheetInfo(
            String appId,
            String worksheetId,
            String worksheetName,
            ArrayNode rawFields,
            ArrayNode simplifiedFields) {
    }
}
