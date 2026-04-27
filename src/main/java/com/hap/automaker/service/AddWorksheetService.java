package com.hap.automaker.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.AiJsonParser;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;

public final class AddWorksheetService {

    private static final String DEFAULT_BASE_URL = "https://api.mingdao.com";
    private static final String APP_INFO_ENDPOINT = "/v3/app";
    private static final Set<String> ALLOWED_FIELD_TYPES = Set.of(
            "Text", "Number", "Money", "SingleSelect", "MultipleSelect", "Dropdown", "Date", "DateTime",
            "Collaborator", "Phone", "Email", "RichText", "Attachment", "Rating", "Checkbox", "Area", "Relation");
    private static final Set<String> OPTION_REQUIRED_TYPES = Set.of("SingleSelect", "MultipleSelect", "Dropdown");

    private final AiTextClient aiClient;
    private final AiJsonParser aiJsonParser;
    private final WorksheetCreator worksheetCreator;
    private final HttpClient httpClient;
    private final String baseUrl;

    public AddWorksheetService() {
        this(
                new HttpAiTextClient(),
                new AiJsonParser(),
                new WorksheetCreateService(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                DEFAULT_BASE_URL);
    }

    AddWorksheetService(
            AiTextClient aiClient,
            WorksheetCreator worksheetCreator,
            String baseUrl) {
        this(
                aiClient,
                new AiJsonParser(),
                worksheetCreator,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                baseUrl);
    }

    AddWorksheetService(
            AiTextClient aiClient,
            AiJsonParser aiJsonParser,
            WorksheetCreator worksheetCreator,
            HttpClient httpClient,
            String baseUrl) {
        this.aiClient = aiClient;
        this.aiJsonParser = aiJsonParser;
        this.worksheetCreator = worksheetCreator;
        this.httpClient = httpClient;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }

    public JsonNode addWorksheet(
            Path repoRoot,
            String appId,
            String worksheetName,
            String description,
            boolean execute) throws Exception {
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(new ConfigPaths(repoRoot).aiAuth().toFile(), AiAuthConfig.class);
        AppAuthorization authorization = resolveAppAuthorization(repoRoot, appId);
        List<String> existingWorksheets = fetchExistingWorksheetNames(authorization);

        ObjectNode plan = planSingleWorksheet(aiAuth, worksheetName, description, existingWorksheets);

        Path incrementalDir = repoRoot.resolve("data").resolve("outputs").resolve("incremental");
        Files.createDirectories(incrementalDir);
        String timestamp = nowTs();
        Path planFile = incrementalDir.resolve("worksheet_plan_" + appId + "_" + timestamp + ".json");
        Jacksons.mapper().writeValue(planFile.toFile(), plan);

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("status", execute ? "success" : "plan_only");
        result.put("appId", appId);
        result.put("worksheetName", worksheetName);
        result.put("planFile", planFile.toAbsolutePath().normalize().toString());
        result.set("plan", plan);

        if (!execute) {
            return result;
        }

        Path outputFile = incrementalDir.resolve("worksheet_add_" + appId + "_" + timestamp + ".json");
        WorksheetCreateResult createResult = worksheetCreator.createFromPlan(
                repoRoot,
                planFile,
                authorization.authPath(),
                outputFile);
        result.put("outputFile", outputFile.toAbsolutePath().normalize().toString());
        result.set("createResult", createResult.summary());
        return result;
    }

    private ObjectNode planSingleWorksheet(
            AiAuthConfig aiAuth,
            String worksheetName,
            String description,
            List<String> existingWorksheets) throws Exception {
        String prompt = buildPrompt(worksheetName, description, existingWorksheets);
        JsonNode parsed = aiJsonParser.parse(aiClient.generateJson(prompt, aiAuth));
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("Add worksheet AI response must be a JSON object");
        }
        return normalizeAndValidatePlan(objectNode, worksheetName, existingWorksheets);
    }

    private String buildPrompt(String worksheetName, String description, List<String> existingWorksheets) {
        String existing = existingWorksheets.isEmpty() ? "none" : String.join(", ", existingWorksheets);
        return """
                You are an enterprise app designer. Plan exactly one new worksheet for an existing app.

                New worksheet name: %s
                Business description: %s
                Existing worksheets: %s

                Output strict JSON:
                {
                  "worksheets": [
                    {
                      "name": "%s",
                      "purpose": "One-line purpose",
                      "fields": [
                        {
                          "name": "Field name",
                          "type": "Text|Number|Money|SingleSelect|MultipleSelect|Dropdown|Date|DateTime|Collaborator|Phone|Email|RichText|Attachment|Rating|Checkbox|Area|Relation",
                          "required": true,
                          "description": "Field description",
                          "option_values": [],
                          "relation_target": ""
                        }
                      ],
                      "depends_on": []
                    }
                  ],
                  "relationships": [],
                  "creation_order": ["%s"]
                }

                Rules:
                1) Output exactly one worksheet
                2) Worksheet name must be exactly "%s"
                3) First field should be a required Text title field
                4) Select fields must have 2-8 option_values
                5) Relation fields must set relation_target to one of existing worksheets
                6) Output JSON only
                """.formatted(
                worksheetName,
                description == null ? "" : description,
                existing,
                worksheetName,
                worksheetName,
                worksheetName);
    }

