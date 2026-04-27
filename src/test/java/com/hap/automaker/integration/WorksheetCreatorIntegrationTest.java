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
 * WorksheetCreator 集成测试 - 验证工作表创建流程 API 交互格式
 */
@WireMockTest
class WorksheetCreatorIntegrationTest {

    private HapApiClientIntegrationTest.TestableApiClient apiClient;
    private final ObjectMapper mapper = Jacksons.mapper();

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        apiClient = new HapApiClientIntegrationTest.TestableApiClient(wmInfo.getHttpBaseUrl());
    }

    @Test
    @DisplayName("创建工作表 - 请求体包含名称和字段")
    void testCreateWorksheetRequestBodyFormat() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/app/worksheets"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"data\":{\"worksheetId\":\"ws-new-1\"}}")));

        ArrayNode fields = mapper.createArrayNode();
        ObjectNode textField = mapper.createObjectNode();
        textField.put("name", "名称");
        textField.put("type", 2);
        fields.add(textField);

        apiClient.createWorksheetV3("产品表", fields);

        verify(postRequestedFor(urlPathEqualTo("/v3/app/worksheets"))
            .withRequestBody(containing("产品表"))
            .withRequestBody(containing("名称")));
    }

    @Test
    @DisplayName("编辑工作表 - PUT 请求包含字段")
    void testEditWorksheetPutRequest() throws Exception {
        stubFor(put(urlPathEqualTo("/v3/app/worksheets/ws-123"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"data\":{}}")));

        ArrayNode fields = mapper.createArrayNode();
        ObjectNode relationField = mapper.createObjectNode();
        relationField.put("name", "关联订单");
        relationField.put("type", 20);
        fields.add(relationField);

        apiClient.editWorksheetV3("ws-123", fields);

        verify(putRequestedFor(urlPathEqualTo("/v3/app/worksheets/ws-123"))
            .withRequestBody(containing("关联订单")));
    }

    @Test
    @DisplayName("删除工作表 - DELETE 请求包含认证参数")
    void testDeleteWorksheetDeleteRequest() throws Exception {
        stubFor(delete(urlPathEqualTo("/v3/app/worksheets/ws-del"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"data\":{}}")));

        apiClient.deleteWorksheetV3("ws-del");

        verify(deleteRequestedFor(urlPathEqualTo("/v3/app/worksheets/ws-del"))
            .withQueryParam("appKey", equalTo("test-app-key")));
    }

    @Test
    @DisplayName("创建工作表 - 成功返回 worksheetId")
    void testCreateWorksheetReturnsId() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/app/worksheets"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":1,\"error_msg\":\"\",\"data\":{\"worksheetId\":\"ws-created-42\"}}")));

        JsonNode result = apiClient.createWorksheetV3("新表", mapper.createArrayNode());

        assertEquals("ws-created-42", result.path("data").path("worksheetId").asText());
    }

    @Test
    @DisplayName("创建工作表 - 重复名称返回错误码")
    void testCreateDuplicateWorksheetError() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/app/worksheets"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":0,\"error_msg\":\"工作表名称已存在\"}")));

        JsonNode result = apiClient.createWorksheetV3("已存在的表", mapper.createArrayNode());
        assertEquals(0, result.path("error_code").asInt(-1));
        assertEquals("工作表名称已存在", result.path("error_msg").asText());
    }
}
