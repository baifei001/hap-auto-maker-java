package com.hap.automaker.core.planner;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 视图筛选规划器
 *
 * Python 对应: planners/view_recommender.py
 *
 * 职责:
 * - 为每个视图推荐合适的筛选条件
 * - 基于字段类型和工作表数据特征生成筛选建议
 * - 包括时间筛选、状态筛选、人员筛选等常用类型
 */
public class ViewFilterPlanner implements Planner<ViewFilterPlanner.Input, ViewFilterPlanner.Output> {

    private static final Logger logger = LoggerFactory.getLogger(ViewFilterPlanner.class);

    @Override
    public String getName() {
        return "ViewFilterPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            List<ViewFilterPlan> filterPlans = new ArrayList<>();

            for (ViewInfo view : input.getViews()) {
                List<FilterRecommendation> filters = recommendFilters(view, input.getWorksheetFields());

                if (!filters.isEmpty()) {
                    filterPlans.add(new ViewFilterPlan(
                        view.getViewId(),
                        view.getViewName(),
                        view.getWorksheetId(),
                        filters
                    ));
                }
            }

            logger.info("✓ 视图筛选规划完成: {} 个视图, {} 个筛选条件", filterPlans.size(),
                filterPlans.stream().mapToInt(fp -> fp.getFilters().size()).sum());

            return new Output(filterPlans);

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan view filters", e);
        }
    }

    /**
     * 为视图推荐筛选条件
     */
    private List<FilterRecommendation> recommendFilters(ViewInfo view, List<FieldInfo> worksheetFields) {
        List<FilterRecommendation> filters = new ArrayList<>();

        // 1. 时间筛选 - 如果工作表有日期字段
        List<FieldInfo> dateFields = worksheetFields.stream()
            .filter(f -> "Date".equals(f.getType()) || "DateTime".equals(f.getType()))
            .toList();

        if (!dateFields.isEmpty()) {
            // 添加常用时间筛选
            filters.add(new FilterRecommendation(
                "time_range",
                "时间范围",
                dateFields.get(0).getControlId(),
                dateFields.get(0).getControlName(),
                "dynamic",
                Map.of("type", "today", "label", "今天")
            ));

            filters.add(new FilterRecommendation(
                "time_range",
                "时间范围",
                dateFields.get(0).getControlId(),
                dateFields.get(0).getControlName(),
                "dynamic",
                Map.of("type", "thisWeek", "label", "本周")
            ));

            filters.add(new FilterRecommendation(
                "time_range",
                "时间范围",
                dateFields.get(0).getControlId(),
                dateFields.get(0).getControlName(),
                "dynamic",
                Map.of("type", "thisMonth", "label", "本月")
            ));
        }

        // 2. 状态筛选 - 如果有单选字段
        List<FieldInfo> statusFields = worksheetFields.stream()
            .filter(f -> "SingleSelect".equals(f.getType()) || "Dropdown".equals(f.getType()))
            .toList();

        for (FieldInfo field : statusFields) {
            filters.add(new FilterRecommendation(
                "select",
                "选项筛选",
                field.getControlId(),
                field.getControlName(),
                "multi",
                Map.of("options", field.getOptions() != null ? field.getOptions() : List.of())
            ));
        }

        // 3. 人员筛选 - 如果有成员字段
        List<FieldInfo> peopleFields = worksheetFields.stream()
            .filter(f -> "Collaborator".equals(f.getType()) || "Department".equals(f.getType()))
            .toList();

        for (FieldInfo field : peopleFields) {
            filters.add(new FilterRecommendation(
                "people",
                "人员筛选",
                field.getControlId(),
                field.getControlName(),
                "currentUser",
                Map.of("label", "我的")
            ));

            filters.add(new FilterRecommendation(
                "people",
                "人员筛选",
                field.getControlId(),
                field.getControlName(),
                "select",
                Map.of("label", "选择人员")
            ));
        }

        // 4. 文本筛选 - 如果有文本字段
        List<FieldInfo> textFields = worksheetFields.stream()
            .filter(f -> "Text".equals(f.getType()) || "RichText".equals(f.getType()))
            .limit(1)
            .toList();

        for (FieldInfo field : textFields) {
            if (isTitleField(field)) {
                filters.add(new FilterRecommendation(
                    "text",
                    "关键词搜索",
                    field.getControlId(),
                    field.getControlName(),
                    "search",
                    Map.of("placeholder", "搜索" + field.getControlName())
                ));
            }
        }

        // 5. 数字范围筛选 - 如果有数字字段
        List<FieldInfo> numberFields = worksheetFields.stream()
            .filter(f -> "Number".equals(f.getType()) || "Money".equals(f.getType()))
            .limit(1)
            .toList();

        for (FieldInfo field : numberFields) {
            filters.add(new FilterRecommendation(
                "number",
                "数值范围",
                field.getControlId(),
                field.getControlName(),
                "range",
                Map.of("min", 0, "label", field.getControlName() + "范围")
            ));
        }

        // 限制每个视图的筛选条件数量
        int maxFilters = 5;
        if (filters.size() > maxFilters) {
            return filters.subList(0, maxFilters);
        }

        return filters;
    }

    private boolean isTitleField(FieldInfo field) {
        // 判断是否为标题字段
        String name = field.getControlName();
        return name.contains("名称") || name.contains("标题") || name.contains("姓名")
            || name.contains("标题") || name.contains("Title") || name.contains("Name");
    }

    // ========== 输入类 ==========
    public static class Input {
        private final List<ViewInfo> views;
        private final List<FieldInfo> worksheetFields;

        public Input(List<ViewInfo> views, List<FieldInfo> worksheetFields) {
            this.views = views;
            this.worksheetFields = worksheetFields;
        }

        public List<ViewInfo> getViews() { return views; }
        public List<FieldInfo> getWorksheetFields() { return worksheetFields; }
    }

    public static class ViewInfo {
        private final String viewId;
        private final String viewName;
        private final String worksheetId;
        private final int viewType;

        public ViewInfo(String viewId, String viewName, String worksheetId, int viewType) {
            this.viewId = viewId;
            this.viewName = viewName;
            this.worksheetId = worksheetId;
            this.viewType = viewType;
        }

        public String getViewId() { return viewId; }
        public String getViewName() { return viewName; }
        public String getWorksheetId() { return worksheetId; }
        public int getViewType() { return viewType; }
    }

    public static class FieldInfo {
        private final String controlId;
        private final String controlName;
        private final String type;
        private final List<String> options;

        public FieldInfo(String controlId, String controlName, String type, List<String> options) {
            this.controlId = controlId;
            this.controlName = controlName;
            this.type = type;
            this.options = options;
        }

        public String getControlId() { return controlId; }
        public String getControlName() { return controlName; }
        public String getType() { return type; }
        public List<String> getOptions() { return options; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final List<ViewFilterPlan> filterPlans;

        public Output(List<ViewFilterPlan> filterPlans) {
            this.filterPlans = filterPlans;
        }

        public List<ViewFilterPlan> getFilterPlans() { return filterPlans; }
    }

    public static class ViewFilterPlan {
        private final String viewId;
        private final String viewName;
        private final String worksheetId;
        private final List<FilterRecommendation> filters;

        public ViewFilterPlan(String viewId, String viewName, String worksheetId, List<FilterRecommendation> filters) {
            this.viewId = viewId;
            this.viewName = viewName;
            this.worksheetId = worksheetId;
            this.filters = filters;
        }

        public String getViewId() { return viewId; }
        public String getViewName() { return viewName; }
        public String getWorksheetId() { return worksheetId; }
        public List<FilterRecommendation> getFilters() { return filters; }
    }

    public static class FilterRecommendation {
        private final String filterType;
        private final String filterName;
        private final String fieldId;
        private final String fieldName;
        private final String operator;
        private final Map<String, Object> config;

        public FilterRecommendation(String filterType, String filterName,
                                     String fieldId, String fieldName,
                                     String operator, Map<String, Object> config) {
            this.filterType = filterType;
            this.filterName = filterName;
            this.fieldId = fieldId;
            this.fieldName = fieldName;
            this.operator = operator;
            this.config = config;
        }

        public String getFilterType() { return filterType; }
        public String getFilterName() { return filterName; }
        public String getFieldId() { return fieldId; }
        public String getFieldName() { return fieldName; }
        public String getOperator() { return operator; }
        public Map<String, Object> getConfig() { return config; }
    }
}
