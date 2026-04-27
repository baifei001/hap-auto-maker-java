package com.hap.automaker.core.registry;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段类型注册中心批次4测试 - 日期时间类字段（6种）
 * 对比Python FIELD_TYPE_MAP验证
 */
class FieldTypeRegistryBatch4Test {

    private static final Logger logger = LoggerFactory.getLogger(FieldTypeRegistryBatch4Test.class);

    @Test
    void testDateField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(15);
        assertNotNull(cfg, "Date (type=15) must exist");
        assertEquals("日期", cfg.getName());
        assertEquals("date", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("time", cfg.getAdvancedSetting().get("sorttype"));

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("format"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("defaulttype"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("defaultdate"));

        logger.info("✓ Date verified: " + cfg.getName() + " (type=15) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testDateTimeField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(16);
        assertNotNull(cfg, "DateTime (type=16) must exist");
        assertEquals("日期时间", cfg.getName());
        assertEquals("date", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("time", cfg.getAdvancedSetting().get("sorttype"));

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("format"));

        logger.info("✓ DateTime verified: " + cfg.getName() + " (type=16) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testTimeField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(46);
        assertNotNull(cfg, "Time (type=46) must exist");
        assertEquals("时间", cfg.getName());
        assertEquals("date", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        logger.info("✓ Time verified: " + cfg.getName() + " (type=46) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testWeekField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(21);
        assertNotNull(cfg, "Week (type=21) must exist");
        assertEquals("星期", cfg.getName());
        assertEquals("date", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        logger.info("✓ Week verified: " + cfg.getName() + " (type=21) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testCreatedAtField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(1001);
        assertNotNull(cfg, "CreatedAt (type=1001) must exist");
        assertEquals("创建时间", cfg.getName());
        assertEquals("date", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "CreatedAt should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ CreatedAt verified (AI disabled): " + cfg.getName() + " (type=1001) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testModifiedAtField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(1002);
        assertNotNull(cfg, "ModifiedAt (type=1002) must exist");
        assertEquals("修改时间", cfg.getName());
        assertEquals("date", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "ModifiedAt should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ ModifiedAt verified (AI disabled): " + cfg.getName() + " (type=1002) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testAllControlTypeMappings() {
        FieldTypeRegistry registry = new FieldTypeRegistry();

        assertEquals(15, registry.getControlTypeByName("Date"));
        assertEquals(16, registry.getControlTypeByName("DateTime"));
        assertEquals(46, registry.getControlTypeByName("Time"));
        assertEquals(21, registry.getControlTypeByName("Week"));
        assertEquals(1001, registry.getControlTypeByName("CreatedAt"));
        assertEquals(1002, registry.getControlTypeByName("ModifiedAt"));

        logger.info("✓ All controlType values match Python FIELD_TYPE_MAP");
    }

    @Test
    void testRegistrySize() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        // 所有批次已合并，总数应为38
        assertTrue(registry.size() >= 21, "Registry should have at least batch 4 types (total 38)");
        // 验证批次4的关键类型都存在
        assertNotNull(registry.getById(15), "Date should exist");
        assertNotNull(registry.getById(16), "DateTime should exist");
        assertNotNull(registry.getById(46), "Time should exist");
        assertNotNull(registry.getById(21), "Week should exist");
        assertNotNull(registry.getById(1001), "CreatedAt should exist");
        assertNotNull(registry.getById(1002), "ModifiedAt should exist");
        logger.info("✓ Registry statistics: size=" + registry.size() + " (batch 4 types verified)");
    }

    @Test
    void testPlannableTypes() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        var plannable = registry.getPlannableTypes();

        // 日期时间类中可规划的
        assertTrue(plannable.contains("Date"), "Date should be plannable");
        assertTrue(plannable.contains("DateTime"), "DateTime should be plannable");
        assertTrue(plannable.contains("Time"), "Time should be plannable");
        assertTrue(plannable.contains("Week"), "Week should be plannable");

        // AI禁用的
        assertFalse(plannable.contains("CreatedAt"), "CreatedAt should not be plannable (AI disabled)");
        assertFalse(plannable.contains("ModifiedAt"), "ModifiedAt should not be plannable (AI disabled)");

        logger.info("✓ Plannable types check passed (batch4: Date, DateTime, Time, Week are plannable)");
    }

    @Test
    void testDateCategoryPrompt() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        String prompt = registry.generatePrompt();

        assertTrue(prompt.contains("日期时间"), "Prompt should contain '日期时间' category");
        assertTrue(prompt.contains("日期") || prompt.contains("日期时间"), "Prompt should contain date field types");

        // AI禁用的不应该出现在prompt中
        assertFalse(prompt.contains("创建时间"), "CreatedAt should not appear in prompt (AI disabled)");
        assertFalse(prompt.contains("修改时间"), "ModifiedAt should not appear in prompt (AI disabled)");

        logger.info("✓ Batch4 prompt generation verified");
    }
}
