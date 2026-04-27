package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.model.AiAuthConfig;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图标规划器测试
 */
class IconPlannerTest {

    @Test
    void testGetName() {
        AiTextClient mockClient = new AiTextClient() {
            @Override
            public String generateJson(String prompt, AiAuthConfig config) { return "{}"; }
        };
        IconPlanner planner = new IconPlanner(mockClient);
        assertEquals("IconPlanner", planner.getName());
    }

    @Test
    void testInputOutputAccessors() {
        List<IconPlanner.WorksheetInfo> worksheets = List.of(
            new IconPlanner.WorksheetInfo("ws_001", "订单表"),
            new IconPlanner.WorksheetInfo("ws_002", "客户表")
        );

        IconPlanner.Input input = new IconPlanner.Input("app_123", worksheets);

        assertEquals("app_123", input.getAppId());
        assertEquals(2, input.getWorksheets().size());
        assertEquals("ws_001", input.getWorksheets().get(0).getWorkSheetId());
        assertEquals("订单表", input.getWorksheets().get(0).getWorkSheetName());
    }

    @Test
    void testEmptyWorksheets() {
        IconPlanner.Input input = new IconPlanner.Input("app_123", List.of());
        assertEquals("app_123", input.getAppId());
        assertTrue(input.getWorksheets().isEmpty());
    }

    @Test
    void testWorksheetInfoAccessors() {
        IconPlanner.WorksheetInfo ws = new IconPlanner.WorksheetInfo("ws_test", "测试工作表");

        assertEquals("ws_test", ws.getWorkSheetId());
        assertEquals("测试工作表", ws.getWorkSheetName());
    }

    @Test
    void testIconMappingAccessors() {
        IconPlanner.IconMapping mapping = new IconPlanner.IconMapping(
            "ws_001",
            "订单表",
            "sys_2_1_chart",
            "匹配图表用途"
        );

        assertEquals("ws_001", mapping.getWorkSheetId());
        assertEquals("订单表", mapping.getWorkSheetName());
        assertEquals("sys_2_1_chart", mapping.getIcon());
        assertEquals("匹配图表用途", mapping.getReason());
    }

    @Test
    void testOutputAccessors() {
        List<IconPlanner.IconMapping> mappings = List.of(
            new IconPlanner.IconMapping("ws_001", "订单表", "sys_2_1_chart", "图表"),
            new IconPlanner.IconMapping("ws_002", "客户表", "sys_6_1_user_group", "用户")
        );

        IconPlanner.Output output = new IconPlanner.Output("app_123", mappings);

        assertEquals("app_123", output.getAppId());
        assertEquals(2, output.getMappings().size());
        assertEquals("ws_001", output.getMappings().get(0).getWorkSheetId());
        assertEquals("sys_2_1_chart", output.getMappings().get(0).getIcon());
    }

    @Test
    void testEmptyMappings() {
        IconPlanner.Output output = new IconPlanner.Output("app_123", List.of());

        assertEquals("app_123", output.getAppId());
        assertTrue(output.getMappings().isEmpty());
    }

    @Test
    void testNullWorksheetsHandled() {
        // 测试 null worksheets 被处理为空列表
        IconPlanner.Input input = new IconPlanner.Input("app_123", null);
        assertNotNull(input.getWorksheets());
        assertTrue(input.getWorksheets().isEmpty());
    }

    @Test
    void testNullMappingsHandled() {
        // 测试 null mappings 被处理为空列表
        IconPlanner.Output output = new IconPlanner.Output("app_123", null);
        assertNotNull(output.getMappings());
        assertTrue(output.getMappings().isEmpty());
    }
}
