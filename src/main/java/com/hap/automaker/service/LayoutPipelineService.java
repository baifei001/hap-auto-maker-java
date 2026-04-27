package com.hap.automaker.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.planner.LayoutPlanner;
import com.hap.automaker.core.executor.LayoutCreator;
import com.hap.automaker.core.executor.ExecuteOptions;

/**
 * 布局流水线服务
 *
 * 整合 LayoutPlanner + LayoutCreator
 * 实现 Wave 4: 工作表字段布局规划与应用
 */
public final class LayoutPipelineService implements LayoutPipelineRunner {

    private final LayoutPlanner layoutPlanner;
    private final LayoutCreator layoutCreator;

    public LayoutPipelineService() {
        this(
            new LayoutPlanner(),
            new LayoutCreator(new HapApiClient())
        );
    }

    LayoutPipelineService(
            LayoutPlanner layoutPlanner,
            LayoutCreator layoutCreator) {
        this.layoutPlanner = layoutPlanner;
        this.layoutCreator = layoutCreator;
    }

    @Override
    public LayoutPipelineResult run(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path layoutPlanOutput,
            Path layoutResultOutput) throws Exception {

        OffsetDateTime startedAt = OffsetDateTime.now();

        // 读取工作表创建结果
        JsonNode worksheetResult = Jacksons.mapper().readTree(worksheetCreateResult.toFile());

        // 读取工作表规划以获取字段信息
        Path worksheetPlanPath = worksheetCreateResult.getParent().resolve("worksheet_plan.json");
        if (!Files.exists(worksheetPlanPath)) {
            worksheetPlanPath = repoRoot.resolve("data/outputs/java_phase1/worksheet_plan.json");
        }
        JsonNode worksheetPlan = Files.exists(worksheetPlanPath)
            ? Jacksons.mapper().readTree(worksheetPlanPath.toFile())
            : Jacksons.mapper().createObjectNode();

        // 构建 WorksheetInfo 列表
        List<LayoutPlanner.WorksheetInfo> worksheets = buildWorksheetInfos(worksheetResult, worksheetPlan);

        // Step 1: 规划布局
        LayoutPlanner.Input planInput = new LayoutPlanner.Input(worksheets);
        LayoutPlanner.Output planOutput = layoutPlanner.plan(planInput);

        // 保存布局规划结果
        ObjectNode planJson = buildLayoutPlanJson(appId, planOutput);
        Files.createDirectories(layoutPlanOutput.getParent());
        Jacksons.mapper().writeValue(layoutPlanOutput.toFile(), planJson);

        // Step 2: 应用布局
        LayoutCreator.Result createResult = layoutCreator.execute(
            planOutput,
            new ExecuteOptions(false, false)
        );

        // 构建结果
        ObjectNode resultJson = buildLayoutResultJson(appId, createResult);
        Files.createDirectories(layoutResultOutput.getParent());
        Jacksons.mapper().writeValue(layoutResultOutput.toFile(), resultJson);

        return new LayoutPipelineResult(
            layoutPlanOutput,
            layoutResultOutput,
            planOutput.getLayouts().size(),
            createResult.getSuccessCount(),
            createResult.getFailedCount(),
            startedAt,
            OffsetDateTime.now()
        );
    }

    private List<LayoutPlanner.WorksheetInfo> buildWorksheetInfos(JsonNode worksheetResult, JsonNode worksheetPlan) {
        List<LayoutPlanner.WorksheetInfo> result = new ArrayList<>();
        ArrayNode worksheets = (ArrayNode) worksheetResult.path("created_worksheets");

        Iterator<JsonNode> elements = worksheets.elements();
        while (elements.hasNext()) {
            JsonNode ws = elements.next();
            String wsName = ws.path("name").asText();

            // 从 worksheet_plan.json 获取字段信息
            List<LayoutPlanner.FieldInfo> fields = buildFieldInfosFromPlan(wsName, worksheetPlan);

            result.add(new LayoutPlanner.WorksheetInfo(
                ws.path("worksheetId").asText(),
                wsName,
                getWorksheetPurpose(wsName, worksheetPlan),
                fields
            ));
        }

        return result;
    }

