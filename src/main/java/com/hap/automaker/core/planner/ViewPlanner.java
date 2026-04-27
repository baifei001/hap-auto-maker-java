package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.registry.ViewTypeRegistry;
import com.hap.automaker.core.registry.ViewTypeConfig;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 视图规划器
 *
 * Python 对应: view_recommender.py + view_configurator.py
 *
 * 职责:
 * - 为每个工作表推荐合适的视图类型
 * - 基于工作表字段选择合适的视图配置
 * - 生成完整的视图配置（包括二次保存配置）
 *
 * 视图推荐策略:
 * - 表格视图(0): 所有工作表默认创建
 * - 看板视图(1): 有单选字段时创建
 * - 日历视图(4): 有日期字段时创建
 * - 甘特图(5): 有日期/日期时间字段时创建
 * - 画廊视图(3): 有图片附件字段时创建
 * - 地图视图(7): 有地区/定位字段时创建
 * - 层级视图(2): 有自关联字段时创建（暂缓）
 * - 资源视图(9): 有成员+日期字段时创建
 */
public class ViewPlanner implements Planner<ViewPlanner.Input, ViewPlanner.Output> {

    private static final Logger logger = LoggerFactory.getLogger(ViewPlanner.class);

    private final AiTextClient aiClient;
    private final ViewTypeRegistry viewTypeRegistry;

    private static final int MAX_RETRIES = 2;

    // 视图类型的字段要求
    private static final Map<Integer, List<String>> VIEW_TYPE_FIELD_REQUIREMENTS = Map.of(
        0, List.of(), // 表格视图 - 无要求
        1, List.of("SingleSelect", "Dropdown"), // 看板视图 - 需要单选
        3, List.of("Attachment"), // 画廊视图 - 需要图片附件
        4, List.of("Date", "DateTime"), // 日历视图 - 需要日期
        5, List.of("Date", "DateTime"), // 甘特图 - 需要日期
        7, List.of("Location", "Region"), // 地图视图 - 需要定位
        9, List.of("Collaborator", "Department") // 资源视图 - 需要成员
    );

    // 每个工作表最大视图数
    private static final int MAX_VIEWS_PER_WORKSHEET = 6;

    public ViewPlanner(AiTextClient aiClient) {
        this.aiClient = aiClient;
        this.viewTypeRegistry = new ViewTypeRegistry();
    }

