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

import com.hap.automaker.config.Jacksons;

class UpdateWorksheetIconsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void dryRunBuildsPayloadsFromWorksheetMeta() throws Exception {
        UpdateWorksheetIconsApiHandler apiHandler = new UpdateWorksheetIconsApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app", apiHandler);
            server.createContext("/api/AppManagement/EditWorkSheetInfoForApp", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-dry-run");
            UpdateWorksheetIconsService service = new UpdateWorksheetIconsService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.updateIcons(
                    repo,
                    "app-123",
                    List.of(
                            new UpdateWorksheetIconsService.IconMapping("ws-001", "sys_6_1_user_group"),
                            new UpdateWorksheetIconsService.IconMapping("ws-002", "sys_8_4_folder")),
                    true);

            assertTrue(result.path("dryRun").asBoolean());
            assertEquals(2, result.path("results").size());
            assertEquals("section-1", result.path("results").path(0).path("payload").path("appSectionId").asText());
            assertEquals("Customers", result.path("results").path(0).path("payload").path("workSheetName").asText());
            assertEquals(0, apiHandler.editCalls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executesIconUpdates() throws Exception {
        UpdateWorksheetIconsApiHandler apiHandler = new UpdateWorksheetIconsApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app", apiHandler);
            server.createContext("/api/AppManagement/EditWorkSheetInfoForApp", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-execute");
            UpdateWorksheetIconsService service = new UpdateWorksheetIconsService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.updateIcons(
                    repo,
                    "app-123",
                    List.of(new UpdateWorksheetIconsService.IconMapping("ws-001", "sys_6_1_user_group")),
                    false);

            assertEquals(1, apiHandler.editCalls);
            assertEquals(1, result.path("results").size());
            assertEquals(200, result.path("results").path(0).path("statusCode").asInt());
            assertEquals("sys_6_1_user_group", apiHandler.lastPayload.path("icon").asText());
        } finally {
            server.stop(0);
        }
    }

    private Path prepareRepo(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("app_authorizations"));
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

    private static final class UpdateWorksheetIconsApiHandler implements HttpHandler {
        private int editCalls = 0;
        private JsonNode lastPayload;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/v3/app".equals(path)) {
                writeJson(exchange, """
                        {
                          "success": true,
                          "data": {
                            "sections": [
                              {
                                "id": "section-1",
                                "name": "默认分组",
                                "items": [
                                  { "id": "ws-001", "name": "Customers", "type": 0 },
                                  { "id": "ws-002", "name": "Orders", "type": 0 }
                                ],
                                "childSections": []
                              }
                            ]
                          }
                        }
                        """);
                return;
            }
            if ("/api/AppManagement/EditWorkSheetInfoForApp".equals(path)) {
                editCalls++;
                lastPayload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                writeJson(exchange, """
                        {
                          "state": 1,
                          "data": true
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
