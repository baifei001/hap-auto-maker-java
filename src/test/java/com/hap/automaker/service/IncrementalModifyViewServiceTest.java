package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;

class IncrementalModifyViewServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void modifySingleViewSupportsPlanOnlyMode() throws Exception {
        ModifyViewApiHandler apiHandler = new ModifyViewApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app/worksheets/ws-001", apiHandler);
            server.createContext("/api/Worksheet/SaveWorksheetView", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-plan");
            ViewPipelineService service = new ViewPipelineService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.modifySingleView(
                    repo,
                    "app-123",
                    "ws-001",
                    "view-001",
                    "按状态分组并只显示关键字段",
                    false);

            assertEquals("plan_only", result.path("status").asText());
            assertEquals("view-001", result.path("viewId").asText());
            assertEquals("现有视图", result.path("currentView").path("name").asText());
            assertEquals("field-date", result.path("payload").path("sortCid").asText());
            assertTrue(Files.exists(Path.of(result.path("planFile").asText())));
            assertEquals(0, apiHandler.savePayloads.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modifySingleViewSavesMergedPayload() throws Exception {
        ModifyViewApiHandler apiHandler = new ModifyViewApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app/worksheets/ws-001", apiHandler);
            server.createContext("/api/Worksheet/SaveWorksheetView", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-execute");
            ViewPipelineService service = new ViewPipelineService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.modifySingleView(
                    repo,
                    "app-123",
                    "ws-001",
                    "view-001",
                    "按状态分组并只显示关键字段",
                    true);

            assertEquals("success", result.path("status").asText());
            assertEquals(1, apiHandler.savePayloads.size());
            JsonNode payload = apiHandler.savePayloads.get(0);
            assertEquals("view-001", payload.path("viewId").asText());
            assertEquals("现有视图", payload.path("name").asText());
            assertEquals(2, payload.path("displayControls").size());
            assertEquals("field-date", payload.path("sortCid").asText());
            assertTrue(payload.path("advancedSetting").path("groupView").asText().contains("\"viewId\":\"view-001\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modifySingleViewUsesGetWorksheetViewsWhenV3ViewPayloadIsMinimal() throws Exception {
        MinimalV3RichWebViewApiHandler apiHandler = new MinimalV3RichWebViewApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app/worksheets/ws-001", apiHandler);
            server.createContext("/api/Worksheet/GetWorksheetViews", apiHandler);
            server.createContext("/api/Worksheet/SaveWorksheetView", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-execute-rich-web");
            ViewPipelineService service = new ViewPipelineService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.modifySingleView(
                    repo,
                    "app-123",
                    "ws-001",
                    "view-001",
                    "按状态分组并只显示关键字段",
                    true);

            assertEquals("success", result.path("status").asText());
            assertTrue(result.path("currentViewComplete").asBoolean());
            assertEquals(1, apiHandler.savePayloads.size());
            JsonNode payload = apiHandler.savePayloads.get(0);
            assertEquals(2, payload.path("displayControls").size());
            assertEquals("field-date", payload.path("sortCid").asText());
        } finally {
            server.stop(0);
        }
    }

    private Path prepareRepo(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("app_authorizations"));
        Jacksons.mapper().writeValue(
                repo.resolve("config").resolve("credentials").resolve("ai_auth.json").toFile(),
                new AiAuthConfig("deepseek", "test-key", "deepseek-chat", "https://api.deepseek.com/v1"));
        Jacksons.mapper().writeValue(
                repo.resolve("config").resolve("credentials").resolve("web_auth.json").toFile(),
                Map.of(
                        "account_id", "account-1",
                        "authorization", "auth-1",
                        "cookie", "cookie-1",
                        "login_account", "",
                        "login_password", "",
                        "login_url", ""));
        Jacksons.mapper().writeValue(
                repo.resolve("data").resolve("outputs").resolve("app_authorizations").resolve("app_authorize_test.json").toFile(),
                Jacksons.mapper().readTree("""
                        {
                          "data": [
                            {
                              "appId": "app-123",
                              "appKey": "generated-app-key",
                              "sign": "generated-sign",
                              "name": "Demo App"
                            }
                          ],
                          "success": true,
                          "error_code": 1
                        }
                        """));
        return repo;
    }

    private static final class FakeAiTextClient implements AiTextClient {
        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            return """
                    {
                      "displayControls": ["field-title", "field-status"],
                      "advancedSetting": {
                        "groupView": "{\\"viewId\\":\\"\\",\\"groupFilters\\":[{\\"controlId\\":\\"field-status\\",\\"values\\":[],\\"dataType\\":11,\\"spliceType\\":1,\\"filterType\\":2,\\"dateRange\\":0,\\"minValue\\":\\"\\",\\"maxValue\\":\\"\\",\\"isGroup\\":true}],\\"navShow\\":true}"
                      },
                      "filters": [
                        {
                          "controlId": "field-status",
                          "dataType": 11,
                          "spliceType": 1,
                          "filterType": 2,
                          "values": ["Active"]
                        }
                      ],
                      "sortCid": "field-date",
                      "sortType": 1,
                      "reason": "按状态筛选并按日期排序"
                    }
                    """;
        }
    }

    private static final class ModifyViewApiHandler implements HttpHandler {
        private final List<JsonNode> savePayloads = new ArrayList<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/v3/app/worksheets/ws-001".equals(path)) {
                writeJson(exchange, """
                        {
                          "success": true,
                          "data": {
                            "worksheetName": "Customers",
                            "controls": [
                              {
                                "controlId": "field-title",
                                "controlName": "Customer Name",
                                "id": "field-title",
                                "name": "Customer Name",
                                "type": 2,
                                "isTitle": true
                              },
                              {
                                "controlId": "field-status",
                                "controlName": "Status",
                                "id": "field-status",
                                "name": "Status",
                                "type": 11,
                                "options": [
                                  { "key": "1", "value": "Active" },
                                  { "key": "2", "value": "Paused" }
                                ]
                              },
                              {
                                "controlId": "field-date",
                                "controlName": "Created At",
                                "id": "field-date",
                                "name": "Created At",
                                "type": 16
                              }
                            ],
                            "views": [
                              {
                                "viewId": "view-001",
                                "name": "现有视图",
                                "viewType": 0,
                                "displayControls": ["field-title"],
                                "filters": [],
                                "sortCid": "",
                                "sortType": 0,
                                "coverType": 0,
                                "controls": [],
                                "showControlName": true,
                                "advancedSetting": {
                                  "enablerules": "1"
                                }
                              }
                            ]
                          }
                        }
                        """);
                return;
            }
            if ("/api/Worksheet/SaveWorksheetView".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                savePayloads.add(payload);
                writeJson(exchange, """
                        {
                          "state": 1,
                          "data": {
                            "viewId": "view-001"
                          }
                        }
                        """);
                return;
            }
            throw new IOException("Unexpected path: " + path);
        }

        private void writeJson(HttpExchange exchange, String body) throws IOException {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }

    private static final class MinimalV3RichWebViewApiHandler implements HttpHandler {
        private final List<JsonNode> savePayloads = new ArrayList<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/v3/app/worksheets/ws-001".equals(path)) {
                writeJson(exchange, """
                        {
                          "success": true,
                          "data": {
                            "worksheetName": "Customers",
                            "controls": [
                              {
                                "controlId": "field-title",
                                "controlName": "Customer Name",
                                "id": "field-title",
                                "name": "Customer Name",
                                "type": 2,
                                "isTitle": true
                              },
                              {
                                "controlId": "field-status",
                                "controlName": "Status",
                                "id": "field-status",
                                "name": "Status",
                                "type": 11,
                                "options": [
                                  { "key": "1", "value": "Active" },
                                  { "key": "2", "value": "Paused" }
                                ]
                              },
                              {
                                "controlId": "field-date",
                                "controlName": "Created At",
                                "id": "field-date",
                                "name": "Created At",
                                "type": 16
                              }
                            ],
                            "views": [
                              {
                                "id": "view-001",
                                "name": "现有视图",
                                "type": 0
                              }
                            ]
                          }
                        }
                        """);
                return;
            }
            if ("/api/Worksheet/GetWorksheetViews".equals(path)) {
                writeJson(exchange, """
                        {
                          "state": 1,
                          "data": [
                            {
                              "viewId": "view-001",
                              "name": "现有视图",
                              "worksheetId": "ws-001",
                              "sortCid": "",
                              "sortType": 0,
                              "controls": [],
                              "filters": [],
                              "displayControls": ["field-title"],
                              "viewType": 0,
                              "viewControl": "",
                              "coverType": 0,
                              "showControlName": true,
                              "advancedSetting": {
                                "enablerules": "1"
                              },
                              "navGroup": []
                            }
                          ]
                        }
                        """);
                return;
            }
            if ("/api/Worksheet/SaveWorksheetView".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                savePayloads.add(payload);
                writeJson(exchange, """
                        {
                          "state": 1,
                          "data": {
                            "viewId": "view-001"
                          }
                        }
                        """);
                return;
            }
            throw new IOException("Unexpected path: " + path);
        }

        private void writeJson(HttpExchange exchange, String body) throws IOException {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }
}
