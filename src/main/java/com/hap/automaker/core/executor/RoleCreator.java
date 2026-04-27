package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.planner.RolePlanner;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色创建执行器
 *
 * Python 对应: create_roles_from_recommendation.py
 *
 * 职责:
 * - 根据 RolePlanner 的输出创建应用角色
 * - 检查现有角色避免重复创建
 * - 支持权限配置（工作表权限、功能权限）
 */
public class RoleCreator implements Executor<RolePlanner.Output, RoleCreator.Result> {

    private static final Logger logger = LoggerFactory.getLogger(RoleCreator.class);

    private final HapApiClient apiClient;
    private final ObjectMapper mapper;

    // API 端点
    private static final String GET_ROLES_ENDPOINT = "/v1/open/app/getRoles";
    private static final String CREATE_ROLE_ENDPOINT = "/v1/open/app/createRole";

    public RoleCreator(HapApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = Jacksons.mapper();
    }

    /**
     * 从组织认证配置生成签名
     * 对应 Python: build_sign() in create_app.py / get_app_authorize.py
     */
    private OpenApiAuth generateOpenApiAuth(String appId) throws Exception {
        // 加载组织认证配置
        ConfigLoader.OrgAuthConfig orgAuth = ConfigLoader.loadOrgAuthConfig();

        // 生成签名 (SHA256 -> hex -> base64)
        long timestamp = System.currentTimeMillis();
        String raw = "AppKey=" + orgAuth.getAppKey() + "&SecretKey=" + orgAuth.getSecretKey() + "&Timestamp=" + timestamp;
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        String sign = java.util.Base64.getEncoder().encodeToString(hexString.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        logger.info("  ✓ 生成 Open API 签名 (timestamp={})", timestamp);

        // 调用 getAppAuthorize 获取应用授权信息
        JsonNode authResponse = apiClient.getAppAuthorize(appId, orgAuth.getAppKey(), sign, timestamp, orgAuth.getProjectId());

        // 提取返回的 appKey 和 sign（这些是经过服务器验证的）
        JsonNode dataNode = authResponse.path("data");
        if (dataNode.isArray() && dataNode.size() > 0) {
            JsonNode authData = dataNode.get(0);
            String authorizedAppKey = authData.path("appKey").asText("").trim();
            String authorizedSign = authData.path("sign").asText("").trim();
            String projectId = authData.path("projectId").asText("").trim();

            if (!authorizedAppKey.isEmpty() && !authorizedSign.isEmpty()) {
                logger.info("  ✓ 从 getAppAuthorize 获取应用授权信息");
                return new OpenApiAuth(authorizedAppKey, authorizedSign, projectId);
            }
        }

        throw new RuntimeException("无法从 getAppAuthorize 响应中提取 appKey 和 sign");
    }

    /**
     * 从 app_authorize JSON 文件加载 Open API 认证信息（备用方案）
     */
    private OpenApiAuth loadOpenApiAuthFromFile(String appId) throws Exception {
        // 查找 app_authorize 文件（相对于项目根目录）
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();
        Path authDir = repoRoot.resolve("data/outputs/app_authorizations");
        if (!Files.exists(authDir)) {
            // 尝试当前目录（兼容测试环境）
            authDir = Paths.get("data/outputs/app_authorizations");
        }
        if (!Files.exists(authDir)) {
            throw new IllegalStateException("App auth directory not found: " + authDir + " (repoRoot: " + repoRoot + ")");
        }

        // 遍历查找包含目标 appId 的授权文件
        var files = Files.list(authDir)
            .filter(p -> p.getFileName().toString().startsWith("app_authorize_") && p.toString().endsWith(".json"))
            .sorted((a, b) -> {
                try {
                    return Long.compare(Files.getLastModifiedTime(b).toMillis(),
                                       Files.getLastModifiedTime(a).toMillis());
                } catch (Exception e) {
                    return 0;
                }
            })
            .toList();

        for (Path file : files) {
            try {
                JsonNode root = mapper.readTree(file.toFile());
                JsonNode dataNode = root.path("data");
                if (dataNode.isArray()) {
                    for (JsonNode row : dataNode) {
                        String authAppId = row.path("appId").asText("").trim();
                        if (authAppId.equals(appId)) {
                            String appKey = row.path("appKey").asText("").trim();
                            String sign = row.path("sign").asText("").trim();
                            String projectId = row.path("projectId").asText("").trim();

                            if (!appKey.isEmpty() && !sign.isEmpty()) {
                                logger.info("  ✓ 从 {} 加载 Open API 认证", file.getFileName());
                                return new OpenApiAuth(appKey, sign, projectId);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 继续尝试下一个文件
            }
        }

        throw new IllegalStateException("未找到应用 " + appId + " 的 Open API 认证信息");
    }

    /**
     * Open API 认证信息
     */
    private static class OpenApiAuth {
        final String appKey;
        final String sign;
        final String projectId;

        OpenApiAuth(String appKey, String sign, String projectId) {
            this.appKey = appKey;
            this.sign = sign;
            this.projectId = projectId;
        }
    }

    @Override
    public String getName() {
        return "RoleCreator";
    }

    @Override
    public Result execute(RolePlanner.Output plan) throws ExecutorException {
        return execute(plan, new ExecuteOptions());
    }

    @Override
    public Result execute(RolePlanner.Output plan, ExecuteOptions options) throws ExecutorException {
        String appId = plan.getAppName(); // 这里实际上应该是appId
        boolean skipExisting = options.isSkipExisting();

        // 加载 Open API 认证
        OpenApiAuth openApiAuth;
        try {
            openApiAuth = generateOpenApiAuth(appId);
        } catch (Exception e) {
            throw new ExecutorException(getName(), "无法生成 Open API 认证: " + e.getMessage(), e);
        }

        List<RolePlanner.RolePlan> roles = plan.getRoles();
        if (roles == null || roles.isEmpty()) {
            logger.info("⚠ 没有角色需要创建");
            return new Result(appId, List.of(), List.of(), List.of());
        }

        logger.info("→ 开始创建角色，共 {} 个", roles.size());

        // 获取现有角色（API调用可能失败，但不中断流程）
        List<ExistingRole> existingRoles;
        try {
            existingRoles = fetchExistingRoles(appId, openApiAuth);
        } catch (Exception e) {
            logger.warn("⚠ 获取现有角色失败，将跳过重复检查: {}", e.getMessage());
            existingRoles = List.of();
        }

        Map<String, ExistingRole> existingByName = new HashMap<>();
        for (ExistingRole role : existingRoles) {
            existingByName.put(role.getName(), role);
        }

        List<CreatedRole> created = new ArrayList<>();
        List<SkippedRole> skipped = new ArrayList<>();
        List<FailedRole> failed = new ArrayList<>();

        for (int i = 0; i < roles.size(); i++) {
            RolePlanner.RolePlan role = roles.get(i);
            String roleName = role.getName();

            if (roleName == null || roleName.trim().isEmpty()) {
                failed.add(new FailedRole(i, "", "角色名称为空"));
                continue;
            }

            // 检查是否已存在
            ExistingRole existing = existingByName.get(roleName.trim());
            if (existing != null && skipExisting) {
                skipped.add(new SkippedRole(roleName, "角色已存在", existing.getRoleId()));
                logger.info("  ↷ 跳过已存在角色: {}", roleName);
                continue;
            }

            // 创建角色
            try {
                String roleId = createRole(role, appId, openApiAuth);
                created.add(new CreatedRole(roleName, roleId));
                existingByName.put(roleName, new ExistingRole(roleName, roleId));
                logger.info("  ✓ 创建角色成功: {} (ID: {})", roleName, roleId);
            } catch (Exception e) {
                failed.add(new FailedRole(i, roleName, e.getMessage()));
                logger.error("  ✗ 创建角色失败: {} - {}", roleName, e.getMessage());
            }
        }

        logger.info("✓ 角色创建完成: {} 成功, {} 跳过, {} 失败", created.size(), skipped.size(), failed.size());

        return new Result(appId, created, skipped, failed);
    }

    /**
     * 获取现有角色列表
     *
     * 使用 /v1/open/app/getRoles 端点，认证参数在 query string 中
     */
    private List<ExistingRole> fetchExistingRoles(String appId, OpenApiAuth openApiAuth) throws ExecutorException {
        try {
            JsonNode response = apiClient.getOpenApp(GET_ROLES_ENDPOINT, appId, openApiAuth.appKey, openApiAuth.sign);
            List<ExistingRole> roles = new ArrayList<>();

            JsonNode dataNode = response.path("data");
            if (dataNode.isArray()) {
                for (JsonNode roleNode : dataNode) {
                    String name = roleNode.path("name").asText("");
                    String roleId = roleNode.path("roleId").asText("");
                    if (roleId.isEmpty()) {
                        roleId = roleNode.path("id").asText("");
                    }
                    if (!name.isEmpty() && !roleId.isEmpty()) {
                        roles.add(new ExistingRole(name, roleId));
                    }
                }
            }

            return roles;
        } catch (Exception e) {
            // API 调用失败，记录警告并返回空列表（优雅降级）
            logger.warn("⚠ 获取现有角色列表失败: {}", e.getMessage());
            logger.warn("  将继续创建角色而不检查重复");
            return List.of();
        }
    }

    /**
     * 创建单个角色
     *
     * Python 对应: make_create_role_payload() + build_open_app_params()
     * 使用从 app_authorize 加载的预计算 sign
     */
    private String createRole(RolePlanner.RolePlan role, String appId, OpenApiAuth openApiAuth) throws Exception {
        ObjectNode payload = mapper.createObjectNode();

        // 基本信息
        payload.put("name", role.getName().trim());
        payload.put("description", role.getDescription() != null ? role.getDescription().trim() : "");
        payload.put("permissionWay", 20); // 默认权限方式
        payload.put("roleType", 0);

        // 从 roleType 推断权限
        String roleType = role.getRoleType();
        boolean isAdmin = "admin".equals(roleType);
        boolean isEditor = "editor".equals(roleType);
        boolean isViewer = "viewer".equals(roleType);

        // 通用权限配置
        payload.set("generalAdd", createPermissionNode(isAdmin || isEditor));
        payload.set("gneralShare", createPermissionNode(false)); // 注意：接口拼写是 gneral
        payload.set("generalImport", createPermissionNode(isAdmin || isEditor));
        payload.set("generalExport", createPermissionNode(isAdmin || isEditor));
        payload.set("generalDiscussion", createPermissionNode(true));
        payload.set("generalLogging", createPermissionNode(true));
        payload.set("generalSystemPrinting", createPermissionNode(false));
        payload.set("recordShare", createPermissionNode(false));
        payload.set("payment", createPermissionNode(false));

        // 隐藏应用配置（viewer 默认隐藏）
        payload.put("hideAppForMembers", isViewer);

        // 使用 Open App API 方法（使用预计算 sign）
        JsonNode response = apiClient.postOpenApp(CREATE_ROLE_ENDPOINT, appId, openApiAuth.appKey, openApiAuth.sign, payload);

        JsonNode dataNode = response.path("data");
        String roleId = dataNode.path("roleId").asText("");
        if (roleId.isEmpty()) {
            roleId = dataNode.path("id").asText("");
        }

        if (roleId.isEmpty()) {
            throw new RuntimeException("API 未返回 roleId");
        }

        return roleId;
    }

    private ObjectNode createPermissionNode(boolean enabled) {
        ObjectNode node = mapper.createObjectNode();
        node.put("enable", enabled);
        return node;
    }

    @Override
    public boolean rollback(Result result) throws ExecutorException {
        logger.info("→ 回滚角色创建...");
        // 角色创建后通常不自动删除，而是通过禁用或标记处理
        // 这里仅记录需要手动处理
        List<CreatedRole> created = result.getCreated();
        if (created.isEmpty()) {
            return true;
        }

        logger.warn("⚠ 以下角色需要手动处理:");
        for (CreatedRole role : created) {
            logger.warn("  - {} (ID: {})", role.getName(), role.getRoleId());
        }
        return true;
    }

    // ========== 内部类 ==========

    private static class ExistingRole {
        private final String name;
        private final String roleId;

        ExistingRole(String name, String roleId) {
            this.name = name;
            this.roleId = roleId;
        }

        String getName() { return name; }
        String getRoleId() { return roleId; }
    }

    // ========== 结果类 ==========

    public static class Result {
        private final String appId;
        private final List<CreatedRole> created;
        private final List<SkippedRole> skipped;
        private final List<FailedRole> failed;

        public Result(String appId, List<CreatedRole> created, List<SkippedRole> skipped, List<FailedRole> failed) {
            this.appId = appId;
            this.created = created != null ? created : List.of();
            this.skipped = skipped != null ? skipped : List.of();
            this.failed = failed != null ? failed : List.of();
        }

        public String getAppId() { return appId; }
        public List<CreatedRole> getCreated() { return created; }
        public List<SkippedRole> getSkipped() { return skipped; }
        public List<FailedRole> getFailed() { return failed; }

        public boolean isSuccess() {
            return failed.isEmpty();
        }

        public int getCreatedCount() { return created.size(); }
        public int getSkippedCount() { return skipped.size(); }
        public int getFailedCount() { return failed.size(); }
    }

    public static class CreatedRole {
        private final String name;
        private final String roleId;

        public CreatedRole(String name, String roleId) {
            this.name = name;
            this.roleId = roleId;
        }

        public String getName() { return name; }
        public String getRoleId() { return roleId; }
    }

    public static class SkippedRole {
        private final String name;
        private final String reason;
        private final String existingRoleId;

        public SkippedRole(String name, String reason, String existingRoleId) {
            this.name = name;
            this.reason = reason;
            this.existingRoleId = existingRoleId;
        }

        public String getName() { return name; }
        public String getReason() { return reason; }
        public String getExistingRoleId() { return existingRoleId; }
    }

    public static class FailedRole {
        private final int index;
        private final String name;
        private final String error;

        public FailedRole(int index, String name, String error) {
            this.index = index;
            this.name = name;
            this.error = error;
        }

        public int getIndex() { return index; }
        public String getName() { return name; }
        public String getError() { return error; }
    }
}
