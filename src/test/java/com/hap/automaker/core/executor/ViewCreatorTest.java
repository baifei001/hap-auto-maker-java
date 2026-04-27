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
 * ViewCreator 测试类
 */
class ViewCreatorTest {

    private MockHapApiClient apiClient;

    @BeforeEach
    void setUp() {
        apiClient = new MockHapApiClient();
    }

    @Test
    void testGetName() {
        ViewCreator creator = new ViewCreator(apiClient, 4);
        assertEquals("ViewCreator", creator.getName());
    }

    @Test
    void testInputCreation() {
        ViewCreator.ViewDefinition view1 = new ViewCreator.ViewDefinition(
            "全部订单", 0, List.of("field1", "field2"),
            null, null, null
        );

        ViewCreator.WorksheetViewPlan wsPlan = new ViewCreator.WorksheetViewPlan(
            "ws-123", "订单表", List.of(view1)
        );

        ViewCreator.Input input = new ViewCreator.Input(
            List.of(wsPlan), "project-abc", false, false
        );

        assertEquals(1, input.getPlans().size());
        assertEquals("project-abc", input.getProjectId());
        assertFalse(input.isDryRun());
        assertFalse(input.isFailFast());
    }

    @Test
    void testViewDefinition() {
        Map<String, Object> filters = new HashMap<>();
        filters.put("status", "active");

        Map<String, Object> sorts = new HashMap<>();
        sorts.put("field1", "desc");

        Map<String, Object> advanced = new HashMap<>();
        advanced.put("coverType", 1);

        ViewCreator.ViewDefinition view = new ViewCreator.ViewDefinition(
            "待办任务", 0,
            List.of("field1", "field2", "field3"),
            filters, sorts, advanced
        );

        assertEquals("待办任务", view.getName());
        assertEquals(0, view.getViewType()); // 表格视图
        assertEquals(3, view.getControlIds().size());
        assertEquals("field1", view.getControlIds().get(0));
        assertEquals(filters, view.getFilters());
        assertEquals(sorts, view.getSorts());
        assertEquals(advanced, view.getAdvancedSettings());
    }

    @Test
    void testWorksheetViewPlan() {
        ViewCreator.ViewDefinition view1 = new ViewCreator.ViewDefinition(
            "视图1", 0, List.of(), null, null, null
        );
        ViewCreator.ViewDefinition view2 = new ViewCreator.ViewDefinition(
            "视图2", 1, List.of(), null, null, null
        );

        ViewCreator.WorksheetViewPlan plan = new ViewCreator.WorksheetViewPlan(
            "ws-123", "测试表", List.of(view1, view2)
        );

        assertEquals("ws-123", plan.getWorksheetId());
        assertEquals("测试表", plan.getWorksheetName());
        assertEquals(2, plan.getViews().size());
    }

    @Test
    void testOutputCreation() {
        Map<String, List<ViewCreator.ViewCreationDetail>> wsViews = new HashMap<>();

        ViewCreator.ViewCreationDetail detail = new ViewCreator.ViewCreationDetail(
            "测试视图", "view-123", true, null, 0, "ws-123"
        );

        wsViews.put("ws-123", List.of(detail));

        ViewCreator.Output output = new ViewCreator.Output(
            true, wsViews, List.of(detail), null
        );

        assertTrue(output.isSuccess());
        assertEquals(1, output.getWorksheetViews().size());
        assertEquals(1, output.getAllViews().size());
        assertNull(output.getErrorMessage());

        ViewCreator.ViewCreationDetail retrieved = output.getAllViews().get(0);
        assertEquals("测试视图", retrieved.getName());
        assertEquals("view-123", retrieved.getViewId());
        assertTrue(retrieved.isSuccess());
        assertEquals(0, retrieved.getViewType());
        assertEquals("ws-123", retrieved.getWorksheetId());
    }

    @Test
    void testViewCreationDetailFailure() {
        ViewCreator.ViewCreationDetail detail = new ViewCreator.ViewCreationDetail(
            "失败视图", null, false, "API Error", 0, "ws-123"
        );

        assertFalse(detail.isSuccess());
        assertEquals("API Error", detail.getErrorMessage());
        assertNull(detail.getViewId());
    }

