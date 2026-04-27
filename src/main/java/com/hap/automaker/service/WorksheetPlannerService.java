package com.hap.automaker.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.AiJsonParser;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;

public final class WorksheetPlannerService implements WorksheetPlannerRunner {

    private static final List<String> ALLOWED_FIELD_TYPES = List.of(
            "Text", "Number", "Money", "SingleSelect", "MultipleSelect", "Dropdown", "Date", "DateTime",
            "Collaborator", "Phone", "Email", "RichText", "Attachment", "Rating", "Checkbox", "Area");
    private static final Set<String> OPTION_REQUIRED_TYPES = Set.of("SingleSelect", "MultipleSelect", "Dropdown");

    private final AiTextClient aiClient;
    private final AiJsonParser aiJsonParser;

    public WorksheetPlannerService() {
        this(new HttpAiTextClient(), new AiJsonParser());
    }

    WorksheetPlannerService(AiTextClient aiClient, AiJsonParser aiJsonParser) {
        this.aiClient = aiClient;
        this.aiJsonParser = aiJsonParser;
    }

    @Override
    public WorksheetPlannerResult plan(
            Path repoRoot,
            String appName,
            String businessContext,
            String requirements,
            String language,
            Path outputJson) throws Exception {
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(new ConfigPaths(repoRoot).aiAuth().toFile(), AiAuthConfig.class);

        ObjectNode skeleton = planSkeleton(aiAuth, appName, businessContext, requirements, language);
        repairSkeleton(skeleton);
        validateSkeleton(skeleton);

        ArrayNode worksheets = asArray(skeleton.path("worksheets"));
        ArrayNode worksheetSummaries = Jacksons.mapper().createArrayNode();
        for (JsonNode worksheet : worksheets) {
            ObjectNode summary = worksheetSummaries.addObject();
            summary.put("name", worksheet.path("name").asText(""));
            summary.put("purpose", worksheet.path("purpose").asText(""));
        }

        ObjectNode fieldsByWorksheet = Jacksons.mapper().createObjectNode();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(3, Math.max(1, worksheets.size())));
        try {
            List<Future<WorksheetFieldsResult>> futures = new ArrayList<>();
            for (JsonNode worksheet : worksheets) {
                futures.add(executor.submit(() -> planWorksheetFields(aiAuth, worksheet, worksheetSummaries, skeleton, language)));
            }
            for (Future<WorksheetFieldsResult> future : futures) {
                WorksheetFieldsResult result = future.get();
                fieldsByWorksheet.set(result.worksheetName(), result.fields());
            }
        } finally {
            executor.shutdownNow();
        }

