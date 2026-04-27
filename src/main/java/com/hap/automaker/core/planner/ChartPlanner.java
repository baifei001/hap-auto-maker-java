package com.hap.automaker.core.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.registry.ChartTypeConfig;
import com.hap.automaker.core.registry.ChartTypeRegistry;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 图表规划器
 *
 * Python 对应: plan_app_charts_gemini.py + planning/chart_planner.py
 *
 * 职责:
 * - 两阶段 AI 规划图表结构
 *   1. Structure: 规划哪些工作表上创建哪些图表（结构规划）
 *   2. Config: 为每个图表生成详细配置（xaxes/yaxisList/filters）
 * - 字段分类和图表约束检查
 * - 基于可用字段推荐合适的图表类型
 *
 * 图表类型约束:
 * - 需要分类字段: 柱图、饼图、漏斗图、词云、排行图、对称条形图
 * - 需要日期字段: 折线图（最佳）、柱图、双轴图、透视表
 * - 需要数值字段: 双轴图、散点图（X和Y）、进度图、仪表盘
 * - 需要地理字段: 地图、行政区划图
 * - 无X轴图表: 数值图、进度图、仪表盘
 */
public class ChartPlanner implements Planner<ChartPlanner.Input, ChartPlanner.Output> {

    private final AiTextClient aiClient;
    private final ChartTypeRegistry chartTypeRegistry;

    private static final int MAX_RETRIES = 3;
    private static final int MAX_CHARTS_PER_WORKSHEET = 6;
    private static final int MIN_CHARTS_TOTAL = 3;
    private static final Logger logger = LoggerFactory.getLogger(ChartPlanner.class);

    public ChartPlanner(AiTextClient aiClient) {
        this.aiClient = aiClient;
        this.chartTypeRegistry = new ChartTypeRegistry();
    }

