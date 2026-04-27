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

import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.planner.IconPlanner;
import com.hap.automaker.core.executor.ExecuteOptions;
import com.hap.automaker.core.executor.IconCreator;

/**
 * 图标流水线服务
 *
 * 整合 IconPlanner + IconCreator
 * 实现 Wave 3: 图标规划与更新
 */
public final class IconPipelineService implements IconPipelineRunner {

    private final IconPlanner iconPlanner;
    private final IconCreator iconCreator;

    public IconPipelineService() {
        this(
            new IconPlanner(new HttpAiTextClient()),
            new IconCreator(new HapApiClient())
        );
    }

    IconPipelineService(
            IconPlanner iconPlanner,
            IconCreator iconCreator) {
        this.iconPlanner = iconPlanner;
        this.iconCreator = iconCreator;
    }

    @Override
    public IconPipelineResult run(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path iconPlanOutput,
            Path iconResultOutput) throws Exception {

        OffsetDateTime startedAt = OffsetDateTime.now();

        // 读取工作表创建结果
        JsonNode worksheetResult = Jacksons.mapper().readTree(worksheetCreateResult.toFile());

        // 构建 WorksheetInfo 列表
        List<IconPlanner.WorksheetInfo> worksheets = buildWorksheetInfos(worksheetResult);

        // Step 1: 规划图标
        IconPlanner.Input planInput = new IconPlanner.Input(appId, worksheets);
        IconPlanner.Output planOutput = iconPlanner.plan(planInput);

        // 保存图标规划结果
        ObjectNode planJson = buildIconPlanJson(appId, planOutput);
        Files.createDirectories(iconPlanOutput.getParent());
        Jacksons.mapper().writeValue(iconPlanOutput.toFile(), planJson);

        // Step 2: 更新图标
        IconCreator.Result createResult = iconCreator.execute(
            planOutput,
            new ExecuteOptions(false, false)
        );

        // 构建结果
        ObjectNode resultJson = buildIconResultJson(appId, createResult);
        Files.createDirectories(iconResultOutput.getParent());
        Jacksons.mapper().writeValue(iconResultOutput.toFile(), resultJson);

        return new IconPipelineResult(
            iconPlanOutput,
            iconResultOutput,
            planOutput.getMappings().size(),
            createResult.getSuccessCount(),
            createResult.getFailedCount(),
            startedAt,
            OffsetDateTime.now()
        );
    }

    private List<IconPlanner.WorksheetInfo> buildWorksheetInfos(JsonNode worksheetResult) {
        List<IconPlanner.WorksheetInfo> result = new ArrayList<>();

        // 从 name_to_worksheet_id 映射中获取工作表ID和名称
        JsonNode mappingNode = worksheetResult.path("name_to_worksheet_id");
        if (mappingNode.isObject()) {
            Iterator<java.util.Map.Entry<String, JsonNode>> fields = mappingNode.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> entry = fields.next();
                String wsName = entry.getKey();
                String wsId = entry.getValue().asText();
                result.add(new IconPlanner.WorksheetInfo(wsId, wsName));
            }
        }

        // 如果没有映射，尝试从 created_worksheets 数组获取
        if (result.isEmpty()) {
            ArrayNode worksheets = (ArrayNode) worksheetResult.path("created_worksheets");
            Iterator<JsonNode> elements = worksheets.elements();
            while (elements.hasNext()) {
                JsonNode ws = elements.next();
                String wsId = ws.path("worksheetId").asText();
                String wsName = ws.path("name").asText();
                if (!wsId.isEmpty()) {
                    result.add(new IconPlanner.WorksheetInfo(wsId, wsName));
                }
            }
        }

        return result;
    }

    private ObjectNode buildIconPlanJson(String appId, IconPlanner.Output planOutput) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("schemaVersion", "icon_plan_v1");

        ArrayNode mappingsArray = result.putArray("mappings");
        for (IconPlanner.IconMapping mapping : planOutput.getMappings()) {
            ObjectNode mappingNode = mappingsArray.addObject();
            mappingNode.put("workSheetId", mapping.getWorkSheetId());
            mappingNode.put("workSheetName", mapping.getWorkSheetName());
            mappingNode.put("icon", mapping.getIcon());
            mappingNode.put("reason", mapping.getReason());
        }

        return result;
    }

    private ObjectNode buildIconResultJson(String appId, IconCreator.Result createResult) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("createdAt", OffsetDateTime.now().toString());
        result.put("totalPlanned", createResult.getResults().size());
        result.put("successCount", createResult.getSuccessCount());
        result.put("failedCount", createResult.getFailedCount());
        result.put("success", createResult.isSuccess());

        ArrayNode updatedArray = result.putArray("updated");
        for (IconCreator.UpdateResult updateResult : createResult.getResults()) {
            if (updateResult.isSuccess()) {
                ObjectNode node = updatedArray.addObject();
                node.put("workSheetId", updateResult.getWorkSheetId());
                node.put("workSheetName", updateResult.getWorkSheetName());
                node.put("icon", updateResult.getIcon());
            }
        }

        ArrayNode failedArray = result.putArray("failed");
        for (IconCreator.UpdateResult updateResult : createResult.getResults()) {
            if (!updateResult.isSuccess()) {
                ObjectNode node = failedArray.addObject();
                node.put("workSheetId", updateResult.getWorkSheetId());
                node.put("workSheetName", updateResult.getWorkSheetName());
                node.put("error", updateResult.getError());
            }
        }

        return result;
    }

    public record IconPipelineResult(
            Path planOutputPath,
            Path resultOutputPath,
            int totalMappings,
            int successCount,
            int failedCount,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt
    ) {
        public ObjectNode summary() {
            ObjectNode node = Jacksons.mapper().createObjectNode();
            node.put("planOutputPath", planOutputPath.toString());
            node.put("resultOutputPath", resultOutputPath.toString());
            node.put("totalMappings", totalMappings);
            node.put("successCount", successCount);
            node.put("failedCount", failedCount);
            node.put("durationMs", endedAt.toInstant().toEpochMilli() - startedAt.toInstant().toEpochMilli());
            return node;
        }
    }
}
