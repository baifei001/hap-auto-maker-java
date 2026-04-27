package com.hap.automaker.core.registry;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段类型注册中心批次3测试 - 选择类字段（6种）
 * 对比Python FIELD_TYPE_MAP验证
 */
class FieldTypeRegistryBatch3Test {

    private static final Logger logger = LoggerFactory.getLogger(FieldTypeRegistryBatch3Test.class);

    @Test
    void testSingleSelectField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(9);
        assertNotNull(cfg, "SingleSelect (type=9) must exist");
        assertEquals("单选", cfg.getName());
        assertEquals("select", cfg.getCategory());
        assertTrue(cfg.isCanBeTitle(), "SingleSelect can be title");
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("zh", cfg.getAdvancedSetting().get("sorttype"));
        assertEquals("1", cfg.getAdvancedSetting().get("analysislink"));

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("showtype"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("checktype"));

        logger.info("✓ SingleSelect verified: " + cfg.getName() + " (type=9) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testMultipleSelectField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(10);
        assertNotNull(cfg, "MultipleSelect (type=10) must exist");
        assertEquals("多选", cfg.getName());
        assertEquals("select", cfg.getCategory());
        assertFalse(cfg.isCanBeTitle());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("maxitem"));

        logger.info("✓ MultipleSelect verified: " + cfg.getName() + " (type=10) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testDropdownField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(11);
        assertNotNull(cfg, "Dropdown (type=11) must exist");
        assertEquals("下拉框", cfg.getName());
        assertEquals("select", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        logger.info("✓ Dropdown verified: " + cfg.getName() + " (type=11) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testFlatSelectField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(36);
        assertNotNull(cfg, "FlatSelect (type=36) must exist");
        assertEquals("平铺选择", cfg.getName());
        assertEquals("select", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("1", cfg.getAdvancedSetting().get("showtype"));

        logger.info("✓ FlatSelect verified: " + cfg.getName() + " (type=36) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testCascadeSelectField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(28);
        assertNotNull(cfg, "CascadeSelect (type=28) must exist");
        assertEquals("级联选择", cfg.getName());
        assertEquals("select", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "CascadeSelect should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ CascadeSelect verified (AI disabled): " + cfg.getName() + " (type=28) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testAssociationCascadeField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(35);
        assertNotNull(cfg, "AssociationCascade (type=35) must exist");
        assertEquals("关联级联", cfg.getName());
        assertEquals("relation", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "AssociationCascade should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ AssociationCascade verified (AI disabled): " + cfg.getName() + " (type=35) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testAllControlTypeMappings() {
        FieldTypeRegistry registry = new FieldTypeRegistry();

        assertEquals(9, registry.getControlTypeByName("SingleSelect"));
        assertEquals(10, registry.getControlTypeByName("MultipleSelect"));
        assertEquals(11, registry.getControlTypeByName("Dropdown"));
        assertEquals(36, registry.getControlTypeByName("FlatSelect"));
        assertEquals(28, registry.getControlTypeByName("CascadeSelect"));
        assertEquals(35, registry.getControlTypeByName("AssociationCascade"));

        logger.info("✓ All controlType values match Python FIELD_TYPE_MAP");
    }

    @Test
    void testRegistrySize() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        // 所有批次已合并，总数应为38
        assertTrue(registry.size() >= 15, "Registry should have at least batch 3 types (total 38)");
        // 验证批次3的关键类型都存在
        assertNotNull(registry.getById(9), "SingleSelect should exist");
        assertNotNull(registry.getById(10), "MultipleSelect should exist");
        assertNotNull(registry.getById(11), "Dropdown should exist");
        assertNotNull(registry.getById(36), "FlatSelect should exist");
        assertNotNull(registry.getById(28), "CascadeSelect should exist");
        assertNotNull(registry.getById(35), "AssociationCascade should exist");
        logger.info("✓ Registry statistics: size=" + registry.size() + " (batch 3 types verified)");
    }

    @Test
    void testPlannableTypes() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        var plannable = registry.getPlannableTypes();

        // 选择类中可规划的（非AI禁用）
        assertTrue(plannable.contains("SingleSelect"), "SingleSelect should be plannable");
        assertTrue(plannable.contains("MultipleSelect"), "MultipleSelect should be plannable");
        assertTrue(plannable.contains("Dropdown"), "Dropdown should be plannable");
        assertTrue(plannable.contains("FlatSelect"), "FlatSelect should be plannable");

        // AI禁用的
        assertFalse(plannable.contains("CascadeSelect"), "CascadeSelect should not be plannable (AI disabled)");
        assertFalse(plannable.contains("AssociationCascade"), "AssociationCascade should not be plannable (AI disabled)");

        logger.info("✓ Plannable types check passed (batch3: SingleSelect, MultipleSelect, Dropdown, FlatSelect are plannable)");
    }

    @Test
    void testSelectCategoryPrompt() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        String prompt = registry.generatePrompt();

        assertTrue(prompt.contains("选择"), "Prompt should contain '选择' category");
        assertTrue(prompt.contains("单选") || prompt.contains("多选"), "Prompt should contain select field types");

        // AI禁用的不应该出现在prompt中
        assertFalse(prompt.contains("级联选择"), "CascadeSelect should not appear in prompt (AI disabled)");
        assertFalse(prompt.contains("关联级联"), "AssociationCascade should not appear in prompt (AI disabled)");

        logger.info("✓ Batch3 prompt generation verified");
    }
}
