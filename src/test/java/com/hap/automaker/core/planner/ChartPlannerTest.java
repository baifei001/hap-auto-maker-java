package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.model.AiAuthConfig;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图表规划器测试
 */
class ChartPlannerTest {

    @Test
    void testGetName() {
        // Create a simple mock/placeholder client
        AiTextClient mockClient = new AiTextClient() {
            @Override
            public String generateJson(String prompt, AiAuthConfig config) { return "{}"; }
        };
        ChartPlanner planner = new ChartPlanner(mockClient);
        assertEquals("ChartPlanner", planner.getName());
    }

    @Test
    void testInputOutputAccessors() {
        List<ChartPlanner.WorksheetInfo> worksheets = List.of(
            new ChartPlanner.WorksheetInfo("表1", "用途1", List.of())
        );

        ChartPlanner.Input input = new ChartPlanner.Input("应用名", worksheets);

        assertEquals("应用名", input.getAppName());
        assertEquals(1, input.getWorksheets().size());
    }

    @Test
    void testEmptyWorksheets() {
        ChartPlanner.Input input = new ChartPlanner.Input("空应用", List.of());
        assertEquals("空应用", input.getAppName());
        assertTrue(input.getWorksheets().isEmpty());
    }

    @Test
    void testFieldClassificationWithNumbers() {
        // 创建字段分类测试输入
        List<ChartPlanner.FieldInfo> fields = List.of(
            new ChartPlanner.FieldInfo("名称", 2),      // Text
            new ChartPlanner.FieldInfo("年龄", 6),      // Number
            new ChartPlanner.FieldInfo("金额", 7),      // Money
            new ChartPlanner.FieldInfo("创建日期", 9),  // Date
            new ChartPlanner.FieldInfo("状态", 14),     // Single Select
            new ChartPlanner.FieldInfo("负责人", 26),   // User
            new ChartPlanner.FieldInfo("部门", 27),     // Department
            new ChartPlanner.FieldInfo("地址", 29)      // Area
        );

        assertEquals(8, fields.size());
        assertEquals(2, fields.get(0).getControlType()); // Text
        assertEquals(6, fields.get(1).getControlType()); // Number
        assertEquals(7, fields.get(2).getControlType()); // Money
        assertEquals(9, fields.get(3).getControlType()); // Date
        assertEquals(14, fields.get(4).getControlType()); // Single Select
        assertEquals(26, fields.get(5).getControlType()); // User
        assertEquals(27, fields.get(6).getControlType()); // Department
        assertEquals(29, fields.get(7).getControlType()); // Area
    }

    @Test
    void testChartPlanOutputAccessors() {
        List<ChartPlanner.YAxisConfig> yAxes = List.of(
            new ChartPlanner.YAxisConfig("金额", "SUM", "总销售额"),
            new ChartPlanner.YAxisConfig("数量", "COUNT", "订单数")
        );

        List<ChartPlanner.FilterConfig> filters = List.of(
            new ChartPlanner.FilterConfig("状态", "eq", "已完成")
        );

        ChartPlanner.ChartPlan chart = new ChartPlanner.ChartPlan(
            "销售分析",
            "DualAxis",
            "订单",
            "日期",
            yAxes,
            filters,
            "展示销售趋势"
        );

        assertEquals("销售分析", chart.getName());
        assertEquals("DualAxis", chart.getReportType());
        assertEquals("订单", chart.getWorksheetName());
        assertEquals("日期", chart.getXAxisField());
        assertEquals("展示销售趋势", chart.getPurpose());

        // 验证 Y 轴配置
        assertEquals(2, chart.getYAxes().size());
        assertEquals("金额", chart.getYAxes().get(0).getField());
        assertEquals("SUM", chart.getYAxes().get(0).getAggregation());
        assertEquals("总销售额", chart.getYAxes().get(0).getAlias());
        assertEquals("数量", chart.getYAxes().get(1).getField());
        assertEquals("COUNT", chart.getYAxes().get(1).getAggregation());

        // 验证过滤器配置
        assertEquals(1, chart.getFilters().size());
        assertEquals("状态", chart.getFilters().get(0).getField());
        assertEquals("eq", chart.getFilters().get(0).getOperator());
        assertEquals("已完成", chart.getFilters().get(0).getValue());
    }

    @Test
    void testWorksheetInfoAndFieldInfoAccessors() {
        List<ChartPlanner.FieldInfo> fields = List.of(
            new ChartPlanner.FieldInfo("字段1", 2),
            new ChartPlanner.FieldInfo("字段2", 6)
        );

        ChartPlanner.WorksheetInfo ws = new ChartPlanner.WorksheetInfo(
            "测试表",
            "测试用途",
            fields
        );

        assertEquals("测试表", ws.getName());
        assertEquals("测试用途", ws.getPurpose());
        assertEquals(2, ws.getFields().size());

        assertEquals("字段1", ws.getFields().get(0).getName());
        assertEquals(2, ws.getFields().get(0).getControlType());
    }
}
