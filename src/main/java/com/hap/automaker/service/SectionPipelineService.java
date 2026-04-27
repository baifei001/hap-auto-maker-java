package com.hap.automaker.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.executor.NaviStyleUpdater;
import com.hap.automaker.core.executor.SectionCreator;
import com.hap.automaker.core.planner.SectionPlanner;

import com.hap.automaker.util.LoggerFactory;
import org.slf4j.Logger;

/**
 * 分组流水线服务
 *
 * 整合 SectionPlanner + SectionCreator + NaviStyleUpdater
 * 实现 Wave 2.5: 分组规划、创建、导航样式更新
 */
public final class SectionPipelineService implements SectionPipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SectionPipelineService.class);

    private final SectionPlanner sectionPlanner;
    private final SectionCreator sectionCreator;
    private final NaviStyleUpdater naviStyleUpdater;
    private final HapApiClient apiClient;

    public SectionPipelineService() {
        this(
            new SectionPlanner(new HttpAiTextClient()),
            new SectionCreator(new HapApiClient(), 4),
            new NaviStyleUpdater(new HapApiClient()),
            new HapApiClient()
        );
    }

    SectionPipelineService(
            SectionPlanner sectionPlanner,
            SectionCreator sectionCreator,
            NaviStyleUpdater naviStyleUpdater,
            HapApiClient apiClient) {
        this.sectionPlanner = sectionPlanner;
        this.sectionCreator = sectionCreator;
        this.naviStyleUpdater = naviStyleUpdater;
        this.apiClient = apiClient;
    }

    @Override
    public SectionPipelineResult run(
            Path repoRoot,
            String appId,
            Path appAuth,
            Path worksheetCreateResult,
            Path sectionPlanOutput,
            Path sectionResultOutput) throws Exception {

        OffsetDateTime startedAt = OffsetDateTime.now();

        // 读取工作表创建结果
        JsonNode worksheetResult = Jacksons.mapper().readTree(worksheetCreateResult.toFile());
        String appName = worksheetResult.path("appName").asText();

        // 构建 WorksheetInfo 列表
        List<SectionPlanner.WorksheetInfo> worksheets = buildWorksheetInfos(worksheetResult);

        // Step 1: 规划分组
        SectionPlanner.Input planInput = new SectionPlanner.Input(
            appName,
            worksheets,
            "zh"
        );
        SectionPlanner.Output planOutput = sectionPlanner.plan(planInput);

        // 保存分组规划结果
        ObjectNode planJson = buildSectionPlanJson(appId, appName, planOutput);
        Files.createDirectories(sectionPlanOutput.getParent());
        Jacksons.mapper().writeValue(sectionPlanOutput.toFile(), planJson);

        // Step 2: 创建分组并移动工作表
        ObjectNode createResult = createSectionsAndMoveWorksheets(appId, planOutput, worksheetResult);

        // Step 3: 更新导航样式
        int dashboardSectionCount = planOutput.getSections().size();
        // Let NaviStyleUpdater decide the style based on sectionCount (null = auto)
        naviStyleUpdater.execute(new NaviStyleUpdater.Input(appId, dashboardSectionCount, null, false, false));

        // 计算导航样式用于结果记录 (1=侧边导航, 0=顶部导航)
        int pcNaviStyle = dashboardSectionCount > 3 ? 0 : 1;

        // 构建结果
        createResult.put("naviStyle", pcNaviStyle);
        createResult.put("naviStyleName", pcNaviStyle == 0 ? "顶部导航" : "侧边导航");

        Files.createDirectories(sectionResultOutput.getParent());
        Jacksons.mapper().writeValue(sectionResultOutput.toFile(), createResult);

        return new SectionPipelineResult(
            sectionPlanOutput,
            sectionResultOutput,
            planOutput.getSections().size(),
            createResult.path("createdSections").asInt(0),
            startedAt,
            OffsetDateTime.now()
        );
    }

    private List<SectionPlanner.WorksheetInfo> buildWorksheetInfos(JsonNode worksheetResult) {
        List<SectionPlanner.WorksheetInfo> result = new ArrayList<>();
        ArrayNode worksheets = (ArrayNode) worksheetResult.path("created_worksheets");
        Iterator<JsonNode> elements = worksheets.elements();
        while (elements.hasNext()) {
            JsonNode ws = elements.next();
            result.add(new SectionPlanner.WorksheetInfo(
                ws.path("name").asText(),
                ws.path("purpose").asText("")
            ));
        }
        return result;
    }

    private ObjectNode buildSectionPlanJson(String appId, String appName, SectionPlanner.Output planOutput) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("appName", appName);
        result.put("schemaVersion", "section_plan_v1");

        ArrayNode sectionsArray = result.putArray("sections");
        for (SectionPlanner.SectionPlan section : planOutput.getSections()) {
            ObjectNode sectionNode = sectionsArray.addObject();
            sectionNode.put("name", section.getName());
            ArrayNode worksheetsArray = sectionNode.putArray("worksheets");
            for (String wsName : section.getWorksheets()) {
                worksheetsArray.add(wsName);
            }
        }

        return result;
    }

    private ObjectNode createSectionsAndMoveWorksheets(
            String appId,
            SectionPlanner.Output planOutput,
            JsonNode worksheetResult) throws Exception {

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("createdAt", OffsetDateTime.now().toString());

        // 获取默认分组 ID（第一个分组）
        String defaultSectionId = getDefaultSectionId(appId);
        logger.info("Default section ID: {}", defaultSectionId);

        // 构建工作表名称到 worksheetId 的映射
        Map<String, String> worksheetNameToId = new HashMap<>();
        ArrayNode createdWorksheets = (ArrayNode) worksheetResult.path("created_worksheets");
        for (JsonNode ws : createdWorksheets) {
            String name = ws.path("name").asText();
            String id = ws.path("worksheetId").asText();
            if (!name.isEmpty() && !id.isEmpty()) {
                worksheetNameToId.put(name, id);
            }
        }

        int createdCount = 0;
        ArrayNode createdSections = result.putArray("sections");
        Map<String, String> sectionNameToId = new HashMap<>();
        List<SectionPlanner.SectionPlan> sections = planOutput.getSections();

        // Step 1: 创建/重命名分组
        boolean firstSection = true;
        for (int i = 0; i < sections.size(); i++) {
            SectionPlanner.SectionPlan section = sections.get(i);
            String sectionName = section.getName();

            // 第一个分组是仪表盘，跳过
            if (i == 0 && section.getWorksheets().isEmpty()) {
                ObjectNode dashboardNode = createdSections.addObject();
                dashboardNode.put("name", sectionName);
                dashboardNode.put("skipped", true);
                dashboardNode.put("reason", "dashboard_placeholder");
                continue;
            }

            String sectionId;
            try {
                if (firstSection && defaultSectionId != null && !defaultSectionId.isEmpty()) {
                    // 复用默认分组：重命名
                    sectionId = defaultSectionId;
                    renameSection(appId, sectionId, sectionName);
                    logger.info("Reused default section -> '{}' ({})", sectionName, sectionId);
                    firstSection = false;
                } else {
                    // 创建新分组
                    sectionId = createSection(appId, sectionName);
                    logger.info("Created section '{}' -> {}", sectionName, sectionId);
                }

                sectionNameToId.put(sectionName, sectionId);
                createdCount++;

                ObjectNode sectionNode = createdSections.addObject();
                sectionNode.put("name", sectionName);
                sectionNode.put("sectionId", sectionId);
                sectionNode.put("created", true);
                sectionNode.put("worksheets", section.getWorksheets().size());
            } catch (Exception e) {
                logger.error("Failed to create section '{}': {}", sectionName, e.getMessage());
                ObjectNode sectionNode = createdSections.addObject();
                sectionNode.put("name", sectionName);
                sectionNode.put("created", false);
                sectionNode.put("error", e.getMessage());
            }
        }

        // Step 2: 移动工作表到对应分组
        int movedCount = 0;
        ArrayNode moveResults = result.putArray("moveResults");

        for (SectionPlanner.SectionPlan section : sections) {
            String sectionId = sectionNameToId.get(section.getName());
            if (sectionId == null) {
                continue;
            }

            for (String wsName : section.getWorksheets()) {
                String worksheetId = worksheetNameToId.get(wsName);
                if (worksheetId == null) {
                    logger.warn("Worksheet '{}' not found in creation result", wsName);
                    continue;
                }

                try {
                    moveWorksheetToSection(appId, sectionId, worksheetId);
                    logger.info("Moved worksheet '{}' to section '{}'", wsName, section.getName());
                    movedCount++;

                    ObjectNode moveResult = moveResults.addObject();
                    moveResult.put("worksheetName", wsName);
                    moveResult.put("sectionName", section.getName());
                    moveResult.put("success", true);
                } catch (Exception e) {
                    logger.error("Failed to move worksheet '{}': {}", wsName, e.getMessage());
                    ObjectNode moveResult = moveResults.addObject();
                    moveResult.put("worksheetName", wsName);
                    moveResult.put("sectionName", section.getName());
                    moveResult.put("success", false);
                    moveResult.put("error", e.getMessage());
                }
            }
        }

        result.put("createdSections", createdCount);
        result.put("totalSections", sections.size());
        result.put("movedWorksheets", movedCount);
        result.put("dryRun", false);

        return result;
    }

    private String getDefaultSectionId(String appId) throws Exception {
        try {
            JsonNode response = apiClient.postWeb("/api/HomeApp/GetApp",
                Jacksons.mapper().createObjectNode()
                    .put("appId", appId)
                    .put("getSection", true));

            if (response.path("state").asInt() != 1) {
                return "";
            }

            JsonNode sections = response.path("data").path("sections");
            if (sections.isArray() && sections.size() > 0) {
                return sections.get(0).path("appSectionId").asText("");
            }
        } catch (Exception e) {
            logger.warn("Failed to get default section ID: {}", e.getMessage());
        }
        return "";
    }

    private String createSection(String appId, String name) throws Exception {
        // 调用 AddAppSection 创建分组
        JsonNode response = apiClient.postWeb("/api/HomeApp/AddAppSection",
            Jacksons.mapper().createObjectNode()
                .put("appId", appId)
                .put("name", name));

        if (response.path("state").asInt() != 1) {
            throw new RuntimeException("AddAppSection failed: " + response);
        }

        String sectionId = response.path("data").path("data").asText("");
        if (sectionId.isEmpty()) {
            throw new RuntimeException("AddAppSection returned empty sectionId");
        }

        // 重命名确保名称正确
        renameSection(appId, sectionId, name);

        return sectionId;
    }

    private void renameSection(String appId, String sectionId, String name) throws Exception {
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            JsonNode response = apiClient.postWeb("/api/HomeApp/UpdateAppSectionName",
                Jacksons.mapper().createObjectNode()
                    .put("appId", appId)
                    .put("appSectionId", sectionId)
                    .put("name", name));

            if (response.path("state").asInt() == 1) {
                return;
            }

            logger.warn("UpdateAppSectionName attempt {} failed: {}", attempt + 1, response);
            if (attempt == maxRetries - 1) {
                throw new RuntimeException("UpdateAppSectionName failed after " + maxRetries + " attempts");
            }
        }
    }

    private void moveWorksheetToSection(String appId, String sectionId, String worksheetId) throws Exception {
        // 获取工作表当前所在分组
        String sourceSectionId = getWorksheetCurrentSectionId(appId, worksheetId);

        // 已在目标分组，无需移动
        if (sourceSectionId != null && sourceSectionId.equals(sectionId)) {
            return;
        }

        // 使用 RemoveWorkSheetAscription API 移动工作表
        JsonNode worksheetInfo = Jacksons.mapper().createObjectNode()
            .put("workSheetId", worksheetId)
            .put("type", 0)
            .put("icon", "table")
            .put("iconColor", "#757575");

        JsonNode payload = Jacksons.mapper().createObjectNode()
            .put("sourceAppId", appId)
            .put("resultAppId", appId)
            .put("sourceAppSectionId", sourceSectionId != null ? sourceSectionId : sectionId)
            .put("ResultAppSectionId", sectionId)
            .set("workSheetsInfo", Jacksons.mapper().createArrayNode().add(worksheetInfo));

        JsonNode response = apiClient.postWeb("/api/AppManagement/RemoveWorkSheetAscription", payload);

        if (response.path("state").asInt() != 1) {
            throw new RuntimeException("Move worksheet failed: " + response);
        }
    }

    private String getWorksheetCurrentSectionId(String appId, String worksheetId) throws Exception {
        try {
            JsonNode response = apiClient.postWeb("/api/HomeApp/GetApp",
                Jacksons.mapper().createObjectNode()
                    .put("appId", appId)
                    .put("getSection", true));

            if (response.path("state").asInt() != 1) {
                return null;
            }

            JsonNode sections = response.path("data").path("sections");
            if (!sections.isArray()) {
                return null;
            }

            for (JsonNode section : sections) {
                String sectionId = section.path("appSectionId").asText("");
                JsonNode worksheetInfo = section.path("workSheetInfo");
                if (worksheetInfo.isArray()) {
                    for (JsonNode ws : worksheetInfo) {
                        if (ws.path("workSheetId").asText("").equals(worksheetId)) {
                            return sectionId;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to get worksheet current section: {}", e.getMessage());
        }
        return null;
    }

    public record SectionPipelineResult(
            Path planOutputPath,
            Path resultOutputPath,
            int totalSections,
            int createdSections,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt
    ) {
        public ObjectNode summary() {
            ObjectNode node = Jacksons.mapper().createObjectNode();
            node.put("planOutputPath", planOutputPath.toString());
            node.put("resultOutputPath", resultOutputPath.toString());
            node.put("totalSections", totalSections);
            node.put("createdSections", createdSections);
            node.put("durationMs", endedAt.toInstant().toEpochMilli() - startedAt.toInstant().toEpochMilli());
            return node;
        }
    }
}
