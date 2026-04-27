package com.hap.automaker.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.executor.ChatbotCreator;
import com.hap.automaker.core.planner.ChatbotPlanner;

/**
 * 聊天机器人流水线服务
 *
 * 整合 ChatbotPlanner + ChatbotCreator
 * 实现 Wave 7: 聊天机器人规划与创建
 */
public final class ChatbotPipelineService implements ChatbotPipelineRunner {

    private final ChatbotPlanner chatbotPlanner;
    private final ChatbotCreator chatbotCreator;

    public ChatbotPipelineService() {
        this(
            new ChatbotPlanner(new HttpAiTextClient()),
            new ChatbotCreator(new HapApiClient(), 4)
        );
    }

    ChatbotPipelineService(
            ChatbotPlanner chatbotPlanner,
            ChatbotCreator chatbotCreator) {
        this.chatbotPlanner = chatbotPlanner;
        this.chatbotCreator = chatbotCreator;
    }

    @Override
    public ChatbotPipelineResult run(
            Path repoRoot,
            String appId,
            Path appAuth,
            Path worksheetCreateResult,
            Path chatbotPlanOutput,
            Path chatbotResultOutput,
            boolean dryRun) throws Exception {

        OffsetDateTime startedAt = OffsetDateTime.now();

        // 读取工作表创建结果以获取工作表信息
        JsonNode worksheetResult = Jacksons.mapper().readTree(worksheetCreateResult.toFile());
        String appName = worksheetResult.path("appName").asText();

        // 构建 WorksheetSummary 列表
        List<ChatbotPlanner.WorksheetSummary> worksheets = buildWorksheetSummaries(worksheetResult);

        // Step 1: 规划聊天机器人
        ChatbotPlanner.Input planInput = new ChatbotPlanner.Input(appId, appName, worksheets);
        ChatbotPlanner.Output planOutput = chatbotPlanner.plan(planInput);

        // 保存聊天机器人规划结果
        ObjectNode planJson = buildChatbotPlanJson(appId, planOutput);
        Files.createDirectories(chatbotPlanOutput.getParent());
        Jacksons.mapper().writeValue(chatbotPlanOutput.toFile(), planJson);

        // Step 2: 创建聊天机器人（如果不是 dry-run）
        ChatbotCreator.Output createOutput;
        if (dryRun) {
            createOutput = createDryRunResult(planOutput, appId);
        } else {
            // 转换计划到创建器格式
            List<ChatbotCreator.ChatbotPlan> plans = convertToCreatorPlans(planOutput.getProposals());
            ChatbotCreator.Input creatorInput = new ChatbotCreator.Input(
                appId,
                "", // sectionId - 需要从其他来源获取
                "", // projectId - 需要从其他来源获取
                plans,
                dryRun,
                false, // failFast
                "11", // uploadPermission
                "zh"  // language
            );
            createOutput = chatbotCreator.execute(creatorInput);
        }

        // 构建结果
        ObjectNode resultJson = buildChatbotResultJson(appId, planOutput, createOutput, dryRun);
        Files.createDirectories(chatbotResultOutput.getParent());
        Jacksons.mapper().writeValue(chatbotResultOutput.toFile(), resultJson);

        return new ChatbotPipelineResult(
            chatbotPlanOutput,
            chatbotResultOutput,
            planOutput.getProposals().size(),
            createOutput.getSuccessCount(),
            dryRun,
            startedAt,
            OffsetDateTime.now()
        );
    }

