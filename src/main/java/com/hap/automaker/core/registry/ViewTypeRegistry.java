package com.hap.automaker.core.registry;

import java.util.*;

/**
 * 视图类型注册中心 - 11种视图类型
 */
public class ViewTypeRegistry implements TypeRegistry<ViewTypeConfig> {

    private static final Map<Integer, ViewTypeConfig> BY_ID = new HashMap<>();
    private static final Map<String, ViewTypeConfig> BY_NAME = new HashMap<>();
    private static final Map<String, List<ViewTypeConfig>> BY_CATEGORY = new HashMap<>();
    private static final List<ViewTypeConfig> ALL = new ArrayList<>();

    static {
        // ========== 基础视图 (3种) ==========
        register("Table", 0, "表格", "basic", false, null,
            "经典表格展示，支持排序、筛选、分组。适用于大多数场景。",
            null,
            Map.of("sortType", 0,
                   "coverType", 0,
                   "showControlName", true),
            Map.of("sortType", "排序方式: 0=默认, 1=时间降序...",
                   "coverType", "封面显示: 0=无, 1=附件, 2=封面",
                   "groupFilters", "分组筛选配置(JSON)"));

        register("Detail", 6, "详情视图", "basic", false, null,
            "单条记录的详细展示，可自定义字段布局。",
            null,
            Map.of(),
            Map.of("displayControls", "显示的字段列表"));

        register("Quick", 8, "快速视图", "basic", false, null,
            "简洁的快速查看视图。",
            null,
            Map.of(),
            Map.of());

        // ========== 视觉视图 (3种) ==========
        register("Kanban", 1, "看板", "visual", false, null,
            "以卡片形式展示，按字段分组拖拽。需要SingleSelect/MultipleSelect字段。",
            new String[]{"SingleSelect", "MultipleSelect"},
            Map.of("coverType", 0),
            Map.of("viewControl", "分组字段ID(必须提供)",
                   "colorConfig", "颜色配置"));

        register("Gallery", 3, "画廊", "visual", false, null,
            "卡片网格布局，适合图片展示。支持封面和富文本预览。",
            null,
            Map.of("coverType", 1,
                   "coverstyle", "{\"position\":\"2\"}"),
            Map.of("coverType", "封面类型: 1=附件, 2=封面",
                   "coverstyle", "封面样式JSON"));

        register("Map", 7, "地图", "visual", true,
            "需要地理位置字段",
            "在地图上定位记录位置。需要Location或Area字段。",
            new String[]{"Location", "Area"},
            Map.of(),
            Map.of("locationField", "定位字段ID"));

        // ========== 时间视图 (3种) ==========
        register("Calendar", 4, "日历", "time", false, null,
            "以日历形式展示记录。需要Date/DateTime字段。",
            new String[]{"Date", "DateTime"},
            Map.of(),
            Map.of("calendarcids", "日期字段配置JSON",
                   "begindate", "开始日期字段ID",
                   "enddate", "结束日期字段ID"));

        register("Gantt", 5, "甘特图", "time", false, null,
            "项目管理甘特图，展示任务时间线。需要两个Date字段。",
            new String[]{"Date", "DateTime"},
            Map.of(),
            Map.of("begindate", "开始日期字段ID(必须)",
                   "enddate", "结束日期字段ID(必须)",
                   "progressField", "进度字段ID"));

        register("Resource", 9, "资源视图", "time", false, null,
            "资源分配视图，展示人员或资源占用情况。",
            new String[]{"Collaborator", "Department"},
            Map.of(),
            Map.of("resourceField", "资源字段ID",
                   "timeField", "时间字段ID"));

        // ========== 特殊视图 (2种) ==========
        register("Hierarchy", 2, "层级", "special", true,
            "需要自关联关系",
            "展示自关联数据的树形层级结构。需要工作表有自关联字段。",
            new String[]{"Relation"},
            Map.of(),
            Map.of("parentField", "父级关联字段ID"));

        register("Custom", 10, "自定义视图", "advanced", true,
            "需要前端开发",
            "完全自定义的视图，需要前端开发。",
            null,
            Map.of(),
            Map.of("customConfig", "自定义配置(JSON)"));
    }

