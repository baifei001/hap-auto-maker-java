package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 应用创建执行器
 *
 * Python 对应: create_app.py + pipeline_create_app.py
 *
 * 职责:
 * - 调用 Open API /v1/open/app/create 创建应用
 * - 生成应用授权文件
 * - 分配主题色和图标
 */
public class AppCreator implements Executor<AppCreator.Input, AppCreator.Output> {

    private static final Logger logger = LoggerFactory.getLogger(AppCreator.class);

    private final HapApiClient apiClient;

    // 主题色池 (from create_app.py DEFAULT_COLOR_POOL)
    public static final String[] COLOR_POOL = {
        "#00BCD4", "#4CAF50", "#2196F3", "#FF9800", "#E91E63",
        "#9C27B0", "#3F51B5", "#009688", "#FF5722", "#795548",
        "#607D8B", "#F44336", "#673AB7", "#03A9F4", "#26A69A",
        "#1565C0", "#2E7D32", "#00838F", "#6A1B9A", "#AD1457",
        "#283593", "#EF6C00", "#C62828", "#37474F", "#5D4037",
        "#0277BD", "#00695C", "#4527A0", "#7B1FA2", "#880E4F",
        "#D84315", "#424242", "#546E7A", "#1E88E5", "#43A047",
        "#00897B", "#3949AB", "#8E24AA", "#D81B60", "#FB8C00",
        "#F4511E", "#6D4C41", "#0D47A1", "#1B5E20", "#004D40",
        "#311B92", "#4A148C", "#B71C1C", "#BF360C", "#263238"
    };

    public AppCreator(HapApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String getName() {
        return "AppCreator";
    }

    /**
     * 创建应用
     *
     * @param input 包含应用名称、描述、图标颜色的输入
     * @return 创建结果
     */
    @Override
    public Output execute(Input input) throws ExecutorException {
        try {
            // 加载组织认证配置
            ConfigLoader.OrgAuthConfig orgAuth = ConfigLoader.loadOrgAuthConfig();

            // 验证必需的配置
            if (orgAuth.getProjectId() == null || orgAuth.getProjectId().isEmpty()) {
                throw new ExecutorException(getName(), "缺少 projectId，请在 organization_auth.json 中配置");
            }
            if (orgAuth.getOwnerId() == null || orgAuth.getOwnerId().isEmpty()) {
                throw new ExecutorException(getName(), "缺少 ownerId，请在 organization_auth.json 中配置");
            }

            // 配置 API 客户端
            apiClient.setOrgAuth(orgAuth.getAppKey(), orgAuth.getSecretKey());

            // 确定图标和颜色
            String icon = input.getIcon();
            if (icon == null || icon.isEmpty()) {
                icon = getRandomIcon();
            }

            String color = input.getIconColor();
            if (color == null || color.isEmpty()) {
                color = getRandomColor();
            }

            // 调用 Open API 创建应用
            JsonNode response = apiClient.createAppOpen(
                input.getName(),
                input.getDescription(),
                icon,
                color,
                orgAuth.getProjectId(),
                orgAuth.getOwnerId(),
                orgAuth.getGroupIds()
            );

            // 提取 appId
            JsonNode data = response.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new ExecutorException(getName(), "API response missing data field");
            }

            String appId = data.path("id").asText();
            String appName = data.path("name").asText(input.getName());
            String appKey = data.path("appKey").asText();
            String sign = data.path("sign").asText();

            if (appId == null || appId.isEmpty()) {
                throw new ExecutorException(getName(), "API response missing app id");
            }

            // 构建原始响应 Map
            Map<String, Object> rawResponse = Jacksons.mapper().convertValue(data, Map.class);

            // 保存授权文件
            Path outputDir = Path.of("data", "outputs", "app_authorizations");
            String groupName = input.getGroupName() != null ? input.getGroupName() : "";
            Path authFile = saveAppAuthorization(appId, appName, appKey, sign, outputDir,
                                                  orgAuth.getGroupIds(), groupName);

            logger.info("✓ 应用创建成功: {} (ID: {})", appName, appId);

            return new Output(appId, appName, appKey, sign, true, null, rawResponse, authFile);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to create app: " + e.getMessage(), e);
        }
    }

    /**
     * 保存应用授权文件
     *
     * @param appId 应用ID
     * @param appName 应用名称
     * @param appKey 应用Key
     * @param sign 签名
     * @param outputDir 输出目录
     * @param groupId 分组ID
     * @param groupName 分组名称
     * @return 授权文件路径
     */
    public Path saveAppAuthorization(String appId, String appName, String appKey, String sign,
                                      Path outputDir, String groupId, String groupName) throws ExecutorException {
        try {
            Files.createDirectories(outputDir);

            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());

            Path authFile = outputDir.resolve("app_authorize_" + timestamp + ".json");

            Map<String, Object> authData = new HashMap<>();
            authData.put("appId", appId);
            authData.put("appName", appName);
            authData.put("appKey", appKey);
            authData.put("sign", sign);
            authData.put("groupId", groupId);
            authData.put("groupName", groupName);
            authData.put("createdAt", timestamp);

            // 写入 JSON 文件
            String json = Jacksons.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(authData);
            Files.writeString(authFile, json);

            logger.info("✓ 授权文件已保存: {}", authFile);

            return authFile;

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to save authorization file", e);
        }
    }

    /**
     * 获取随机主题色
     */
    public String getRandomColor() {
        int index = (int) (Math.random() * COLOR_POOL.length);
        return COLOR_POOL[index];
    }

    /**
     * 获取随机图标（简化版，实际应从图标库加载）
     */
    public String getRandomIcon() {
        // 常用图标列表
        String[] icons = {
            "0_lego", "1_building", "2_calendar", "3_chart", "4_home",
            "5_document", "6_user_group", "7_settings", "8_folder", "9_task"
        };
        return "sys_" + icons[(int) (Math.random() * icons.length)];
    }

    // ========== 输入类 ==========
    public static class Input {
        private final String name;
        private final String description;
        private final String icon;
        private final String iconColor;
        private final String groupName;

        public Input(String name, String description) {
            this(name, description, null, null, null);
        }

        public Input(String name, String description, String icon, String iconColor) {
            this(name, description, icon, iconColor, null);
        }

        public Input(String name, String description, String icon, String iconColor, String groupName) {
            this.name = name;
            this.description = description;
            this.icon = icon;
            this.iconColor = iconColor;
            this.groupName = groupName;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getIcon() { return icon; }
        public String getIconColor() { return iconColor; }
        public String getGroupName() { return groupName; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final String appId;
        private final String appName;
        private final String appKey;
        private final String sign;
        private final boolean success;
        private final String errorMessage;
        private final Map<String, Object> rawResponse;
        private final Path authFile;

        public Output(String appId, String appName, String appKey, String sign,
                      boolean success, String errorMessage,
                      Map<String, Object> rawResponse, Path authFile) {
            this.appId = appId;
            this.appName = appName;
            this.appKey = appKey;
            this.sign = sign;
            this.success = success;
            this.errorMessage = errorMessage;
            this.rawResponse = rawResponse;
            this.authFile = authFile;
        }

        public String getAppId() { return appId; }
        public String getAppName() { return appName; }
        public String getAppKey() { return appKey; }
        public String getSign() { return sign; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public Map<String, Object> getRawResponse() { return rawResponse; }
        public Path getAuthFile() { return authFile; }
    }
}
