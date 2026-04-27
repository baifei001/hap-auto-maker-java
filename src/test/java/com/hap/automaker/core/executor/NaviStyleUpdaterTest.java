package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.api.HapApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NaviStyleUpdater 测试类
 */
class NaviStyleUpdaterTest {

    private MockHapApiClient apiClient;

    @BeforeEach
    void setUp() {
        apiClient = new MockHapApiClient();
    }

    @Test
    void testGetName() {
        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);
        assertEquals("NaviStyleUpdater", updater.getName());
    }

    @Test
    void testInputCreation() {
        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 5, null, false, false
        );

        assertEquals("app-abc", input.getAppId());
        assertEquals(5, input.getSectionCount());
        assertNull(input.getForceNaviStyle());
        assertFalse(input.isDryRun());
        assertFalse(input.isFailFast());
    }

    @Test
    void testInputWithForcedStyle() {
        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 2, 0, false, false
        );

        assertEquals(0, input.getForceNaviStyle());
        assertEquals(2, input.getSectionCount());
    }

    @Test
    void testInputWithZeroSectionCount() {
        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 0, null, false, false
        );

        assertEquals(0, input.getSectionCount());
    }

    @Test
    void testOutputCreation() {
        NaviStyleUpdater.Output output = new NaviStyleUpdater.Output(
            true,
            1,
            "左侧导航（分组数=2 <= 3）",
            null
        );

        assertTrue(output.isSuccess());
        assertEquals(1, output.getPcNaviStyle());
        assertEquals("左侧导航（分组数=2 <= 3）", output.getStyleDescription());
        assertNull(output.getErrorMessage());
    }

    @Test
    void testOutputCreationWithFailure() {
        NaviStyleUpdater.Output output = new NaviStyleUpdater.Output(
            false,
            0,
            "",
            "API Error"
        );

        assertFalse(output.isSuccess());
        assertEquals(0, output.getPcNaviStyle());
        assertEquals("API Error", output.getErrorMessage());
    }

    @Test
    void testExecuteWithLeftNaviStyle() throws Exception {
        // 分组数 <= 3 应使用左侧导航 (pcNaviStyle=1)
        apiClient.setMockResponse(JsonNodeCreationHelper.createSuccessResponse());

        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);

        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 2, null, false, false
        );

        NaviStyleUpdater.Output output = updater.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getPcNaviStyle());
        assertTrue(output.getStyleDescription().contains("左侧导航"));
    }

    @Test
    void testExecuteWithTopNaviStyle() throws Exception {
        // 分组数 > 3 应使用经典顶部导航 (pcNaviStyle=0)
        apiClient.setMockResponse(JsonNodeCreationHelper.createSuccessResponse());

        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);

        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 5, null, false, false
        );

        NaviStyleUpdater.Output output = updater.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(0, output.getPcNaviStyle());
        assertTrue(output.getStyleDescription().contains("经典顶部导航"));
    }

    @Test
    void testExecuteWithExactThreshold() throws Exception {
        // 分组数 = 3 应使用左侧导航
        apiClient.setMockResponse(JsonNodeCreationHelper.createSuccessResponse());

        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);

        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 3, null, false, false
        );

        NaviStyleUpdater.Output output = updater.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getPcNaviStyle());
    }

    @Test
    void testExecuteWithForcedLeftStyle() throws Exception {
        // 强制使用左侧导航
        apiClient.setMockResponse(JsonNodeCreationHelper.createSuccessResponse());

        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);

        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 10, 1, false, false // 即使分组数>3，也强制使用左侧导航
        );

        NaviStyleUpdater.Output output = updater.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getPcNaviStyle());
        assertTrue(output.getStyleDescription().contains("左侧导航（强制）"));
    }

    @Test
    void testExecuteWithForcedTopStyle() throws Exception {
        // 强制使用顶部导航
        apiClient.setMockResponse(JsonNodeCreationHelper.createSuccessResponse());

        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);

        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 2, 0, false, false // 即使分组数<=3，也强制使用顶部导航
        );

        NaviStyleUpdater.Output output = updater.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(0, output.getPcNaviStyle());
        assertTrue(output.getStyleDescription().contains("经典顶部导航（强制）"));
    }

    @Test
    void testExecuteDryRun() throws Exception {
        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);

        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 4, null, true, false // dryRun = true
        );

        NaviStyleUpdater.Output output = updater.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(0, output.getPcNaviStyle()); // 4 > 3, 应为顶部导航
        assertTrue(output.getStyleDescription().contains("经典顶部导航"));
    }

    @Test
    void testExecuteWithNoSections() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createSuccessResponse());

        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);

        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 0, null, false, false
        );

        NaviStyleUpdater.Output output = updater.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getPcNaviStyle()); // 0 <= 3, 应为左侧导航
    }

    @Test
    void testNaviStyleThresholdEdgeCases() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createSuccessResponse());

        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);

        // 测试阈值边界
        int[] sectionCounts = {1, 2, 3, 4, 5, 10};
        int[] expectedStyles = {1, 1, 1, 0, 0, 0}; // <=3 左侧(1), >3 顶部(0)

        for (int i = 0; i < sectionCounts.length; i++) {
            NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
                "app-abc", sectionCounts[i], null, false, false
            );

            NaviStyleUpdater.Output output = updater.execute(input);

            assertEquals(expectedStyles[i], output.getPcNaviStyle(),
                "Section count " + sectionCounts[i] + " should have navi style " + expectedStyles[i]);
        }
    }

    @Test
    void testStyleDescriptionContainsSectionCount() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createSuccessResponse());

        NaviStyleUpdater updater = new NaviStyleUpdater(apiClient);

        NaviStyleUpdater.Input input = new NaviStyleUpdater.Input(
            "app-abc", 5, null, false, false
        );

        NaviStyleUpdater.Output output = updater.execute(input);

        assertTrue(output.getStyleDescription().contains("分组数=5"));
        assertTrue(output.getStyleDescription().contains("3"));
    }

    // ========== Mock 和辅助类 ==========

    private static class MockHapApiClient extends HapApiClient {
        private JsonNode mockResponse;

        public void setMockResponse(JsonNode response) {
            this.mockResponse = response;
        }

        @Override
        public JsonNode updateNavStyle(String appId, int pcNaviStyle) {
            return mockResponse != null ? mockResponse : JsonNodeCreationHelper.createSuccessResponse();
        }
    }

    private static class JsonNodeCreationHelper {
        static JsonNode createSuccessResponse() {
            return com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                .put("success", true)
                .put("error_code", 1);
        }
    }
}