    private static void register(String key, int viewType, String name, String category,
                                  boolean aiDisabled, String aiDisabledReason,
                                  String doc, String[] requiredFieldTypes,
                                  Map<String, Object> defaultConfig,
                                  Map<String, String> configHints) {
        ViewTypeConfig cfg = new ViewTypeConfig();
        cfg.setViewType(viewType);
        cfg.setName(name);
        cfg.setCategory(category);
        cfg.setAiDisabled(aiDisabled);
        cfg.setAiDisabledReason(aiDisabledReason);
        cfg.setDoc(doc);
        cfg.setRequiredFieldTypes(requiredFieldTypes);
        if (defaultConfig != null) cfg.setDefaultConfig(new HashMap<>(defaultConfig));
        if (configHints != null) cfg.setConfigHints(new HashMap<>(configHints));

        BY_ID.put(viewType, cfg);
        BY_NAME.put(key, cfg);
        ALL.add(cfg);
        BY_CATEGORY.computeIfAbsent(category, k -> new ArrayList<>()).add(cfg);
    }

    @Override
    public ViewTypeConfig getById(int id) {
        return BY_ID.get(id);
    }

    @Override
    public ViewTypeConfig getByName(String name) {
        return BY_NAME.get(name);
    }

    @Override
    public List<ViewTypeConfig> getByCategory(String category) {
        return BY_CATEGORY.getOrDefault(category, Collections.emptyList());
    }

    @Override
    public List<ViewTypeConfig> getAll() {
        return Collections.unmodifiableList(ALL);
    }

    @Override
    public boolean validateConfig(String typeId, Map<String, Object> config) {
        if (typeId == null || typeId.isEmpty()) {
            return false;
        }

        ViewTypeConfig viewConfig = BY_NAME.get(typeId);
        if (viewConfig == null) {
            return false; // 未知视图类型
        }

        // 检查 AI 禁用视图
        if (viewConfig.isAiDisabled()) {
            return false;
        }

        // 需要特定字段类型的视图检查
        String[] requiredFieldTypes = viewConfig.getRequiredFieldTypes();
        if (requiredFieldTypes != null && requiredFieldTypes.length > 0) {
            // 检查配置中是否提供了必需的字段
            Object viewControl = config.get("viewControl");
            if (viewControl == null || String.valueOf(viewControl).isEmpty()) {
                // 某些视图需要指定字段（如看板需要分组字段）
                if (viewConfig.getViewType() == 1) { // 看板
                    return false; // 看板必须提供分组字段
                }
            }
        }

        // 时间视图需要日期字段配置
        Set<Integer> timeViews = Set.of(4, 5, 9); // 日历、甘特图、资源视图
        if (timeViews.contains(viewConfig.getViewType())) {
            Object beginDate = config.get("begindate");
            // 至少需要一个日期字段
            if (beginDate == null || String.valueOf(beginDate).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public String generatePrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 可用视图类型（共").append(ALL.size()).append("种）\n\n");

        String[] categories = {"basic", "visual", "time", "special", "advanced"};
        for (String cat : categories) {
            List<ViewTypeConfig> types = BY_CATEGORY.get(cat);
            if (types == null || types.isEmpty()) continue;
            sb.append("## ").append(getCategoryName(cat)).append("\n");
            for (ViewTypeConfig t : types) {
                if (t.isAiDisabled()) continue;
                sb.append(t.toPromptString()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String getCategoryName(String category) {
        return switch (category) {
            case "basic" -> "基础视图";
            case "visual" -> "视觉视图";
            case "time" -> "时间视图";
            case "special" -> "特殊视图";
            case "advanced" -> "高级视图";
            default -> category;
        };
    }

    @Override
    public int size() {
        return ALL.size();
    }

    // Python 兼容性查询
    public int getViewTypeByName(String name) {
        ViewTypeConfig cfg = BY_NAME.get(name);
        return cfg != null ? cfg.getViewType() : -1;
    }

    public Set<String> getPlannableTypes() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, ViewTypeConfig> e : BY_NAME.entrySet()) {
            if (!e.getValue().isAiDisabled()) {
                result.add(e.getKey());
            }
        }
        return result;
    }
}
