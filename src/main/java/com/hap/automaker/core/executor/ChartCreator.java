package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.registry.ChartTypeRegistry;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 图表创建执行器
 *
 * Python 对应: create_charts_from_plan.py
 *
 * 职责:
 * - 根据 chart_plan JSON 创建统计图
 * - 调用 API /report/reportConfig/saveReportConfig
 * - 处理不同 reportType 的特殊参数（双轴图、对称条形图的 rightY 等）
 */
public class ChartCreator implements Executor<ChartCreator.Input, ChartCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(ChartCreator.class);

    private final HapApiClient apiClient;
    private final ChartTypeRegistry chartTypeRegistry;
    private final ExecutorService executor;

    public ChartCreator(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.chartTypeRegistry = new ChartTypeRegistry();
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "ChartCreator";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        Map<String, List<ChartCreationDetail>> worksheetCharts = new ConcurrentHashMap<>();
        List<ChartCreationDetail> allCharts = new CopyOnWriteArrayList<>();

        try {
            List<Callable<Void>> tasks = new ArrayList<>();

            for (WorksheetChartPlan wsPlan : input.getPlans()) {
                tasks.add(() -> {
                    List<ChartCreationDetail> details = createChartsForWorksheet(wsPlan, input);
                    worksheetCharts.put(wsPlan.getWorksheetId(), details);
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
                            throw new ExecutorException(getName(), "Chart creation failed", e.getCause());
                        }
                    }
                }
            }

            boolean allSuccess = allCharts.stream().allMatch(ChartCreationDetail::isSuccess);
            return new Output(allSuccess, worksheetCharts, allCharts, null);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to create charts", e);
        }
    }

    private List<ChartCreationDetail> createChartsForWorksheet(WorksheetChartPlan plan, Input input) {
        List<ChartCreationDetail> details = new ArrayList<>();

        for (ChartDefinition chart : plan.getCharts()) {
            try {
                String reportId = createChart(plan.getWorksheetId(), chart, input);
                details.add(new ChartCreationDetail(
                    chart.getName(), reportId, true, null,
                    chart.getReportType(), plan.getWorksheetId()
                ));
            } catch (Exception e) {
                details.add(new ChartCreationDetail(
                    chart.getName(), null, false, e.getMessage(),
                    chart.getReportType(), plan.getWorksheetId()
                ));
                if (input.isFailFast()) {
                    break;
                }
            }
        }

        return details;
    }

    private String createChart(String worksheetId, ChartDefinition chart, Input input) throws Exception {
        if (input.isDryRun()) {
            logger.info("[DryRun] Would create chart: {} (type={})", chart.getName(), chart.getReportType());
            return "dry-run-report-id";
        }

        // 构建图表配置 JSON
        ObjectNode reportBody = buildReportBody(worksheetId, chart);

        // 调用 API 创建图表
        JsonNode response = apiClient.saveReportConfig(reportBody);

        // 从响应中提取 reportId
        String reportId = "";
        JsonNode data = response.path("data");
        if (data.isObject()) {
            reportId = data.path("reportId").asText();
            if (reportId.isEmpty()) {
                reportId = data.path("id").asText();
            }
        } else if (!data.isMissingNode() && !data.isNull()) {
            reportId = data.asText();
        }

        // 检查成功标志
        boolean isSuccess = response.path("success").asBoolean(false)
            || response.path("status").asInt(0) == 1
            || response.path("code").asInt(0) == 1
            || !reportId.isEmpty();

        if (!isSuccess) {
            throw new RuntimeException("Chart creation failed: " + response.toString());
        }

        logger.info("✓ 图表创建成功: {} (ID: {}, type={})", chart.getName(), reportId, chart.getReportType());

        return reportId;
    }

    /**
     * 构建 saveReportConfig 请求体
     */
    private ObjectNode buildReportBody(String worksheetId, ChartDefinition chart) {
        ObjectMapper mapper = Jacksons.mapper();
        ObjectNode body = mapper.createObjectNode();

        int reportType = chart.getReportType();
        String name = chart.getName();

        body.put("name", name);
        body.put("reportType", reportType);
        body.put("appId", worksheetId);
        body.put("appType", 1);
        body.put("sourceType", 1);
        body.put("isPublic", true);
        body.put("id", ""); // 新建图表为空
        body.put("splitId", "");
        body.put("version", "6.5");
        body.put("auth", 1);

        // 创建时间
        body.put("createdDate", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // account 对象
        ObjectNode account = body.putObject("account");
        account.put("accountId", "");
        account.putNull("fullName");
        account.putNull("avatar");
        account.putNull("status");

        // views
        body.putArray("views");

        // summary
        ObjectNode summary = body.putObject("summary");
        summary.put("controlId", "");
        summary.put("type", 1);
        summary.put("name", "总计");
        summary.put("number", true);
        summary.put("percent", false);
        summary.put("sum", 0);
        summary.put("contrastSum", 0);
        summary.put("contrastMapSum", 0);
        summary.put("rename", "");
        summary.put("all", false);

        // style
        body.putObject("style");

        // formulas
        body.putArray("formulas");

        // sorts
        body.putArray("sorts");

        // displaySetup
        ObjectNode displaySetup = buildDisplaySetupNode(reportType, chart.getXaxes());
        body.set("displaySetup", displaySetup);

        // xaxes
        ObjectNode xaxes = buildXaxesNode(chart.getXaxes(), reportType);
        body.set("xaxes", xaxes);

        // yaxisList
        ArrayNode yaxisList = buildYaxisListNode(chart.getYaxisList());
        body.set("yaxisList", yaxisList);

        // split
        body.putObject("split");

        // filter - 默认筛选器（不限时间）
        ObjectNode filter = body.putObject("filter");
        filter.put("filterRangeId", "ctime");
        filter.put("filterRangeName", "创建时间");
        filter.put("rangeType", 0);
        filter.put("rangeValue", 0);
        filter.put("today", false);

        // 双轴图 (reportType=7) 需要 rightY 和 yreportType
        if (reportType == 7 && chart.getRightY() != null) {
            int yreportType = chart.getYreportType() != null ? chart.getYreportType() : 2;
            body.put("yreportType", yreportType);

            ObjectNode rightY = body.putObject("rightY");
            rightY.put("reportType", ((Number) chart.getRightY().getOrDefault("reportType", 2)).intValue());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rightYaxisList = (List<Map<String, Object>>) chart.getRightY().get("yaxisList");
            if (rightYaxisList != null) {
                rightY.set("yaxisList", buildYaxisListNode(rightYaxisList));
            }
        }

        // 透视表 (reportType=13) 需要 pivotTable
        if (reportType == 13 && chart.getPivotTable() != null) {
            ObjectNode pivotTable = mapper.convertValue(chart.getPivotTable(), ObjectNode.class);
            body.set("pivotTable", pivotTable);
        }

        // 地图 (reportType=5) 需要 country
        if (reportType == 5 && chart.getCountry() != null) {
            ObjectNode country = mapper.convertValue(chart.getCountry(), ObjectNode.class);
            body.set("country", country);
        }

        return body;
    }

    private ObjectNode buildDisplaySetupNode(int reportType, Map<String, Object> xaxes) {
        ObjectMapper mapper = Jacksons.mapper();
        ObjectNode setup = mapper.createObjectNode();

        setup.put("isPerPile", false);
        setup.put("isPile", false);
        setup.put("isAccumulate", false);
        setup.putNull("accumulatePerPile");
        setup.put("isToday", false);
        setup.put("isLifecycle", false);
        setup.put("lifecycleValue", 0);
        setup.put("contrastType", 0);
        setup.put("fontStyle", 1);
        setup.put("showTotal", false);
        setup.put("showTitle", true);
        setup.put("showLegend", true);
        setup.put("legendType", 1);
        setup.put("showDimension", true);
        setup.put("showNumber", true);

        // 饼图/环形图/漏斗图显示百分比
        boolean showPercent = reportType == 3 || reportType == 4 || reportType == 5;
        setup.put("showPercent", showPercent);

        setup.put("showXAxisCount", 0);
        setup.put("showChartType", 1);
        setup.put("showPileTotal", true);
        setup.put("hideOverlapText", false);
        setup.put("showRowList", true);
        setup.putArray("showControlIds");
        setup.putArray("auxiliaryLines");
        setup.putArray("showOptionIds");
        setup.put("contrast", false);
        setup.putArray("colorRules");

        // percent 配置
        ObjectNode percent = setup.putObject("percent");
        percent.put("enable", false);
        percent.put("type", 2);
        percent.put("dot", "2");
        percent.put("dotFormat", "1");
        percent.put("roundType", 2);

        // mergeCell - 透视表启用
        setup.put("mergeCell", reportType == 13);

        // xdisplay
        String xControlName = "";
        if (xaxes != null && xaxes.get("controlName") != null) {
            xControlName = xaxes.get("controlName").toString();
        }
        ObjectNode xdisplay = setup.putObject("xdisplay");
        xdisplay.put("showDial", true);
        xdisplay.put("showTitle", false);
        xdisplay.put("title", xControlName);
        xdisplay.putNull("minValue");
        xdisplay.putNull("maxValue");

        setup.put("xaxisEmpty", false);

        // ydisplay
        ObjectNode ydisplay = setup.putObject("ydisplay");
        ydisplay.put("showDial", true);
        ydisplay.put("showTitle", false);
        ydisplay.put("title", "记录数量");
        ydisplay.putNull("minValue");
        ydisplay.putNull("maxValue");
        ydisplay.put("lineStyle", 1);
        ydisplay.putNull("showNumber");

        // previewUrl / imageUrl
        setup.putNull("previewUrl");
        setup.putNull("imageUrl");

        // 数值图不显示图例和维度
        if (reportType == 10) {
            setup.put("showLegend", false);
            setup.put("showDimension", false);
        }

        return setup;
    }

    private ObjectNode buildXaxesNode(Map<String, Object> xaxes, int reportType) {
        ObjectMapper mapper = Jacksons.mapper();
        ObjectNode node = mapper.createObjectNode();

        if (xaxes == null) {
            // 默认值
            node.put("controlId", "");
            node.put("sortType", 0);
            node.put("particleSizeType", 0);
            node.put("rename", "");
            node.put("emptyType", 0);
            node.putNull("fields");
            node.put("subTotal", false);
            node.putNull("subTotalName");
            node.put("showFormat", "0");
            node.put("displayMode", "text");
            node.put("controlName", "");
            node.put("controlType", 16);
            node.putNull("dataSource");
            node.putArray("options");
            node.putNull("advancedSetting");
            node.putNull("relationControl");
            node.put("cid", "");
            node.put("cname", "");
            node.put("xaxisEmptyType", 0);
            node.put("xaxisEmpty", false);
            node.put("c_Id", "");
            return node;
        }

        String controlId = xaxes.get("controlId") != null ? xaxes.get("controlId").toString() : "";
        int controlType = xaxes.get("controlType") != null ?
            ((Number) xaxes.get("controlType")).intValue() : 16;
        String controlName = xaxes.get("controlName") != null ? xaxes.get("controlName").toString() : "";
        int particleSizeType = xaxes.get("particleSizeType") != null ?
            ((Number) xaxes.get("particleSizeType")).intValue() : 0;

        // 数值图 (reportType=10) xaxes.controlId 为空
        if (reportType == 10) {
            controlId = null;
        }

        node.put("controlId", controlId);
        node.put("sortType", ((Number) xaxes.getOrDefault("sortType", 0)).intValue());
        node.put("particleSizeType", particleSizeType);
        node.put("rename", (String) xaxes.getOrDefault("rename", ""));
        node.put("emptyType", ((Number) xaxes.getOrDefault("emptyType", 0)).intValue());
        node.putNull("fields");
        node.put("subTotal", false);
        node.putNull("subTotalName");
        node.put("showFormat", "0");
        node.put("displayMode", "text");
        node.put("controlName", controlName);
        node.put("controlType", controlType);
        node.putNull("dataSource");
        node.putArray("options");
        node.putNull("advancedSetting");
        node.putNull("relationControl");

        String cid = controlId != null && !controlId.isEmpty() ? controlId + "-1" : "";
        node.put("cid", cid);
        node.put("cname", controlName);
        node.put("xaxisEmptyType", ((Number) xaxes.getOrDefault("xaxisEmptyType", 0)).intValue());
        node.put("xaxisEmpty", (Boolean) xaxes.getOrDefault("xaxisEmpty", false));
        node.put("c_Id", cid);

        return node;
    }

    private ArrayNode buildYaxisListNode(List<Map<String, Object>> yaxisList) {
        ObjectMapper mapper = Jacksons.mapper();
        ArrayNode array = mapper.createArrayNode();

        if (yaxisList == null || yaxisList.isEmpty()) {
            // 默认指标：记录数量
            ObjectNode defaultY = array.addObject();
            defaultY.put("controlId", "record_count");
            defaultY.put("controlName", "记录数量");
            defaultY.put("controlType", 10000000);
            defaultY.put("magnitude", 0);
            defaultY.put("roundType", 2);
            defaultY.put("dotFormat", "1");
            defaultY.put("suffix", "");
            defaultY.put("ydot", 2);
            defaultY.put("fixType", 0);
            defaultY.put("showNumber", true);
            defaultY.put("hide", false);

            ObjectNode percent = defaultY.putObject("percent");
            percent.put("enable", false);
            percent.put("type", 2);
            percent.put("dot", "2");
            percent.put("dotFormat", "1");
            percent.put("roundType", 2);

            defaultY.put("normType", 5);
            defaultY.put("emptyShowType", 0);
            defaultY.put("dot", 0);
            defaultY.put("rename", "");
            defaultY.putObject("advancedSetting");

            return array;
        }

        for (Map<String, Object> y : yaxisList) {
            ObjectNode yNode = array.addObject();

            String controlId = y.get("controlId") != null ? y.get("controlId").toString() : "record_count";
            String controlName = y.get("controlName") != null ? y.get("controlName").toString() : "记录数量";
            int controlType = y.get("controlType") != null ?
                ((Number) y.get("controlType")).intValue() : 10000000;

            yNode.put("controlId", controlId);
            yNode.put("controlName", controlName);
            yNode.put("controlType", controlType);
            yNode.put("magnitude", 0);
            yNode.put("roundType", 2);
            yNode.put("dotFormat", "1");
            yNode.put("suffix", "");
            yNode.put("ydot", 2);
            yNode.put("fixType", 0);
            yNode.put("showNumber", true);
            yNode.put("hide", false);

            ObjectNode percent = yNode.putObject("percent");
            percent.put("enable", false);
            percent.put("type", 2);
            percent.put("dot", "2");
            percent.put("dotFormat", "1");
            percent.put("roundType", 2);

            yNode.put("normType", 5);
            yNode.put("emptyShowType", 0);
            yNode.put("dot", 0);
            yNode.put("rename", (String) y.getOrDefault("rename", ""));
            yNode.putObject("advancedSetting");
        }

        return array;
    }

    /**
     * 构建通用 displaySetup 配置
     */
    private Map<String, Object> buildDefaultDisplaySetup(int reportType, Map<String, Object> xaxes) {
        Map<String, Object> setup = new HashMap<>();
        setup.put("isPerPile", false);
        setup.put("isPile", false);
        setup.put("isAccumulate", false);
        setup.put("accumulatePerPile", null);
        setup.put("isToday", false);
        setup.put("isLifecycle", false);
        setup.put("lifecycleValue", 0);
        setup.put("contrastType", 0);
        setup.put("fontStyle", 1);
        setup.put("showTotal", false);
        setup.put("showTitle", true);
        setup.put("showLegend", true);
        setup.put("legendType", 1);
        setup.put("showDimension", true);
        setup.put("showNumber", true);
        // 饼图/漏斗图显示百分比
        setup.put("showPercent", reportType == 3 || reportType == 6);
        setup.put("showXAxisCount", 0);
        setup.put("showChartType", 1);
        setup.put("showPileTotal", true);
        setup.put("hideOverlapText", false);
        setup.put("showRowList", true);
        setup.put("showControlIds", new ArrayList<>());
        setup.put("auxiliaryLines", new ArrayList<>());
        setup.put("showOptionIds", new ArrayList<>());
        setup.put("contrast", false);
        setup.put("colorRules", new ArrayList<>());

        Map<String, Object> percent = new HashMap<>();
        percent.put("enable", false);
        percent.put("type", 2);
        percent.put("dot", "2");
        percent.put("dotFormat", "1");
        percent.put("roundType", 2);
        setup.put("percent", percent);

        return setup;
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 输入类 ==========
    public static class Input {
        private final List<WorksheetChartPlan> plans;
        private final String pageId;
        private final boolean dryRun;
        private final boolean failFast;

        public Input(List<WorksheetChartPlan> plans, String pageId,
                     boolean dryRun, boolean failFast) {
            this.plans = plans;
            this.pageId = pageId;
            this.dryRun = dryRun;
            this.failFast = failFast;
        }

        public List<WorksheetChartPlan> getPlans() { return plans; }
        public String getPageId() { return pageId; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
    }

    public static class WorksheetChartPlan {
        private final String worksheetId;
        private final String worksheetName;
        private final List<ChartDefinition> charts;

        public WorksheetChartPlan(String worksheetId, String worksheetName,
                                  List<ChartDefinition> charts) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.charts = charts;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public List<ChartDefinition> getCharts() { return charts; }
    }

    public static class ChartDefinition {
        private final String name;
        private final int reportType;
        private final Map<String, Object> xaxes;
        private final List<Map<String, Object>> yaxisList;
        private final Map<String, Object> rightY;
        private final Integer yreportType;
        private final Map<String, Object> config;
        private final Map<String, Object> pivotTable;
        private final Map<String, Object> country;

        public ChartDefinition(String name, int reportType,
                               Map<String, Object> xaxes,
                               List<Map<String, Object>> yaxisList,
                               Map<String, Object> rightY,
                               Integer yreportType,
                               Map<String, Object> config,
                               Map<String, Object> pivotTable,
                               Map<String, Object> country) {
            this.name = name;
            this.reportType = reportType;
            this.xaxes = xaxes;
            this.yaxisList = yaxisList;
            this.rightY = rightY;
            this.yreportType = yreportType;
            this.config = config;
            this.pivotTable = pivotTable;
            this.country = country;
        }

        public String getName() { return name; }
        public int getReportType() { return reportType; }
        public Map<String, Object> getXaxes() { return xaxes; }
        public List<Map<String, Object>> getYaxisList() { return yaxisList; }
        public Map<String, Object> getRightY() { return rightY; }
        public Integer getYreportType() { return yreportType; }
        public Map<String, Object> getConfig() { return config; }
        public Map<String, Object> getPivotTable() { return pivotTable; }
        public Map<String, Object> getCountry() { return country; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final boolean success;
        private final Map<String, List<ChartCreationDetail>> worksheetCharts;
        private final List<ChartCreationDetail> allCharts;
        private final String errorMessage;

        public Output(boolean success, Map<String, List<ChartCreationDetail>> worksheetCharts,
                      List<ChartCreationDetail> allCharts, String errorMessage) {
            this.success = success;
            this.worksheetCharts = worksheetCharts;
            this.allCharts = allCharts;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Map<String, List<ChartCreationDetail>> getWorksheetCharts() { return worksheetCharts; }
        public List<ChartCreationDetail> getAllCharts() { return allCharts; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class ChartCreationDetail {
        private final String name;
        private final String reportId;
        private final boolean success;
        private final String errorMessage;
        private final int reportType;
        private final String worksheetId;

        public ChartCreationDetail(String name, String reportId, boolean success,
                                   String errorMessage, int reportType, String worksheetId) {
            this.name = name;
            this.reportId = reportId;
            this.success = success;
            this.errorMessage = errorMessage;
            this.reportType = reportType;
            this.worksheetId = worksheetId;
        }

        public String getName() { return name; }
        public String getReportId() { return reportId; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public int getReportType() { return reportType; }
        public String getWorksheetId() { return worksheetId; }
    }
}
