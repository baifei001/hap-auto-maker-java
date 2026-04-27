package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.hap.automaker.config.Jacksons;

class AppBootstrapServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsAppAndWritesAuthorizeFile() throws Exception {
        Map<String, JsonNode> captured = new HashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v1/open/app/create", new CreateHandler(captured));
            server.createContext("/v1/open/app/getAppAuthorize", new GetAuthorizeHandler(captured));
            server.start();

            Path repo = tempDir.resolve("repo");
            Files.createDirectories(repo.resolve("config").resolve("credentials"));
            Jacksons.mapper().writeValue(
                    repo.resolve("config").resolve("credentials").resolve("organization_auth.json").toFile(),
                    Map.of(
                            "app_key", "org-app-key",
                            "secret_key", "org-secret-key",
                            "project_id", "project-1",
                            "owner_id", "owner-1",
                            "group_ids", "group-a"));

            AppBootstrapService service = new AppBootstrapService("http://127.0.0.1:" + server.getAddress().getPort());
            AppBootstrapResult result = service.createAndAuthorize(
                    repo,
                    "Demo App",
                    "group-a",
                    "sys_test_icon",
                    "#2196F3");

            assertEquals("app-123", result.appId());
            assertTrue(Files.exists(result.appAuthJsonPath()));
            JsonNode storedAuth = Jacksons.mapper().readTree(result.appAuthJsonPath().toFile());
            assertEquals("app-123", storedAuth.path("data").path(0).path("appId").asText());

            JsonNode createPayload = captured.get("createPayload");
            assertEquals("Demo App", createPayload.path("name").asText());
            assertEquals("project-1", createPayload.path("projectId").asText());
            assertEquals("owner-1", createPayload.path("ownerId").asText());
            assertEquals("sys_test_icon", createPayload.path("icon").asText());
            assertEquals("#2196F3", createPayload.path("color").asText());
            assertTrue(createPayload.path("groupIds").isArray());
            assertFalse(createPayload.path("sign").asText().isBlank());

            JsonNode authorizeQuery = captured.get("authorizeQuery");
            assertEquals("app-123", authorizeQuery.path("appId").asText());
            assertEquals("project-1", authorizeQuery.path("projectId").asText());
            assertFalse(authorizeQuery.path("sign").asText().isBlank());
        } finally {
            server.stop(0);
        }
    }

    private static final class CreateHandler implements HttpHandler {
        private final Map<String, JsonNode> captured;

        private CreateHandler(Map<String, JsonNode> captured) {
            this.captured = captured;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            JsonNode payload = Jacksons.mapper().readTree(bodyBytes);
            captured.put("createPayload", payload);
            byte[] response = """
                    {
                      "data": {
                        "appId": "app-123",
                        "name": "Demo App"
                      },
                      "success": true,
                      "error_code": 1
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }

    private static final class GetAuthorizeHandler implements HttpHandler {
        private final Map<String, JsonNode> captured;

        private GetAuthorizeHandler(Map<String, JsonNode> captured) {
            this.captured = captured;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            captured.put("authorizeQuery", parseQuery(exchange.getRequestURI()));
            byte[] response = """
                    {
                      "data": [
                        {
                          "projectId": "project-1",
                          "appId": "app-123",
                          "appKey": "generated-app-key",
                          "sign": "generated-sign",
                          "type": 1,
                          "name": "Demo App"
                        }
                      ],
                      "success": true,
                      "error_code": 1
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private JsonNode parseQuery(URI uri) throws IOException {
            Map<String, String> values = new HashMap<>();
            String raw = uri.getRawQuery();
            if (raw != null && !raw.isBlank()) {
                for (String pair : raw.split("&")) {
                    String[] parts = pair.split("=", 2);
                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                    values.put(key, value);
                }
            }
            return Jacksons.mapper().valueToTree(values);
        }
    }
}
