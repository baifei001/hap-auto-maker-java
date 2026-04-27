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
 * Mock关联数据应用执行器
 *
 * Python 对应: mock_data_inline.py - apply_relation_phase()
 *
 * 职责:
 * - 应用 MockRelationPlanner 规划的关联数据
 * - 使用 V3 API PATCH 更新记录的关联字段
 * - 按 tier 分批执行（明细端 tier=3 先执行，1:1从属端 tier=2 后执行）
 *
 * API 调用:
 * - PATCH /v3/app/worksheets/{worksheet_id}/rows/{row_id} - 更新记录字段
 */
public class MockRelationApplier implements Executor<MockRelationApplier.Input, MockRelationApplier.Output> {

    private static final Logger logger = LoggerFactory.getLogger(MockRelationApplier.class);

    private final HapApiClient apiClient;
    private final ExecutorService executor;

    public MockRelationApplier(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "MockRelationApplier";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        Map<String, WorksheetResult> results = new ConcurrentHashMap<>();

        try {
            // 构建 tierMap
            Map<String, Integer> tierMap = input.getTierMap();

            // 按 tier 顺序执行：先 tier=3，再 tier=2
            for (int targetTier : List.of(3, 2)) {
                List<RelationPlan> batch = input.getPlans().stream()
                    .filter(p -> tierMap.getOrDefault(p.getWorksheetId(), 2) == targetTier)
                    .toList();

                if (batch.isEmpty()) {
                    continue;
                }

                List<Callable<Void>> tasks = new ArrayList<>();
                for (RelationPlan plan : batch) {
                    tasks.add(() -> {
                        WorksheetResult result = applyRelationsForWorksheet(plan, input);
                        results.put(plan.getWorksheetId() + ":" + plan.getFieldId(), result);
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
                                throw new ExecutorException(getName(), "Relation application failed", e.getCause());
                            }
                        }
                    }
                }
            }

            boolean allSuccess = results.values().stream().allMatch(WorksheetResult::isSuccess);
            int totalPlanned = results.values().stream().mapToInt(WorksheetResult::getPlanned).sum();
            int totalUpdated = results.values().stream().mapToInt(WorksheetResult::getUpdated).sum();
            int totalFailed = results.values().stream().mapToInt(WorksheetResult::getFailed).sum();

            logger.info("✓ 关联数据应用完成: planned={}, updated={}, failed={}", totalPlanned, totalUpdated, totalFailed);

            return new Output(allSuccess, results, totalPlanned, totalUpdated, totalFailed, null);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to apply relation data", e);
        }
    }

    private WorksheetResult applyRelationsForWorksheet(RelationPlan plan, Input input) {
        int planned = 0;
        int updated = 0;
        int failed = 0;

        List<RowAssignment> assignments = plan.getAssignments();
        if (assignments == null || assignments.isEmpty()) {
            return new WorksheetResult(
                plan.getWorksheetId(), plan.getWorksheetName(),
                plan.getFieldId(), plan.getFieldName(),
                true, 0, 0, 0
            );
        }

        planned = assignments.size();

        if (input.isDryRun()) {
            logger.info("  [dry-run] [{}] 关联字段 {} → {} 条分配",
                plan.getWorksheetName(), plan.getFieldId(), planned);
            return new WorksheetResult(
                plan.getWorksheetId(), plan.getWorksheetName(),
                plan.getFieldId(), plan.getFieldName(),
                true, planned, 0, 0
            );
        }

        for (RowAssignment assignment : assignments) {
            try {
                // 构建字段值（Relation 字段值为 [targetRowId] 数组）
                ObjectMapper mapper = Jacksons.mapper();
                ObjectNode fieldObj = mapper.createObjectNode();
                fieldObj.put("id", plan.getFieldId());

                ArrayNode valueArray = mapper.createArrayNode();
                valueArray.add(assignment.getTargetRowId());
                fieldObj.set("value", valueArray);

                ArrayNode fieldsArray = mapper.createArrayNode();
                fieldsArray.add(fieldObj);

                // 调用 API 更新记录
                apiClient.updateRowV3(
                    plan.getWorksheetId(),
                    assignment.getSourceRowId(),
                    fieldsArray,
                    input.isTriggerWorkflow()
                );

                updated++;

            } catch (Exception e) {
                logger.error("  ✗ [{}] PATCH 失败 row={}: {}",
                    plan.getWorksheetName(), assignment.getSourceRowId(), e.getMessage());
                failed++;

                if (input.isFailFast()) {
                    throw new RuntimeException(e);
                }
            }
        }

        logger.info("  ✓ [{}] 关联字段 {} 处理完成: updated={}, failed={}",
            plan.getWorksheetName(), plan.getFieldName(), updated, failed);

        boolean success = updated == planned;
        return new WorksheetResult(
            plan.getWorksheetId(), plan.getWorksheetName(),
            plan.getFieldId(), plan.getFieldName(),
            success, planned, updated, failed
        );
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 输入类 ==========
    public static class Input {
        private final List<RelationPlan> plans;
        private final Map<String, Integer> tierMap;
        private final boolean dryRun;
        private final boolean failFast;
        private final boolean triggerWorkflow;

        public Input(List<RelationPlan> plans, Map<String, Integer> tierMap,
                     boolean dryRun, boolean failFast, boolean triggerWorkflow) {
            this.plans = plans != null ? plans : List.of();
            this.tierMap = tierMap != null ? tierMap : Map.of();
            this.dryRun = dryRun;
            this.failFast = failFast;
            this.triggerWorkflow = triggerWorkflow;
        }

        public List<RelationPlan> getPlans() { return plans; }
        public Map<String, Integer> getTierMap() { return tierMap; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
        public boolean isTriggerWorkflow() { return triggerWorkflow; }
    }

    public static class RelationPlan {
        private final String worksheetId;
        private final String worksheetName;
        private final String fieldId;
        private final String fieldName;
        private final String targetWorksheetId;
        private final List<RowAssignment> assignments;

        public RelationPlan(String worksheetId, String worksheetName,
                           String fieldId, String fieldName,
                           String targetWorksheetId,
                           List<RowAssignment> assignments) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.fieldId = fieldId;
            this.fieldName = fieldName;
            this.targetWorksheetId = targetWorksheetId;
            this.assignments = assignments != null ? assignments : List.of();
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public String getFieldId() { return fieldId; }
        public String getFieldName() { return fieldName; }
        public String getTargetWorksheetId() { return targetWorksheetId; }
        public List<RowAssignment> getAssignments() { return assignments; }
    }

    public static class RowAssignment {
        private final String sourceRowId;
        private final String targetRowId;

        public RowAssignment(String sourceRowId, String targetRowId) {
            this.sourceRowId = sourceRowId;
            this.targetRowId = targetRowId;
        }

        public String getSourceRowId() { return sourceRowId; }
        public String getTargetRowId() { return targetRowId; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final boolean success;
        private final Map<String, WorksheetResult> results;
        private final int totalPlanned;
        private final int totalUpdated;
        private final int totalFailed;
        private final String errorMessage;

        public Output(boolean success, Map<String, WorksheetResult> results,
                      int totalPlanned, int totalUpdated, int totalFailed,
                      String errorMessage) {
            this.success = success;
            this.results = results != null ? results : Map.of();
            this.totalPlanned = totalPlanned;
            this.totalUpdated = totalUpdated;
            this.totalFailed = totalFailed;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Map<String, WorksheetResult> getResults() { return results; }
        public int getTotalPlanned() { return totalPlanned; }
        public int getTotalUpdated() { return totalUpdated; }
        public int getTotalFailed() { return totalFailed; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class WorksheetResult {
        private final String worksheetId;
        private final String worksheetName;
        private final String fieldId;
        private final String fieldName;
        private final boolean success;
        private final int planned;
        private final int updated;
        private final int failed;

        public WorksheetResult(String worksheetId, String worksheetName,
                              String fieldId, String fieldName,
                              boolean success, int planned, int updated, int failed) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.fieldId = fieldId;
            this.fieldName = fieldName;
            this.success = success;
            this.planned = planned;
            this.updated = updated;
            this.failed = failed;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public String getFieldId() { return fieldId; }
        public String getFieldName() { return fieldName; }
        public boolean isSuccess() { return success; }
        public int getPlanned() { return planned; }
        public int getUpdated() { return updated; }
        public int getFailed() { return failed; }
    }
}
