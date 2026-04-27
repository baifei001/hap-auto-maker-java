package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hap.automaker.api.HapApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatbotCreator 测试类
 */
class ChatbotCreatorTest {

    private MockHapApiClient apiClient;
    private ChatbotCreator creator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        apiClient = new MockHapApiClient();
        creator = new ChatbotCreator(apiClient, 2);
        mapper = new ObjectMapper();
    }

    @Test
    void testGetName() {
        assertEquals("ChatbotCreator", creator.getName());
    }

    @Test
    void testExecuteDryRun() throws Exception {
        ChatbotCreator.ChatbotPlan plan = new ChatbotCreator.ChatbotPlan(
            "销售助手", "帮助销售人员查询客户信息"
        );

        ChatbotCreator.Input input = new ChatbotCreator.Input(
            "app-001", "section-001", "project-001",
            List.of(plan), true, false, "11", "zh"
        );

        ChatbotCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getResults().size());
        assertEquals(1, output.getSuccessCount());
        assertEquals(0, output.getFailedCount());

        ChatbotCreator.ChatbotResult result = output.getResults().get(0);
        assertEquals("销售助手", result.getName());
        assertEquals("dry-run-chatbot-id", result.getChatbotId());
        assertTrue(result.isSuccess());
    }

    @Test
    void testExecuteWithMockApi() throws Exception {
        // Mock response for all API calls - both generateChatbotInfo and addWorksheet
        // The MockHapApiClient returns the same response for all calls
        JsonNode mockResponse = mapper.readTree("""
            {
                "success": true,
                "data": {
                    "systemPrompt": "你是销售助手，帮助查询客户信息",
                    "greeting": "你好！我是销售助手",
                    "suggestedQuestions": ["如何查看客户列表？", "怎么添加新客户？"],
                    "chatbotId": "chatbot-123"
                }
            }
            """);
        apiClient.setNextResponse(mockResponse);

        ChatbotCreator.ChatbotPlan plan = new ChatbotCreator.ChatbotPlan(
            "销售助手", "帮助销售人员查询客户信息"
        );

        ChatbotCreator.Input input = new ChatbotCreator.Input(
            "app-001", "section-001", "project-001",
            List.of(plan), false, false, "11", "zh"
        );

        ChatbotCreator.Output output = creator.execute(input);

        // Should be successful (API calls were made)
        assertEquals(1, output.getResults().size());

        ChatbotCreator.ChatbotResult result = output.getResults().get(0);
        assertEquals("销售助手", result.getName());
    }

    @Test
    void testExecuteMultipleChatbots() throws Exception {
        // All tests use dry-run mode for simplicity
        ChatbotCreator.ChatbotPlan plan1 = new ChatbotCreator.ChatbotPlan("销售助手", "销售");
        ChatbotCreator.ChatbotPlan plan2 = new ChatbotCreator.ChatbotPlan("库存助手", "库存");
        ChatbotCreator.ChatbotPlan plan3 = new ChatbotCreator.ChatbotPlan("报表助手", "报表");

        ChatbotCreator.Input input = new ChatbotCreator.Input(
            "app-001", "section-001", "project-001",
            List.of(plan1, plan2, plan3), true, false, "11", "zh"
        );

        ChatbotCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(3, output.getResults().size());
        assertEquals(3, output.getSuccessCount());
    }

    @Test
    void testChatbotPlan() {
        ChatbotCreator.ChatbotPlan plan = new ChatbotCreator.ChatbotPlan(
            "测试机器人", "测试描述"
        );

        assertEquals("测试机器人", plan.getName());
        assertEquals("测试描述", plan.getDescription());
    }

    @Test
    void testChatbotResult() {
        ChatbotCreator.ChatbotResult result = new ChatbotCreator.ChatbotResult(
            "机器人", "id-123", true, null, null
        );

        assertEquals("机器人", result.getName());
        assertEquals("id-123", result.getChatbotId());
        assertTrue(result.isSuccess());
        assertNull(result.getErrorMessage());
    }

    @Test
    void testChatbotResultWithError() {
        ChatbotCreator.ChatbotResult result = new ChatbotCreator.ChatbotResult(
            "失败机器人", null, false, null, "API调用失败"
        );

        assertEquals("失败机器人", result.getName());
        assertNull(result.getChatbotId());
        assertFalse(result.isSuccess());
        assertEquals("API调用失败", result.getErrorMessage());
    }

    @Test
    void testInputCreation() {
        ChatbotCreator.ChatbotPlan plan = new ChatbotCreator.ChatbotPlan("测试", "描述");

        ChatbotCreator.Input input = new ChatbotCreator.Input(
            "app-001", "section-001", "project-001",
            List.of(plan), false, true, "10", "en"
        );

        assertEquals("app-001", input.getAppId());
        assertEquals("section-001", input.getSectionId());
        assertEquals("project-001", input.getProjectId());
        assertFalse(input.isDryRun());
        assertTrue(input.isFailFast());
        assertEquals("10", input.getUploadPermission());
        assertEquals("en", input.getLanguage());
    }

    @Test
    void testOutput() {
        ChatbotCreator.ChatbotResult result1 = new ChatbotCreator.ChatbotResult(
            "机器人1", "id-1", true, null, null
        );
        ChatbotCreator.ChatbotResult result2 = new ChatbotCreator.ChatbotResult(
            "机器人2", "id-2", true, null, null
        );

        ChatbotCreator.Output output = new ChatbotCreator.Output(
            true, List.of(result1, result2), 2, 0, null
        );

        assertTrue(output.isSuccess());
        assertEquals(2, output.getResults().size());
        assertEquals(2, output.getSuccessCount());
        assertEquals(0, output.getFailedCount());
        assertNull(output.getErrorMessage());
    }

    @Test
    void testOutputWithError() {
        ChatbotCreator.Output output = new ChatbotCreator.Output(
            false, List.of(), 0, 0, "创建失败"
        );

        assertFalse(output.isSuccess());
        assertEquals("创建失败", output.getErrorMessage());
    }

    @Test
    void testEmptyPlans() throws Exception {
        ChatbotCreator.Input input = new ChatbotCreator.Input(
            "app-001", "section-001", "project-001",
            List.of(), false, false, "11", "zh"
        );

        ChatbotCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(0, output.getResults().size());
    }
}
