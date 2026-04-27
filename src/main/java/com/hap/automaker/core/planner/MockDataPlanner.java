package com.hap.automaker.core.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * Mock数据规划器
 *
 * Python 对应: planners/mock_data_inline.py + planners/plan_mock_data_gemini.py
 *
 * 职责:
 * - AI 规划每个工作表的 Mock 数据
 * - 根据工作表结构生成合理的测试数据
 * - 支持分批生成（每批最多5条）
 * - 处理字段类型约束和选项
 *
 * 造数规则:
 * - 明细端（有 subType=1 的出边且参与 1-N 关系）: 共10条，拆成 [5, 5]
 * - 其他（主表端、1:1、无关联）: 共5条，拆成 [5]
 */
public class MockDataPlanner implements Planner<MockDataPlanner.Input, MockDataPlanner.Output> {

    private final AiTextClient aiClient;
    private static final int MAX_RETRIES = 3;
    private static final int BATCH_SIZE = 5; // 每轮AI最多生成条数
    private static final Logger logger = LoggerFactory.getLogger(MockDataPlanner.class);

    // 支持的写字段类型
    private static final Set<String> SUPPORTED_WRITABLE_TYPES = Set.of(
        "Text", "Number", "Currency", "Date", "DateTime",
        "SingleSelect", "MultipleSelect", "Dropdown", "Checkbox",
        "Rating", "Location", "PhoneNumber", "Email", "Textarea",
        "RichText", "Link", "Region"
    );

    // 需要AI生成的字段类型（有options的选择类字段）
    private static final Set<String> AI_ONLY_TYPES = Set.of(
        "SingleSelect", "Dropdown", "MultipleSelect"
    );

    // 系统字段
    private static final Set<String> SYSTEM_FIELD_IDS = Set.of(
        "rowid", "ctime", "utime", "ownerid", "caid", "uaid",
        "_createdAt", "_updatedAt", "_createBy", "_updatedBy", "_owner"
    );

    public MockDataPlanner(AiTextClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public String getName() {
        return "MockDataPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            List<WorksheetMockPlan> plans = new ArrayList<>();

            for (WorksheetInfo ws : input.getWorksheets()) {
                // 计算该工作表的造数条数和分批
                List<Integer> batches = computeRecordBatches(ws, input.getRelationPairs(), input.getRelationEdges());
                int totalCount = batches.stream().mapToInt(Integer::intValue).sum();

                if (totalCount <= 0) {
                    continue;
                }

                // 为每批生成数据
                List<MockRecord> allRecords = new ArrayList<>();
                for (int i = 0; i < batches.size(); i++) {
                    int batchCount = batches.get(i);
                    String roundLabel = batches.size() > 1 ? "第" + (i + 1) + "/" + batches.size() + "轮" : "";

                    List<MockRecord> batchRecords = generateBatchRecords(
                        ws, batchCount, input.getAppName(), input.getBusinessContext(), roundLabel
                    );
                    allRecords.addAll(batchRecords);
                }

                plans.add(new WorksheetMockPlan(
                    ws.getWorksheetId(),
                    ws.getWorksheetName(),
                    totalCount,
                    allRecords
                ));

                logger.info("✓ Mock数据规划完成: {} ({}条记录)", ws.getWorksheetName(), allRecords.size());
            }

            return new Output(plans);

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan mock data", e);
        }
    }

    /**
     * 计算工作表的造数条数和分批
     */
    private List<Integer> computeRecordBatches(WorksheetInfo ws,
                                                List<RelationPair> relationPairs,
                                                List<RelationEdge> relationEdges) {
        // 收集该表的出边 subType
        List<Integer> outgoingSubtypes = relationEdges.stream()
            .filter(e -> e.getSourceWorksheetId().equals(ws.getWorksheetId()))
            .map(RelationEdge::getSubType)
            .toList();

        // 检查是否参与 1-N pair
        boolean in1NPair = relationPairs.stream()
            .filter(p -> "1-N".equals(p.getPairType()))
            .anyMatch(p -> p.getWorksheetAId().equals(ws.getWorksheetId())
                || p.getWorksheetBId().equals(ws.getWorksheetId()));

        // 明细端条件：有出边 subType=1 且都是1，且参与 1-N pair
        boolean isDetailEnd = !outgoingSubtypes.isEmpty()
            && outgoingSubtypes.stream().allMatch(s -> s == 1)
            && in1NPair;

        int total = isDetailEnd ? 10 : 5;

        // 拆分为 batches
        List<Integer> batches = new ArrayList<>();
        int remaining = total;
        while (remaining > 0) {
            batches.add(Math.min(BATCH_SIZE, remaining));
            remaining -= BATCH_SIZE;
        }

        return batches;
    }

