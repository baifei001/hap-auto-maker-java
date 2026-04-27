package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

class ViewPipelineServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runsRecommendConfigureAndCreateFlowWithoutPythonPlanners() throws Exception {
        ViewApiHandler apiHandler = new ViewApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app", apiHandler);
            server.createContext("/api/Worksheet/GetWorksheetControls", apiHandler);
            server.createContext("/api/Worksheet/SaveWorksheetView", apiHandler);
            server.start();

            Path repo = tempDir.resolve("repo");
            Files.createDirectories(repo.resolve("config").resolve("credentials"));
            Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("java_phase1"));
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

            Path appAuthJson = repo.resolve("data").resolve("outputs").resolve("app_authorizations")
                    .resolve("app_authorize_java_phase1.json");
            Files.createDirectories(appAuthJson.getParent());
            Jacksons.mapper().writeValue(appAuthJson.toFile(), Jacksons.mapper().readTree("""
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

            Path outputJson = repo.resolve("data").resolve("outputs").resolve("java_phase1")
                    .resolve("view_pipeline_result.json");

            ViewPipelineService service = new ViewPipelineService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            ViewPipelineResult result = service.run(repo, appAuthJson, outputJson);

            assertEquals(outputJson, result.outputJsonPath());
            assertTrue(Files.exists(outputJson));
            JsonNode payload = Jacksons.mapper().readTree(outputJson.toFile());
            assertEquals(1, payload.path("worksheets").size());
            assertEquals("Customers", payload.path("worksheets").path(0).path("worksheetName").asText());
            assertEquals(1, payload.path("worksheets").path(0).path("configs").size());
            assertEquals(1, payload.path("worksheets").path(0).path("creates").size());
            assertEquals("view-001", payload.path("worksheets").path(0).path("creates").path(0).path("viewId").asText());

            assertEquals(2, apiHandler.savePayloads.size());
            assertEquals("Customer Calendar", apiHandler.savePayloads.get(0).path("name").asText());
            assertEquals("view-001", apiHandler.savePayloads.get(1).path("viewId").asText());
        } finally {
            server.stop(0);
        }
    }

    private static final class FakeAiTextClient implements AiTextClient {
        private int calls = 0;

        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            calls++;
            return switch (calls) {
                case 1 -> """
                        {
                          "worksheetId": "ws-001",
                          "worksheetName": "Customers",
                          "available_view_types": [4],
                          "views": [
                            {
                              "viewType": 4,
                              "name": "Customer Calendar",
                              "reason": "Track customer creation dates"
                            }
                          ],
                          "stats": {
                            "elapsed_s": 1.0,
                            "ai_called": true
                          }
                        }
                        """;
                case 2 -> """
                        {
                          "viewType": 4,
                          "name": "Customer Calendar",
                          "displayControls": ["field-title", "field-date"],
                          "viewControl": "",
                          "coverCid": "",
                          "advancedSetting": {
                            "calendarType": "1",
                            "enablerules": "1"
                          },
                          "postCreateUpdates": [
                            {
                              "editAttrs": ["advancedSetting"],
                              "editAdKeys": ["calendarcids"],
                              "advancedSetting": {
                                "calendarcids": "[{\\"begin\\":\\"field-date\\",\\"end\\":\\"\\"}]"
                              }
                            }
                          ]
                        }
                        """;
                default -> throw new AssertionError("Unexpected AI call count");
            };
        }
    }

    private static final class ViewApiHandler implements HttpHandler {
        private final List<JsonNode> savePayloads = new ArrayList<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/v3/app".equals(path)) {
                writeJson(exchange, """
                        {
                          "data": {
                            "sections": [
                              {
                                "id": "section-1",
                                "name": "Default",
                                "items": [
                                  {
                                    "id": "ws-001",
                                    "name": "Customers",
                                    "type": 0
                                  }
                                ],
                                "childSections": []
                              }
                            ]
                          },
                          "success": true,
                          "error_code": 1
                        }
                        """);
                return;
            }
            if ("/api/Worksheet/GetWorksheetControls".equals(path)) {
                writeJson(exchange, """
                        {
                          "data": {
                            "data": {
                              "worksheetName": "Customers",
                              "controls": [
                                {
                                  "controlId": "field-title",
                                  "controlName": "Customer Name",
                                  "id": "field-title",
                                  "name": "Customer Name",
                                  "type": 2,
                                  "isTitle": true,
                                  "required": true
                                },
                                {
                                  "controlId": "field-date",
                                  "controlName": "Created At",
                                  "id": "field-date",
                                  "name": "Created At",
                                  "type": 16,
                                  "required": false
                                }
                              ]
                            },
                            "code": 1
                          }
                        }
                        """);
                return;
            }
            if ("/api/Worksheet/SaveWorksheetView".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                savePayloads.add(payload);
                if (payload.path("viewId").asText("").isBlank()) {
                    writeJson(exchange, """
                            {
                              "data": {
                                "viewId": "view-001"
                              },
                              "state": 1
                            }
                            """);
                } else {
                    writeJson(exchange, """
                            {
                              "data": {
                                "viewId": "view-001"
                              },
                              "state": 1
                            }
                            """);
                }
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
