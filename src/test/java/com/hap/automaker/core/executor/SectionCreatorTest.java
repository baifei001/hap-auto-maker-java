package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.api.HapApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SectionCreator 测试类
 */
class SectionCreatorTest {

    private MockHapApiClient apiClient;

    @BeforeEach
    void setUp() {
        apiClient = new MockHapApiClient();
    }

    @Test
    void testGetName() {
        SectionCreator creator = new SectionCreator(apiClient, 4);
        assertEquals("SectionCreator", creator.getName());
    }

    @Test
    void testInputCreation() {
        SectionCreator.SectionDefinition section1 = new SectionCreator.SectionDefinition(
            "仪表盘", List.of()
        );
        SectionCreator.SectionDefinition section2 = new SectionCreator.SectionDefinition(
            "业务数据", List.of("客户表", "订单表")
        );

        Map<String, String> worksheetIdMap = new HashMap<>();
        worksheetIdMap.put("客户表", "ws-customer-123");
        worksheetIdMap.put("订单表", "ws-order-456");

        SectionCreator.Input input = new SectionCreator.Input(
            "app-abc",
            List.of(section1, section2),
            worksheetIdMap,
            false,
            false
        );

        assertEquals("app-abc", input.getAppId());
        assertEquals(2, input.getSections().size());
        assertEquals("仪表盘", input.getSections().get(0).getName());
        assertTrue(input.getSections().get(0).getWorksheets().isEmpty());
        assertEquals(2, input.getSections().get(1).getWorksheets().size());
        assertEquals("客户表", input.getSections().get(1).getWorksheets().get(0));
        assertEquals(2, input.getWorksheetIdMap().size());
        assertFalse(input.isDryRun());
        assertFalse(input.isFailFast());
    }

    @Test
    void testSectionDefinition() {
        SectionCreator.SectionDefinition section = new SectionCreator.SectionDefinition(
            "客户管理", List.of("客户表", "联系人表")
        );

        assertEquals("客户管理", section.getName());
        assertEquals(2, section.getWorksheets().size());
        assertEquals("客户表", section.getWorksheets().get(0));
        assertEquals("联系人表", section.getWorksheets().get(1));
    }

    @Test
    void testSectionDefinitionWithEmptyList() {
        SectionCreator.SectionDefinition section = new SectionCreator.SectionDefinition(
            "空分组", null
        );

        assertEquals("空分组", section.getName());
        assertTrue(section.getWorksheets().isEmpty());
    }

    @Test
    void testOutputCreation() {
        Map<String, String> sectionIdMap = new HashMap<>();
        sectionIdMap.put("仪表盘", "sec-dashboard-123");
        sectionIdMap.put("业务数据", "sec-business-456");

        SectionCreator.SectionCreationDetail detail1 = new SectionCreator.SectionCreationDetail(
            "仪表盘", "sec-dashboard-123", true, null, 0
        );
        SectionCreator.SectionCreationDetail detail2 = new SectionCreator.SectionCreationDetail(
            "业务数据", "sec-business-456", true, null, 2
        );

        SectionCreator.Output output = new SectionCreator.Output(
            true,
            sectionIdMap,
            List.of(detail1, detail2),
            null
        );

        assertTrue(output.isSuccess());
        assertEquals(2, output.getSectionIdMap().size());
        assertEquals("sec-dashboard-123", output.getSectionIdMap().get("仪表盘"));
        assertEquals(2, output.getDetails().size());
        assertNull(output.getErrorMessage());

        SectionCreator.SectionCreationDetail retrieved = output.getDetails().get(1);
        assertEquals("业务数据", retrieved.getName());
        assertEquals("sec-business-456", retrieved.getSectionId());
        assertTrue(retrieved.isSuccess());
        assertEquals(2, retrieved.getWorksheetCount());
    }

    @Test
    void testSectionCreationDetailFailure() {
        SectionCreator.SectionCreationDetail detail = new SectionCreator.SectionCreationDetail(
            "失败分组", null, false, "API Error", 0
        );

        assertFalse(detail.isSuccess());
        assertEquals("API Error", detail.getErrorMessage());
        assertNull(detail.getSectionId());
    }

