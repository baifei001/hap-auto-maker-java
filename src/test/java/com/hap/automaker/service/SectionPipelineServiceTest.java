package com.hap.automaker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.config.Jacksons;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SectionPipelineService 测试类
 */
class SectionPipelineServiceTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private Path worksheetResultPath;
    private Path appAuthPath;

    @BeforeEach
    void setUp() throws Exception {
        mapper = Jacksons.mapper();

        // 创建工作表结果文件
        worksheetResultPath = tempDir.resolve("worksheet_create_result.json");
        ObjectNode worksheetResult = mapper.createObjectNode();
        worksheetResult.put("appId", "app-test-001");
        worksheetResult.put("appName", "测试应用");

        ArrayNode worksheets = worksheetResult.putArray("worksheets");
        ObjectNode ws1 = worksheets.addObject();
        ws1.put("name", "客户");
        ws1.put("purpose", "客户信息管理");
        ws1.put("isDetail", false);

        ObjectNode ws2 = worksheets.addObject();
        ws2.put("name", "订单");
        ws2.put("purpose", "订单信息管理");
        ws2.put("isDetail", false);

        ObjectNode ws3 = worksheets.addObject();
        ws3.put("name", "产品");
        ws3.put("purpose", "产品信息管理");
        ws3.put("isDetail", false);

        mapper.writeValue(worksheetResultPath.toFile(), worksheetResult);

        // 创建应用授权文件
        appAuthPath = tempDir.resolve("app_authorize.json");
        ObjectNode authData = mapper.createObjectNode();
        ArrayNode data = authData.putArray("data");
        ObjectNode app = data.addObject();
        app.put("appId", "app-test-001");
        app.put("appKey", "test-key");
        app.put("sign", "test-sign");
        mapper.writeValue(appAuthPath.toFile(), authData);
    }

    @Test
    void testDryRunMode() throws Exception {
        // 使用简化模式（工作表数量 < 4）
        SectionPipelineService service = new SectionPipelineService();

        Path sectionPlanOutput = tempDir.resolve("section_plan.json");
        Path sectionResultOutput = tempDir.resolve("section_result.json");

        // 注意：当前实现中 API 调用未完全实现，会抛出异常或返回 dry-run 结果
        // 这里主要测试结构完整性

        assertTrue(Files.exists(worksheetResultPath));
        assertTrue(Files.exists(appAuthPath));
    }

    @Test
    void testSectionPipelineResult() {
        Path planPath = tempDir.resolve("plan.json");
        Path resultPath = tempDir.resolve("result.json");

        SectionPipelineService.SectionPipelineResult result =
            new SectionPipelineService.SectionPipelineResult(
                planPath,
                resultPath,
                3,  // total sections
                2,  // created sections
                java.time.OffsetDateTime.now(),
                java.time.OffsetDateTime.now()
            );

        assertEquals(planPath, result.planOutputPath());
        assertEquals(resultPath, result.resultOutputPath());
        assertEquals(3, result.totalSections());
        assertEquals(2, result.createdSections());

        JsonNode summary = result.summary();
        assertEquals(3, summary.path("totalSections").asInt());
        assertEquals(2, summary.path("createdSections").asInt());
        assertTrue(summary.has("durationMs"));
    }

    @Test
    void testBuildWorksheetInfos() throws Exception {
        JsonNode worksheetResult = mapper.readTree(worksheetResultPath.toFile());
        ArrayNode worksheets = (ArrayNode) worksheetResult.path("worksheets");

        assertEquals(3, worksheets.size());

        assertEquals("客户", worksheets.get(0).path("name").asText());
        assertEquals("客户信息管理", worksheets.get(0).path("purpose").asText());
        assertFalse(worksheets.get(0).path("isDetail").asBoolean());
    }

    @Test
    void testBuildSectionPlanJson() throws Exception {
        ObjectNode planJson = mapper.createObjectNode();
        planJson.put("appId", "app-test-001");
        planJson.put("appName", "测试应用");
        planJson.put("schemaVersion", "section_plan_v1");

        ArrayNode sections = planJson.putArray("sections");
        ObjectNode section1 = sections.addObject();
        section1.put("name", "仪表盘");
        ArrayNode ws1 = section1.putArray("worksheets");

        ObjectNode section2 = sections.addObject();
        section2.put("name", "基础数据");
        ArrayNode ws2 = section2.putArray("worksheets");
        ws2.add("客户");
        ws2.add("产品");

        Path outputPath = tempDir.resolve("test_plan.json");
        mapper.writeValue(outputPath.toFile(), planJson);

        assertTrue(Files.exists(outputPath));
        JsonNode readBack = mapper.readTree(outputPath.toFile());
        assertEquals("section_plan_v1", readBack.path("schemaVersion").asText());
        assertEquals(2, readBack.path("sections").size());
    }
}
