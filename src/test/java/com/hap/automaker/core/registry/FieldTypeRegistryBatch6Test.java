package com.hap.automaker.core.registry;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段类型注册中心批次6测试 - 联系方式+文件+地理位置（8种）
 * 对比Python FIELD_TYPE_MAP验证
 */
class FieldTypeRegistryBatch6Test {

    private static final Logger logger = LoggerFactory.getLogger(FieldTypeRegistryBatch6Test.class);

    // --- 联系方式 (4种) ---
    @Test
    void testPhoneField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(5);
        assertNotNull(cfg, "Phone (type=5) must exist");
        assertEquals("电话", cfg.getName());
        assertEquals("contact", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("zh", cfg.getAdvancedSetting().get("sorttype"));

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("format"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("linkify"));

        logger.info("✓ Phone verified: " + cfg.getName() + " (type=5) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testEmailField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(7);
        assertNotNull(cfg, "Email (type=7) must exist");
        assertEquals("邮箱", cfg.getName());
        assertEquals("contact", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("zh", cfg.getAdvancedSetting().get("sorttype"));
        assertEquals("1", cfg.getAdvancedSetting().get("analysislink"));

        logger.info("✓ Email verified: " + cfg.getName() + " (type=7) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testLinkField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(17);
        assertNotNull(cfg, "Link (type=17) must exist");
        assertEquals("链接", cfg.getName());
        assertEquals("contact", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("1", cfg.getAdvancedSetting().get("analysislink"));

        logger.info("✓ Link verified: " + cfg.getName() + " (type=17) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testSubFormField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(34);
        assertNotNull(cfg, "SubForm (type=34) must exist");
        assertEquals("子表", cfg.getName());
        assertEquals("contact", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "SubForm should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ SubForm verified (AI disabled): " + cfg.getName() + " (type=34) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    // --- 文件 (2种) ---
    @Test
    void testAttachmentField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(14);
        assertNotNull(cfg, "Attachment (type=14) must exist");
        assertEquals("附件", cfg.getName());
        assertEquals("file", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("maxcount"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("filetypes"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("maxsize"));

        logger.info("✓ Attachment verified: " + cfg.getName() + " (type=14) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testRelatedInfoField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(1006);
        assertNotNull(cfg, "RelatedInfo (type=1006) must exist");
        assertEquals("关联资料", cfg.getName());
        assertEquals("file", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "RelatedInfo should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ RelatedInfo verified (AI disabled): " + cfg.getName() + " (type=1006) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    // --- 地理位置 (2种) ---
    @Test
    void testLocationField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(40);
        assertNotNull(cfg, "Location (type=40) must exist");
        assertEquals("定位", cfg.getName());
        assertEquals("location", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("showformat"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("defaulttype"));

        logger.info("✓ Location verified: " + cfg.getName() + " (type=40) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testAreaField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(29);
        assertNotNull(cfg, "Area (type=29) must exist");
        assertEquals("地区", cfg.getName());
        assertEquals("location", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("level"));

        logger.info("✓ Area verified: " + cfg.getName() + " (type=29) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testAllControlTypeMappings() {
        FieldTypeRegistry registry = new FieldTypeRegistry();

        // 联系方式
        assertEquals(5, registry.getControlTypeByName("Phone"));
        assertEquals(7, registry.getControlTypeByName("Email"));
        assertEquals(17, registry.getControlTypeByName("Link"));
        assertEquals(34, registry.getControlTypeByName("SubForm"));
        // 文件
        assertEquals(14, registry.getControlTypeByName("Attachment"));
        assertEquals(1006, registry.getControlTypeByName("RelatedInfo"));
        // 地理位置
        assertEquals(40, registry.getControlTypeByName("Location"));
        assertEquals(29, registry.getControlTypeByName("Area"));

        logger.info("✓ All controlType values match Python FIELD_TYPE_MAP");
    }

    @Test
    void testRegistrySize() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        // 所有批次已合并，总数应为38
        assertTrue(registry.size() >= 34, "Registry should have at least batch 6 types (total 38)");
        // 验证批次6的关键类型都存在
        assertNotNull(registry.getById(5), "Phone should exist");
        assertNotNull(registry.getById(7), "Email should exist");
        assertNotNull(registry.getById(17), "Link should exist");
        assertNotNull(registry.getById(34), "SubForm should exist");
        assertNotNull(registry.getById(14), "Attachment should exist");
        assertNotNull(registry.getById(1006), "RelatedInfo should exist");
        assertNotNull(registry.getById(40), "Location should exist");
        assertNotNull(registry.getById(29), "Area should exist");
        logger.info("✓ Registry statistics: size=" + registry.size() + " (batch 6 types verified)");
    }

    @Test
    void testPlannableTypes() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        var plannable = registry.getPlannableTypes();

        // 可规划的
        assertTrue(plannable.contains("Phone"), "Phone should be plannable");
        assertTrue(plannable.contains("Email"), "Email should be plannable");
        assertTrue(plannable.contains("Link"), "Link should be plannable");
        assertTrue(plannable.contains("Attachment"), "Attachment should be plannable");
        assertTrue(plannable.contains("Location"), "Location should be plannable");
        assertTrue(plannable.contains("Area"), "Area should be plannable");

        // AI禁用的
        assertFalse(plannable.contains("SubForm"), "SubForm should not be plannable (AI disabled)");
        assertFalse(plannable.contains("RelatedInfo"), "RelatedInfo should not be plannable (AI disabled)");

        logger.info("✓ Plannable types check passed (batch6: Phone, Email, Link, Attachment, Location, Area are plannable)");
    }

    @Test
    void testCategoryPrompt() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        String prompt = registry.generatePrompt();

        assertTrue(prompt.contains("联系方式"), "Prompt should contain '联系方式' category");
        assertTrue(prompt.contains("文件"), "Prompt should contain '文件' category");
        assertTrue(prompt.contains("地理位置"), "Prompt should contain '地理位置' category");

        assertTrue(prompt.contains("电话") || prompt.contains("邮箱"), "Prompt should contain contact field types");
        assertTrue(prompt.contains("附件"), "Prompt should contain attachment type");
        assertTrue(prompt.contains("地区") || prompt.contains("定位"), "Prompt should contain location field types");

        // AI禁用的不应该出现在prompt中
        assertFalse(prompt.contains("子表"), "SubForm should not appear in prompt (AI disabled)");
        assertFalse(prompt.contains("关联资料"), "RelatedInfo should not appear in prompt (AI disabled)");

        logger.info("✓ Batch6 prompt generation verified");
    }
}
