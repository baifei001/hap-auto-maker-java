package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.model.AiAuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SectionPlanner 测试类
 */
class SectionPlannerTest {

    private SectionPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new SectionPlanner(new MockAiTextClient());
    }

    @Test
    void testGetName() {
        assertEquals("SectionPlanner", planner.getName());
    }

    @Test
    void testValidateEmptyResult() {
        assertFalse(planner.validate(null));
    }

    @Test
    void testValidateValidResult() {
        SectionPlanner.SectionPlan section = new SectionPlanner.SectionPlan(
            "业务分组", List.of("工作表1", "工作表2")
        );
        SectionPlanner.Output output = new SectionPlanner.Output(
            "测试应用", List.of(section)
        );
        assertTrue(planner.validate(output));
    }

    @Test
    void testWorksheetInfo() {
        SectionPlanner.WorksheetInfo ws = new SectionPlanner.WorksheetInfo(
            "客户信息", "存储客户基本资料"
        );
        assertEquals("客户信息", ws.getName());
        assertEquals("存储客户基本资料", ws.getPurpose());
    }

    @Test
    void testSectionPlan() {
        List<String> worksheets = List.of("客户表", "订单表", "产品表");
        SectionPlanner.SectionPlan section = new SectionPlanner.SectionPlan(
            "核心业务", worksheets
        );
        assertEquals("核心业务", section.getName());
        assertEquals(3, section.getWorksheets().size());
        assertEquals("客户表", section.getWorksheets().get(0));
    }

    @Test
    void testSectionPlanWithEmptyWorksheets() {
        SectionPlanner.SectionPlan section = new SectionPlanner.SectionPlan(
            "仪表盘", List.of()
        );
        assertTrue(section.getWorksheets().isEmpty());
    }

    @Test
    void testInputCreation() {
        List<SectionPlanner.WorksheetInfo> worksheets = List.of(
            new SectionPlanner.WorksheetInfo("客户表", "客户信息"),
            new SectionPlanner.WorksheetInfo("订单表", "订单信息"),
            new SectionPlanner.WorksheetInfo("产品表", "产品信息")
        );

        SectionPlanner.Input input = new SectionPlanner.Input(
            "CRM系统", worksheets, "zh"
        );

        assertEquals("CRM系统", input.getAppName());
        assertEquals(3, input.getWorksheets().size());
        assertEquals("zh", input.getLanguage());
    }

    @Test
    void testInputWithDefaultLanguage() {
        List<SectionPlanner.WorksheetInfo> worksheets = List.of(
            new SectionPlanner.WorksheetInfo("表1", null)
        );

        SectionPlanner.Input input = new SectionPlanner.Input(
            "测试应用", worksheets, null
        );

        assertEquals("zh", input.getLanguage()); // 默认为 zh
    }

    @Test
    void testOutputCreation() {
        SectionPlanner.SectionPlan section1 = new SectionPlanner.SectionPlan(
            "仪表盘", List.of()
        );
        SectionPlanner.SectionPlan section2 = new SectionPlanner.SectionPlan(
            "业务数据", List.of("客户表", "订单表")
        );

        SectionPlanner.Output output = new SectionPlanner.Output(
            "CRM系统", List.of(section1, section2)
        );

        assertEquals("CRM系统", output.getAppName());
        assertEquals(2, output.getSections().size());
        assertEquals("仪表盘", output.getSections().get(0).getName());
        assertTrue(output.getSections().get(0).getWorksheets().isEmpty());
    }

    @Test
    void testSimplifiedModeForSmallWorksheetCount() throws Exception {
        // 少于4张工作表时应该进入简化模式
        List<SectionPlanner.WorksheetInfo> worksheets = List.of(
            new SectionPlanner.WorksheetInfo("表1", "描述1"),
            new SectionPlanner.WorksheetInfo("表2", "描述2"),
            new SectionPlanner.WorksheetInfo("表3", "描述3")
        );

        SectionPlanner.Input input = new SectionPlanner.Input(
            "小应用", worksheets, "zh"
        );

        SectionPlanner.Output output = planner.plan(input);

        assertNotNull(output);
        assertEquals("小应用", output.getAppName());
        // 简化模式应该有2个分组：仪表盘 + 全部工作表
        assertEquals(2, output.getSections().size());
        assertEquals("仪表盘", output.getSections().get(0).getName());
        assertEquals("全部工作表", output.getSections().get(1).getName());
    }

    @Test
    void testEnglishMode() {
        List<SectionPlanner.WorksheetInfo> worksheets = List.of(
            new SectionPlanner.WorksheetInfo("Table1", "Description")
        );

        SectionPlanner.Input input = new SectionPlanner.Input(
            "Test App", worksheets, "en"
        );

        assertEquals("en", input.getLanguage());
    }

    @Test
    void testWorksheetInfoWithNullPurpose() {
        SectionPlanner.WorksheetInfo ws = new SectionPlanner.WorksheetInfo(
            "表名", null
        );
        assertEquals("表名", ws.getName());
        assertNull(ws.getPurpose());
    }

    @Test
    void testMultipleSections() {
        SectionPlanner.SectionPlan section1 = new SectionPlanner.SectionPlan(
            "仪表盘", List.of()
        );
        SectionPlanner.SectionPlan section2 = new SectionPlanner.SectionPlan(
            "基础数据", List.of("客户表", "产品表")
        );
        SectionPlanner.SectionPlan section3 = new SectionPlanner.SectionPlan(
            "业务数据", List.of("订单表", "发货表")
        );

        SectionPlanner.Output output = new SectionPlanner.Output(
            "ERP系统", List.of(section1, section2, section3)
        );

        assertEquals(3, output.getSections().size());
        assertEquals(2, output.getSections().get(1).getWorksheets().size());
        assertEquals(2, output.getSections().get(2).getWorksheets().size());
    }

    @Test
    void testPlanParsing() throws Exception {
        // 测试 JSON 解析逻辑
        String json = """
            {
                "sections": [
                    {
                        "name": "仪表盘",
                        "worksheets": []
                    },
                    {
                        "name": "客户管理",
                        "worksheets": ["客户表", "联系人表"]
                    },
                    {
                        "name": "销售管理",
                        "worksheets": ["订单表", "发货表", "回款表"]
                    }
                ]
            }
            """;

        // 使用 Jackson 解析验证
        var mapper = com.hap.automaker.config.Jacksons.mapper();
        var root = mapper.readTree(json);

        assertTrue(root.has("sections"));
        assertEquals(3, root.get("sections").size());
        assertEquals("仪表盘", root.get("sections").get(0).get("name").asText());
        assertEquals(0, root.get("sections").get(0).get("worksheets").size());
        assertEquals("客户管理", root.get("sections").get(1).get("name").asText());
        assertEquals(2, root.get("sections").get(1).get("worksheets").size());
    }

    /**
     * Mock AI Text Client for testing
     */
    private static class MockAiTextClient implements AiTextClient {
        @Override
        public String generateJson(String prompt, AiAuthConfig config) throws Exception {
            return """
                {
                    "sections": [
                        {
                            "name": "仪表盘",
                            "worksheets": []
                        },
                        {
                            "name": "业务分组",
                            "worksheets": ["表1", "表2", "表3", "表4"]
                        }
                    ]
                }
                """;
        }
    }
}
