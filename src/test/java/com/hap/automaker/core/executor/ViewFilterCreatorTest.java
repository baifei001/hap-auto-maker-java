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
 * ViewFilterCreator 测试类
 */
class ViewFilterCreatorTest {

    private MockHapApiClient apiClient;
    private ViewFilterCreator creator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        apiClient = new MockHapApiClient();
        creator = new ViewFilterCreator(apiClient, 2);
        mapper = new ObjectMapper();
    }

    @Test
    void testGetName() {
        assertEquals("ViewFilterCreator", creator.getName());
    }

    @Test
    void testExecuteDryRun() throws Exception {
        ViewFilterCreator.FilterDefinition filter = new ViewFilterCreator.FilterDefinition(
            "time_range", "创建时间", "f-date-001", "创建时间",
            "today", Map.of("type", "today")
        );

        ViewFilterCreator.ViewFilterPlan plan = new ViewFilterCreator.ViewFilterPlan(
            "view-001", "全部视图", "ws-001",
            List.of(filter)
        );

        ViewFilterCreator.Input input = new ViewFilterCreator.Input(
            List.of(plan), true, false
        );

        ViewFilterCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getAllFilters().size());
        assertEquals("dry-run-filter-id", output.getAllFilters().get(0).getFilterId());
    }

    @Test
    void testExecuteWithMockApi() throws Exception {
        JsonNode mockResponse = mapper.readTree("""
            {
                "success": true,
                "data": {
                    "filterId": "filter-123"
                }
            }
            """);
        apiClient.setNextResponse(mockResponse);

        ViewFilterCreator.FilterDefinition filter = new ViewFilterCreator.FilterDefinition(
            "select", "状态筛选", "f-select-001", "状态",
            "equals", Map.of("options", List.of("进行中", "已完成"))
        );

        ViewFilterCreator.ViewFilterPlan plan = new ViewFilterCreator.ViewFilterPlan(
            "view-001", "全部视图", "ws-001",
            List.of(filter)
        );

        ViewFilterCreator.Input input = new ViewFilterCreator.Input(
            List.of(plan), false, false
        );

        ViewFilterCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getAllFilters().size());
        assertEquals("filter-123", output.getAllFilters().get(0).getFilterId());
        assertTrue(output.getAllFilters().get(0).isSuccess());
    }

    @Test
    void testExecuteWithEmptyFilters() throws Exception {
        ViewFilterCreator.ViewFilterPlan plan = new ViewFilterCreator.ViewFilterPlan(
            "view-001", "全部视图", "ws-001",
            List.of()
        );

        ViewFilterCreator.Input input = new ViewFilterCreator.Input(
            List.of(plan), false, false
        );

        ViewFilterCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(0, output.getAllFilters().size());
    }

    @Test
    void testFilterDefinition() {
        ViewFilterCreator.FilterDefinition filter = new ViewFilterCreator.FilterDefinition(
            "text", "搜索", "f-text-001", "标题",
            "contains", Map.of("placeholder", "请输入关键词")
        );

        assertEquals("text", filter.getFilterType());
        assertEquals("搜索", filter.getFilterName());
        assertEquals("f-text-001", filter.getFieldId());
        assertEquals("标题", filter.getFieldName());
        assertEquals("contains", filter.getOperator());
        assertEquals("请输入关键词", filter.getConfig().get("placeholder"));
    }

    @Test
    void testViewFilterPlan() {
        ViewFilterCreator.FilterDefinition filter1 = new ViewFilterCreator.FilterDefinition(
            "time_range", "时间", "f-1", "时间", "today", Map.of()
        );
        ViewFilterCreator.FilterDefinition filter2 = new ViewFilterCreator.FilterDefinition(
            "people", "负责人", "f-2", "负责人", "currentUser", Map.of()
        );

        ViewFilterCreator.ViewFilterPlan plan = new ViewFilterCreator.ViewFilterPlan(
            "view-001", "我的视图", "ws-001",
            List.of(filter1, filter2)
        );

        assertEquals("view-001", plan.getViewId());
        assertEquals("我的视图", plan.getViewName());
        assertEquals("ws-001", plan.getWorksheetId());
        assertEquals(2, plan.getFilters().size());
    }

    @Test
    void testMultipleViews() throws Exception {
        JsonNode mockResponse = mapper.readTree("""
            {
                "success": true,
                "data": {
                    "filterId": "filter-multi"
                }
            }
            """);
        apiClient.setNextResponse(mockResponse);

        ViewFilterCreator.FilterDefinition filter1 = new ViewFilterCreator.FilterDefinition(
            "time_range", "时间", "f-1", "时间", "thisWeek", Map.of("type", "thisWeek")
        );
        ViewFilterCreator.FilterDefinition filter2 = new ViewFilterCreator.FilterDefinition(
            "number", "金额", "f-2", "金额", "range", Map.of("min", 0, "max", 10000)
        );

        ViewFilterCreator.ViewFilterPlan plan1 = new ViewFilterCreator.ViewFilterPlan(
            "view-001", "视图1", "ws-001", List.of(filter1)
        );
        ViewFilterCreator.ViewFilterPlan plan2 = new ViewFilterCreator.ViewFilterPlan(
            "view-002", "视图2", "ws-002", List.of(filter2)
        );

        ViewFilterCreator.Input input = new ViewFilterCreator.Input(
            List.of(plan1, plan2), true, false
        );

        ViewFilterCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getAllFilters().size());
        assertEquals(2, output.getViewFilters().size());
    }

    @Test
    void testFilterCreationDetail() {
        ViewFilterCreator.FilterCreationDetail detail = new ViewFilterCreator.FilterCreationDetail(
            "状态筛选", "filter-001", true, null, "select", "view-001"
        );

        assertEquals("状态筛选", detail.getFilterName());
        assertEquals("filter-001", detail.getFilterId());
        assertTrue(detail.isSuccess());
        assertNull(detail.getErrorMessage());
        assertEquals("select", detail.getFilterType());
        assertEquals("view-001", detail.getViewId());
    }

    @Test
    void testFilterCreationDetailWithError() {
        ViewFilterCreator.FilterCreationDetail detail = new ViewFilterCreator.FilterCreationDetail(
            "失败筛选", null, false, "API调用失败", "time_range", "view-002"
        );

        assertFalse(detail.isSuccess());
        assertEquals("API调用失败", detail.getErrorMessage());
        assertNull(detail.getFilterId());
    }

    @Test
    void testOutput() {
        ViewFilterCreator.FilterCreationDetail detail1 = new ViewFilterCreator.FilterCreationDetail(
            "筛选1", "f-1", true, null, "text", "view-001"
        );
        ViewFilterCreator.FilterCreationDetail detail2 = new ViewFilterCreator.FilterCreationDetail(
            "筛选2", "f-2", true, null, "number", "view-001"
        );

        Map<String, List<ViewFilterCreator.FilterCreationDetail>> viewFilters = Map.of(
            "view-001", List.of(detail1, detail2)
        );

        ViewFilterCreator.Output output = new ViewFilterCreator.Output(
            true, viewFilters, List.of(detail1, detail2), null
        );

        assertTrue(output.isSuccess());
        assertEquals(2, output.getAllFilters().size());
        assertEquals(1, output.getViewFilters().size());
        assertEquals(2, output.getViewFilters().get("view-001").size());
    }

    @Test
    void testInputCreation() {
        ViewFilterCreator.FilterDefinition filter = new ViewFilterCreator.FilterDefinition(
            "time_range", "创建时间", "f-1", "创建时间", "today", Map.of()
        );

        ViewFilterCreator.ViewFilterPlan plan = new ViewFilterCreator.ViewFilterPlan(
            "view-001", "视图", "ws-001", List.of(filter)
        );

        ViewFilterCreator.Input input = new ViewFilterCreator.Input(
            List.of(plan), false, true
        );

        assertFalse(input.isDryRun());
        assertTrue(input.isFailFast());
        assertEquals(1, input.getPlans().size());
    }
}
