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
import java.util.UUID;
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

public final class AddFieldService {

    private static final String DEFAULT_WEB_BASE_URL = "https://www.mingdao.com";
    private static final String GET_CONTROLS_ENDPOINT = "/api/Worksheet/GetWorksheetControls";
    private static final String SAVE_CONTROLS_ENDPOINT = "/api/Worksheet/SaveWorksheetControls";
    private static final Pattern PY_AUTH_PATTERN = Pattern.compile("^(ACCOUNT_ID|AUTHORIZATION|COOKIE)\\s*=\\s*\"(.*)\"\\s*$");
    private static final Map<String, Integer> FIELD_TYPES = Map.ofEntries(
            Map.entry("Text", 2),
            Map.entry("Phone", 3),
            Map.entry("Email", 5),
            Map.entry("Number", 6),
            Map.entry("Money", 8),
            Map.entry("SingleSelect", 9),
            Map.entry("MultipleSelect", 10),
            Map.entry("Dropdown", 11),
            Map.entry("Attachment", 14),
            Map.entry("Date", 15),
            Map.entry("DateTime", 16),
            Map.entry("Area", 24),
            Map.entry("Collaborator", 26),
            Map.entry("Checkbox", 36),
            Map.entry("RichText", 41),
            Map.entry("Rating", 47));

    private final AiTextClient aiClient;
    private final AiJsonParser aiJsonParser;
    private final HttpClient httpClient;
    private final String webBaseUrl;

