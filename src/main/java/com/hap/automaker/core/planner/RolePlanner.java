package com.hap.automaker.core.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 角色规划器
 *
 * Python 对应: planning/role_planner.py
 *
 * 职责:
 * - AI 规划应用角色结构和权限配置
 * - 生成角色定义（管理员、普通成员、只读等）
 * - 为每个角色规划权限（工作表权限、功能权限）
 *
 * 角色类型:
 * - admin: 管理员（所有权限）
 * - editor: 编辑者（读写权限，无管理权限）
 * - viewer: 只读用户（仅查看权限）
 * - custom: 自定义角色（根据业务场景定制）
 */
public class RolePlanner implements Planner<RolePlanner.Input, RolePlanner.Output> {

    private final AiTextClient aiClient;

    private static final int MAX_RETRIES = 3;
    private static final Logger logger = LoggerFactory.getLogger(RolePlanner.class);

    public RolePlanner(AiTextClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public String getName() {
        return "RolePlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            String prompt = buildPrompt(input);
            String responseJson = callAiWithRetry(prompt);

            JsonNode root = Jacksons.mapper().readTree(responseJson);
            JsonNode rolesNode = root.path("roles");

            List<RolePlan> roles = new ArrayList<>();
            if (rolesNode.isArray()) {
                for (JsonNode roleNode : rolesNode) {
                    String roleName = roleNode.path("name").asText();
                    String description = roleNode.path("description").asText();
                    String roleType = roleNode.path("role_type").asText("custom");

                    // 解析工作表权限
                    Map<String, WorksheetPermission> worksheetPermissions = new HashMap<>();
                    JsonNode wsPermsNode = roleNode.path("worksheet_permissions");
                    if (wsPermsNode.isObject()) {
                        wsPermsNode.fields().forEachRemaining(entry -> {
                            String wsName = entry.getKey();
                            JsonNode permNode = entry.getValue();
                            worksheetPermissions.put(wsName, new WorksheetPermission(
                                permNode.path("view").asBoolean(true),
                                permNode.path("add").asBoolean(false),
                                permNode.path("edit").asBoolean(false),
                                permNode.path("delete").asBoolean(false),
                                permNode.path("import").asBoolean(false),
                                permNode.path("export").asBoolean(false),
                                permNode.path("share").asBoolean(false)
                            ));
                        });
                    }

                    // 解析功能权限
                    FeaturePermissions featurePermissions = parseFeaturePermissions(roleNode.path("feature_permissions"));

                    roles.add(new RolePlan(roleName, description, roleType,
                                          worksheetPermissions, featurePermissions));
                }
            }

            // 验证至少有一个角色
            if (roles.isEmpty()) {
                logger.warn("[warn] No roles planned, using defaults");
                roles = createDefaultRoles(input.getWorksheets());
            }

            logger.info("✓ 角色规划完成: {} 个角色", roles.size());
            return new Output(input.getAppName(), roles);

        } catch (Exception e) {
            logger.error("[error] Role planning failed: {}", e.getMessage());
            // 返回默认角色
            List<RolePlan> defaultRoles = createDefaultRoles(input.getWorksheets());
            return new Output(input.getAppName(), defaultRoles);
        }
    }

