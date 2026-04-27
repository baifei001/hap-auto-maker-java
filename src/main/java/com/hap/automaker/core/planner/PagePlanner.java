package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 页面规划器
 *
 * Python 对应: planners/plan_pages_gemini.py
 *
 * 职责:
 * - AI 规划自定义页面
 * - 确定页面类型（统计页面、仪表盘等）
 * - 规划页面中的图表布局
 */
public class PagePlanner implements Planner<PagePlanner.Input, PagePlanner.Output> {

    private final AiTextClient aiClient;
    private static final int MAX_RETRIES = 3;
    private static final Logger logger = LoggerFactory.getLogger(PagePlanner.class);

    public PagePlanner(AiTextClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public String getName() {
        return "PagePlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            // 构建规划提示词
            String prompt = buildPrompt(input);
            String json = callAiWithRetry(prompt);

            // 解析规划结果
            PagePlan plan = Jacksons.mapper().readValue(json, PagePlan.class);

            // 验证规划结果
            validatePlan(plan);

            logger.info("✓ 页面规划完成: {} 个页面", plan.getPages().size());

            return new Output(plan.getPages());

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan pages", e);
        }
    }

    private String buildPrompt(Input input) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an enterprise app architect. Plan custom pages for app \"").append(input.getAppName()).append("\".\n\n");

        sb.append("Worksheets available:");
        for (WorksheetSummary ws : input.getWorksheets()) {
            sb.append("\n  - ").append(ws.getName());
            if (ws.getPurpose() != null && !ws.getPurpose().isEmpty()) {
                sb.append(" (").append(ws.getPurpose()).append(")");
            }
        }

        sb.append("\n\nTask:\n");
        sb.append("1. Plan 1-3 custom pages (dashboard/analytics pages)");
        sb.append("\n2. For each page, specify the charts to display");
        sb.append("\n3. Charts should reference the available worksheets\n");

        sb.append("\nOutput strict JSON format:");
        sb.append("\n{");
        sb.append("\n  \"pages\": [");
        sb.append("\n    {");
        sb.append("\n      \"name\": \"仪表盘\",");
        sb.append("\n      \"type\": \"dashboard\",");
        sb.append("\n      \"description\": \"Overview of key metrics\",");
        sb.append("\n      \"charts\": [");
        sb.append("\n        {");
        sb.append("\n          \"name\": \"订单统计\",");
        sb.append("\n          \"worksheet\": \"订单表\",");
        sb.append("\n          \"chartType\": \"pie\",");
        sb.append("\n          \"xField\": \"状态\",");
        sb.append("\n          \"yField\": \"记录数量\"");
        sb.append("\n        }");
        sb.append("\n      ]");
        sb.append("\n    }");
        sb.append("\n  ]");
        sb.append("\n}");

        sb.append("\n\nRules:");
        sb.append("\n1. Page name should be 2-6 Chinese characters");
        sb.append("\n2. Page type can be: dashboard, analytics, report");
        sb.append("\n3. Chart type can be: pie, bar, line, number");
        sb.append("\n4. xField and yField should match worksheet fields");
        sb.append("\n5. Output JSON only, no markdown");

        return sb.toString();
    }

    private String callAiWithRetry(String prompt) throws Exception {
        Exception lastError = null;

        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                AiAuthConfig config = ConfigLoader.loadAiConfig();
                return aiClient.generateJson(prompt, config);
            } catch (Exception e) {
                lastError = e;
                logger.error("AI call failed (attempt {}/{}): {}", i + 1, MAX_RETRIES, e.getMessage());
                if (i < MAX_RETRIES - 1) {
                    Thread.sleep(1000 * (i + 1));
                }
            }
        }

        throw new PlanningException(getName(), "AI call failed after " + MAX_RETRIES + " retries", lastError);
    }

    private void validatePlan(PagePlan plan) throws PlanningException {
        if (plan.getPages() == null || plan.getPages().isEmpty()) {
            throw new PlanningException(getName(), "Plan contains no pages");
        }

        if (plan.getPages().size() > 5) {
            throw new PlanningException(getName(), "Too many pages: " + plan.getPages().size());
        }

        for (PageDefinition page : plan.getPages()) {
            if (page.getName() == null || page.getName().isEmpty()) {
                throw new PlanningException(getName(), "Page missing name");
            }

            if (page.getCharts() != null && page.getCharts().size() > 12) {
                logger.warn("[warn] Page '{}' has too many charts (>12)", page.getName());
            }
        }
    }

    // ========== 数据类 ==========
    public static class WorksheetSummary {
        private final String name;
        private final String purpose;

        public WorksheetSummary(String name, String purpose) {
            this.name = name;
            this.purpose = purpose;
        }

        public String getName() { return name; }
        public String getPurpose() { return purpose; }
    }

    public static class PageDefinition {
        private String name;
        private String type;
        private String description;
        private List<ChartDefinition> charts;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<ChartDefinition> getCharts() { return charts; }
        public void setCharts(List<ChartDefinition> charts) { this.charts = charts; }
    }

    public static class ChartDefinition {
        private String name;
        private String worksheet;
        private String chartType;
        private String xField;
        private String yField;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getWorksheet() { return worksheet; }
        public void setWorksheet(String worksheet) { this.worksheet = worksheet; }
        public String getChartType() { return chartType; }
        public void setChartType(String chartType) { this.chartType = chartType; }
        public String getXField() { return xField; }
        public void setXField(String xField) { this.xField = xField; }
        public String getYField() { return yField; }
        public void setYField(String yField) { this.yField = yField; }
    }

    public static class PagePlan {
        private List<PageDefinition> pages;

        public List<PageDefinition> getPages() { return pages; }
        public void setPages(List<PageDefinition> pages) { this.pages = pages; }
    }

    // ========== 输入输出类 ==========
    public static class Input {
        private final String appName;
        private final List<WorksheetSummary> worksheets;
        private final String language;

        public Input(String appName, List<WorksheetSummary> worksheets, String language) {
            this.appName = appName;
            this.worksheets = worksheets;
            this.language = language != null ? language : "zh";
        }

        public String getAppName() { return appName; }
        public List<WorksheetSummary> getWorksheets() { return worksheets; }
        public String getLanguage() { return language; }
    }

    public static class Output {
        private final List<PageDefinition> pages;

        public Output(List<PageDefinition> pages) {
            this.pages = pages;
        }

        public List<PageDefinition> getPages() { return pages; }
    }
}