    public AddFieldService() {
        this(
                new HttpAiTextClient(),
                new AiJsonParser(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                DEFAULT_WEB_BASE_URL);
    }

    AddFieldService(AiTextClient aiClient, String webBaseUrl) {
        this(
                aiClient,
                new AiJsonParser(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                webBaseUrl);
    }

    AddFieldService(
            AiTextClient aiClient,
            AiJsonParser aiJsonParser,
            HttpClient httpClient,
            String webBaseUrl) {
        this.aiClient = aiClient;
        this.aiJsonParser = aiJsonParser;
        this.httpClient = httpClient;
        this.webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? DEFAULT_WEB_BASE_URL : webBaseUrl;
    }

    public JsonNode addField(
            Path repoRoot,
            String appId,
            String worksheetId,
            String fieldName,
            String fieldType,
            String fieldDescription,
            boolean required,
            List<String> optionValues,
            boolean execute) throws Exception {
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(new ConfigPaths(repoRoot).aiAuth().toFile(), AiAuthConfig.class);
        AppAuthorization authorization = resolveAppAuthorization(repoRoot, appId);
        WebAuth webAuth = loadWebAuth(repoRoot);
        WorksheetControlsState controlsState = fetchWorksheetControls(authorization, webAuth, worksheetId);

        String canonicalType = canonicalFieldType(fieldType);
        List<String> finalOptionValues = optionValues == null ? new ArrayList<>() : new ArrayList<>(optionValues);
        JsonNode recommended = Jacksons.mapper().createObjectNode();
        boolean finalRequired = required;

        boolean needsRecommendation = canonicalType.isBlank()
                || (requiresOptions(canonicalType) && finalOptionValues.isEmpty());
        if (needsRecommendation) {
            ObjectNode rec = recommendFieldType(
                    aiAuth,
                    controlsState.worksheetName(),
                    controlsState.controls(),
                    fieldName,
                    fieldDescription);
            recommended = rec;
            if (canonicalType.isBlank()) {
                canonicalType = canonicalFieldType(rec.path("type").asText(""));
                finalRequired = rec.path("required").asBoolean(false);
            }
            if (finalOptionValues.isEmpty()) {
                for (JsonNode option : asArray(rec.path("option_values"))) {
                    String value = option.asText("").trim();
                    if (!value.isBlank()) {
                        finalOptionValues.add(value);
                    }
                }
            }
        }
        if (canonicalType.isBlank()) {
            canonicalType = "Text";
        }

        ObjectNode fieldPlan = Jacksons.mapper().createObjectNode();
        fieldPlan.put("name", fieldName);
        fieldPlan.put("type", canonicalType);
        fieldPlan.put("controlType", FIELD_TYPES.getOrDefault(canonicalType, 2));
        fieldPlan.put("required", finalRequired);
        fieldPlan.put("description", fieldDescription == null ? "" : fieldDescription);
        ArrayNode optionValuesNode = fieldPlan.putArray("option_values");
        for (String option : finalOptionValues) {
            optionValuesNode.add(option);
        }

        Path incrementalDir = repoRoot.resolve("data").resolve("outputs").resolve("incremental");
        Files.createDirectories(incrementalDir);
        String timestamp = nowTs();
        Path planFile = incrementalDir.resolve("field_plan_" + worksheetId + "_" + timestamp + ".json");
        Jacksons.mapper().writeValue(planFile.toFile(), fieldPlan);

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("status", execute ? "success" : "plan_only");
        result.put("appId", appId);
        result.put("worksheetId", worksheetId);
        result.put("worksheetName", controlsState.worksheetName());
        result.put("planFile", planFile.toAbsolutePath().normalize().toString());
        result.set("fieldPlan", fieldPlan);
        result.set("recommended", recommended);

        if (!execute) {
            return result;
        }

        WorksheetControlsState latestState = fetchWorksheetControls(authorization, webAuth, worksheetId);
        ArrayNode controls = latestState.controls().deepCopy();
        controls.add(buildNewFieldControl(fieldName, canonicalType, finalRequired, finalOptionValues, fieldDescription));

        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("version", latestState.version());
        payload.put("sourceId", worksheetId);
        payload.set("controls", controls);
        payload.put("appKey", authorization.appKey());
        payload.put("sign", authorization.sign());

        JsonNode saveResponse = postWebJson(webBaseUrl + SAVE_CONTROLS_ENDPOINT, payload, webAuth);
        if (saveResponse.path("error_code").asInt(0) != 1) {
            throw new IllegalStateException("SaveWorksheetControls failed: " + Jacksons.mapper().writeValueAsString(saveResponse));
        }

        ObjectNode createdField = findCreatedField(saveResponse, fieldName, FIELD_TYPES.getOrDefault(canonicalType, 2));
        Path outputFile = incrementalDir.resolve("field_add_" + worksheetId + "_" + timestamp + ".json");
        ObjectNode output = Jacksons.mapper().createObjectNode();
        output.set("field", createdField);
        output.set("response", saveResponse);
        Jacksons.mapper().writeValue(outputFile.toFile(), output);

        result.put("outputFile", outputFile.toAbsolutePath().normalize().toString());
        result.set("field", createdField);
        return result;
    }

    private WorksheetControlsState fetchWorksheetControls(AppAuthorization authorization, WebAuth webAuth, String worksheetId) throws Exception {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("worksheetId", worksheetId);
        payload.put("appKey", authorization.appKey());
        payload.put("sign", authorization.sign());
        JsonNode body = postWebJson(webBaseUrl + GET_CONTROLS_ENDPOINT, payload, webAuth);
        boolean ok = body.path("error_code").asInt(0) == 1
                || body.path("state").asInt(0) == 1
                || body.path("data").path("code").asInt(0) == 1;
        if (!ok) {
            throw new IllegalStateException("GetWorksheetControls failed: " + Jacksons.mapper().writeValueAsString(body));
        }
        JsonNode data = body.path("data").path("data").isObject()
                ? body.path("data").path("data")
                : body.path("data");
        return new WorksheetControlsState(
                data.path("worksheetName").asText(worksheetId),
                data.path("version").asInt(0),
                asArray(data.path("controls")).deepCopy());
    }

    private ObjectNode recommendFieldType(
            AiAuthConfig aiAuth,
            String worksheetName,
            ArrayNode existingControls,
            String fieldName,
            String fieldDescription) throws Exception {
        StringBuilder existingSummary = new StringBuilder();
        int limit = Math.min(15, existingControls.size());
        for (int i = 0; i < limit; i++) {
            JsonNode field = existingControls.get(i);
            existingSummary.append("- ")
                    .append(field.path("controlName").asText(field.path("name").asText("")))
                    .append(" (type=")
                    .append(field.path("type").asInt(field.path("controlType").asInt(0)))
                    .append(")\n");
        }
        String prompt = """
                You are a worksheet field design expert. Recommend the best field type and config.

                Worksheet: %s
                Existing fields:
                %s

                New field name: %s
                Business description: %s

                Supported types:
                Text, SingleSelect, MultipleSelect, Dropdown, Collaborator, Number, Money, Date, DateTime, Phone, Email, RichText, Attachment, Rating, Checkbox

                Output strict JSON:
                {
                  "type": "Text|SingleSelect|MultipleSelect|Dropdown|Collaborator|Number|Money|Date|DateTime|Phone|Email|RichText|Attachment|Rating|Checkbox",
                  "required": false,
                  "option_values": [],
                  "reason": "Why"
                }

                Rules:
                1) Select/Dropdown fields must provide 2-8 option_values
                2) Collaborator required should be false
                3) Output JSON only
                """.formatted(
                worksheetName,
                existingSummary.toString(),
                fieldName,
                fieldDescription == null ? "" : fieldDescription);
        JsonNode parsed = aiJsonParser.parse(aiClient.generateJson(prompt, aiAuth));
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("Add field AI response must be a JSON object");
        }
        return objectNode;
    }

    private ObjectNode buildNewFieldControl(
            String fieldName,
            String fieldType,
            boolean required,
            List<String> optionValues,
            String hint) {
        int controlType = FIELD_TYPES.getOrDefault(fieldType, 2);
        ObjectNode control = Jacksons.mapper().createObjectNode();
        control.put("controlId", "");
        control.put("controlName", fieldName);
        control.put("type", controlType);
        control.put("required", required ? 1 : 0);
        control.put("attribute", 0);
        ObjectNode advancedSetting = control.putObject("advancedSetting");
        advancedSetting.put("sorttype", "zh");
        if (hint != null && !hint.isBlank()) {
            control.put("hint", hint);
        }

        if (requiresOptions(fieldType)) {
            ArrayNode options = control.putArray("options");
            List<String> values = optionValues == null || optionValues.isEmpty()
                    ? List.of("Option 1", "Option 2")
                    : optionValues;
            for (int i = 0; i < values.size(); i++) {
                ObjectNode option = options.addObject();
                option.put("key", UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                option.put("value", values.get(i));
                option.put("index", i + 1);
                option.put("color", "#C9E6FC");
            }
        }

        if ("Collaborator".equals(fieldType)) {
            control.put("required", 0);
            control.put("enumDefault", 0);
            advancedSetting.put("usertype", "1");
        }

        if ("Number".equals(fieldType) || "Money".equals(fieldType)) {
            control.put("dot", 2);
        }
        return control;
    }

    private boolean requiresOptions(String fieldType) {
        return "SingleSelect".equals(fieldType)
                || "MultipleSelect".equals(fieldType)
                || "Dropdown".equals(fieldType);
    }

    private String canonicalFieldType(String rawType) {
        if (rawType == null) {
            return "";
        }
        String trimmed = rawType.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if ("Member".equalsIgnoreCase(trimmed)) {
            return "Collaborator";
        }
        for (String candidate : FIELD_TYPES.keySet()) {
            if (candidate.equalsIgnoreCase(trimmed)) {
                return candidate;
            }
        }
        return "";
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
                    String appName = row.path("name").asText(appId);
                    if (!appKey.isBlank() && !sign.isBlank()) {
                        return new AppAuthorization(appId, appKey, sign, appName, candidate);
                    }
                }
            }
        }
        throw new IllegalStateException("No app authorization found for appId=" + appId);
    }

    private JsonNode postWebJson(String url, JsonNode payload, WebAuth webAuth) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("AccountId", webAuth.accountId())
                .header("Authorization", webAuth.authorization())
                .header("Cookie", webAuth.cookie())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", webBaseUrl)
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

    private ObjectNode findCreatedField(JsonNode saveResponse, String fieldName, int controlType) {
        ArrayNode controls = asArray(saveResponse.path("data").path("data").path("controls"));
        for (int i = controls.size() - 1; i >= 0; i--) {
            JsonNode control = controls.get(i);
            if (!fieldName.equals(control.path("controlName").asText(""))) {
                continue;
            }
            if (controlType != control.path("type").asInt(-1)) {
                continue;
            }
            ObjectNode result = Jacksons.mapper().createObjectNode();
            result.put("controlId", control.path("controlId").asText(""));
            result.put("controlName", fieldName);
            result.put("type", controlType);
            return result;
        }
        ObjectNode fallback = Jacksons.mapper().createObjectNode();
        fallback.put("controlId", "");
        fallback.put("controlName", fieldName);
        fallback.put("type", controlType);
        return fallback;
    }

    private ArrayNode asArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : Jacksons.mapper().createArrayNode();
    }

    private String nowTs() {
        return DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
    }

    private record AppAuthorization(String appId, String appKey, String sign, String appName, Path authPath) {
    }

    private record WorksheetControlsState(String worksheetName, int version, ArrayNode controls) {
    }

    private record WebAuth(String accountId, String authorization, String cookie) {
    }
}
