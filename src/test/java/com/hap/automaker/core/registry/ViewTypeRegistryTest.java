package com.hap.automaker.core.registry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 视图类型注册中心测试 - 11种视图类型
 */
class ViewTypeRegistryTest {

    private static final Logger logger = LoggerFactory.getLogger(ViewTypeRegistryTest.class);

    @Test
    void testTableView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(0);
        assertNotNull(cfg, "Table (viewType=0) must exist");
        assertEquals("表格", cfg.getName());
        assertEquals("basic", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getDefaultConfig());
        assertEquals(0, cfg.getDefaultConfig().get("sortType"));

        assertNotNull(cfg.getConfigHints());
        assertTrue(cfg.getConfigHints().containsKey("sortType"));

        logger.info("✓ Table verified: " + cfg.getName() + " (viewType=0) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testDetailView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(6);
        assertNotNull(cfg, "Detail (viewType=6) must exist");
        assertEquals("详情视图", cfg.getName());
        assertEquals("basic", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        logger.info("✓ Detail verified: " + cfg.getName() + " (viewType=6) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testQuickView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(8);
        assertNotNull(cfg, "Quick (viewType=8) must exist");
        assertEquals("快速视图", cfg.getName());
        assertFalse(cfg.isAiDisabled());

        logger.info("✓ Quick verified: " + cfg.getName() + " (viewType=8) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testKanbanView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(1);
        assertNotNull(cfg, "Kanban (viewType=1) must exist");
        assertEquals("看板", cfg.getName());
        assertEquals("visual", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getRequiredFieldTypes());
        assertTrue(Arrays.asList(cfg.getRequiredFieldTypes()).contains("SingleSelect") ||
                   Arrays.asList(cfg.getRequiredFieldTypes()).contains("MultipleSelect"));

        assertTrue(cfg.getConfigHints().containsKey("viewControl"));

        logger.info("✓ Kanban verified: " + cfg.getName() + " (viewType=1) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testGalleryView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(3);
        assertNotNull(cfg, "Gallery (viewType=3) must exist");
        assertEquals("画廊", cfg.getName());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getDefaultConfig());
        assertTrue(cfg.getDefaultConfig().containsKey("coverstyle"));

        logger.info("✓ Gallery verified: " + cfg.getName() + " (viewType=3) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testMapView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(7);
        assertNotNull(cfg, "Map (viewType=7) must exist");
        assertEquals("地图", cfg.getName());
        assertTrue(cfg.isAiDisabled(), "Map should be AI disabled (requires location field)");
        assertNotNull(cfg.getAiDisabledReason());

        assertNotNull(cfg.getRequiredFieldTypes());
        assertTrue(Arrays.asList(cfg.getRequiredFieldTypes()).contains("Location"));

        logger.info("✓ Map verified (AI disabled): " + cfg.getName() + " (viewType=7) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testCalendarView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(4);
        assertNotNull(cfg, "Calendar (viewType=4) must exist");
        assertEquals("日历", cfg.getName());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getRequiredFieldTypes());
        assertTrue(Arrays.asList(cfg.getRequiredFieldTypes()).contains("Date") ||
                   Arrays.asList(cfg.getRequiredFieldTypes()).contains("DateTime"));

        logger.info("✓ Calendar verified: " + cfg.getName() + " (viewType=4) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testGanttView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(5);
        assertNotNull(cfg, "Gantt (viewType=5) must exist");
        assertEquals("甘特图", cfg.getName());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getRequiredFieldTypes());
        assertTrue(cfg.getConfigHints().containsKey("begindate"));
        assertTrue(cfg.getConfigHints().containsKey("enddate"));

        logger.info("✓ Gantt verified: " + cfg.getName() + " (viewType=5) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testResourceView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(9);
        assertNotNull(cfg, "Resource (viewType=9) must exist");
        assertEquals("资源视图", cfg.getName());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getRequiredFieldTypes());

        logger.info("✓ Resource verified: " + cfg.getName() + " (viewType=9) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testHierarchyView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(2);
        assertNotNull(cfg, "Hierarchy (viewType=2) must exist");
        assertEquals("层级", cfg.getName());
        assertTrue(cfg.isAiDisabled(), "Hierarchy should be AI disabled (requires self relation)");
        assertNotNull(cfg.getAiDisabledReason());

        assertNotNull(cfg.getRequiredFieldTypes());
        assertTrue(Arrays.asList(cfg.getRequiredFieldTypes()).contains("Relation"));

        logger.info("✓ Hierarchy verified (AI disabled): " + cfg.getName() + " (viewType=2) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testCustomView() {
        ViewTypeConfig cfg = new ViewTypeRegistry().getById(10);
        assertNotNull(cfg, "Custom (viewType=10) must exist");
        assertEquals("自定义视图", cfg.getName());
        assertTrue(cfg.isAiDisabled(), "Custom should be AI disabled (requires frontend dev)");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ Custom verified (AI disabled): " + cfg.getName() + " (viewType=10) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testAllViewTypesExist() {
        ViewTypeRegistry registry = new ViewTypeRegistry();

        // 验证所有11种视图类型都存在
        int[] viewTypes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        for (int vt : viewTypes) {
            assertNotNull(registry.getById(vt), "ViewType " + vt + " should exist");
        }

        logger.info("✓ All 11 view types verified");
    }

    @Test
    void testRegistrySize() {
        ViewTypeRegistry registry = new ViewTypeRegistry();
        assertEquals(11, registry.size(), "Registry should have exactly 11 view types");
        logger.info("✓ Registry statistics: size=" + registry.size());
    }

    @Test
    void testPlannableTypes() {
        ViewTypeRegistry registry = new ViewTypeRegistry();
        var plannable = registry.getPlannableTypes();

        // 可规划的视图
        assertTrue(plannable.contains("Table"), "Table should be plannable");
        assertTrue(plannable.contains("Detail"), "Detail should be plannable");
        assertTrue(plannable.contains("Quick"), "Quick should be plannable");
        assertTrue(plannable.contains("Kanban"), "Kanban should be plannable");
        assertTrue(plannable.contains("Gallery"), "Gallery should be plannable");
        assertTrue(plannable.contains("Calendar"), "Calendar should be plannable");
        assertTrue(plannable.contains("Gantt"), "Gantt should be plannable");
        assertTrue(plannable.contains("Resource"), "Resource should be plannable");

        // AI禁用的
        assertFalse(plannable.contains("Map"), "Map should not be plannable (AI disabled)");
        assertFalse(plannable.contains("Hierarchy"), "Hierarchy should not be plannable (AI disabled)");
        assertFalse(plannable.contains("Custom"), "Custom should not be plannable (AI disabled)");

        logger.info("✓ Plannable types check passed (8 plannable, 3 AI disabled)");
    }

    @Test
    void testPromptGeneration() {
        ViewTypeRegistry registry = new ViewTypeRegistry();
        String prompt = registry.generatePrompt();

        assertTrue(prompt.contains("基础视图"), "Prompt should contain basic category");
        assertTrue(prompt.contains("视觉视图"), "Prompt should contain visual category");
        assertTrue(prompt.contains("时间视图"), "Prompt should contain time category");

        assertTrue(prompt.contains("表格"), "Table should appear in prompt");
        assertTrue(prompt.contains("看板"), "Kanban should appear in prompt");
        assertTrue(prompt.contains("日历"), "Calendar should appear in prompt");

        // AI禁用的不应该出现在prompt中
        assertFalse(prompt.contains("地图"), "Map should not appear in prompt (AI disabled)");
        assertFalse(prompt.contains("层级"), "Hierarchy should not appear in prompt (AI disabled)");
        assertFalse(prompt.contains("自定义视图"), "Custom should not appear in prompt (AI disabled)");

        logger.info("✓ Prompt generation verified");
    }

    @Test
    void testNameToIdMapping() {
        ViewTypeRegistry registry = new ViewTypeRegistry();

        assertEquals(0, registry.getViewTypeByName("Table"));
        assertEquals(1, registry.getViewTypeByName("Kanban"));
        assertEquals(2, registry.getViewTypeByName("Hierarchy"));
        assertEquals(3, registry.getViewTypeByName("Gallery"));
        assertEquals(4, registry.getViewTypeByName("Calendar"));
        assertEquals(5, registry.getViewTypeByName("Gantt"));
        assertEquals(6, registry.getViewTypeByName("Detail"));
        assertEquals(7, registry.getViewTypeByName("Map"));
        assertEquals(8, registry.getViewTypeByName("Quick"));
        assertEquals(9, registry.getViewTypeByName("Resource"));
        assertEquals(10, registry.getViewTypeByName("Custom"));

        logger.info("✓ All name-to-id mappings verified");
    }
}
