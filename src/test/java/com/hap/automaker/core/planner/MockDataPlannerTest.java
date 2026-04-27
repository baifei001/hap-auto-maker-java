package com.hap.automaker.core.planner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockDataPlanner 测试类
 */
class MockDataPlannerTest {

    private MockDataPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new MockDataPlanner(null); // AI client not needed for basic tests
    }

    @Test
    void testGetName() {
        assertEquals("MockDataPlanner", planner.getName());
    }

    @Test
    void testInputCreation() {
        MockDataPlanner.WorksheetInfo ws = new MockDataPlanner.WorksheetInfo(
            "ws-001", "客户表", List.of(
                new MockDataPlanner.FieldInfo("f1", "客户名称", "Text", List.of()),
                new MockDataPlanner.FieldInfo("f2", "状态", "SingleSelect", List.of("活跃", "暂停"))
            )
        );

        MockDataPlanner.RelationPair pair = new MockDataPlanner.RelationPair(
            "ws-001", "ws-002", "1-N"
        );

        MockDataPlanner.RelationEdge edge = new MockDataPlanner.RelationEdge(
            "ws-002", "ws-001", 1
        );

        MockDataPlanner.Input input = new MockDataPlanner.Input(
            "CRM系统", "客户关系管理",
            List.of(ws),
            List.of(pair),
            List.of(edge)
        );

        assertEquals("CRM系统", input.getAppName());
        assertEquals("客户关系管理", input.getBusinessContext());
        assertEquals(1, input.getWorksheets().size());
        assertEquals(1, input.getRelationPairs().size());
        assertEquals(1, input.getRelationEdges().size());
    }

    @Test
    void testFieldInfo() {
        MockDataPlanner.FieldInfo field = new MockDataPlanner.FieldInfo(
            "f-001", "客户名称", "Text", List.of()
        );

        assertEquals("f-001", field.getFieldId());
        assertEquals("客户名称", field.getName());
        assertEquals("Text", field.getType());
        assertTrue(field.getOptions().isEmpty());
    }

    @Test
    void testFieldInfoWithOptions() {
        MockDataPlanner.FieldInfo field = new MockDataPlanner.FieldInfo(
            "f-002", "状态", "SingleSelect", List.of("新建", "处理中", "已完成")
        );

        assertEquals(3, field.getOptions().size());
        assertEquals("新建", field.getOptions().get(0));
    }

    @Test
    void testRelationPair() {
        MockDataPlanner.RelationPair pair = new MockDataPlanner.RelationPair(
            "ws-001", "ws-002", "1-N"
        );

        assertEquals("ws-001", pair.getWorksheetAId());
        assertEquals("ws-002", pair.getWorksheetBId());
        assertEquals("1-N", pair.getPairType());
    }

    @Test
    void testRelationEdge() {
        MockDataPlanner.RelationEdge edge = new MockDataPlanner.RelationEdge(
            "ws-001", "ws-002", 1
        );

        assertEquals("ws-001", edge.getSourceWorksheetId());
        assertEquals("ws-002", edge.getTargetWorksheetId());
        assertEquals(1, edge.getSubType());
    }

    @Test
    void testWorksheetInfo() {
        List<MockDataPlanner.FieldInfo> fields = List.of(
            new MockDataPlanner.FieldInfo("f1", "名称", "Text", List.of()),
            new MockDataPlanner.FieldInfo("f2", "金额", "Currency", List.of())
        );

        MockDataPlanner.WorksheetInfo ws = new MockDataPlanner.WorksheetInfo(
            "ws-123", "订单表", fields
        );

        assertEquals("ws-123", ws.getWorksheetId());
        assertEquals("订单表", ws.getWorksheetName());
        assertEquals(2, ws.getFields().size());
    }

    @Test
    void testMockRecord() {
        Map<String, Object> values = Map.of(
            "f1", "测试客户",
            "f2", 10000,
            "f3", true
        );

        MockDataPlanner.MockRecord record = new MockDataPlanner.MockRecord(
            "测试客户记录", values
        );

        assertEquals("测试客户记录", record.getRecordSummary());
        assertEquals(3, record.getValuesByFieldId().size());
        assertEquals("测试客户", record.getValuesByFieldId().get("f1"));
        assertEquals(10000, record.getValuesByFieldId().get("f2"));
        assertEquals(true, record.getValuesByFieldId().get("f3"));
    }

    @Test
    void testWorksheetMockPlan() {
        List<MockDataPlanner.MockRecord> records = List.of(
            new MockDataPlanner.MockRecord("记录1", Map.of("f1", "值1")),
            new MockDataPlanner.MockRecord("记录2", Map.of("f1", "值2"))
        );

        MockDataPlanner.WorksheetMockPlan plan = new MockDataPlanner.WorksheetMockPlan(
            "ws-001", "客户表", 5, records
        );

        assertEquals("ws-001", plan.getWorksheetId());
        assertEquals("客户表", plan.getWorksheetName());
        assertEquals(5, plan.getRecordCount());
        assertEquals(2, plan.getRecords().size());
    }

    @Test
    void testOutput() {
        List<MockDataPlanner.MockRecord> records = List.of(
            new MockDataPlanner.MockRecord("记录1", Map.of("f1", "值1"))
        );

        MockDataPlanner.WorksheetMockPlan plan = new MockDataPlanner.WorksheetMockPlan(
            "ws-001", "客户表", 1, records
        );

        MockDataPlanner.Output output = new MockDataPlanner.Output(List.of(plan));

        assertEquals(1, output.getPlans().size());
        assertEquals("ws-001", output.getPlans().get(0).getWorksheetId());
    }

    @Test
    void testEmptyOutput() {
        MockDataPlanner.Output output = new MockDataPlanner.Output(List.of());
        assertTrue(output.getPlans().isEmpty());
    }

    @Test
    void testNullSafety() {
        MockDataPlanner.Input input = new MockDataPlanner.Input(
            "Test", null, null, null, null
        );

        assertEquals("", input.getBusinessContext());
        assertTrue(input.getWorksheets().isEmpty());
        assertTrue(input.getRelationPairs().isEmpty());
        assertTrue(input.getRelationEdges().isEmpty());
    }

    @Test
    void testMockRecordWithComplexValues() {
        Map<String, Object> values = Map.of(
            "textField", "文本值",
            "numberField", 12345,
            "boolField", true,
            "listField", List.of("选项1", "选项2"),
            "mapField", Map.of("key", "value")
        );

        MockDataPlanner.MockRecord record = new MockDataPlanner.MockRecord(
            "复杂记录", values
        );

        assertEquals("复杂记录", record.getRecordSummary());
        assertEquals(5, record.getValuesByFieldId().size());
    }
}
