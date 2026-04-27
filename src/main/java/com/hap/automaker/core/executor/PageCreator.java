package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * 页面创建执行器
 *
 * Python 对应: executors/create_pages_from_plan.py
 *
 * 职责:
 * - 根据 PagePlanner 的规划创建自定义页面
 * - 调用 API 创建页面结构
 * - 为页面添加图表（调用 PageChartCreator）
 */
public class PageCreator implements Executor<PageCreator.Input, PageCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(PageCreator.class);

    private final HapApiClient apiClient;
    private final ExecutorService executor;

    public PageCreator(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "PageCreator";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        Map<String, String> pageIdMap = new ConcurrentHashMap<>();
        List<PageCreationDetail> details = new CopyOnWriteArrayList<>();

        try {
            logger.info("Creating {} custom pages", input.getPages().size());

            for (PageDefinition page : input.getPages()) {
                try {
                    String pageId = createPage(page, input);
                    pageIdMap.put(page.getName(), pageId);
                    details.add(new PageCreationDetail(
                        page.getName(),
                        pageId,
                        true,
                        null,
                        page.getCharts() != null ? page.getCharts().size() : 0
                    ));

                    logger.info("✓ Page created: {} (ID: {})", page.getName(), pageId);

                } catch (Exception e) {
                    String errorMsg = "Failed to create page [" + page.getName() + "]: " + e.getMessage();
                    details.add(new PageCreationDetail(
                        page.getName(),
                        null,
                        false,
                        errorMsg,
                        0
                    ));

                    if (input.isFailFast()) {
                        throw new ExecutorException(getName(), errorMsg, e);
                    }
                    logger.error("✗ {}", errorMsg);
                }
            }

            boolean allSuccess = details.stream().allMatch(PageCreationDetail::isSuccess);
            return new Output(allSuccess, pageIdMap, details, null);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to create pages", e);
        }
    }

    private String createPage(PageDefinition page, Input input) throws Exception {
        if (input.isDryRun()) {
            logger.info("[DryRun] Would create page: {} (type={})", page.getName(), page.getType());
            return "dry-run-page-id";
        }

        // 调用 API 创建页面
        JsonNode response = apiClient.saveReportPage(
            input.getAppId(),
            buildPageConfig(page)
        );

        // 从响应中提取 pageId
        String pageId = extractPageId(response);

        if (pageId == null || pageId.isEmpty()) {
            throw new ExecutorException(getName(), "API response missing pageId");
        }

        return pageId;
    }

    private JsonNode buildPageConfig(PageDefinition page) {
        var mapper = com.hap.automaker.config.Jacksons.mapper();
        var config = mapper.createObjectNode();

        config.put("name", page.getName());
        config.put("type", page.getType() != null ? page.getType() : "dashboard");
        config.put("description", page.getDescription() != null ? page.getDescription() : "");

        // 页面布局配置
        var layout = config.putObject("layout");
        layout.put("columns", 2); // 默认两列布局
        layout.put("rowHeight", 300);

        // 预留图表位置（由 PageChartCreator 填充）
        config.putArray("charts");

        return config;
    }

    private String extractPageId(JsonNode response) {
        if (response == null) return null;

        JsonNode data = response.path("data");
        if (!data.isMissingNode()) {
            String id = data.path("id").asText();
            if (!id.isEmpty()) return id;

            id = data.path("pageId").asText();
            if (!id.isEmpty()) return id;
        }

        String id = response.path("id").asText();
        if (!id.isEmpty()) return id;

        id = response.path("pageId").asText();
        if (!id.isEmpty()) return id;

        return null;
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 输入类 ==========
    public static class Input {
        private final String appId;
        private final List<PageDefinition> pages;
        private final boolean dryRun;
        private final boolean failFast;

        public Input(String appId, List<PageDefinition> pages, boolean dryRun, boolean failFast) {
            this.appId = appId;
            this.pages = pages;
            this.dryRun = dryRun;
            this.failFast = failFast;
        }

        public String getAppId() { return appId; }
        public List<PageDefinition> getPages() { return pages; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
    }

    public static class PageDefinition {
        private final String name;
        private final String type;
        private final String description;
        private final List<ChartDefinition> charts;

        public PageDefinition(String name, String type, String description, List<ChartDefinition> charts) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.charts = charts != null ? charts : List.of();
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public String getDescription() { return description; }
        public List<ChartDefinition> getCharts() { return charts; }
    }

    public static class ChartDefinition {
        private final String name;
        private final String worksheet;
        private final String chartType;
        private final String xField;
        private final String yField;

        public ChartDefinition(String name, String worksheet, String chartType, String xField, String yField) {
            this.name = name;
            this.worksheet = worksheet;
            this.chartType = chartType;
            this.xField = xField;
            this.yField = yField;
        }

        public String getName() { return name; }
        public String getWorksheet() { return worksheet; }
        public String getChartType() { return chartType; }
        public String getXField() { return xField; }
        public String getYField() { return yField; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final boolean success;
        private final Map<String, String> pageIdMap;
        private final List<PageCreationDetail> details;
        private final String errorMessage;

        public Output(boolean success, Map<String, String> pageIdMap,
                      List<PageCreationDetail> details, String errorMessage) {
            this.success = success;
            this.pageIdMap = pageIdMap;
            this.details = details;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Map<String, String> getPageIdMap() { return pageIdMap; }
        public List<PageCreationDetail> getDetails() { return details; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class PageCreationDetail {
        private final String name;
        private final String pageId;
        private final boolean success;
        private final String errorMessage;
        private final int chartCount;

        public PageCreationDetail(String name, String pageId, boolean success,
                                   String errorMessage, int chartCount) {
            this.name = name;
            this.pageId = pageId;
            this.success = success;
            this.errorMessage = errorMessage;
            this.chartCount = chartCount;
        }

        public String getName() { return name; }
        public String getPageId() { return pageId; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public int getChartCount() { return chartCount; }
    }
}