        ObjectNode merged = mergeSkeleton(skeleton, fieldsByWorksheet);
        validateMerged(merged);
        Files.createDirectories(outputJson.getParent());
        Jacksons.mapper().writeValue(outputJson.toFile(), merged);
        return new WorksheetPlannerResult(outputJson, merged);
    }

    private ObjectNode planSkeleton(
            AiAuthConfig aiAuth,
            String appName,
            String businessContext,
            String requirements,
            String language) throws Exception {
        String raw = aiClient.generateJson(buildSkeletonPrompt(appName, businessContext, requirements, language), aiAuth);
        JsonNode parsed = aiJsonParser.parse(raw);
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("Worksheet skeleton AI response must be a JSON object");
        }
        return objectNode;
    }

    private WorksheetFieldsResult planWorksheetFields(
            AiAuthConfig aiAuth,
            JsonNode worksheet,
            ArrayNode worksheetSummaries,
            ObjectNode skeleton,
            String language) throws Exception {
        String worksheetName = worksheet.path("name").asText("");
        ArrayNode existingFields = Jacksons.mapper().createArrayNode();
        for (JsonNode field : asArray(worksheet.path("core_fields"))) {
            ObjectNode one = existingFields.addObject();
            one.put("name", field.path("name").asText(""));
            one.put("type", field.path("type").asText(""));
        }
        for (JsonNode relation : asArray(skeleton.path("relationships"))) {
            if (worksheetName.equals(relation.path("to").asText(""))) {
                ObjectNode one = existingFields.addObject();
                one.put("name", relation.path("field").asText(relation.path("from").asText("")));
                one.put("type", "Relation");
            }
        }

        String raw = aiClient.generateJson(
                buildFieldsPrompt(
                        worksheetName,
                        worksheet.path("purpose").asText(""),
                        existingFields,
                        worksheetSummaries,
                        language),
                aiAuth);
        JsonNode parsed = aiJsonParser.parse(raw);
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("Worksheet fields AI response must be a JSON object");
        }
        ArrayNode fields = asArray(objectNode.path("fields"));
        validateFields(fields, existingFields);
        ArrayNode repairedFields = repairFields(fields);
        return new WorksheetFieldsResult(worksheetName, repairedFields);
    }

    private void repairSkeleton(ObjectNode skeleton) {
        ArrayNode worksheets = asArray(skeleton.path("worksheets"));
        ArrayNode creationOrder = skeleton.path("creation_order").isArray()
                ? (ArrayNode) skeleton.path("creation_order")
                : skeleton.putArray("creation_order");
        Set<String> seen = new HashSet<>();
        for (JsonNode name : creationOrder) {
            seen.add(name.asText(""));
        }
        for (JsonNode worksheet : worksheets) {
            String name = worksheet.path("name").asText("");
            if (!name.isBlank() && !seen.contains(name)) {
                creationOrder.add(name);
                seen.add(name);
            }
            for (JsonNode field : asArray(worksheet.path("core_fields"))) {
                if ("Collaborator".equals(field.path("type").asText("")) && field instanceof ObjectNode objectNode) {
                    objectNode.put("required", false);
                }
            }
        }
    }

    private void validateSkeleton(ObjectNode skeleton) {
        ArrayNode worksheets = asArray(skeleton.path("worksheets"));
        if (worksheets.isEmpty()) {
            throw new IllegalArgumentException("Worksheet skeleton must contain worksheets");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode worksheet : worksheets) {
            String name = worksheet.path("name").asText("");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Worksheet skeleton contains blank worksheet name");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Worksheet skeleton contains duplicate worksheet name: " + name);
            }
        }
    }

    private void validateFields(ArrayNode fields, ArrayNode existingFields) {
        if (fields.size() < 1) {
            throw new IllegalArgumentException("Worksheet fields result must contain at least one field");
        }
        Set<String> existingNames = new HashSet<>();
        for (JsonNode field : existingFields) {
            existingNames.add(field.path("name").asText(""));
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode field : fields) {
            String name = field.path("name").asText("");
            String type = field.path("type").asText("");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Worksheet fields result contains blank field name");
            }
            if (!ALLOWED_FIELD_TYPES.contains(type)) {
                // 自动修复：将不支持的类型转为Text
                ((ObjectNode) field).put("type", "Text");
            }
            if (!seen.add(name) || existingNames.contains(name)) {
                continue; // 跳过重复字段
            }
        }
    }

    private ArrayNode repairFields(ArrayNode fields) {
        ArrayNode repaired = Jacksons.mapper().createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode field : fields) {
            ObjectNode repairedField = ((ObjectNode) field).deepCopy();
            String name = repairedField.path("name").asText("").trim();
            String type = repairedField.path("type").asText("Text").trim();

            if (name.isBlank()) {
                continue; // 跳过无名字段
            }
            if (seen.contains(name)) {
                continue; // 跳过重复
            }
            seen.add(name);

            // 修复：Collaborator 必须 required=false
            if ("Collaborator".equals(type)) {
                repairedField.put("required", false);
            }

            // 修复：选择字段必须给 option_values
            if (OPTION_REQUIRED_TYPES.contains(type)) {
                ArrayNode opts = asArray(repairedField.path("option_values"));
                if (opts.isEmpty()) {
                    opts = repairedField.putArray("option_values");
                    opts.add("选项1");
                    opts.add("选项2");
                }
            }

            // 确保存在 option_values 数组（即使是Text也有空数组）
            if (!repairedField.has("option_values")) {
                repairedField.putArray("option_values");
            }

            repaired.add(repairedField);
        }
        return repaired;
    }

    private ObjectNode mergeSkeleton(ObjectNode skeleton, ObjectNode fieldsByWorksheet) {
        ObjectNode merged = Jacksons.mapper().createObjectNode();
        merged.put("app_name", skeleton.path("app_name").asText(""));
        merged.put("summary", skeleton.path("summary").asText(""));
        ArrayNode worksheets = merged.putArray("worksheets");
        for (JsonNode worksheet : asArray(skeleton.path("worksheets"))) {
            String worksheetName = worksheet.path("name").asText("");
            ObjectNode out = worksheets.addObject();
            out.put("name", worksheetName);
            out.put("purpose", worksheet.path("purpose").asText(""));
            ArrayNode fields = out.putArray("fields");
            for (JsonNode coreField : asArray(worksheet.path("core_fields"))) {
                fields.add(toFieldNode(coreField, ""));
            }
            for (JsonNode extraField : asArray(fieldsByWorksheet.path(worksheetName))) {
                fields.add(toFieldNode(extraField, extraField.path("relation_target").asText("")));
            }
            for (JsonNode relation : asArray(skeleton.path("relationships"))) {
                if (worksheetName.equals(relation.path("to").asText(""))) {
                    ObjectNode field = fields.addObject();
                    field.put("name", relation.path("field").asText(relation.path("from").asText("")));
                    field.put("type", "Relation");
                    field.put("required", false);
                    field.put("description", relation.path("description").asText(""));
                    field.put("relation_target", relation.path("from").asText(""));
                    field.putArray("option_values");
                }
            }
            ArrayNode dependsOn = out.putArray("depends_on");
            for (JsonNode dependency : asArray(worksheet.path("depends_on"))) {
                dependsOn.add(dependency.asText(""));
            }
        }
        merged.set("relationships", skeleton.path("relationships").deepCopy());
        merged.set("creation_order", skeleton.path("creation_order").deepCopy());
        ArrayNode notes = merged.putArray("notes");
        notes.add("使用 layered 模式生成（骨架规划 + 逐表字段细化）");
        return merged;
    }

    private ObjectNode toFieldNode(JsonNode source, String relationTarget) {
        ObjectNode field = Jacksons.mapper().createObjectNode();
        field.put("name", source.path("name").asText(""));
        field.put("type", source.path("type").asText("Text"));
        field.put("required", source.path("required").asBoolean(false));
        field.put("description", source.path("description").asText(""));
        field.put("relation_target", relationTarget);
        ArrayNode optionValues = field.putArray("option_values");
        for (JsonNode option : asArray(source.path("option_values"))) {
            optionValues.add(option.asText(""));
        }
        if (source.has("unit")) {
            field.put("unit", source.path("unit").asText(""));
        }
        if (source.has("dot")) {
            field.put("dot", source.path("dot").asText(""));
        }
        return field;
    }

    private void validateMerged(ObjectNode merged) {
        ArrayNode worksheets = asArray(merged.path("worksheets"));
        if (worksheets.isEmpty()) {
            throw new IllegalArgumentException("Merged worksheet plan must contain worksheets");
        }
        ArrayNode creationOrder = asArray(merged.path("creation_order"));
        Set<String> names = new HashSet<>();
        for (JsonNode worksheet : worksheets) {
            names.add(worksheet.path("name").asText(""));
        }
        for (String name : names) {
            boolean present = false;
            for (JsonNode order : creationOrder) {
                if (name.equals(order.asText(""))) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                throw new IllegalArgumentException("creation_order missing worksheet: " + name);
            }
        }
    }

    private String buildSkeletonPrompt(String appName, String businessContext, String requirements, String language) {
        return """
                你是企业应用架构师。请为应用“%s”设计工作表结构骨架，输出严格 JSON。

                业务背景：
                %s

                额外要求：
                %s

                任务：
                1. 规划工作表（表名 + 用途）
                2. 每个工作表提供 1-3 个核心字段
                3. 定义工作表之间的 1-1 / 1-N 关系

                输出严格 JSON：
                {
                  "app_name": "%s",
                  "summary": "一句话概述",
                  "worksheets": [
                    {
                      "name": "工作表名",
                      "purpose": "一句话用途",
                      "core_fields": [
                        {
                          "name": "字段名",
                          "type": "Text|Number|Money|SingleSelect|MultipleSelect|Dropdown|Date|DateTime|Collaborator|Phone|Email|RichText|Attachment|Rating|Checkbox|Area",
                          "required": true,
                          "option_values": ["仅选择字段需要"]
                        }
                      ],
                      "depends_on": ["依赖的工作表"]
                    }
                  ],
                  "relationships": [
                    {"from": "表A", "field": "关联字段名", "to": "表B", "cardinality": "1-1|1-N", "description": "关系说明"}
                  ],
                  "creation_order": ["所有工作表名"]
                }

                约束：
                1) core_fields 中不要包含 Relation
                2) 第一核心字段必须是 Text 且 required=true
                3) creation_order 必须包含所有 worksheets.name
                4) 禁止 N-N，只允许 1-1 / 1-N
                5) 选择字段必须给 option_values
                6) Collaborator.required 必须为 false
                7) 只输出 JSON
                """.formatted(appName, businessContext, requirements, appName);
    }

    private String buildFieldsPrompt(
            String worksheetName,
            String purpose,
            ArrayNode existingFields,
            ArrayNode worksheetSummaries,
            String language) throws Exception {
        return """
                你是企业应用字段设计专家。请为工作表“%s”设计额外业务字段，只输出 JSON。

                工作表用途：
                %s

                已有字段（不要重复）：
                %s

                其他工作表（仅供理解上下文）：
                %s

                输出格式：
                {
                  "worksheetId": "",
                  "worksheetName": "%s",
                  "fields": [
                    {
                      "name": "字段名",
                      "type": "Text|Number|Money|SingleSelect|MultipleSelect|Dropdown|Date|DateTime|Collaborator|Phone|Email|RichText|Attachment|Rating|Checkbox|Area",
                      "required": true,
                      "description": "字段说明",
                      "option_values": ["选择字段必填"],
                      "unit": "可选",
                      "dot": "可选"
                    }
                  ]
                }

                约束：
                1) 不要输出 Relation
                2) 不要与已有字段重名
                3) 选择字段必须给 option_values
                4) Money/Number 可以给 unit 和 dot
                5) Collaborator.required 必须为 false
                6) 只输出 JSON
                """.formatted(
                worksheetName,
                purpose,
                Jacksons.mapper().writeValueAsString(existingFields),
                Jacksons.mapper().writeValueAsString(worksheetSummaries),
                worksheetName);
    }

    private ArrayNode asArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : Jacksons.mapper().createArrayNode();
    }

    private record WorksheetFieldsResult(String worksheetName, ArrayNode fields) {
    }
}
