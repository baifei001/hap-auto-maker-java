package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 对话机器人规划器
 *
 * Python 对应: planners/plan_chatbots_gemini.py
 *
 * 职责:
 * - AI 规划对话机器人（1-3个，根据应用复杂度）
 * - 每个机器人有独立的职责和适配的工作表
 * - 生成机器人名称和简介
 */
public class ChatbotPlanner implements Planner<ChatbotPlanner.Input, ChatbotPlanner.Output> {

    private final AiTextClient aiClient;
    private static final int MAX_RETRIES = 3;
    private static final Logger logger = LoggerFactory.getLogger(ChatbotPlanner.class);

    public ChatbotPlanner(AiTextClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public String getName() {
        return "ChatbotPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            String prompt = buildPrompt(input);
            String json = callAiWithRetry(prompt);

            ChatbotPlan plan = Jacksons.mapper().readValue(json, ChatbotPlan.class);
            validatePlan(plan);

            logger.info("✓ 对话机器人规划完成: {} 个机器人", plan.getProposals().size());

            return new Output(plan.getProposals());

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan chatbots", e);
        }
    }

    private String buildPrompt(Input input) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是明道云 HAP 对话机器人策划顾问。请基于以下应用结构，为该应用规划合适数量的对话机器人方案（1-3 个，根据应用复杂度决定，最多不超过 3 个）。\n\n");

        sb.append("应用名称：").append(input.getAppName()).append("\n");
        if (input.getSectionName() != null) {
            sb.append("目标分组：").append(input.getSectionName()).append("\n");
        }

        sb.append("\n工作表与字段概览：\n");
        for (WorksheetSummary ws : input.getWorksheets()) {
            sb.append("- ").append(ws.getName()).append(": ");
            List<String> fields = new ArrayList<>();
            for (FieldSummary f : ws.getFields()) {
                String fieldDesc = f.getName() + "<" + f.getType() + ">";
                if (f.getOptions() != null && !f.getOptions().isEmpty()) {
                    fieldDesc += "[可选值:" + String.join("/", f.getOptions().subList(0, Math.min(5, f.getOptions().size()))) + "]";
                }
                fields.add(fieldDesc);
            }
            sb.append(String.join("；", fields.subList(0, Math.min(10, fields.size()))));
            if (fields.size() > 10) {
                sb.append("；等");
            }
            sb.append("\n");
        }

        sb.append("\n要求：\n");
        sb.append("1. 根据应用复杂度规划合适数量的机器人（1-3 个，最多不超过 3 个），职责各有侧重，不要重复。简单应用规划 1 个，中等复杂度规划 2 个，高度复杂应用最多规划 3 个。\n");
        sb.append("2. 机器人必须适配该应用现有工作表，不要脱离业务。\n");
        sb.append("3. 名称简洁，不要出现'助手'这种占位名。\n");
        sb.append("4. 简介要说明它主要处理什么数据、解决什么问题。\n");
        sb.append("5. 输出严格 JSON，不要 markdown，不要解释。\n");

        sb.append("\nJSON 结构如下：\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"一句话总结本轮方案\",\n");
        sb.append("  \"proposals\": [\n");
        sb.append("    {\n");
        sb.append("      \"name\": \"机器人名称\",\n");
        sb.append("      \"description\": \"机器人简介\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"notes\": [\"可选说明1\", \"可选说明2\"]\n");
        sb.append("}");

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

    private void validatePlan(ChatbotPlan plan) throws PlanningException {
        if (plan.getProposals() == null || plan.getProposals().isEmpty()) {
            throw new PlanningException(getName(), "Plan contains no chatbot proposals");
        }

        if (plan.getProposals().size() > 3) {
            throw new PlanningException(getName(), "Too many chatbots: " + plan.getProposals().size());
        }

        for (ChatbotProposal proposal : plan.getProposals()) {
            if (proposal.getName() == null || proposal.getName().isEmpty()) {
                throw new PlanningException(getName(), "Chatbot missing name");
            }
            if (proposal.getDescription() == null || proposal.getDescription().isEmpty()) {
                throw new PlanningException(getName(), "Chatbot missing description: " + proposal.getName());
            }
        }
    }

    // ========== 数据类 ==========
    public static class FieldSummary {
        private final String name;
        private final String type;
        private final List<String> options;

        public FieldSummary(String name, String type, List<String> options) {
            this.name = name;
            this.type = type;
            this.options = options != null ? options : List.of();
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public List<String> getOptions() { return options; }
    }

    public static class WorksheetSummary {
        private final String name;
        private final List<FieldSummary> fields;

        public WorksheetSummary(String name, List<FieldSummary> fields) {
            this.name = name;
            this.fields = fields != null ? fields : List.of();
        }

        public String getName() { return name; }
        public List<FieldSummary> getFields() { return fields; }
    }

    public static class ChatbotProposal {
        private String name;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class ChatbotPlan {
        private String summary;
        private List<ChatbotProposal> proposals;
        private List<String> notes;

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public List<ChatbotProposal> getProposals() { return proposals; }
        public void setProposals(List<ChatbotProposal> proposals) { this.proposals = proposals; }
        public List<String> getNotes() { return notes; }
        public void setNotes(List<String> notes) { this.notes = notes; }
    }

    // ========== 输入输出类 ==========
    public static class Input {
        private final String appName;
        private final String sectionName;
        private final List<WorksheetSummary> worksheets;

        public Input(String appName, String sectionName, List<WorksheetSummary> worksheets) {
            this.appName = appName;
            this.sectionName = sectionName;
            this.worksheets = worksheets != null ? worksheets : List.of();
        }

        public String getAppName() { return appName; }
        public String getSectionName() { return sectionName; }
        public List<WorksheetSummary> getWorksheets() { return worksheets; }
    }

    public static class Output {
        private final List<ChatbotProposal> proposals;

        public Output(List<ChatbotProposal> proposals) {
            this.proposals = proposals != null ? proposals : List.of();
        }

        public List<ChatbotProposal> getProposals() { return proposals; }
    }
}