    private String buildPrompt(Input input) {
        StringBuilder sb = new StringBuilder();

        sb.append("Plan roles and permissions for HAP application \"").append(input.getAppName()).append("\".\n\n");

        sb.append("## Application Description\n");
        sb.append(input.getAppDescription()).append("\n\n");

        sb.append("## Worksheets\n");
        for (WorksheetInfo ws : input.getWorksheets()) {
            sb.append("- ").append(ws.getName()).append(": ").append(ws.getPurpose()).append("\n");
        }
        sb.append("\n");

        sb.append("## Role Types Reference\n");
        sb.append("- admin: Full permissions (view/edit/delete all worksheets, manage settings)\n");
        sb.append("- editor: Read/write permissions on relevant worksheets\n");
        sb.append("- viewer: Read-only access to relevant worksheets\n");
        sb.append("- custom: Business-specific role with tailored permissions\n\n");

        sb.append("## Permission Levels\n");
        sb.append("For each worksheet, permissions include: view, add, edit, delete, import, export, share\n\n");

        sb.append("## Task\n");
        sb.append("Plan 2-5 roles with appropriate permissions based on the application type.\n");
        sb.append("Consider common business scenarios:\n");
        sb.append("- Who creates records?\n");
        sb.append("- Who approves/modifies records?\n");
        sb.append("- Who only needs to view/report?\n");
        sb.append("- Who manages configuration?\n\n");

        sb.append("Return strict JSON only:\n");
        sb.append("{\n");
        sb.append("  \"roles\": [\n");
        sb.append("    {\n");
        sb.append("      \"name\": \"Role Name\",\n");
        sb.append("      \"description\": \"Role description and responsibilities\",\n");
        sb.append("      \"role_type\": \"admin|editor|viewer|custom\",\n");
        sb.append("      \"worksheet_permissions\": {\n");
        sb.append("        \"Worksheet Name\": {\n");
        sb.append("          \"view\": true,\n");
        sb.append("          \"add\": true,\n");
        sb.append("          \"edit\": true,\n");
        sb.append("          \"delete\": false,\n");
        sb.append("          \"import\": true,\n");
        sb.append("          \"export\": true,\n");
        sb.append("          \"share\": false\n");
        sb.append("        }\n");
        sb.append("      },\n");
        sb.append("      \"feature_permissions\": {\n");
        sb.append("        \"app_settings\": false,\n");
        sb.append("        \"user_management\": false,\n");
        sb.append("        \"role_management\": false,\n");
        sb.append("        \"workflow_management\": false,\n");
        sb.append("        \"report_management\": true,\n");
        sb.append("        \"api_access\": true\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("Rules:\n");
        sb.append("1. role_type should be 'admin', 'editor', 'viewer', or 'custom'\n");
        sb.append("2. Provide worksheet_permissions for ALL worksheets in the app\n");
        sb.append("3. admin role should have all permissions set to true\n");
        sb.append("4. viewer role should have only 'view' permission set to true\n");
        sb.append("5. editor role should have view/add/edit but limited delete\n");
        sb.append("6. Role names should be clear (e.g., '管理员', '销售人员', '查看用户')\n");

        return sb.toString();
    }

    private FeaturePermissions parseFeaturePermissions(JsonNode node) {
        if (!node.isObject()) {
            return new FeaturePermissions(false, false, false, false, true, false);
        }

        return new FeaturePermissions(
            node.path("app_settings").asBoolean(false),
            node.path("user_management").asBoolean(false),
            node.path("role_management").asBoolean(false),
            node.path("workflow_management").asBoolean(false),
            node.path("report_management").asBoolean(true),
            node.path("api_access").asBoolean(false)
        );
    }

    private List<RolePlan> createDefaultRoles(List<WorksheetInfo> worksheets) {
        List<RolePlan> roles = new ArrayList<>();

        // Default Admin role
        Map<String, WorksheetPermission> adminWsPerms = new HashMap<>();
        for (WorksheetInfo ws : worksheets) {
            adminWsPerms.put(ws.getName(), new WorksheetPermission(true, true, true, true, true, true, true));
        }
        roles.add(new RolePlan("管理员", "系统管理员，拥有所有权限", "admin",
                            adminWsPerms, new FeaturePermissions(true, true, true, true, true, true)));

        // Default Editor role
        Map<String, WorksheetPermission> editorWsPerms = new HashMap<>();
        for (WorksheetInfo ws : worksheets) {
            editorWsPerms.put(ws.getName(), new WorksheetPermission(true, true, true, false, true, true, false));
        }
        roles.add(new RolePlan("编辑者", "可以编辑数据但无法管理配置", "editor",
                            editorWsPerms, new FeaturePermissions(false, false, false, false, true, false)));

        // Default Viewer role
        Map<String, WorksheetPermission> viewerWsPerms = new HashMap<>();
        for (WorksheetInfo ws : worksheets) {
            viewerWsPerms.put(ws.getName(), new WorksheetPermission(true, false, false, false, false, true, false));
        }
        roles.add(new RolePlan("查看者", "只读访问权限", "viewer",
                            viewerWsPerms, new FeaturePermissions(false, false, false, false, true, false)));

        return roles;
    }

    private String callAiWithRetry(String prompt) throws Exception {
        Exception lastError = null;

        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                AiAuthConfig config = ConfigLoader.loadAiConfig();
                return aiClient.generateJson(prompt, config);
            } catch (Exception e) {
                lastError = e;
                logger.error("AI call failed (attempt {}/{}): {}", i + 1, MAX_RETRIES, e.getMessage());
                if (i < MAX_RETRIES - 1) {
                    Thread.sleep(1000 * (i + 1));
                }
            }
        }