    /**
     * 为一批生成记录
     */
    private List<MockRecord> generateBatchRecords(WorksheetInfo ws,
                                                   int batchCount,
                                                   String appName,
                                                   String businessContext,
                                                   String roundLabel) throws Exception {
        // 构建可写字段列表（排除Relation字段）
        List<FieldInfo> writableFields = ws.getFields().stream()
            .filter(f -> !"Relation".equals(f.getType()))
            .filter(f -> !SYSTEM_FIELD_IDS.contains(f.getFieldId()))
            .filter(f -> SUPPORTED_WRITABLE_TYPES.contains(f.getType()))
            .toList();

        if (writableFields.isEmpty()) {
            return List.of();
        }

        // 构建 prompt
        String prompt = buildPrompt(ws, writableFields, batchCount, appName, businessContext, roundLabel);

        // 调用 AI 生成
        String responseJson = callAiWithRetry(prompt);

        // 解析响应
        JsonNode root = Jacksons.mapper().readTree(responseJson);
        JsonNode worksheetsNode = root.path("worksheets");

        List<MockRecord> records = new ArrayList<>();
        if (worksheetsNode.isArray() && worksheetsNode.size() > 0) {
            JsonNode recordsNode = worksheetsNode.get(0).path("records");
            if (recordsNode.isArray()) {
                for (JsonNode recordNode : recordsNode) {
                    String summary = recordNode.path("recordSummary").asText("");
                    JsonNode valuesNode = recordNode.path("valuesByFieldId");

                    Map<String, Object> valuesByFieldId = new HashMap<>();
                    if (valuesNode.isObject()) {
                        valuesNode.fields().forEachRemaining(entry -> {
                            String fieldId = entry.getKey();
                            JsonNode valueNode = entry.getValue();
                            Object value = convertJsonNodeToValue(valueNode);
                            valuesByFieldId.put(fieldId, value);
                        });
                    }

                    records.add(new MockRecord(summary, valuesByFieldId));
                }
            }
        }

        // 验证记录数量
        if (records.size() != batchCount) {
            logger.warn("[warn] {} {} 生成的记录数不匹配: 期望 {}, 实际 {}",
                ws.getWorksheetName(), roundLabel, batchCount, records.size());
        }

        return records;
    }

