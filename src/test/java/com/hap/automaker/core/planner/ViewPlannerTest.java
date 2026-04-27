package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ViewPlanner 测试类
 */
class ViewPlannerTest {

    private ViewPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new ViewPlanner(new MockAiTextClient());
    }

    @Test
    void testGetName() {
        assertEquals("ViewPlanner", planner.getName());
    }

    @Test
    void testValidateEmptyResult() {
        assertFalse(planner.validate(null));
    }

    @Test
    void testValidateValidResult() {
        ViewPlanner.Output output = new ViewPlanner.Output(List.of());
        assertTrue(planner.validate(output));
    }

    @Test
    void testFieldInfo() {
        ViewPlanner.FieldInfo field = new ViewPlanner.FieldInfo(
            "field-001", "客户名称", "Text"
        );
        assertEquals("field-001", field.getControlId());
        assertEquals("客户名称", field.getControlName());
        assertEquals("Text", field.getType());
    }

    @Test
    void testWorksheetInfo() {
        List<ViewPlanner.FieldInfo> fields = List.of(
            new ViewPlanner.FieldInfo("f1", "名称", "Text"),
            new ViewPlanner.FieldInfo("f2", "状态", "SingleSelect"),
            new ViewPlanner.FieldInfo("f3", "创建时间", "Date")
        );

        ViewPlanner.WorksheetInfo ws = new ViewPlanner.WorksheetInfo(
            "ws-123", "客户表", fields
        );

        assertEquals("ws-123", ws.getWorksheetId());
        assertEquals("客户表", ws.getWorksheetName());
        assertEquals(3, ws.getFields().size());
    }

    @Test
    void testInputCreation() {
        List<ViewPlanner.FieldInfo> fields = List.of(
            new ViewPlanner.FieldInfo("f1", "名称", "Text")
        );
        ViewPlanner.WorksheetInfo ws = new ViewPlanner.WorksheetInfo(
            "ws-123", "测试表", fields
        );

        ViewPlanner.Input input = new ViewPlanner.Input(List.of(ws), "zh");

        assertEquals(1, input.getWorksheets().size());
        assertEquals("zh", input.getLanguage());
    }

    @Test
    void testInputWithDefaultLanguage() {
        ViewPlanner.WorksheetInfo ws = new ViewPlanner.WorksheetInfo(
            "ws-123", "表", List.of()
        );
        ViewPlanner.Input input = new ViewPlanner.Input(List.of(ws), null);
        assertEquals("zh", input.getLanguage());
    }

    @Test
    void testOutputCreation() {
        ViewPlanner.ViewConfig config = new ViewPlanner.ViewConfig();
        config.setDisplayControls(List.of("field1", "field2"));

        ViewPlanner.ViewPlan viewPlan = new ViewPlanner.ViewPlan(
            0, "全部", "表格视图默认", config
        );

        List<ViewPlanner.FieldInfo> fields = List.of(
            new ViewPlanner.FieldInfo("f1", "名称", "Text")
        );
        ViewPlanner.WorksheetViewPlan wsPlan = new ViewPlanner.WorksheetViewPlan(
            "ws-123", "客户表", fields, List.of(viewPlan)
        );

        ViewPlanner.Output output = new ViewPlanner.Output(List.of(wsPlan));

        assertEquals(1, output.getWorksheetPlans().size());
        assertEquals("ws-123", output.getWorksheetPlans().get(0).getWorksheetId());
        assertEquals(1, output.getWorksheetPlans().get(0).getViews().size());
    }

    @Test
    void testViewConfig() {
        ViewPlanner.ViewConfig config = new ViewPlanner.ViewConfig();
        config.setDisplayControls(List.of("f1", "f2", "f3"));
        config.setViewControl("f-status");
        config.setAdvancedSetting(Map.of(
            "enablerules", "1",
            "navempty", "1"
        ));

        assertEquals(3, config.getDisplayControls().size());
        assertEquals("f-status", config.getViewControl());
        assertEquals("1", config.getAdvancedSetting().get("enablerules"));
    }

    @Test
    void testViewPlan() {
        ViewPlanner.ViewConfig config = new ViewPlanner.ViewConfig();

        ViewPlanner.ViewPlan viewPlan = new ViewPlanner.ViewPlan(
            1, "看板", "按状态分组", config
        );

        assertEquals(1, viewPlan.getViewType()); // 看板视图
        assertEquals("看板", viewPlan.getName());
        assertEquals("按状态分组", viewPlan.getReason());
        assertNotNull(viewPlan.getConfig());
    }

    @Test
    void testPlanWithTableViewOnly() throws Exception {
        // 只有文本字段的工作表应该只生成表格视图
        List<ViewPlanner.FieldInfo> fields = List.of(
            new ViewPlanner.FieldInfo("f1", "名称", "Text"),
            new ViewPlanner.FieldInfo("f2", "描述", "Text")
        );
        ViewPlanner.WorksheetInfo ws = new ViewPlanner.WorksheetInfo(
            "ws-123", "简单表", fields
        );

        ViewPlanner.Input input = new ViewPlanner.Input(List.of(ws), "zh");
        ViewPlanner.Output output = planner.plan(input);

        assertEquals(1, output.getWorksheetPlans().size());
        assertEquals(1, output.getWorksheetPlans().get(0).getViews().size());
        assertEquals(0, output.getWorksheetPlans().get(0).getViews().get(0).getViewType()); // 表格视图
    }

    @Test
    void testPlanWithKanbanView() throws Exception {
        // 有单选字段的工作表应该生成表格 + 看板视图
        List<ViewPlanner.FieldInfo> fields = List.of(
            new ViewPlanner.FieldInfo("f1", "名称", "Text"),
            new ViewPlanner.FieldInfo("f2", "状态", "SingleSelect"),
            new ViewPlanner.FieldInfo("f3", "优先级", "Dropdown")
        );
        ViewPlanner.WorksheetInfo ws = new ViewPlanner.WorksheetInfo(
            "ws-123", "任务表", fields
        );

        ViewPlanner.Input input = new ViewPlanner.Input(List.of(ws), "zh");
        ViewPlanner.Output output = planner.plan(input);

        assertEquals(1, output.getWorksheetPlans().size());
        // 表格 + 看板 + 可能的画廊
        assertTrue(output.getWorksheetPlans().get(0).getViews().size() >= 2);

        // 验证有看板视图
        boolean hasKanban = output.getWorksheetPlans().get(0).getViews().stream()
            .anyMatch(v -> v.getViewType() == 1);
        assertTrue(hasKanban, "应该有看板视图");
    }

    @Test
    void testPlanWithCalendarView() throws Exception {
        // 有日期字段的工作表应该生成日历视图
        List<ViewPlanner.FieldInfo> fields = List.of(
            new ViewPlanner.FieldInfo("f1", "名称", "Text"),
            new ViewPlanner.FieldInfo("f2", "截止日期", "Date"),
            new ViewPlanner.FieldInfo("f3", "创建时间", "DateTime")
        );
        ViewPlanner.WorksheetInfo ws = new ViewPlanner.WorksheetInfo(
            "ws-123", "日程表", fields
        );

        ViewPlanner.Input input = new ViewPlanner.Input(List.of(ws), "zh");
        ViewPlanner.Output output = planner.plan(input);

        // 验证有日历视图
        boolean hasCalendar = output.getWorksheetPlans().get(0).getViews().stream()
            .anyMatch(v -> v.getViewType() == 4);
        assertTrue(hasCalendar, "应该有日历视图");

        // 验证有甘特图
        boolean hasGantt = output.getWorksheetPlans().get(0).getViews().stream()
            .anyMatch(v -> v.getViewType() == 5);
        assertTrue(hasGantt, "应该有甘特图视图");
    }

    @Test
    void testPlanWithGalleryView() throws Exception {
        // 有附件字段的工作表应该生成画廊视图
        List<ViewPlanner.FieldInfo> fields = List.of(
            new ViewPlanner.FieldInfo("f1", "名称", "Text"),
            new ViewPlanner.FieldInfo("f2", "图片", "Attachment")
        );
        ViewPlanner.WorksheetInfo ws = new ViewPlanner.WorksheetInfo(
            "ws-123", "图片表", fields
        );

        ViewPlanner.Input input = new ViewPlanner.Input(List.of(ws), "zh");
        ViewPlanner.Output output = planner.plan(input);

        // 验证有画廊视图
        boolean hasGallery = output.getWorksheetPlans().get(0).getViews().stream()
            .anyMatch(v -> v.getViewType() == 3);
        assertTrue(hasGallery, "应该有画廊视图");
    }

    @Test
    void testPlanWithMapView() throws Exception {
        // 有定位字段的工作表应该生成地图视图
        List<ViewPlanner.FieldInfo> fields = List.of(
            new ViewPlanner.FieldInfo("f1", "名称", "Text"),
            new ViewPlanner.FieldInfo("f2", "位置", "Location")
        );
        ViewPlanner.WorksheetInfo ws = new ViewPlanner.WorksheetInfo(
            "ws-123", "位置表", fields
        );

        ViewPlanner.Input input = new ViewPlanner.Input(List.of(ws), "zh");
        ViewPlanner.Output output = planner.plan(input);

        // 验证有地图视图
        boolean hasMap = output.getWorksheetPlans().get(0).getViews().stream()
            .anyMatch(v -> v.getViewType() == 7);
        assertTrue(hasMap, "应该有地图视图");
    }

    @Test
    void testPlanWithResourceView() throws Exception {
        // 有成员+日期字段的工作表应该生成资源视图
        List<ViewPlanner.FieldInfo> fields = List.of(
            new ViewPlanner.FieldInfo("f1", "名称", "Text"),
            new ViewPlanner.FieldInfo("f2", "负责人", "Collaborator"),
            new ViewPlanner.FieldInfo("f3", "开始日期", "Date"),
            new ViewPlanner.FieldInfo("f4", "结束日期", "Date")
        );
        ViewPlanner.WorksheetInfo ws = new ViewPlanner.WorksheetInfo(
            "ws-123", "任务分配表", fields
        );

        ViewPlanner.Input input = new ViewPlanner.Input(List.of(ws), "zh");
        ViewPlanner.Output output = planner.plan(input);

        // 验证有资源视图
        boolean hasResource = output.getWorksheetPlans().get(0).getViews().stream()
            .anyMatch(v -> v.getViewType() == 9);
        assertTrue(hasResource, "应该有资源视图");
    }

    @Test
    void testPlanWithMultipleWorksheets() throws Exception {
        ViewPlanner.WorksheetInfo ws1 = new ViewPlanner.WorksheetInfo(
            "ws-1", "客户表",
            List.of(
                new ViewPlanner.FieldInfo("f1", "名称", "Text"),
                new ViewPlanner.FieldInfo("f2", "状态", "SingleSelect")
            )
        );

        ViewPlanner.WorksheetInfo ws2 = new ViewPlanner.WorksheetInfo(
            "ws-2", "订单表",
            List.of(
                new ViewPlanner.FieldInfo("f1", "名称", "Text"),
                new ViewPlanner.FieldInfo("f2", "订单日期", "Date")
            )
        );

        ViewPlanner.Input input = new ViewPlanner.Input(List.of(ws1, ws2), "zh");
        ViewPlanner.Output output = planner.plan(input);

        assertEquals(2, output.getWorksheetPlans().size());
    }

    /**
     * Mock AI Text Client for testing
     */
    private static class MockAiTextClient implements AiTextClient {
        @Override
        public String generateJson(String prompt, com.hap.automaker.model.AiAuthConfig config) throws Exception {
            return "{\"mock\": \"response\"}";
        }
    }
}
