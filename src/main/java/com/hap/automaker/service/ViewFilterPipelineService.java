package com.hap.automaker.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.planner.ViewFilterPlanner;
import com.hap.automaker.core.executor.ViewFilterCreator;

/**
 * 视图筛选流水线服务
 *
 * 整合 ViewFilterPlanner + ViewFilterCreator
 * 实现 Wave 4: 视图筛选规划与创建
 */
public final class ViewFilterPipelineService implements ViewFilterPipelineRunner {

    private final ViewFilterPlanner viewFilterPlanner;
    private final ViewFilterCreator viewFilterCreator;

    public ViewFilterPipelineService() {
        this(
            new ViewFilterPlanner(),
            new ViewFilterCreator(new HapApiClient(), 4)
        );
    }

    ViewFilterPipelineService(
            ViewFilterPlanner viewFilterPlanner,
            ViewFilterCreator viewFilterCreator) {
        this.viewFilterPlanner = viewFilterPlanner;
        this.viewFilterCreator = viewFilterCreator;
    }

    @Override
    public ViewFilterPipelineResult run(
            Path repoRoot,
            String appId,
            Path viewCreateResult,
            Path viewFilterPlanOutput,
            Path viewFilterResultOutput,
            boolean dryRun) throws Exception {

        OffsetDateTime startedAt = OffsetDateTime.now();

        // 读取视图创建结果
        JsonNode viewResult = Jacksons.mapper().readTree(viewCreateResult.toFile());

        // 构建 ViewInfo 列表 (需要views和worksheet fields)
        List<ViewFilterPlanner.ViewInfo> views = buildViewInfos(viewResult);
        List<ViewFilterPlanner.FieldInfo> fields = buildFieldInfos(viewResult);

        // Step 1: 规划视图筛选
        ViewFilterPlanner.Input planInput = new ViewFilterPlanner.Input(views, fields);
        ViewFilterPlanner.Output planOutput = viewFilterPlanner.plan(planInput);

        // 保存视图筛选规划结果
        ObjectNode planJson = buildViewFilterPlanJson(appId, planOutput);
        Files.createDirectories(viewFilterPlanOutput.getParent());
        Jacksons.mapper().writeValue(viewFilterPlanOutput.toFile(), planJson);

        // Step 2: 创建视图筛选
        ViewFilterCreator.Output createOutput;
        if (dryRun) {
            createOutput = createDryRunResult(planOutput);
        } else {
            // 转换计划到创建器格式
            List<ViewFilterCreator.ViewFilterPlan> plans = convertToCreatorPlans(planOutput.getFilterPlans());
            ViewFilterCreator.Input creatorInput = new ViewFilterCreator.Input(
                plans,
                dryRun,
                false  // failFast
            );
            createOutput = viewFilterCreator.execute(creatorInput);
        }

        // 构建结果
        ObjectNode resultJson = buildViewFilterResultJson(appId, planOutput, createOutput, dryRun);
        Files.createDirectories(viewFilterResultOutput.getParent());
        Jacksons.mapper().writeValue(viewFilterResultOutput.toFile(), resultJson);

        return new ViewFilterPipelineResult(
            viewFilterPlanOutput,
            viewFilterResultOutput,
            planOutput.getFilterPlans().size(),
            createOutput.getAllFilters().size(),
            (int) createOutput.getAllFilters().stream().filter(ViewFilterCreator.FilterCreationDetail::isSuccess).count(),
            dryRun,
            startedAt,
            OffsetDateTime.now()
        );
    }

    private List<ViewFilterPlanner.ViewInfo> buildViewInfos(JsonNode viewResult) {
        List<ViewFilterPlanner.ViewInfo> result = new ArrayList<>();
        ArrayNode views = (ArrayNode) viewResult.path("views");

        Iterator<JsonNode> elements = views.elements();
        while (elements.hasNext()) {
            JsonNode view = elements.next();
            result.add(new ViewFilterPlanner.ViewInfo(
                view.path("id").asText(),
                view.path("name").asText(),
                view.path("worksheetId").asText(),
                view.path("viewType").asInt(0)
            ));
        }
        return result;
    }

    private List<ViewFilterPlanner.FieldInfo> buildFieldInfos(JsonNode viewResult) {
        List<ViewFilterPlanner.FieldInfo> result = new ArrayList<>();
        JsonNode fieldsNode = viewResult.path("fields");

        if (fieldsNode.isArray()) {
            Iterator<JsonNode> elements = fieldsNode.elements();
            while (elements.hasNext()) {
                JsonNode field = elements.next();
                List<String> options = new ArrayList<>();
                if (field.has("options") && field.path("options").isArray()) {
                    field.path("options").forEach(opt -> options.add(opt.asText()));
                }
                result.add(new ViewFilterPlanner.FieldInfo(
                    field.path("controlId").asText(),
                    field.path("controlName").asText(),
                    field.path("type").asText("Text"),
                    options
                ));
            }
        }
        return result;
    }

    private ObjectNode buildViewFilterPlanJson(String appId, ViewFilterPlanner.Output planOutput) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("schemaVersion", "view_filter_plan_v1");

