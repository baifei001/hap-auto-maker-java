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

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;

class IncrementalAddChartServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void addChartsSupportsPlanOnlyMode() throws Exception {
        IncrementalChartApiHandler apiHandler = new IncrementalChartApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/HomeApp/GetApp", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-plan");
            ChartPipelineService service = new ChartPipelineService(
                    new SingleResponseAiTextClient("""
                            {
                              "appId": "app-123",
                              "appName": "Demo App",
                              "charts": [
                                {
                                  "name": "客户状态分布",
                                  "desc": "按状态统计客户数量",
                                  "reportType": 3,
                                  "worksheetId": "ws-001",
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
                                      "rename": "客户数"
                                    }
                                  ],
                                  "filter": {
                                    "filterRangeId": "ctime",
                                    "filterRangeName": "创建时间",
                                    "rangeType": 18,
                                    "rangeValue": 365,
                                    "today": true
                                  }
                                }
                              ]
                            }
                            """),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.addCharts(
                    repo,
                    "app-123",
                    "ws-001",
                    "",
                    "按客户状态推荐图表",
                    1,
                    false);

            assertEquals("plan_only", result.path("status").asText());
            assertEquals("app-123", result.path("appId").asText());
            assertEquals("ws-001", result.path("worksheetIds").path(0).asText());
            assertTrue(Files.exists(Path.of(result.path("planFile").asText())));
            assertEquals(0, apiHandler.saveReportPayloads.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void addChartsCreatesChartsAndAppendsToPage() throws Exception {
        IncrementalChartApiHandler apiHandler = new IncrementalChartApiHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/HomeApp/GetApp", apiHandler);
            server.createContext("/report/reportConfig/saveReportConfig", apiHandler);
            server.createContext("/report/custom/getPage", apiHandler);
            server.createContext("/report/custom/savePage", apiHandler);
            server.start();

            Path repo = prepareRepo("repo-execute");
            ChartPipelineService service = new ChartPipelineService(
                    new SingleResponseAiTextClient("""
                            {
                              "appId": "app-123",
                              "appName": "Demo App",
                              "charts": [
                                {
                                  "name": "客户状态分布",
                                  "desc": "按状态统计客户数量",
                                  "reportType": 3,
                                  "worksheetId": "ws-001",
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
                                      "rename": "客户数"
                                    }
                                  ],
                                  "filter": {
                                    "filterRangeId": "ctime",
                                    "filterRangeName": "创建时间",
                                    "rangeType": 18,
                                    "rangeValue": 365,
                                    "today": true
                                  }
                                },
                                {
                                  "name": "订单趋势",
                                  "desc": "按月统计订单趋势",
                                  "reportType": 2,
                                  "worksheetId": "ws-002",
                                  "xaxes": {
                                    "controlId": "month",
                                    "controlType": 16,
                                    "particleSizeType": 6,
                                    "sortType": 1,
                                    "emptyType": 0
                                  },
                                  "yaxisList": [
                                    {
                                      "controlId": "record_count",
                                      "controlType": 0,
                                      "rename": "订单数"
                                    }
                                  ],
                                  "filter": {
                                    "filterRangeId": "ctime",
                                    "filterRangeName": "创建时间",
                                    "rangeType": 18,
                                    "rangeValue": 365,
                                    "today": true
                                  }
                                }
                              ]
                            }
                            """),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            JsonNode result = service.addCharts(
                    repo,
                    "app-123",
                    "",
                    "page-001",
                    "给仪表盘补 2 个图表",
                    2,
                    true);

            assertEquals("success", result.path("status").asText());
            assertEquals(2, result.path("results").size());
            assertEquals(2, apiHandler.saveReportPayloads.size());
            assertEquals(1, apiHandler.savePagePayloads.size());
            assertTrue(Files.exists(Path.of(result.path("outputFile").asText())));
        } finally {
            server.stop(0);
        }
    }

    private Path prepareRepo(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
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
        return repo;
    }

    private static final class SingleResponseAiTextClient implements AiTextClient {
        private final String response;

        private SingleResponseAiTextClient(String response) {
            this.response = response;
        }

        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            return response;
        }
    }

    private static final class IncrementalChartApiHandler implements HttpHandler {
        private final List<JsonNode> saveReportPayloads = new ArrayList<>();
        private final List<JsonNode> savePagePayloads = new ArrayList<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/api/HomeApp/GetApp".equals(path)) {
                writeJson(exchange, """
                        {
                          "data": {
                            "name": "Demo App",
                            "sections": [
                              {
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
            if ("/report/reportConfig/saveReportConfig".equals(path)) {
                JsonNode payload = Jacksons.mapper().readTree(exchange.getRequestBody().readAllBytes());
                saveReportPayloads.add(payload);
                int index = saveReportPayloads.size();
                writeJson(exchange, """
                        {
                          "status": 1,
                          "data": {
                            "reportId": "report-%03d"
                          }
                        }
                        """.formatted(index));
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
