package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.executor.LayoutCreator;
import com.hap.automaker.core.planner.LayoutPlanner;
import com.hap.automaker.core.executor.ExecuteOptions;

/**
 * LayoutPipelineService 单元测试
 */
class LayoutPipelineServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void layoutPipelineRunsSuccessfully() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("java_phase2"));

        // 创建工作表创建结果 JSON
        Path worksheetCreateResult = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("worksheet_create_result.json");

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appName", "测试应用");
        result.put("businessContext", "测试业务");

        ArrayNode createdWorksheets = result.putArray("created_worksheets");
        ObjectNode ws1 = createdWorksheets.addObject();
        ws1.put("name", "客户表");
        ws1.put("worksheetId", "ws-001");

        ObjectNode ws2 = createdWorksheets.addObject();
        ws2.put("name", "订单表");
        ws2.put("worksheetId", "ws-002");

        // 添加关系信息
        result.putArray("relationPairs");
        result.putArray("relationEdges");

        Files.createDirectories(worksheetCreateResult.getParent());
        Jacksons.mapper().writeValue(worksheetCreateResult.toFile(), result);

        // 创建工作表规划文件
        Path worksheetPlan = repo.resolve("data").resolve("outputs").resolve("java_phase1")
            .resolve("worksheet_plan.json");
        ObjectNode plan = Jacksons.mapper().createObjectNode();
        plan.put("app_name", "测试应用");
        
        ArrayNode worksheets = plan.putArray("worksheets");
        ObjectNode wsPlan1 = worksheets.addObject();
        wsPlan1.put("name", "客户表");
        wsPlan1.put("purpose", "存储客户信息");
        
        ArrayNode fields1 = wsPlan1.putArray("fields");
        ObjectNode f1 = fields1.addObject();
        f1.put("name", "客户名称");
        f1.put("type", "Text");
        
        ObjectNode f2 = fields1.addObject();
        f2.put("name", "联系电话");
        f2.put("type", "Phone");
        
        ObjectNode wsPlan2 = worksheets.addObject();
        wsPlan2.put("name", "订单表");
        wsPlan2.put("purpose", "存储订单信息");
        
        ArrayNode fields2 = wsPlan2.putArray("fields");
        ObjectNode f3 = fields2.addObject();
        f3.put("name", "订单号");
        f3.put("type", "Text");

        Files.createDirectories(worksheetPlan.getParent());
        Jacksons.mapper().writeValue(worksheetPlan.toFile(), plan);

        Path layoutPlanOutput = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("layout_plan.json");
        Path layoutResultOutput = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("layout_result.json");

        // 使用 Fake 实现
        LayoutPipelineRunner runner = new LayoutPipelineService(
            new FakeLayoutPlanner(),
            new FakeLayoutCreator()
        );

        LayoutPipelineService.LayoutPipelineResult result_pipeline = runner.run(
            repo,
            "app-test-123",
            worksheetCreateResult,
            layoutPlanOutput,
            layoutResultOutput
        );

        assertEquals(2, result_pipeline.totalWorksheets());
        assertEquals(2, result_pipeline.successCount());
        assertEquals(0, result_pipeline.failedCount());
        assertTrue(Files.exists(layoutPlanOutput));
        assertTrue(Files.exists(layoutResultOutput));

        // 验证结果 JSON
        JsonNode resultJson = Jacksons.mapper().readTree(layoutResultOutput.toFile());
        assertEquals("app-test-123", resultJson.path("appId").asText());
        assertTrue(resultJson.path("success").asBoolean());
    }

    @Test
    void layoutPipelineHandlesEmptyWorksheets() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("java_phase2"));

        Path worksheetCreateResult = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("worksheet_create_result.json");

        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.putArray("created_worksheets");
        result.putArray("relationPairs");
        result.putArray("relationEdges");

        Files.createDirectories(worksheetCreateResult.getParent());
        Jacksons.mapper().writeValue(worksheetCreateResult.toFile(), result);

        // 创建工作表规划文件（空）
        Path worksheetPlan = repo.resolve("data").resolve("outputs").resolve("java_phase1")
            .resolve("worksheet_plan.json");
        ObjectNode plan = Jacksons.mapper().createObjectNode();
        plan.putArray("worksheets");
        Files.createDirectories(worksheetPlan.getParent());
        Jacksons.mapper().writeValue(worksheetPlan.toFile(), plan);

        Path layoutPlanOutput = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("layout_plan.json");
        Path layoutResultOutput = repo.resolve("data").resolve("outputs").resolve("java_phase2")
            .resolve("layout_result.json");

        LayoutPipelineRunner runner = new LayoutPipelineService(
            new FakeLayoutPlanner(),
            new FakeLayoutCreator()
        );

        LayoutPipelineService.LayoutPipelineResult result_pipeline = runner.run(
            repo,
            "app-empty-test",
            worksheetCreateResult,
            layoutPlanOutput,
            layoutResultOutput
        );

        assertEquals(0, result_pipeline.totalWorksheets());
        assertTrue(Files.exists(layoutPlanOutput));
    }

    // ========== Fake Implementations ==========

    private static final class FakeLayoutPlanner extends LayoutPlanner {
        @Override
        public Output plan(Input input) {
            List<WorksheetLayout> layouts = new ArrayList<>();
            
            for (WorksheetInfo ws : input.getWorksheets()) {
                List<FieldGroup> groups = new ArrayList<>();
                List<FieldInfo> fields = new ArrayList<>();
                fields.add(new FieldInfo("field_0", "名称", "Text", true));
                fields.add(new FieldInfo("field_1", "电话", "PhoneNumber", false));
                groups.add(new FieldGroup("基础信息", fields));
                
                layouts.add(new WorksheetLayout(
                    ws.getWorksheetId(),
                    ws.getWorksheetName(),
                    new FieldLayout(groups)
                ));
            }
            
            return new Output(layouts);
        }
    }

    private static final class FakeLayoutCreator extends LayoutCreator {
        FakeLayoutCreator() {
            super(null);
        }

        @Override
        public Result execute(LayoutPlanner.Output plan, ExecuteOptions options) {
            List<LayoutUpdateResult> results = new ArrayList<>();
            
            for (LayoutPlanner.WorksheetLayout layout : plan.getLayouts()) {
                results.add(new LayoutUpdateResult(
                    layout.getWorksheetId(),
                    layout.getWorksheetName(),
                    true,
                    null,
                    layout.getLayout().getGroups().size()
                ));
            }
            
            return new Result(results);
        }
    }
}
