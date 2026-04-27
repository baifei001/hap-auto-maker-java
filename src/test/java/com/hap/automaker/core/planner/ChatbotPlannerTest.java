package com.hap.automaker.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hap.automaker.ai.AiTextClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatbotPlanner 测试类
 */
class ChatbotPlannerTest {

    private ChatbotPlanner planner;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // 使用模拟 AI 客户端的测试子类
        planner = new MockChatbotPlanner();
        mapper = new ObjectMapper();
    }

    @Test
    void testGetName() {
        assertEquals("ChatbotPlanner", planner.getName());
    }

    @Test
    void testPlanWithOneChatbot() throws Exception {
        String aiResponse = """
            {
              "summary": "为应用规划1个对话机器人",
              "proposals": [
                {
                  "name": "销售助手",
                  "description": "帮助销售人员查询客户和订单信息"
                }
              ],
              "notes": ["简单应用只需1个机器人"]
            }
            """;
        ((MockChatbotPlanner) planner).setNextResponse(aiResponse);

        ChatbotPlanner.Input input = new ChatbotPlanner.Input(
            "销售管理",
            "销售分组",
            List.of(new ChatbotPlanner.WorksheetSummary("客户表", List.of(
                new ChatbotPlanner.FieldSummary("客户名称", "Text", null),
                new ChatbotPlanner.FieldSummary("状态", "SingleSelect", List.of("潜在客户", "成交客户"))
            )))
        );

        ChatbotPlanner.Output output = planner.plan(input);

        assertEquals(1, output.getProposals().size());
        assertEquals("销售助手", output.getProposals().get(0).getName());
        assertEquals("帮助销售人员查询客户和订单信息", output.getProposals().get(0).getDescription());
    }

    @Test
    void testPlanWithMultipleChatbots() throws Exception {
        String aiResponse = """
            {
              "summary": "为应用规划3个对话机器人",
              "proposals": [
                {
                  "name": "销售助手",
                  "description": "帮助销售人员查询客户和订单"
                },
                {
                  "name": "库存管理员",
                  "description": "管理产品库存和入库出库"
                },
                {
                  "name": "报表分析师",
                  "description": "生成销售报表和数据分析"
                }
              ]
            }
            """;
        ((MockChatbotPlanner) planner).setNextResponse(aiResponse);

        ChatbotPlanner.Input input = new ChatbotPlanner.Input(
            "ERP系统",
            "默认分组",
            List.of(
                new ChatbotPlanner.WorksheetSummary("客户", List.of()),
                new ChatbotPlanner.WorksheetSummary("订单", List.of()),
                new ChatbotPlanner.WorksheetSummary("产品", List.of()),
                new ChatbotPlanner.WorksheetSummary("库存", List.of())
            )
        );

        ChatbotPlanner.Output output = planner.plan(input);

        assertEquals(3, output.getProposals().size());
        assertEquals("销售助手", output.getProposals().get(0).getName());
        assertEquals("库存管理员", output.getProposals().get(1).getName());
        assertEquals("报表分析师", output.getProposals().get(2).getName());
    }

    @Test
    void testWorksheetSummary() {
        ChatbotPlanner.FieldSummary field1 = new ChatbotPlanner.FieldSummary("名称", "Text", null);
        ChatbotPlanner.FieldSummary field2 = new ChatbotPlanner.FieldSummary("状态", "SingleSelect", List.of("A", "B", "C"));

        assertEquals("名称", field1.getName());
        assertEquals("Text", field1.getType());
        assertTrue(field1.getOptions().isEmpty());

        assertEquals(3, field2.getOptions().size());
        assertEquals("A", field2.getOptions().get(0));
    }

    @Test
    void testChatbotProposal() {
        ChatbotPlanner.ChatbotProposal proposal = new ChatbotPlanner.ChatbotProposal();
        proposal.setName("测试机器人");
        proposal.setDescription("这是一个测试机器人");

        assertEquals("测试机器人", proposal.getName());
        assertEquals("这是一个测试机器人", proposal.getDescription());
    }

    @Test
    void testInputNullSafety() {
        ChatbotPlanner.Input input = new ChatbotPlanner.Input("应用", "分组", null);
        assertTrue(input.getWorksheets().isEmpty());
    }

    @Test
    void testEmptyProposals() throws Exception {
        String aiResponse = """
            {
              "summary": "无效响应",
              "proposals": [],
              "notes": []
            }
            """;
        ((MockChatbotPlanner) planner).setNextResponse(aiResponse);

        ChatbotPlanner.Input input = new ChatbotPlanner.Input("应用", null, List.of());

        assertThrows(PlanningException.class, () -> planner.plan(input));
    }

    @Test
    void testTooManyProposals() throws Exception {
        String aiResponse = """
            {
              "summary": "太多机器人",
              "proposals": [
                {"name": "机器人1", "description": "描述1"},
                {"name": "机器人2", "description": "描述2"},
                {"name": "机器人3", "description": "描述3"},
                {"name": "机器人4", "description": "描述4"}
              ]
            }
            """;
        ((MockChatbotPlanner) planner).setNextResponse(aiResponse);

        ChatbotPlanner.Input input = new ChatbotPlanner.Input("应用", null, List.of());

        assertThrows(PlanningException.class, () -> planner.plan(input));
    }

    // ========== Mock 实现 ==========
    private static class MockChatbotPlanner extends ChatbotPlanner {
        private String nextResponse;

        MockChatbotPlanner() {
            super(null);
        }

        void setNextResponse(String response) {
            this.nextResponse = response;
        }

        @Override
        public ChatbotPlanner.Output plan(ChatbotPlanner.Input input) throws PlanningException {
            try {
                ChatbotPlan plan = new ObjectMapper().readValue(nextResponse, ChatbotPlan.class);

                if (plan.getProposals() == null || plan.getProposals().isEmpty()) {
                    throw new PlanningException(getName(), "Plan contains no chatbot proposals");
                }
                if (plan.getProposals().size() > 3) {
                    throw new PlanningException(getName(), "Too many chatbots: " + plan.getProposals().size());
                }

                return new ChatbotPlanner.Output(plan.getProposals());
            } catch (PlanningException e) {
                throw e;
            } catch (Exception e) {
                throw new PlanningException(getName(), "Failed to plan chatbots", e);
            }
        }
    }

    private static class ChatbotPlan {
        private List<ChatbotPlanner.ChatbotProposal> proposals;

        public List<ChatbotPlanner.ChatbotProposal> getProposals() { return proposals; }
        public void setProposals(List<ChatbotPlanner.ChatbotProposal> proposals) { this.proposals = proposals; }
    }
}
