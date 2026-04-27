package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

class IncrementalAddWorksheetServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void addWorksheetSupportsPlanOnlyMode() throws Exception {
        WorksheetAppInfoHandler apiHandler = new WorksheetAppInfoHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-plan");
            RecordingWorksheetCreator creator = new RecordingWorksheetCreator();
            AddWorksheetService service = new AddWorksheetService(
                    new FakeAiTextClient(),
                    creator,
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.addWorksheet(
                    repo,
                    "app-123",
                    "Leave Requests",
                    "员工请假申请与审批流程",
                    false);

            assertEquals("plan_only", result.path("status").asText());
            assertEquals("app-123", result.path("appId").asText());
            assertEquals("Leave Requests", result.path("plan").path("worksheets").path(0).path("name").asText());
            assertEquals("Customers", result.path("plan").path("worksheets").path(0).path("fields").path(2).path("relation_target").asText());
            assertTrue(Files.exists(Path.of(result.path("planFile").asText())));
            assertEquals(0, creator.callCount);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void addWorksheetExecutesViaWorksheetCreator() throws Exception {
        WorksheetAppInfoHandler apiHandler = new WorksheetAppInfoHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-execute");
            RecordingWorksheetCreator creator = new RecordingWorksheetCreator();
            AddWorksheetService service = new AddWorksheetService(
                    new FakeAiTextClient(),
                    creator,
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.addWorksheet(
                    repo,
                    "app-123",
                    "Leave Requests",
                    "员工请假申请与审批流程",
                    true);

            assertEquals("success", result.path("status").asText());
            assertEquals(1, creator.callCount);
            assertTrue(Files.exists(Path.of(result.path("outputFile").asText())));
            assertEquals("ws-leave", result.path("createResult").path("name_to_worksheet_id").path("Leave Requests").asText());
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
                      "worksheets": [
                        {
                          "name": "Leave Requests",
                          "purpose": "员工请假审批",
                          "fields": [
                            {
                              "name": "Request Title",
                              "type": "Text",
                              "required": true,
                              "description": "申请标题",
                              "option_values": []
                            },
                            {
                              "name": "Leave Type",
                              "type": "SingleSelect",
                              "required": true,
                              "description": "请假类型",
                              "option_values": ["年假", "病假", "事假"]
                            },
                            {
                              "name": "Customer",
                              "type": "Relation",
                              "required": false,
                              "description": "关联客户",
                              "relation_target": "Customers",
                              "option_values": []
                            }
                          ],
                          "depends_on": ["Customers"]
                        }
                      ],
                      "relationships": [],
                      "creation_order": ["Leave Requests"]
                    }
                    """;
        }
    }

    private static final class RecordingWorksheetCreator implements WorksheetCreator {
        private int callCount = 0;

        @Override
        public WorksheetCreateResult createFromPlan(Path repoRoot, Path planJson, Path appAuthJson, Path outputJson) throws Exception {
            callCount++;
            JsonNode summary = Jacksons.mapper().readTree("""
                    {
                      "name_to_worksheet_id": {
                        "Leave Requests": "ws-leave"
                      }
                    }
                    """);
            Files.createDirectories(outputJson.getParent());
            Jacksons.mapper().writeValue(outputJson.toFile(), summary);
            return new WorksheetCreateResult(outputJson, summary);
        }
    }

    private static final class WorksheetAppInfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/v3/app".equals(path)) {
                writeJson(exchange, """
                        {
                          "data": {
                            "sections": [
                              {
                                "items": [
                                  {
                                    "id": "ws-customers",
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
