package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.registry.ViewTypeRegistry;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * 视图创建执行器
 *
 * Python 对应: create_views_from_plan.py
 *
 * 职责:
 * - 按规划 JSON 创建工作表视图
 * - 处理 different view types (Table, Kanban, Calendar, etc.)
 * - 调用 Web API /api/Worksheet/SaveWorksheetView
 */
public class ViewCreator implements Executor<ViewCreator.Input, ViewCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(ViewCreator.class);

    private final HapApiClient apiClient;
    private final ViewTypeRegistry viewTypeRegistry;
    private final ExecutorService executor;

    private static final Set<String> SYSTEM_FIELD_IDS = Set.of(
        "ctime", "utime", "ownerid", "caid", "record_count", "wfctime"
    );

    public ViewCreator(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.viewTypeRegistry = new ViewTypeRegistry();
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "ViewCreator";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        Map<String, List<ViewCreationDetail>> worksheetViews = new ConcurrentHashMap<>();
        List<ViewCreationDetail> allViews = new CopyOnWriteArrayList<>();

        try {
            // 按工作表并行创建视图
            List<Callable<Void>> tasks = new ArrayList<>();

            for (WorksheetViewPlan wsPlan : input.getPlans()) {
                tasks.add(() -> {
                    List<ViewCreationDetail> details = createViewsForWorksheet(wsPlan, input);
                    worksheetViews.put(wsPlan.getWorksheetId(), details);
                    allViews.addAll(details);
                    return null;
                });
            }

            if (input.isFailFast()) {
                // 串行执行，失败即停
                for (Callable<Void> task : tasks) {
                    task.call();
                }
            } else {
                // 并行执行
                List<Future<Void>> futures = executor.invokeAll(tasks);
                for (Future<Void> future : futures) {
                    try {
                        future.get();
                    } catch (ExecutionException e) {
                        if (input.isFailFast()) {
                            throw new ExecutorException(getName(), "View creation failed", e.getCause());
                        }
                    }
                }
            }

            boolean allSuccess = allViews.stream().allMatch(ViewCreationDetail::isSuccess);
            return new Output(allSuccess, worksheetViews, allViews, null);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to create views", e);
        }
    }

    private List<ViewCreationDetail> createViewsForWorksheet(WorksheetViewPlan plan, Input input) {
        List<ViewCreationDetail> details = new ArrayList<>();

        for (ViewDefinition view : plan.getViews()) {
            try {
                String viewId = createView(plan.getWorksheetId(), view, input);
                details.add(new ViewCreationDetail(
                    view.getName(), viewId, true, null,
                    view.getViewType(), plan.getWorksheetId()
                ));
            } catch (Exception e) {
                details.add(new ViewCreationDetail(
                    view.getName(), null, false, e.getMessage(),
                    view.getViewType(), plan.getWorksheetId()
                ));
                if (input.isFailFast()) {
                    break;
                }
            }
        }

        return details;
    }

    private String createView(String worksheetId, ViewDefinition view, Input input) throws Exception {
        if (input.isDryRun()) {
            logger.info("[DryRun] Would create view: {} (type={})", view.getName(), view.getViewType());
            return "dry-run-view-id";
        }

        // 构建视图配置 JSON
        ObjectNode viewConfig = buildViewConfig(worksheetId, view);

        // 调用 API 创建视图
        JsonNode response = apiClient.saveWorksheetView(worksheetId, viewConfig);

        // 从响应中提取 viewId
        String viewId = response.path("data").path("viewId").asText();
        if (viewId == null || viewId.isEmpty()) {
            viewId = response.path("viewId").asText();
        }

        if (viewId == null || viewId.isEmpty()) {
            throw new RuntimeException("API response missing viewId");
        }

        logger.info("✓ 视图创建成功: {} (ID: {}, type={})", view.getName(), viewId, view.getViewType());

        // 处理需要二次保存的视图配置
        handlePostCreateUpdates(worksheetId, viewId, view);

        return viewId;
    }

    private ObjectNode buildViewConfig(String worksheetId, ViewDefinition view) {
        ObjectNode config = Jacksons.mapper().createObjectNode();

        // 基础字段
        config.put("worksheetId", worksheetId);
        config.put("viewType", String.valueOf(view.getViewType()));
        config.put("name", view.getName());

        // 显示字段列表
        ArrayNode displayControls = config.putArray("displayControls");
        if (view.getControlIds() != null) {
            for (String controlId : view.getControlIds()) {
                displayControls.add(controlId);
            }
        }

        // 排序配置
        config.put("sortType", 0); // 默认不排序
        config.put("sortCid", "");

        // 封面类型
        config.put("coverType", 0);

        // 过滤条件
        ArrayNode controls = config.putArray("controls");

        // 快速筛选
        ArrayNode filters = config.putArray("filters");

        // 是否显示字段名
        config.put("showControlName", true);

        // 高级设置
        ObjectNode advancedSetting = config.putObject("advancedSetting");

        // 默认高级设置
        advancedSetting.put("enablerules", "1");  // 启用颜色规则
        advancedSetting.put("navempty", "1");   // 显示空分组
        advancedSetting.put("detailbtns", "[]"); // 详情按钮
        advancedSetting.put("listbtns", "[]");   // 列表按钮

        // 根据视图类型添加特定配置
        switch (view.getViewType()) {
            case 0: // 表格视图
                // 表格默认封面样式
                advancedSetting.put("coverstyle", "{\"position\":\"1\",\"style\":3}");
                break;
            case 1: // 看板视图
                advancedSetting.put("coverstyle", "{\"position\":\"1\",\"style\":3}");
                // 看板需要 viewControl 字段（单选字段ID）
                if (view.getAdvancedSettings() != null && view.getAdvancedSettings().containsKey("viewControl")) {
                    config.put("viewControl", view.getAdvancedSettings().get("viewControl").toString());
                }
                break;
            case 3: // 画廊视图
                // 画廊默认左侧封面
                advancedSetting.put("coverstyle", "{\"position\":\"2\"}");
                break;
            case 4: // 日历视图
            case 5: // 甘特图
                // 这些视图需要二次保存设置日期字段
                break;
            case 2: // 层级视图
                // 层级视图需要自关联字段，通过二次保存设置
                break;
            case 7: // 地图视图
                // 地图视图可选设置定位字段
                break;
            case 9: // 资源视图
                // 资源视图需要二次保存设置成员字段和日期字段
                break;
        }

        // 应用用户提供的自定义高级设置
        if (view.getAdvancedSettings() != null) {
            for (Map.Entry<String, Object> entry : view.getAdvancedSettings().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // 跳过非 advancedSetting 的字段（如 viewControl 等顶层字段）
                if (key.equals("viewControl") || key.equals("childType") || key.equals("layersControlId")) {
                    continue;
                }

                if (value instanceof String) {
                    advancedSetting.put(key, (String) value);
                } else if (value instanceof Number) {
                    advancedSetting.put(key, value.toString());
                } else if (value instanceof Boolean) {
                    advancedSetting.put(key, ((Boolean) value) ? "1" : "0");
                }
            }
        }

        return config;
    }

    private void handlePostCreateUpdates(String worksheetId, String viewId, ViewDefinition view) throws Exception {
        // 某些视图类型需要二次保存来设置特定字段
        int viewType = view.getViewType();
        Map<String, Object> advancedSettings = view.getAdvancedSettings();

        if (advancedSettings == null) {
            return;
        }

        ObjectNode updateConfig = Jacksons.mapper().createObjectNode();
        updateConfig.put("worksheetId", worksheetId);
        updateConfig.put("viewId", viewId);
        updateConfig.put("viewType", String.valueOf(viewType));

        ArrayNode editAttrs = updateConfig.putArray("editAttrs");
        ArrayNode editAdKeys = updateConfig.putArray("editAdKeys");
        ObjectNode advancedSetting = updateConfig.putObject("advancedSetting");

        boolean needsUpdate = false;

        switch (viewType) {
            case 2: // 层级视图 - 需要设置 childType 和 layersControlId
                if (advancedSettings.containsKey("layersControlId")) {
                    editAttrs.add("childType");
                    editAttrs.add("layersControlId");
                    updateConfig.put("childType", 0);
                    updateConfig.put("layersControlId", advancedSettings.get("layersControlId").toString());
                    needsUpdate = true;
                }
                break;

            case 4: // 日历视图 - 需要设置 calendarcids
                if (advancedSettings.containsKey("calendarcids")) {
                    editAttrs.add("advancedSetting");
                    editAdKeys.add("calendarcids");
                    advancedSetting.put("calendarcids", advancedSettings.get("calendarcids").toString());
                    needsUpdate = true;
                }
                break;

            case 5: // 甘特图 - 需要设置 begindate 和 enddate
                if (advancedSettings.containsKey("begindate") || advancedSettings.containsKey("enddate")) {
                    editAttrs.add("advancedSetting");
                    editAdKeys.add("begindate");
                    editAdKeys.add("enddate");
                    if (advancedSettings.containsKey("begindate")) {
                        advancedSetting.put("begindate", advancedSettings.get("begindate").toString());
                    }
                    if (advancedSettings.containsKey("enddate")) {
                        advancedSetting.put("enddate", advancedSettings.get("enddate").toString());
                    }
                    needsUpdate = true;
                }
                break;

            case 9: // 资源视图 - 需要设置 resourceId, startdate, enddate
                if (advancedSettings.containsKey("resourceId") ||
                    advancedSettings.containsKey("startdate") ||
                    advancedSettings.containsKey("enddate")) {
                    editAttrs.add("advancedSetting");
                    if (advancedSettings.containsKey("resourceId")) {
                        editAdKeys.add("resourceId");
                        advancedSetting.put("resourceId", advancedSettings.get("resourceId").toString());
                    }
                    if (advancedSettings.containsKey("startdate")) {
                        editAdKeys.add("startdate");
                        advancedSetting.put("startdate", advancedSettings.get("startdate").toString());
                    }
                    if (advancedSettings.containsKey("enddate")) {
                        editAdKeys.add("enddate");
                        advancedSetting.put("enddate", advancedSettings.get("enddate").toString());
                    }
                    needsUpdate = true;
                }
                break;
        }

        if (needsUpdate) {
            JsonNode response = apiClient.saveWorksheetView(worksheetId, updateConfig);
            logger.info("  ✓ 视图二次保存完成: {}", view.getName());
        }
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 输入类 ==========
    public static class Input {
        private final List<WorksheetViewPlan> plans;
        private final String projectId;
        private final boolean dryRun;
        private final boolean failFast;

        public Input(List<WorksheetViewPlan> plans, String projectId,
                     boolean dryRun, boolean failFast) {
            this.plans = plans;
            this.projectId = projectId;
            this.dryRun = dryRun;
            this.failFast = failFast;
        }

        public List<WorksheetViewPlan> getPlans() { return plans; }
        public String getProjectId() { return projectId; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
    }

    public static class WorksheetViewPlan {
        private final String worksheetId;
        private final String worksheetName;
        private final List<ViewDefinition> views;

        public WorksheetViewPlan(String worksheetId, String worksheetName,
                                 List<ViewDefinition> views) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.views = views;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public List<ViewDefinition> getViews() { return views; }
    }

    public static class ViewDefinition {
        private final String name;
        private final int viewType;
        private final List<String> controlIds;
        private final Map<String, Object> filters;
        private final Map<String, Object> sorts;
        private final Map<String, Object> advancedSettings;

        public ViewDefinition(String name, int viewType, List<String> controlIds,
                              Map<String, Object> filters, Map<String, Object> sorts,
                              Map<String, Object> advancedSettings) {
            this.name = name;
            this.viewType = viewType;
            this.controlIds = controlIds;
            this.filters = filters;
            this.sorts = sorts;
            this.advancedSettings = advancedSettings;
        }

        public String getName() { return name; }
        public int getViewType() { return viewType; }
        public List<String> getControlIds() { return controlIds; }
        public Map<String, Object> getFilters() { return filters; }
        public Map<String, Object> getSorts() { return sorts; }
        public Map<String, Object> getAdvancedSettings() { return advancedSettings; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final boolean success;
        private final Map<String, List<ViewCreationDetail>> worksheetViews;
        private final List<ViewCreationDetail> allViews;
        private final String errorMessage;

        public Output(boolean success, Map<String, List<ViewCreationDetail>> worksheetViews,
                      List<ViewCreationDetail> allViews, String errorMessage) {
            this.success = success;
            this.worksheetViews = worksheetViews;
            this.allViews = allViews;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Map<String, List<ViewCreationDetail>> getWorksheetViews() { return worksheetViews; }
        public List<ViewCreationDetail> getAllViews() { return allViews; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class ViewCreationDetail {
        private final String name;
        private final String viewId;
        private final boolean success;
        private final String errorMessage;
        private final int viewType;
        private final String worksheetId;

        public ViewCreationDetail(String name, String viewId, boolean success,
                                  String errorMessage, int viewType, String worksheetId) {
            this.name = name;
            this.viewId = viewId;
            this.success = success;
            this.errorMessage = errorMessage;
            this.viewType = viewType;
            this.worksheetId = worksheetId;
        }

        public String getName() { return name; }
        public String getViewId() { return viewId; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public int getViewType() { return viewType; }
        public String getWorksheetId() { return worksheetId; }
    }
}
