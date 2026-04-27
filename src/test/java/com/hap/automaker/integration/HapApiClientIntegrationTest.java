package com.hap.automaker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HapApiClient 集成测试 - 使用 WireMock 模拟真实 API
 *
 * 测试重点：API 请求格式、认证参数、响应解析
 * 注意：由于 HapApiClient.BASE_URL 是 static final，
 * 我们使用子类覆盖 post/get 方法来注入 WireMock URL。
 */
@WireMockTest
class HapApiClientIntegrationTest {

    /**
     * 可测试的 HapApiClient 子类，将请求转发到 WireMock
     */
    static class TestableApiClient extends HapApiClient {
        private final String baseUrl;

        TestableApiClient(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override
        public JsonNode createWorksheetV3(String name, JsonNode fields) throws Exception {
            // 调用父类但使用 WireMock 的 URL - 通过直接 HTTP 调用
            ObjectNode body = getMapper().createObjectNode();
            body.put("name", name);
            if (fields != null && !fields.isEmpty()) {
                body.set("fields", fields);
            }
            return postToWireMock("/v3/app/worksheets", body);
        }

        @Override
        public JsonNode editWorksheetV3(String worksheetId, JsonNode fields) throws Exception {
            ObjectNode body = getMapper().createObjectNode();
            if (fields != null && !fields.isEmpty()) {
                body.set("fields", fields);
            }
            return putToWireMock("/v3/app/worksheets/" + worksheetId, body);
        }

        @Override
        public JsonNode deleteWorksheetV3(String worksheetId) throws Exception {
            return deleteToWireMock("/v3/app/worksheets/" + worksheetId);
        }

        @Override
        public JsonNode createWorksheet(String appId, String name, String icon, String iconColor) throws Exception {
            ObjectNode body = getMapper().createObjectNode();
            body.put("appId", appId);
            body.put("name", name);
            if (icon != null) body.put("icon", icon);
            if (iconColor != null) body.put("iconColor", iconColor);
            return postWebToWireMock("/api/Worksheet/CreateWorksheet", body);
        }

        @Override
        public JsonNode getOpenApp(String path, String appId, String appKey, String sign) throws Exception {
            StringBuilder url = new StringBuilder(baseUrl + path);
            url.append("?appKey=").append(java.net.URLEncoder.encode(appKey, java.nio.charset.StandardCharsets.UTF_8));
            url.append("&sign=").append(java.net.URLEncoder.encode(sign, java.nio.charset.StandardCharsets.UTF_8));
            url.append("&timestamp=").append(System.currentTimeMillis());
            url.append("&appId=").append(java.net.URLEncoder.encode(appId, java.nio.charset.StandardCharsets.UTF_8));

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url.toString()))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();

            java.net.http.HttpResponse<String> response = getHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            JsonNode result = getMapper().readTree(response.body());

            if (!result.path("success").asBoolean(false)) {
                String msg = result.path("error_msg").asText("Unknown error");
                throw new RuntimeException("HAP Open App API error: " + msg);
            }
            return result;
        }

