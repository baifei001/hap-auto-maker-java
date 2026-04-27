package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hap.automaker.api.HapApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageChartCreator 测试类
 */
class PageChartCreatorTest {

    private MockHapApiClient mockApiClient;
    private PageChartCreator creator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mockApiClient = new MockHapApiClient();
        creator = new PageChartCreator(mockApiClient, 2);
        mapper = new ObjectMapper();
    }

    @Test
    void testGetName() {
        assertEquals("PageChartCreator", creator.getName());
    }

    @Test
    void testExecuteDryRun() throws Exception {
        PageChartCreator.PageChartDefinition chart1 = new PageChartCreator.PageChartDefinition(
            "订单统计", 3, "ws-001", "view-001",
            "状态", 9, "记录数量", 10000000, "count",
            1, 1
        );

        PageChartCreator.PageChartPlan plan = new PageChartCreator.PageChartPlan(
            "page-001", "仪表盘", "dashboard", "数据概览",
            List.of(chart1), 2
        );

        PageChartCreator.Input input = new PageChartCreator.Input(
            "app-001", List.of(plan), true, false
        );

        PageChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getAllCharts().size());
        assertTrue(output.getAllCharts().get(0).isSuccess());
    }

    @Test
    void testExecuteWithEmptyCharts() throws Exception {
        PageChartCreator.PageChartPlan plan = new PageChartCreator.PageChartPlan(
            "page-001", "仪表盘", "dashboard", "数据概览",
            List.of(), 2
        );

        PageChartCreator.Input input = new PageChartCreator.Input(
            "app-001", List.of(plan), false, false
        );

        PageChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertTrue(output.getAllCharts().isEmpty());
    }

    @Test
    void testPageChartPlan() {
        PageChartCreator.PageChartDefinition chart = new PageChartCreator.PageChartDefinition(
            "测试图表", 1, "ws-001", "view-001",
            "时间", 7, "数量", 6, "sum", 2, 2
        );

        PageChartCreator.PageChartPlan plan = new PageChartCreator.PageChartPlan(
            "page-123", "分析页", "analytics", "数据分析",
            List.of(chart), 3
        );

        assertEquals("page-123", plan.getPageId());
        assertEquals("分析页", plan.getPageName());
        assertEquals("analytics", plan.getPageType());
        assertEquals("数据分析", plan.getPageDescription());
        assertEquals(1, plan.getCharts().size());
        assertEquals(3, plan.getColumns());
    }

    @Test
    void testPageChartDefinition() {
        PageChartCreator.PageChartDefinition chart = new PageChartCreator.PageChartDefinition(
            "销售趋势", 1, "ws-001", "view-001",
            "日期", 7, "销售额", 6, "sum",
            2, 1
        );

        assertEquals("销售趋势", chart.getName());
        assertEquals(1, chart.getReportType());
        assertEquals("ws-001", chart.getWorksheetId());
        assertEquals("view-001", chart.getViewId());
        assertEquals("日期", chart.getXField());
        assertEquals(7, chart.getXFieldType());
        assertEquals("销售额", chart.getYField());
        assertEquals(6, chart.getYFieldType());
        assertEquals("sum", chart.getYAggregate());
        assertEquals(2, chart.getWidth());
        assertEquals(1, chart.getHeight());
    }

    @Test
    void testOutput() {
        PageChartCreator.PageChartDetail detail = new PageChartCreator.PageChartDetail(
            "测试图表", "chart-001", true, null,
            3, "page-001", "仪表盘"
        );

        java.util.Map<String, List<PageChartCreator.PageChartDetail>> pageCharts = java.util.Map.of(
            "page-001", List.of(detail)
        );

        PageChartCreator.Output output = new PageChartCreator.Output(
            true, pageCharts, List.of(detail), null
        );

        assertTrue(output.isSuccess());
        assertEquals(1, output.getAllCharts().size());
        assertEquals(1, output.getPageCharts().size());
        assertNull(output.getErrorMessage());
    }

    @Test
    void testPageChartDetail() {
        PageChartCreator.PageChartDetail detail = new PageChartCreator.PageChartDetail(
            "订单统计", "chart-123", true, null,
            3, "page-001", "仪表盘"
        );

        assertEquals("订单统计", detail.getChartName());
        assertEquals("chart-123", detail.getChartId());
        assertTrue(detail.isSuccess());
        assertNull(detail.getErrorMessage());
        assertEquals(3, detail.getReportType());
        assertEquals("page-001", detail.getPageId());
        assertEquals("仪表盘", detail.getPageName());
    }

    @Test
    void testPageChartDetailWithError() {
        PageChartCreator.PageChartDetail detail = new PageChartCreator.PageChartDetail(
            "失败图表", null, false, "API Error",
            2, "page-001", "仪表盘"
        );

        assertEquals("失败图表", detail.getChartName());
        assertNull(detail.getChartId());
        assertFalse(detail.isSuccess());
        assertEquals("API Error", detail.getErrorMessage());
    }

    @Test
    void testMultiplePages() throws Exception {
        PageChartCreator.PageChartPlan plan1 = new PageChartCreator.PageChartPlan(
            "page-001", "仪表盘1", "dashboard", "概览1",
            List.of(new PageChartCreator.PageChartDefinition(
                "图表1", 3, "ws-001", "view-001",
                "状态", 9, "数量", 10000000, "count", 1, 1
            )), 2
        );

        PageChartCreator.PageChartPlan plan2 = new PageChartCreator.PageChartPlan(
            "page-002", "仪表盘2", "dashboard", "概览2",
            List.of(new PageChartCreator.PageChartDefinition(
                "图表2", 2, "ws-002", "view-002",
                "类型", 14, "数值", 6, "sum", 1, 1
            )), 2
        );

        PageChartCreator.Input input = new PageChartCreator.Input(
            "app-001", List.of(plan1, plan2), true, false
        );

        PageChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getAllCharts().size());
    }

    @Test
    void testDefaultValues() {
        // Test default columns value
        PageChartCreator.PageChartDefinition chart = new PageChartCreator.PageChartDefinition(
            "测试", 1, "ws-001", "view-001",
            "字段", 2, null, 10000000, "count",
            0, 0  // width=0, height=0 should default to 1
        );

        assertEquals(1, chart.getWidth());
        assertEquals(1, chart.getHeight());

        // Test plan with default columns
        PageChartCreator.PageChartPlan plan = new PageChartCreator.PageChartPlan(
            "page-001", "测试页", "dashboard", "描述",
            List.of(), 0  // columns=0 should default to 2
        );

        assertEquals(2, plan.getColumns());
    }

    @Test
    void testInputCreation() {
        PageChartCreator.PageChartPlan plan = new PageChartCreator.PageChartPlan(
            "page-001", "仪表盘", "dashboard", "概览",
            List.of(), 2
        );

        PageChartCreator.Input input = new PageChartCreator.Input(
            "app-001", List.of(plan), false, true
        );

        assertEquals("app-001", input.getAppId());
        assertFalse(input.isDryRun());
        assertTrue(input.isFailFast());
        assertEquals(1, input.getPlans().size());
    }
}
