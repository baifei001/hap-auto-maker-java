package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.model.AiAuthConfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 角色规划器测试
 */
class RolePlannerTest {

    @Test
    void testGetName() {
        AiTextClient mockClient = new AiTextClient() {
            @Override
            public String generateJson(String prompt, AiAuthConfig config) { return "{}"; }
        };
        RolePlanner planner = new RolePlanner(mockClient);
        assertEquals("RolePlanner", planner.getName());
    }

    @Test
    void testInputOutputAccessors() {
        List<RolePlanner.WorksheetInfo> worksheets = List.of(
            new RolePlanner.WorksheetInfo("订单表", "记录销售订单"),
            new RolePlanner.WorksheetInfo("客户表", "记录客户信息")
        );

        RolePlanner.Input input = new RolePlanner.Input("销售管理", "用于管理销售订单和客户关系", worksheets);

        assertEquals("销售管理", input.getAppName());
        assertEquals("用于管理销售订单和客户关系", input.getAppDescription());
        assertEquals(2, input.getWorksheets().size());
        assertEquals("订单表", input.getWorksheets().get(0).getName());
        assertEquals("记录销售订单", input.getWorksheets().get(0).getPurpose());
    }

    @Test
    void testEmptyWorksheets() {
        RolePlanner.Input input = new RolePlanner.Input("空应用", "描述", List.of());
        assertEquals("空应用", input.getAppName());
        assertTrue(input.getWorksheets().isEmpty());
    }

    @Test
    void testWorksheetPermissionAccessors() {
        RolePlanner.WorksheetPermission perm = new RolePlanner.WorksheetPermission(
            true,   // view
            true,   // add
            true,   // edit
            false,  // delete
            true,   // import
            true,   // export
            false   // share
        );

        assertTrue(perm.canView());
        assertTrue(perm.canAdd());
        assertTrue(perm.canEdit());
        assertFalse(perm.canDelete());
        assertTrue(perm.canImport());
        assertTrue(perm.canExport());
        assertFalse(perm.canShare());
    }

    @Test
    void testFeaturePermissionsAccessors() {
        RolePlanner.FeaturePermissions fp = new RolePlanner.FeaturePermissions(
            true,   // appSettings
            true,   // userManagement
            true,   // roleManagement
            false,  // workflowManagement
            true,   // reportManagement
            true    // apiAccess
        );

        assertTrue(fp.canAppSettings());
        assertTrue(fp.canUserManagement());
        assertTrue(fp.canRoleManagement());
        assertFalse(fp.canWorkflowManagement());
        assertTrue(fp.canReportManagement());
        assertTrue(fp.canApiAccess());
    }

    @Test
    void testFeaturePermissionsDefaultValues() {
        RolePlanner.FeaturePermissions fp = new RolePlanner.FeaturePermissions();

        assertFalse(fp.canAppSettings());
        assertFalse(fp.canUserManagement());
        assertFalse(fp.canRoleManagement());
        assertFalse(fp.canWorkflowManagement());
        assertTrue(fp.canReportManagement());
        assertFalse(fp.canApiAccess());
    }

    @Test
    void testRolePlanOutputAccessors() {
        Map<String, RolePlanner.WorksheetPermission> wsPerms = Map.of(
            "订单表", new RolePlanner.WorksheetPermission(true, true, true, false, true, true, false),
            "客户表", new RolePlanner.WorksheetPermission(true, true, true, false, true, true, false)
        );

        RolePlanner.FeaturePermissions featurePerms = new RolePlanner.FeaturePermissions(
            false, false, false, false, true, false
        );

        RolePlanner.RolePlan role = new RolePlanner.RolePlan(
            "销售人员",
            "负责管理订单和客户信息",
            "editor",
            wsPerms,
            featurePerms
        );

        assertEquals("销售人员", role.getName());
        assertEquals("负责管理订单和客户信息", role.getDescription());
        assertEquals("editor", role.getRoleType());
        assertEquals(2, role.getWorksheetPermissions().size());
        assertNotNull(role.getFeaturePermissions());

        // 验证工作表权限
        RolePlanner.WorksheetPermission orderPerm = role.getWorksheetPermissions().get("订单表");
        assertNotNull(orderPerm);
        assertTrue(orderPerm.canView());
        assertTrue(orderPerm.canAdd());
        assertTrue(orderPerm.canEdit());
        assertFalse(orderPerm.canDelete());
    }

    @Test
    void testRolePlanWithNullPermissions() {
        RolePlanner.RolePlan role = new RolePlanner.RolePlan(
            "测试角色",
            "测试",
            "custom",
            null,
            null
        );

        assertNotNull(role.getWorksheetPermissions());
        assertTrue(role.getWorksheetPermissions().isEmpty());
        assertNotNull(role.getFeaturePermissions());
    }

    @Test
    void testOutputAccessors() {
        List<RolePlanner.RolePlan> roles = List.of(
            new RolePlanner.RolePlan(
                "管理员",
                "系统管理员",
                "admin",
                Map.of(),
                new RolePlanner.FeaturePermissions(true, true, true, true, true, true)
            )
        );

        RolePlanner.Output output = new RolePlanner.Output("测试应用", roles);

        assertEquals("测试应用", output.getAppName());
        assertEquals(1, output.getRoles().size());
        assertEquals("管理员", output.getRoles().get(0).getName());
    }

    @Test
    void testAllRoleTypes() {
        String[] roleTypes = {"admin", "editor", "viewer", "custom"};

        for (String roleType : roleTypes) {
            RolePlanner.RolePlan role = new RolePlanner.RolePlan(
                "测试-" + roleType,
                "测试角色",
                roleType,
                Map.of(),
                new RolePlanner.FeaturePermissions()
            );
            assertEquals(roleType, role.getRoleType());
        }
    }
}
