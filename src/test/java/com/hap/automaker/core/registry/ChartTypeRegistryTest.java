package com.hap.automaker.core.registry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;

/**
 * 图表类型注册中心测试 - 17种图表类型
 */
class ChartTypeRegistryTest {

    private static final Logger logger = LoggerFactory.getLogger(ChartTypeRegistryTest.class);

    // ========== 基础统计图 (4种) ==========
    @Test
    void testBarChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(1);
        assertNotNull(cfg, "Bar (reportType=1) must exist");
        assertEquals("柱图", cfg.getName());
        assertEquals("basic", cfg.getCategory());
        assertTrue(cfg.isNeedsXAxis());
        assertTrue(cfg.isNeedsYAxis());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getDefaultConfig());
        assertTrue(cfg.getConfigHints().containsKey("isPile"));
        assertTrue(Arrays.asList(cfg.getRecommendedXAxisTypes()).contains("classify"));
        assertTrue(Arrays.asList(cfg.getRecommendedYAxisTypes()).contains("count"));

        logger.info("✓ Bar verified: {}", cfg.getName());
    }

    @Test
    void testLineChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(2);
        assertNotNull(cfg, "Line (reportType=2) must exist");
        assertEquals("折线图", cfg.getName());
        assertEquals("basic", cfg.getCategory());
        assertTrue(cfg.isNeedsXAxis());
        assertTrue(cfg.isNeedsYAxis());

        logger.info("✓ Line verified: {}", cfg.getName());
    }

    @Test
    void testPieChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(3);
        assertNotNull(cfg, "Pie (reportType=3) must exist");
        assertEquals("饼图/环形图", cfg.getName());

        assertNotNull(cfg.getDefaultConfig());
        assertEquals(true, cfg.getDefaultConfig().get("showPercent"));

        logger.info("✓ Pie verified: {}", cfg.getName());
    }

    @Test
    void testNumberChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(10);
        assertNotNull(cfg, "Number (reportType=10) must exist");
        assertEquals("数值图", cfg.getName());
        assertFalse(cfg.isNeedsXAxis());
        assertTrue(cfg.isNeedsYAxis());

        logger.info("✓ Number verified: {}", cfg.getName());
    }

    // ========== 对比分析图 (3种) ==========
    @Test
    void testDualAxisChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(7);
        assertNotNull(cfg, "DualAxis (reportType=7) must exist");
        assertEquals("双轴图", cfg.getName());
        assertEquals("comparison", cfg.getCategory());

        assertTrue(cfg.getConfigHints().containsKey("yreportType"));

        logger.info("✓ DualAxis verified: {}", cfg.getName());
    }

    @Test
    void testSymmetryBarChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(11);
        assertNotNull(cfg, "SymmetryBar (reportType=11) must exist");
        assertEquals("对称条形图", cfg.getName());
        assertEquals("comparison", cfg.getCategory());

        logger.info("✓ SymmetryBar verified: {}", cfg.getName());
    }

    @Test
    void testPivotTableChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(8);
        assertNotNull(cfg, "PivotTable (reportType=8) must exist");
        assertEquals("透视表", cfg.getName());

        assertNotNull(cfg.getDefaultConfig());
        assertEquals(true, cfg.getDefaultConfig().get("showTotal"));
        assertEquals(true, cfg.getDefaultConfig().get("mergeCell"));

        logger.info("✓ PivotTable verified: {}", cfg.getName());
    }

    // ========== 流程分析图 (2种) ==========
    @Test
    void testFunnelChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(6);
        assertNotNull(cfg, "Funnel (reportType=6) must exist");
        assertEquals("漏斗图", cfg.getName());
        assertEquals("flow", cfg.getCategory());

        logger.info("✓ Funnel verified: {}", cfg.getName());
    }

    @Test
    void testProgressChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(15);
        assertNotNull(cfg, "Progress (reportType=15) must exist");
        assertEquals("进度图", cfg.getName());
        assertEquals("flow", cfg.getCategory());
        assertFalse(cfg.isNeedsXAxis());
        assertTrue(cfg.isNeedsYAxis());

        logger.info("✓ Progress verified: {}", cfg.getName());
    }

    // ========== 数据分布图 (3种) ==========
    @Test
    void testScatterChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(12);
        assertNotNull(cfg, "Scatter (reportType=12) must exist");
        assertEquals("散点图", cfg.getName());
        assertEquals("distribution", cfg.getCategory());

        assertTrue(Arrays.asList(cfg.getRecommendedXAxisTypes()).contains("numeric"));
        assertTrue(Arrays.asList(cfg.getRecommendedYAxisTypes()).contains("numeric"));

        logger.info("✓ Scatter verified: {}", cfg.getName());
    }

    @Test
    void testWordCloudChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(13);
        assertNotNull(cfg, "WordCloud (reportType=13) must exist");
        assertEquals("词云图", cfg.getName());

        assertTrue(Arrays.asList(cfg.getRecommendedXAxisTypes()).contains("text"));
        assertTrue(Arrays.asList(cfg.getRecommendedYAxisTypes()).contains("count"));

        logger.info("✓ WordCloud verified: {}", cfg.getName());
    }

    // ========== 地理/空间图 (2种) ==========
    @Test
    void testMapChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(17);
        assertNotNull(cfg, "Map (reportType=17) must exist");
        assertEquals("地图", cfg.getName());
        assertEquals("geo", cfg.getCategory());

        assertTrue(Arrays.asList(cfg.getRecommendedXAxisTypes()).contains("geo"));

        logger.info("✓ Map verified: {}", cfg.getName());
    }

    @Test
    void testRegionMapChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(9);
        assertNotNull(cfg, "RegionMap (reportType=9) must exist");
        assertEquals("行政区划图", cfg.getName());

        assertTrue(Arrays.asList(cfg.getRecommendedXAxisTypes()).contains("region"));

        logger.info("✓ RegionMap verified: {}", cfg.getName());
    }

    // ========== 仪表盘组件 (2种) ==========
    @Test
    void testGaugeChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(14);
        assertNotNull(cfg, "Gauge (reportType=14) must exist");
        assertEquals("仪表盘", cfg.getName());
        assertEquals("gauge", cfg.getCategory());
        assertFalse(cfg.isNeedsXAxis());
        assertTrue(cfg.isNeedsYAxis());

        logger.info("✓ Gauge verified: {}", cfg.getName());
    }

    @Test
    void testRankingChart() {
        ChartTypeConfig cfg = new ChartTypeRegistry().getById(16);
        assertNotNull(cfg, "Ranking (reportType=16) must exist");
        assertEquals("排行图", cfg.getName());
        assertEquals("gauge", cfg.getCategory());

        assertTrue(cfg.getConfigHints().containsKey("topN"));

        logger.info("✓ Ranking verified: {}", cfg.getName());
    }

    // ========== 整体验证 ==========
    @Test
    void testAllChartTypesExist() {
        ChartTypeRegistry registry = new ChartTypeRegistry();

        int[] reportTypes = {1, 2, 3, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17};
        for (int rt : reportTypes) {
            assertNotNull(registry.getById(rt), "Chart type reportType=" + rt + " should exist");
        }

        logger.info("✓ All chart types verified");
    }

    @Test
    void testRegistrySize() {
        ChartTypeRegistry registry = new ChartTypeRegistry();
        assertTrue(registry.size() >= 14, "Registry should have at least 14 chart types");
        logger.info("✓ Registry statistics: size={}", registry.size());
    }

    @Test
    void testNameToIdMapping() {
        ChartTypeRegistry registry = new ChartTypeRegistry();

        assertEquals(1, registry.getReportTypeByName("Bar"));
        assertEquals(2, registry.getReportTypeByName("Line"));
        assertEquals(3, registry.getReportTypeByName("Pie"));
        assertEquals(6, registry.getReportTypeByName("Funnel"));
        assertEquals(7, registry.getReportTypeByName("DualAxis"));
        assertEquals(12, registry.getReportTypeByName("Scatter"));
        assertEquals(13, registry.getReportTypeByName("WordCloud"));
        assertEquals(14, registry.getReportTypeByName("Gauge"));
        assertEquals(15, registry.getReportTypeByName("Progress"));
        assertEquals(16, registry.getReportTypeByName("Ranking"));
        assertEquals(17, registry.getReportTypeByName("Map"));

        logger.info("✓ All name-to-id mappings verified");
    }

    @Test
    void testPlannableTypes() {
        ChartTypeRegistry registry = new ChartTypeRegistry();
        var plannable = registry.getPlannableTypes();

        assertTrue(plannable.contains("Bar"), "Bar should be plannable");
        assertTrue(plannable.contains("Line"), "Line should be plannable");
        assertTrue(plannable.contains("Pie"), "Pie should be plannable");
        assertTrue(plannable.contains("Number"), "Number should be plannable");
        assertTrue(plannable.contains("DualAxis"), "DualAxis should be plannable");
        assertTrue(plannable.contains("Funnel"), "Funnel should be plannable");
        assertTrue(plannable.contains("Scatter"), "Scatter should be plannable");
        assertTrue(plannable.contains("Gauge"), "Gauge should be plannable");

        logger.info("✓ Plannable types check passed, count={}", plannable.size());
    }

    @Test
    void testPromptGeneration() {
        ChartTypeRegistry registry = new ChartTypeRegistry();
        String prompt = registry.generatePrompt();

        assertTrue(prompt.contains("基础统计图"), "Prompt should contain basic category");
        assertTrue(prompt.contains("对比分析图"), "Prompt should contain comparison category");
        assertTrue(prompt.contains("流程分析图"), "Prompt should contain flow category");
        assertTrue(prompt.contains("数据分布图"), "Prompt should contain distribution category");
        assertTrue(prompt.contains("地理"), "Prompt should contain geo category");
        assertTrue(prompt.contains("仪表盘"), "Prompt should contain gauge category");

        assertTrue(prompt.contains("柱图"), "Bar should appear in prompt");
        assertTrue(prompt.contains("折线图"), "Line should appear in prompt");
        assertTrue(prompt.contains("饼图"), "Pie should appear in prompt");

        assertTrue(prompt.contains("配置说明"), "Prompt should contain configuration guide");
        assertTrue(prompt.contains("X轴字段类型"), "Prompt should explain X axis types");

        logger.info("✓ Prompt generation verified");
    }

    @Test
    void testStaticHelperMethods() {
        Map<Integer, String> normTypes = ChartTypeRegistry.getNormTypeNames();
        assertNotNull(normTypes);
        assertTrue(normTypes.containsKey(1));
        assertTrue(normTypes.get(1).contains("SUM"));
        assertTrue(normTypes.get(5).contains("COUNT"));

        Map<Integer, String> particleSizes = ChartTypeRegistry.getParticleSizeNames();
        assertNotNull(particleSizes);
        assertTrue(particleSizes.containsKey(1));
        assertTrue(particleSizes.get(1).contains("按月"));

        Map<Integer, String> rangeTypes = ChartTypeRegistry.getRangeTypeNames();
        assertNotNull(rangeTypes);
        assertTrue(rangeTypes.containsKey(18));
        assertTrue(rangeTypes.get(18).contains("近N天"));

        logger.info("✓ Static helper methods verified");
    }
}
