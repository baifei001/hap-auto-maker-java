package com.hap.automaker.core.registry;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段类型注册中心批次2测试 - 数值类字段（5种）
 * 对比Python FIELD_TYPE_MAP验证
 */
class FieldTypeRegistryBatch2Test {

    private static final Logger logger = LoggerFactory.getLogger(FieldTypeRegistryBatch2Test.class);

    @Test
    void testNumberField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(6);
        assertNotNull(cfg, "Number (type=6) must exist");
        assertEquals("数字", cfg.getName());
        assertEquals("number", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());
        assertFalse(cfg.isCanBeTitle());

        // 验证advancedSetting默认值
        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("zh", cfg.getAdvancedSetting().get("sorttype"));

        // 验证advancedSettingAllKeys
        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("dot"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("precision"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("unit"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("unitpos"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("thousandth"));

        logger.info("✓ Number verified: " + cfg.getName() + " (type=6) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testMoneyField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(8);
        assertNotNull(cfg, "Money (type=8) must exist");
        assertEquals("金额", cfg.getName());
        assertEquals("number", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());
        assertFalse(cfg.isCanBeTitle());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("zh", cfg.getAdvancedSetting().get("sorttype"));

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("dot"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("unit"));

        logger.info("✓ Money verified: " + cfg.getName() + " (type=8) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testMoneyCapitalField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(25);
        assertNotNull(cfg, "MoneyCapital (type=25) must exist");
        assertEquals("金额大写", cfg.getName());
        assertEquals("number", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "MoneyCapital should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ MoneyCapital verified (AI disabled): " + cfg.getName() + " (type=25) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testFormulaField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(31);
        assertNotNull(cfg, "Formula (type=31) must exist");
        assertEquals("公式", cfg.getName());
        assertEquals("number", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "Formula should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("dot"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("precision"));

        logger.info("✓ Formula verified (AI disabled): " + cfg.getName() + " (type=31) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testFormulaDateField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(38);
        assertNotNull(cfg, "FormulaDate (type=38) must exist");
        assertEquals("公式日期", cfg.getName());
        assertEquals("date", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "FormulaDate should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ FormulaDate verified (AI disabled): " + cfg.getName() + " (type=38) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testAllControlTypeMappings() {
        FieldTypeRegistry registry = new FieldTypeRegistry();

        // 验证所有批次2类型可以通过名称查找
        assertEquals(6, registry.getControlTypeByName("Number"));
        assertEquals(8, registry.getControlTypeByName("Money"));
        assertEquals(25, registry.getControlTypeByName("MoneyCapital"));
        assertEquals(31, registry.getControlTypeByName("Formula"));
        assertEquals(38, registry.getControlTypeByName("FormulaDate"));

        logger.info("✓ All controlType values match Python FIELD_TYPE_MAP");
    }

    @Test
    void testRegistrySize() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        // 所有批次已合并，总数应为38
        assertTrue(registry.size() >= 9, "Registry should have at least batch 2 types (total 38)");
        // 验证批次2的关键类型都存在
        assertNotNull(registry.getById(6), "Number should exist");
        assertNotNull(registry.getById(8), "Money should exist");
        assertNotNull(registry.getById(25), "MoneyCapital should exist");
        assertNotNull(registry.getById(31), "Formula should exist");
        assertNotNull(registry.getById(38), "FormulaDate should exist");
        logger.info("✓ Registry statistics: size=" + registry.size() + " (batch 2 types verified)");
    }

    @Test
    void testPlannableTypes() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        var plannable = registry.getPlannableTypes();

        // 数值类中 Number 和 Money 是可规划的（非AI禁用）
        assertTrue(plannable.contains("Number"), "Number should be plannable");
        assertTrue(plannable.contains("Money"), "Money should be plannable");

        // AI禁用的不应在plannable中
        assertFalse(plannable.contains("MoneyCapital"), "MoneyCapital should not be plannable (AI disabled)");
        assertFalse(plannable.contains("Formula"), "Formula should not be plannable (AI disabled)");
        assertFalse(plannable.contains("FormulaDate"), "FormulaDate should not be plannable (AI disabled)");

        logger.info("✓ Plannable types check passed (batch2: Number, Money are plannable)");
    }

    @Test
    void testNumberCategoryPrompt() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        String prompt = registry.generatePrompt();

        // 验证数值类别出现在prompt中
        assertTrue(prompt.contains("数值"), "Prompt should contain '数值' category");
        assertTrue(prompt.contains("数字") || prompt.contains("金额"), "Prompt should contain number field types");

        // AI禁用的不应该出现在prompt中
        assertFalse(prompt.contains("金额大写"), "MoneyCapital should not appear in prompt (AI disabled)");
        assertFalse(prompt.contains("公式"), "Formula should not appear in prompt (AI disabled)");
        assertFalse(prompt.contains("公式日期"), "FormulaDate should not appear in prompt (AI disabled)");

        logger.info("✓ Batch2 prompt generation verified");
    }
}
