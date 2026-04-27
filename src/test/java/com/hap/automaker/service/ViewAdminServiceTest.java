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

class ViewAdminServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesViewWithExpectedPayload() throws Exception {
        ViewAdminApiHandler apiHandler = new ViewAdminApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/Worksheet/DeleteWorksheetView", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-delete-view");
            ViewAdminService service = new ViewAdminService("http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.deleteView(repo, "app-123", "ws-001", "view-001");

            JsonNode payload = apiHandler.deleteViewPayloads.get(0);
            assertEquals("app-123", payload.path("appId").asText());
            assertEquals("ws-001", payload.path("worksheetId").asText());
            assertEquals("view-001", payload.path("viewId").asText());
            assertEquals(9, payload.path("status").asInt());
            assertTrue(result.path("ok").asBoolean());
            assertEquals("view-001", result.path("viewId").asText());
        } finally {
            server.stop(0);
        }
    }

    private Path prepareRepo(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
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

    private static final class ViewAdminApiHandler implements HttpHandler {
        private final List<JsonNode> deleteViewPayloads = new ArrayList<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/api/Worksheet/DeleteWorksheetView".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                deleteViewPayloads.add(payload);
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
