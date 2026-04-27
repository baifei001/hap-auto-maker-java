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

class IncrementalAddFieldServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void addFieldSupportsPlanOnlyMode() throws Exception {
        FieldApiHandler apiHandler = new FieldApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/Worksheet/GetWorksheetControls", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-plan");
            AddFieldService service = new AddFieldService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.addField(
                    repo,
                    "app-123",
                    "ws-001",
                    "审批状态",
                    "",
                    "记录审批进度：待审批/审批中/已通过/已拒绝",
                    false,
                    java.util.List.of(),
                    false);

            assertEquals("plan_only", result.path("status").asText());
            assertEquals("审批状态", result.path("fieldPlan").path("name").asText());
            assertEquals("SingleSelect", result.path("fieldPlan").path("type").asText());
            assertEquals(4, result.path("fieldPlan").path("option_values").size());
            assertEquals("account-1", apiHandler.lastAccountIdHeader);
            assertEquals("auth-1", apiHandler.lastAuthorizationHeader);
            assertEquals("cookie-1", apiHandler.lastCookieHeader);
            assertTrue(Files.exists(Path.of(result.path("planFile").asText())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void addFieldExecutesViaSaveWorksheetControls() throws Exception {
        FieldApiHandler apiHandler = new FieldApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/Worksheet/GetWorksheetControls", apiHandler);
            server.createContext("/api/Worksheet/SaveWorksheetControls", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-execute");
            AddFieldService service = new AddFieldService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.addField(
                    repo,
                    "app-123",
                    "ws-001",
                    "审批状态",
                    "SingleSelect",
                    "记录审批进度",
                    false,
                    java.util.List.of("待审批", "审批中", "已通过", "已拒绝"),
                    true);

            assertEquals("success", result.path("status").asText());
            assertEquals("field-new", result.path("field").path("controlId").asText());
            assertEquals("审批状态", result.path("field").path("controlName").asText());
            assertEquals(9, apiHandler.savedPayload.path("controls").path(2).path("type").asInt());
            assertEquals(4, apiHandler.savedPayload.path("controls").path(2).path("options").size());
            assertEquals(0, apiHandler.savedPayload.path("controls").path(2).path("required").asInt());
            assertEquals("account-1", apiHandler.lastAccountIdHeader);
            assertEquals("auth-1", apiHandler.lastAuthorizationHeader);
            assertEquals("cookie-1", apiHandler.lastCookieHeader);
            assertTrue(Files.exists(Path.of(result.path("outputFile").asText())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void addFieldAcceptsStateBasedGetWorksheetControlsResponse() throws Exception {
        StateWrappedFieldApiHandler apiHandler = new StateWrappedFieldApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/Worksheet/GetWorksheetControls", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-state-wrapper");
            AddFieldService service = new AddFieldService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.addField(
                    repo,
                    "app-123",
                    "ws-001",
                    "审批状态",
                    "",
                    "记录审批进度：待审批、审批中、已通过、已拒绝",
                    false,
                    java.util.List.of(),
                    false);

            assertEquals("plan_only", result.path("status").asText());
            assertEquals("请假申请", result.path("worksheetName").asText());
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
                      "type": "SingleSelect",
                      "controlType": 9,
                      "required": true,
                      "option_values": ["待审批", "审批中", "已通过", "已拒绝"],
                      "reason": "审批状态最适合用单选字段"
                    }
                    """;
        }
    }

    private static final class FieldApiHandler implements HttpHandler {
        private JsonNode savedPayload;
        private String lastAccountIdHeader = "";
        private String lastAuthorizationHeader = "";
        private String lastCookieHeader = "";

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            lastAccountIdHeader = exchange.getRequestHeaders().getFirst("AccountId");
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastCookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String path = exchange.getRequestURI().getPath();
            if ("/api/Worksheet/GetWorksheetControls".equals(path)) {
                writeJson(exchange, """
                        {
                          "error_code": 1,
                          "data": {
                            "data": {
                              "worksheetName": "请假申请",
                              "version": 7,
                              "controls": [
                                {
                                  "controlId": "field-title",
                                  "controlName": "标题",
                                  "type": 2
                                },
                                {
                                  "controlId": "field-owner",
                                  "controlName": "申请人",
                                  "type": 26
                                }
                              ]
                            }
                          }
                        }
                        """);
                return;
            }
            if ("/api/Worksheet/SaveWorksheetControls".equals(path)) {
                savedPayload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                writeJson(exchange, """
                        {
                          "error_code": 1,
                          "data": {
                            "data": {
                              "controls": [
                                {
                                  "controlId": "field-title",
                                  "controlName": "标题",
                                  "type": 2
                                },
                                {
                                  "controlId": "field-owner",
                                  "controlName": "申请人",
                                  "type": 26
                                },
                                {
                                  "controlId": "field-new",
                                  "controlName": "审批状态",
                                  "type": 9
                                }
                              ]
                            }
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

    private static final class StateWrappedFieldApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/api/Worksheet/GetWorksheetControls".equals(path)) {
                writeJson(exchange, """
                        {
                          "state": 1,
                          "data": {
                            "code": 1,
                            "data": {
                              "worksheetName": "请假申请",
                              "version": 7,
                              "controls": [
                                {
                                  "controlId": "field-title",
                                  "controlName": "标题",
                                  "type": 2
                                }
                              ]
                            }
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
