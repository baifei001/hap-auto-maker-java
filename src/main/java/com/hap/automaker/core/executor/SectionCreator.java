package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * 分组创建执行器
 *
 * Python 对应: create_sections_from_plan.py
 *
 * 职责:
 * 1. 根据 sections_plan 创建工作表分组（CreateSection）
 * 2. 将工作表移动到对应分组（MoveWorksheetToSection）
 *
 * API 调用:
 * - POST /api/AppManagement/CreateSection
 * - POST /api/AppManagement/MoveWorksheetToSection
 */
public class SectionCreator implements Executor<SectionCreator.Input, SectionCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(SectionCreator.class);

    private final HapApiClient apiClient;
    private final ExecutorService executor;

    public SectionCreator(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "SectionCreator";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        Map<String, String> sectionIdMap = new ConcurrentHashMap<>();
        List<SectionCreationDetail> details = new CopyOnWriteArrayList<>();

        String appId = input.getAppId();
        Map<String, String> worksheetIdMap = input.getWorksheetIdMap();

        try {
            // Phase 1: 创建所有分组
            logger.info("Phase 1: 创建 {} 个分组", input.getSections().size());

            int row = 1;
            for (SectionDefinition section : input.getSections()) {
                try {
                    if (input.isDryRun()) {
                        logger.info("[DryRun] Would create section: {}", section.getName());
                        sectionIdMap.put(section.getName(), "dry-run-section-id-" + row);
                        details.add(new SectionCreationDetail(
                            section.getName(), "dry-run-section-id-" + row, true, null, 0));
                        continue;
                    }

                    // 调用 API 创建分组
                    JsonNode response = apiClient.createSection(appId, section.getName(), row);

                    // 从响应中提取 sectionId
                    String sectionId = extractSectionId(response);

                    if (sectionId == null || sectionId.isEmpty()) {
                        throw new ExecutorException(getName(), "API response missing sectionId for: " + section.getName());
                    }

                    sectionIdMap.put(section.getName(), sectionId);
                    details.add(new SectionCreationDetail(
                        section.getName(), sectionId, true, null, 0));

                    logger.info("✓ 分组创建成功: {} (ID: {})", section.getName(), sectionId);

                } catch (Exception e) {
                    String errorMsg = "创建分组失败 [" + section.getName() + "]: " + e.getMessage();
                    details.add(new SectionCreationDetail(
                        section.getName(), null, false, errorMsg, 0));

                    if (input.isFailFast()) {
                        throw new ExecutorException(getName(), errorMsg, e);
                    }
                    logger.error("✗ {}", errorMsg);
                }
                row++;
            }

            // Phase 2: 将工作表移动到对应分组
            logger.info("\nPhase 2: 移动工作表到分组");

            int movedCount = 0;
            int rowCounter = 1;

            for (SectionDefinition section : input.getSections()) {
                String sectionId = sectionIdMap.get(section.getName());
                if (sectionId == null || sectionId.startsWith("dry-run")) {
                    continue; // 分组创建失败或者是仪表盘分组（空）
                }

                // 跳过仪表盘分组（worksheets 为空）
                if (section.getWorksheets() == null || section.getWorksheets().isEmpty()) {
                    continue;
                }

                for (String worksheetName : section.getWorksheets()) {
                    String worksheetId = worksheetIdMap.get(worksheetName);
                    if (worksheetId == null) {
                        logger.warn("⚠ 工作表未找到: {}，跳过移动", worksheetName);
                        continue;
                    }

                    if (input.isDryRun()) {
                        logger.info("[DryRun] Would move worksheet '{}' to section '{}'",
                            worksheetName, section.getName());
                        movedCount++;
                        continue;
                    }

                    try {
                        apiClient.moveWorksheetToSection(appId, sectionId, worksheetId, rowCounter);
                        logger.info("  ✓ 工作表移动成功: {} -> {}", worksheetName, section.getName());
                        movedCount++;
                    } catch (Exception e) {
                        String errorMsg = "移动工作表失败 [" + worksheetName + "]: " + e.getMessage();
                        logger.error("  ✗ {}", errorMsg);
                        if (input.isFailFast()) {
                            throw new ExecutorException(getName(), errorMsg, e);
                        }
                    }
                    rowCounter++;
                }
            }

            logger.info("\n移动完成: {} 个工作表已分配到分组", movedCount);

            boolean allSuccess = details.stream().allMatch(SectionCreationDetail::isSuccess);
            return new Output(allSuccess, sectionIdMap, details, null);

        } catch (Exception e) {
            if (e instanceof ExecutorException) {
                throw (ExecutorException) e;
            }
            throw new ExecutorException(getName(), "Failed to create sections", e);
        }
    }

    private String extractSectionId(JsonNode response) {
        if (response == null) return null;

        // 尝试不同的响应格式
        JsonNode data = response.path("data");
        if (!data.isMissingNode()) {
            String id = data.path("id").asText();
            if (!id.isEmpty()) return id;

            id = data.path("sectionId").asText();
            if (!id.isEmpty()) return id;
        }

        String id = response.path("id").asText();
        if (!id.isEmpty()) return id;

        id = response.path("sectionId").asText();
        if (!id.isEmpty()) return id;

        return null;
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 输入类 ==========
    public static class Input {
        private final String appId;
        private final List<SectionDefinition> sections;
        private final Map<String, String> worksheetIdMap; // 工作表名称 -> ID
        private final boolean dryRun;
        private final boolean failFast;

        public Input(String appId, List<SectionDefinition> sections,
                     Map<String, String> worksheetIdMap, boolean dryRun, boolean failFast) {
            this.appId = appId;
            this.sections = sections != null ? sections : List.of();
            this.worksheetIdMap = worksheetIdMap != null ? worksheetIdMap : Map.of();
            this.dryRun = dryRun;
            this.failFast = failFast;
        }

        public String getAppId() { return appId; }
        public List<SectionDefinition> getSections() { return sections; }
        public Map<String, String> getWorksheetIdMap() { return worksheetIdMap; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
    }

    public static class SectionDefinition {
        private final String name;
        private final List<String> worksheets;

        public SectionDefinition(String name, List<String> worksheets) {
            this.name = name;
            this.worksheets = worksheets != null ? worksheets : List.of();
        }

        public String getName() { return name; }
        public List<String> getWorksheets() { return worksheets; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final boolean success;
        private final Map<String, String> sectionIdMap;
        private final List<SectionCreationDetail> details;
        private final String errorMessage;

        public Output(boolean success, Map<String, String> sectionIdMap,
                      List<SectionCreationDetail> details, String errorMessage) {
            this.success = success;
            this.sectionIdMap = sectionIdMap;
            this.details = details;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Map<String, String> getSectionIdMap() { return sectionIdMap; }
        public List<SectionCreationDetail> getDetails() { return details; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class SectionCreationDetail {
        private final String name;
        private final String sectionId;
        private final boolean success;
        private final String errorMessage;
        private final int worksheetCount;

        public SectionCreationDetail(String name, String sectionId,
                                     boolean success, String errorMessage, int worksheetCount) {
            this.name = name;
            this.sectionId = sectionId;
            this.success = success;
            this.errorMessage = errorMessage;
            this.worksheetCount = worksheetCount;
        }

        public String getName() { return name; }
        public String getSectionId() { return sectionId; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public int getWorksheetCount() { return worksheetCount; }
    }
}
