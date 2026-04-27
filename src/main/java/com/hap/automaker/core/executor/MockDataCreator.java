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
 * Mock数据创建执行器
 */
public class MockDataCreator implements Executor<MockDataCreator.Input, MockDataCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(MockDataCreator.class);

    private final HapApiClient apiClient;
    private final ExecutorService executor;

    public MockDataCreator(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "MockDataCreator";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        Map<String, WorksheetResult> results = new ConcurrentHashMap<>();

        try {
            List<Callable<Void>> tasks = new ArrayList<>();

            for (WorksheetMockPlan plan : input.getPlans()) {
                tasks.add(() -> {
                    WorksheetResult result = createRecordsForWorksheet(plan, input);
                    results.put(plan.getWorksheetId(), result);
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
                            throw new ExecutorException(getName(), "Mock data creation failed", e.getCause());
                        }
                    }
                }
            }

            boolean allSuccess = results.values().stream().allMatch(WorksheetResult::isSuccess);
            int totalCreated = results.values().stream().mapToInt(WorksheetResult::getCreatedCount).sum();

            logger.info("✓ Mock数据创建完成: {} 条记录", totalCreated);

            return new Output(allSuccess, results, totalCreated, null);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to create mock data", e);
        }
    }

    private WorksheetResult createRecordsForWorksheet(WorksheetMockPlan plan, Input input) {
        List<String> rowIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (plan.getRecords() == null || plan.getRecords().isEmpty()) {
            return new WorksheetResult(plan.getWorksheetId(), plan.getWorksheetName(),
                true, 0, rowIds, "No records to create");
        }

        try {
            // 分批处理（每批最多5条）
            List<MockRecord> records = plan.getRecords();
            int batchSize = 5;
            int totalBatches = (records.size() + batchSize - 1) / batchSize;

            for (int batchIdx = 0; batchIdx < totalBatches; batchIdx++) {
                int start = batchIdx * batchSize;
                int end = Math.min(start + batchSize, records.size());
                List<MockRecord> batch = records.subList(start, end);

                String roundLabel = totalBatches > 1 ?
                    "第" + (batchIdx + 1) + "/" + totalBatches + "轮" : "";

                if (input.isDryRun()) {
                    logger.info("[DryRun] [{}] {} 规划完成，batch={}，跳过写入",
                        plan.getWorksheetName(), roundLabel, batch.size());
                    continue;
                }

                // 构建批量请求
                ArrayNode rowsArray = buildBatchRows(batch, plan.getFieldMetaMap());

                // 调用 API 批量创建
                JsonNode response = apiClient.createRowsBatchV3(
                    plan.getWorksheetId(),
                    rowsArray,
                    input.isTriggerWorkflow()
                );

                // 提取 rowIds
                List<String> batchRowIds = extractRowIds(response);
                rowIds.addAll(batchRowIds);

                if (totalBatches > 1) {
                    logger.info("  ✓ [{}] {} 写入 {} 条",
                        plan.getWorksheetName(), roundLabel, batchRowIds.size());
                }
            }

            if (!input.isDryRun()) {
                logger.info("  ✓ [{}] 共写入 {} 条（计划 {} 条）",
                    plan.getWorksheetName(), rowIds.size(), records.size());
            }

            boolean success = input.isDryRun() || rowIds.size() == records.size();
            return new WorksheetResult(plan.getWorksheetId(), plan.getWorksheetName(),
                success, rowIds.size(), rowIds,
                success ? null : "Expected " + records.size() + " but created " + rowIds.size());

        } catch (Exception e) {
            String errorMsg = "[" + plan.getWorksheetName() + "] 造数失败: " + e.getMessage();
            logger.error("  ✗ {}", errorMsg);
            return new WorksheetResult(plan.getWorksheetId(), plan.getWorksheetName(),
                false, rowIds.size(), rowIds, errorMsg);
        }
    }

    private ArrayNode buildBatchRows(List<MockRecord> records, Map<String, FieldMeta> fieldMetaMap) {
        ObjectMapper mapper = Jacksons.mapper();
        ArrayNode rows = mapper.createArrayNode();

        for (MockRecord record : records) {
            ObjectNode rowObj = rows.addObject();
            ArrayNode fieldsArray = rowObj.putArray("fields");

            for (Map.Entry<String, Object> entry : record.getValuesByFieldId().entrySet()) {
                String fieldId = entry.getKey();
                Object rawValue = entry.getValue();

                FieldMeta meta = fieldMetaMap.get(fieldId);
                if (meta == null) {
                    logger.warn("[警告] 字段元数据缺失，已跳过该字段: fieldId={}", fieldId);
                    continue;
                }

                ObjectNode fieldObj = fieldsArray.addObject();
                fieldObj.put("id", fieldId);

                // 根据字段类型转换值
                JsonNode valueNode = convertValueToJson(rawValue, meta);
                fieldObj.set("value", valueNode);
            }
        }

        return rows;
    }

    private JsonNode convertValueToJson(Object value, FieldMeta meta) {
        ObjectMapper mapper = Jacksons.mapper();

        if (value == null) {
            return mapper.nullNode();
        }

        String fieldType = meta.getType();

        // Location 字段特殊处理
        if ("Location".equals(fieldType)) {
            if (value instanceof Map) {
                return mapper.valueToTree(value);
            } else if (value instanceof String) {
                try {
                    // 尝试解析为 JSON
                    return mapper.readTree((String) value);
                } catch (Exception e) {
                    ObjectNode locObj = mapper.createObjectNode();
                    locObj.put("address", (String) value);
                    return locObj;
                }
            }
        }

        // List 转 JSON 数组
        if (value instanceof List) {
            return mapper.valueToTree(value);
        }

        // Map 转 JSON 对象
        if (value instanceof Map) {
            return mapper.valueToTree(value);
        }

        // 基础类型
        if (value instanceof String) {
            return mapper.valueToTree(value);
        } else if (value instanceof Number) {
            return mapper.valueToTree(value);
        } else if (value instanceof Boolean) {
            return mapper.valueToTree(value);
        }

        return mapper.valueToTree(value.toString());
    }

    private List<String> extractRowIds(JsonNode response) {
        List<String> rowIds = new ArrayList<>();

        if (response == null) {
            return rowIds;
        }

        JsonNode data = response.path("data");

        // 尝试从 rowIds 或 ids 数组提取
        JsonNode idsNode = data.path("rowIds");
        if (!idsNode.isArray()) {
            idsNode = data.path("ids");
        }

        if (idsNode.isArray()) {
            for (JsonNode idNode : idsNode) {
                String rowId = idNode.asText().trim();
                if (!rowId.isEmpty()) {
                    rowIds.add(rowId);
                }
            }
        }

        return rowIds;
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 输入类 ==========
    public static class Input {
        private final List<WorksheetMockPlan> plans;
        private final boolean dryRun;
        private final boolean failFast;
        private final boolean triggerWorkflow;

        public Input(List<WorksheetMockPlan> plans, boolean dryRun,
                     boolean failFast, boolean triggerWorkflow) {
            this.plans = plans != null ? plans : List.of();
            this.dryRun = dryRun;
            this.failFast = failFast;
            this.triggerWorkflow = triggerWorkflow;
        }

        public List<WorksheetMockPlan> getPlans() { return plans; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
        public boolean isTriggerWorkflow() { return triggerWorkflow; }
    }

    public static class WorksheetMockPlan {
        private final String worksheetId;
        private final String worksheetName;
        private final List<MockRecord> records;
        private final Map<String, FieldMeta> fieldMetaMap;

        public WorksheetMockPlan(String worksheetId, String worksheetName,
                                 List<MockRecord> records,
                                 Map<String, FieldMeta> fieldMetaMap) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.records = records != null ? records : List.of();
            this.fieldMetaMap = fieldMetaMap != null ? fieldMetaMap : Map.of();
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public List<MockRecord> getRecords() { return records; }
        public Map<String, FieldMeta> getFieldMetaMap() { return fieldMetaMap; }
    }

    public static class MockRecord {
        private final String recordSummary;
        private final Map<String, Object> valuesByFieldId;

        public MockRecord(String recordSummary, Map<String, Object> valuesByFieldId) {
            this.recordSummary = recordSummary;
            this.valuesByFieldId = valuesByFieldId != null ? valuesByFieldId : Map.of();
        }

        public String getRecordSummary() { return recordSummary; }
        public Map<String, Object> getValuesByFieldId() { return valuesByFieldId; }
    }

    public static class FieldMeta {
        private final String fieldId;
        private final String name;
        private final String type;
        private final int controlType;

        public FieldMeta(String fieldId, String name, String type, int controlType) {
            this.fieldId = fieldId;
            this.name = name;
            this.type = type;
            this.controlType = controlType;
        }

        public String getFieldId() { return fieldId; }
        public String getName() { return name; }
        public String getType() { return type; }
        public int getControlType() { return controlType; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final boolean success;
        private final Map<String, WorksheetResult> results;
        private final int totalCreated;
        private final String errorMessage;

        public Output(boolean success, Map<String, WorksheetResult> results,
                      int totalCreated, String errorMessage) {
            this.success = success;
            this.results = results != null ? results : Map.of();
            this.totalCreated = totalCreated;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Map<String, WorksheetResult> getResults() { return results; }
        public int getTotalCreated() { return totalCreated; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class WorksheetResult {
        private final String worksheetId;
        private final String worksheetName;
        private final boolean success;
        private final int createdCount;
        private final List<String> rowIds;
        private final String errorMessage;

        public WorksheetResult(String worksheetId, String worksheetName,
                               boolean success, int createdCount,
                               List<String> rowIds, String errorMessage) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.success = success;
            this.createdCount = createdCount;
            this.rowIds = rowIds != null ? rowIds : List.of();
            this.errorMessage = errorMessage;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public boolean isSuccess() { return success; }
        public int getCreatedCount() { return createdCount; }
        public List<String> getRowIds() { return rowIds; }
        public String getErrorMessage() { return errorMessage; }
    }
}
