package com.hap.automaker.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.executor.MockDataCreator;
import com.hap.automaker.core.planner.MockDataPlanner;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.service.MockDataPipelineRunner;
import com.hap.automaker.service.MockDataPipelineService;
import com.hap.automaker.service.MockDataPipelineRunner.MockDataPipelineResult;

class MockDataIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void mockDataPipelineCreatesRecordsFromWorksheetResult() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("java_phase2"));

        // 创建工作表创建结果 JSON
        Path worksheetCreateResult = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("worksheet_create_result.json");
        JsonNode worksheetResult = Jacksons.mapper().readTree("""
            {
              "appName": "客户管理应用",
              "businessContext": "客户管理和订单跟踪",
              "created_worksheets": [
                {
                  "worksheetId": "ws-customers",
                  "name": "客户"
                }
              ],
              "relationPairs": [],
              "relationEdges": []
            }
            """);
        Files.createDirectories(worksheetCreateResult.getParent());
        Jacksons.mapper().writeValue(worksheetCreateResult.toFile(), worksheetResult);

        Path mockDataPlanOutput = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("mock_data_plan.json");
        Path mockDataResultOutput = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("mock_data_result.json");

        // 使用 Fake 实现进行测试
        MockDataPipelineRunner runner = new MockDataPipelineService(
            new FakeMockDataPlanner(),
            new FakeMockDataCreator()
        );

        MockDataPipelineResult result = runner.run(
            repo,
            "app-test-123",
            worksheetCreateResult,
            mockDataPlanOutput,
            mockDataResultOutput,
            false
        );

        assertTrue(result.success());
        assertEquals(1, result.totalWorksheets());
        assertEquals(5, result.totalRecords());
        assertEquals(5, result.createdRecords());
        assertTrue(Files.exists(mockDataPlanOutput));
        assertTrue(Files.exists(mockDataResultOutput));

        // 验证结果 JSON 结构
        JsonNode resultJson = Jacksons.mapper().readTree(mockDataResultOutput.toFile());
        assertEquals("app-test-123", resultJson.path("appId").asText());
        assertTrue(resultJson.path("success").asBoolean());
        assertEquals(5, resultJson.path("totalCreated").asInt());
    }

    @Test
    void mockDataPipelineDryRunSkipsCreation() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("java_phase2"));

        Path worksheetCreateResult = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("worksheet_create_result.json");
        JsonNode worksheetResult = Jacksons.mapper().readTree("""
            {
              "appName": "测试应用",
              "created_worksheets": [
                {
                  "worksheetId": "ws-test",
                  "name": "测试表"
                }
              ],
              "relationPairs": [],
              "relationEdges": []
            }
            """);
        Files.createDirectories(worksheetCreateResult.getParent());
        Jacksons.mapper().writeValue(worksheetCreateResult.toFile(), worksheetResult);

        Path mockDataPlanOutput = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("mock_data_plan.json");
        Path mockDataResultOutput = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("mock_data_result.json");

        MockDataPipelineRunner runner = new MockDataPipelineService(
            new FakeMockDataPlanner(),
            new FakeMockDataCreator()
        );

        MockDataPipelineResult result = runner.run(
            repo,
            "app-dryrun-456",
            worksheetCreateResult,
            mockDataPlanOutput,
            mockDataResultOutput,
            true // dry-run
        );

        assertTrue(result.success());
        assertEquals(5, result.totalRecords());

        JsonNode resultJson = Jacksons.mapper().readTree(mockDataResultOutput.toFile());
        assertEquals("app-dryrun-456", resultJson.path("appId").asText());
        assertTrue(resultJson.path("dryRun").asBoolean());
    }

    // ========== Fake Implementations ==========

    private static final class FakeMockDataPlanner extends MockDataPlanner {
        FakeMockDataPlanner() {
            super(new FakeAiTextClient());
        }

        @Override
        public Output plan(Input input) {
            List<WorksheetMockPlan> plans = new ArrayList<>();

            for (WorksheetInfo ws : input.getWorksheets()) {
                List<MockRecord> records = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    Map<String, Object> values = new HashMap<>();
                    values.put("c_name", "客户" + (i + 1));
                    values.put("c_phone", "1380013800" + i);
                    values.put("c_email", "customer" + i + "@example.com");
                    values.put("c_level", List.of("VIP"));
                    records.add(new MockRecord("测试记录" + (i + 1), values));
                }
                plans.add(new WorksheetMockPlan(ws.getWorksheetId(), ws.getWorksheetName(), 5, records));
            }

            return new Output(plans);
        }
    }

    private static final class FakeMockDataCreator extends MockDataCreator {
        FakeMockDataCreator() {
            super(new FakeHapApiClient(), 1);
        }

        @Override
        public Output execute(Input input) {
            Map<String, WorksheetResult> results = new HashMap<>();
            int totalCreated = 0;

            for (WorksheetMockPlan plan : input.getPlans()) {
                List<String> rowIds = new ArrayList<>();
                for (int i = 0; i < plan.getRecords().size(); i++) {
                    rowIds.add("row-" + (i + 1));
                }
                totalCreated += rowIds.size();

                results.put(plan.getWorksheetId(), new WorksheetResult(
                    plan.getWorksheetId(),
                    plan.getWorksheetName(),
                    true,
                    rowIds.size(),
                    rowIds,
                    null
                ));
            }

            return new Output(true, results, totalCreated, null);
        }
    }

    private static final class FakeAiTextClient implements AiTextClient {
        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            ObjectNode root = Jacksons.mapper().createObjectNode();
            root.putArray("notes").add("inline_mock");
            ArrayNode worksheets = root.putArray("worksheets");
            ObjectNode ws = worksheets.addObject();
            ws.put("worksheetId", "ws-test");
            ws.put("worksheetName", "测试表");
            ws.put("recordCount", 5);
            ArrayNode records = ws.putArray("records");
            for (int i = 0; i < 5; i++) {
                ObjectNode record = records.addObject();
                record.put("recordSummary", "测试记录" + (i + 1));
                ObjectNode values = record.putObject("valuesByFieldId");
                values.put("c_name", "客户" + (i + 1));
                values.put("c_phone", "1380013800" + i);
            }
            return root.toString();
        }
    }

    private static final class FakeHapApiClient extends HapApiClient {
        @Override
        public JsonNode createRowsBatchV3(String worksheetId, JsonNode rows, boolean triggerWorkflow) {
            ObjectNode response = Jacksons.mapper().createObjectNode();
            ObjectNode data = response.putObject("data");
            ArrayNode rowIds = data.putArray("rowIds");
            for (int i = 0; i < rows.size(); i++) {
                rowIds.add("row-" + (i + 1));
            }
            return response;
        }
    }
}