    @Test
    void testExecuteWithSimpleSections() throws Exception {
        apiClient.setMockSectionResponse(JsonNodeCreationHelper.createSectionResponse("sec-123"));
        apiClient.setMockMoveResponse(JsonNodeCreationHelper.createSuccessResponse());

        SectionCreator creator = new SectionCreator(apiClient, 4);

        SectionCreator.SectionDefinition section1 = new SectionCreator.SectionDefinition(
            "仪表盘", List.of()
        );
        SectionCreator.SectionDefinition section2 = new SectionCreator.SectionDefinition(
            "客户管理", List.of("客户表")
        );

        Map<String, String> worksheetIdMap = Map.of("客户表", "ws-customer-456");

        SectionCreator.Input input = new SectionCreator.Input(
            "app-abc",
            List.of(section1, section2),
            worksheetIdMap,
            false,
            false
        );

        SectionCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getDetails().size());
    }

    @Test
    void testExecuteDryRun() throws Exception {
        SectionCreator creator = new SectionCreator(apiClient, 4);

        SectionCreator.SectionDefinition section = new SectionCreator.SectionDefinition(
            "测试分组", List.of("表1")
        );

        Map<String, String> worksheetIdMap = Map.of("表1", "ws-1");

        SectionCreator.Input input = new SectionCreator.Input(
            "app-abc",
            List.of(section),
            worksheetIdMap,
            true, // dryRun = true
            false
        );

        SectionCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getDetails().size());
    }

    @Test
    void testExecuteWithMultipleWorksheets() throws Exception {
        apiClient.setMockSectionResponse(JsonNodeCreationHelper.createSectionResponse("sec-multi"));
        apiClient.setMockMoveResponse(JsonNodeCreationHelper.createSuccessResponse());

        SectionCreator creator = new SectionCreator(apiClient, 4);

        SectionCreator.SectionDefinition section = new SectionCreator.SectionDefinition(
            "业务数据",
            List.of("客户表", "订单表", "产品表", "发货表")
        );

        Map<String, String> worksheetIdMap = new HashMap<>();
        worksheetIdMap.put("客户表", "ws-1");
        worksheetIdMap.put("订单表", "ws-2");
        worksheetIdMap.put("产品表", "ws-3");
        worksheetIdMap.put("发货表", "ws-4");

        SectionCreator.Input input = new SectionCreator.Input(
            "app-abc",
            List.of(section),
            worksheetIdMap,
            false,
            false
        );

        SectionCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
    }

    @Test
    void testExecuteWithMultipleSections() throws Exception {
        apiClient.setMockSectionResponse(JsonNodeCreationHelper.createSectionResponse("sec-test"));
        apiClient.setMockMoveResponse(JsonNodeCreationHelper.createSuccessResponse());

        SectionCreator creator = new SectionCreator(apiClient, 4);

        SectionCreator.SectionDefinition section1 = new SectionCreator.SectionDefinition(
            "仪表盘", List.of()
        );
        SectionCreator.SectionDefinition section2 = new SectionCreator.SectionDefinition(
            "基础数据", List.of("客户表", "产品表")
        );
        SectionCreator.SectionDefinition section3 = new SectionCreator.SectionDefinition(
            "业务数据", List.of("订单表", "发货表")
        );
        SectionCreator.SectionDefinition section4 = new SectionCreator.SectionDefinition(
            "报表", List.of("统计表")
        );

        Map<String, String> worksheetIdMap = new HashMap<>();
        worksheetIdMap.put("客户表", "ws-1");
        worksheetIdMap.put("产品表", "ws-2");
        worksheetIdMap.put("订单表", "ws-3");
        worksheetIdMap.put("发货表", "ws-4");
        worksheetIdMap.put("统计表", "ws-5");

        SectionCreator.Input input = new SectionCreator.Input(
            "app-abc",
            List.of(section1, section2, section3, section4),
            worksheetIdMap,
            false,
            false
        );

        SectionCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(4, output.getDetails().size());
    }

    @Test
    void testExecuteWithMissingWorksheet() throws Exception {
        apiClient.setMockSectionResponse(JsonNodeCreationHelper.createSectionResponse("sec-test"));
        apiClient.setMockMoveResponse(JsonNodeCreationHelper.createSuccessResponse());

        SectionCreator creator = new SectionCreator(apiClient, 4);

        // 分组中引用的工作表不在 worksheetIdMap 中
        SectionCreator.SectionDefinition section = new SectionCreator.SectionDefinition(
            "测试分组",
            List.of("存在的表", "不存在的表")
        );

        Map<String, String> worksheetIdMap = Map.of("存在的表", "ws-1");

        SectionCreator.Input input = new SectionCreator.Input(
            "app-abc",
            List.of(section),
            worksheetIdMap,
            false,
            false
        );

        // 应该跳过不存在的表，继续执行
        SectionCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
    }

    @Test
    void testSectionCreationDetail() {
        SectionCreator.SectionCreationDetail detail = new SectionCreator.SectionCreationDetail(
            "测试分组", "sec-123", true, null, 5
        );

        assertEquals("测试分组", detail.getName());
        assertEquals("sec-123", detail.getSectionId());
        assertTrue(detail.isSuccess());
        assertNull(detail.getErrorMessage());
        assertEquals(5, detail.getWorksheetCount());
    }

    // ========== Mock 和辅助类 ==========

    private static class MockHapApiClient extends HapApiClient {
        private JsonNode mockSectionResponse;
        private JsonNode mockMoveResponse;

        public void setMockSectionResponse(JsonNode response) {
            this.mockSectionResponse = response;
        }

        public void setMockMoveResponse(JsonNode response) {
            this.mockMoveResponse = response;
        }

        @Override
        public JsonNode createSection(String appId, String name, int row) {
            return mockSectionResponse != null ? mockSectionResponse : JsonNodeCreationHelper.createSectionResponse("mock-sec-id");
        }

        @Override
        public JsonNode moveWorksheetToSection(String appId, String sectionId, String worksheetId, int row) {
            return mockMoveResponse != null ? mockMoveResponse : JsonNodeCreationHelper.createSuccessResponse();
        }
    }

    private static class JsonNodeCreationHelper {
        static JsonNode createSectionResponse(String sectionId) {
            return com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                .put("success", true)
                .set("data", com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                    .put("id", sectionId));
        }

        static JsonNode createSuccessResponse() {
            return com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                .put("success", true);
        }
    }
}
