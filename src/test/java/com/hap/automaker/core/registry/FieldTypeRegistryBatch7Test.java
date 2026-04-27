package com.hap.automaker.core.registry;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段类型注册中心批次7测试 - 高级/特殊+布局类（4种）
 * 对比Python FIELD_TYPE_MAP验证
 */
class FieldTypeRegistryBatch7Test {

    private static final Logger logger = LoggerFactory.getLogger(FieldTypeRegistryBatch7Test.class);

    @Test
    void testRatingField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(30);
        assertNotNull(cfg, "Rating (type=30) must exist");
        assertEquals("评分", cfg.getName());
        assertEquals("advanced", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("zh", cfg.getAdvancedSetting().get("sorttype"));

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("max"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("allowhalf"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("showtext"));

        logger.info("✓ Rating verified: " + cfg.getName() + " (type=30) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testCheckboxField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(24);
        assertNotNull(cfg, "Checkbox (type=24) must exist");
        assertEquals("复选框", cfg.getName());
        assertEquals("advanced", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("defaultvalue"));

        logger.info("✓ Checkbox verified: " + cfg.getName() + " (type=24) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testRelationField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(20);
        assertNotNull(cfg, "Relation (type=20) must exist");
        assertEquals("关联记录", cfg.getName());
        assertEquals("relation", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "Relation should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("bidirectional"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("subType"));

        logger.info("✓ Relation verified (AI disabled): " + cfg.getName() + " (type=20) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testDividerField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(22);
        assertNotNull(cfg, "Divider (type=22) must exist");
        assertEquals("分割线", cfg.getName());
        assertEquals("layout", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "Divider should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ Divider verified (AI disabled): " + cfg.getName() + " (type=22) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testAllControlTypeMappings() {
        FieldTypeRegistry registry = new FieldTypeRegistry();

        assertEquals(30, registry.getControlTypeByName("Rating"));
        assertEquals(24, registry.getControlTypeByName("Checkbox"));
        assertEquals(20, registry.getControlTypeByName("Relation"));
        assertEquals(22, registry.getControlTypeByName("Divider"));

        logger.info("✓ All controlType values match Python FIELD_TYPE_MAP");
    }

    @Test
    void testRegistrySize() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        // 批次1(4) + 批次2(5) + 批次3(6) + 批次4(6) + 批次5(5) + 批次6(8) + 批次7(4) = 38
        assertEquals(38, registry.size(), "Registry should have 38 types (all batches complete)");
        logger.info("✓ Registry statistics: size=" + registry.size() + " (全部38种字段类型完成)");
    }

    @Test
    void testPlannableTypes() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        var plannable = registry.getPlannableTypes();

        // advanced类别也会被排除（不是可规划字段的概念范畴）
        assertFalse(plannable.contains("Rating"), "Rating should not be plannable (advanced category)");
        assertFalse(plannable.contains("Checkbox"), "Checkbox should not be plannable (advanced category)");

        // layout类别和AI禁用的也不应规划
        assertFalse(plannable.contains("Relation"), "Relation should not be plannable (AI disabled)");
        assertFalse(plannable.contains("Divider"), "Divider should not be plannable (layout category)");

        logger.info("✓ Plannable types check passed (batch7: none are plannable - Rating/Checkbox are advanced category)");
    }

    @Test
    void testCategoryPrompt() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        String prompt = registry.generatePrompt();

        assertTrue(prompt.contains("高级"), "Prompt should contain '高级' category");
        assertTrue(prompt.contains("评分") || prompt.contains("复选框"), "Prompt should contain advanced field types");

        // layout类别和AI禁用的不应该出现在prompt中
        assertFalse(prompt.contains("分割线"), "Divider should not appear in prompt (layout category)");
        assertFalse(prompt.contains("关联记录"), "Relation should not appear in prompt (AI disabled)");

        logger.info("✓ Batch7 prompt generation verified");
    }

    @Test
    void testFinalRegistryCompleteness() {
        FieldTypeRegistry registry = new FieldTypeRegistry();

        // 验证所有批次的关键字段都存在
        assertAll("Complete Registry Verification",
            // 批次1: 基础文本
            () -> assertNotNull(registry.getById(2), "Text"),
            () -> assertNotNull(registry.getById(41), "RichText"),
            () -> assertNotNull(registry.getById(33), "AutoNumber"),
            () -> assertNotNull(registry.getById(32), "TextCombine"),

            // 批次2: 数值
            () -> assertNotNull(registry.getById(6), "Number"),
            () -> assertNotNull(registry.getById(8), "Money"),
            () -> assertNotNull(registry.getById(25), "MoneyCapital"),
            () -> assertNotNull(registry.getById(31), "Formula"),
            () -> assertNotNull(registry.getById(38), "FormulaDate"),

            // 批次3: 选择
            () -> assertNotNull(registry.getById(9), "SingleSelect"),
            () -> assertNotNull(registry.getById(10), "MultipleSelect"),
            () -> assertNotNull(registry.getById(11), "Dropdown"),
            () -> assertNotNull(registry.getById(36), "FlatSelect"),
            () -> assertNotNull(registry.getById(28), "CascadeSelect"),
            () -> assertNotNull(registry.getById(35), "AssociationCascade"),

            // 批次4: 日期时间
            () -> assertNotNull(registry.getById(15), "Date"),
            () -> assertNotNull(registry.getById(16), "DateTime"),
            () -> assertNotNull(registry.getById(46), "Time"),
            () -> assertNotNull(registry.getById(21), "Week"),
            () -> assertNotNull(registry.getById(1001), "CreatedAt"),
            () -> assertNotNull(registry.getById(1002), "ModifiedAt"),

            // 批次5: 人员组织
            () -> assertNotNull(registry.getById(19), "Collaborator"),
            () -> assertNotNull(registry.getById(1003), "Owner"),
            () -> assertNotNull(registry.getById(27), "Department"),
            () -> assertNotNull(registry.getById(1004), "DepartmentMerge"),
            () -> assertNotNull(registry.getById(1005), "Role"),

            // 批次6: 联系方式+文件+地理位置
            () -> assertNotNull(registry.getById(5), "Phone"),
            () -> assertNotNull(registry.getById(7), "Email"),
            () -> assertNotNull(registry.getById(17), "Link"),
            () -> assertNotNull(registry.getById(34), "SubForm"),
            () -> assertNotNull(registry.getById(14), "Attachment"),
            () -> assertNotNull(registry.getById(1006), "RelatedInfo"),
            () -> assertNotNull(registry.getById(40), "Location"),
            () -> assertNotNull(registry.getById(29), "Area"),

            // 批次7: 高级/特殊+布局
            () -> assertNotNull(registry.getById(30), "Rating"),
            () -> assertNotNull(registry.getById(24), "Checkbox"),
            () -> assertNotNull(registry.getById(20), "Relation"),
            () -> assertNotNull(registry.getById(22), "Divider")
        );

        logger.info("✓ All 38 field types verified in registry");
    }
}