        ArrayNode plansArray = result.putArray("filterPlans");
        for (ViewFilterPlanner.ViewFilterPlan plan : planOutput.getFilterPlans()) {
            ObjectNode planNode = plansArray.addObject();
            planNode.put("viewId", plan.getViewId());
            planNode.put("viewName", plan.getViewName());
            planNode.put("worksheetId", plan.getWorksheetId());

            ArrayNode filtersArray = planNode.putArray("filters");
            for (ViewFilterPlanner.FilterRecommendation filter : plan.getFilters()) {
                ObjectNode filterNode = filtersArray.addObject();
                filterNode.put("filterType", filter.getFilterType());
                filterNode.put("filterName", filter.getFilterName());
                filterNode.put("fieldId", filter.getFieldId());
                filterNode.put("fieldName", filter.getFieldName());
                filterNode.put("operator", filter.getOperator());
            }
        }

        return result;
    }

    private List<ViewFilterCreator.ViewFilterPlan> convertToCreatorPlans(
            List<ViewFilterPlanner.ViewFilterPlan> plans) {
        List<ViewFilterCreator.ViewFilterPlan> result = new ArrayList<>();

        for (ViewFilterPlanner.ViewFilterPlan plan : plans) {
            List<ViewFilterCreator.FilterDefinition> filters = new ArrayList<>();
            for (ViewFilterPlanner.FilterRecommendation rec : plan.getFilters()) {
                Map<String, Object> config = new HashMap<>();
                if (rec.getConfig() != null) {
                    config.putAll(rec.getConfig());
                }
                filters.add(new ViewFilterCreator.FilterDefinition(
                    rec.getFilterType(),
                    rec.getFilterName(),
                    rec.getFieldId(),
                    rec.getFieldName(),
                    rec.getOperator(),
                    config
                ));
            }
            result.add(new ViewFilterCreator.ViewFilterPlan(
                plan.getViewId(),
                plan.getViewName(),
                plan.getWorksheetId(),
                filters
            ));
        }

        return result;
    }

    private ViewFilterCreator.Output createDryRunResult(ViewFilterPlanner.Output planOutput) {
        Map<String, List<ViewFilterCreator.FilterCreationDetail>> viewFilters = new HashMap<>();
        List<ViewFilterCreator.FilterCreationDetail> allFilters = new ArrayList<>();

        for (ViewFilterPlanner.ViewFilterPlan plan : planOutput.getFilterPlans()) {
            List<ViewFilterCreator.FilterCreationDetail> details = new ArrayList<>();
            for (ViewFilterPlanner.FilterRecommendation filter : plan.getFilters()) {
                ViewFilterCreator.FilterCreationDetail detail = new ViewFilterCreator.FilterCreationDetail(
                    filter.getFilterName(),
                    "dry-run-filter-id",
                    true,
                    null,
                    filter.getFilterType(),
                    plan.getViewId()
                );
                details.add(detail);
                allFilters.add(detail);
            }
            viewFilters.put(plan.getViewId(), details);
        }

        return new ViewFilterCreator.Output(true, viewFilters, allFilters, null);
    }

    private ObjectNode buildViewFilterResultJson(String appId, ViewFilterPlanner.Output planOutput,
                                                   ViewFilterCreator.Output createOutput, boolean dryRun) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("createdAt", OffsetDateTime.now().toString());
        result.put("totalPlannedViews", planOutput.getFilterPlans().size());
        result.put("totalFilters", createOutput.getAllFilters().size());
        result.put("successCount",
            (int) createOutput.getAllFilters().stream().filter(ViewFilterCreator.FilterCreationDetail::isSuccess).count());
        result.put("dryRun", dryRun);
        result.put("success", createOutput.isSuccess());

        ArrayNode viewArray = result.putArray("views");
        for (Map.Entry<String, List<ViewFilterCreator.FilterCreationDetail>> entry : createOutput.getViewFilters().entrySet()) {
            ObjectNode viewNode = viewArray.addObject();
            viewNode.put("viewId", entry.getKey());

            ArrayNode filtersArray = viewNode.putArray("filters");
            for (ViewFilterCreator.FilterCreationDetail detail : entry.getValue()) {
                ObjectNode filterNode = filtersArray.addObject();
                filterNode.put("filterName", detail.getFilterName());
                filterNode.put("filterId", detail.getFilterId());
                filterNode.put("success", detail.isSuccess());
                if (detail.getErrorMessage() != null) {
                    filterNode.put("error", detail.getErrorMessage());
                }
            }
        }

        return result;
    }

    public record ViewFilterPipelineResult(
            Path planOutputPath,
            Path resultOutputPath,
            int totalViews,
            int totalFilters,
            int successCount,
            boolean dryRun,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt
    ) {
        public ObjectNode summary() {
            ObjectNode node = Jacksons.mapper().createObjectNode();
            node.put("planOutputPath", planOutputPath.toString());
            node.put("resultOutputPath", resultOutputPath.toString());
            node.put("totalViews", totalViews);
            node.put("totalFilters", totalFilters);
            node.put("successCount", successCount);
            node.put("dryRun", dryRun);
            node.put("durationMs", endedAt.toInstant().toEpochMilli() - startedAt.toInstant().toEpochMilli());
            return node;
        }
    }
}
