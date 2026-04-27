package com.hap.automaker.core.planner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LayoutPlanner 测试类
 */
class LayoutPlannerTest {

    private LayoutPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new LayoutPlanner();
    }

    @Test
    void testGetName() {
        assertEquals("LayoutPlanner", planner.getName());
    }

    @Test
    void testValidateEmptyResult() {
        assertFalse(planner.validate(null));
    }

    @Test
    void testValidateValidResult() {
        LayoutPlanner.Output output = new LayoutPlanner.Output(List.of());
        assertTrue(planner.validate(output));
    }

    @Test
    void testFieldInfo() {
        LayoutPlanner.FieldInfo field = new LayoutPlanner.FieldInfo(
            "field-001", "客户名称", "Text", true
        );

        assertEquals("field-001", field.getControlId());
        assertEquals("客户名称", field.getControlName());
        assertEquals("Text", field.getType());
        assertTrue(field.isTitle());
    }

    @Test
    void testWorksheetInfo() {
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "名称", "Text", true),
            new LayoutPlanner.FieldInfo("f2", "状态", "SingleSelect", false)
        );

        LayoutPlanner.WorksheetInfo ws = new LayoutPlanner.WorksheetInfo(
            "ws-123", "客户表", "存储客户信息", fields
        );

        assertEquals("ws-123", ws.getWorksheetId());
        assertEquals("客户表", ws.getWorksheetName());
        assertEquals("存储客户信息", ws.getPurpose());
        assertEquals(2, ws.getFields().size());
    }

    @Test
    void testFieldGroup() {
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "名称", "Text", true)
        );

        LayoutPlanner.FieldGroup group = new LayoutPlanner.FieldGroup("基础信息", fields);

        assertEquals("基础信息", group.getTitle());
        assertEquals(1, group.getFields().size());

        group.setTitle(null);
        assertNull(group.getTitle());
    }

    @Test
    void testInputCreation() {
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "名称", "Text", true)
        );
        LayoutPlanner.WorksheetInfo ws = new LayoutPlanner.WorksheetInfo(
            "ws-123", "客户表", "存储客户信息", fields
        );

        LayoutPlanner.Input input = new LayoutPlanner.Input(List.of(ws));

        assertEquals(1, input.getWorksheets().size());
    }

    @Test
    void testOutputCreation() {
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "名称", "Text", true)
        );
        LayoutPlanner.FieldGroup group = new LayoutPlanner.FieldGroup("基础", fields);
        LayoutPlanner.FieldLayout layout = new LayoutPlanner.FieldLayout(List.of(group));
        LayoutPlanner.WorksheetLayout wsLayout = new LayoutPlanner.WorksheetLayout(
            "ws-123", "客户表", layout
        );

        LayoutPlanner.Output output = new LayoutPlanner.Output(List.of(wsLayout));

        assertEquals(1, output.getLayouts().size());
        assertEquals("ws-123", output.getLayouts().get(0).getWorksheetId());
        assertEquals("客户表", output.getLayouts().get(0).getWorksheetName());
    }

    @Test
    void testPlanWithBasicFields() throws Exception {
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "客户名称", "Text", true),
            new LayoutPlanner.FieldInfo("f2", "状态", "SingleSelect", false),
            new LayoutPlanner.FieldInfo("f3", "创建时间", "Date", false)
        );

        LayoutPlanner.WorksheetInfo ws = new LayoutPlanner.WorksheetInfo(
            "ws-123", "客户表", "客户信息管理", fields
        );

        LayoutPlanner.Input input = new LayoutPlanner.Input(List.of(ws));
        LayoutPlanner.Output output = planner.plan(input);

        assertEquals(1, output.getLayouts().size());
        // 应该有基础信息分组
        assertTrue(output.getLayouts().get(0).getLayout().getGroups().size() >= 1);
    }

    @Test
    void testPlanWithMoneyFields() throws Exception {
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "订单金额", "Money", false),
            new LayoutPlanner.FieldInfo("f2", "数量", "Number", false),
            new LayoutPlanner.FieldInfo("f3", "负责人", "Collaborator", false)
        );

        LayoutPlanner.WorksheetInfo ws = new LayoutPlanner.WorksheetInfo(
            "ws-123", "订单表", "订单管理", fields
        );

        LayoutPlanner.Input input = new LayoutPlanner.Input(List.of(ws));
        LayoutPlanner.Output output = planner.plan(input);

        assertEquals(1, output.getLayouts().size());
    }

    @Test
    void testPlanWithManyFields() throws Exception {
        // 测试字段较多时的分组
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "客户名称", "Text", true),
            new LayoutPlanner.FieldInfo("f2", "状态", "SingleSelect", false),
            new LayoutPlanner.FieldInfo("f3", "创建时间", "Date", false),
            new LayoutPlanner.FieldInfo("f4", "负责人", "Collaborator", false),
            new LayoutPlanner.FieldInfo("f5", "金额", "Money", false),
            new LayoutPlanner.FieldInfo("f6", "备注", "Text", false),
            new LayoutPlanner.FieldInfo("f7", "附件", "Attachment", false)
        );

        LayoutPlanner.WorksheetInfo ws = new LayoutPlanner.WorksheetInfo(
            "ws-123", "客户表", "客户管理", fields
        );

        LayoutPlanner.Input input = new LayoutPlanner.Input(List.of(ws));
        LayoutPlanner.Output output = planner.plan(input);

        assertEquals(1, output.getLayouts().size());
        // 应该有多个分组
        assertTrue(output.getLayouts().get(0).getLayout().getGroups().size() >= 2);
    }

    @Test
    void testPlanWithFewFields() throws Exception {
        // 测试字段较少时的情况
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "字段1", "Text", true),
            new LayoutPlanner.FieldInfo("f2", "字段2", "Text", false)
        );

        LayoutPlanner.WorksheetInfo ws = new LayoutPlanner.WorksheetInfo(
            "ws-123", "简单表", "简单用途", fields
        );

        LayoutPlanner.Input input = new LayoutPlanner.Input(List.of(ws));
        LayoutPlanner.Output output = planner.plan(input);

        assertEquals(1, output.getLayouts().size());
        // 两个字段都是普通文本，应该只有一个分组
        assertEquals(1, output.getLayouts().get(0).getLayout().getGroups().size());
    }

    @Test
    void testPlanWithMultipleWorksheets() throws Exception {
        LayoutPlanner.WorksheetInfo ws1 = new LayoutPlanner.WorksheetInfo(
            "ws-1", "客户表", "客户管理",
            List.of(new LayoutPlanner.FieldInfo("f1", "名称", "Text", true))
        );

        LayoutPlanner.WorksheetInfo ws2 = new LayoutPlanner.WorksheetInfo(
            "ws-2", "订单表", "订单管理",
            List.of(new LayoutPlanner.FieldInfo("f2", "金额", "Money", false))
        );

        LayoutPlanner.Input input = new LayoutPlanner.Input(List.of(ws1, ws2));
        LayoutPlanner.Output output = planner.plan(input);

        assertEquals(2, output.getLayouts().size());
        assertEquals("ws-1", output.getLayouts().get(0).getWorksheetId());
        assertEquals("ws-2", output.getLayouts().get(1).getWorksheetId());
    }

    @Test
    void testFieldLayout() {
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "名称", "Text", true)
        );
        LayoutPlanner.FieldGroup group = new LayoutPlanner.FieldGroup("测试组", fields);
        LayoutPlanner.FieldLayout layout = new LayoutPlanner.FieldLayout(List.of(group));

        assertEquals(1, layout.getGroups().size());
        assertEquals("测试组", layout.getGroups().get(0).getTitle());
    }

    @Test
    void testWorksheetLayout() {
        List<LayoutPlanner.FieldInfo> fields = List.of(
            new LayoutPlanner.FieldInfo("f1", "名称", "Text", true)
        );
        LayoutPlanner.FieldGroup group = new LayoutPlanner.FieldGroup("基础", fields);
        LayoutPlanner.FieldLayout layout = new LayoutPlanner.FieldLayout(List.of(group));
        LayoutPlanner.WorksheetLayout wsLayout = new LayoutPlanner.WorksheetLayout(
            "ws-123", "测试表", layout
        );

        assertEquals("ws-123", wsLayout.getWorksheetId());
        assertEquals("测试表", wsLayout.getWorksheetName());
        assertNotNull(wsLayout.getLayout());
    }
}