        throw new PlanningException(getName(), "AI call failed after " + MAX_RETRIES + " retries", lastError);
    }

    // ========== 输入类 ==========
    public static class Input {
        private final String appName;
        private final String appDescription;
        private final List<WorksheetInfo> worksheets;

        public Input(String appName, String appDescription, List<WorksheetInfo> worksheets) {
            this.appName = appName;
            this.appDescription = appDescription;
            this.worksheets = worksheets != null ? worksheets : List.of();
        }

        public String getAppName() { return appName; }
        public String getAppDescription() { return appDescription; }
        public List<WorksheetInfo> getWorksheets() { return worksheets; }
    }

    public static class WorksheetInfo {
        private final String name;
        private final String purpose;

        public WorksheetInfo(String name, String purpose) {
            this.name = name;
            this.purpose = purpose;
        }

        public String getName() { return name; }
        public String getPurpose() { return purpose; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final String appName;
        private final List<RolePlan> roles;

        public Output(String appName, List<RolePlan> roles) {
            this.appName = appName;
            this.roles = roles;
        }

        public String getAppName() { return appName; }
        public List<RolePlan> getRoles() { return roles; }
    }

    public static class RolePlan {
        private final String name;
        private final String description;
        private final String roleType;
        private final Map<String, WorksheetPermission> worksheetPermissions;
        private final FeaturePermissions featurePermissions;

        public RolePlan(String name, String description, String roleType,
                       Map<String, WorksheetPermission> worksheetPermissions,
                       FeaturePermissions featurePermissions) {
            this.name = name;
            this.description = description;
            this.roleType = roleType;
            this.worksheetPermissions = worksheetPermissions != null ? worksheetPermissions : Map.of();
            this.featurePermissions = featurePermissions != null ? featurePermissions : new FeaturePermissions();
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getRoleType() { return roleType; }
        public Map<String, WorksheetPermission> getWorksheetPermissions() { return worksheetPermissions; }
        public FeaturePermissions getFeaturePermissions() { return featurePermissions; }
    }

    public static class WorksheetPermission {
        private final boolean view;
        private final boolean add;
        private final boolean edit;
        private final boolean delete;
        private final boolean imp; // import
        private final boolean export;
        private final boolean share;

        public WorksheetPermission(boolean view, boolean add, boolean edit, boolean delete,
                                   boolean imp, boolean export, boolean share) {
            this.view = view;
            this.add = add;
            this.edit = edit;
            this.delete = delete;
            this.imp = imp;
            this.export = export;
            this.share = share;
        }

        public boolean canView() { return view; }
        public boolean canAdd() { return add; }
        public boolean canEdit() { return edit; }
        public boolean canDelete() { return delete; }
        public boolean canImport() { return imp; }
        public boolean canExport() { return export; }
        public boolean canShare() { return share; }
    }

    public static class FeaturePermissions {
        private final boolean appSettings;
        private final boolean userManagement;
        private final boolean roleManagement;
        private final boolean workflowManagement;
        private final boolean reportManagement;
        private final boolean apiAccess;

        public FeaturePermissions() {
            this(false, false, false, false, true, false);
        }

        public FeaturePermissions(boolean appSettings, boolean userManagement, boolean roleManagement,
                                boolean workflowManagement, boolean reportManagement, boolean apiAccess) {
            this.appSettings = appSettings;
            this.userManagement = userManagement;
            this.roleManagement = roleManagement;
            this.workflowManagement = workflowManagement;
            this.reportManagement = reportManagement;
            this.apiAccess = apiAccess;
        }

        public boolean canAppSettings() { return appSettings; }
        public boolean canUserManagement() { return userManagement; }
        public boolean canRoleManagement() { return roleManagement; }
        public boolean canWorkflowManagement() { return workflowManagement; }
        public boolean canReportManagement() { return reportManagement; }
        public boolean canApiAccess() { return apiAccess; }
    }
}
