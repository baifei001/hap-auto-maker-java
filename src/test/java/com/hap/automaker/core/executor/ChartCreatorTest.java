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
 * ChartCreator 测试类
 */
class ChartCreatorTest {

    private MockHapApiClient apiClient;

    @BeforeEach
    void setUp() {
        apiClient = new MockHapApiClient();
    }

    @Test
    void testGetName() {
        ChartCreator creator = new ChartCreator(apiClient, 4);
        assertEquals("ChartCreator", creator.getName());
    }

    @Test
    void testInputCreation() {
        Map<String, Object> xaxes = new HashMap<>();
        xaxes.put("controlId", "field-123");
        xaxes.put("controlName", "状态");
        xaxes.put("controlType", 9);

        ChartCreator.ChartDefinition chart = new ChartCreator.ChartDefinition(
            "订单状态分布", 3, xaxes, null, null, null, null, null, null
        );

        ChartCreator.WorksheetChartPlan wsPlan = new ChartCreator.WorksheetChartPlan(
            "ws-123", "订单表", List.of(chart)
        );

        ChartCreator.Input input = new ChartCreator.Input(
            List.of(wsPlan), "page-abc", false, false
        );

        assertEquals(1, input.getPlans().size());
        assertEquals("page-abc", input.getPageId());
        assertFalse(input.isDryRun());
        assertFalse(input.isFailFast());
    }

    @Test
    void testChartDefinition() {
        Map<String, Object> xaxes = new HashMap<>();
        xaxes.put("controlId", "field-123");
        xaxes.put("controlName", "日期");
        xaxes.put("controlType", 15);
        xaxes.put("particleSizeType", 1);

        List<Map<String, Object>> yaxisList = List.of(
            Map.of("controlId", "field-456", "controlName", "金额", "controlType", 8)
        );

        ChartCreator.ChartDefinition chart = new ChartCreator.ChartDefinition(
            "月度销售额趋势", 1, xaxes, yaxisList, null, null, null, null, null
        );

        assertEquals("月度销售额趋势", chart.getName());
        assertEquals(1, chart.getReportType()); // 折线图
        assertEquals(xaxes, chart.getXaxes());
        assertEquals(yaxisList, chart.getYaxisList());
        assertNull(chart.getRightY());
    }

    @Test
    void testWorksheetChartPlan() {
        ChartCreator.ChartDefinition chart1 = new ChartCreator.ChartDefinition(
            "图表1", 3, null, null, null, null, null, null, null
        );
        ChartCreator.ChartDefinition chart2 = new ChartCreator.ChartDefinition(
            "图表2", 4, null, null, null, null, null, null, null
        );

        ChartCreator.WorksheetChartPlan plan = new ChartCreator.WorksheetChartPlan(
            "ws-123", "测试表", List.of(chart1, chart2)
        );

        assertEquals("ws-123", plan.getWorksheetId());
        assertEquals("测试表", plan.getWorksheetName());
        assertEquals(2, plan.getCharts().size());
    }

    @Test
    void testOutputCreation() {
        Map<String, List<ChartCreator.ChartCreationDetail>> wsCharts = new HashMap<>();

        ChartCreator.ChartCreationDetail detail = new ChartCreator.ChartCreationDetail(
            "测试图表", "report-123", true, null, 3, "ws-123"
        );

        wsCharts.put("ws-123", List.of(detail));

        ChartCreator.Output output = new ChartCreator.Output(
            true, wsCharts, List.of(detail), null
        );

        assertTrue(output.isSuccess());
        assertEquals(1, output.getWorksheetCharts().size());
        assertEquals(1, output.getAllCharts().size());
        assertNull(output.getErrorMessage());

        ChartCreator.ChartCreationDetail retrieved = output.getAllCharts().get(0);
        assertEquals("测试图表", retrieved.getName());
        assertEquals("report-123", retrieved.getReportId());
        assertTrue(retrieved.isSuccess());
        assertEquals(3, retrieved.getReportType());
        assertEquals("ws-123", retrieved.getWorksheetId());
    }