    private ObjectNode normalizeAndValidatePlan(ObjectNode plan, String worksheetName, List<String> existingWorksheets) {
        ArrayNode worksheets = asArray(plan.path("worksheets"));
        JsonNode selected = null;
        for (JsonNode worksheet : worksheets) {
            if (worksheetName.equals(worksheet.path("name").asText(""))) {
                selected = worksheet;
                break;
            }
        }
        if (selected == null) {
            selected = worksheets.isEmpty() ? null : worksheets.get(0);
        }
        if (selected == null || !selected.isObject()) {
            throw new IllegalArgumentException("Worksheet plan must contain exactly one worksheet");
        }

        ObjectNode worksheet = (ObjectNode) selected.deepCopy();
        worksheet.put("name", worksheetName);
        ArrayNode fields = asArray(worksheet.path("fields"));
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("Worksheet plan must contain at least one field");
        }

        Set<String> seenFieldNames = new HashSet<>();
        boolean titleAssigned = false;
        for (JsonNode fieldNode : fields) {
            if (!(fieldNode instanceof ObjectNode field)) {
                throw new IllegalArgumentException("Worksheet field must be an object");
            }
            String name = field.path("name").asText("").trim();
            String type = field.path("type").asText("Text").trim();
            if (name.isBlank()) {
                throw new IllegalArgumentException("Worksheet field name cannot be blank");
            }
            if (!seenFieldNames.add(name)) {
                throw new IllegalArgumentException("Duplicate field name: " + name);
            }
            if (!ALLOWED_FIELD_TYPES.contains(type)) {
                throw new IllegalArgumentException("Unsupported field type: " + type);
            }
            if (OPTION_REQUIRED_TYPES.contains(type) && asArray(field.path("option_values")).size() < 2) {
                throw new IllegalArgumentException("Select field missing option_values: " + name);
            }
            if ("Relation".equals(type)) {
                String target = field.path("relation_target").asText("").trim();
                if (!existingWorksheets.contains(target)) {
                    throw new IllegalArgumentException("Relation target not found in existing worksheets: " + target);
                }
            }
            if ("Collaborator".equals(type)) {
                field.put("required", false);
            }
            if (!titleAssigned && "Text".equals(type)) {
                field.put("required", true);
                titleAssigned = true;
            }
        }
        if (!titleAssigned && fields.get(0) instanceof ObjectNode firstField) {
            firstField.put("type", "Text");
            firstField.put("required", true);
        }

        ObjectNode normalized = Jacksons.mapper().createObjectNode();
        ArrayNode outWorksheets = normalized.putArray("worksheets");
        outWorksheets.add(worksheet);
        normalized.set("relationships", plan.path("relationships").isArray()
                ? plan.path("relationships").deepCopy()
                : Jacksons.mapper().createArrayNode());
        ArrayNode creationOrder = normalized.putArray("creation_order");
        creationOrder.add(worksheetName);
        return normalized;
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

    private List<String> fetchExistingWorksheetNames(AppAuthorization authorization) throws Exception {
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
            throw new IllegalStateException("Fetch existing worksheets failed: " + response.body());
        }
        List<String> result = new ArrayList<>();
        for (JsonNode section : asArray(body.path("data").path("sections"))) {
            collectWorksheetNames(section, result);
        }
        return result;
    }

    private void collectWorksheetNames(JsonNode section, List<String> result) {
        for (JsonNode item : asArray(section.path("items"))) {
            if (item.path("type").asInt(-1) != 0) {
                continue;
            }
            String name = item.path("name").asText("").trim();
            if (!name.isBlank() && !result.contains(name)) {
                result.add(name);
            }
        }
        for (JsonNode child : asArray(section.path("childSections"))) {
            collectWorksheetNames(child, result);
        }
    }

    private ArrayNode asArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : Jacksons.mapper().createArrayNode();
    }

    private String nowTs() {
        return DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
    }

    private record AppAuthorization(String appId, String appKey, String sign, String appName, Path authPath) {
    }
}
