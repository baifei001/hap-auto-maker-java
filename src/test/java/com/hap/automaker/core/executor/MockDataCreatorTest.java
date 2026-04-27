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
 * MockDataCreator 测试类
 */
class MockDataCreatorTest {

    private MockHapApiClient apiClient;
    private MockDataCreator creator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        apiClient = new MockHapApiClient();
        creator = new MockDataCreator(apiClient, 2);
        mapper = new ObjectMapper();
    }

    @Test
    void testGetName() {
        assertEquals("MockDataCreator", creator.getName());
    }

    @Test
    void testExecuteDryRun() throws Exception {
        MockDataCreator.MockRecord record = new MockDataCreator.MockRecord(
            "测试记录",
            Map.of("f1", "测试值", "f2", 100)
        );

        Map<String, MockDataCreator.FieldMeta> fieldMeta = Map.of(
            "f1", new MockDataCreator.FieldMeta("f1", "名称", "Text", 2),
            "f2", new MockDataCreator.FieldMeta("f2", "数量", "Number", 6)
        );

        MockDataCreator.WorksheetMockPlan plan = new MockDataCreator.WorksheetMockPlan(
            "ws-001", "客户表", List.of(record), fieldMeta
        );

        MockDataCreator.Input input = new MockDataCreator.Input(
            List.of(plan), true, false, false
        );

        MockDataCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getResults().size());
        assertEquals(0, output.getTotalCreated()); // dry run doesn't create actual records
    }

    @Test
    void testExecuteWithMockApi() throws Exception {
        apiClient.setNextResponse(mapper.readTree("""
            {
                "success": true,
                "data": {
                    "rowIds": ["row-001", "row-002"]
                }
            }
            """));

        MockDataCreator.MockRecord record1 = new MockDataCreator.MockRecord(
            "记录1", Map.of("f1", "值1")
        );
        MockDataCreator.MockRecord record2 = new MockDataCreator.MockRecord(
            "记录2", Map.of("f1", "值2")
        );

        Map<String, MockDataCreator.FieldMeta> fieldMeta = Map.of(
            "f1", new MockDataCreator.FieldMeta("f1", "名称", "Text", 2)
        );

        MockDataCreator.WorksheetMockPlan plan = new MockDataCreator.WorksheetMockPlan(
            "ws-001", "客户表", List.of(record1, record2), fieldMeta
        );

        MockDataCreator.Input input = new MockDataCreator.Input(
            List.of(plan), false, false, false
        );

        MockDataCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getTotalCreated());
        assertEquals(2, output.getResults().get("ws-001").getCreatedCount());
    }

    @Test
    void testExecuteWithEmptyRecords() throws Exception {
        MockDataCreator.WorksheetMockPlan plan = new MockDataCreator.WorksheetMockPlan(
            "ws-001", "客户表", List.of(), Map.of()
        );

        MockDataCreator.Input input = new MockDataCreator.Input(
            List.of(plan), false, false, false
        );

        MockDataCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(0, output.getTotalCreated());
    }

    @Test
    void testMockRecord() {
        Map<String, Object> values = Map.of(
            "f1", "文本值",
            "f2", 12345,
            "f3", true,
            "f4", List.of("选项1", "选项2")
        );

        MockDataCreator.MockRecord record = new MockDataCreator.MockRecord(
            "测试记录摘要", values
        );

        assertEquals("测试记录摘要", record.getRecordSummary());
        assertEquals(4, record.getValuesByFieldId().size());
        assertEquals("文本值", record.getValuesByFieldId().get("f1"));
        assertEquals(12345, record.getValuesByFieldId().get("f2"));
        assertEquals(true, record.getValuesByFieldId().get("f3"));
    }

    @Test
    void testFieldMeta() {
        MockDataCreator.FieldMeta meta = new MockDataCreator.FieldMeta(
            "f-001", "客户名称", "Text", 2
        );

        assertEquals("f-001", meta.getFieldId());
        assertEquals("客户名称", meta.getName());
        assertEquals("Text", meta.getType());
        assertEquals(2, meta.getControlType());
    }

    @Test
    void testWorksheetMockPlan() {
        List<MockDataCreator.MockRecord> records = List.of(
            new MockDataCreator.MockRecord("记录1", Map.of("f1", "值1"))
        );

        Map<String, MockDataCreator.FieldMeta> fieldMeta = Map.of(
            "f1", new MockDataCreator.FieldMeta("f1", "名称", "Text", 2)
        );

        MockDataCreator.WorksheetMockPlan plan = new MockDataCreator.WorksheetMockPlan(
            "ws-001", "客户表", records, fieldMeta
        );

        assertEquals("ws-001", plan.getWorksheetId());
        assertEquals("客户表", plan.getWorksheetName());
        assertEquals(1, plan.getRecords().size());
        assertEquals(1, plan.getFieldMetaMap().size());
    }

    @Test
    void testWorksheetResult() {
        MockDataCreator.WorksheetResult result = new MockDataCreator.WorksheetResult(
            "ws-001", "客户表", true, 5,
            List.of("row-001", "row-002", "row-003", "row-004", "row-005"),
            null
        );

        assertEquals("ws-001", result.getWorksheetId());
        assertEquals("客户表", result.getWorksheetName());
        assertTrue(result.isSuccess());
        assertEquals(5, result.getCreatedCount());
        assertEquals(5, result.getRowIds().size());
        assertNull(result.getErrorMessage());
    }

    @Test
    void testWorksheetResultWithError() {
        MockDataCreator.WorksheetResult result = new MockDataCreator.WorksheetResult(
            "ws-001", "客户表", false, 0,
            List.of(), "API调用失败"
        );

        assertFalse(result.isSuccess());
        assertEquals(0, result.getCreatedCount());
        assertEquals("API调用失败", result.getErrorMessage());
    }

    @Test
    void testInputCreation() {
        MockDataCreator.MockRecord record = new MockDataCreator.MockRecord(
            "测试记录", Map.of("f1", "值")
        );

        Map<String, MockDataCreator.FieldMeta> fieldMeta = Map.of(
            "f1", new MockDataCreator.FieldMeta("f1", "名称", "Text", 2)
        );

        MockDataCreator.WorksheetMockPlan plan = new MockDataCreator.WorksheetMockPlan(
            "ws-001", "客户表", List.of(record), fieldMeta
        );

        MockDataCreator.Input input = new MockDataCreator.Input(
            List.of(plan), false, true, true
        );

        assertFalse(input.isDryRun());
        assertTrue(input.isFailFast());
        assertTrue(input.isTriggerWorkflow());
        assertEquals(1, input.getPlans().size());
    }

    @Test
    void testOutput() {
        MockDataCreator.WorksheetResult result = new MockDataCreator.WorksheetResult(
            "ws-001", "客户表", true, 2,
            List.of("row-001", "row-002"), null
        );

        MockDataCreator.Output output = new MockDataCreator.Output(
            true, Map.of("ws-001", result), 2, null
        );

        assertTrue(output.isSuccess());
        assertEquals(2, output.getTotalCreated());
        assertEquals(1, output.getResults().size());
        assertNull(output.getErrorMessage());
    }

    @Test
    void testOutputWithError() {
        MockDataCreator.Output output = new MockDataCreator.Output(
            false, Map.of(), 0, "批量创建失败"
        );

        assertFalse(output.isSuccess());
        assertEquals(0, output.getTotalCreated());
        assertEquals("批量创建失败", output.getErrorMessage());
    }

    @Test
    void testMultipleWorksheets() throws Exception {
        apiClient.setNextResponse(mapper.readTree("""
            {
                "success": true,
                "data": {
                    "rowIds": ["row-001"]
                }
            }
            """));

        Map<String, MockDataCreator.FieldMeta> fieldMeta = Map.of(
            "f1", new MockDataCreator.FieldMeta("f1", "名称", "Text", 2)
        );

        MockDataCreator.WorksheetMockPlan plan1 = new MockDataCreator.WorksheetMockPlan(
            "ws-001", "客户表", List.of(new MockDataCreator.MockRecord("r1", Map.of("f1", "v1"))), fieldMeta
        );

        MockDataCreator.WorksheetMockPlan plan2 = new MockDataCreator.WorksheetMockPlan(
            "ws-002", "订单表", List.of(new MockDataCreator.MockRecord("r2", Map.of("f1", "v2"))), fieldMeta
        );

        MockDataCreator.Input input = new MockDataCreator.Input(
            List.of(plan1, plan2), false, false, false
        );

        MockDataCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getTotalCreated());
        assertEquals(2, output.getResults().size());
    }

    @Test
    void testLocationFieldValue() throws Exception {
        // Test that Location field values are properly converted to JSON
        Map<String, Object> locationValue = Map.of("address", "北京市朝阳区");

        MockDataCreator.MockRecord record = new MockDataCreator.MockRecord(
            "位置记录", Map.of("loc-field", locationValue)
        );

        assertTrue(record.getValuesByFieldId().get("loc-field") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> loc = (Map<String, String>) record.getValuesByFieldId().get("loc-field");
        assertEquals("北京市朝阳区", loc.get("address"));
    }

    @Test
    void testNullSafety() {
        MockDataCreator.MockRecord record = new MockDataCreator.MockRecord(
            null, null
        );

        assertNull(record.getRecordSummary());
        assertTrue(record.getValuesByFieldId().isEmpty());

        MockDataCreator.WorksheetMockPlan plan = new MockDataCreator.WorksheetMockPlan(
            "ws-001", "表", null, null
        );

        assertTrue(plan.getRecords().isEmpty());
        assertTrue(plan.getFieldMetaMap().isEmpty());

        MockDataCreator.Input input = new MockDataCreator.Input(
            null, false, false, false
        );

        assertTrue(input.getPlans().isEmpty());
    }
}
