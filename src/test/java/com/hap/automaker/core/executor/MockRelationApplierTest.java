package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hap.automaker.api.HapApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockRelationApplier 测试类
 */
class MockRelationApplierTest {

    private MockHapApiClient apiClient;
    private MockRelationApplier applier;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        apiClient = new MockHapApiClient();
        applier = new MockRelationApplier(apiClient, 2);
        mapper = new ObjectMapper();
    }

    @Test
    void testGetName() {
        assertEquals("MockRelationApplier", applier.getName());
    }

    @Test
    void testExecuteDryRun() throws Exception {
        MockRelationApplier.RowAssignment assignment = new MockRelationApplier.RowAssignment(
            "row-src-001", "row-tgt-001"
        );

        MockRelationApplier.RelationPlan plan = new MockRelationApplier.RelationPlan(
            "ws-002", "订单表", "rel-001", "客户", "ws-001",
            List.of(assignment)
        );

        Map<String, Integer> tierMap = Map.of("ws-002", 3);

        MockRelationApplier.Input input = new MockRelationApplier.Input(
            List.of(plan), tierMap, true, false, false
        );

        MockRelationApplier.Output output = applier.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getTotalPlanned());
        assertEquals(0, output.getTotalUpdated()); // dry run doesn't actually update
        assertEquals(0, output.getTotalFailed());
    }

    @Test
    void testExecuteWithMockApi() throws Exception {
        apiClient.setNextResponse(mapper.readTree("""
            {
                "success": true,
                "data": {
                    "id": "row-updated"
                }
            }
            """));

        MockRelationApplier.RowAssignment assignment1 = new MockRelationApplier.RowAssignment(
            "row-src-001", "row-tgt-001"
        );
        MockRelationApplier.RowAssignment assignment2 = new MockRelationApplier.RowAssignment(
            "row-src-002", "row-tgt-002"
        );

        MockRelationApplier.RelationPlan plan = new MockRelationApplier.RelationPlan(
            "ws-002", "订单表", "rel-001", "客户", "ws-001",
            List.of(assignment1, assignment2)
        );

        Map<String, Integer> tierMap = Map.of("ws-002", 3);

        MockRelationApplier.Input input = new MockRelationApplier.Input(
            List.of(plan), tierMap, false, false, false
        );

        MockRelationApplier.Output output = applier.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getTotalPlanned());
        assertEquals(2, output.getTotalUpdated());
        assertEquals(0, output.getTotalFailed());
    }

    @Test
    void testExecuteWithEmptyAssignments() throws Exception {
        MockRelationApplier.RelationPlan plan = new MockRelationApplier.RelationPlan(
            "ws-002", "订单表", "rel-001", "客户", "ws-001",
            List.of()
        );

        Map<String, Integer> tierMap = Map.of("ws-002", 3);

        MockRelationApplier.Input input = new MockRelationApplier.Input(
            List.of(plan), tierMap, false, false, false
        );

        MockRelationApplier.Output output = applier.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(0, output.getTotalPlanned());
    }

    @Test
    void testRelationPlan() {
        List<MockRelationApplier.RowAssignment> assignments = List.of(
            new MockRelationApplier.RowAssignment("src-1", "tgt-1"),
            new MockRelationApplier.RowAssignment("src-2", "tgt-1") // Round-robin back to first target
        );

        MockRelationApplier.RelationPlan plan = new MockRelationApplier.RelationPlan(
            "ws-002", "订单表", "customer-field", "客户", "ws-001",
            assignments
        );

        assertEquals("ws-002", plan.getWorksheetId());
        assertEquals("订单表", plan.getWorksheetName());
        assertEquals("customer-field", plan.getFieldId());
        assertEquals("客户", plan.getFieldName());
        assertEquals("ws-001", plan.getTargetWorksheetId());
        assertEquals(2, plan.getAssignments().size());
    }

    @Test
    void testRowAssignment() {
        MockRelationApplier.RowAssignment assignment = new MockRelationApplier.RowAssignment(
            "source-row-123", "target-row-456"
        );

        assertEquals("source-row-123", assignment.getSourceRowId());
        assertEquals("target-row-456", assignment.getTargetRowId());
    }

    @Test
    void testWorksheetResult() {
        MockRelationApplier.WorksheetResult result = new MockRelationApplier.WorksheetResult(
            "ws-002", "订单表", "rel-001", "客户",
            true, 3, 3, 0
        );

        assertEquals("ws-002", result.getWorksheetId());
        assertEquals("订单表", result.getWorksheetName());
        assertEquals("rel-001", result.getFieldId());
        assertEquals("客户", result.getFieldName());
        assertTrue(result.isSuccess());
        assertEquals(3, result.getPlanned());
        assertEquals(3, result.getUpdated());
        assertEquals(0, result.getFailed());
    }

    @Test
    void testWorksheetResultWithErrors() {
        MockRelationApplier.WorksheetResult result = new MockRelationApplier.WorksheetResult(
            "ws-002", "订单表", "rel-001", "客户",
            false, 3, 1, 2
        );

        assertFalse(result.isSuccess());
        assertEquals(3, result.getPlanned());
        assertEquals(1, result.getUpdated());
        assertEquals(2, result.getFailed());
    }

    @Test
    void testInputCreation() {
        MockRelationApplier.RowAssignment assignment = new MockRelationApplier.RowAssignment(
            "src-001", "tgt-001"
        );

        MockRelationApplier.RelationPlan plan = new MockRelationApplier.RelationPlan(
            "ws-002", "订单表", "rel-001", "客户", "ws-001",
            List.of(assignment)
        );

        Map<String, Integer> tierMap = Map.of(
            "ws-001", 1,
            "ws-002", 3
        );

        MockRelationApplier.Input input = new MockRelationApplier.Input(
            List.of(plan), tierMap, false, true, true
        );

        assertFalse(input.isDryRun());
        assertTrue(input.isFailFast());
        assertTrue(input.isTriggerWorkflow());
        assertEquals(1, input.getPlans().size());
        assertEquals(2, input.getTierMap().size());
    }

    @Test
    void testOutput() {
        MockRelationApplier.WorksheetResult result = new MockRelationApplier.WorksheetResult(
            "ws-002", "订单表", "rel-001", "客户",
            true, 2, 2, 0
        );

        Map<String, MockRelationApplier.WorksheetResult> results = Map.of(
            "ws-002:rel-001", result
        );

        MockRelationApplier.Output output = new MockRelationApplier.Output(
            true, results, 2, 2, 0, null
        );

        assertTrue(output.isSuccess());
        assertEquals(2, output.getTotalPlanned());
        assertEquals(2, output.getTotalUpdated());
        assertEquals(0, output.getTotalFailed());
        assertEquals(1, output.getResults().size());
        assertNull(output.getErrorMessage());
    }

    @Test
    void testOutputWithError() {
        MockRelationApplier.Output output = new MockRelationApplier.Output(
            false, Map.of(), 0, 0, 0, "Relation update failed"
        );

        assertFalse(output.isSuccess());
        assertEquals("Relation update failed", output.getErrorMessage());
    }

    @Test
    void testMultipleTiers() throws Exception {
        apiClient.setNextResponse(mapper.readTree("""
            {
                "success": true,
                "data": {
                    "id": "row-updated"
                }
            }
            """));

        // Tier 3 plans (processed first)
        MockRelationApplier.RelationPlan planTier3 = new MockRelationApplier.RelationPlan(
            "ws-detail", "明细表", "rel-1", "主表", "ws-master",
            List.of(new MockRelationApplier.RowAssignment("src-1", "tgt-1"))
        );

        // Tier 2 plans (processed second)
        MockRelationApplier.RelationPlan planTier2 = new MockRelationApplier.RelationPlan(
            "ws-11", "一对一表", "rel-2", "关联", "ws-target",
            List.of(new MockRelationApplier.RowAssignment("src-2", "tgt-2"))
        );

        Map<String, Integer> tierMap = Map.of(
            "ws-detail", 3,
            "ws-11", 2
        );

        MockRelationApplier.Input input = new MockRelationApplier.Input(
            List.of(planTier3, planTier2), tierMap, false, false, false
        );

        MockRelationApplier.Output output = applier.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getTotalPlanned());
    }

    @Test
    void testNullSafety() {
        MockRelationApplier.Input input = new MockRelationApplier.Input(
            null, null, false, false, false
        );

        assertTrue(input.getPlans().isEmpty());
        assertTrue(input.getTierMap().isEmpty());

        MockRelationApplier.Output output = new MockRelationApplier.Output(
            true, null, 0, 0, 0, null
        );

        assertTrue(output.getResults().isEmpty());
    }
}