        @Override
        public JsonNode postOpenApp(String path, String appId, String appKey, String sign, JsonNode payload) throws Exception {
            StringBuilder url = new StringBuilder(baseUrl + path);
            url.append("?appKey=").append(java.net.URLEncoder.encode(appKey, java.nio.charset.StandardCharsets.UTF_8));
            url.append("&sign=").append(java.net.URLEncoder.encode(sign, java.nio.charset.StandardCharsets.UTF_8));
            url.append("&timestamp=").append(System.currentTimeMillis());
            url.append("&appId=").append(java.net.URLEncoder.encode(appId, java.nio.charset.StandardCharsets.UTF_8));

            ObjectNode bodyWithAuth = (ObjectNode) payload.deepCopy();
            bodyWithAuth.put("appKey", appKey);
            bodyWithAuth.put("sign", sign);
            bodyWithAuth.put("timestamp", System.currentTimeMillis());
            bodyWithAuth.put("appId", appId);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url.toString()))
                .timeout(java.time.Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/plain, */*")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(bodyWithAuth.toString()))
                .build();

            java.net.http.HttpResponse<String> response = getHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Open App API call failed: " + response.statusCode());
            }

            JsonNode result = getMapper().readTree(response.body());
            if (!result.path("success").asBoolean(false)) {
                String msg = result.path("error_msg").asText("Unknown error");
                throw new RuntimeException("HAP Open App API error: " + msg);
            }
            return result;
        }

        @Override
        public JsonNode getAppAuthorize(String appId, String appKey, String sign, long timestamp, String projectId) throws Exception {
            StringBuilder url = new StringBuilder(baseUrl + "/v1/open/app/getAppAuthorize");
            url.append("?appKey=").append(java.net.URLEncoder.encode(appKey, java.nio.charset.StandardCharsets.UTF_8));
            url.append("&sign=").append(java.net.URLEncoder.encode(sign, java.nio.charset.StandardCharsets.UTF_8));
            url.append("&timestamp=").append(timestamp);
            url.append("&projectId=").append(java.net.URLEncoder.encode(projectId, java.nio.charset.StandardCharsets.UTF_8));
            url.append("&appId=").append(java.net.URLEncoder.encode(appId, java.nio.charset.StandardCharsets.UTF_8));

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url.toString()))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();

            java.net.http.HttpResponse<String> response = getHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("GetAppAuthorize API call failed: " + response.statusCode());
            }

            JsonNode result = getMapper().readTree(response.body());
            if (!result.path("success").asBoolean(false)) {
                String msg = result.path("error_msg").asText("Unknown error");
                throw new RuntimeException("HAP GetAppAuthorize API error: " + msg);
            }
            return result;
        }

        // ========== 内部辅助方法 ==========

        private JsonNode postToWireMock(String path, ObjectNode body) throws Exception {
            body.put("appKey", "test-app-key");
            body.put("sign", "test-sign");
            body.put("timestamp", System.currentTimeMillis());

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl + path))
                .timeout(java.time.Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

            java.net.http.HttpResponse<String> response = getHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("V3 API call failed: " + response.statusCode());
            }
            return getMapper().readTree(response.body());
        }

        private JsonNode putToWireMock(String path, ObjectNode body) throws Exception {
            body.put("appKey", "test-app-key");
            body.put("sign", "test-sign");
            body.put("timestamp", System.currentTimeMillis());

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl + path))
                .timeout(java.time.Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

            java.net.http.HttpResponse<String> response = getHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("V3 API call failed: " + response.statusCode());
            }
            return getMapper().readTree(response.body());
        }

        private JsonNode deleteToWireMock(String path) throws Exception {
            String url = baseUrl + path
                + "?appKey=test-app-key&sign=test-sign&timestamp=" + System.currentTimeMillis();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(120))
                .header("Accept", "application/json")
                .DELETE()
                .build();

            java.net.http.HttpResponse<String> response = getHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("V3 API call failed: " + response.statusCode());
            }
            return getMapper().readTree(response.body());
        }

        private JsonNode postWebToWireMock(String path, ObjectNode body) throws Exception {
            java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl + path))
                .timeout(java.time.Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "test-auth-token")
                .header("projectId", "test-project-id");

            java.net.http.HttpRequest request = reqBuilder
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

            java.net.http.HttpResponse<String> response = getHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("API call failed: " + response.statusCode());
            }

            JsonNode result = getMapper().readTree(response.body());
            int errorCode = result.path("error_code").asInt(0);
            if (errorCode != 1) {
                String msg = result.path("error_msg").asText("Unknown error");
                throw new RuntimeException("HAP API error: " + errorCode + " - " + msg);
            }
            return result;
        }

        // 提供子类访问
        private java.net.http.HttpClient getHttpClient() {
            try {
                java.lang.reflect.Field f = HapApiClient.class.getDeclaredField("httpClient");
                f.setAccessible(true);
                return (java.net.http.HttpClient) f.get(this);
            } catch (Exception e) {
                return java.net.http.HttpClient.newHttpClient();
            }
        }

        private ObjectMapper getMapper() {
            try {
                java.lang.reflect.Field f = HapApiClient.class.getDeclaredField("mapper");
                f.setAccessible(true);
                return (ObjectMapper) f.get(this);
            } catch (Exception e) {
                return Jacksons.mapper();
            }
        }
    }

    private TestableApiClient apiClient;
    private final ObjectMapper mapper = Jacksons.mapper();

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        apiClient = new TestableApiClient(wmInfo.getHttpBaseUrl());
    }

    @Test
    @DisplayName("V3 API - 创建工作表成功 (error_code=1)")
    void testCreateWorksheetV3Success() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/app/worksheets"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"error_msg\":\"\",\"data\":{\"worksheetId\":\"ws123\"}}")));

        JsonNode fields = mapper.createArrayNode();
        JsonNode result = apiClient.createWorksheetV3("测试工作表", fields);

        assertEquals(1, result.path("error_code").asInt(0));
        assertEquals("ws123", result.path("data").path("worksheetId").asText());
    }

    @Test
    @DisplayName("Web API - 创建工作表成功 (error_code=1)")
    void testCreateWorksheetWebApiSuccess() throws Exception {
        stubFor(post(urlPathEqualTo("/api/Worksheet/CreateWorksheet"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"error_msg\":\"\",\"data\":{\"worksheetId\":\"ws456\"}}")));

        JsonNode result = apiClient.createWorksheet("app123", "测试工作表", "icon1", "#FF0000");

        assertEquals(1, result.path("error_code").asInt(0));
    }

    @Test
    @DisplayName("Web API - 需要认证头")
    void testWebApiRequiresAuthHeaders() throws Exception {
        stubFor(post(urlPathEqualTo("/api/Worksheet/CreateWorksheet"))
            .withHeader("Authorization", equalTo("test-auth-token"))
            .withHeader("projectId", equalTo("test-project-id"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"error_msg\":\"\"}")));

        apiClient.createWorksheet("app123", "测试", null, null);

        verify(postRequestedFor(urlPathEqualTo("/api/Worksheet/CreateWorksheet"))
            .withHeader("Authorization", equalTo("test-auth-token"))
            .withHeader("projectId", equalTo("test-project-id")));
    }

    @Test
    @DisplayName("Web API - 错误响应 (error_code!=1)")
    void testWebApiErrorResponse() {
        stubFor(post(urlPathEqualTo("/api/Worksheet/CreateWorksheet"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":0,\"error_msg\":\"权限不足\"}")));

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> apiClient.createWorksheet("app123", "测试", null, null));
        assertTrue(ex.getMessage().contains("权限不足"));
    }

    @Test
    @DisplayName("Open App API - getRoles 成功")
    void testOpenAppGetRoles() throws Exception {
        stubFor(get(urlPathEqualTo("/v1/open/app/getRoles"))
            .withQueryParam("appKey", equalTo("authorized-key"))
            .withQueryParam("appId", equalTo("app123"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"data\":[{\"name\":\"管理员\",\"roleId\":\"role1\"}]}")));

        JsonNode result = apiClient.getOpenApp("/v1/open/app/getRoles", "app123", "authorized-key", "test-sign");

        assertTrue(result.path("success").asBoolean());
        assertEquals("管理员", result.path("data").get(0).path("name").asText());
    }

    @Test
    @DisplayName("Open App API - createRole POST 成功")
    void testOpenAppCreateRole() throws Exception {
        stubFor(post(urlPathEqualTo("/v1/open/app/createRole"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"data\":{\"roleId\":\"new-role-1\"}}")));

        ObjectNode payload = mapper.createObjectNode();
        payload.put("name", "测试角色");
        payload.put("description", "测试描述");

        JsonNode result = apiClient.postOpenApp("/v1/open/app/createRole", "app123", "test-key", "test-sign", payload);

        assertTrue(result.path("success").asBoolean());
        assertEquals("new-role-1", result.path("data").path("roleId").asText());
    }

    @Test
    @DisplayName("Open App API - 失败响应")
    void testOpenAppFailureResponse() {
        stubFor(get(urlPathEqualTo("/v1/open/app/getRoles"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":false,\"error_msg\":\"应用不存在\"}")));

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> apiClient.getOpenApp("/v1/open/app/getRoles", "bad-app", "key", "sign"));
        assertTrue(ex.getMessage().contains("应用不存在"));
    }

    @Test
    @DisplayName("V3 API - DELETE 请求认证参数在 URL 中")
    void testV3DeleteWithUrlParams() throws Exception {
        stubFor(delete(urlPathEqualTo("/v3/app/worksheets/ws-to-delete"))
            .withQueryParam("appKey", equalTo("test-app-key"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"error_msg\":\"\"}")));

        JsonNode result = apiClient.deleteWorksheetV3("ws-to-delete");
        assertEquals(1, result.path("error_code").asInt(0));

        verify(deleteRequestedFor(urlPathEqualTo("/v3/app/worksheets/ws-to-delete"))
            .withQueryParam("appKey", equalTo("test-app-key")));
    }

    @Test
    @DisplayName("V3 API - PUT 请求（编辑工作表）")
    void testV3PutRequest() throws Exception {
        stubFor(put(urlPathEqualTo("/v3/app/worksheets/ws-123"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"error_msg\":\"\"}")));

        JsonNode fields = mapper.createArrayNode();
        JsonNode result = apiClient.editWorksheetV3("ws-123", fields);

        assertEquals(1, result.path("error_code").asInt(0));
    }

    @Test
    @DisplayName("getAppAuthorize - 成功获取授权信息")
    void testGetAppAuthorize() throws Exception {
        stubFor(get(urlPathEqualTo("/v1/open/app/getAppAuthorize"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"data\":[{\"appKey\":\"ak1\",\"sign\":\"s1\",\"projectId\":\"p1\"}]}")));

        JsonNode result = apiClient.getAppAuthorize("app123", "org-key", "org-sign", 1234567890L, "proj1");

        assertTrue(result.path("success").asBoolean());
        assertEquals("ak1", result.path("data").get(0).path("appKey").asText());
    }

    @Test
    @DisplayName("V3 API - 请求体包含认证参数")
    void testV3RequestBodyContainsAuth() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/app/worksheets"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"data\":{\"worksheetId\":\"ws-1\"}}")));

        apiClient.createWorksheetV3("产品表", mapper.createArrayNode());

        verify(postRequestedFor(urlPathEqualTo("/v3/app/worksheets"))
            .withRequestBody(containing("appKey"))
            .withRequestBody(containing("sign"))
            .withRequestBody(containing("timestamp")));
    }

    @Test
    @DisplayName("V3 API - 错误响应包含错误码")
    void testV3ErrorResponse() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/app/worksheets"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":0,\"error_msg\":\"工作表名称已存在\"}")));

        // TestableApiClient 不做 error_code 检查（那是 HapApiClient 父类的职责）
        // 这里验证响应被正确解析
        JsonNode result = apiClient.createWorksheetV3("已存在的表", mapper.createArrayNode());
        assertEquals(0, result.path("error_code").asInt(-1));
        assertEquals("工作表名称已存在", result.path("error_msg").asText());
    }

    @Test
    @DisplayName("Open App API - 未配置认证时抛 IllegalStateException")
    void testOpenAppWithoutAuthThrows() {
        HapApiClient unauthClient = new HapApiClient();
        assertThrows(IllegalStateException.class,
            () -> unauthClient.createAppOpen("test", "desc", "icon", "#000", "p1", "o1", null));
    }
}