    @Test
    void testChartCreationDetailFailure() {
        ChartCreator.ChartCreationDetail detail = new ChartCreator.ChartCreationDetail(
            "失败图表", null, false, "API Error", 1, "ws-123"
        );

        assertFalse(detail.isSuccess());
        assertEquals("API Error", detail.getErrorMessage());
        assertNull(detail.getReportId());
    }

    @Test
    void testExecuteWithPieChart() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createChartResponse("report-pie-123"));

        ChartCreator creator = new ChartCreator(apiClient, 4);

        Map<String, Object> xaxes = new HashMap<>();
        xaxes.put("controlId", "field-status");
        xaxes.put("controlName", "状态");
        xaxes.put("controlType", 9);

        ChartCreator.ChartDefinition chart = new ChartCreator.ChartDefinition(
            "状态分布", 3, xaxes, null, null, null, null, null, null
        );

        ChartCreator.WorksheetChartPlan plan = new ChartCreator.WorksheetChartPlan(
            "ws-123", "测试表", List.of(chart)
        );

        ChartCreator.Input input = new ChartCreator.Input(
            List.of(plan), "page-abc", false, false
        );

        ChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getAllCharts().size());
        assertEquals("report-pie-123", output.getAllCharts().get(0).getReportId());
    }

    @Test
    void testExecuteWithLineChart() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createChartResponse("report-line-123"));

        ChartCreator creator = new ChartCreator(apiClient, 4);

        Map<String, Object> xaxes = new HashMap<>();
        xaxes.put("controlId", "field-date");
        xaxes.put("controlName", "日期");
        xaxes.put("controlType", 15);
        xaxes.put("particleSizeType", 1); // 按月分组

        List<Map<String, Object>> yaxisList = List.of(
            Map.of("controlId", "field-amount", "controlName", "金额", "controlType", 8)
        );

        ChartCreator.ChartDefinition chart = new ChartCreator.ChartDefinition(
            "月度趋势", 1, xaxes, yaxisList, null, null, null, null, null
        );

        ChartCreator.WorksheetChartPlan plan = new ChartCreator.WorksheetChartPlan(
            "ws-123", "测试表", List.of(chart)
        );

        ChartCreator.Input input = new ChartCreator.Input(
            List.of(plan), "page-abc", false, false
        );

        ChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
    }

    @Test
    void testExecuteWithDualAxisChart() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createChartResponse("report-dual-123"));

        ChartCreator creator = new ChartCreator(apiClient, 4);

        Map<String, Object> xaxes = new HashMap<>();
        xaxes.put("controlId", "field-date");
        xaxes.put("controlName", "日期");
        xaxes.put("controlType", 15);

        List<Map<String, Object>> yaxisList = List.of(
            Map.of("controlId", "field-sales", "controlName", "销售额", "controlType", 8)
        );

        Map<String, Object> rightY = new HashMap<>();
        rightY.put("reportType", 2);
        rightY.put("yaxisList", List.of(
            Map.of("controlId", "field-profit", "controlName", "利润率", "controlType", 6)
        ));

        ChartCreator.ChartDefinition chart = new ChartCreator.ChartDefinition(
            "销售与利润", 7, xaxes, yaxisList, rightY, 2, null, null, null
        );

        ChartCreator.WorksheetChartPlan plan = new ChartCreator.WorksheetChartPlan(
            "ws-123", "测试表", List.of(chart)
        );

        ChartCreator.Input input = new ChartCreator.Input(
            List.of(plan), "page-abc", false, false
        );

        ChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
    }

    @Test
    void testExecuteWithMultipleCharts() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createChartResponse("report-multi"));

        ChartCreator creator = new ChartCreator(apiClient, 4);

        ChartCreator.ChartDefinition chart1 = new ChartCreator.ChartDefinition(
            "图表1", 3, null, null, null, null, null, null, null
        );
        ChartCreator.ChartDefinition chart2 = new ChartCreator.ChartDefinition(
            "图表2", 4, null, null, null, null, null, null, null
        );

        ChartCreator.WorksheetChartPlan plan = new ChartCreator.WorksheetChartPlan(
            "ws-123", "测试表", List.of(chart1, chart2)
        );

        ChartCreator.Input input = new ChartCreator.Input(
            List.of(plan), "page-abc", false, false
        );

        ChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getAllCharts().size());
    }

    @Test
    void testExecuteWithMultipleWorksheets() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createChartResponse("report-ws"));

        ChartCreator creator = new ChartCreator(apiClient, 4);

        ChartCreator.WorksheetChartPlan plan1 = new ChartCreator.WorksheetChartPlan(
            "ws-1", "表1", List.of(new ChartCreator.ChartDefinition("图1", 3, null, null, null, null, null, null, null))
        );

        ChartCreator.WorksheetChartPlan plan2 = new ChartCreator.WorksheetChartPlan(
            "ws-2", "表2", List.of(new ChartCreator.ChartDefinition("图2", 4, null, null, null, null, null, null, null))
        );

        ChartCreator.Input input = new ChartCreator.Input(
            List.of(plan1, plan2), "page-abc", false, false
        );

        ChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(2, output.getAllCharts().size());
        assertEquals(2, output.getWorksheetCharts().size());
    }

    @Test
    void testExecuteDryRun() throws Exception {
        ChartCreator creator = new ChartCreator(apiClient, 4);

        ChartCreator.ChartDefinition chart = new ChartCreator.ChartDefinition(
            "测试图表", 3, null, null, null, null, null, null, null
        );

        ChartCreator.WorksheetChartPlan plan = new ChartCreator.WorksheetChartPlan(
            "ws-123", "测试表", List.of(chart)
        );

        ChartCreator.Input input = new ChartCreator.Input(
            List.of(plan), "page-abc", true, false // dryRun = true
        );

        ChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
        assertEquals(1, output.getAllCharts().size());
    }

    @Test
    void testChartTypes() {
        // 测试各种图表类型
        // 1=折线图, 2=柱状图, 3=饼图, 4=环形图, 5=地图, 6=漏斗图
        // 7=双轴图, 8=堆叠柱状图, 9=条形图, 10=数值图, 13=透视表
        int[] reportTypes = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 13};

        for (int type : reportTypes) {
            ChartCreator.ChartDefinition chart = new ChartCreator.ChartDefinition(
                "图表" + type, type, null, null, null, null, null, null, null
            );
            assertEquals(type, chart.getReportType());
        }
    }

    @Test
    void testPivotTableChart() throws Exception {
        apiClient.setMockResponse(JsonNodeCreationHelper.createChartResponse("report-pivot-123"));

        ChartCreator creator = new ChartCreator(apiClient, 4);

        Map<String, Object> xaxes = new HashMap<>();
        xaxes.put("controlId", "field-product");
        xaxes.put("controlName", "产品");
        xaxes.put("controlType", 9);

        Map<String, Object> pivotTable = new HashMap<>();
        pivotTable.put("columnFields", List.of("field-region"));
        pivotTable.put("rowFields", List.of("field-product"));
        pivotTable.put("valueFields", List.of("field-amount"));

        ChartCreator.ChartDefinition chart = new ChartCreator.ChartDefinition(
            "销售透视表", 13, xaxes, null, null, null, null, pivotTable, null
        );

        ChartCreator.WorksheetChartPlan plan = new ChartCreator.WorksheetChartPlan(
            "ws-123", "测试表", List.of(chart)
        );

        ChartCreator.Input input = new ChartCreator.Input(
            List.of(plan), "page-abc", false, false
        );

        ChartCreator.Output output = creator.execute(input);

        assertTrue(output.isSuccess());
    }

    // ========== Mock 和辅助类 ==========

    private static class MockHapApiClient extends HapApiClient {
        private JsonNode mockResponse;

        public void setMockResponse(JsonNode response) {
            this.mockResponse = response;
        }

        @Override
        public JsonNode saveReportConfig(JsonNode reportConfig) {
            return mockResponse != null ? mockResponse : JsonNodeCreationHelper.createChartResponse("mock-report-id");
        }
    }

    private static class JsonNodeCreationHelper {
        static JsonNode createChartResponse(String reportId) {
            return com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                .put("success", true)
                .set("data", com.hap.automaker.config.Jacksons.mapper().createObjectNode()
                    .put("reportId", reportId));
        }
    }
}
