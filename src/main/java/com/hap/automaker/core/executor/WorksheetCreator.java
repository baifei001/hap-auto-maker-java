package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.registry.FieldTypeRegistry;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * 工作表创建执行器
 *
 * Python 对应: create_worksheets_from_plan.py
 *
 * 职责:
 * 1. 按 creation_order 创建所有非关联字段的工作表
 * 2. 回填 Relation 字段（需要目标 worksheetId）
 *
 * 字段分割策略:
 * - Normal: 可直接在 createWorksheet 中创建的字段类型
 * - Relation: 关联字段，需要在所有工作表创建完成后回填
 * - Deferred: 不支持在创建时包含的字段，需要通过 editWorksheet 补加
 */
public class WorksheetCreator implements Executor<WorksheetCreator.Input, WorksheetCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(WorksheetCreator.class);

    // V3 创建 API 支持的字段类型白名单（从 Python _CREATE_WS_SUPPORTED_TYPES）
    private static final Set<String> CREATE_WS_SUPPORTED_TYPES = Set.of(
        "Text",           // 2  - 单行文本
        "Number",         // 6  - 数值
        "SingleSelect",   // 9  - 单选
        "MultipleSelect", // 10 - 多选
        "Dropdown",       // 11 - 下拉
        "Attachment",     // 14 - 附件
        "Date",           // 15 - 日期
        "DateTime",       // 16 - 日期时间
        "Collaborator",   // 26 - 成员
        "Rating",         // 28 - 等级（星级）
        "Checkbox"        // 36 - 检查框
    );

    private static final Set<String> ALLOWED_CARDINALITY = Set.of("1-1", "1-N");
    private static final Set<String> RELATION_UPDATE_RETRYABLE_ERRORS = Set.of("数据过时");

    private final HapApiClient apiClient;
    private final ExecutorService executor;
    private final FieldTypeRegistry fieldTypeRegistry;

    public WorksheetCreator(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
        this.fieldTypeRegistry = new FieldTypeRegistry();
    }

    @Override
    public String getName() {
        return "WorksheetCreator";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        Map<String, String> worksheetIdMap = new ConcurrentHashMap<>();
        List<WorksheetCreationDetail> details = new CopyOnWriteArrayList<>();

        try {
            // Phase 1: 按 creation_order 排序并创建基础工作表
            List<WorksheetDefinition> sortedWorksheets = new ArrayList<>(input.getPlan().getWorksheets());
            sortedWorksheets.sort(Comparator.comparingInt(WorksheetDefinition::getCreationOrder));

            logger.info("Phase 1: 创建 {} 个工作表（不含关联字段）", sortedWorksheets.size());

            // 存储每个工作表的 deferred 字段，用于后续回补
            Map<String, List<FieldPayload>> deferredFieldsMap = new ConcurrentHashMap<>();
            Map<String, List<RelationFieldDef>> relationFieldsMap = new ConcurrentHashMap<>();

            for (WorksheetDefinition ws : sortedWorksheets) {
                try {
                    // 分割字段
                    FieldSplitResult split = splitFields(ws.getFields());

                    // 创建基础工作表
                    JsonNode response = apiClient.createWorksheetV3(
                        ws.getDisplayName(),
                        buildFieldsArray(split.getNormalFields())
                    );

                    String worksheetId = response.path("data").path("id").asText();
                    if (worksheetId == null || worksheetId.isEmpty()) {
                        throw new ExecutorException(getName(), "API response missing worksheet id");
                    }

                    worksheetIdMap.put(ws.getName(), worksheetId);
                    deferredFieldsMap.put(ws.getName(), split.getDeferredFields());
                    relationFieldsMap.put(ws.getName(), split.getRelationFields());

                    details.add(new WorksheetCreationDetail(
                        ws.getName(), worksheetId, true, null,
                        split.getNormalFields().size()
                    ));

                    logger.info("✓ 工作表创建成功: {} (ID: {})", ws.getName(), worksheetId);

                } catch (Exception e) {
                    String errorMsg = "创建工作表失败 [" + ws.getName() + "]: " + e.getMessage();
                    details.add(new WorksheetCreationDetail(
                        ws.getName(), null, false, errorMsg, 0
                    ));

                    if (input.isFailFast()) {
                        throw new ExecutorException(getName(), errorMsg, e);
                    }
                    logger.error("✗ {}", errorMsg);
                }
            }

            // Phase 2: 处理 deferred 字段
            logger.info("\nPhase 2: 为工作表添加延迟字段");
            for (Map.Entry<String, List<FieldPayload>> entry : deferredFieldsMap.entrySet()) {
                String wsName = entry.getKey();
                String worksheetId = worksheetIdMap.get(wsName);
                List<FieldPayload> deferredFields = entry.getValue();

                if (worksheetId == null || deferredFields.isEmpty()) {
                    continue;
                }

                try {
                    apiClient.editWorksheetV3(
                        worksheetId,
                        buildFieldPayloadArray(deferredFields)
                    );
                    logger.info("✓ 延迟字段添加成功: {} (+{} 个字段)", wsName, deferredFields.size());
                } catch (Exception e) {
                    String errorMsg = "添加延迟字段失败 [" + wsName + "]: " + e.getMessage();
                    logger.error("✗ {}", errorMsg);
                    if (input.isFailFast()) {
                        throw new ExecutorException(getName(), errorMsg, e);
                    }
                }
            }

            // Phase 3: 回填 Relation 字段
            logger.info("\nPhase 3: 回填关联字段");
            boolean allRelationsSuccess = createRelationFields(relationFieldsMap, worksheetIdMap, input.isFailFast());
            if (!allRelationsSuccess) {
                logger.warn("部分关联字段回填失败");
            }

            boolean allSuccess = details.stream().allMatch(WorksheetCreationDetail::isSuccess) && allRelationsSuccess;
            return new Output(allSuccess, worksheetIdMap, details, null);

        } catch (Exception e) {
            if (e instanceof ExecutorException) {
                throw (ExecutorException) e;
            }
            throw new ExecutorException(getName(), "Failed to create worksheets", e);
        }
    }

    /**
     * 分割字段为三类：普通、延迟、关联
     */
    private FieldSplitResult splitFields(List<FieldDefinition> fields) {
        List<FieldPayload> normal = new ArrayList<>();
        List<RelationFieldDef> relation = new ArrayList<>();
        List<FieldPayload> deferred = new ArrayList<>();

        boolean titleSet = false;

        for (FieldDefinition field : fields) {
            String typeKey = getTypeKeyByControlType(field.getControlType());

            if ("Relation".equals(typeKey)) {
                // 关联字段单独处理
                relation.add(new RelationFieldDef(
                    field.getControlId(),
                    field.getControlName(),
                    field.getConfig().get("relation_target") != null ?
                        field.getConfig().get("relation_target").toString() : "",
                    field.getConfig().get("cardinality") != null ?
                        field.getConfig().get("cardinality").toString() : "1-N"
                ));
                continue;
            }

            boolean isDeferred = typeKey == null || !CREATE_WS_SUPPORTED_TYPES.contains(typeKey);

            FieldPayload payload = buildFieldPayload(field, !titleSet);

            if (payload.isTitle()) {
                titleSet = true;
            }

            if (isDeferred) {
                deferred.add(payload);
            } else {
                normal.add(payload);
            }
        }

        // 兜底：确保有标题字段
        if (normal.isEmpty() && deferred.isEmpty()) {
            normal.add(new FieldPayload("name", "名称", "Text", true, true, null));
            titleSet = true;
        }

        if (!titleSet) {
            // 尝试给第一个 Text 字段设置标题
            for (FieldPayload payload : normal) {
                if ("Text".equals(payload.getType())) {
                    payload.setTitle(true);
                    titleSet = true;
                    break;
                }
            }
        }

        if (!titleSet && !normal.isEmpty()) {
            // 第一个字段设为标题
            normal.get(0).setTitle(true);
        }

        return new FieldSplitResult(normal, deferred, relation);
    }

    private FieldPayload buildFieldPayload(FieldDefinition field, boolean isFirstTextTitle) {
        String typeKey = getTypeKeyByControlType(field.getControlType());
        if (typeKey == null) {
            typeKey = "Text"; // 默认类型
        }

        boolean isTitle = isFirstTextTitle && "Text".equals(typeKey);

        return new FieldPayload(
            field.getControlId(),
            field.getControlName(),
            typeKey,
            field.isRequired(),
            isTitle,
            field.getConfig()
        );
    }

    private JsonNode buildFieldsArray(List<FieldPayload> fields) {
        ArrayNode array = Jacksons.mapper().createArrayNode();
        for (FieldPayload field : fields) {
            array.add(field.toJson());
        }
        return array;
    }

    private JsonNode buildFieldPayloadArray(List<FieldPayload> fields) {
        return buildFieldsArray(fields);
    }

    private String getTypeKeyByControlType(int controlType) {
        // 通过注册中心查找类型键
        FieldTypeRegistry registry = new FieldTypeRegistry();
        // 简化的映射，实际需要反向查找
        return switch (controlType) {
            case 2 -> "Text";
            case 6 -> "Number";
            case 9 -> "SingleSelect";
            case 10 -> "MultipleSelect";
            case 11 -> "Dropdown";
            case 14 -> "Attachment";
            case 15 -> "Date";
            case 16 -> "DateTime";
            case 26 -> "Collaborator";
            case 28 -> "Rating";
            case 36 -> "Checkbox";
            case 20, 21, 29 -> "Relation"; // 关联记录类型
            default -> null;
        };
    }

    private boolean createRelationFields(
            Map<String, List<RelationFieldDef>> relationFieldsMap,
            Map<String, String> worksheetIdMap,
            boolean failFast) {
        // 回填关联字段（第二阶段）
        logger.info("Phase 3: 回填 {} 个工作表的关联字段", relationFieldsMap.size());

        boolean allSuccess = true;

        for (Map.Entry<String, List<RelationFieldDef>> entry : relationFieldsMap.entrySet()) {
            String worksheetName = entry.getKey();
            List<RelationFieldDef> relations = entry.getValue();
            String worksheetId = worksheetIdMap.get(worksheetName);

            if (worksheetId == null || worksheetId.isEmpty()) {
                logger.error("找不到工作表 '{}' 的ID，无法创建关联字段", worksheetName);
                allSuccess = false;
                if (failFast) {
                    return false;
                }
                continue;
            }

            // 构建 addFields 数组
            ArrayNode addFields = Jacksons.mapper().createArrayNode();
            for (RelationFieldDef relation : relations) {
                String targetName = relation.target();
                String targetId = worksheetIdMap.get(targetName);

                if (targetId == null || targetId.isEmpty()) {
                    logger.error("找不到目标工作表 '{}' 的ID，无法创建关联字段", targetName);
                    allSuccess = false;
                    if (failFast) {
                        return false;
                    }
                    continue;
                }

                ObjectNode fieldNode = Jacksons.mapper().createObjectNode();
                fieldNode.put("name", relation.name());
                fieldNode.put("type", "Relation");
                fieldNode.put("required", relation.required());
                fieldNode.put("dataSource", targetId);
                fieldNode.put("subType", 1); // 单条关联

                // 双向关联配置
                ObjectNode relationConfig = Jacksons.mapper().createObjectNode();
                relationConfig.putArray("showFields"); // 空数组
                relationConfig.put("bidirectional", true);
                fieldNode.set("relation", relationConfig);

                addFields.add(fieldNode);
                logger.info("准备创建关联字段: {} -> {} (目标: {})",
                    worksheetName, relation.name(), targetName);
            }

            if (addFields.isEmpty()) {
                continue;
            }

            // 调用 API 添加关联字段（带重试）
            boolean success = addRelationFieldsWithRetry(worksheetId, worksheetName, addFields);
            if (!success) {
                allSuccess = false;
                if (failFast) {
                    return false;
                }
            }
        }

        return allSuccess;
    }

    private boolean addRelationFieldsWithRetry(String worksheetId, String worksheetName, ArrayNode addFields) {
        int maxRetries = 5;
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.set("addFields", addFields);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                JsonNode response = apiClient.editWorksheetV3(worksheetId, payload);

                if (response.path("success").asBoolean(false)) {
                    logger.info("✓ 关联字段创建成功: {} ({} 个字段)",
                        worksheetName, addFields.size());
                    return true;
                }

                String errorMsg = response.path("error_msg").asText("Unknown error");
                logger.warn("关联字段创建失败 [{}] (attempt {}/{}): {}",
                    worksheetName, attempt, maxRetries, errorMsg);

                // 可重试错误
                if (RELATION_UPDATE_RETRYABLE_ERRORS.contains(errorMsg) && attempt < maxRetries) {
                    Thread.sleep((long) (600 * attempt)); // 递增延迟
                    continue;
                }

                // 不可重试错误或最后一次尝试
                logger.error("✗ 关联字段创建失败 [{}]: {}", worksheetName, errorMsg);
                return false;

            } catch (Exception e) {
                logger.error("关联字段创建异常 [{}] (attempt {}/{}): {}",
                    worksheetName, attempt, maxRetries, e.getMessage());
                if (attempt == maxRetries) {
                    return false;
                }
                try {
                    Thread.sleep((long) (600 * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false;
    }

    @Override
    public boolean isRollbackable() {
        return true;
    }

    @Override
    public Output rollback(String executionId) {
        // 从 executionId 中解析 worksheet IDs
        // executionId 格式：逗号分隔的工作表ID列表
        if (executionId == null || executionId.isBlank()) {
            logger.warn("Rollback: empty executionId, nothing to rollback");
            return new Output(true, Map.of(), List.of(), null);
        }

        List<String> worksheetIds = new ArrayList<>();
        for (String id : executionId.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                worksheetIds.add(trimmed);
            }
        }

        if (worksheetIds.isEmpty()) {
            logger.warn("Rollback: no worksheet IDs found in executionId");
            return new Output(true, Map.of(), List.of(), null);
        }

        logger.info("Rollback: deleting {} worksheets", worksheetIds.size());
        List<WorksheetCreationDetail> details = new ArrayList<>();
        boolean allSuccess = true;

        for (String worksheetId : worksheetIds) {
            try {
                apiClient.deleteWorksheetV3(worksheetId);
                logger.info("Rollback: deleted worksheet {}", worksheetId);
                details.add(new WorksheetCreationDetail("", worksheetId, true, "", 0));
            } catch (Exception e) {
                logger.error("Rollback: failed to delete worksheet {}: {}", worksheetId, e.getMessage());
                details.add(new WorksheetCreationDetail("", worksheetId, false, e.getMessage(), 0));
                allSuccess = false;
            }
        }

        return new Output(allSuccess, Map.of(), details, allSuccess ? null : "Some worksheets failed to delete");
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 内部类 ==========
    private static class FieldSplitResult {
        private final List<FieldPayload> normalFields;
        private final List<FieldPayload> deferredFields;
        private final List<RelationFieldDef> relationFields;

        FieldSplitResult(List<FieldPayload> normal, List<FieldPayload> deferred, List<RelationFieldDef> relation) {
            this.normalFields = normal;
            this.deferredFields = deferred;
            this.relationFields = relation;
        }

        List<FieldPayload> getNormalFields() { return normalFields; }
        List<FieldPayload> getDeferredFields() { return deferredFields; }
        List<RelationFieldDef> getRelationFields() { return relationFields; }
    }

    private static class FieldPayload {
        private String id;
        private String name;
        private String type;
        private boolean required;
        private boolean title;
        private Map<String, Object> extra;

        FieldPayload(String id, String name, String type, boolean required, boolean title, Map<String, Object> extra) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.required = required;
            this.title = title;
            this.extra = extra;
        }

        JsonNode toJson() {
            ObjectNode node = Jacksons.mapper().createObjectNode();
            node.put("id", id);
            node.put("name", name);
            node.put("type", type);
            node.put("required", required);
            if (title) {
                node.put("isTitle", 1);
            }
            return node;
        }

        String getType() { return type; }
        boolean isTitle() { return title; }
        void setTitle(boolean title) { this.title = title; }
    }

    private static class RelationFieldDef {
        private final String id;
        private final String name;
        private final String target;
        private final String cardinality;
        private final boolean required;

        RelationFieldDef(String id, String name, String target, String cardinality) {
            this.id = id;
            this.name = name;
            this.target = target;
            this.cardinality = cardinality;
            this.required = false; // 关联字段默认非必填
        }

        String id() { return id; }
        String name() { return name; }
        String target() { return target; }
        String cardinality() { return cardinality; }
        boolean required() { return required; }
    }

    // ========== 输入输出类 ==========
    public static class Input {
        private final String appId;
        private final Path appAuthPath;
        private final WorksheetPlan plan;
        private final boolean dryRun;
        private final boolean failFast;

        public Input(String appId, Path appAuthPath, WorksheetPlan plan,
                     boolean dryRun, boolean failFast) {
            this.appId = appId;
            this.appAuthPath = appAuthPath;
            this.plan = plan;
            this.dryRun = dryRun;
            this.failFast = failFast;
        }

        public String getAppId() { return appId; }
        public Path getAppAuthPath() { return appAuthPath; }
        public WorksheetPlan getPlan() { return plan; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
    }

    public static class WorksheetPlan {
        private final List<WorksheetDefinition> worksheets;

        public WorksheetPlan(List<WorksheetDefinition> worksheets) {
            this.worksheets = worksheets;
        }

        public List<WorksheetDefinition> getWorksheets() { return worksheets; }
    }

    public static class WorksheetDefinition {
        private final String name;
        private final String displayName;
        private final int creationOrder;
        private final List<FieldDefinition> fields;

        public WorksheetDefinition(String name, String displayName,
                                   int creationOrder, List<FieldDefinition> fields) {
            this.name = name;
            this.displayName = displayName;
            this.creationOrder = creationOrder;
            this.fields = fields;
        }

        public String getName() { return name; }
        public String getDisplayName() { return displayName; }
        public int getCreationOrder() { return creationOrder; }
        public List<FieldDefinition> getFields() { return fields; }
    }

    public static class FieldDefinition {
        private final String controlId;
        private final String controlName;
        private final int controlType;
        private final boolean required;
        private final Map<String, Object> config;

        public FieldDefinition(String controlId, String controlName,
                               int controlType, boolean required, Map<String, Object> config) {
            this.controlId = controlId;
            this.controlName = controlName;
            this.controlType = controlType;
            this.required = required;
            this.config = config != null ? config : new HashMap<>();
        }

        public String getControlId() { return controlId; }
        public String getControlName() { return controlName; }
        public int getControlType() { return controlType; }
        public boolean isRequired() { return required; }
        public Map<String, Object> getConfig() { return config; }
    }

    public static class Output {
        private final boolean success;
        private final Map<String, String> worksheetIdMap;
        private final List<WorksheetCreationDetail> details;
        private final String errorMessage;

        public Output(boolean success, Map<String, String> worksheetIdMap,
                      List<WorksheetCreationDetail> details, String errorMessage) {
            this.success = success;
            this.worksheetIdMap = worksheetIdMap;
            this.details = details;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Map<String, String> getWorksheetIdMap() { return worksheetIdMap; }
        public List<WorksheetCreationDetail> getDetails() { return details; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class WorksheetCreationDetail {
        private final String name;
        private final String worksheetId;
        private final boolean success;
        private final String errorMessage;
        private final int fieldCount;

        public WorksheetCreationDetail(String name, String worksheetId,
                                       boolean success, String errorMessage, int fieldCount) {
            this.name = name;
            this.worksheetId = worksheetId;
            this.success = success;
            this.errorMessage = errorMessage;
            this.fieldCount = fieldCount;
        }

        public String getName() { return name; }
        public String getWorksheetId() { return worksheetId; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public int getFieldCount() { return fieldCount; }
    }
}
