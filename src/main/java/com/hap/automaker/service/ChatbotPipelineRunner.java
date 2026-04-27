package com.hap.automaker.service;

import java.nio.file.Path;

/**
 * Chatbot Pipeline Runner 接口
 *
 * 定义聊天机器人流水线的执行契约
 */
public interface ChatbotPipelineRunner {

    /**
     * 执行聊天机器人流水线
     *
     * @param repoRoot 仓库根目录
     * @param appId 应用ID
     * @param appAuth 应用授权文件路径
     * @param worksheetCreateResult 工作表创建结果 JSON 路径
     * @param chatbotPlanOutput 聊天机器人规划输出路径
     * @param chatbotResultOutput 聊天机器人执行结果输出路径
     * @param dryRun 是否 dry-run 模式
     * @return 流水线执行结果
     */
    ChatbotPipelineService.ChatbotPipelineResult run(
            Path repoRoot,
            String appId,
            Path appAuth,
            Path worksheetCreateResult,
            Path chatbotPlanOutput,
            Path chatbotResultOutput,
            boolean dryRun) throws Exception;
}
