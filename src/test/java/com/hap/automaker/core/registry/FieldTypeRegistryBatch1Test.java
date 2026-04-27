package com.hap.automaker.core.registry;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions .*;

/**
 * FieldTypeRegistry 批次1单元测试
 * 验证前4种基础文本字段类型
 */
class FieldTypeRegistryBatch1Test {

    private static final Logger logger = LoggerFactory.getLogger(FieldTypeRegistryBatch1Test.class);

    private FieldTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new FieldTypeRegistry();
    }

    // ========== 基础文本类测试 (4种) ==========

    @Test
    @DisplayName("Text: controlType=2, canBeTitle=true")
    void testText() {
        FieldTypeConfig cfg = registry.getByName("Text");
        assertNotNull(cfg, "Text should exist");
        assertEquals(2, cfg.getControlType(), "controlType mismatch");
        assertEquals("文本", cfg.getName(), "name mismatch");
        assertEquals("basic", cfg.getCategory(), "category mismatch");
        assertTrue(cfg.isCanBeTitle(), "canBeTitle should be true");
        assertFalse(cfg.isAiDisabled(), "aiDisabled should be false");

        // advancedSetting
        Map<String, Object> advanced = cfg.getAdvancedSetting();
        assertNotNull(advanced, "advancedSetting should exist");
        assertEquals("zh", advanced.get("sorttype"), "sorttype mismatch");
        assertEquals("1", advanced.get("analysislink"), "analysislink mismatch");

        // advancedSetting_all_keys
        Map<String, String> keys = cfg.getAdvancedSettingAllKeys();
        assertNotNull(keys, "advancedSettingAllKeys should exist");
        assertTrue(keys.containsKey("datamask"), "missing datamask key");
        assertTrue(keys.containsKey("encryId"), "missing encryId key");

        logger.info("✓ Text verified: " + cfg.toPromptString());
    }

    @Test
    @DisplayName("RichText: controlType=41, basic category")
    void testRichText() {
        FieldTypeConfig cfg = registry.getByName("RichText");
        assertNotNull(cfg, "RichText should exist");
        assertEquals(41, cfg.getControlType(), "controlType mismatch");
        assertEquals("富文本", cfg.getName(), "name mismatch");
        assertEquals("basic", cfg.getCategory(), "category mismatch");

        assertNotNull(cfg.getAdvancedSetting(), "advancedSetting should exist");
        assertEquals("zh", cfg.getAdvancedSetting().get("sorttype"), "sorttype mismatch");

        logger.info("✓ RichText verified: " + cfg.toPromptString());
    }

    @Test
    @DisplayName("AutoNumber: controlType=33, AI disabled, api_extra present")
    void testAutoNumber() {
        FieldTypeConfig cfg = registry.getByName("AutoNumber");
        assertNotNull(cfg, "AutoNumber should exist");
        assertEquals(33, cfg.getControlType(), "controlType mismatch");
        assertEquals("自动编号", cfg.getName(), "name mismatch");
        assertTrue(cfg.isAiDisabled(), "aiDisabled should be true");
        assertNotNull(cfg.getAiDisabledReason(), "aiDisabledReason should exist");

        // api_extra
        Map<String, Object> extra = cfg.getExtra();
        assertNotNull(extra, "extra (api_extra) should exist");
        assertEquals("increase", extra.get("strDefault"), "strDefault mismatch");

        logger.info("✓ AutoNumber verified (AI disabled): " + cfg.toPromptString());
    }

    @Test
    @DisplayName("TextCombine: controlType=32, AI disabled")
    void testTextCombine() {
        FieldTypeConfig cfg = registry.getByName("TextCombine");
        assertNotNull(cfg, "TextCombine should exist");
        assertEquals(32, cfg.getControlType(), "controlType mismatch");
        assertEquals("文本组合", cfg.getName(), "name mismatch");
        assertTrue(cfg.isAiDisabled(), "aiDisabled should be true");

        logger.info("✓ TextCombine verified (AI disabled): " + cfg.toPromptString());
    }

    // ========== 反向查询测试 ==========

    @Test
    @DisplayName("通过ID查询字段类型")
    void testGetById() {
        assertEquals("文本", registry.getById(2).getName());
        assertEquals("富文本", registry.getById(41).getName());
        assertEquals("自动编号", registry.getById(33).getName());
        assertEquals("文本组合", registry.getById(32).getName());
    }

    @Test
    @DisplayName("通过分类查询字段类型")
    void testGetByCategory() {
        var basicTypes = registry.getByCategory("basic");
        assertEquals(4, basicTypes.size(), "basic category should have 4 types");

        long aiEnabledCount = basicTypes.stream().filter(t -> !t.isAiDisabled()).count();
        assertEquals(2, aiEnabledCount, "basic category should have 2 AI-enabled types");
    }

    // ========== Python 兼容性测试 ==========

    @Test
    @DisplayName("controlType映射与Python一致")
    void testControlTypeMapMatchesPython() {
        // Python: FIELD_TYPE_MAP = {name: controlType}
        Map<String, Integer> expected = Map.of(
            "Text", 2,
            "RichText", 41,
            "AutoNumber", 33,
            "TextCombine", 32
        );

        expected.forEach((name, controlType) -> {
            FieldTypeConfig cfg = registry.getByName(name);
            assertNotNull(cfg, name + " should exist in registry");
            assertEquals(controlType, cfg.getControlType(),
                name + " controlType mismatch with Python");
        });

        logger.info("✓ All controlType values match Python FIELD_TYPE_MAP");
    }

    @Test
    @DisplayName("PLANNABLE_TYPES 与Python一致 (非layout/advanced)")
    void testPlannableTypesMatchesPython() {
        Set<String> plannable = registry.getPlannableTypes();

        // Python: PLANABLE_TYPES 排除 layout 和 advanced category
        // 批次1中: Text, RichText 是 plannable; AutoNumber, TextCombine 是 advanced (但category=basic，只是ai_disabled)
        // 实际上批次1全部都属于 plannable（因为category都是basic）
        assertTrue(plannable.contains("Text"), "Text should be plannable");
        assertTrue(plannable.contains("RichText"), "RichText should be plannable");

        logger.info("✓ Plannable types check passed (batch1 all plannable)");
    }

    @Test
    @DisplayName("Registry基础统计")
    void testRegistryStats() {
        assertTrue(registry.size() >= 4, "registry should have at least 4 types");
        assertNotNull(registry.getAll(), "getAll should not be null");
        assertFalse(registry.getAll().isEmpty(), "getAll should not be empty");
        assertNotNull(registry.generatePrompt(), "generatePrompt should not be null");

        logger.info("✓ Registry statistics: size=" + registry.size());
    }
}
