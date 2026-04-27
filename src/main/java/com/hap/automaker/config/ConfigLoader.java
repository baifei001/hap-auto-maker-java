package com.hap.automaker.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.model.AiAuthConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置加载器
 *
 * 负责加载各种配置文件：AI配置、组织认证配置等
 */
public class ConfigLoader {

    private static final Path AI_CONFIG_PATH = ConfigPaths.getAiConfigPath();
    private static final Path ORG_AUTH_CONFIG_PATH = ConfigPaths.getOrganizationAuthPath();

    /**
     * 加载 AI 配置
     *
     * 从 config/ai_config.json 加载
     *
     * @return AiAuthConfig
     * @throws Exception 如果配置文件不存在或解析失败
     */
    public static AiAuthConfig loadAiConfig() throws Exception {
        if (!Files.exists(AI_CONFIG_PATH)) {
            throw new IllegalStateException("AI config not found at: " + AI_CONFIG_PATH);
        }

        JsonNode root = Jacksons.mapper().readTree(AI_CONFIG_PATH.toFile());

        // 支持嵌套结构，提取 gemini-fast 配置（如果有）
        // 默认使用 gemini-flash
        String provider = "gemini";
        String model = "gemini-2.5-flash";
        String apiKey = "";
        String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

        if (root.hasNonNull("gemini-fast")) {
            JsonNode fast = root.get("gemini-fast");
            if (fast.hasNonNull("api_key")) {
                apiKey = fast.get("api_key").asText();
            }
            if (fast.hasNonNull("model")) {
                model = fast.get("model").asText();
            }
            if (fast.hasNonNull("base_url")) {
                baseUrl = fast.get("base_url").asText();
            }
        } else if (root.hasNonNull("gemini")) {
            JsonNode gemini = root.get("gemini");
            if (gemini.hasNonNull("api_key")) {
                apiKey = gemini.get("api_key").asText();
            }
            if (gemini.hasNonNull("model")) {
                model = gemini.get("model").asText();
            }
            if (gemini.hasNonNull("base_url")) {
                baseUrl = gemini.get("base_url").asText();
            }
        }

        // 回退到根级配置（旧格式兼容）
        if (apiKey.isEmpty() && root.hasNonNull("api_key")) {
            apiKey = root.get("api_key").asText();
        }
        if (apiKey.isEmpty() && root.hasNonNull("apiKey")) {
            apiKey = root.get("apiKey").asText();
        }

        if (apiKey.isEmpty()) {
            throw new IllegalStateException("API key not found in AI config");
        }

        return new AiAuthConfig(provider, apiKey, model, baseUrl);
    }

    /**
     * 组织认证配置
     */
    public static class OrgAuthConfig {
        private final String appKey;
        private final String secretKey;
        private final String projectId;
        private final String ownerId;
        private final String groupIds;

        public OrgAuthConfig(String appKey, String secretKey, String projectId,
                            String ownerId, String groupIds) {
            this.appKey = appKey;
            this.secretKey = secretKey;
            this.projectId = projectId;
            this.ownerId = ownerId;
            this.groupIds = groupIds;
        }

        public String getAppKey() { return appKey; }
        public String getSecretKey() { return secretKey; }
        public String getProjectId() { return projectId; }
        public String getOwnerId() { return ownerId; }
        public String getGroupIds() { return groupIds; }

        /**
         * 生成 Open API 签名
         *
         * sign = base64(hmac-sha256(appKey + timestamp + appSecret, appSecret))
         */
        public String generateSign(long timestamp) throws Exception {
            String message = appKey + timestamp + secretKey;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(
                this.secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(hash);
        }

        /**
         * 获取当前时间戳的签名
         */
        public String getSign() throws Exception {
            return generateSign(System.currentTimeMillis());
        }
    }

    /**
     * 加载组织认证配置
     *
     * 从 config/credentials/organization_auth.json 加载
     *
     * @return OrgAuthConfig
     * @throws Exception 如果配置文件不存在或解析失败
     */
    public static OrgAuthConfig loadOrgAuthConfig() throws Exception {
        if (!Files.exists(ORG_AUTH_CONFIG_PATH)) {
            throw new IllegalStateException("Organization auth config not found at: " + ORG_AUTH_CONFIG_PATH);
        }

        JsonNode root = Jacksons.mapper().readTree(ORG_AUTH_CONFIG_PATH.toFile());

        String appKey = getStringOrThrow(root, "app_key", "appKey");
        String secretKey = getStringOrThrow(root, "secret_key", "secretKey");
        String projectId = getString(root, "project_id", "projectId", "");
        String ownerId = getString(root, "owner_id", "ownerId", "");
        String groupIds = getString(root, "group_ids", "groupIds", "");

        return new OrgAuthConfig(appKey, secretKey, projectId, ownerId, groupIds);
    }

    private static String getStringOrThrow(JsonNode root, String... fieldNames) throws Exception {
        for (String field : fieldNames) {
            if (root.hasNonNull(field)) {
                String value = root.get(field).asText().trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("Missing required field: " + String.join(" or ", fieldNames));
    }

    private static String getString(JsonNode root, String fieldName1, String fieldName2, String defaultValue) {
        for (String field : new String[]{fieldName1, fieldName2}) {
            if (root.hasNonNull(field)) {
                String value = root.get(field).asText().trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return defaultValue;
    }
}
