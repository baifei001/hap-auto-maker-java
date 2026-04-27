package com.hap.automaker.core.registry;

import java.util.*;

/**
 * 图表类型注册中心 - 17种图表类型
 */
public class ChartTypeRegistry implements TypeRegistry<ChartTypeConfig> {

    private static final Map<Integer, ChartTypeConfig> BY_ID = new HashMap<>();
    private static final Map<String, ChartTypeConfig> BY_NAME = new HashMap<>();
    private static final Map<String, List<ChartTypeConfig>> BY_CATEGORY = new HashMap<>();
    private static final List<ChartTypeConfig> ALL = new ArrayList<>();

    static {
        // ========== 基础统计图 (4种) ==========
        register("Bar", 1, "柱图", "basic",
            true, true, false,
            "经典的柱状图，适合比较各类别的数值大小。支持堆叠和百分比显示。",
            new String[]{"classify", "date"},
            new String[]{"count", "numeric"},
            Map.of("isPile", false, "isPerPile", false),
            Map.of("isPile", "是否堆叠: true/false",
                   "isPerPile", "是否百分比堆叠: true/false"));

        register("Line", 2, "折线图", "basic",
            true, true, false,
            "用折线展示数据趋势，适合时间序列数据。支持多条折线对比。",
            new String[]{"date", "classify"},
            new String[]{"count", "numeric"},
            Map.of("isAccumulate", false),
            Map.of("isAccumulate", "是否累计: true/false"));

        register("Pie", 3, "饼图/环形图", "basic",
            true, true, false,
            "展示各部分占整体的比例，适合占比分析。支持环形图变体。",
            new String[]{"classify"},
            new String[]{"count", "numeric"},
            Map.of("showPercent", true),
            Map.of("showPercent", "显示百分比: true/false"));

        register("Number", 10, "数值图", "basic",
            false, true, false,
            "以数字卡片形式展示核心指标，适合仪表盘场景。无X轴，单指标。",
            new String[]{},
            new String[]{"count", "numeric"},
            Map.of(),
            Map.of());

        register("DualAxis", 7, "双轴图", "comparison",
            true, true, false,
            "左右双Y轴，适合同时展示量级差异大的两个指标（如销售额vs订单数）。",
            new String[]{"date", "classify"},
            new String[]{"count", "numeric"},
            Map.of(),
            Map.of("yreportType", "第二个Y轴配置"));

        register("SymmetryBar", 11, "对称条形图", "comparison",
            true, true, false,
            "左右对称的条形图，适合对比两个相关维度（如收入vs支出）。",
            new String[]{"classify"},
            new String[]{"count", "numeric"},
            Map.of(),
            Map.of());

        // 注意：雷达图(Radar)已合并入透视表，共用reportType=8

        register("Funnel", 6, "漏斗图", "flow",
            true, true, false,
            "展示流程各阶段的转化率，适合销售漏斗、用户转化分析。",
            new String[]{"classify"},
            new String[]{"count", "numeric"},
            Map.of(),
            Map.of());

        register("Progress", 15, "进度图", "flow",
            false, true, false,
            "展示目标完成进度，适合KPI完成情况。无X轴，单进度指标。",
            new String[]{},
            new String[]{"numeric"},
            Map.of(),
            Map.of("target", "目标值", "current", "当前值"));

        register("Scatter", 12, "散点图", "distribution",
            true, true, false,
            "展示两个数值变量的相关性，适合相关性分析、聚类分析。",
            new String[]{"numeric"},
            new String[]{"numeric"},
            Map.of(),
            Map.of("xControlType", "X轴必须是数值字段"));

        register("WordCloud", 13, "词云图", "distribution",
            true, true, false,
            "以文字大小展示词频，适合文本数据分析。",
            new String[]{"text"},
            new String[]{"count"},
            Map.of(),
            Map.of());

        register("PivotTable", 8, "透视表", "distribution",
            true, true, false,
            "多维度交叉分析表格，类似Excel透视表。支持行/列/值配置。",
            new String[]{"classify", "date"},
            new String[]{"count", "numeric"},
            Map.of("showTotal", true, "mergeCell", true),
            Map.of("rowFields", "行维度字段列表",
                   "colFields", "列维度字段列表",
                   "metrics", "指标配置列表"));

        register("Map", 17, "地图", "geo",
            true, true, false,
            "在地图上展示地域分布数据，需要Area或Location字段。",
            new String[]{"geo"},
            new String[]{"count", "numeric"},
            Map.of(),
            Map.of("mapType", "地图类型: china/world/province",
                   "zoom", "默认缩放级别"));

        register("RegionMap", 9, "行政区划图", "geo",
            true, true, false,
            "按行政区划层级展示数据，适合省市区县分布。需要Region字段。",
            new String[]{"region"},
            new String[]{"count", "numeric"},
            Map.of(),
            Map.of("regionLevel", "层级: 1=省, 2=市, 3=区"));

        register("Gauge", 14, "仪表盘", "gauge",
            false, true, false,
            "类似汽车仪表盘的进度展示，适合KPI实时监控。无X轴，单指标。",
            new String[]{},
            new String[]{"numeric"},
            Map.of(),
            Map.of("min", "最小值", "max", "最大值", "thresholds", "阈值区间"));

        register("Ranking", 16, "排行图", "gauge",
            true, true, false,
            "展示TOP N排行榜，支持正序/倒序。",
            new String[]{"classify"},
            new String[]{"count", "numeric"},
            Map.of(),
            Map.of("topN", "显示数量", "order", "排序: asc/desc"));
    }

