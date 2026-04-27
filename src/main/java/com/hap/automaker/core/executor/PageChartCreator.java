package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * 页面图表创建执行器
 *
 * Python 对应: executors/create_charts_from_plan.py 中页面图表相关部分
 *
 * 职责:
 * - 为自定义页面添加图表
 * - 调用 API 更新页面配置，添加图表组件
 * - 支持批量为多个页面添加图表
 *
 * API 调用:
 * - POST /api/ReportPage/SaveReportPage - 保存页面配置（含图表）
 */
public class PageChartCreator implements Executor<PageChartCreator.Input, PageChartCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(PageChartCreator.class);

    private final HapApiClient apiClient;
    private final ExecutorService executor;

    public PageChartCreator(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "PageChartCreator";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        Map<String, List<PageChartDetail>> pageCharts = new ConcurrentHashMap<>();
        List<PageChartDetail> allCharts = new CopyOnWriteArrayList<>();

        try {
            List<Callable<Void>> tasks = new ArrayList<>();

            for (PageChartPlan plan : input.getPlans()) {
                tasks.add(() -> {
                    List<PageChartDetail> details = createChartsForPage(plan, input);
                    pageCharts.put(plan.getPageId(), details);
                    allCharts.addAll(details);
                    return null;
                });
            }

            if (input.isFailFast()) {
                for (Callable<Void> task : tasks) {
                    task.call();
                }
            } else {
                List<Future<Void>> futures = executor.invokeAll(tasks);
                for (Future<Void> future : futures) {
                    try {
                        future.get();
                    } catch (ExecutionException e) {
                        if (input.isFailFast()) {
                            throw new ExecutorException(getName(), "Page chart creation failed", e.getCause());
                        }
                    }
                }
            }

            boolean allSuccess = allCharts.stream().allMatch(PageChartDetail::isSuccess);
            return new Output(allSuccess, pageCharts, allCharts, null);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to create page charts", e);
        }
    }

    private List<PageChartDetail> createChartsForPage(PageChartPlan plan, Input input) {
        List<PageChartDetail> details = new ArrayList<>();

        if (plan.getCharts() == null || plan.getCharts().isEmpty()) {
            logger.info("⚠ Page {} has no charts to add", plan.getPageName());
            return details;
        }

        try {
            // 为页面批量添加图表
            List<String> chartIds = addChartsToPage(plan, input);

            for (int i = 0; i < plan.getCharts().size(); i++) {
                PageChartDefinition chart = plan.getCharts().get(i);
                String chartId = i < chartIds.size() ? chartIds.get(i) : null;

                details.add(new PageChartDetail(
                    chart.getName(),
                    chartId,
                    chartId != null,
                    chartId == null ? "Failed to add chart to page" : null,
                    chart.getReportType(),
                    plan.getPageId(),
                    plan.getPageName()
                ));

                if (chartId != null) {
                    logger.info("✓ Chart added to page: {} (page={}, chartId={})",
                        chart.getName(), plan.getPageName(), chartId);
                }
            }

        } catch (Exception e) {
            for (PageChartDefinition chart : plan.getCharts()) {
                details.add(new PageChartDetail(
                    chart.getName(),
                    null,
                    false,
                    e.getMessage(),
                    chart.getReportType(),
                    plan.getPageId(),
                    plan.getPageName()
                ));
            }
            if (input.isFailFast()) {
                throw new RuntimeException(e);
            }
        }

        return details;
    }

    private List<String> addChartsToPage(PageChartPlan plan, Input input) throws Exception {
        if (input.isDryRun()) {
            logger.info("[DryRun] Would add {} charts to page: {}", plan.getCharts().size(), plan.getPageName());
            return plan.getCharts().stream()
                .map(c -> "dry-run-chart-id-" + c.getName())
                .toList();
        }

        // 构建页面配置（包含图表）
        ObjectNode pageConfig = buildPageConfigWithCharts(plan, input.getAppId());

        // 调用 API 保存页面配置
        JsonNode response = apiClient.saveReportPage(
            input.getAppId(),
            pageConfig
        );

        // 从响应中提取图表ID列表
        List<String> chartIds = extractChartIds(response, plan.getCharts().size());

        return chartIds;
    }

    /**
     * 构建包含图表的页面配置
     */
    private ObjectNode buildPageConfigWithCharts(PageChartPlan plan, String appId) {
        ObjectMapper mapper = Jacksons.mapper();
        ObjectNode config = mapper.createObjectNode();

        config.put("id", plan.getPageId());
        config.put("name", plan.getPageName());
        config.put("type", plan.getPageType() != null ? plan.getPageType() : "dashboard");
        config.put("description", plan.getPageDescription() != null ? plan.getPageDescription() : "");

        // 页面布局配置 - 使用网格布局
        ObjectNode layout = config.putObject("layout");
        layout.put("columns", plan.getColumns() > 0 ? plan.getColumns() : 2);
        layout.put("rowHeight", 300);

        // 构建图表数组
        ArrayNode charts = config.putArray("charts");

        int row = 0;
        int col = 0;
        int columns = plan.getColumns() > 0 ? plan.getColumns() : 2;

        for (PageChartDefinition chartDef : plan.getCharts()) {
            ObjectNode chart = charts.addObject();

            chart.put("name", chartDef.getName());
            chart.put("reportType", chartDef.getReportType());
            chart.put("worksheetId", chartDef.getWorksheetId());

            // 图表位置和大小
            ObjectNode position = chart.putObject("position");
            position.put("x", col);
            position.put("y", row);
            position.put("w", chartDef.getWidth() > 0 ? chartDef.getWidth() : 1);
            position.put("h", chartDef.getHeight() > 0 ? chartDef.getHeight() : 1);

            // 图表数据源配置
            ObjectNode dataSource = chart.putObject("dataSource");
            dataSource.put("worksheetId", chartDef.getWorksheetId());
            dataSource.put("viewId", chartDef.getViewId());

            // 轴配置
            if (chartDef.getXField() != null) {
                ObjectNode xaxis = chart.putObject("xaxis");
                xaxis.put("field", chartDef.getXField());
                xaxis.put("controlType", chartDef.getXFieldType());
            }

            if (chartDef.getYField() != null) {
                ObjectNode yaxis = chart.putObject("yaxis");
                yaxis.put("field", chartDef.getYField());
                yaxis.put("controlType", chartDef.getYFieldType());
                yaxis.put("aggregate", chartDef.getYAggregate() != null ? chartDef.getYAggregate() : "count");
            }

            // 图表样式配置
            ObjectNode style = chart.putObject("style");
            style.put("showLegend", true);
            style.put("showTitle", true);
            style.put("showPercent",
                chartDef.getReportType() == 3 || chartDef.getReportType() == 4); // 饼图/环形图显示百分比

            // 移动网格位置
            col++;
            if (col >= columns) {
                col = 0;
                row++;
            }
        }

        return config;
    }

    private List<String> extractChartIds(JsonNode response, int expectedCount) {
        List<String> chartIds = new ArrayList<>();

        if (response == null) {
            return chartIds;
        }

        JsonNode data = response.path("data");
        if (data.isMissingNode()) {
            data = response;
        }

        // 尝试从 charts 数组中提取
        JsonNode charts = data.path("charts");
        if (charts.isArray()) {
            for (JsonNode chart : charts) {
                String chartId = chart.path("id").asText();
                if (chartId.isEmpty()) {
                    chartId = chart.path("chartId").asText();
                }
                if (!chartId.isEmpty()) {
                    chartIds.add(chartId);
                }
            }
        }

        // 如果未找到，生成占位ID
        while (chartIds.size() < expectedCount) {
            chartIds.add("chart-" + UUID.randomUUID().toString().substring(0, 8));
        }

        return chartIds;
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 输入类 ==========
    public static class Input {
        private final String appId;
        private final List<PageChartPlan> plans;
        private final boolean dryRun;
        private final boolean failFast;

        public Input(String appId, List<PageChartPlan> plans, boolean dryRun, boolean failFast) {
            this.appId = appId;
            this.plans = plans;
            this.dryRun = dryRun;
            this.failFast = failFast;
        }

        public String getAppId() { return appId; }
        public List<PageChartPlan> getPlans() { return plans; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
    }

    public static class PageChartPlan {
        private final String pageId;
        private final String pageName;
        private final String pageType;
        private final String pageDescription;
        private final List<PageChartDefinition> charts;
        private final int columns;

        public PageChartPlan(String pageId, String pageName, String pageType,
                            String pageDescription, List<PageChartDefinition> charts,
                            int columns) {
            this.pageId = pageId;
            this.pageName = pageName;
            this.pageType = pageType;
            this.pageDescription = pageDescription;
            this.charts = charts;
            this.columns = columns > 0 ? columns : 2;
        }

        public String getPageId() { return pageId; }
        public String getPageName() { return pageName; }
        public String getPageType() { return pageType; }
        public String getPageDescription() { return pageDescription; }
        public List<PageChartDefinition> getCharts() { return charts; }
        public int getColumns() { return columns; }
    }

    public static class PageChartDefinition {
        private final String name;
        private final int reportType;
        private final String worksheetId;
        private final String viewId;
        private final String xField;
        private final Integer xFieldType;
        private final String yField;
        private final Integer yFieldType;
        private final String yAggregate;
        private final int width;
        private final int height;

        public PageChartDefinition(String name, int reportType,
                                   String worksheetId, String viewId,
                                   String xField, Integer xFieldType,
                                   String yField, Integer yFieldType,
                                   String yAggregate,
                                   int width, int height) {
            this.name = name;
            this.reportType = reportType;
            this.worksheetId = worksheetId;
            this.viewId = viewId;
            this.xField = xField;
            this.xFieldType = xFieldType;
            this.yField = yField;
            this.yFieldType = yFieldType;
            this.yAggregate = yAggregate;
            this.width = width > 0 ? width : 1;
            this.height = height > 0 ? height : 1;
        }

        public String getName() { return name; }
        public int getReportType() { return reportType; }
        public String getWorksheetId() { return worksheetId; }
        public String getViewId() { return viewId; }
        public String getXField() { return xField; }
        public Integer getXFieldType() { return xFieldType; }
        public String getYField() { return yField; }
        public Integer getYFieldType() { return yFieldType; }
        public String getYAggregate() { return yAggregate; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final boolean success;
        private final Map<String, List<PageChartDetail>> pageCharts;
        private final List<PageChartDetail> allCharts;
        private final String errorMessage;

        public Output(boolean success, Map<String, List<PageChartDetail>> pageCharts,
                      List<PageChartDetail> allCharts, String errorMessage) {
            this.success = success;
            this.pageCharts = pageCharts;
            this.allCharts = allCharts;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Map<String, List<PageChartDetail>> getPageCharts() { return pageCharts; }
        public List<PageChartDetail> getAllCharts() { return allCharts; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class PageChartDetail {
        private final String chartName;
        private final String chartId;
        private final boolean success;
        private final String errorMessage;
        private final int reportType;
        private final String pageId;
        private final String pageName;

        public PageChartDetail(String chartName, String chartId, boolean success,
                               String errorMessage, int reportType,
                               String pageId, String pageName) {
            this.chartName = chartName;
            this.chartId = chartId;
            this.success = success;
            this.errorMessage = errorMessage;
            this.reportType = reportType;
            this.pageId = pageId;
            this.pageName = pageName;
        }

        public String getChartName() { return chartName; }
        public String getChartId() { return chartId; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public int getReportType() { return reportType; }
        public String getPageId() { return pageId; }
        public String getPageName() { return pageName; }
    }
}
