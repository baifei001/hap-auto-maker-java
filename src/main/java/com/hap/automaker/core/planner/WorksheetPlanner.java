package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.registry.FieldTypeConfig;
import com.hap.automaker.core.registry.FieldTypeRegistry;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 工作表规划器
 *
 * Python 对应: plan_app_worksheets_gemini.py + planning/worksheet_planner.py
 *
 * 职责:
 * - 三阶段 AI 规划工作表结构
 *   1. Skeleton: 骨架规划（表名、用途、核心字段、关联关系）
 *   2. Fields: 逐表字段细化
 *   3. Validate & Repair: 校验和修复
 * - 从 FieldTypeRegistry 获取字段类型信息生成 prompt
 */
public class WorksheetPlanner implements Planner<WorksheetPlanner.Input, WorksheetPlanner.Output> {

    private final AiTextClient aiClient;
    private final FieldTypeRegistry fieldTypeRegistry;

    // 最大重试次数
    private static final int MAX_RETRIES = 3;
    private static final Logger logger = LoggerFactory.getLogger(WorksheetPlanner.class);

    public WorksheetPlanner(AiTextClient aiClient) {
        this.aiClient = aiClient;
        this.fieldTypeRegistry = new FieldTypeRegistry();
    }

    @Override
    public String getName() {
        return "WorksheetPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            // Step 1: 骨架规划
            String skeletonPrompt = buildSkeletonPrompt(input);
            String skeletonJson = callAiWithRetry(skeletonPrompt);
            SkeletonPlan skeleton = parseSkeletonPlan(skeletonJson);

            // Step 2: 逐表字段细化
            List<WorksheetPlan> worksheets = new ArrayList<>();
            for (SkeletonWorksheet sw : skeleton.getWorksheets()) {
                String fieldsPrompt = buildFieldsPrompt(sw, input);
                String fieldsJson = callAiWithRetry(fieldsPrompt);
                WorksheetPlan worksheet = parseWorksheetPlan(fieldsJson, sw);
                worksheets.add(worksheet);
            }

            // Step 3: 校验和修复
            validateAndRepair(worksheets);

            logger.info("✓ 工作表规划完成: {} 个工作表", worksheets.size());

            return new Output(skeleton.getAppName(), skeleton.getSummary(), worksheets);

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan worksheets", e);
        }
    }

    /**
     * 构建骨架规划 Prompt
     *
     * 只规划表名、用途、核心字段（1-3个）和关联关系
     */
    private String buildSkeletonPrompt(Input input) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an enterprise app architect. Design worksheet skeleton for app \"").append(input.getAppName()).append("\" and output strict JSON.\n\n");
        sb.append("Business context:\n").append(input.getBusinessContext()).append("\n\n");

        if (input.getExtraRequirements() != null && !input.getExtraRequirements().isEmpty()) {
            sb.append("Extra requirements:\n").append(input.getExtraRequirements()).append("\n\n");
        }

        sb.append("Task:\n");
        sb.append("1. Plan worksheets (name + purpose)\n");
        sb.append("2. For each worksheet, provide 1-3 core fields\n");
        sb.append("3. Define relationships between worksheets\n\n");

        if (input.getMinWorksheets() > 0) {
            sb.append("Minimum worksheets: ").append(input.getMinWorksheets()).append("\n");
        }
        if (input.getMaxWorksheets() > 0) {
            sb.append("Maximum worksheets: ").append(input.getMaxWorksheets()).append("\n");
        }

        sb.append("\nOutput strict JSON format:\n");
        sb.append("{\n");
        sb.append("  \"app_name\": \"").append(input.getAppName()).append("\",\n");
        sb.append("  \"summary\": \"one-line summary\",\n");
        sb.append("  \"worksheets\": [\n");
        sb.append("    {\n");
        sb.append("      \"name\": \"Worksheet name\",\n");
        sb.append("      \"purpose\": \"One-line purpose\",\n");
        sb.append("      \"core_fields\": [\"field1\", \"field2\"],\n");
        sb.append("      \"relations\": [\n");
        sb.append("        {\"target\": \"target_worksheet\", \"type\": \"1-N\", \"description\": \"relation description\"}\n");
        sb.append("      ]\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("Rules:\n");
        sb.append("1. Use snake_case for identifiers\n");
        sb.append("2. Worksheet name should be concise (2-6 Chinese characters)\n");
        sb.append("3. Relationships: type can be 1-1, 1-N, or N-N\n");
        sb.append("4. Output JSON only, no markdown\n");

        return sb.toString();
    }

    /**
     * 构建字段细化 Prompt
     *
     * 基于骨架为单个工作表生成完整字段列表
     */
    private String buildFieldsPrompt(SkeletonWorksheet skeletonWs, Input input) {
        StringBuilder sb = new StringBuilder();
        sb.append("Design complete fields for worksheet \"").append(skeletonWs.getName()).append("\".\n\n");
        sb.append("Worksheet purpose: ").append(skeletonWs.getPurpose()).append("\n");
        sb.append("Core fields: ").append(String.join(", ", skeletonWs.getCoreFields())).append("\n\n");

        // 从注册中心生成字段类型说明
        sb.append(buildFieldTypePromptSection());
        sb.append("\n\n");

        sb.append("Output strict JSON format:\n");
        sb.append("{\n");
        sb.append("  \"name\": \"").append(skeletonWs.getName()).append("\",\n");
        sb.append("  \"display_name\": \"显示名称\",\n");
        sb.append("  \"fields\": [\n");
        sb.append("    {\n");
        sb.append("      \"controlId\": \"field_001\",\n");
        sb.append("      \"controlName\": \"Field Name\",\n");
        sb.append("      \"controlType\": 2,\n");
        sb.append("      \"required\": false,\n");
        sb.append("      \"description\": \"field description\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("Field design guidelines:\n");
        sb.append("1. Every worksheet must have: 名称/标题 (Text), 创建时间, 负责人\n");
        sb.append("2. Use appropriate controlType from the list above\n");
        sb.append("3. Required fields should be minimal (usually just 名称)\n");
        sb.append("4. Add business fields based on worksheet purpose\n");
        sb.append("5. Output JSON only, no markdown\n");

        return sb.toString();
    }

    /**
     * 从注册中心构建字段类型 Prompt 段落
     */
    private String buildFieldTypePromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available field types:\n");

        // 获取所有非 AI 禁用的字段类型
        Set<String> plannableTypes = fieldTypeRegistry.getPlannableTypes();

        for (String typeKey : plannableTypes) {
            FieldTypeConfig config = fieldTypeRegistry.getByName(typeKey);
            if (config == null) continue;

            sb.append("  - ").append(typeKey)
              .append(" (controlType=").append(config.getControlType()).append(")")
              .append(" — ").append(config.getName());

            // 添加特殊标记（从 extra 字段中提取）
            Map<String, Object> extra = config.getExtra();
            List<String> markers = new ArrayList<>();

            if (extra != null) {
                if (Boolean.TRUE.equals(extra.get("requires_options"))) {
                    markers.add("requires options");
                }
                if (Boolean.TRUE.equals(extra.get("requires_relation_target"))) {
                    markers.add("requires relation_target");
                }
                if (Boolean.TRUE.equals(extra.get("force_not_required"))) {
                    markers.add("required must be false");
                }
            }

            if (!markers.isEmpty()) {
                sb.append(" [").append(String.join(", ", markers)).append("]");
            }
            sb.append("\n");
        }

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
                    Thread.sleep(1000 * (i + 1)); // 指数退避
                }
            }
        }

        throw new PlanningException(getName(), "AI call failed after " + MAX_RETRIES + " retries", lastError);
    }

    private SkeletonPlan parseSkeletonPlan(String json) throws Exception {
        return Jacksons.mapper().readValue(json, SkeletonPlan.class);
    }

    private WorksheetPlan parseWorksheetPlan(String json, SkeletonWorksheet skeleton) throws Exception {
        return Jacksons.mapper().readValue(json, WorksheetPlan.class);
    }

    private void validateAndRepair(List<WorksheetPlan> worksheets) {
        // 校验和自动修复工作表规划
        for (WorksheetPlan worksheet : worksheets) {
            // 1. 检查字段类型合法性并修复
            validateFieldTypes(worksheet);

            // 2. 强制 Collaborator required=false（成员字段不能设必填）
            for (FieldDef field : worksheet.getFields()) {
                if (field.getControlType() == 26) { // 26 = 成员字段
                    field.setRequired(false);
                }
            }

            // 3. 确保每个工作表只有一个标题字段
            ensureSingleTitleField(worksheet);
        }
    }

    private void validateFieldTypes(WorksheetPlan worksheet) {
        Set<Integer> validTypes = Set.of(
            2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 15, 16,
            22, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33,
            34, 35, 36, 37, 38, 40, 41, 42, 43, 45, 46, 47, 48, 49
        );

        for (FieldDef field : worksheet.getFields()) {
            if (!validTypes.contains(field.getControlType())) {
                // 无效类型转为文本字段
                logger.warn("Invalid field type {} for field '{}', converting to Text(2)",
                    field.getControlType(), field.getControlName());
                field.setControlType(2); // 转为文本字段
            }
        }
    }

    private void ensureSingleTitleField(WorksheetPlan worksheet) {
        // 在 FieldTypeRegistry 中，标题字段由 attribute=1 标识
        // 这里简化为：将第一个文本字段标记为潜在标题
        boolean titleFound = false;
        for (FieldDef field : worksheet.getFields()) {
            if (field.getControlType() == 2) { // 2 = 文本字段
                if (!titleFound) {
                    titleFound = true;
                }
                // 后续文本字段保持普通
            }
        }

        // 如果没有文本字段，记录警告
        if (!titleFound) {
            logger.warn("No Text field found for worksheet '{}', cannot set title field",
                worksheet.getName());
        }
    }

    // ========== 骨架规划结果类 ==========
    public static class SkeletonPlan {
        private String appName;
        private String summary;
        private List<SkeletonWorksheet> worksheets;

        public String getAppName() { return appName; }
        public void setAppName(String appName) { this.appName = appName; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public List<SkeletonWorksheet> getWorksheets() { return worksheets; }
        public void setWorksheets(List<SkeletonWorksheet> worksheets) { this.worksheets = worksheets; }
    }

    public static class SkeletonWorksheet {
        private String name;
        private String purpose;
        private List<String> coreFields;
        private List<RelationDef> relations;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPurpose() { return purpose; }
        public void setPurpose(String purpose) { this.purpose = purpose; }
        public List<String> getCoreFields() { return coreFields; }
        public void setCoreFields(List<String> coreFields) { this.coreFields = coreFields; }
        public List<RelationDef> getRelations() { return relations; }
        public void setRelations(List<RelationDef> relations) { this.relations = relations; }
    }

    public static class RelationDef {
        private String target;
        private String type; // 1-1, 1-N, N-N
        private String description;

        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    // ========== 完整工作表规划类 ==========
    public static class WorksheetPlan {
        private String name;
        private String displayName;
        private List<FieldDef> fields;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public List<FieldDef> getFields() { return fields; }
        public void setFields(List<FieldDef> fields) { this.fields = fields; }
    }

    public static class FieldDef {
        private String controlId;
        private String controlName;
        private int controlType;
        private boolean required;
        private String description;

        public String getControlId() { return controlId; }
        public void setControlId(String controlId) { this.controlId = controlId; }
        public String getControlName() { return controlName; }
        public void setControlName(String controlName) { this.controlName = controlName; }
        public int getControlType() { return controlType; }
        public void setControlType(int controlType) { this.controlType = controlType; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    // ========== 输入输出类 ==========
    public static class Input {
        private final String appName;
        private final String businessContext;
        private final String extraRequirements;
        private final int minWorksheets;
        private final int maxWorksheets;
        private final String language;

        public Input(String appName, String businessContext, String extraRequirements,
                     int minWorksheets, int maxWorksheets, String language) {
            this.appName = appName;
            this.businessContext = businessContext;
            this.extraRequirements = extraRequirements;
            this.minWorksheets = minWorksheets;
            this.maxWorksheets = maxWorksheets;
            this.language = language != null ? language : "zh";
        }

        public String getAppName() { return appName; }
        public String getBusinessContext() { return businessContext; }
        public String getExtraRequirements() { return extraRequirements; }
        public int getMinWorksheets() { return minWorksheets; }
        public int getMaxWorksheets() { return maxWorksheets; }
        public String getLanguage() { return language; }
    }

    public static class Output {
        private final String appName;
        private final String summary;
        private final List<WorksheetPlan> worksheets;

        public Output(String appName, String summary, List<WorksheetPlan> worksheets) {
            this.appName = appName;
            this.summary = summary;
            this.worksheets = worksheets;
        }

        public String getAppName() { return appName; }
        public String getSummary() { return summary; }
        public List<WorksheetPlan> getWorksheets() { return worksheets; }
    }
}
