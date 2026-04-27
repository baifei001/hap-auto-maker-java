package com.hap.automaker.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.planner.RolePlanner;
import com.hap.automaker.core.executor.ExecuteOptions;
import com.hap.automaker.core.executor.RoleCreator;

/**
 * 角色流水线服务
 *
 * 整合 RolePlanner + RoleCreator
 * 实现 Wave 3: 角色规划与创建
 */
public final class RolePipelineService implements RolePipelineRunner {

    private final RolePlanner rolePlanner;
    private final RoleCreator roleCreator;

    public RolePipelineService() {
        this(
            new RolePlanner(new HttpAiTextClient()),
            new RoleCreator(new HapApiClient())
        );
    }

    RolePipelineService(
            RolePlanner rolePlanner,
            RoleCreator roleCreator) {
        this.rolePlanner = rolePlanner;
        this.roleCreator = roleCreator;
    }

    @Override
    public RolePipelineResult run(
            Path repoRoot,
            String appId,
            Path appAuth,
            Path worksheetCreateResult,
            Path rolePlanOutput,
            Path roleResultOutput) throws Exception {

        OffsetDateTime startedAt = OffsetDateTime.now();

        // 读取工作表创建结果以获取应用信息
        JsonNode worksheetResult = Jacksons.mapper().readTree(worksheetCreateResult.toFile());
        String appName = worksheetResult.path("appName").asText();

        // 读取应用描述（如果有）
        String appDescription = readAppDescription(repoRoot, appId);

        // 构建 WorksheetInfo 列表
        List<RolePlanner.WorksheetInfo> worksheets = buildWorksheetInfos(worksheetResult);

        // Step 1: 规划角色
        RolePlanner.Input planInput = new RolePlanner.Input(
            appName,
            appDescription,
            worksheets
        );
        RolePlanner.Output planOutput = rolePlanner.plan(planInput);

        // 保存角色规划结果
        ObjectNode planJson = buildRolePlanJson(appId, appName, planOutput);
        Files.createDirectories(rolePlanOutput.getParent());
        Jacksons.mapper().writeValue(rolePlanOutput.toFile(), planJson);

        // Step 2: 创建角色（如果不是 dry-run）
        // 使用正确的 appId 而非 plan.getAppName()
        RolePlanner.Output planWithAppId = new RolePlanner.Output(appId, planOutput.getRoles());
        RoleCreator.Result createResult = roleCreator.execute(
            planWithAppId,
            new ExecuteOptions(false, false)
        );

        // 构建结果
        ObjectNode resultJson = buildRoleResultJson(appId, createResult);
        Files.createDirectories(roleResultOutput.getParent());
        Jacksons.mapper().writeValue(roleResultOutput.toFile(), resultJson);

        return new RolePipelineResult(
            rolePlanOutput,
            roleResultOutput,
            planOutput.getRoles().size(),
            createResult.getCreatedCount(),
            createResult.getSkippedCount(),
            createResult.getFailedCount(),
            startedAt,
            OffsetDateTime.now()
        );
    }

    private String readAppDescription(Path repoRoot, String appId) {
        try {
            Path specPath = repoRoot.resolve("data").resolve("outputs")
                .resolve("app_runs").resolve(appId).resolve("requirement_spec.json");
            if (Files.exists(specPath)) {
                JsonNode spec = Jacksons.mapper().readTree(specPath.toFile());
                return spec.path("app").path("description").asText("");
            }
        } catch (Exception e) {
            // 忽略错误，返回空描述
        }
        return "";
    }

    private List<RolePlanner.WorksheetInfo> buildWorksheetInfos(JsonNode worksheetResult) {
        List<RolePlanner.WorksheetInfo> result = new ArrayList<>();
        ArrayNode worksheets = (ArrayNode) worksheetResult.path("created_worksheets");
        Iterator<JsonNode> elements = worksheets.elements();
        while (elements.hasNext()) {
            JsonNode ws = elements.next();
            result.add(new RolePlanner.WorksheetInfo(
                ws.path("name").asText(),
                ws.path("purpose").asText("")
            ));
        }
        return result;
    }

