package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.api.HapApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorksheetCreator 测试类
 */
class WorksheetCreatorTest {

    private MockHapApiClient apiClient;

    @BeforeEach
    void setUp() {
        apiClient = new MockHapApiClient();
    }

    @Test
    void testGetName() {
        WorksheetCreator creator = new WorksheetCreator(apiClient, 4);
        assertEquals("WorksheetCreator", creator.getName());
    }

    @Test
    void testInputCreation() {
        WorksheetCreator.WorksheetDefinition ws1 = new WorksheetCreator.WorksheetDefinition(
            "customers", "客户信息", 1,
            List.of(
                new WorksheetCreator.FieldDefinition("f1", "客户名称", 2, true, null),
                new WorksheetCreator.FieldDefinition("f2", "联系电话", 5, false, null)
            )
        );

        WorksheetCreator.WorksheetPlan plan = new WorksheetCreator.WorksheetPlan(
            List.of(ws1)
        );

        WorksheetCreator.Input input = new WorksheetCreator.Input(
            "app-123",
            Path.of("data/test/auth.json"),
            plan,
            false,
            false
        );

        assertEquals("app-123", input.getAppId());
        assertTrue(input.getAppAuthPath().toString().endsWith("auth.json"));
        assertEquals(1, input.getPlan().getWorksheets().size());
        assertFalse(input.isDryRun());
        assertFalse(input.isFailFast());
    }

    @Test
    void testOutputCreation() {
        Map<String, String> worksheetIdMap = new HashMap<>();
        worksheetIdMap.put("customers", "ws-abc-123");

        WorksheetCreator.WorksheetCreationDetail detail = new WorksheetCreator.WorksheetCreationDetail(
            "customers", "ws-abc-123", true, null, 5
        );

        WorksheetCreator.Output output = new WorksheetCreator.Output(
            true,
            worksheetIdMap,
            List.of(detail),
            null
        );

        assertTrue(output.isSuccess());
        assertEquals(1, output.getWorksheetIdMap().size());
        assertEquals("ws-abc-123", output.getWorksheetIdMap().get("customers"));
        assertEquals(1, output.getDetails().size());
        assertNull(output.getErrorMessage());
    }

    @Test
    void testWorksheetCreationDetail() {
        WorksheetCreator.WorksheetCreationDetail success = new WorksheetCreator.WorksheetCreationDetail(
            "customers", "ws-abc", true, null, 5
        );

        assertEquals("customers", success.getName());
        assertEquals("ws-abc", success.getWorksheetId());
        assertTrue(success.isSuccess());
        assertNull(success.getErrorMessage());
        assertEquals(5, success.getFieldCount());

        WorksheetCreator.WorksheetCreationDetail failure = new WorksheetCreator.WorksheetCreationDetail(
            "orders", null, false, "API 错误", 0
        );

        assertFalse(failure.isSuccess());
        assertEquals("API 错误", failure.getErrorMessage());
        assertEquals(0, failure.getFieldCount());
    }

    @Test
    void testFieldDefinition() {
        Map<String, Object> config = new HashMap<>();
        config.put("options", List.of("选项1", "选项2"));

        WorksheetCreator.FieldDefinition field = new WorksheetCreator.FieldDefinition(
            "f1", "客户类型", 9, true, config
        );

        assertEquals("f1", field.getControlId());
        assertEquals("客户类型", field.getControlName());
        assertEquals(9, field.getControlType()); // SingleSelect
        assertTrue(field.isRequired());
        assertEquals(config, field.getConfig());
    }

    @Test
    void testFieldDefinitionWithNullConfig() {
        WorksheetCreator.FieldDefinition field = new WorksheetCreator.FieldDefinition(
            "f1", "名称", 2, true, null
        );

        assertNotNull(field.getConfig());
        assertTrue(field.getConfig().isEmpty());
    }

    @Test
    void testWorksheetDefinition() {
        List<WorksheetCreator.FieldDefinition> fields = List.of(
            new WorksheetCreator.FieldDefinition("f1", "名称", 2, true, null),
            new WorksheetCreator.FieldDefinition("f2", "金额", 8, false, null)
        );

        WorksheetCreator.WorksheetDefinition ws = new WorksheetCreator.WorksheetDefinition(
            "orders", "订单表", 1, fields
        );

        assertEquals("orders", ws.getName());
        assertEquals("订单表", ws.getDisplayName());
        assertEquals(1, ws.getCreationOrder());
        assertEquals(2, ws.getFields().size());
    }

    @Test
    void testWorksheetPlan() {
        WorksheetCreator.WorksheetDefinition ws1 = new WorksheetCreator.WorksheetDefinition(
            "customers", "客户", 1, List.of()
        );
        WorksheetCreator.WorksheetDefinition ws2 = new WorksheetCreator.WorksheetDefinition(
            "orders", "订单", 2, List.of()
        );

        WorksheetCreator.WorksheetPlan plan = new WorksheetCreator.WorksheetPlan(List.of(ws1, ws2));

        assertEquals(2, plan.getWorksheets().size());
    }

