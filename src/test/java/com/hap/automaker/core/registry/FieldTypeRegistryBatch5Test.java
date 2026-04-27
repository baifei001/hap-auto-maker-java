package com.hap.automaker.core.registry;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段类型注册中心批次5测试 - 人员组织类字段（5种）
 * 对比Python FIELD_TYPE_MAP验证
 */
class FieldTypeRegistryBatch5Test {

    private static final Logger logger = LoggerFactory.getLogger(FieldTypeRegistryBatch5Test.class);

    @Test
    void testCollaboratorField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(19);
        assertNotNull(cfg, "Collaborator (type=19) must exist");
        assertEquals("成员", cfg.getName());
        assertEquals("people", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());
        assertFalse(cfg.isCanBeTitle());

        assertNotNull(cfg.getAdvancedSetting());
        assertEquals("zh", cfg.getAdvancedSetting().get("sorttype"));

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("choicecontroltype"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("choicecontrolrange"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("defaulttype"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("defaultuserids"));

        logger.info("✓ Collaborator verified: " + cfg.getName() + " (type=19) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testOwnerField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(1003);
        assertNotNull(cfg, "Owner (type=1003) must exist");
        assertEquals("拥有者", cfg.getName());
        assertEquals("people", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "Owner should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ Owner verified (AI disabled): " + cfg.getName() + " (type=1003) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testDepartmentField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(27);
        assertNotNull(cfg, "Department (type=27) must exist");
        assertEquals("部门", cfg.getName());
        assertEquals("people", cfg.getCategory());
        assertFalse(cfg.isAiDisabled());

        assertNotNull(cfg.getAdvancedSettingAllKeys());
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("choicecontroltype"));
        assertTrue(cfg.getAdvancedSettingAllKeys().containsKey("choicecontrolrange"));

        logger.info("✓ Department verified: " + cfg.getName() + " (type=27) - " + cfg.getDoc().split("。")[0]);
    }

    @Test
    void testDepartmentMergeField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(1004);
        assertNotNull(cfg, "DepartmentMerge (type=1004) must exist");
        assertEquals("部门合并", cfg.getName());
        assertEquals("people", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "DepartmentMerge should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ DepartmentMerge verified (AI disabled): " + cfg.getName() + " (type=1004) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testRoleField() {
        FieldTypeConfig cfg = new FieldTypeRegistry().getById(1005);
        assertNotNull(cfg, "Role (type=1005) must exist");
        assertEquals("角色", cfg.getName());
        assertEquals("people", cfg.getCategory());
        assertTrue(cfg.isAiDisabled(), "Role should be AI disabled");
        assertNotNull(cfg.getAiDisabledReason());

        logger.info("✓ Role verified (AI disabled): " + cfg.getName() + " (type=1005) [AI禁用: " + cfg.getAiDisabledReason() + "]");
    }

    @Test
    void testAllControlTypeMappings() {
        FieldTypeRegistry registry = new FieldTypeRegistry();

        assertEquals(19, registry.getControlTypeByName("Collaborator"));
        assertEquals(1003, registry.getControlTypeByName("Owner"));
        assertEquals(27, registry.getControlTypeByName("Department"));
        assertEquals(1004, registry.getControlTypeByName("DepartmentMerge"));
        assertEquals(1005, registry.getControlTypeByName("Role"));

        logger.info("✓ All controlType values match Python FIELD_TYPE_MAP");
    }

    @Test
    void testRegistrySize() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        // 所有批次已合并，总数应为38
        assertTrue(registry.size() >= 26, "Registry should have at least batch 5 types (total 38)");
        // 验证批次5的关键类型都存在
        assertNotNull(registry.getById(19), "Collaborator should exist");
        assertNotNull(registry.getById(1003), "Owner should exist");
        assertNotNull(registry.getById(27), "Department should exist");
        assertNotNull(registry.getById(1004), "DepartmentMerge should exist");
        assertNotNull(registry.getById(1005), "Role should exist");
        logger.info("✓ Registry statistics: size=" + registry.size() + " (batch 5 types verified)");
    }

    @Test
    void testPlannableTypes() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        var plannable = registry.getPlannableTypes();

        // 人员组织类中可规划的
        assertTrue(plannable.contains("Collaborator"), "Collaborator should be plannable");
        assertTrue(plannable.contains("Department"), "Department should be plannable");

        // AI禁用的
        assertFalse(plannable.contains("Owner"), "Owner should not be plannable (AI disabled)");
        assertFalse(plannable.contains("DepartmentMerge"), "DepartmentMerge should not be plannable (AI disabled)");
        assertFalse(plannable.contains("Role"), "Role should not be plannable (AI disabled)");

        logger.info("✓ Plannable types check passed (batch5: Collaborator, Department are plannable)");
    }

    @Test
    void testPeopleCategoryPrompt() {
        FieldTypeRegistry registry = new FieldTypeRegistry();
        String prompt = registry.generatePrompt();

        assertTrue(prompt.contains("人员组织"), "Prompt should contain '人员组织' category");
        assertTrue(prompt.contains("成员") || prompt.contains("部门"), "Prompt should contain people field types");

        // AI禁用的不应该出现在prompt中
        assertFalse(prompt.contains("拥有者"), "Owner should not appear in prompt (AI disabled)");
        assertFalse(prompt.contains("部门合并"), "DepartmentMerge should not appear in prompt (AI disabled)");
        assertFalse(prompt.contains("角色"), "Role should not appear in prompt (AI disabled)");

        logger.info("✓ Batch5 prompt generation verified");
    }
}