    @Override
    public String getName() {
        return "ViewPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            List<WorksheetViewPlan> worksheetPlans = new ArrayList<>();

            for (WorksheetInfo ws : input.getWorksheets()) {
                // 基于字段特征推荐视图
                List<ViewRecommendation> recommendations = recommendViews(ws);

                // 为每个推荐视图生成详细配置
                List<ViewPlan> viewPlans = new ArrayList<>();
                for (ViewRecommendation rec : recommendations) {
                    ViewConfig config = generateViewConfig(rec, ws);
                    if (config != null) {
                        viewPlans.add(new ViewPlan(
                            rec.getViewType(),
                            rec.getName(),
                            rec.getReason(),
                            config
                        ));
                    }
                }

                if (!viewPlans.isEmpty()) {
                    worksheetPlans.add(new WorksheetViewPlan(
                        ws.getWorksheetId(),
                        ws.getWorksheetName(),
                        ws.getFields(),
                        viewPlans
                    ));
                }
            }

            logger.info("✓ 视图规划完成: {} 个工作表, {} 个视图", worksheetPlans.size(),
                worksheetPlans.stream().mapToInt(wp -> wp.getViews().size()).sum());

            return new Output(worksheetPlans);

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan views", e);
        }
    }

    /**
     * 基于工作表字段特征推荐合适的视图类型
     */
    private List<ViewRecommendation> recommendViews(WorksheetInfo ws) {
        List<ViewRecommendation> recommendations = new ArrayList<>();
        List<FieldInfo> fields = ws.getFields();

        // 1. 表格视图 - 所有工作表默认创建
        recommendations.add(new ViewRecommendation(
            0,
            "全部",
            "默认表格视图，显示所有记录",
            100 // 优先级最高
        ));

        // 分析字段类型
        boolean hasSingleSelect = fields.stream().anyMatch(f ->
            "SingleSelect".equals(f.getType()) || "Dropdown".equals(f.getType()));
        boolean hasDate = fields.stream().anyMatch(f ->
            "Date".equals(f.getType()) || "DateTime".equals(f.getType()));
        boolean hasAttachment = fields.stream().anyMatch(f ->
            "Attachment".equals(f.getType()));
        boolean hasLocation = fields.stream().anyMatch(f ->
            "Location".equals(f.getType()) || "Region".equals(f.getType()));
        boolean hasCollaborator = fields.stream().anyMatch(f ->
            "Collaborator".equals(f.getType()) || "Department".equals(f.getType()));

        // 2. 看板视图 - 有单选字段时创建
        if (hasSingleSelect) {
            String groupField = fields.stream()
                .filter(f -> "SingleSelect".equals(f.getType()) || "Dropdown".equals(f.getType()))
                .findFirst()
                .map(FieldInfo::getControlId)
                .orElse("");

            recommendations.add(new ViewRecommendation(
                1,
                "看板",
                "按状态/阶段分组的看板视图",
                90
            ).withViewControl(groupField));
        }

        // 3. 画廊视图 - 有附件字段时创建
        if (hasAttachment) {
            recommendations.add(new ViewRecommendation(
                3,
                "画廊",
                "以图片/附件为封面的卡片视图",
                80
            ));
        }

        // 4. 日历视图 - 有日期字段时创建
        if (hasDate) {
            List<String> dateFields = fields.stream()
                .filter(f -> "Date".equals(f.getType()) || "DateTime".equals(f.getType()))
                .map(FieldInfo::getControlId)
                .toList();

            recommendations.add(new ViewRecommendation(
                4,
                "日历",
                "按日期查看记录的日历视图",
                70
            ).withDateFields(dateFields));
        }

        // 5. 甘特图 - 有日期字段时创建
        if (hasDate) {
            List<String> dateFields = fields.stream()
                .filter(f -> "Date".equals(f.getType()) || "DateTime".equals(f.getType()))
                .map(FieldInfo::getControlId)
                .toList();

            recommendations.add(new ViewRecommendation(
                5,
                "甘特图",
                "项目/任务时间线视图",
                60
            ).withDateFields(dateFields));
        }

        // 6. 地图视图 - 有定位字段时创建
        if (hasLocation) {
            String locationField = fields.stream()
                .filter(f -> "Location".equals(f.getType()) || "Region".equals(f.getType()))
                .findFirst()
                .map(FieldInfo::getControlId)
                .orElse("");

            recommendations.add(new ViewRecommendation(
                7,
                "地图",
                "在地图上展示记录位置",
                50
            ).withViewControl(locationField));
        }

        // 7. 资源视图 - 有成员+日期字段时创建
        if (hasCollaborator && hasDate) {
            String memberField = fields.stream()
                .filter(f -> "Collaborator".equals(f.getType()) || "Department".equals(f.getType()))
                .findFirst()
                .map(FieldInfo::getControlId)
                .orElse("");

            List<String> dateFields = fields.stream()
                .filter(f -> "Date".equals(f.getType()) || "DateTime".equals(f.getType()))
                .map(FieldInfo::getControlId)
                .toList();

            recommendations.add(new ViewRecommendation(
                9,
                "资源",
                "按成员/资源分组的时间线视图",
                40
            ).withViewControl(memberField).withDateFields(dateFields));
        }

        // 限制每个工作表的视图数量
        if (recommendations.size() > MAX_VIEWS_PER_WORKSHEET) {
            recommendations.sort(Comparator.comparingInt(ViewRecommendation::getPriority).reversed());
            recommendations = recommendations.subList(0, MAX_VIEWS_PER_WORKSHEET);
        }

        return recommendations;
    }

    /**
     * 为推荐视图生成详细配置
     */
    private ViewConfig generateViewConfig(ViewRecommendation rec, WorksheetInfo ws) {
        ViewConfig config = new ViewConfig();

        // 选择显示的字段（5-8个最重要的）
        List<String> displayControls = selectImportantFields(ws.getFields(), 8);
        config.setDisplayControls(displayControls);

        // 根据视图类型设置特定配置
        switch (rec.getViewType()) {
            case 0: // 表格视图
                config.setAdvancedSetting(Map.of(
                    "enablerules", "1",
                    "navempty", "1",
                    "coverstyle", "{\"position\":\"1\",\"style\":3}"
                ));
                break;

            case 1: // 看板视图
                config.setViewControl(rec.getViewControl());
                config.setAdvancedSetting(Map.of(
                    "enablerules", "1",
                    "navempty", "1",
                    "coverstyle", "{\"position\":\"1\",\"style\":3}"
                ));
                break;

            case 3: // 画廊视图
                config.setAdvancedSetting(Map.of(
                    "enablerules", "1",
                    "navempty", "1",
                    "coverstyle", "{\"position\":\"2\"}"
                ));
                break;

            case 4: // 日历视图
                // 需要二次保存设置日期字段
                if (rec.getDateFields() != null && !rec.getDateFields().isEmpty()) {
                    String dateField = rec.getDateFields().get(0);
                    String calendarCids = "[{\"begin\":\"" + dateField + "\",\"end\":\"\"}]";
                    config.setPostCreateUpdates(List.of(
                        Map.of(
                            "editAttrs", List.of("advancedSetting"),
                            "editAdKeys", List.of("calendarcids"),
                            "advancedSetting", Map.of("calendarcids", calendarCids)
                        )
                    ));
                }
                config.setAdvancedSetting(Map.of(
                    "enablerules", "1",
                    "navempty", "1"
                ));
                break;

            case 5: // 甘特图
                if (rec.getDateFields() != null && !rec.getDateFields().isEmpty()) {
                    String beginDate = rec.getDateFields().get(0);
                    String endDate = rec.getDateFields().size() > 1 ?
                        rec.getDateFields().get(1) : beginDate;
                    config.setPostCreateUpdates(List.of(
                        Map.of(
                            "editAttrs", List.of("advancedSetting"),
                            "editAdKeys", List.of("begindate", "enddate"),
                            "advancedSetting", Map.of(
                                "begindate", beginDate,
                                "enddate", endDate
                            )
                        )
                    ));
                }
                config.setAdvancedSetting(Map.of(
                    "enablerules", "1",
                    "navempty", "1"
                ));
                break;

            case 7: // 地图视图
                config.setViewControl(rec.getViewControl());
                config.setAdvancedSetting(Map.of(
                    "enablerules", "1",
                    "navempty", "1"
                ));
                break;

            case 9: // 资源视图
                if (rec.getDateFields() != null && !rec.getDateFields().isEmpty()) {
                    String beginDate = rec.getDateFields().get(0);
                    String endDate = rec.getDateFields().size() > 1 ?
                        rec.getDateFields().get(1) : beginDate;
                    config.setPostCreateUpdates(List.of(
                        Map.of(
                            "editAttrs", List.of("advancedSetting"),
                            "editAdKeys", List.of("resourceId", "startdate", "enddate"),
                            "advancedSetting", Map.of(
                                "resourceId", rec.getViewControl(),
                                "startdate", beginDate,
                                "enddate", endDate
                            )
                        )
                    ));
                }
                config.setViewControl(rec.getViewControl());
                config.setAdvancedSetting(Map.of(
                    "enablerules", "1",
                    "navempty", "1"
                ));
                break;
        }

        return config;
    }

    /**
     * 选择最重要的字段用于视图显示
     */
    private List<String> selectImportantFields(List<FieldInfo> fields, int maxCount) {
        List<String> result = new ArrayList<>();

        // 优先选择关键字段：名称/标题、状态、负责人、日期等
        List<String> priorityTypes = List.of("Text", "SingleSelect", "Collaborator", "Date", "DateTime");

        for (String type : priorityTypes) {
            for (FieldInfo field : fields) {
                if (result.contains(field.getControlId())) continue;
                if (type.equals(field.getType())) {
                    result.add(field.getControlId());
                    if (result.size() >= maxCount) break;
                }
            }
            if (result.size() >= maxCount) break;
        }

        // 补充其他字段
        for (FieldInfo field : fields) {
            if (result.contains(field.getControlId())) continue;
            result.add(field.getControlId());
            if (result.size() >= maxCount) break;
        }

        return result;
    }

    // ========== 内部类 ==========

    private static class ViewRecommendation {
        private final int viewType;
        private final String name;
        private final String reason;
        private final int priority;
        private String viewControl;
        private List<String> dateFields;

        ViewRecommendation(int viewType, String name, String reason, int priority) {
            this.viewType = viewType;
            this.name = name;
            this.reason = reason;
            this.priority = priority;
        }

        ViewRecommendation withViewControl(String viewControl) {
            this.viewControl = viewControl;
            return this;
        }

        ViewRecommendation withDateFields(List<String> dateFields) {
            this.dateFields = dateFields;
            return this;
        }

        int getViewType() { return viewType; }
        String getName() { return name; }
        String getReason() { return reason; }
        int getPriority() { return priority; }
        String getViewControl() { return viewControl; }
        List<String> getDateFields() { return dateFields; }
    }

    // ========== 输入类 ==========
    public static class Input {
        private final List<WorksheetInfo> worksheets;
        private final String language;

        public Input(List<WorksheetInfo> worksheets, String language) {
            this.worksheets = worksheets;
            this.language = language != null ? language : "zh";
        }

        public List<WorksheetInfo> getWorksheets() { return worksheets; }
        public String getLanguage() { return language; }
    }

    public static class WorksheetInfo {
        private final String worksheetId;
        private final String worksheetName;
        private final List<FieldInfo> fields;

        public WorksheetInfo(String worksheetId, String worksheetName, List<FieldInfo> fields) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.fields = fields;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public List<FieldInfo> getFields() { return fields; }
    }

    public static class FieldInfo {
        private final String controlId;
        private final String controlName;
        private final String type;

        public FieldInfo(String controlId, String controlName, String type) {
            this.controlId = controlId;
            this.controlName = controlName;
            this.type = type;
        }

        public String getControlId() { return controlId; }
        public String getControlName() { return controlName; }
        public String getType() { return type; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final List<WorksheetViewPlan> worksheetPlans;

        public Output(List<WorksheetViewPlan> worksheetPlans) {
            this.worksheetPlans = worksheetPlans;
        }

        public List<WorksheetViewPlan> getWorksheetPlans() { return worksheetPlans; }
    }

    public static class WorksheetViewPlan {
        private final String worksheetId;
        private final String worksheetName;
        private final List<FieldInfo> fields;
        private final List<ViewPlan> views;

        public WorksheetViewPlan(String worksheetId, String worksheetName,
                                 List<FieldInfo> fields, List<ViewPlan> views) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.fields = fields;
            this.views = views;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public List<FieldInfo> getFields() { return fields; }
        public List<ViewPlan> getViews() { return views; }
    }

    public static class ViewPlan {
        private final int viewType;
        private final String name;
        private final String reason;
        private final ViewConfig config;

        public ViewPlan(int viewType, String name, String reason, ViewConfig config) {
            this.viewType = viewType;
            this.name = name;
            this.reason = reason;
            this.config = config;
        }

        public int getViewType() { return viewType; }
        public String getName() { return name; }
        public String getReason() { return reason; }
        public ViewConfig getConfig() { return config; }
    }

    public static class ViewConfig {
        private List<String> displayControls = new ArrayList<>();
        private String viewControl = "";
        private Map<String, Object> advancedSetting = new HashMap<>();
        private List<Map<String, Object>> postCreateUpdates = new ArrayList<>();

        public List<String> getDisplayControls() { return displayControls; }
        public void setDisplayControls(List<String> displayControls) { this.displayControls = displayControls; }

        public String getViewControl() { return viewControl; }
        public void setViewControl(String viewControl) { this.viewControl = viewControl; }

        public Map<String, Object> getAdvancedSetting() { return advancedSetting; }
        public void setAdvancedSetting(Map<String, Object> advancedSetting) { this.advancedSetting = advancedSetting; }

        public List<Map<String, Object>> getPostCreateUpdates() { return postCreateUpdates; }
        public void setPostCreateUpdates(List<Map<String, Object>> postCreateUpdates) { this.postCreateUpdates = postCreateUpdates; }
    }
}