    @Test
    void testExecuteWithTableView() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createViewResponse("view-table-123"));

        ViewCreator creator = new ViewCreator(apiClient, 4);

        ViewCreator.ViewDefinition view = new ViewCreator.ViewDefinition(
            "表格视图", 0, List.of("field1", "field2"),
            null, null, null
        );

        ViewCreator.WorksheetViewPlan plan = new ViewCreator.WorksheetViewPlan(
            "ws-123", "测试表", List.of(view)
        );

        ViewCreator.Input input = new ViewCreator.Input(
            List.of(plan), "project-abc", false, false
        );

        ViewCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getAllViews().size());
        assertEquals("view-table-123", output.getAllViews().get(0).getViewId());
    }

    @Test
    void testExecuteWithKanbanView() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createViewResponse("view-kanban-123"));

        ViewCreator creator = new ViewCreator(apiClient, 4);

        Map<String, Object> advanced = new HashMap<>();
        advanced.put("viewControl", "select-field-123");

        ViewCreator.ViewDefinition view = new ViewCreator.ViewDefinition(
            "看板视图", 1, List.of("field1"),
            null, null, advanced
        );

        ViewCreator.WorksheetViewPlan plan = new ViewCreator.WorksheetViewPlan(
            "ws-123", "测试表", List.of(view)
        );

        ViewCreator.Input input = new ViewCreator.Input(
            List.of(plan), "project-abc", false, false
        );

        ViewCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getAllViews().size());
    }

    @Test
    void testExecuteWithCalendarView() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createViewResponse("view-calendar-123"));

        ViewCreator creator = new ViewCreator(apiClient, 4);

        Map<String, Object> advanced = new HashMap<>();
        advanced.put("calendarcids", "[\"date-field-123\"]");

        ViewCreator.ViewDefinition view = new ViewCreator.ViewDefinition(
            "日历视图", 4, List.of("field1"), null, null, advanced
        );

        ViewCreator.WorksheetViewPlan plan = new ViewCreator.WorksheetViewPlan(
            "ws-123", "测试表", List.of(view)
        );

        ViewCreator.Input input = new ViewCreator.Input(
            List.of(plan), "project-abc", false, false
        );

        ViewCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
    }

    @Test
    void testExecuteWithMultipleViews() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createViewResponse("view-multi"));

        ViewCreator creator = new ViewCreator(apiClient, 4);

        ViewCreator.ViewDefinition tableView = new ViewCreator.ViewDefinition(
            "表格视图", 0, List.of(), null, null, null
        );
        ViewCreator.ViewDefinition kanbanView = new ViewCreator.ViewDefinition(
            "看板视图", 1, List.of(), null, null, null
        );

        ViewCreator.WorksheetViewPlan plan = new ViewCreator.WorksheetViewPlan(
            "ws-123", "测试表", List.of(tableView, kanbanView)
        );

        ViewCreator.Input input = new ViewCreator.Input(
            List.of(plan), "project-abc", false, false
        );

        ViewCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getAllViews().size());
    }

    @Test
    void testExecuteWithMultipleWorksheets() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createViewResponse("view-ws"));

        ViewCreator creator = new ViewCreator(apiClient, 4);

        ViewCreator.WorksheetViewPlan plan1 = new ViewCreator.WorksheetViewPlan(
            "ws-1", "表1", List.of(new ViewCreator.ViewDefinition("视1", 0, List.of(), null, null, null))
        );

        ViewCreator.WorksheetViewPlan plan2 = new ViewCreator.WorksheetViewPlan(
            "ws-2", "表2", List.of(new ViewCreator.ViewDefinition("视2", 0, List.of(), null, null, null))
        );

        ViewCreator.Input input = new ViewCreator.Input(
            List.of(plan1, plan2), "project-abc", false, false
        );

        ViewCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getAllViews().size());
        assertEquals(2, output.getWorksheetViews().size());
    }

    @Test
    void testExecuteDryRun() throws Exception {
        ViewCreator creator = new ViewCreator(apiClient, 4);

        ViewCreator.ViewDefinition view = new ViewCreator.ViewDefinition(
            "测试视图", 0, List.of(), null, null, null
        );

        ViewCreator.WorksheetViewPlan plan = new ViewCreator.WorksheetViewPlan(
            "ws-123", "测试表", List.of(view)
        );

        ViewCreator.Input input = new ViewCreator.Input(
            List.of(plan), "project-abc", true, false // dryRun = true
        );

        ViewCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getAllViews().size());
    }

    @Test
    void testViewTypes() {
        // 测试各种视图类型
        int[] viewTypes = {0, 1, 2, 3, 4, 5, 7, 9};
        // 0=表格, 1=看板, 2=层级, 3=画廊, 4=日历, 5=甘特图, 7=地图, 9=资源

        for (int type : viewTypes) {
            ViewCreator.ViewDefinition view = new ViewCreator.ViewDefinition(
                "视图" + type, type, List.of(), null, null, null
            );
            assertEquals(type, view.getViewType());
        }
    }

    // ========== Mock 和辅助类 ==========

    private static class MockHapApiClient extends HapApiClient {
        private JsonNode mockResponse;

        public void setMockResponse(JsonNode response) {
            this.mockResponse = response;
        }

        @Override
        public JsonNode saveWorksheetView(String worksheetId, JsonNode viewConfig) {
            return mockResponse != null ? mockResponse : JsonNodeCreationHelper.createViewResponse("mock-view-id");
        }
    }

    private static class JsonNodeCreationHelper {
        static JsonNode createViewResponse(String viewId) {
            return com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                .put("success", true)
                .set("data", com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                    .put("viewId", viewId));
        }
    }
}
