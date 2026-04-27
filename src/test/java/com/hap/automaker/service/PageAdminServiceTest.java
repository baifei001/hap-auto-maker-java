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

class PageAdminServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void getsPageByIdUsingWebAuthJson() throws Exception {
        PageAdminApiHandler apiHandler = new PageAdminApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/report/custom/getPage", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-get");
            PageAdminService service = new PageAdminService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode page = service.getPage(repo, "page-001");

            assertEquals("page-001", apiHandler.lastRequestedPageId);
            assertEquals(3, page.path("version").asInt());
            assertEquals(1, page.path("components").size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void savesBlankPageLayout() throws Exception {
        PageAdminApiHandler apiHandler = new PageAdminApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/report/custom/savePage", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-save");
            PageAdminService service = new PageAdminService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode response = service.saveBlankPage(repo, "page-001", 0);

            assertEquals("page-001", apiHandler.savePagePayloads.get(0).path("appId").asText());
            assertEquals(0, apiHandler.savePagePayloads.get(0).path("version").asInt());
            assertEquals(48, apiHandler.savePagePayloads.get(0).path("config").path("webNewCols").asInt());
            assertEquals(1, response.path("status").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deletesPageWithExpectedPayload() throws Exception {
        PageAdminApiHandler apiHandler = new PageAdminApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/AppManagement/RemoveWorkSheetForApp", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-delete");
            PageAdminService service = new PageAdminService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.deletePage(repo, "app-123", "section-1", "page-001", "project-1", true);

            JsonNode payload = apiHandler.deletePagePayloads.get(0);
            assertEquals("app-123", payload.path("appId").asText());
            assertEquals("section-1", payload.path("appSectionId").asText());
            assertEquals("page-001", payload.path("workSheetId").asText());
            assertEquals("project-1", payload.path("projectId").asText());
            assertTrue(payload.path("isPermanentlyDelete").asBoolean());
            assertTrue(result.path("ok").asBoolean());
            assertEquals("page-001", result.path("pageId").asText());
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

    private static final class PageAdminApiHandler implements HttpHandler {
        private final List<JsonNode> savePagePayloads = new ArrayList<>();
        private final List<JsonNode> deletePagePayloads = new ArrayList<>();
        private String lastRequestedPageId = "";

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/report/custom/getPage".equals(path)) {
                lastRequestedPageId = exchange.getRequestURI().getQuery().replace("appId=", "");
                writeJson(exchange, """
                        {
                          "status": 1,
                          "data": {
                            "version": 3,
                            "components": [
                              { "id": "component-1" }
                            ]
                          }
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
            if ("/api/AppManagement/RemoveWorkSheetForApp".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                deletePagePayloads.add(payload);
                writeJson(exchange, """
                        {
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
