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

class ChartPipelineServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void plansChartsCreatesReportsAndSavesPageLayoutWithoutPythonPlanner() throws Exception {
        ChartApiHandler apiHandler = new ChartApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/report/reportConfig/saveReportConfig", apiHandler);
            server.createContext("/report/custom/getPage", apiHandler);
            server.createContext("/report/custom/savePage", apiHandler);
            server.start();

            Path repo = tempDir.resolve("repo");
            Files.createDirectories(repo.resolve("config").resolve("credentials"));
            Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("chart_plans"));
            Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("page_create_results"));
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

            Path planOutput = repo.resolve("data").resolve("outputs").resolve("chart_plans").resolve("chart_plan.json");
            Path outputJson = repo.resolve("data").resolve("outputs").resolve("page_create_results").resolve("chart_create.json");

            ChartPipelineService service = new ChartPipelineService(
                    new FakeAiTextClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            ChartPipelineResult result = service.run(
                    repo,
                    "app-123",
                    "Demo App-Overview",
                    List.of("ws-001", "ws-002"),
                    "page-001",
                    planOutput,
                    outputJson);

            assertEquals(outputJson, result.outputJsonPath());
            assertTrue(Files.exists(outputJson));
            JsonNode payload = Jacksons.mapper().readTree(outputJson.toFile());
            assertEquals(2, payload.path("totalCharts").asInt());
            assertEquals(2, payload.path("successCount").asInt());
            assertEquals(2, payload.path("results").size());
            assertEquals("report-001", payload.path("results").path(0).path("reportId").asText());

            assertEquals(2, apiHandler.saveReportPayloads.size());
            assertEquals(1, apiHandler.savePagePayloads.size());
            assertEquals(2, apiHandler.savePagePayloads.get(0).path("components").size());
        } finally {
            server.stop(0);
        }
    }

    private static final class FakeAiTextClient implements AiTextClient {
        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            return """
                    {
                      "charts": [
                        {
                          "name": "Total Orders",
                          "desc": "Total order count",
                          "reportType": 10,
                          "worksheetId": "ws-002",
                          "xaxes": {
                            "controlId": "",
                            "controlType": 0,
                            "particleSizeType": 0,
                            "sortType": 0,
                            "emptyType": 0
                          },
                          "yaxisList": [
                            {
                              "controlId": "record_count",
                              "controlType": 0,
                              "rename": "Orders"
                            }
                          ],
                          "filter": {
                            "filterRangeId": "ctime",
                            "filterRangeName": "Created Time",
                            "rangeType": 18,
                            "rangeValue": 365,
                            "today": true
                          }
                        },
                        {
                          "name": "Customer Type Split",
                          "desc": "Customer distribution",
                          "reportType": 3,
                          "worksheetId": "ws-001",
                          "xaxes": {
                            "controlId": "field-select",
                            "controlName": "Customer Type",
                            "controlType": 9,
                            "particleSizeType": 0,
                            "sortType": 0,
                            "emptyType": 0
                          },
                          "yaxisList": [
                            {
                              "controlId": "record_count",
                              "controlType": 0,
                              "rename": "Customers"
                            }
                          ],
                          "filter": {
                            "filterRangeId": "ctime",
                            "filterRangeName": "Created Time",
                            "rangeType": 18,
                            "rangeValue": 365,
                            "today": true
                          }
                        }
                      ]
                    }
                    """;
        }
    }

    private static final class ChartApiHandler implements HttpHandler {
        private final List<JsonNode> saveReportPayloads = new ArrayList<>();
        private final List<JsonNode> savePagePayloads = new ArrayList<>();
        private int reportCounter = 1;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/report/reportConfig/saveReportConfig".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                saveReportPayloads.add(payload);
                String reportId = String.format("report-%03d", reportCounter++);
                writeJson(exchange, """
                        {
                          "status": 1,
                          "data": {
                            "reportId": "%s"
                          },
                          "msg": "success"
                        }
                        """.formatted(reportId));
                return;
            }
            if ("/report/custom/getPage".equals(path)) {
                writeJson(exchange, """
                        {
                          "status": 1,
                          "data": {
                            "version": 1,
                            "components": []
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
                            "version": 2
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