    @Test
    void testSupportedFieldTypes() {
        // 这些类型应该可以直接创建
        int[] supportedTypes = {2, 6, 9, 10, 11, 14, 15, 16, 26, 28, 36};
        String[] typeNames = {"Text", "Number", "SingleSelect", "MultipleSelect", "Dropdown",
                              "Attachment", "Date", "DateTime", "Collaborator", "Rating", "Checkbox"};

        for (int i = 0; i < supportedTypes.length; i++) {
            WorksheetCreator.FieldDefinition field = new WorksheetCreator.FieldDefinition(
                "f" + i, "字段" + i, supportedTypes[i], false, null
            );
            assertEquals(supportedTypes[i], field.getControlType());
        }
    }

    @Test
    void testRelationField() {
        // 关联字段需要特殊处理
        Map<String, Object> config = new HashMap<>();
        config.put("relation_target", "target_ws_id");
        config.put("cardinality", "1-N");

        WorksheetCreator.FieldDefinition relationField = new WorksheetCreator.FieldDefinition(
            "rel1", "关联订单", 20, false, config
        );

        assertNotNull(relationField.getConfig());
        assertEquals("target_ws_id", relationField.getConfig().get("relation_target"));
        assertEquals("1-N", relationField.getConfig().get("cardinality"));
    }

    @Test
    void testIsRollbackable() {
        WorksheetCreator creator = new WorksheetCreator(apiClient, 4);
        assertTrue(creator.isRollbackable());
    }

    @Test
    void testExecuteWithDryRun() throws Exception {
        // 创建 mock 返回数据
        apiClient.setMockResponse(JsonNodeCreationHelper.createWorksheetResponse("ws-123"));

        WorksheetCreator creator = new WorksheetCreator(apiClient, 4);

        WorksheetCreator.WorksheetDefinition ws = new WorksheetCreator.WorksheetDefinition(
            "test", "测试表", 1,
            List.of(
                new WorksheetCreator.FieldDefinition("f1", "名称", 2, true, null)
            )
        );

        WorksheetCreator.Input input = new WorksheetCreator.Input(
            "app-123", null,
            new WorksheetCreator.WorksheetPlan(List.of(ws)),
            false, true // failFast = true
        );

        WorksheetCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getWorksheetIdMap().size());
        assertEquals("ws-123", output.getWorksheetIdMap().get("test"));
    }

    @Test
    void testExecuteWithMultipleWorksheets() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createWorksheetResponse("ws-multi"));

        WorksheetCreator creator = new WorksheetCreator(apiClient, 4);

        WorksheetCreator.WorksheetDefinition ws1 = new WorksheetCreator.WorksheetDefinition(
            "customers", "客户", 1,
            List.of(new WorksheetCreator.FieldDefinition("f1", "名称", 2, true, null))
        );

        WorksheetCreator.WorksheetDefinition ws2 = new WorksheetCreator.WorksheetDefinition(
            "orders", "订单", 2,
            List.of(new WorksheetCreator.FieldDefinition("f2", "金额", 6, false, null))
        );

        WorksheetCreator.Input input = new WorksheetCreator.Input(
            "app-123", null,
            new WorksheetCreator.WorksheetPlan(List.of(ws1, ws2)),
            false, false
        );

        WorksheetCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
    }

    @Test
    void testExecuteWithDeferredFields() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createWorksheetResponse("ws-deferred"));

        WorksheetCreator creator = new WorksheetCreator(apiClient, 4);

        // 富文本(41)和公式(31)是延迟字段
        WorksheetCreator.WorksheetDefinition ws = new WorksheetCreator.WorksheetDefinition(
            "test", "测试表", 1,
            List.of(
                new WorksheetCreator.FieldDefinition("f1", "名称", 2, true, null),      // normal
                new WorksheetCreator.FieldDefinition("f2", "描述", 41, false, null),    // deferred (RichText)
                new WorksheetCreator.FieldDefinition("f3", "计算", 31, false, null)     // deferred (Formula)
            )
        );

        WorksheetCreator.Input input = new WorksheetCreator.Input(
            "app-123", null,
            new WorksheetCreator.WorksheetPlan(List.of(ws)),
            false, false
        );

        WorksheetCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
    }

    // ========== Mock 和辅助类 ==========

    /**
     * Mock HapApiClient for testing
     */
    private static class MockHapApiClient extends HapApiClient {
        private JsonNode mockResponse;

        public void setMockResponse(JsonNode response) {
            this.mockResponse = response;
        }

        @Override
        public JsonNode createWorksheetV3(String name, JsonNode fields) {
            return mockResponse != null ? mockResponse : JsonNodeCreationHelper.createWorksheetResponse("mock-id");
        }

        @Override
        public JsonNode editWorksheetV3(String worksheetId, JsonNode fields) {
            return mockResponse != null ? mockResponse : JsonNodeCreationHelper.createSuccessResponse();
        }
    }

    /**
     * Helper for creating JsonNode test data
     */
    private static class JsonNodeCreationHelper {
        static JsonNode createWorksheetResponse(String worksheetId) {
            return com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                .put("success", true)
                .set("data", com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                    .put("id", worksheetId));
        }

        static JsonNode createSuccessResponse() {
            return com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                .put("success", true);
        }
    }
}