    private ObjectNode buildRolePlanJson(String appId, String appName, RolePlanner.Output planOutput) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("appName", appName);
        result.put("schemaVersion", "role_plan_v1");

        ArrayNode rolesArray = result.putArray("roles");
        for (RolePlanner.RolePlan role : planOutput.getRoles()) {
            ObjectNode roleNode = rolesArray.addObject();
            roleNode.put("name", role.getName());
            roleNode.put("description", role.getDescription());
            roleNode.put("roleType", role.getRoleType());

            // 工作表权限
            ObjectNode wsPermsNode = roleNode.putObject("worksheetPermissions");
            role.getWorksheetPermissions().forEach((wsName, perm) -> {
                ObjectNode permNode = wsPermsNode.putObject(wsName);
                permNode.put("view", perm.canView());
                permNode.put("add", perm.canAdd());
                permNode.put("edit", perm.canEdit());
                permNode.put("delete", perm.canDelete());
                permNode.put("import", perm.canImport());
                permNode.put("export", perm.canExport());
                permNode.put("share", perm.canShare());
            });

            // 功能权限
            RolePlanner.FeaturePermissions fp = role.getFeaturePermissions();
            ObjectNode featPermsNode = roleNode.putObject("featurePermissions");
            featPermsNode.put("appSettings", fp.canAppSettings());
            featPermsNode.put("userManagement", fp.canUserManagement());
            featPermsNode.put("roleManagement", fp.canRoleManagement());
            featPermsNode.put("workflowManagement", fp.canWorkflowManagement());
            featPermsNode.put("reportManagement", fp.canReportManagement());
            featPermsNode.put("apiAccess", fp.canApiAccess());
        }

        return result;
    }

    private ObjectNode buildRoleResultJson(String appId, RoleCreator.Result createResult) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("createdAt", OffsetDateTime.now().toString());
        result.put("totalPlanned", createResult.getCreatedCount() + createResult.getSkippedCount() + createResult.getFailedCount());
        result.put("createdCount", createResult.getCreatedCount());
        result.put("skippedCount", createResult.getSkippedCount());
        result.put("failedCount", createResult.getFailedCount());
        result.put("success", createResult.isSuccess());

        ArrayNode createdArray = result.putArray("created");
        for (RoleCreator.CreatedRole role : createResult.getCreated()) {
            ObjectNode node = createdArray.addObject();
            node.put("name", role.getName());
            node.put("roleId", role.getRoleId());
        }

        ArrayNode skippedArray = result.putArray("skipped");
        for (RoleCreator.SkippedRole role : createResult.getSkipped()) {
            ObjectNode node = skippedArray.addObject();
            node.put("name", role.getName());
            node.put("reason", role.getReason());
            node.put("existingRoleId", role.getExistingRoleId());
        }

        ArrayNode failedArray = result.putArray("failed");
        for (RoleCreator.FailedRole role : createResult.getFailed()) {
            ObjectNode node = failedArray.addObject();
            node.put("index", role.getIndex());
            node.put("name", role.getName());
            node.put("error", role.getError());
        }

        return result;
    }

    public record RolePipelineResult(
            Path planOutputPath,
            Path resultOutputPath,
            int totalRoles,
            int createdRoles,
            int skippedRoles,
            int failedRoles,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt
    ) {
        public ObjectNode summary() {
            ObjectNode node = Jacksons.mapper().createObjectNode();
            node.put("planOutputPath", planOutputPath.toString());
            node.put("resultOutputPath", resultOutputPath.toString());
            node.put("totalRoles", totalRoles);
            node.put("createdRoles", createdRoles);
            node.put("skippedRoles", skippedRoles);
            node.put("failedRoles", failedRoles);
            node.put("durationMs", endedAt.toInstant().toEpochMilli() - startedAt.toInstant().toEpochMilli());
            return node;
        }
    }
}
