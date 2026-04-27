package com.hap.automaker.core.planner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockRelationPlanner 测试类
 */
class MockRelationPlannerTest {

    private MockRelationPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new MockRelationPlanner();
    }

    @Test
    void testGetName() {
        assertEquals("MockRelationPlanner", planner.getName());
    }

    @Test
    void testInputCreation() {
        MockRelationPlanner.WorksheetInfo ws1 = new MockRelationPlanner.WorksheetInfo(
            "ws-001", "客户表", List.of()
        );
        MockRelationPlanner.WorksheetInfo ws2 = new MockRelationPlanner.WorksheetInfo(
            "ws-002", "订单表", List.of()
        );

        MockRelationPlanner.RelationPair pair = new MockRelationPlanner.RelationPair(
            "ws-001", "ws-002", "1-N"
        );

        MockRelationPlanner.RelationEdge edge = new MockRelationPlanner.RelationEdge(
            "ws-002", "ws-001", "rel-001", "客户", 1, true
        );

        Map<String, List<String>> allRowIds = Map.of(
            "ws-001", List.of("row-001", "row-002"),
            "ws-002", List.of("row-003", "row-004", "row-005")
        );

        MockRelationPlanner.Input input = new MockRelationPlanner.Input(
            List.of(ws1, ws2),
            List.of(pair),
            List.of(edge),
            allRowIds
        );

        assertEquals(2, input.getWorksheets().size());
        assertEquals(1, input.getRelationPairs().size());
        assertEquals(1, input.getRelationEdges().size());
        assertEquals(2, input.getAllRowIds().size());
    }

    @Test
    void testWorksheetInfo() {
        MockRelationPlanner.FieldInfo field = new MockRelationPlanner.FieldInfo(
            "f-001", "客户名称", "Text"
        );

        MockRelationPlanner.WorksheetInfo ws = new MockRelationPlanner.WorksheetInfo(
            "ws-001", "客户表", List.of(field)
        );

        assertEquals("ws-001", ws.getWorksheetId());
        assertEquals("客户表", ws.getWorksheetName());
        assertEquals(1, ws.getFields().size());
    }

    @Test
    void testFieldInfo() {
        MockRelationPlanner.FieldInfo field = new MockRelationPlanner.FieldInfo(
            "f-001", "状态", "SingleSelect"
        );

        assertEquals("f-001", field.getFieldId());
        assertEquals("状态", field.getName());
        assertEquals("SingleSelect", field.getType());
    }

    @Test
    void testRelationPair() {
        MockRelationPlanner.RelationPair pair = new MockRelationPlanner.RelationPair(
            "ws-001", "ws-002", "1-N"
        );

        assertEquals("ws-001", pair.getWorksheetAId());
        assertEquals("ws-002", pair.getWorksheetBId());
        assertEquals("1-N", pair.getPairType());
    }

    @Test
    void testRelationPairNullType() {
        MockRelationPlanner.RelationPair pair = new MockRelationPlanner.RelationPair(
            "ws-001", "ws-002", null
        );

        assertEquals("ambiguous", pair.getPairType());
    }

    @Test
    void testRelationEdge() {
        MockRelationPlanner.RelationEdge edge = new MockRelationPlanner.RelationEdge(
            "ws-002", "ws-001", "rel-001", "客户", 1, true
        );

        assertEquals("ws-002", edge.getSourceWorksheetId());
        assertEquals("ws-001", edge.getTargetWorksheetId());
        assertEquals("rel-001", edge.getFieldId());
        assertEquals("客户", edge.getFieldName());
        assertEquals(1, edge.getSubType());
        assertTrue(edge.isBidirectional());
    }

    @Test
    void testOutput() {
        MockRelationPlanner.RowAssignment assignment = new MockRelationPlanner.RowAssignment(
            "row-003", "row-001"
        );

        MockRelationPlanner.RelationAssignmentPlan plan = new MockRelationPlanner.RelationAssignmentPlan(
            "ws-002", "订单表", "rel-001", "客户", "ws-001",
            List.of(assignment)
        );

        MockRelationPlanner.Output output = new MockRelationPlanner.Output(List.of(plan));

        assertEquals(1, output.getPlans().size());
        assertEquals("ws-002", output.getPlans().get(0).getWorksheetId());
        assertEquals("rel-001", output.getPlans().get(0).getFieldId());
    }

    @Test
    void testRelationAssignmentPlan() {
        List<MockRelationPlanner.RowAssignment> assignments = List.of(
            new MockRelationPlanner.RowAssignment("src-1", "tgt-1"),
            new MockRelationPlanner.RowAssignment("src-2", "tgt-2"),
            new MockRelationPlanner.RowAssignment("src-3", "tgt-1") // round-robin back to first
        );

        MockRelationPlanner.RelationAssignmentPlan plan = new MockRelationPlanner.RelationAssignmentPlan(
            "ws-002", "订单表", "customer-field", "客户", "ws-001",
            assignments
        );

        assertEquals("ws-002", plan.getWorksheetId());
        assertEquals("订单表", plan.getWorksheetName());
        assertEquals("customer-field", plan.getFieldId());
        assertEquals("客户", plan.getFieldName());
        assertEquals("ws-001", plan.getTargetWorksheetId());
        assertEquals(3, plan.getAssignments().size());
    }

    @Test
    void testRowAssignment() {
        MockRelationPlanner.RowAssignment assignment = new MockRelationPlanner.RowAssignment(
            "source-row-123", "target-row-456"
        );

        assertEquals("source-row-123", assignment.getSourceRowId());
        assertEquals("target-row-456", assignment.getTargetRowId());
    }

    @Test
    void testEmptyOutput() {
        MockRelationPlanner.Output output = new MockRelationPlanner.Output(List.of());
        assertTrue(output.getPlans().isEmpty());
    }

    @Test
    void testNullSafety() {
        MockRelationPlanner.Input input = new MockRelationPlanner.Input(
            null, null, null, null
        );

        assertTrue(input.getWorksheets().isEmpty());
        assertTrue(input.getRelationPairs().isEmpty());
        assertTrue(input.getRelationEdges().isEmpty());
        assertTrue(input.getAllRowIds().isEmpty());

        MockRelationPlanner.Output output = new MockRelationPlanner.Output(null);
        assertTrue(output.getPlans().isEmpty());
    }

    @Test
    void testPlanWithEmptyRowIds() {
        // Create a scenario where target worksheet has no rowIds
        MockRelationPlanner.WorksheetInfo ws = new MockRelationPlanner.WorksheetInfo(
            "ws-001", "客户表", List.of()
        );

        MockRelationPlanner.RelationEdge edge = new MockRelationPlanner.RelationEdge(
            "ws-001", "ws-002", "rel-001", "关联字段", 1, false
        );

        // ws-002 has no rowIds
        Map<String, List<String>> allRowIds = Map.of(
            "ws-001", List.of("row-001")
        );

        MockRelationPlanner.Input input = new MockRelationPlanner.Input(
            List.of(ws),
            List.of(),
            List.of(edge),
            allRowIds
        );

        MockRelationPlanner.Output output = planner.plan(input);

        // Should not generate plans because target has no rows
        assertTrue(output.getPlans().isEmpty());
    }

    @Test
    void testPlanWithoutSubType1() {
        // Edge with subType=2 should not be processed
        MockRelationPlanner.WorksheetInfo ws = new MockRelationPlanner.WorksheetInfo(
            "ws-001", "客户表", List.of()
        );

        MockRelationPlanner.RelationEdge edge = new MockRelationPlanner.RelationEdge(
            "ws-001", "ws-002", "rel-001", "关联字段", 2, false
        );

        Map<String, List<String>> allRowIds = Map.of(
            "ws-001", List.of("row-001"),
            "ws-002", List.of("row-002")
        );

        MockRelationPlanner.Input input = new MockRelationPlanner.Input(
            List.of(ws),
            List.of(),
            List.of(edge),
            allRowIds
        );

        MockRelationPlanner.Output output = planner.plan(input);

        // subType=2 should be filtered out
        assertTrue(output.getPlans().isEmpty());
    }
}
