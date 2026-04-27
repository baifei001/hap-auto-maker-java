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

class DeleteDefaultViewsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void dryRunCollectsDefaultViewsWithoutDeleting() throws Exception {
        DeleteDefaultViewsApiHandler apiHandler = new DeleteDefaultViewsApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app", apiHandler);
            server.createContext("/v3/app/worksheets/ws-001", apiHandler);
            server.createContext("/v3/app/worksheets/ws-002", apiHandler);
            server.createContext("/api/Worksheet/DeleteWorksheetView", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-dry-run");
            DeleteDefaultViewsService service = new DeleteDefaultViewsService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    new ViewAdminService("http://127.0.0.1:" + server.getAddress().getPort()));

            JsonNode result = service.deleteDefaultViews(repo, "app-123", true, false);

            assertTrue(result.path("dryRun").asBoolean());
            assertEquals(1, result.path("matchedViews").size());
            assertEquals("全部", result.path("matchedViews").path(0).path("viewName").asText());
            assertEquals(0, apiHandler.deleteCalls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deletesMatchedDefaultViewsWhenNotDryRun() throws Exception {
        DeleteDefaultViewsApiHandler apiHandler = new DeleteDefaultViewsApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app", apiHandler);
            server.createContext("/v3/app/worksheets/ws-001", apiHandler);
            server.createContext("/v3/app/worksheets/ws-002", apiHandler);
            server.createContext("/api/Worksheet/DeleteWorksheetView", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-delete");
            DeleteDefaultViewsService service = new DeleteDefaultViewsService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    new ViewAdminService("http://127.0.0.1:" + server.getAddress().getPort()));

            JsonNode result = service.deleteDefaultViews(repo, "app-123", false, false);

            assertEquals(1, apiHandler.deleteCalls);
            assertEquals(1, result.path("deletedCount").asInt());
            assertEquals("view-default", result.path("matchedViews").path(0).path("viewId").asText());
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

    private static final class DeleteDefaultViewsApiHandler implements HttpHandler {
        private int deleteCalls = 0;

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
            if ("/v3/app/worksheets/ws-001".equals(path)) {
                writeJson(exchange, """
                        {
                          "success": true,
                          "data": {
                            "views": [
                              { "id": "view-default", "name": "全部", "type": 0 },
                              { "id": "view-custom", "name": "活跃客户", "type": 0 }
                            ]
                          }
                        }
                        """);
                return;
            }
            if ("/v3/app/worksheets/ws-002".equals(path)) {
                writeJson(exchange, """
                        {
                          "success": true,
                          "data": {
                            "views": [
                              { "id": "view-orders", "name": "订单列表", "type": 0 }
                            ]
                          }
                        }
                        """);
                return;
            }
            if ("/api/Worksheet/DeleteWorksheetView".equals(path)) {
                deleteCalls++;
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