    private List<ChatbotPlanner.WorksheetSummary> buildWorksheetSummaries(JsonNode worksheetResult) {
        List<ChatbotPlanner.WorksheetSummary> worksheets = new ArrayList<>();
        ArrayNode worksheetsArray = (ArrayNode) worksheetResult.path("created_worksheets");

        Iterator<JsonNode> elements = worksheetsArray.elements();
        while (elements.hasNext()) {
            JsonNode ws = elements.next();
            String wsName = ws.path("name").asText();

            List<ChatbotPlanner.FieldSummary> fields = new ArrayList<>();

            // 如果有 controls 字段，提取字段信息
            if (ws.has("controls")) {
                JsonNode controls = ws.path("controls");
                if (controls.isArray()) {
                    Iterator<JsonNode> controlIter = controls.elements();
                    while (controlIter.hasNext()) {
                        JsonNode control = controlIter.next();
                        String name = control.path("name").asText(control.path("controlName").asText(""));
                        String type = control.path("type").asText("Text");

                        List<String> options = new ArrayList<>();
                        if (control.has("options") && control.path("options").isArray()) {
                            control.path("options").forEach(opt -> options.add(opt.asText()));
                        }

                        fields.add(new ChatbotPlanner.FieldSummary(name, type, options));
                    }
                }
            }

            worksheets.add(new ChatbotPlanner.WorksheetSummary(wsName, fields));
        }

        return worksheets;
    }

    private ObjectNode buildChatbotPlanJson(String appId, ChatbotPlanner.Output planOutput) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("schemaVersion", "chatbot_plan_v1");

        ArrayNode proposalsArray = result.putArray("proposals");
        for (ChatbotPlanner.ChatbotProposal proposal : planOutput.getProposals()) {
            ObjectNode proposalNode = proposalsArray.addObject();
            proposalNode.put("name", proposal.getName());
            proposalNode.put("description", proposal.getDescription());
        }

        return result;
    }

    private List<ChatbotCreator.ChatbotPlan> convertToCreatorPlans(
            List<ChatbotPlanner.ChatbotProposal> proposals) {
        List<ChatbotCreator.ChatbotPlan> plans = new ArrayList<>();

        for (ChatbotPlanner.ChatbotProposal proposal : proposals) {
            plans.add(new ChatbotCreator.ChatbotPlan(
                proposal.getName(),
                proposal.getDescription()
            ));
        }

        return plans;
    }

    private ChatbotCreator.Output createDryRunResult(ChatbotPlanner.Output planOutput, String appId) {
        List<ChatbotCreator.ChatbotResult> results = new ArrayList<>();

        for (ChatbotPlanner.ChatbotProposal proposal : planOutput.getProposals()) {
            results.add(new ChatbotCreator.ChatbotResult(
                proposal.getName(),
                "dry-run-chatbot-id",
                true,
                null,
                null
            ));
        }

        return new ChatbotCreator.Output(true, results, results.size(), 0, null);
    }

    private ObjectNode buildChatbotResultJson(String appId, ChatbotPlanner.Output planOutput,
                                               ChatbotCreator.Output createOutput, boolean dryRun) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("createdAt", OffsetDateTime.now().toString());
        result.put("totalPlanned", planOutput.getProposals().size());
        result.put("successCount", createOutput.getSuccessCount());
        result.put("failedCount", createOutput.getFailedCount());
        result.put("dryRun", dryRun);
        result.put("success", createOutput.isSuccess());

        ArrayNode createdArray = result.putArray("created");
        for (ChatbotCreator.ChatbotResult bot : createOutput.getResults()) {
            if (bot.isSuccess()) {
                ObjectNode node = createdArray.addObject();
                node.put("name", bot.getName());
                node.put("chatbotId", bot.getChatbotId());
            }
        }

        return result;
    }

    public record ChatbotPipelineResult(
            Path planOutputPath,
            Path resultOutputPath,
            int totalProposals,
            int createdCount,
            boolean dryRun,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt
    ) {
        public ObjectNode summary() {
            ObjectNode node = Jacksons.mapper().createObjectNode();
            node.put("planOutputPath", planOutputPath.toString());
            node.put("resultOutputPath", resultOutputPath.toString());
            node.put("totalProposals", totalProposals);
            node.put("createdCount", createdCount);
            node.put("dryRun", dryRun);
            node.put("durationMs", endedAt.toInstant().toEpochMilli() - startedAt.toInstant().toEpochMilli());
            return node;
        }
    }
}