    private Object convertJsonNodeToValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        } else if (node.isTextual()) {
            return node.asText();
        } else if (node.isNumber()) {
            return node.numberValue();
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(convertJsonNodeToValue(item));
            }
            return list;
        } else if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            node.fields().forEachRemaining(entry -> {
                map.put(entry.getKey(), convertJsonNodeToValue(entry.getValue()));
            });
            return map;
        }
        return node.asText();
    }

    private String buildPrompt(WorksheetInfo ws, List<FieldInfo> writableFields,
                               int recordCount, String appName,
                               String businessContext, String roundLabel) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 应用背景\n");
        sb.append("应用名称：").append(appName).append("\n");
        sb.append("行业/业务背景：").append(businessContext).append("\n\n");

        sb.append("请根据上述背景，为「").append(ws.getWorksheetName()).append("」生成真实有业务含义的数据，");
        sb.append("避免使用\"示例\"、\"测试\"、\"sample\"等无意义词汇。\n\n");

        sb.append("你是企业应用造数规划助手。请基于给定应用结构，输出严格 JSON，不要 markdown，不要解释。\n\n");

        sb.append("目标：\n");
        sb.append("1. 为工作表生成指定数量的记录。\n");
        sb.append("2. Relation 字段在本阶段一律不要输出。\n");
        sb.append("3. SingleSelect / Dropdown 字段值必须是包含一个 key 字符串的数组，例如 [\"key1\"]，不要使用 value 文案。\n");
        sb.append("4. MultipleSelect 字段值必须是包含一个或多个 key 字符串的数组，例如 [\"key1\", \"key2\"]。\n");
        sb.append("5. Currency（金额）字段使用数字，例如 50000。\n");
        sb.append("6. Region（地区）字段使用中文地址文本。\n");
        sb.append("7. Location（定位）字段使用 JSON 对象，包含 address 字段。\n");
        sb.append("8. RichText（富文本）字段使用纯文本字符串。\n");
        sb.append("9. valuesByFieldId 的 key 必须是字段 ID。\n");
        sb.append("10. 每条记录都要有一句中文 recordSummary，描述该记录的业务含义。\n");
        sb.append("11. 每个 writableField 都必须填值，禁止遗漏！\n\n");

        sb.append("字段信息：\n");
        for (FieldInfo field : writableFields) {
            sb.append("  - ").append(field.getFieldId()).append(" (").append(field.getName()).append(")");
            sb.append(" [").append(field.getType()).append("]");
            if (field.getOptions() != null && !field.getOptions().isEmpty()) {
                sb.append(" options: ").append(String.join(", ", field.getOptions()));
            }
            sb.append("\n");
        }
        sb.append("\n");

        sb.append("请严格输出 JSON，格式如下：\n");
        sb.append("{\n");
        sb.append("  \"notes\": [\"inline_mock\"],\n");
        sb.append("  \"worksheets\": [\n");
        sb.append("    {\n");
        sb.append("      \"worksheetId\": \"").append(ws.getWorksheetId()).append("\",\n");
        sb.append("      \"worksheetName\": \"").append(ws.getWorksheetName()).append("\",\n");
        sb.append("      \"recordCount\": ").append(recordCount).append(",\n");
        sb.append("      \"records\": [\n");
        sb.append("        {\n");
        sb.append("          \"recordSummary\": \"记录的业务描述\",\n");
        sb.append("          \"valuesByFieldId\": {\n");
        sb.append("            \"字段ID\": \"值\"\n");
        sb.append("          }\n");
        sb.append("        }\n");
        sb.append("      ]\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("约束：\n");
        sb.append("1. 每张表 records 数量必须严格等于 recordCount（").append(recordCount).append(" 条）。\n");
        sb.append("2. Checkbox 使用 true/false；Number / Currency 使用数字；Date 使用 yyyy-MM-dd；DateTime 使用 yyyy-MM-dd HH:mm:ss。\n");
        sb.append("3. 必须为每个 writableField 都生成合理的值，不允许遗漏任何可写字段。\n");
        sb.append("4. Number 字段：禁止使用 0。字段名含\"ID/编号/序号/No/号/码\"等时，生成 1001-9999 范围的正整数。\n");

        return sb.toString();
    }

    private String callAiWithRetry(String prompt) throws Exception {
        Exception lastError = null;

        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                AiAuthConfig config = ConfigLoader.loadAiConfig();
                return aiClient.generateJson(prompt, config);
            } catch (Exception e) {
                lastError = e;
                logger.error("AI call failed (attempt {}/{}): {}", i + 1, MAX_RETRIES, e.getMessage());
                if (i < MAX_RETRIES - 1) {
                    Thread.sleep(1000 * (i + 1));
                }
            }
        }

        throw new PlanningException(getName(), "AI call failed after " + MAX_RETRIES + " retries", lastError);
    }

    // ========== 输入类 ==========
    public static class Input {
        private final String appName;
        private final String businessContext;
        private final List<WorksheetInfo> worksheets;
        private final List<RelationPair> relationPairs;
        private final List<RelationEdge> relationEdges;

        public Input(String appName, String businessContext,
                     List<WorksheetInfo> worksheets,
                     List<RelationPair> relationPairs,
                     List<RelationEdge> relationEdges) {
            this.appName = appName;
            this.businessContext = businessContext != null ? businessContext : "";
            this.worksheets = worksheets != null ? worksheets : List.of();
            this.relationPairs = relationPairs != null ? relationPairs : List.of();
            this.relationEdges = relationEdges != null ? relationEdges : List.of();
        }

        public String getAppName() { return appName; }
        public String getBusinessContext() { return businessContext; }
        public List<WorksheetInfo> getWorksheets() { return worksheets; }
        public List<RelationPair> getRelationPairs() { return relationPairs; }
        public List<RelationEdge> getRelationEdges() { return relationEdges; }
    }

    public static class WorksheetInfo {
        private final String worksheetId;
        private final String worksheetName;
        private final List<FieldInfo> fields;

        public WorksheetInfo(String worksheetId, String worksheetName, List<FieldInfo> fields) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.fields = fields != null ? fields : List.of();
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public List<FieldInfo> getFields() { return fields; }
    }

    public static class FieldInfo {
        private final String fieldId;
        private final String name;
        private final String type;
        private final List<String> options;

        public FieldInfo(String fieldId, String name, String type, List<String> options) {
            this.fieldId = fieldId;
            this.name = name;
            this.type = type;
            this.options = options != null ? options : List.of();
        }

        public String getFieldId() { return fieldId; }
        public String getName() { return name; }
        public String getType() { return type; }
        public List<String> getOptions() { return options; }
    }

    public static class RelationPair {
        private final String worksheetAId;
        private final String worksheetBId;
        private final String pairType;

        public RelationPair(String worksheetAId, String worksheetBId, String pairType) {
            this.worksheetAId = worksheetAId;
            this.worksheetBId = worksheetBId;
            this.pairType = pairType;
        }

        public String getWorksheetAId() { return worksheetAId; }
        public String getWorksheetBId() { return worksheetBId; }
        public String getPairType() { return pairType; }
    }

    public static class RelationEdge {
        private final String sourceWorksheetId;
        private final String targetWorksheetId;
        private final int subType;

        public RelationEdge(String sourceWorksheetId, String targetWorksheetId, int subType) {
            this.sourceWorksheetId = sourceWorksheetId;
            this.targetWorksheetId = targetWorksheetId;
            this.subType = subType;
        }

        public String getSourceWorksheetId() { return sourceWorksheetId; }
        public String getTargetWorksheetId() { return targetWorksheetId; }
        public int getSubType() { return subType; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final List<WorksheetMockPlan> plans;

        public Output(List<WorksheetMockPlan> plans) {
            this.plans = plans != null ? plans : List.of();
        }

        public List<WorksheetMockPlan> getPlans() { return plans; }
    }

    public static class WorksheetMockPlan {
        private final String worksheetId;
        private final String worksheetName;
        private final int recordCount;
        private final List<MockRecord> records;

        public WorksheetMockPlan(String worksheetId, String worksheetName,
                                 int recordCount, List<MockRecord> records) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.recordCount = recordCount;
            this.records = records != null ? records : List.of();
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public int getRecordCount() { return recordCount; }
        public List<MockRecord> getRecords() { return records; }
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
}