    private static void register(String key, int reportType, String name, String category,
                                  boolean needsXAxis, boolean needsYAxis, boolean aiDisabled,
                                  String doc,
                                  String[] recommendedXAxisTypes,
                                  String[] recommendedYAxisTypes,
                                  Map<String, Object> defaultConfig,
                                  Map<String, String> configHints) {
        ChartTypeConfig cfg = new ChartTypeConfig();
        cfg.setReportType(reportType);
        cfg.setName(name);
        cfg.setCategory(category);
        cfg.setNeedsXAxis(needsXAxis);
        cfg.setNeedsYAxis(needsYAxis);
        cfg.setAiDisabled(aiDisabled);
        cfg.setDoc(doc);
        cfg.setRecommendedXAxisTypes(recommendedXAxisTypes);
        cfg.setRecommendedYAxisTypes(recommendedYAxisTypes);
        if (defaultConfig != null) cfg.setDefaultConfig(new HashMap<>(defaultConfig));
        if (configHints != null) cfg.setConfigHints(new HashMap<>(configHints));

        BY_ID.put(reportType, cfg);
        BY_NAME.put(key, cfg);
        ALL.add(cfg);
        BY_CATEGORY.computeIfAbsent(category, k -> new ArrayList<>()).add(cfg);
    }

    @Override
    public ChartTypeConfig getById(int id) {
        return BY_ID.get(id);
    }

    @Override
    public ChartTypeConfig getByName(String name) {
        return BY_NAME.get(name);
    }

    @Override
    public List<ChartTypeConfig> getByCategory(String category) {
        return BY_CATEGORY.getOrDefault(category, Collections.emptyList());
    }

    @Override
    public List<ChartTypeConfig> getAll() {
        return Collections.unmodifiableList(ALL);
    }

    @Override
    public boolean validateConfig(String typeId, Map<String, Object> config) {
        if (typeId == null || typeId.isEmpty()) {
            return false;
        }

        ChartTypeConfig chartConfig = BY_NAME.get(typeId);
        if (chartConfig == null) {
            return false; // 未知图表类型
        }

        // 检查 AI 禁用图表
        if (chartConfig.isAiDisabled()) {
            return false;
        }

        // 检查 X 轴字段配置
        if (chartConfig.isNeedsXAxis()) {
            Object xAxisField = config.get("xAxis");
            Object xIds = config.get("xids"); // 另一种X轴字段配置
            if ((xAxisField == null || String.valueOf(xAxisField).isEmpty())
                && (xIds == null || (xIds instanceof List && ((List<?>) xIds).isEmpty()))) {
                return false; // 需要提供X轴字段
            }
        }

        // 检查 Y 轴字段配置 (指标字段)
        if (chartConfig.isNeedsYAxis()) {
            Object metrics = config.get("metrics");
            if (metrics == null || (metrics instanceof List && ((List<?>) metrics).isEmpty())) {
                return false; // 需要提供Y轴指标字段
            }
        }

        // 数值图、进度图、仪表盘不需要X轴但需要Y轴指标
        // 这些类型已经在上面检查过了

        return true;
    }

    @Override
    public String generatePrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 可用图表类型（共").append(ALL.size()).append("种）\n\n");

        String[] categories = {"basic", "comparison", "flow", "distribution", "geo", "gauge"};
        for (String cat : categories) {
            List<ChartTypeConfig> types = BY_CATEGORY.get(cat);
            if (types == null || types.isEmpty()) continue;
            sb.append("## ").append(getCategoryName(cat)).append("\n");
            for (ChartTypeConfig t : types) {
                if (t.isAiDisabled()) continue;
                sb.append(t.toPromptString()).append("\n");
            }
            sb.append("\n");
        }

        // 添加配置说明
        sb.append("## 配置说明\n");
        sb.append("- X轴字段类型: classify=分类(单选/多选/成员), date=日期, numeric=数值, geo=地理\n");
        sb.append("- Y轴字段类型: count=记录数, numeric=数值字段(求和/平均/最大/最小)\n");
        sb.append("- 无需X轴: 数值图、进度图、仪表盘 (无X轴配置)\n");

        return sb.toString();
    }

    private String getCategoryName(String category) {
        return switch (category) {
            case "basic" -> "基础统计图";
            case "comparison" -> "对比分析图";
            case "flow" -> "流程分析图";
            case "distribution" -> "数据分布图";
            case "geo" -> "地理/空间图";
            case "gauge" -> "仪表盘组件";
            default -> category;
        };
    }

    @Override
    public int size() {
        return ALL.size();
    }

    // Python 兼容性查询
    public int getReportTypeByName(String name) {
        ChartTypeConfig cfg = BY_NAME.get(name);
        return cfg != null ? cfg.getReportType() : -1;
    }

    public Set<String> getPlannableTypes() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, ChartTypeConfig> e : BY_NAME.entrySet()) {
            if (!e.getValue().isAiDisabled()) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    /**
     * 获取聚合类型说明 (normType)
     */
    public static Map<Integer, String> getNormTypeNames() {
        return Map.of(
            1, "SUM（求和）",
            2, "AVG（平均值）",
            3, "MAX（最大值）",
            4, "MIN（最小值）",
            5, "COUNT（计数）",
            6, "COUNT DISTINCT（去重计数）"
        );
    }

    /**
     * 获取日期粒度说明 (particleSizeType)
     */
    public static Map<Integer, String> getParticleSizeNames() {
        return Map.of(
            0, "不分组（默认）",
            1, "按月",
            2, "按季度",
            3, "按年",
            4, "按天",
            5, "按周",
            6, "按小时"
        );
    }

    /**
     * 获取时间范围类型说明 (rangeType)
     */
    public static Map<Integer, String> getRangeTypeNames() {
        return Map.of(
            0, "不限时间",
            1, "今天",
            2, "昨天",
            3, "本周",
            5, "本月",
            6, "本季度",
            7, "本年",
            18, "近N天"
        );
    }
}
