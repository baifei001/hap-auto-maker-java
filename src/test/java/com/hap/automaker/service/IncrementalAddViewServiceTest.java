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

class IncrementalAddViewServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void addSingleViewSupportsAiPlanningWithoutExecute() throws Exception {
        IncrementalViewApiHandler apiHandler = new IncrementalViewApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/Worksheet/GetWorksheetControls", apiHandler);
            server.createContext("/api/Worksheet/SaveWorksheetView", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-plan");
            ViewPipelineService service = new ViewPipelineService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.addSingleView(
                    repo,
                    "app-123",
                    "ws-001",
                    "",
                    null,
                    "",
                    "按创建时间展示客户日历视图",
                    List.of(),
                    false);

            assertEquals("plan_only", result.path("status").asText());
            assertEquals("Customers Calendar", result.path("view").path("name").asText());
            assertEquals(4, result.path("view").path("viewType").asInt());
            assertEquals(0, apiHandler.savePayloads.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void addSingleViewCreatesExplicitView() throws Exception {
        IncrementalViewApiHandler apiHandler = new IncrementalViewApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/Worksheet/GetWorksheetControls", apiHandler);
            server.createContext("/api/Worksheet/SaveWorksheetView", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-create");
            ViewPipelineService service = new ViewPipelineService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.addSingleView(
                    repo,
                    "app-123",
                    "ws-001",
                    "By Status",
                    1,
                    "field-status",
                    "",
                    List.of("field-title", "field-status"),
                    true);

            assertEquals("success", result.path("status").asText());
            assertEquals("view-001", result.path("view").path("viewId").asText());
            assertEquals(1, apiHandler.savePayloads.size());
            assertEquals("By Status", apiHandler.savePayloads.get(0).path("name").asText());
            assertEquals("field-status", apiHandler.savePayloads.get(0).path("viewControl").asText());
        } finally {
            server.stop(0);
        }
    }

    private Path prepareRepo(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
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
        return repo;
    }

    private static final class FakeAiTextClient implements AiTextClient {
        private int calls = 0;

        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            calls++;
            return switch (calls) {
                case 1 -> """
                        {
                          "views": [
                            {
                              "viewType": 4,
                              "name": "Customers Calendar",
                              "reason": "Track customer creation time"
                            }
                          ]
                        }
                        """;
                case 2 -> """
                        {
                          "viewType": 4,
                          "name": "Customers Calendar",
                          "displayControls": ["field-title", "field-date", "field-status"],
                          "viewControl": "",
                          "advancedSetting": {
                            "calendarType": "1",
                            "enablerules": "1"
                          },
                          "postCreateUpdates": []
                        }
                        """;
                default -> throw new AssertionError("Unexpected AI call count");
            };
        }
    }

    private static final class IncrementalViewApiHandler implements HttpHandler {
        private final List<JsonNode> savePayloads = new ArrayList<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
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
                                },
                                {
                                  "controlId": "field-status",
                                  "controlName": "Status",
                                  "id": "field-status",
                                  "name": "Status",
                                  "type": 11,
                                  "required": false,
                                  "options": [
                                    { "key": "1", "value": "New" },
                                    { "key": "2", "value": "Active" }
                                  ]
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
                writeJson(exchange, """
                        {
                          "data": {
                            "viewId": "view-001"
                          },
                          "state": 1
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
