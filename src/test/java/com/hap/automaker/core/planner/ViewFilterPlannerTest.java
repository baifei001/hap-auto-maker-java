package com.hap.automaker.core.planner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ViewFilterPlanner 测试类
 */
class ViewFilterPlannerTest {

    private ViewFilterPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new ViewFilterPlanner();
    }

    @Test
    void testGetName() {
        assertEquals("ViewFilterPlanner", planner.getName());
    }

    @Test
    void testValidateEmptyResult() {
        assertFalse(planner.validate(null));
    }

    @Test
    void testValidateValidResult() {
        ViewFilterPlanner.Output output = new ViewFilterPlanner.Output(List.of());
        assertTrue(planner.validate(output));
    }

    @Test
    void testFieldInfo() {
        ViewFilterPlanner.FieldInfo field = new ViewFilterPlanner.FieldInfo(
            "field-001", "状态", "SingleSelect", List.of("待处理", "处理中", "已完成")
        );

        assertEquals("field-001", field.getControlId());
        assertEquals("状态", field.getControlName());
        assertEquals("SingleSelect", field.getType());
        assertEquals(3, field.getOptions().size());
    }

    @Test
    void testViewInfo() {
        ViewFilterPlanner.ViewInfo view = new ViewFilterPlanner.ViewInfo(
            "view-123", "全部订单", "ws-456", 0
        );

        assertEquals("view-123", view.getViewId());
        assertEquals("全部订单", view.getViewName());
        assertEquals("ws-456", view.getWorksheetId());
        assertEquals(0, view.getViewType());
    }

    @Test
    void testFilterRecommendation() {
        ViewFilterPlanner.FilterRecommendation filter = new ViewFilterPlanner.FilterRecommendation(
            "time_range", "时间范围", "field-date", "创建时间", "dynamic",
            java.util.Map.of("type", "today", "label", "今天")
        );

        assertEquals("time_range", filter.getFilterType());
        assertEquals("时间范围", filter.getFilterName());
        assertEquals("field-date", filter.getFieldId());
        assertEquals("今天", filter.getConfig().get("label"));
    }

    @Test
    void testInputCreation() {
        List<ViewFilterPlanner.FieldInfo> fields = List.of(
            new ViewFilterPlanner.FieldInfo("f1", "状态", "SingleSelect", List.of("A", "B")),
            new ViewFilterPlanner.FieldInfo("f2", "创建时间", "Date", null)
        );

        List<ViewFilterPlanner.ViewInfo> views = List.of(
            new ViewFilterPlanner.ViewInfo("v1", "视图1", "ws-1", 0)
        );

        ViewFilterPlanner.Input input = new ViewFilterPlanner.Input(views, fields);

        assertEquals(1, input.getViews().size());
        assertEquals(2, input.getWorksheetFields().size());
    }

    @Test
    void testOutputCreation() {
        ViewFilterPlanner.FilterRecommendation filter = new ViewFilterPlanner.FilterRecommendation(
            "select", "选项筛选", "f1", "状态", "multi",
            java.util.Map.of("options", List.of("待办", "已完成"))
        );

        ViewFilterPlanner.ViewFilterPlan plan = new ViewFilterPlanner.ViewFilterPlan(
            "v1", "视图1", "ws-1", List.of(filter)
        );

        ViewFilterPlanner.Output output = new ViewFilterPlanner.Output(List.of(plan));

        assertEquals(1, output.getFilterPlans().size());
        assertEquals("v1", output.getFilterPlans().get(0).getViewId());
        assertEquals(1, output.getFilterPlans().get(0).getFilters().size());
    }

    @Test
    void testPlanWithDateFields() throws Exception {
        List<ViewFilterPlanner.FieldInfo> fields = List.of(
            new ViewFilterPlanner.FieldInfo("f1", "名称", "Text", null),
            new ViewFilterPlanner.FieldInfo("f2", "创建时间", "Date", null)
        );

        List<ViewFilterPlanner.ViewInfo> views = List.of(
            new ViewFilterPlanner.ViewInfo("v1", "全部", "ws-1", 0)
        );

        ViewFilterPlanner.Input input = new ViewFilterPlanner.Input(views, fields);
        ViewFilterPlanner.Output output = planner.plan(input);

        assertEquals(1, output.getFilterPlans().size());
        // 应该有时间筛选条件（今天、本周、本月）
        assertTrue(output.getFilterPlans().get(0).getFilters().size() >= 3);

        // 验证时间筛选类型
        boolean hasTimeFilter = output.getFilterPlans().get(0).getFilters().stream()
            .anyMatch(f -> "time_range".equals(f.getFilterType()));
        assertTrue(hasTimeFilter, "应该有时间筛选条件");
    }

    @Test
    void testPlanWithSelectFields() throws Exception {
        List<ViewFilterPlanner.FieldInfo> fields = List.of(
            new ViewFilterPlanner.FieldInfo("f1", "名称", "Text", null),
            new ViewFilterPlanner.FieldInfo("f2", "状态", "SingleSelect", List.of("待办", "进行中", "已完成"))
        );

        List<ViewFilterPlanner.ViewInfo> views = List.of(
            new ViewFilterPlanner.ViewInfo("v1", "全部", "ws-1", 0)
        );

        ViewFilterPlanner.Input input = new ViewFilterPlanner.Input(views, fields);
        ViewFilterPlanner.Output output = planner.plan(input);

        // 验证有选项筛选
        boolean hasSelectFilter = output.getFilterPlans().get(0).getFilters().stream()
            .anyMatch(f -> "select".equals(f.getFilterType()));
        assertTrue(hasSelectFilter, "应该有选项筛选条件");
    }

    @Test
    void testPlanWithCollaboratorFields() throws Exception {
        List<ViewFilterPlanner.FieldInfo> fields = List.of(
            new ViewFilterPlanner.FieldInfo("f1", "名称", "Text", null),
            new ViewFilterPlanner.FieldInfo("f2", "负责人", "Collaborator", null)
        );

        List<ViewFilterPlanner.ViewInfo> views = List.of(
            new ViewFilterPlanner.ViewInfo("v1", "全部", "ws-1", 0)
        );

        ViewFilterPlanner.Input input = new ViewFilterPlanner.Input(views, fields);
        ViewFilterPlanner.Output output = planner.plan(input);

        // 验证有人员筛选
        boolean hasPeopleFilter = output.getFilterPlans().get(0).getFilters().stream()
            .anyMatch(f -> "people".equals(f.getFilterType()));
        assertTrue(hasPeopleFilter, "应该有人员筛选条件");
    }

    @Test
    void testPlanWithNumberFields() throws Exception {
        List<ViewFilterPlanner.FieldInfo> fields = List.of(
            new ViewFilterPlanner.FieldInfo("f1", "名称", "Text", null),
            new ViewFilterPlanner.FieldInfo("f2", "金额", "Money", null)
        );

        List<ViewFilterPlanner.ViewInfo> views = List.of(
            new ViewFilterPlanner.ViewInfo("v1", "全部", "ws-1", 0)
        );

        ViewFilterPlanner.Input input = new ViewFilterPlanner.Input(views, fields);
        ViewFilterPlanner.Output output = planner.plan(input);

        // 验证有数值筛选
        boolean hasNumberFilter = output.getFilterPlans().get(0).getFilters().stream()
            .anyMatch(f -> "number".equals(f.getFilterType()));
        assertTrue(hasNumberFilter, "应该有数值筛选条件");
    }

    @Test
    void testPlanWithMultipleViews() throws Exception {
        List<ViewFilterPlanner.FieldInfo> fields = List.of(
            new ViewFilterPlanner.FieldInfo("f1", "创建时间", "Date", null)
        );

        List<ViewFilterPlanner.ViewInfo> views = List.of(
            new ViewFilterPlanner.ViewInfo("v1", "表格视图", "ws-1", 0),
            new ViewFilterPlanner.ViewInfo("v2", "看板视图", "ws-1", 1)
        );

        ViewFilterPlanner.Input input = new ViewFilterPlanner.Input(views, fields);
        ViewFilterPlanner.Output output = planner.plan(input);

        assertEquals(2, output.getFilterPlans().size());
    }

    @Test
    void testPlanWithComplexFields() throws Exception {
        // 复杂场景：多种字段类型
        List<ViewFilterPlanner.FieldInfo> fields = List.of(
            new ViewFilterPlanner.FieldInfo("f1", "客户名称", "Text", null),
            new ViewFilterPlanner.FieldInfo("f2", "状态", "SingleSelect", List.of("新客户", "老客户")),
            new ViewFilterPlanner.FieldInfo("f3", "负责人", "Collaborator", null),
            new ViewFilterPlanner.FieldInfo("f4", "签约金额", "Money", null),
            new ViewFilterPlanner.FieldInfo("f5", "签约日期", "Date", null)
        );

        List<ViewFilterPlanner.ViewInfo> views = List.of(
            new ViewFilterPlanner.ViewInfo("v1", "全部客户", "ws-1", 0)
        );

        ViewFilterPlanner.Input input = new ViewFilterPlanner.Input(views, fields);
        ViewFilterPlanner.Output output = planner.plan(input);

        assertEquals(1, output.getFilterPlans().size());
        // 应该有多个筛选条件
        assertTrue(output.getFilterPlans().get(0).getFilters().size() >= 5);
    }

    @Test
    void testFilterCountLimit() throws Exception {
        // 测试筛选条件数量限制
        List<ViewFilterPlanner.FieldInfo> fields = List.of(
            new ViewFilterPlanner.FieldInfo("f1", "状态1", "SingleSelect", List.of("A", "B")),
            new ViewFilterPlanner.FieldInfo("f2", "状态2", "SingleSelect", List.of("C", "D")),
            new ViewFilterPlanner.FieldInfo("f3", "状态3", "SingleSelect", List.of("E", "F")),
            new ViewFilterPlanner.FieldInfo("f4", "状态4", "SingleSelect", List.of("G", "H")),
            new ViewFilterPlanner.FieldInfo("f5", "状态5", "SingleSelect", List.of("I", "J")),
            new ViewFilterPlanner.FieldInfo("f6", "创建时间", "Date", null)
        );

        List<ViewFilterPlanner.ViewInfo> views = List.of(
            new ViewFilterPlanner.ViewInfo("v1", "全部", "ws-1", 0)
        );

        ViewFilterPlanner.Input input = new ViewFilterPlanner.Input(views, fields);
        ViewFilterPlanner.Output output = planner.plan(input);

        // 验证筛选条件不超过5个
        assertTrue(output.getFilterPlans().get(0).getFilters().size() <= 5);
    }
}
