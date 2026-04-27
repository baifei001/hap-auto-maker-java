package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * 视图筛选创建执行器
 *
 * Python 对应: executors/pipeline_tableview_filters_v2.py
 *
 * 职责:
 * - 根据 ViewFilterPlanner 的推荐创建视图筛选条件
 * - 调用 Web API 保存筛选配置
 * - 支持批量创建多个视图的筛选条件
 *
 * API 调用:
 * - POST /api/Worksheet/filters/save - 保存视图筛选条件
 */
public class ViewFilterCreator implements Executor<ViewFilterCreator.Input, ViewFilterCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(ViewFilterCreator.class);

    private final HapApiClient apiClient;
    private final ExecutorService executor;

    public ViewFilterCreator(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "ViewFilterCreator";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        Map<String, List<FilterCreationDetail>> viewFilters = new ConcurrentHashMap<>();
        List<FilterCreationDetail> allFilters = new CopyOnWriteArrayList<>();

        try {
            List<Callable<Void>> tasks = new ArrayList<>();

            for (ViewFilterPlan filterPlan : input.getPlans()) {
                tasks.add(() -> {
                    List<FilterCreationDetail> details = createFiltersForView(filterPlan, input);
                    viewFilters.put(filterPlan.getViewId(), details);
                    allFilters.addAll(details);
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
                            throw new ExecutorException(getName(), "Filter creation failed", e.getCause());
                        }
                    }
                }
            }

            boolean allSuccess = allFilters.stream().allMatch(FilterCreationDetail::isSuccess);
            return new Output(allSuccess, viewFilters, allFilters, null);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to create view filters", e);
        }
    }

    private List<FilterCreationDetail> createFiltersForView(ViewFilterPlan plan, Input input) {
        List<FilterCreationDetail> details = new ArrayList<>();

        for (FilterDefinition filter : plan.getFilters()) {
            try {
                String filterId = createFilter(plan.getViewId(), plan.getWorksheetId(), filter, input);
                details.add(new FilterCreationDetail(
                    filter.getFilterName(),
                    filterId,
                    true,
                    null,
                    filter.getFilterType(),
                    plan.getViewId()
                ));
            } catch (Exception e) {
                details.add(new FilterCreationDetail(
                    filter.getFilterName(),
                    null,
                    false,
                    e.getMessage(),
                    filter.getFilterType(),
                    plan.getViewId()
                ));
                if (input.isFailFast()) {
                    break;
                }
            }
        }

        return details;
    }

    private String createFilter(String viewId, String worksheetId, FilterDefinition filter, Input input) throws Exception {
        if (input.isDryRun()) {
            logger.info("[DryRun] Would create filter: {} for view {} (type={})",
                filter.getFilterName(), viewId, filter.getFilterType());
            return "dry-run-filter-id";
        }

        // 构建筛选配置 JSON
        ObjectNode filterConfig = buildFilterConfig(viewId, worksheetId, filter);

        // 调用 API 保存筛选条件
        JsonNode response = apiClient.saveWorksheetViewFilter(worksheetId, viewId, filterConfig);

        // 从响应中提取 filterId
        String filterId = "";
        JsonNode data = response.path("data");
        if (!data.isMissingNode() && data.isObject()) {
            filterId = data.path("filterId").asText();
            if (filterId.isEmpty()) {
                filterId = data.path("id").asText();
            }
        }

        logger.info("✓ 筛选条件创建成功: {} (ID: {}, view={})",
            filter.getFilterName(), filterId, viewId);

        return filterId;
    }

    private ObjectNode buildFilterConfig(String viewId, String worksheetId, FilterDefinition filter) {
        ObjectNode config = Jacksons.mapper().createObjectNode();

        config.put("worksheetId", worksheetId);
        config.put("viewId", viewId);
        config.put("name", filter.getFilterName());
        config.put("filterType", filter.getFilterType());

        // 根据筛选类型构建不同的配置
        switch (filter.getFilterType()) {
            case "time_range":
                buildTimeRangeFilter(config, filter);
                break;
            case "select":
                buildSelectFilter(config, filter);
                break;
            case "people":
                buildPeopleFilter(config, filter);
                break;
            case "number":
                buildNumberFilter(config, filter);
                break;
            case "text":
                buildTextFilter(config, filter);
                break;
            default:
                // 默认配置
                config.put("controlId", filter.getFieldId());
                config.put("controlName", filter.getFieldName());
        }

        return config;
    }

    private void buildTimeRangeFilter(ObjectNode config, FilterDefinition filter) {
        config.put("controlId", filter.getFieldId());
        config.put("controlName", filter.getFieldName());
        config.put("filterRangeId", filter.getFieldId());
        config.put("filterRangeName", filter.getFieldName());

        Map<String, Object> cfg = filter.getConfig();
        String rangeType = cfg.containsKey("type") ? cfg.get("type").toString() : "custom";

        switch (rangeType) {
            case "today":
                config.put("rangeType", 0);
                config.put("rangeValue", 0);
                config.put("today", true);
                break;
            case "thisWeek":
                config.put("rangeType", 1);
                config.put("rangeValue", 0);
                break;
            case "thisMonth":
                config.put("rangeType", 2);
                config.put("rangeValue", 0);
                break;
            default:
                config.put("rangeType", 0);
                config.put("rangeValue", 0);
        }
    }

    private void buildSelectFilter(ObjectNode config, FilterDefinition filter) {
        config.put("controlId", filter.getFieldId());
        config.put("controlName", filter.getFieldName());
        config.put("controlType", 9); // SingleSelect

        ArrayNode options = config.putArray("selectedOptions");
        Map<String, Object> cfg = filter.getConfig();
        @SuppressWarnings("unchecked")
        List<String> opts = cfg.containsKey("options") ? (List<String>) cfg.get("options") : List.of();
        for (String opt : opts) {
            options.add(opt);
        }
    }

    private void buildPeopleFilter(ObjectNode config, FilterDefinition filter) {
        config.put("controlId", filter.getFieldId());
        config.put("controlName", filter.getFieldName());
        config.put("controlType", 26); // Collaborator

        Map<String, Object> cfg = filter.getConfig();
        String operator = cfg.containsKey("label") ? cfg.get("label").toString() : "currentUser";

        if ("我的".equals(operator) || "currentUser".equals(operator)) {
            config.put("userFilterType", "currentUser");
        } else {
            config.put("userFilterType", "select");
            config.putArray("selectedUsers");
        }
    }

    private void buildNumberFilter(ObjectNode config, FilterDefinition filter) {
        config.put("controlId", filter.getFieldId());
        config.put("controlName", filter.getFieldName());
        config.put("controlType", 6); // Number

        Map<String, Object> cfg = filter.getConfig();
        if (cfg.containsKey("min")) {
            config.put("minValue", cfg.get("min").toString());
        }
        if (cfg.containsKey("max")) {
            config.put("maxValue", cfg.get("max").toString());
        }
    }

    private void buildTextFilter(ObjectNode config, FilterDefinition filter) {
        config.put("controlId", filter.getFieldId());
        config.put("controlName", filter.getFieldName());
        config.put("controlType", 2); // Text

        Map<String, Object> cfg = filter.getConfig();
        if (cfg.containsKey("placeholder")) {
            config.put("placeholder", cfg.get("placeholder").toString());
        }
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 输入类 ==========
    public static class Input {
        private final List<ViewFilterPlan> plans;
        private final boolean dryRun;
        private final boolean failFast;

        public Input(List<ViewFilterPlan> plans, boolean dryRun, boolean failFast) {
            this.plans = plans;
            this.dryRun = dryRun;
            this.failFast = failFast;
        }

        public List<ViewFilterPlan> getPlans() { return plans; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
    }

    public static class ViewFilterPlan {
        private final String viewId;
        private final String viewName;
        private final String worksheetId;
        private final List<FilterDefinition> filters;

        public ViewFilterPlan(String viewId, String viewName, String worksheetId,
                             List<FilterDefinition> filters) {
            this.viewId = viewId;
            this.viewName = viewName;
            this.worksheetId = worksheetId;
            this.filters = filters;
        }

        public String getViewId() { return viewId; }
        public String getViewName() { return viewName; }
        public String getWorksheetId() { return worksheetId; }
        public List<FilterDefinition> getFilters() { return filters; }
    }

    public static class FilterDefinition {
        private final String filterType;
        private final String filterName;
        private final String fieldId;
        private final String fieldName;
        private final String operator;
        private final Map<String, Object> config;

        public FilterDefinition(String filterType, String filterName,
                               String fieldId, String fieldName,
                               String operator, Map<String, Object> config) {
            this.filterType = filterType;
            this.filterName = filterName;
            this.fieldId = fieldId;
            this.fieldName = fieldName;
            this.operator = operator;
            this.config = config != null ? config : new HashMap<>();
        }

        public String getFilterType() { return filterType; }
        public String getFilterName() { return filterName; }
        public String getFieldId() { return fieldId; }
        public String getFieldName() { return fieldName; }
        public String getOperator() { return operator; }
        public Map<String, Object> getConfig() { return config; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final boolean success;
        private final Map<String, List<FilterCreationDetail>> viewFilters;
        private final List<FilterCreationDetail> allFilters;
        private final String errorMessage;

        public Output(boolean success, Map<String, List<FilterCreationDetail>> viewFilters,
                       List<FilterCreationDetail> allFilters, String errorMessage) {
            this.success = success;
            this.viewFilters = viewFilters;
            this.allFilters = allFilters;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Map<String, List<FilterCreationDetail>> getViewFilters() { return viewFilters; }
        public List<FilterCreationDetail> getAllFilters() { return allFilters; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class FilterCreationDetail {
        private final String filterName;
        private final String filterId;
        private final boolean success;
        private final String errorMessage;
        private final String filterType;
        private final String viewId;

        public FilterCreationDetail(String filterName, String filterId,
                                     boolean success, String errorMessage,
                                     String filterType, String viewId) {
            this.filterName = filterName;
            this.filterId = filterId;
            this.success = success;
            this.errorMessage = errorMessage;
            this.filterType = filterType;
            this.viewId = viewId;
        }

        public String getFilterName() { return filterName; }
        public String getFilterId() { return filterId; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public String getFilterType() { return filterType; }
        public String getViewId() { return viewId; }
    }
}
