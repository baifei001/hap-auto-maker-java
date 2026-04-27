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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.config.Jacksons;

public final class WorksheetCreateService implements WorksheetCreator {

    private static final String DEFAULT_BASE_URL = "https://api.mingdao.com";
    private static final String APP_INFO_ENDPOINT = "/v3/app";
    private static final String WORKSHEET_ENDPOINT = "/v3/app/worksheets";
    private static final Set<String> CREATE_WS_SUPPORTED_TYPES = Set.of(
            "Text",
            "Number",
            "SingleSelect",
            "MultipleSelect",
            "Dropdown",
            "Attachment",
            "Date",
            "DateTime",
            "Collaborator",
            "Rating",
            "Checkbox");

    private final HttpClient httpClient;
    private final String baseUrl;

    public WorksheetCreateService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), DEFAULT_BASE_URL);
    }

    public WorksheetCreateService(String baseUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), baseUrl);
    }

    public WorksheetCreateService(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }

    @Override
    public WorksheetCreateResult createFromPlan(Path repoRoot, Path planJson, Path appAuthJson, Path outputJson) throws Exception {
        JsonNode plan = Jacksons.mapper().readTree(planJson.toFile());
        validatePlan(plan);
        AppAuthorization authorization = loadAppAuthorization(appAuthJson);
        Map<String, String> existingWorksheets = fetchExistingWorksheets(authorization);

        List<JsonNode> orderedWorksheets = orderWorksheets(plan.path("worksheets"), plan.path("creation_order"));
        List<WorksheetMaterialization> materializations = new ArrayList<>();
        ObjectNode nameToWorksheetId = Jacksons.mapper().createObjectNode();
        ArrayNode createdWorksheets = Jacksons.mapper().createArrayNode();
        ArrayNode skippedWorksheets = Jacksons.mapper().createArrayNode();

        for (JsonNode worksheet : orderedWorksheets) {
            WorksheetFields fields = splitFields(worksheet.path("fields"));
            String worksheetName = worksheet.path("name").asText("").trim();
            String existingWorksheetId = existingWorksheets.get(worksheetName);
            boolean skipped = existingWorksheetId != null && !existingWorksheetId.isBlank();

            String worksheetId = existingWorksheetId;
            JsonNode createResponse;
            if (skipped) {
                createResponse = Jacksons.mapper().readTree("""
                        {
                          "success": true,
                          "skipped": true
                        }
                        """);
                ObjectNode skippedNode = Jacksons.mapper().createObjectNode();
                skippedNode.put("name", worksheetName);
                skippedNode.put("worksheetId", worksheetId);
                skippedNode.putObject("existingWorksheet").put("workSheetId", worksheetId);
                skippedWorksheets.add(skippedNode);
            } else {
                createResponse = createWorksheet(authorization, worksheetName, fields.normalFields());
                worksheetId = createResponse.path("data").path("worksheetId").asText("");
                if (worksheetId.isBlank()) {
                    throw new IllegalStateException("Create worksheet response missing worksheetId for " + worksheetName);
                }
            }

            nameToWorksheetId.put(worksheetName, worksheetId);
            ObjectNode createdNode = Jacksons.mapper().createObjectNode();
            createdNode.put("name", worksheetName);
            createdNode.put("worksheetId", worksheetId);
            createdNode.put("skipped", skipped);
            createdNode.set("result", createResponse);
            createdWorksheets.add(createdNode);

            materializations.add(new WorksheetMaterialization(
                    worksheetName,
                    worksheetId,
                    fields.deferredFields()));
        }

        for (WorksheetMaterialization materialization : materializations) {
            addDeferredFields(authorization, materialization.worksheetId(), materialization.deferredFields());
        }

        List<RelationSpec> normalizedRelations = normalizeRelations(plan.path("worksheets"), plan.path("relationships"));
        ArrayNode relationUpdates = Jacksons.mapper().createArrayNode();
        Map<String, String> worksheetIds = objectNodeToMap(nameToWorksheetId);
        Map<String, List<RelationSpec>> relationsBySource = groupRelationsBySource(normalizedRelations);
        for (WorksheetMaterialization materialization : materializations) {
            List<RelationSpec> relationSpecs = relationsBySource.get(materialization.name());
            if (relationSpecs == null || relationSpecs.isEmpty()) {
                continue;
            }
            JsonNode relationResult = addRelationFields(
                    authorization,
                    materialization.worksheetId(),
                    materialization.name(),
                    relationSpecs,
                    worksheetIds);
            ObjectNode updateNode = Jacksons.mapper().createObjectNode();
            updateNode.put("name", materialization.name());
            updateNode.put("worksheetId", materialization.worksheetId());
            updateNode.put("relation_fields_count", relationSpecs.size());
            updateNode.set("result", relationResult);
            relationUpdates.add(updateNode);
        }

        ObjectNode summary = Jacksons.mapper().createObjectNode();
        summary.put("app_id", authorization.appId());
        summary.put("plan_json", planJson.toAbsolutePath().normalize().toString());
        summary.put("app_auth_json", appAuthJson.toAbsolutePath().normalize().toString());
        summary.put("skip_existing", true);
        summary.put("existing_worksheets_count", existingWorksheets.size());
        summary.set("created_worksheets", createdWorksheets);
        summary.set("skipped_worksheets", skippedWorksheets);
        summary.set("normalized_relations", relationSpecsToJson(normalizedRelations));
        summary.set("relation_updates", relationUpdates);
        summary.set("relation_verification", verifyRelationCardinality(authorization, worksheetIds, normalizedRelations));
        summary.set("name_to_worksheet_id", nameToWorksheetId);

        Files.createDirectories(outputJson.getParent());
        Jacksons.mapper().writeValue(outputJson.toFile(), summary);
        return new WorksheetCreateResult(outputJson, summary);
    }

    private void validatePlan(JsonNode plan) {
        if (!plan.path("worksheets").isArray() || plan.path("worksheets").isEmpty()) {
            throw new IllegalArgumentException("Worksheet plan must contain a non-empty worksheets array");
        }
    }

    private AppAuthorization loadAppAuthorization(Path appAuthJson) throws Exception {
        JsonNode root = Jacksons.mapper().readTree(appAuthJson.toFile());
        JsonNode rows = root.path("data");
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("App auth JSON must contain data array");
        }
        JsonNode row = rows.path(0);
        String appId = row.path("appId").asText("").trim();
        String appKey = row.path("appKey").asText("").trim();
        String sign = row.path("sign").asText("").trim();
        if (appId.isBlank() || appKey.isBlank() || sign.isBlank()) {
            throw new IllegalArgumentException("App auth JSON must contain appId, appKey, and sign");
        }
        return new AppAuthorization(appId, appKey, sign);
    }

    private Map<String, String> fetchExistingWorksheets(AppAuthorization authorization) throws Exception {
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

        Map<String, String> result = new LinkedHashMap<>();
        ArrayNode sections = asArray(body.path("data").path("sections"));
        for (JsonNode section : sections) {
            collectWorksheets(section, result);
        }
        return result;
    }

    private void collectWorksheets(JsonNode section, Map<String, String> result) {
        for (JsonNode item : asArray(section.path("items"))) {
            if (item.path("type").asInt(-1) != 0) {
                continue;
            }
            String worksheetId = item.path("id").asText("").trim();
            String worksheetName = item.path("name").asText("").trim();
            if (!worksheetId.isBlank() && !worksheetName.isBlank() && !result.containsKey(worksheetName)) {
                result.put(worksheetName, worksheetId);
            }
        }
        for (JsonNode child : asArray(section.path("childSections"))) {
            collectWorksheets(child, result);
        }
    }

    private List<JsonNode> orderWorksheets(JsonNode worksheetsNode, JsonNode creationOrderNode) {
        List<JsonNode> all = new ArrayList<>();
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode worksheet : asArray(worksheetsNode)) {
            String name = worksheet.path("name").asText("").trim();
            if (!name.isBlank()) {
                byName.put(name, worksheet);
                all.add(worksheet);
            }
        }
        if (!creationOrderNode.isArray() || creationOrderNode.isEmpty()) {
            return all;
        }
        List<JsonNode> ordered = new ArrayList<>();
        for (JsonNode nameNode : creationOrderNode) {
            JsonNode worksheet = byName.remove(nameNode.asText("").trim());
            if (worksheet != null) {
                ordered.add(worksheet);
            }
        }
        ordered.addAll(byName.values());
        return ordered;
    }

    private WorksheetFields splitFields(JsonNode fieldsNode) {
        ArrayNode normalFields = Jacksons.mapper().createArrayNode();
        ArrayNode deferredFields = Jacksons.mapper().createArrayNode();
        boolean titleAssigned = false;

        for (JsonNode field : asArray(fieldsNode)) {
            String type = field.path("type").asText("Text").trim();
            if ("Relation".equals(type)) {
                continue;
            }
            ObjectNode payload = buildFieldPayload(field, !titleAssigned);
            if (payload.path("isTitle").asInt(0) == 1) {
                titleAssigned = true;
            }
            if (CREATE_WS_SUPPORTED_TYPES.contains(type)) {
                normalFields.add(payload);
            } else {
                deferredFields.add(payload);
            }
        }

        if (normalFields.isEmpty()) {
            normalFields.add(titleField("Name"));
            return new WorksheetFields(normalFields, deferredFields);
        }
        if (!titleAssigned) {
            for (JsonNode field : normalFields) {
                if ("Text".equals(field.path("type").asText())) {
                    ((ObjectNode) field).put("isTitle", 1);
                    titleAssigned = true;
                    break;
                }
            }
        }
        if (!titleAssigned) {
            normalFields.insert(0, titleField("Name"));
        }
        return new WorksheetFields(normalFields, deferredFields);
    }

    private ObjectNode titleField(String name) {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("name", name);
        payload.put("type", "Text");
        payload.put("required", true);
        payload.put("isTitle", 1);
        return payload;
    }

    private ObjectNode buildFieldPayload(JsonNode field, boolean allowTitle) {
        String type = field.path("type").asText("Text").trim();
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("name", field.path("name").asText("Unnamed Field").trim());
        payload.put("type", type);
        boolean required = field.path("required").asBoolean(false);
        if ("Collaborator".equals(type) || "Checkbox".equals(type)) {
            required = false;
        }
        payload.put("required", required);
        if (allowTitle && "Text".equals(type)) {
            payload.put("isTitle", 1);
        }

        if ("Number".equals(type) || "Money".equals(type)) {
            int dot = parseInt(field.path("dot").asText("2"), 2);
            payload.put("dot", dot);
            if ("Number".equals(type)) {
                payload.put("precision", dot);
            }
            String unit = field.path("unit").asText("").trim();
            if (!unit.isBlank()) {
                ObjectNode advanced = payload.putObject("advancedSetting");
                advanced.put("unit", unit);
                advanced.put("unitpos", "0");
            }
        } else if ("SingleSelect".equals(type) || "MultipleSelect".equals(type) || "Dropdown".equals(type)) {
            ArrayNode options = payload.putArray("options");
            List<String> optionValues = collectOptionValues(field.path("option_values"));
            if (optionValues.isEmpty()) {
                optionValues = List.of("Option 1", "Option 2");
            }
            for (int i = 0; i < optionValues.size(); i++) {
                ObjectNode option = options.addObject();
                option.put("value", optionValues.get(i));
                option.put("index", i + 1);
                option.put("color", "#C9E6FC");
            }
            ObjectNode advanced = payload.putObject("advancedSetting");
            advanced.put("sorttype", "zh");
            if ("SingleSelect".equals(type)) {
                advanced.put("showtype", "0");
            } else if ("MultipleSelect".equals(type)) {
                advanced.put("checktype", "1");
            }
        } else if ("Collaborator".equals(type)) {
            payload.put("subType", 0);
        }
        return payload;
    }

    private List<String> collectOptionValues(JsonNode optionValuesNode) {
        List<String> values = new ArrayList<>();
        if (!optionValuesNode.isArray()) {
            return values;
        }
        for (JsonNode valueNode : optionValuesNode) {
            String value = valueNode.asText("").trim();
            if (!value.isBlank() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private JsonNode createWorksheet(AppAuthorization authorization, String worksheetName, ArrayNode fields) throws Exception {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("name", worksheetName);
        payload.set("fields", fields);
        return postJson(
                WORKSHEET_ENDPOINT,
                payload,
                authorization,
                "Create worksheet failed for " + worksheetName);
    }

    private void addDeferredFields(AppAuthorization authorization, String worksheetId, ArrayNode deferredFields) throws Exception {
        for (JsonNode field : deferredFields) {
            ObjectNode payload = Jacksons.mapper().createObjectNode();
            ArrayNode addFields = payload.putArray("addFields");
            addFields.add(field.deepCopy());
            postJsonAllowFailure(
                    WORKSHEET_ENDPOINT + "/" + worksheetId,
                    payload,
                    authorization);
        }
    }

    private List<RelationSpec> normalizeRelations(JsonNode worksheetsNode, JsonNode relationshipsNode) {
        List<RelationSpec> relationSpecs = new ArrayList<>();
        if (relationshipsNode.isArray() && !relationshipsNode.isEmpty()) {
            for (int i = 0; i < relationshipsNode.size(); i++) {
                JsonNode rule = relationshipsNode.path(i);
                String from = rule.path("from").asText("").trim();
                String to = rule.path("to").asText("").trim();
                if (from.isBlank() || to.isBlank()) {
                    continue;
                }
                String cardinality = rule.path("cardinality").asText("1-N").trim().toUpperCase(Locale.ROOT);
                String source = "1-N".equals(cardinality) ? to : from;
                String target = "1-N".equals(cardinality) ? from : to;
                FieldMeta fieldMeta = findRelationFieldMeta(worksheetsNode, source, target, rule.path("field").asText("").trim());
                relationSpecs.add(new RelationSpec(
                        source,
                        target,
                        fieldMeta.fieldName(),
                        fieldMeta.required(),
                        cardinality,
                        "relationship_rule",
                        List.of(min(from, to), max(from, to)),
                        i));
            }
            return relationSpecs;
        }

        for (JsonNode worksheet : asArray(worksheetsNode)) {
            String source = worksheet.path("name").asText("").trim();
            for (JsonNode field : asArray(worksheet.path("fields"))) {
                if (!"Relation".equals(field.path("type").asText("").trim())) {
                    continue;
                }
                String target = field.path("relation_target").asText("").trim();
                if (source.isBlank() || target.isBlank()) {
                    continue;
                }
                relationSpecs.add(new RelationSpec(
                        source,
                        target,
                        field.path("name").asText("关联记录").trim(),
                        field.path("required").asBoolean(false),
                        "1-N",
                        "field_fallback",
                        List.of(min(source, target), max(source, target)),
                        -1));
            }
        }
        return relationSpecs;
    }

    private FieldMeta findRelationFieldMeta(JsonNode worksheetsNode, String source, String target, String preferredFieldName) {
        for (JsonNode worksheet : asArray(worksheetsNode)) {
            if (!source.equals(worksheet.path("name").asText("").trim())) {
                continue;
            }
            FieldMeta fallback = null;
            for (JsonNode field : asArray(worksheet.path("fields"))) {
                if (!"Relation".equals(field.path("type").asText("").trim())) {
                    continue;
                }
                if (!target.equals(field.path("relation_target").asText("").trim())) {
                    continue;
                }
                String fieldName = field.path("name").asText("").trim();
                FieldMeta meta = new FieldMeta(
                        fieldName.isBlank() ? "关联记录" : fieldName,
                        field.path("required").asBoolean(false));
                if (!preferredFieldName.isBlank() && preferredFieldName.equals(fieldName)) {
                    return meta;
                }
                if (fallback == null) {
                    fallback = meta;
                }
            }
            if (fallback != null) {
                return fallback;
            }
        }
        return new FieldMeta(preferredFieldName.isBlank() ? "关联记录" : preferredFieldName, false);
    }

    private Map<String, List<RelationSpec>> groupRelationsBySource(List<RelationSpec> relationSpecs) {
        Map<String, List<RelationSpec>> grouped = new LinkedHashMap<>();
        for (RelationSpec spec : relationSpecs) {
            grouped.computeIfAbsent(spec.source(), ignored -> new ArrayList<>()).add(spec);
        }
        return grouped;
    }

    private JsonNode addRelationFields(
            AppAuthorization authorization,
            String worksheetId,
            String worksheetName,
            List<RelationSpec> relationSpecs,
            Map<String, String> nameToId) throws Exception {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        ArrayNode addFields = payload.putArray("addFields");
        for (RelationSpec spec : relationSpecs) {
            String targetId = nameToId.get(spec.target());
            if (targetId == null || targetId.isBlank()) {
                throw new IllegalStateException("Missing target worksheet for relation " + worksheetName + "." + spec.fieldName());
            }
            ObjectNode field = addFields.addObject();
            field.put("name", spec.fieldName());
            field.put("type", "Relation");
            field.put("required", spec.required());
            field.put("dataSource", targetId);
            field.put("subType", 1);
            ObjectNode relation = field.putObject("relation");
            relation.putArray("showFields");
            relation.put("bidirectional", true);
        }
        JsonNode response = postJson(
                WORKSHEET_ENDPOINT + "/" + worksheetId,
                payload,
                authorization,
                "Add relation fields failed for " + worksheetName);
        if (response.isObject()) {
            ((ObjectNode) response).putArray("skipped_relations");
        }
        return response;
    }

    private ObjectNode verifyRelationCardinality(
            AppAuthorization authorization,
            Map<String, String> nameToId,
            List<RelationSpec> relationSpecs) throws Exception {
        ArrayNode relationEdges = Jacksons.mapper().createArrayNode();
        Map<String, List<RelationEdge>> edgesBySource = new HashMap<>();
        Map<String, String> idToName = new HashMap<>();
        for (Map.Entry<String, String> entry : nameToId.entrySet()) {
            idToName.put(entry.getValue(), entry.getKey());
        }

        for (Map.Entry<String, String> entry : nameToId.entrySet()) {
            String sourceName = entry.getKey();
            String worksheetId = entry.getValue();
            JsonNode detail = fetchWorksheetDetail(authorization, worksheetId);
            List<RelationEdge> edges = new ArrayList<>();
            for (JsonNode field : asArray(detail.path("fields"))) {
                if (!"Relation".equals(field.path("type").asText("").trim())) {
                    continue;
                }
                String targetName = idToName.getOrDefault(
                        field.path("dataSource").asText("").trim(),
                        "[external:" + field.path("dataSource").asText("").trim() + "]");
                int subType = field.path("subType").asInt(0);
                RelationEdge edge = new RelationEdge(
                        sourceName,
                        targetName,
                        field.path("name").asText("").trim(),
                        subType);
                edges.add(edge);

                ObjectNode edgeNode = relationEdges.addObject();
                edgeNode.put("source", edge.source());
                edgeNode.put("target", edge.target());
                edgeNode.put("field", edge.field());
                edgeNode.put("subType", edge.subType());
            }
            edgesBySource.put(sourceName, edges);
        }

        ArrayNode violations = Jacksons.mapper().createArrayNode();
        ArrayNode notes = Jacksons.mapper().createArrayNode();
        for (RelationSpec spec : relationSpecs) {
            boolean hasPrimary = edgesBySource.getOrDefault(spec.source(), List.of()).stream()
                    .anyMatch(edge -> spec.target().equals(edge.target())
                            && spec.fieldName().equals(edge.field())
                            && edge.subType() == 1);
            if (!hasPrimary) {
                ObjectNode violation = violations.addObject();
                violation.put("reason", "missing_primary_relation_field");
                violation.set("spec", spec.toJson());
                continue;
            }
            if (!spec.source().equals(spec.target())) {
                boolean hasReverse = edgesBySource.getOrDefault(spec.target(), List.of()).stream()
                        .anyMatch(edge -> spec.source().equals(edge.target())
                                && (edge.subType() == 1 || edge.subType() == 2));
                if (!hasReverse) {
                    ObjectNode violation = violations.addObject();
                    violation.put("reason", "missing_reverse_relation_field");
                    violation.set("spec", spec.toJson());
                }
            }
        }

        ObjectNode verification = Jacksons.mapper().createObjectNode();
        verification.put("checked_pairs", relationSpecs.size());
        verification.set("relation_edges", relationEdges);
        verification.set("violations", violations);
        verification.set("notes", notes);
        return verification;
    }

    private JsonNode fetchWorksheetDetail(AppAuthorization authorization, String worksheetId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + WORKSHEET_ENDPOINT + "/" + worksheetId))
                .timeout(Duration.ofSeconds(30))
                .header("HAP-Appkey", authorization.appKey())
                .header("HAP-Sign", authorization.sign())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = Jacksons.mapper().readTree(response.body());
        if (!body.path("success").asBoolean(false)) {
            throw new IllegalStateException("Fetch worksheet detail failed: " + response.body());
        }
        return body.path("data");
    }

    private JsonNode postJson(
            String path,
            JsonNode payload,
            AppAuthorization authorization,
            String failureMessage) throws Exception {
        JsonNode body = sendJson(path, payload, authorization);
        if (!body.path("success").asBoolean(false)) {
            throw new IllegalStateException(failureMessage + ": " + Jacksons.mapper().writeValueAsString(body));
        }
        return body;
    }

    private JsonNode postJsonAllowFailure(
            String path,
            JsonNode payload,
            AppAuthorization authorization) throws Exception {
        return sendJson(path, payload, authorization);
    }

    private JsonNode sendJson(
            String path,
            JsonNode payload,
            AppAuthorization authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("HAP-Appkey", authorization.appKey())
                .header("HAP-Sign", authorization.sign())
                .POST(HttpRequest.BodyPublishers.ofString(Jacksons.mapper().writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Jacksons.mapper().readTree(response.body());
    }

    private Map<String, String> objectNodeToMap(ObjectNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText("")));
        return result;
    }

    private ArrayNode relationSpecsToJson(List<RelationSpec> relationSpecs) {
        ArrayNode array = Jacksons.mapper().createArrayNode();
        for (RelationSpec spec : relationSpecs) {
            array.add(spec.toJson());
        }
        return array;
    }

    private ArrayNode asArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : Jacksons.mapper().createArrayNode();
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String min(String left, String right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private String max(String left, String right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private record AppAuthorization(String appId, String appKey, String sign) {
    }

    private record WorksheetFields(ArrayNode normalFields, ArrayNode deferredFields) {
    }

    private record WorksheetMaterialization(String name, String worksheetId, ArrayNode deferredFields) {
    }

    private record FieldMeta(String fieldName, boolean required) {
    }

    private record RelationEdge(String source, String target, String field, int subType) {
    }

    private record RelationSpec(
            String source,
            String target,
            String fieldName,
            boolean required,
            String cardinality,
            String origin,
            List<String> pairKey,
            int ruleIndex) {

        private ObjectNode toJson() {
            ObjectNode node = Jacksons.mapper().createObjectNode();
            if (ruleIndex >= 0) {
                node.put("rule_index", ruleIndex);
            }
            ArrayNode pairKeyNode = node.putArray("pair_key");
            for (String item : pairKey) {
                pairKeyNode.add(item);
            }
            node.put("source", source);
            node.put("target", target);
            node.put("field_name", fieldName);
            node.put("required", required);
            node.put("cardinality", cardinality);
            node.put("origin", origin);
            return node;
        }
    }
}