    @Override
    public String getName() {
        return "ChartPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            // Phase 1: 结构规划 - 决定每个工作表上创建什么图表
            List<WorksheetChartAllocation> allocations = planChartStructure(input);

            // Phase 2: 配置规划 - 为每个图表生成详细配置
            List<ChartPlan> charts = new ArrayList<>();
            for (WorksheetChartAllocation allocation : allocations) {
                WorksheetInfo ws = findWorksheet(input, allocation.getWorksheetName());
                if (ws == null) {
                    logger.warn("[warn] Worksheet not found: {}", allocation.getWorksheetName());
                    continue;
                }

                ChartPlan chart = planChartConfig(allocation, ws, input.getAppName());
                if (chart != null) {
                    charts.add(chart);
                }
            }

            // 验证图表配置
            validateCharts(charts, input);

            logger.info("✓ 图表规划完成: {} 个图表", charts.size());

            return new Output(input.getAppName(), charts);

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan charts", e);
        }
    }

    /**
     * Phase 1: 规划图表结构 - 决定每个工作表上创建哪些图表
     */
    private List<WorksheetChartAllocation> planChartStructure(Input input) throws Exception {
        String prompt = buildStructurePrompt(input);
        String responseJson = callAiWithRetry(prompt);

        JsonNode root = Jacksons.mapper().readTree(responseJson);
        JsonNode allocationsNode = root.path("allocations");

        List<WorksheetChartAllocation> allocations = new ArrayList<>();
        if (allocationsNode.isArray()) {
            for (JsonNode allocNode : allocationsNode) {
                String wsName = allocNode.path("worksheet_name").asText();
                JsonNode chartsNode = allocNode.path("charts");

                if (chartsNode.isArray()) {
                    for (JsonNode chartNode : chartsNode) {
                        String chartType = chartNode.path("chart_type").asText();
                        String purpose = chartNode.path("purpose").asText();
                        int priority = chartNode.path("priority").asInt(5);

                        allocations.add(new WorksheetChartAllocation(wsName, chartType, purpose, priority));
                    }
                }
            }
        }

        return allocations;
    }

    /**
     * Phase 2: 为单个图表规划详细配置
     */
    private ChartPlan planChartConfig(WorksheetChartAllocation allocation,
                                       WorksheetInfo worksheet,
                                       String appName) throws Exception {

        String prompt = buildConfigPrompt(allocation, worksheet, appName);
        String responseJson = callAiWithRetry(prompt);

        JsonNode root = Jacksons.mapper().readTree(responseJson);

        // 解析图表配置
        String chartName = root.path("chart_name").asText(allocation.getChartType() + " - " + worksheet.getName());
        String reportType = root.path("report_type").asText();
        String xAxisField = root.path("x_axis_field").asText(null);
        JsonNode yAxisNode = root.path("y_axis");
        JsonNode filtersNode = root.path("filters");

        // 构建 Y 轴配置
        List<YAxisConfig> yAxes = new ArrayList<>();
        if (yAxisNode.isArray()) {
            for (JsonNode yNode : yAxisNode) {
                yAxes.add(new YAxisConfig(
                    yNode.path("field").asText(),
                    yNode.path("aggregation").asText("SUM"),
                    yNode.path("alias").asText(null)
                ));
            }
        }

        // 构建筛选器配置
        List<FilterConfig> filters = new ArrayList<>();
        if (filtersNode.isArray()) {
            for (JsonNode fNode : filtersNode) {
                filters.add(new FilterConfig(
                    fNode.path("field").asText(),
                    fNode.path("operator").asText("eq"),
                    fNode.path("value").asText(null)
                ));
            }
        }

        return new ChartPlan(
            chartName,
            reportType,
            worksheet.getName(),
            xAxisField,
            yAxes,
            filters,
            allocation.getPurpose()
        );
    }

    /**
     * 构建 Phase 1 结构规划 Prompt
     */
    private String buildStructurePrompt(Input input) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a data visualization expert. Plan chart allocations for app \"").append(input.getAppName()).append("\".\n\n");

        // 添加工作表信息
        sb.append("## Worksheets and Fields\n\n");
        for (WorksheetInfo ws : input.getWorksheets()) {
            sb.append("### ").append(ws.getName()).append("\n");
            sb.append("Purpose: ").append(ws.getPurpose()).append("\n");
            sb.append("Fields:\n");

            FieldClassification fc = classifyFields(ws.getFields());

            if (!fc.getTextFields().isEmpty()) {
                sb.append("  Text: ").append(String.join(", ", fc.getTextFields())).append("\n");
            }
            if (!fc.getNumberFields().isEmpty()) {
                sb.append("  Numeric: ").append(String.join(", ", fc.getNumberFields())).append("\n");
            }
            if (!fc.getDateFields().isEmpty()) {
                sb.append("  Date: ").append(String.join(", ", fc.getDateFields())).append("\n");
            }
            if (!fc.getSelectFields().isEmpty()) {
                sb.append("  Select/Classify: ").append(String.join(", ", fc.getSelectFields())).append("\n");
            }
            if (!fc.getRelationFields().isEmpty()) {
                sb.append("  Relation: ").append(String.join(", ", fc.getRelationFields())).append("\n");
            }

            // 添加推荐的图表类型
            List<String> suggestedTypes = suggestChartTypes(fc);
            sb.append("  Suggested charts: ").append(String.join(", ", suggestedTypes)).append("\n");
            sb.append("\n");
        }

        // 添加图表类型约束
        sb.append("## Available Chart Types\n\n");
        sb.append(chartTypeRegistry.generatePrompt()).append("\n");

        // 添加约束说明
        sb.append("## Chart Constraints\n\n");
        sb.append("- Bar (柱图): Needs classify/date X-axis + numeric/count Y-axis\n");
        sb.append("- Line (折线图): Best with date X-axis + numeric/count Y-axis\n");
        sb.append("- Pie (饼图): Needs classify X-axis + numeric/count Y-axis\n");
        sb.append("- Number (数值图): No X-axis, needs numeric/count Y-axis only\n");
        sb.append("- DualAxis (双轴图): Needs date/classify X-axis + 2 numeric Y-axes\n");
        sb.append("- Scatter (散点图): Needs numeric X-axis + numeric Y-axis\n");
        sb.append("- PivotTable (透视表): Needs classify/date fields for rows/cols\n");
        sb.append("- Map (地图): Needs geo/area field\n");
        sb.append("- Gauge (仪表盘): No X-axis, single numeric metric\n");
        sb.append("- Progress (进度图): No X-axis, needs numeric metric\n");
        sb.append("\n");

        // 任务要求
        sb.append("## Task\n\n");
        sb.append("Plan chart allocations across worksheets following these rules:\n\n");
        sb.append("1. Each worksheet can have 0-").append(MAX_CHARTS_PER_WORKSHEET).append(" charts\n");
        sb.append("2. Total charts should be at least ").append(MIN_CHARTS_TOTAL).append("\n");
        sb.append("3. Charts must match available field types (see constraints above)\n");
        sb.append("4. Main/business worksheets should have more charts than lookup tables\n");
        sb.append("5. Include a mix of: trend (Line), comparison (Bar/Pie), and summary (Number/Gauge)\n");
        sb.append("6. Priority: 1=highest (critical business metric), 5=normal, 9=lowest\n");
        sb.append("\n");

        sb.append("Return strict JSON only:\n");
        sb.append("{\n");
        sb.append("  \"allocations\": [\n");
        sb.append("    {\n");
        sb.append("      \"worksheet_name\": \"Worksheet Name\",\n");
        sb.append("      \"charts\": [\n");
        sb.append("        {\n");
        sb.append("          \"chart_type\": \"Bar\",\n");
        sb.append("          \"purpose\": \"Purpose description\",\n");
        sb.append("          \"priority\": 5\n");
        sb.append("        }\n");
        sb.append("      ]\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * 构建 Phase 2 配置规划 Prompt
     */
    private String buildConfigPrompt(WorksheetChartAllocation allocation,
                                      WorksheetInfo worksheet,
                                      String appName) {
        StringBuilder sb = new StringBuilder();

        sb.append("Design detailed configuration for a chart.\n\n");

        sb.append("App: ").append(appName).append("\n");
        sb.append("Worksheet: ").append(worksheet.getName()).append("\n");
        sb.append("Chart Type: ").append(allocation.getChartType()).append("\n");
        sb.append("Purpose: ").append(allocation.getPurpose()).append("\n\n");

        // 字段分类
        FieldClassification fc = classifyFields(worksheet.getFields());

        sb.append("Available Fields:\n");
        sb.append("  Classify/Select: ").append(fc.getSelectFields().isEmpty() ? "(none)" : String.join(", ", fc.getSelectFields())).append("\n");
        sb.append("  Date: ").append(fc.getDateFields().isEmpty() ? "(none)" : String.join(", ", fc.getDateFields())).append("\n");
        sb.append("  Numeric: ").append(fc.getNumberFields().isEmpty() ? "(none)" : String.join(", ", fc.getNumberFields())).append("\n");
        sb.append("  Text: ").append(fc.getTextFields().isEmpty() ? "(none)" : String.join(", ", fc.getTextFields())).append("\n");
        sb.append("\n");

        // 图表类型配置要求
        ChartTypeConfig chartType = getChartTypeConfig(allocation.getChartType());
        sb.append("Chart Requirements:\n");
        if (chartType != null) {
            sb.append("  Needs X-axis: ").append(chartType.isNeedsXAxis()).append("\n");
            sb.append("  Needs Y-axis: ").append(chartType.isNeedsYAxis()).append("\n");
            if (chartType.getRecommendedXAxisTypes() != null && chartType.getRecommendedXAxisTypes().length > 0) {
                sb.append("  Recommended X-axis types: ").append(String.join(", ", chartType.getRecommendedXAxisTypes())).append("\n");
            }
        }
        sb.append("\n");

        // 聚合选项
        sb.append("Y-axis Aggregation Options: SUM, AVG, MAX, MIN, COUNT, COUNT_DISTINCT\n\n");

        sb.append("Return strict JSON only:\n");
        sb.append("{\n");
        sb.append("  \"chart_name\": \"Descriptive Chart Name\",\n");
        sb.append("  \"report_type\": \"Bar|Line|Pie|Number|DualAxis|...\",\n");
        sb.append("  \"x_axis_field\": \"field_name_or_null\",\n");
        sb.append("  \"y_axis\": [\n");
        sb.append("    {\n");
        sb.append("      \"field\": \"numeric_field\",\n");
        sb.append("      \"aggregation\": \"SUM|AVG|MAX|MIN|COUNT|COUNT_DISTINCT\",\n");
        sb.append("      \"alias\": \"Display Name (optional)\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"filters\": [\n");
        sb.append("    {\n");
        sb.append("      \"field\": \"field_name\",\n");
        sb.append("      \"operator\": \"eq|ne|gt|lt|in|not_in\",\n");
        sb.append("      \"value\": \"filter_value\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("\n");
        sb.append("Rules:\n");
        sb.append("1. chart_name should be concise and descriptive (2-8 Chinese characters or 3-20 English words)\n");
        sb.append("2. x_axis_field: null for Number/Gauge/Progress charts\n");
        sb.append("3. y_axis must have at least 1 entry\n");
        sb.append("4. For DualAxis, y_axis must have exactly 2 entries\n");
        sb.append("5. filters are optional, use only for meaningful filtering\n");

        return sb.toString();
    }

    /**
     * 字段分类 - 根据字段类型分类
     */
    private FieldClassification classifyFields(List<FieldInfo> fields) {
        List<String> textFields = new ArrayList<>();
        List<String> numberFields = new ArrayList<>();
        List<String> dateFields = new ArrayList<>();
        List<String> selectFields = new ArrayList<>();
        List<String> userFields = new ArrayList<>();
        List<String> relationFields = new ArrayList<>();
        List<String> geoFields = new ArrayList<>();

        for (FieldInfo field : fields) {
            String name = field.getName();
            int controlType = field.getControlType();

            // 系统字段排除
            if (isSystemField(name)) {
                continue;
            }

            switch (controlType) {
                case 2: // Text
                case 3: // Phone
                case 4: // Email
                case 5: // URL
                    textFields.add(name);
                    break;
                case 6: // Number
                case 7: // Money
                case 8: // Percentage
                    numberFields.add(name);
                    break;
                case 9: // Date
                case 10: // DateTime
                case 11: // Time
                    dateFields.add(name);
                    break;
                case 14: // Single Select
                case 15: // Multi Select
                case 28: // Level
                    selectFields.add(name);
                    break;
                case 26: // User
                case 27: // Department
                    userFields.add(name);
                    selectFields.add(name); // User/Dept can be used as classify
                    break;
                case 21: // Relation
                case 22: // Lookup
                    relationFields.add(name);
                    break;
                case 29: // Area
                case 30: // Location
                    geoFields.add(name);
                    selectFields.add(name); // Area can be used as classify
                    break;
            }
        }

        return new FieldClassification(textFields, numberFields, dateFields, selectFields,
                                       userFields, relationFields, geoFields);
    }

    private boolean isSystemField(String name) {
        Set<String> systemFields = Set.of("ctime", "utime", "ownerid", "caid", "record_count",
                                          "创建时间", "更新时间", "拥有者", "创建人", "记录数");
        return systemFields.contains(name);
    }

    /**
     * 根据字段分类推荐图表类型
     */
    private List<String> suggestChartTypes(FieldClassification fc) {
        List<String> suggestions = new ArrayList<>();

        boolean hasClassify = !fc.getSelectFields().isEmpty() || !fc.getUserFields().isEmpty();
        boolean hasDate = !fc.getDateFields().isEmpty();
        boolean hasNumeric = !fc.getNumberFields().isEmpty();
        boolean hasGeo = !fc.getGeoFields().isEmpty();

        // 基础图表
        if (hasClassify && hasNumeric) {
            suggestions.add("Bar");
            suggestions.add("Pie");
        }
        if (hasDate && hasNumeric) {
            suggestions.add("Line");
        }
        if (hasNumeric) {
            suggestions.add("Number");
        }

        // 高级图表
        if (hasDate && hasNumeric) {
            suggestions.add("DualAxis");
        }
        if (hasClassify && fc.getNumberFields().size() >= 2) {
            suggestions.add("PivotTable");
        }
        if (hasGeo && hasNumeric) {
            suggestions.add("Map");
        }

        return suggestions.isEmpty() ? List.of("Number") : suggestions;
    }

    /**
     * 获取图表类型配置
     */
    private ChartTypeConfig getChartTypeConfig(String chartType) {
        return chartTypeRegistry.getByName(chartType);
    }

    /**
     * 查找工作表信息
     */
    private WorksheetInfo findWorksheet(Input input, String name) {
        for (WorksheetInfo ws : input.getWorksheets()) {
            if (ws.getName().equals(name)) {
                return ws;
            }
        }
        return null;
    }

    /**
     * 验证图表配置
     */
    private void validateCharts(List<ChartPlan> charts, Input input) {
        // 确保每个图表引用的字段存在
        for (ChartPlan chart : charts) {
            WorksheetInfo ws = findWorksheet(input, chart.getWorksheetName());
            if (ws == null) continue;

            Set<String> availableFields = new HashSet<>();
            for (FieldInfo f : ws.getFields()) {
                availableFields.add(f.getName());
            }

            // 验证 X 轴字段
            if (chart.getXAxisField() != null && !availableFields.contains(chart.getXAxisField())) {
                logger.warn("[warn] Chart '{}' references unknown X-axis field: {}", chart.getName(), chart.getXAxisField());
            }

            // 验证 Y 轴字段
            for (YAxisConfig yAxis : chart.getYAxes()) {
                if (!availableFields.contains(yAxis.getField())) {
                    logger.warn("[warn] Chart '{}' references unknown Y-axis field: {}", chart.getName(), yAxis.getField());
                }
            }
        }
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

    // ========== 输入类 ==========
    public static class Input {
        private final String appName;
        private final List<WorksheetInfo> worksheets;

        public Input(String appName, List<WorksheetInfo> worksheets) {
            this.appName = appName;
            this.worksheets = worksheets != null ? worksheets : List.of();
        }

        public String getAppName() { return appName; }
        public List<WorksheetInfo> getWorksheets() { return worksheets; }
    }

    public static class WorksheetInfo {
        private final String name;
        private final String purpose;
        private final List<FieldInfo> fields;

        public WorksheetInfo(String name, String purpose, List<FieldInfo> fields) {
            this.name = name;
            this.purpose = purpose;
            this.fields = fields != null ? fields : List.of();
        }

        public String getName() { return name; }
        public String getPurpose() { return purpose; }
        public List<FieldInfo> getFields() { return fields; }
    }

    public static class FieldInfo {
        private final String name;
        private final int controlType;

        public FieldInfo(String name, int controlType) {
            this.name = name;
            this.controlType = controlType;
        }

        public String getName() { return name; }
        public int getControlType() { return controlType; }
    }

    // ========== 内部数据结构 ==========
    private static class WorksheetChartAllocation {
        private final String worksheetName;
        private final String chartType;
        private final String purpose;
        private final int priority;

        WorksheetChartAllocation(String worksheetName, String chartType, String purpose, int priority) {
            this.worksheetName = worksheetName;
            this.chartType = chartType;
            this.purpose = purpose;
            this.priority = priority;
        }

        String getWorksheetName() { return worksheetName; }
        String getChartType() { return chartType; }
        String getPurpose() { return purpose; }
        int getPriority() { return priority; }
    }

    private static class FieldClassification {
        private final List<String> textFields;
        private final List<String> numberFields;
        private final List<String> dateFields;
        private final List<String> selectFields;
        private final List<String> userFields;
        private final List<String> relationFields;
        private final List<String> geoFields;

        FieldClassification(List<String> textFields, List<String> numberFields,
                            List<String> dateFields, List<String> selectFields,
                            List<String> userFields, List<String> relationFields,
                            List<String> geoFields) {
            this.textFields = textFields;
            this.numberFields = numberFields;
            this.dateFields = dateFields;
            this.selectFields = selectFields;
            this.userFields = userFields;
            this.relationFields = relationFields;
            this.geoFields = geoFields;
        }

        List<String> getTextFields() { return textFields; }
        List<String> getNumberFields() { return numberFields; }
        List<String> getDateFields() { return dateFields; }
        List<String> getSelectFields() { return selectFields; }
        List<String> getUserFields() { return userFields; }
        List<String> getRelationFields() { return relationFields; }
        List<String> getGeoFields() { return geoFields; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final String appName;
        private final List<ChartPlan> charts;

        public Output(String appName, List<ChartPlan> charts) {
            this.appName = appName;
            this.charts = charts;
        }

        public String getAppName() { return appName; }
        public List<ChartPlan> getCharts() { return charts; }
    }

    public static class ChartPlan {
        private final String name;
        private final String reportType;
        private final String worksheetName;
        private final String xAxisField;
        private final List<YAxisConfig> yAxes;
        private final List<FilterConfig> filters;
        private final String purpose;

        public ChartPlan(String name, String reportType, String worksheetName,
                         String xAxisField, List<YAxisConfig> yAxes,
                         List<FilterConfig> filters, String purpose) {
            this.name = name;
            this.reportType = reportType;
            this.worksheetName = worksheetName;
            this.xAxisField = xAxisField;
            this.yAxes = yAxes != null ? yAxes : List.of();
            this.filters = filters != null ? filters : List.of();
            this.purpose = purpose;
        }

        public String getName() { return name; }
        public String getReportType() { return reportType; }
        public String getWorksheetName() { return worksheetName; }
        public String getXAxisField() { return xAxisField; }
        public List<YAxisConfig> getYAxes() { return yAxes; }
        public List<FilterConfig> getFilters() { return filters; }
        public String getPurpose() { return purpose; }
    }

    public static class YAxisConfig {
        private final String field;
        private final String aggregation;
        private final String alias;

        public YAxisConfig(String field, String aggregation, String alias) {
            this.field = field;
            this.aggregation = aggregation;
            this.alias = alias;
        }

        public String getField() { return field; }
        public String getAggregation() { return aggregation; }
        public String getAlias() { return alias; }
    }

    public static class FilterConfig {
        private final String field;
        private final String operator;
        private final String value;

        public FilterConfig(String field, String operator, String value) {
            this.field = field;
            this.operator = operator;
            this.value = value;
        }

        public String getField() { return field; }
        public String getOperator() { return operator; }
        public String getValue() { return value; }
    }
}
