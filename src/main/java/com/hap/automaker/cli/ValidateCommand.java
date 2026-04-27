package com.hap.automaker.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 验证命令 - 检查所有凭证配置是否有效
 *
 * 验证三类凭证:
 * 1. AI 认证 (ai_auth.json) - 发送一个简单请求验证 API key
 * 2. 组织认证 (organization_auth.json) - 调用 V3 API 验证签名
 * 3. Web 认证 (web_auth.json) - 调用一个简单 Web API 验证 cookie/token
 */
@Command(name = "validate", mixinStandardHelpOptions = true,
         description = "Validate credential configurations (AI, Organization, Web)")
public class ValidateCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ValidateCommand.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Option(names = "--repo-root", defaultValue = "",
            description = "Override the repository root directory")
    private String repoRoot;

    @Option(names = "--ai-only", defaultValue = "false",
            description = "Only validate AI credentials")
    private boolean aiOnly;

    @Option(names = "--org-only", defaultValue = "false",
            description = "Only validate organization credentials")
    private boolean orgOnly;

    @Option(names = "--web-only", defaultValue = "false",
            description = "Only validate Web credentials")
    private boolean webOnly;

    @Override
    public Integer call() throws Exception {
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(),
                                Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));

        ConfigPaths paths = new ConfigPaths(detected.repoRoot());

        boolean allValid = true;

        // 验证 AI 认证
        if (!orgOnly && !webOnly) {
            allValid &= validateAiAuth(paths);
        }

        // 验证组织认证
        if (!aiOnly && !webOnly) {
            allValid &= validateOrgAuth(paths);
        }

        // 验证 Web 认证
        if (!aiOnly && !orgOnly) {
            allValid &= validateWebAuth(paths);
        }

        logger.info("");
        if (allValid) {
            logger.info("✓ All validated credentials are valid");
            return 0;
        } else {
            logger.error("✗ Some credentials are invalid, please check above");
            return 1;
        }
    }

    private boolean validateAiAuth(ConfigPaths paths) {
        logger.info("→ Validating AI credentials...");
        Path aiAuthPath = paths.aiAuth();

        if (!Files.exists(aiAuthPath)) {
            logger.error("  ✗ AI auth file not found: {}", aiAuthPath);
            return false;
        }

        try {
            JsonNode root = Jacksons.mapper().readTree(aiAuthPath.toFile());

            String apiKey = extractApiKey(root);
            if (apiKey == null || apiKey.isEmpty()) {
                logger.error("  ✗ AI auth: API key is missing");
                return false;
            }

            String model = extractModel(root);
            String baseUrl = extractBaseUrl(root);
            String effectiveBaseUrl = (baseUrl != null && !baseUrl.isEmpty())
                    ? baseUrl : "https://generativelanguage.googleapis.com/v1beta";

            // 尝试发送一个简单请求验证 API key
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(effectiveBaseUrl + "/models?key=" + apiKey))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    logger.info("  ✅ AI auth valid (model: {})", model != null ? model : "default");
                    return true;
                } else if (response.statusCode() == 400 || response.statusCode() == 403) {
                    logger.error("  ✗ AI auth: API key rejected (status {})", response.statusCode());
                    return false;
                } else {
                    logger.warn("  ⚠ AI auth: Could not verify (status {}), but config looks valid", response.statusCode());
                    logger.info("  ✅ AI auth config present (model: {})", model != null ? model : "default");
                    return true;
                }
            } catch (Exception e) {
                logger.warn("  ⚠ AI auth: Network error during verification: {}", e.getMessage());
                logger.info("  ✅ AI auth config present (model: {})", model != null ? model : "default");
                return true;
            }

        } catch (Exception e) {
            logger.error("  ✗ AI auth: Failed to parse config: {}", e.getMessage());
            return false;
        }
    }

    private boolean validateOrgAuth(ConfigPaths paths) {
        logger.info("→ Validating Organization credentials...");
        Path orgAuthPath = paths.organizationAuth();

        if (!Files.exists(orgAuthPath)) {
            logger.error("  ✗ Organization auth file not found: {}", orgAuthPath);
            return false;
        }

        try {
            JsonNode root = Jacksons.mapper().readTree(orgAuthPath.toFile());

            String appKey = getStringField(root, "app_key", "appKey");
            String secretKey = getStringField(root, "secret_key", "secretKey");
            String projectId = getStringField(root, "project_id", "projectId");

            if (appKey == null || appKey.isEmpty()) {
                logger.error("  ✗ Organization auth: app_key is missing");
                return false;
            }
            if (secretKey == null || secretKey.isEmpty()) {
                logger.error("  ✗ Organization auth: secret_key is missing");
                return false;
            }

            // 尝试调用 V3 API 验证签名
            try {
                long timestamp = System.currentTimeMillis();
                String raw = "AppKey=" + appKey + "&SecretKey=" + secretKey + "&Timestamp=" + timestamp;
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                String sign = java.util.Base64.getEncoder().encodeToString(
                        hexString.toString().getBytes(StandardCharsets.UTF_8));

                StringBuilder url = new StringBuilder("https://api.mingdao.com/v3/app");
                url.append("?appKey=").append(URLEncoder.encode(appKey, StandardCharsets.UTF_8));
                url.append("&sign=").append(URLEncoder.encode(sign, StandardCharsets.UTF_8));
                url.append("&timestamp=").append(timestamp);
                if (projectId != null && !projectId.isEmpty()) {
                    url.append("&projectId=").append(URLEncoder.encode(projectId, StandardCharsets.UTF_8));
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode result = Jacksons.mapper().readTree(response.body());
                    boolean success = result.path("success").asBoolean(false);
                    if (success) {
                        logger.info("  ✅ Organization auth valid (appKey: {}...{})",
                                appKey.substring(0, Math.min(4, appKey.length())),
                                appKey.length() > 4 ? appKey.substring(appKey.length() - 4) : "");
                        return true;
                    } else {
                        String msg = result.path("error_msg").asText("Unknown error");
                        logger.error("  ✗ Organization auth: API returned error: {}", msg);
                        return false;
                    }
                } else {
                    logger.warn("  ⚠ Organization auth: Could not verify (status {}), but config looks valid",
                            response.statusCode());
                    logger.info("  ✅ Organization auth config present");
                    return true;
                }
            } catch (Exception e) {
                logger.warn("  ⚠ Organization auth: Network error during verification: {}", e.getMessage());
                logger.info("  ✅ Organization auth config present");
                return true;
            }

        } catch (Exception e) {
            logger.error("  ✗ Organization auth: Failed to parse config: {}", e.getMessage());
            return false;
        }
    }

    private boolean validateWebAuth(ConfigPaths paths) {
        logger.info("→ Validating Web credentials...");
        Path webAuthPath = paths.webAuth();

        if (!Files.exists(webAuthPath)) {
            logger.error("  ✗ Web auth file not found: {}", webAuthPath);
            return false;
        }

        try {
            JsonNode root = Jacksons.mapper().readTree(webAuthPath.toFile());

            String authorization = getStringField(root, "authorization");
            String cookie = getStringField(root, "cookie");
            String projectId = getStringField(root, "project_id", "projectId");

            if ((authorization == null || authorization.isEmpty()) && (cookie == null || cookie.isEmpty())) {
                logger.error("  ✗ Web auth: Both authorization and cookie are missing");
                return false;
            }

            // 尝试调用 Web API 验证
            try {
                ObjectNode body = Jacksons.mapper().createObjectNode();
                body.put("pageIndex", 1);
                body.put("pageSize", 1);

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create("https://www.mingdao.com/api/HomeApp/GetAppListByProject"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json");

                if (authorization != null && !authorization.isEmpty()) {
                    requestBuilder.header("Authorization", authorization);
                }
                if (cookie != null && !cookie.isEmpty()) {
                    requestBuilder.header("Cookie", cookie);
                }
                if (projectId != null && !projectId.isEmpty()) {
                    requestBuilder.header("projectId", projectId);
                }

                HttpRequest request = requestBuilder
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode result = Jacksons.mapper().readTree(response.body());
                    int errorCode = result.path("error_code").asInt(0);
                    if (errorCode == 1) {
                        logger.info("  ✅ Web auth valid");
                        return true;
                    } else {
                        String msg = result.path("error_msg").asText("Unknown error");
                        logger.error("  ✗ Web auth: API returned error: {}", msg);
                        return false;
                    }
                } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                    logger.error("  ✗ Web auth: Authentication failed (status {})", response.statusCode());
                    return false;
                } else {
                    logger.warn("  ⚠ Web auth: Could not verify (status {}), but config looks valid",
                            response.statusCode());
                    logger.info("  ✅ Web auth config present");
                    return true;
                }
            } catch (Exception e) {
                logger.warn("  ⚠ Web auth: Network error during verification: {}", e.getMessage());
                logger.info("  ✅ Web auth config present");
                return true;
            }

        } catch (Exception e) {
            logger.error("  ✗ Web auth: Failed to parse config: {}", e.getMessage());
            return false;
        }
    }

    // ========== 辅助方法 ==========

    private String extractApiKey(JsonNode root) {
        if (root.hasNonNull("gemini-fast") && root.get("gemini-fast").hasNonNull("api_key")) {
            return root.get("gemini-fast").get("api_key").asText();
        }
        if (root.hasNonNull("gemini") && root.get("gemini").hasNonNull("api_key")) {
            return root.get("gemini").get("api_key").asText();
        }
        if (root.hasNonNull("api_key")) return root.get("api_key").asText();
        if (root.hasNonNull("apiKey")) return root.get("apiKey").asText();
        return null;
    }

    private String extractModel(JsonNode root) {
        if (root.hasNonNull("gemini-fast") && root.get("gemini-fast").hasNonNull("model")) {
            return root.get("gemini-fast").get("model").asText();
        }
        if (root.hasNonNull("gemini") && root.get("gemini").hasNonNull("model")) {
            return root.get("gemini").get("model").asText();
        }
        return null;
    }

    private String extractBaseUrl(JsonNode root) {
        if (root.hasNonNull("gemini-fast") && root.get("gemini-fast").hasNonNull("base_url")) {
            return root.get("gemini-fast").get("base_url").asText();
        }
        if (root.hasNonNull("gemini") && root.get("gemini").hasNonNull("base_url")) {
            return root.get("gemini").get("base_url").asText();
        }
        return null;
    }

    private String getStringField(JsonNode root, String... fieldNames) {
        for (String field : fieldNames) {
            if (root.hasNonNull(field)) {
                String value = root.get(field).asText().trim();
                if (!value.isEmpty()) return value;
            }
        }
        return null;
    }
}