    private List<LayoutPlanner.FieldInfo> buildFieldInfosFromPlan(String worksheetName, JsonNode worksheetPlan) {
        List<LayoutPlanner.FieldInfo> fields = new ArrayList<>();

        JsonNode worksheetsNode = worksheetPlan.path("worksheets");
        if (!worksheetsNode.isArray()) {
            return fields;
        }

        // 找到对应工作表
        JsonNode targetWorksheet = null;
        for (JsonNode ws : worksheetsNode) {
            if (ws.path("name").asText().equals(worksheetName)) {
                targetWorksheet = ws;
                break;
            }
        }

        if (targetWorksheet == null) {
            return fields;
        }

        // 解析字段
        JsonNode fieldsNode = targetWorksheet.path("fields");
        if (fieldsNode.isArray()) {
            int fieldIndex = 0;
            for (JsonNode fieldNode : fieldsNode) {
                String fieldName = fieldNode.path("name").asText();
                String fieldType = fieldNode.path("type").asText("Text");
                String fieldId = "field_" + fieldIndex++;
                boolean isTitle = fieldName.contains("名称") || fieldName.contains("标题");

                fields.add(new LayoutPlanner.FieldInfo(fieldId, fieldName, fieldType, isTitle));
            }
        }

        return fields;
    }

    private String getWorksheetPurpose(String worksheetName, JsonNode worksheetPlan) {
        JsonNode worksheetsNode = worksheetPlan.path("worksheets");
        if (!worksheetsNode.isArray()) {
            return "";
        }

        for (JsonNode ws : worksheetsNode) {
            if (ws.path("name").asText().equals(worksheetName)) {
                return ws.path("purpose").asText("");
            }
        }

        return "";
    }

    private ObjectNode buildLayoutPlanJson(String appId, LayoutPlanner.Output planOutput) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("schemaVersion", "layout_plan_v1");

        ArrayNode layoutsArray = result.putArray("worksheets");
        for (LayoutPlanner.WorksheetLayout layout : planOutput.getLayouts()) {
            ObjectNode wsNode = layoutsArray.addObject();
            wsNode.put("worksheetId", layout.getWorksheetId());
            wsNode.put("worksheetName", layout.getWorksheetName());

            ArrayNode groupsArray = wsNode.putArray("groups");
            for (LayoutPlanner.FieldGroup group : layout.getLayout().getGroups()) {
                ObjectNode groupNode = groupsArray.addObject();
                if (group.getTitle() != null) {
                    groupNode.put("title", group.getTitle());
                }

                ArrayNode fieldsArray = groupNode.putArray("fields");
                for (LayoutPlanner.FieldInfo field : group.getFields()) {
                    ObjectNode fieldNode = fieldsArray.addObject();
                    fieldNode.put("controlId", field.getControlId());
                    fieldNode.put("controlName", field.getControlName());
                    fieldNode.put("type", field.getType());
                }
            }
        }

        return result;
    }

    private ObjectNode buildLayoutResultJson(String appId, LayoutCreator.Result createResult) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("createdAt", OffsetDateTime.now().toString());
        result.put("totalWorksheets", createResult.getResults().size());
        result.put("successCount", createResult.getSuccessCount());
        result.put("failedCount", createResult.getFailedCount());
        result.put("success", createResult.isSuccess());

        ArrayNode worksheetArray = result.putArray("worksheets");
        for (LayoutCreator.LayoutUpdateResult layoutResult : createResult.getResults()) {
            ObjectNode wsNode = worksheetArray.addObject();
            wsNode.put("worksheetId", layoutResult.getWorksheetId());
            wsNode.put("worksheetName", layoutResult.getWorksheetName());
            wsNode.put("success", layoutResult.isSuccess());
            wsNode.put("groupCount", layoutResult.getGroupCount());
            if (layoutResult.getError() != null) {
                wsNode.put("error", layoutResult.getError());
            }
        }

        return result;
    }

    public record LayoutPipelineResult(
            Path planOutputPath,
            Path resultOutputPath,
            int totalWorksheets,
            int successCount,
            int failedCount,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt
    ) {
        public ObjectNode summary() {
            ObjectNode node = Jacksons.mapper().createObjectNode();
            node.put("planOutputPath", planOutputPath.toString());
            node.put("resultOutputPath", resultOutputPath.toString());
            node.put("totalWorksheets", totalWorksheets);
            node.put("successCount", successCount);
            node.put("failedCount", failedCount);
            node.put("durationMs", endedAt.toInstant().toEpochMilli() - startedAt.toInstant().toEpochMilli());
            return node;
        }
    }
}
