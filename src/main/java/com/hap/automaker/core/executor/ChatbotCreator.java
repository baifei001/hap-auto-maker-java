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
 * 对话机器人创建执行器
 *
 * Python 对应: executors/create_chatbots_from_plan.py
 *
 * 职责:
 * - 根据 ChatbotPlanner 的规划创建对话机器人
 * - 调用 GenerateChatRobotInfo 生成机器人信息
 * - 调用 AddWorkSheet 创建机器人（机器人在 HAP 中是特殊工作表）
 * - 调用 saveChatbotConfig 保存配置
 */
public class ChatbotCreator implements Executor<ChatbotCreator.Input, ChatbotCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(ChatbotCreator.class);

    private final HapApiClient apiClient;
    private final ExecutorService executor;

    // 图标颜色池（从 Python chatbot_common.py 复制）
    private static final String[] ICON_COLORS = {
        "#6D4C41", "#546E7A", "#1E88E5", "#00897B", "#43A047",
        "#F4511E", "#8E24AA", "#3949AB", "#C62828", "#5E35B1"
    };

    public ChatbotCreator(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "ChatbotCreator";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        List<ChatbotResult> results = new ArrayList<>();

        try {
            List<Callable<ChatbotResult>> tasks = new ArrayList<>();

            for (ChatbotPlan plan : input.getPlans()) {
                tasks.add(() -> createChatbot(plan, input));
            }

            if (input.isFailFast()) {
                for (Callable<ChatbotResult> task : tasks) {
                    results.add(task.call());
                }
            } else {
                List<Future<ChatbotResult>> futures = executor.invokeAll(tasks);
                for (Future<ChatbotResult> future : futures) {
                    try {
                        results.add(future.get());
                    } catch (ExecutionException e) {
                        results.add(new ChatbotResult(null, null, false, null, e.getCause().getMessage()));
                        if (input.isFailFast()) {
                            throw new ExecutorException(getName(), "Chatbot creation failed", e.getCause());
                        }
                    }
                }
            }

            boolean allSuccess = results.stream().allMatch(ChatbotResult::isSuccess);
            long successCount = results.stream().filter(ChatbotResult::isSuccess).count();
            long failedCount = results.size() - successCount;

            logger.info("✓ 对话机器人创建完成: 成功={}, 失败={}", successCount, failedCount);

            return new Output(allSuccess, results, (int) successCount, (int) failedCount, null);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to create chatbots", e);
        }
    }

    private ChatbotResult createChatbot(ChatbotPlan plan, Input input) {
        try {
            if (input.isDryRun()) {
                logger.info("[DryRun] 将创建对话机器人: {}", plan.getName());
                return new ChatbotResult(plan.getName(), "dry-run-chatbot-id", true, null, null);
            }

            // 1. 生成机器人信息（问候语、建议问题等）
            JsonNode generatedInfo = generateChatbotInfo(plan, input);
            String systemPrompt = generatedInfo.path("data").path("systemPrompt").asText(plan.getDescription());
            String greeting = generatedInfo.path("data").path("greeting").asText("");
            JsonNode suggestedQuestions = generatedInfo.path("data").path("suggestedQuestions");

            // 2. 创建机器人（AddWorkSheet）
            IconBundle iconBundle = pickIconBundle(plan.getName() + "::" + plan.getDescription());
            JsonNode createResult = addChatbot(plan, input, iconBundle, systemPrompt);
            String chatbotId = createResult.path("data").path("chatbotId").asText();

            if (chatbotId == null || chatbotId.isEmpty()) {
                return new ChatbotResult(plan.getName(), null, false, null, "AddWorkSheet did not return chatbotId");
            }

            // 3. 保存配置
            String presetQuestion = buildPresetQuestion(suggestedQuestions, input.getLanguage());
            saveChatbotConfig(chatbotId, plan, greeting, presetQuestion, input);

            logger.info("  ✓ 机器人「{}」已创建 (ID: {})", plan.getName(), chatbotId);

            return new ChatbotResult(plan.getName(), chatbotId, true, generatedInfo, null);

        } catch (Exception e) {
            logger.error("  ✗ 机器人「{}」创建失败: {}", plan.getName(), e.getMessage());
            return new ChatbotResult(plan.getName(), null, false, null, e.getMessage());
        }
    }

    private JsonNode generateChatbotInfo(ChatbotPlan plan, Input input) throws Exception {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("appId", input.getAppId());
        payload.put("type", 2);
        payload.put("robotDescription", plan.getDescription());
        payload.put("langType", getLangType(input.getLanguage()));
        payload.put("hasIcon", true);

        return apiClient.post("/api/Mingo/GenerateChatRobotInfo", payload);
    }

    private JsonNode addChatbot(ChatbotPlan plan, Input input, IconBundle iconBundle, String prompt) throws Exception {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("appId", input.getAppId());
        payload.put("appSectionId", input.getSectionId());
        payload.put("name", plan.getName());
        payload.put("remark", plan.getDescription());
        payload.put("iconColor", iconBundle.iconColor);
        payload.put("projectId", input.getProjectId());
        payload.put("icon", iconBundle.icon);
        payload.put("iconUrl", iconBundle.iconUrl);
        payload.put("type", 3);
        payload.put("prompt", prompt);

        return apiClient.post("/api/AppManagement/AddWorkSheet", payload);
    }

    private void saveChatbotConfig(String chatbotId, ChatbotPlan plan, String greeting,
                                   String presetQuestion, Input input) throws Exception {
        ObjectNode payload = Jacksons.mapper().createObjectNode();
        payload.put("chatbotId", chatbotId);
        payload.put("name", plan.getName());
        payload.put("welcomeText", greeting.isEmpty() ? "你好，我是" + plan.getName() : greeting);
        payload.put("presetQuestion", presetQuestion);
        payload.put("uploadPermission", input.getUploadPermission());

        apiClient.post("/workflow/process/saveChatbotConfig", payload);
    }

    private String getLangType(String language) {
        return "en".equals(language) ? "1" : "0";
    }

    private String buildPresetQuestion(JsonNode suggestedQuestions, String language) {
        if (suggestedQuestions == null || !suggestedQuestions.isArray()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode q : suggestedQuestions) {
            String question = q.asText().trim();
            if (!question.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(question);
            }
        }
        return sb.toString();
    }

    private IconBundle pickIconBundle(String seed) {
        int hash = seed.hashCode();
        int colorIndex = Math.abs(hash) % ICON_COLORS.length;
        String[] icons = {"robot", "chatbot", "assistant", "ai", "brain"};
        int iconIndex = Math.abs(hash >> 8) % icons.length;

        return new IconBundle(
            icons[iconIndex],
            ICON_COLORS[colorIndex],
            "https://cdn.mingdao.com/icons/" + icons[iconIndex] + ".png"
        );
    }

    private static class IconBundle {
        final String icon;
        final String iconColor;
        final String iconUrl;

        IconBundle(String icon, String iconColor, String iconUrl) {
            this.icon = icon;
            this.iconColor = iconColor;
            this.iconUrl = iconUrl;
        }
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 数据类 ==========
    public static class ChatbotPlan {
        private final String name;
        private final String description;

        public ChatbotPlan(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
    }

    public static class ChatbotResult {
        private final String name;
        private final String chatbotId;
        private final boolean success;
        private final JsonNode generatedInfo;
        private final String errorMessage;

        public ChatbotResult(String name, String chatbotId, boolean success,
                             JsonNode generatedInfo, String errorMessage) {
            this.name = name;
            this.chatbotId = chatbotId;
            this.success = success;
            this.generatedInfo = generatedInfo;
            this.errorMessage = errorMessage;
        }

        public String getName() { return name; }
        public String getChatbotId() { return chatbotId; }
        public boolean isSuccess() { return success; }
        public JsonNode getGeneratedInfo() { return generatedInfo; }
        public String getErrorMessage() { return errorMessage; }
    }

    // ========== 输入输出类 ==========
    public static class Input {
        private final String appId;
        private final String sectionId;
        private final String projectId;
        private final List<ChatbotPlan> plans;
        private final boolean dryRun;
        private final boolean failFast;
        private final String uploadPermission;
        private final String language;

        public Input(String appId, String sectionId, String projectId,
                     List<ChatbotPlan> plans, boolean dryRun, boolean failFast,
                     String uploadPermission, String language) {
            this.appId = appId;
            this.sectionId = sectionId;
            this.projectId = projectId;
            this.plans = plans != null ? plans : List.of();
            this.dryRun = dryRun;
            this.failFast = failFast;
            this.uploadPermission = uploadPermission != null ? uploadPermission : "11";
            this.language = language != null ? language : "zh";
        }

        public String getAppId() { return appId; }
        public String getSectionId() { return sectionId; }
        public String getProjectId() { return projectId; }
        public List<ChatbotPlan> getPlans() { return plans; }
        public boolean isDryRun() { return dryRun; }
        public boolean isFailFast() { return failFast; }
        public String getUploadPermission() { return uploadPermission; }
        public String getLanguage() { return language; }
    }

    public static class Output {
        private final boolean success;
        private final List<ChatbotResult> results;
        private final int successCount;
        private final int failedCount;
        private final String errorMessage;

        public Output(boolean success, List<ChatbotResult> results, int successCount,
                      int failedCount, String errorMessage) {
            this.success = success;
            this.results = results != null ? results : List.of();
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public List<ChatbotResult> getResults() { return results; }
        public int getSuccessCount() { return successCount; }
        public int getFailedCount() { return failedCount; }
        public String getErrorMessage() { return errorMessage; }
    }
}
