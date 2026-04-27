package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

class WorksheetCreateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsWorksheetsAddsDeferredFieldsAndVerifiesRelations() throws Exception {
        Map<String, List<JsonNode>> editPayloadsByWorksheet = new HashMap<>();
        Map<String, JsonNode> createPayloadsByWorksheet = new HashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app", new AppInfoHandler());
            server.createContext(
                    "/v3/app/worksheets",
                    new WorksheetApiHandler(createPayloadsByWorksheet, editPayloadsByWorksheet));
            server.start();

            Path repo = tempDir.resolve("repo");
            Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("java_phase1"));

            Path planJson = repo.resolve("plan.json");
            Jacksons.mapper().writeValue(planJson.toFile(), Jacksons.mapper().readTree("""
                    {
                      "worksheets": [
                        {
                          "name": "Customers",
                          "fields": [
                            { "name": "Customer Name", "type": "Text", "required": true },
                            { "name": "Phone", "type": "Phone", "required": false }
                          ]
                        },
                        {
                          "name": "Orders",
                          "fields": [
                            { "name": "Order No", "type": "Text", "required": true },
                            { "name": "Total", "type": "Money", "required": true, "unit": "USD", "dot": "2" },
                            { "name": "Customer", "type": "Relation", "required": false, "relation_target": "Customers" }
                          ]
                        }
                      ],
                      "relationships": [
                        {
                          "from": "Customers",
                          "to": "Orders",
                          "field": "Customer",
                          "cardinality": "1-N"
                        }
                      ],
                      "creation_order": ["Customers", "Orders"]
                    }
                    """));

            Path appAuthJson = repo.resolve("app-auth.json");
            Jacksons.mapper().writeValue(appAuthJson.toFile(), Jacksons.mapper().readTree("""
                    {
                      "data": [
                        {
                          "appId": "app-123",
                          "appKey": "generated-app-key",
                          "sign": "generated-sign"
                        }
                      ],
                      "success": true,
                      "error_code": 1
                    }
                    """));

            Path outputJson = repo.resolve("data").resolve("outputs").resolve("java_phase1")
                    .resolve("worksheet_create_result.json");

            WorksheetCreateService service = new WorksheetCreateService("http://127.0.0.1:" + server.getAddress().getPort());
            WorksheetCreateResult result = service.createFromPlan(repo, planJson, appAuthJson, outputJson);

            assertEquals(outputJson, result.outputJsonPath());
            assertTrue(Files.exists(outputJson));
            JsonNode summary = Jacksons.mapper().readTree(outputJson.toFile());
            assertEquals("app-123", summary.path("app_id").asText());
            assertEquals("ws-customers", summary.path("name_to_worksheet_id").path("Customers").asText());
            assertEquals("ws-orders", summary.path("name_to_worksheet_id").path("Orders").asText());
            assertEquals(1, summary.path("normalized_relations").size());
            assertEquals("Orders", summary.path("normalized_relations").path(0).path("source").asText());
            assertEquals("Customers", summary.path("normalized_relations").path(0).path("target").asText());
            assertTrue(summary.path("relation_verification").path("violations").isArray());
            assertEquals(0, summary.path("relation_verification").path("violations").size());

            JsonNode customersCreate = createPayloadsByWorksheet.get("Customers");
            assertEquals(1, customersCreate.path("fields").size());
            assertEquals("Customer Name", customersCreate.path("fields").path(0).path("name").asText());
            assertFalse(hasFieldType(customersCreate.path("fields"), "Phone"));

            JsonNode ordersCreate = createPayloadsByWorksheet.get("Orders");
            assertEquals(1, ordersCreate.path("fields").size());
            assertEquals("Order No", ordersCreate.path("fields").path(0).path("name").asText());
            assertFalse(hasFieldType(ordersCreate.path("fields"), "Money"));
            assertFalse(hasFieldType(ordersCreate.path("fields"), "Relation"));

            List<JsonNode> customerEdits = editPayloadsByWorksheet.get("ws-customers");
            assertEquals(1, customerEdits.size());
            assertEquals("Phone", customerEdits.get(0).path("addFields").path(0).path("type").asText());

            List<JsonNode> orderEdits = editPayloadsByWorksheet.get("ws-orders");
            assertEquals(2, orderEdits.size());
            assertTrue(orderEdits.stream().anyMatch(payload ->
                    "Money".equals(payload.path("addFields").path(0).path("type").asText())));
            JsonNode relationPayload = orderEdits.stream()
                    .filter(payload -> "Relation".equals(payload.path("addFields").path(0).path("type").asText()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("ws-customers", relationPayload.path("addFields").path(0).path("dataSource").asText());
            assertEquals("Customer", relationPayload.path("addFields").path(0).path("name").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void continuesWhenDeferredFieldUpdateFails() throws Exception {
        Map<String, List<JsonNode>> editPayloadsByWorksheet = new HashMap<>();
        Map<String, JsonNode> createPayloadsByWorksheet = new HashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/v3/app", new AppInfoHandler());
            server.createContext(
                    "/v3/app/worksheets",
                    new WorksheetApiHandler(createPayloadsByWorksheet, editPayloadsByWorksheet, true));
            server.start();

            Path repo = tempDir.resolve("repo-failing-deferred");
            Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("java_phase1"));

            Path planJson = repo.resolve("plan.json");
            Jacksons.mapper().writeValue(planJson.toFile(), Jacksons.mapper().readTree("""
                    {
                      "worksheets": [
                        {
                          "name": "Customers",
                          "fields": [
                            { "name": "Customer Name", "type": "Text", "required": true },
                            { "name": "Phone", "type": "Phone", "required": false }
                          ]
                        }
                      ],
                      "creation_order": ["Customers"]
                    }
                    """));

            Path appAuthJson = repo.resolve("app-auth.json");
            Jacksons.mapper().writeValue(appAuthJson.toFile(), Jacksons.mapper().readTree("""
                    {
                      "data": [
                        {
                          "appId": "app-123",
                          "appKey": "generated-app-key",
                          "sign": "generated-sign"
                        }
                      ],
                      "success": true,
                      "error_code": 1
                    }
                    """));

            Path outputJson = repo.resolve("data").resolve("outputs").resolve("java_phase1")
                    .resolve("worksheet_create_result.json");

            WorksheetCreateService service = new WorksheetCreateService("http://127.0.0.1:" + server.getAddress().getPort());
            WorksheetCreateResult result = service.createFromPlan(repo, planJson, appAuthJson, outputJson);

            assertEquals(outputJson, result.outputJsonPath());
            assertTrue(Files.exists(outputJson));
            JsonNode summary = Jacksons.mapper().readTree(outputJson.toFile());
            assertEquals("ws-customers", summary.path("name_to_worksheet_id").path("Customers").asText());
            assertEquals(1, editPayloadsByWorksheet.get("ws-customers").size());
        } finally {
            server.stop(0);
        }
    }

    private boolean hasFieldType(JsonNode fields, String type) {
        for (JsonNode field : fields) {
            if (type.equals(field.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private static final class AppInfoHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] response = """
                    {
                      "data": {
                        "sections": []
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

    private static final class WorksheetApiHandler implements HttpHandler {

        private final Map<String, JsonNode> createPayloadsByWorksheet;
        private final Map<String, List<JsonNode>> editPayloadsByWorksheet;
        private final boolean failDeferredFields;

        private WorksheetApiHandler(
                Map<String, JsonNode> createPayloadsByWorksheet,
                Map<String, List<JsonNode>> editPayloadsByWorksheet) {
            this(createPayloadsByWorksheet, editPayloadsByWorksheet, false);
        }

        private WorksheetApiHandler(
                Map<String, JsonNode> createPayloadsByWorksheet,
                Map<String, List<JsonNode>> editPayloadsByWorksheet,
                boolean failDeferredFields) {
            this.createPayloadsByWorksheet = createPayloadsByWorksheet;
            this.editPayloadsByWorksheet = editPayloadsByWorksheet;
            this.failDeferredFields = failDeferredFields;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equalsIgnoreCase(method) && "/v3/app/worksheets".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                String worksheetName = payload.path("name").asText();
                createPayloadsByWorksheet.put(worksheetName, payload);
                String worksheetId = switch (worksheetName) {
                    case "Customers" -> "ws-customers";
                    case "Orders" -> "ws-orders";
                    default -> throw new IOException("Unexpected worksheet name: " + worksheetName);
                };
                writeJson(exchange, """
                        {
                          "data": {
                            "worksheetId": "%s"
                          },
                          "success": true,
                          "error_code": 1
                        }
                        """.formatted(worksheetId));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/v3/app/worksheets/")) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                String worksheetId = path.substring("/v3/app/worksheets/".length());
                editPayloadsByWorksheet.computeIfAbsent(worksheetId, ignored -> new ArrayList<>()).add(payload);
                if (failDeferredFields && !"Relation".equals(payload.path("addFields").path(0).path("type").asText())) {
                    writeJson(exchange, """
                            {
                              "success": false,
                              "error_code": 0,
                              "error_msg": "请求异常"
                            }
                            """);
                    return;
                }
                writeJson(exchange, """
                        {
                          "success": true,
                          "error_code": 1
                        }
                        """);
                return;
            }

            if ("GET".equalsIgnoreCase(method) && path.startsWith("/v3/app/worksheets/")) {
                String worksheetId = path.substring("/v3/app/worksheets/".length());
                if ("ws-customers".equals(worksheetId)) {
                    writeJson(exchange, """
                            {
                              "data": {
                                "fields": [
                                  { "name": "Customer Name", "type": "Text" },
                                  { "name": "Orders", "type": "Relation", "dataSource": "ws-orders", "subType": 2 }
                                ]
                              },
                              "success": true,
                              "error_code": 1
                            }
                            """);
                    return;
                }
                if ("ws-orders".equals(worksheetId)) {
                    writeJson(exchange, """
                            {
                              "data": {
                                "fields": [
                                  { "name": "Order No", "type": "Text" },
                                  { "name": "Customer", "type": "Relation", "dataSource": "ws-customers", "subType": 1 }
                                ]
                              },
                              "success": true,
                              "error_code": 1
                            }
                            """);
                    return;
                }
            }

            throw new IOException("Unexpected request: " + method + " " + path);
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
