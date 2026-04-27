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

class PagePipelineServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void plansPagesCreatesPageAndRunsChartPipelineWithoutPythonPlanner() throws Exception {
        PageApiHandler apiHandler = new PageApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/Worksheet/GetWorksheetInfo", apiHandler);
            server.createContext("/api/HomeApp/GetApp", apiHandler);
            server.createContext("/api/Worksheet/GetWorksheetControls", apiHandler);
            server.createContext("/api/AppManagement/AddWorkSheet", apiHandler);
            server.createContext("/report/custom/savePage", apiHandler);
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

            Path outputJson = repo.resolve("data").resolve("outputs").resolve("java_phase1").resolve("page_create_result.json");
            Path planOutput = repo.resolve("data").resolve("outputs").resolve("java_phase1").resolve("page_plan.json");

            PagePipelineService service = new PagePipelineService(
                    new FakeAiTextClient(),
                    new FakeChartPipelineRunner(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            PagePipelineResult result = service.run(repo, "ws-001", planOutput, outputJson);

            assertEquals(outputJson, result.outputJsonPath());
            assertTrue(Files.exists(outputJson));
            JsonNode payload = Jacksons.mapper().readTree(outputJson.toFile());
            assertEquals("success", payload.path("results").path(0).path("status").asText());
            assertEquals("page-001", payload.path("results").path(0).path("pageId").asText());
            assertTrue(payload.path("results").path(0).path("chartPlanFile").asText().endsWith(".json"));
            assertTrue(payload.path("results").path(0).path("chartCreateFile").asText().endsWith(".json"));
            assertEquals("app-123", payload.path("appId").asText());

            assertEquals(1, apiHandler.addWorksheetPayloads.size());
            assertEquals(1, apiHandler.savePagePayloads.size());
            assertEquals("page-001", apiHandler.savePagePayloads.get(0).path("appId").asText());
            assertEquals("app-123", apiHandler.addWorksheetPayloads.get(0).path("appId").asText());
            assertEquals("project-1", apiHandler.addWorksheetPayloads.get(0).path("projectId").asText());
            assertEquals("section-1", apiHandler.addWorksheetPayloads.get(0).path("appSectionId").asText());
        } finally {
            server.stop(0);
        }
    }

    private static final class FakeAiTextClient implements AiTextClient {
        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            return """
                    {
                      "appId": "wrong-app",
                      "appName": "Wrong Name",
                      "projectId": "wrong-project",
                      "appSectionId": "wrong-section",
                      "pages": [
                        {
                          "name": "Overview",
                          "icon": "sys_dashboard",
                          "iconColor": "#2196F3",
                          "desc": "Business overview",
                          "worksheetNames": ["Customers", "Orders"]
                        }
                      ]
                    }
                    """;
        }
    }

    private static final class FakeChartPipelineRunner implements ChartPipelineRunner {
        @Override
        public ChartPipelineResult run(
                Path repoRoot,
                String appId,
                String appName,
                List<String> worksheetIds,
                String pageId,
                Path planOutput,
                Path outputJson) throws Exception {
            Files.createDirectories(planOutput.getParent());
            Jacksons.mapper().writeValue(planOutput.toFile(), Map.of("charts", List.of()));
            Files.createDirectories(outputJson.getParent());
            JsonNode summary = Jacksons.mapper().readTree("""
                    {
                      "results": [
                        {
                          "status": "success"
                        }
                      ]
                    }
                    """);
            Jacksons.mapper().writeValue(outputJson.toFile(), summary);
            return new ChartPipelineResult(planOutput, outputJson, summary);
        }
    }

    private static final class PageApiHandler implements HttpHandler {
        private final List<JsonNode> addWorksheetPayloads = new ArrayList<>();
        private final List<JsonNode> savePagePayloads = new ArrayList<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/api/Worksheet/GetWorksheetInfo".equals(path)) {
                writeJson(exchange, """
                        {
                          "data": {
                            "appId": "app-123",
                            "appName": "Demo App",
                            "groupId": "section-1",
                            "projectId": "project-1"
                          }
                        }
                        """);
                return;
            }
            if ("/api/HomeApp/GetApp".equals(path)) {
                writeJson(exchange, """
                        {
                          "data": {
                            "appId": "app-123",
                            "name": "Demo App",
                            "projectId": "project-1",
                            "sections": [
                              {
                                "name": "Dashboard",
                                "appSectionId": "section-1",
                                "workSheetInfo": [
                                  {
                                    "workSheetId": "ws-001",
                                    "workSheetName": "Customers",
                                    "type": 0
                                  },
                                  {
                                    "workSheetId": "ws-002",
                                    "workSheetName": "Orders",
                                    "type": 0
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """);
                return;
            }
            if ("/api/Worksheet/GetWorksheetControls".equals(path)) {
                JsonNode request = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                String worksheetId = request.path("worksheetId").asText("");
                if ("ws-001".equals(worksheetId)) {
                    writeJson(exchange, """
                            {
                              "data": {
                                "worksheetName": "Customers",
                                "controls": [
                                  { "id": "c_name", "name": "Customer Name", "controlId": "c_name", "controlName": "Customer Name", "type": 2, "isTitle": true },
                                  { "id": "c_level", "name": "Customer Level", "controlId": "c_level", "controlName": "Customer Level", "type": 9, "options": [ { "value": "VIP" } ] }
                                ]
                              }
                            }
                            """);
                } else {
                    writeJson(exchange, """
                            {
                              "data": {
                                "worksheetName": "Orders",
                                "controls": [
                                  { "id": "o_name", "name": "Order No", "controlId": "o_name", "controlName": "Order No", "type": 2, "isTitle": true },
                                  { "id": "o_date", "name": "Created At", "controlId": "o_date", "controlName": "Created At", "type": 16 }
                                ]
                              }
                            }
                            """);
                }
                return;
            }
            if ("/api/AppManagement/AddWorkSheet".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                addWorksheetPayloads.add(payload);
                writeJson(exchange, """
                        {
                          "data": {
                            "pageId": "page-001"
                          },
                          "state": 1
                        }
                        """);
                return;
            }
            if ("/report/custom/savePage".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                savePagePayloads.add(payload);
                writeJson(exchange, """
                        {
                          "status": 1,
                          "data": {
                            "version": 1
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
