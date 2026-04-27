package com.hap.automaker.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.executor.MockDataCreator;
import com.hap.automaker.core.planner.MockDataPlanner;

/**
 * Mock数据流水线服务
 *
 * 整合 MockDataPlanner + MockDataCreator
 * 实现 Phase Three: Mock数据规划与创建
 */
public final class MockDataPipelineService implements MockDataPipelineRunner {

    private final MockDataPlanner mockDataPlanner;
    private final MockDataCreator mockDataCreator;

    public MockDataPipelineService() {
        this(
            new MockDataPlanner(new HttpAiTextClient()),
            new MockDataCreator(new HapApiClient(), 4)
        );
    }

    public MockDataPipelineService(
            MockDataPlanner mockDataPlanner,
            MockDataCreator mockDataCreator) {
        this.mockDataPlanner = mockDataPlanner;
        this.mockDataCreator = mockDataCreator;
    }

    @Override
    public MockDataPipelineResult run(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path mockDataPlanOutput,
            Path mockDataResultOutput,
            boolean dryRun) throws Exception {

        OffsetDateTime startedAt = OffsetDateTime.now();

        // 读取工作表创建结果以获取工作表信息
        JsonNode worksheetResult = Jacksons.mapper().readTree(worksheetCreateResult.toFile());
        String appName = worksheetResult.path("appName").asText();
        String businessContext = worksheetResult.path("businessContext").asText("");

        // 读取工作表规划以获取字段信息（从worksheet创建结果的同级目录查找）
        Path worksheetPlanPath = worksheetCreateResult.getParent().resolve("worksheet_plan.json");
        if (!Files.exists(worksheetPlanPath)) {
            // 回退到 repoRoot 路径
            worksheetPlanPath = repoRoot.resolve("data/outputs/java_phase1/worksheet_plan.json");
        }
        JsonNode worksheetPlan = Files.exists(worksheetPlanPath)
            ? Jacksons.mapper().readTree(worksheetPlanPath.toFile())
            : Jacksons.mapper().createObjectNode();

        // 构建 WorksheetInfo 列表
        List<MockDataPlanner.WorksheetInfo> worksheets = buildWorksheetInfos(worksheetResult, worksheetPlan);

        // 构建关系信息
        List<MockDataPlanner.RelationPair> relationPairs = buildRelationPairs(worksheetResult);
        List<MockDataPlanner.RelationEdge> relationEdges = buildRelationEdges(worksheetResult);

        // Step 1: 规划Mock数据
        MockDataPlanner.Input planInput = new MockDataPlanner.Input(
            appName,
            businessContext,
            worksheets,
            relationPairs,
            relationEdges
        );
        MockDataPlanner.Output planOutput = mockDataPlanner.plan(planInput);

        // 保存Mock数据规划结果
        ObjectNode planJson = buildMockDataPlanJson(appId, planOutput);
        Files.createDirectories(mockDataPlanOutput.getParent());
        Jacksons.mapper().writeValue(mockDataPlanOutput.toFile(), planJson);

        // Step 2: 创建Mock数据（如果不是 dry-run）
        MockDataCreator.Output createOutput;
        if (dryRun) {
            createOutput = createDryRunResult(planOutput);
        } else {
            // 转换计划到创建器格式
            List<MockDataCreator.WorksheetMockPlan> creatorPlans = convertToCreatorPlans(
                planOutput.getPlans(),
                worksheetResult,
                worksheetPlan
            );
            MockDataCreator.Input creatorInput = new MockDataCreator.Input(
                creatorPlans,
                dryRun,
                false, // failFast
                false  // triggerWorkflow
            );
            createOutput = mockDataCreator.execute(creatorInput);
        }

        // 构建结果
        ObjectNode resultJson = buildMockDataResultJson(appId, planOutput, createOutput, dryRun);
        Files.createDirectories(mockDataResultOutput.getParent());
        Jacksons.mapper().writeValue(mockDataResultOutput.toFile(), resultJson);

        int totalRecords = planOutput.getPlans().stream()
            .mapToInt(MockDataPlanner.WorksheetMockPlan::getRecordCount)
            .sum();
        int createdRecords = createOutput.getTotalCreated();

        return new MockDataPipelineResult(
            mockDataPlanOutput,
            mockDataResultOutput,
            planOutput.getPlans().size(),
            totalRecords,
            createdRecords,
            createOutput.isSuccess()
        );
    }

    private List<MockDataPlanner.WorksheetInfo> buildWorksheetInfos(JsonNode worksheetResult, JsonNode worksheetPlan) {
        List<MockDataPlanner.WorksheetInfo> worksheets = new ArrayList<>();
        JsonNode worksheetsArray = worksheetResult.path("created_worksheets");

        if (worksheetsArray.isArray()) {
            Iterator<JsonNode> elements = worksheetsArray.elements();
            while (elements.hasNext()) {
                JsonNode ws = elements.next();
                String wsId = ws.path("worksheetId").asText();
                String wsName = ws.path("name").asText();

                // 从 worksheet_plan.json 获取字段信息
                List<MockDataPlanner.FieldInfo> fields = buildFieldInfosFromPlan(wsName, worksheetPlan);
                worksheets.add(new MockDataPlanner.WorksheetInfo(wsId, wsName, fields));
            }
        }

        return worksheets;
    }

    private List<MockDataPlanner.FieldInfo> buildFieldInfosFromPlan(String worksheetName, JsonNode worksheetPlan) {
        List<MockDataPlanner.FieldInfo> fields = new ArrayList<>();

        JsonNode worksheetsNode = worksheetPlan.path("worksheets");
        if (!worksheetsNode.isArray()) {
            return fields;
        }

        // 找到对应工作表
        JsonNode targetWorksheet = null;
        for (JsonNode ws : worksheetsNode) {
            if (ws.path("name").asText().equals(worksheetName)) {
                targetWorksheet = ws;
                break;
            }
        }

        if (targetWorksheet == null) {
            return fields;
        }

        // 解析字段
        JsonNode fieldsNode = targetWorksheet.path("fields");
        if (fieldsNode.isArray()) {
            int fieldIndex = 0;
            for (JsonNode fieldNode : fieldsNode) {
                String fieldName = fieldNode.path("name").asText();
                String fieldType = fieldNode.path("type").asText("Text");
                String fieldId = "field_" + fieldIndex++; // 生成字段ID

                // 提取选项值（SingleSelect/MultipleSelect）
                List<String> options = new ArrayList<>();
                JsonNode optionsNode = fieldNode.path("option_values");
                if (optionsNode.isArray()) {
                    for (JsonNode opt : optionsNode) {
                        options.add(opt.asText());
                    }
                }

                // 转换类型为 planner 使用的类型
                String plannerType = mapPlanTypeToPlannerType(fieldType);
                fields.add(new MockDataPlanner.FieldInfo(fieldId, fieldName, plannerType, options));
            }
        }

        return fields;
    }

    private String mapPlanTypeToPlannerType(String planType) {
        return switch (planType) {
            case "Text" -> "Text";
            case "Phone" -> "PhoneNumber";
            case "Email" -> "Email";
            case "Number" -> "Number";
            case "Money" -> "Currency";
            case "Date" -> "Date";
            case "DateTime" -> "DateTime";
            case "SingleSelect" -> "SingleSelect";
            case "MultipleSelect" -> "MultipleSelect";
            case "Checkbox" -> "Checkbox";
            case "RichText" -> "RichText";
            case "Textarea" -> "Textarea";
            case "Area" -> "Region";
            case "Collaborator" -> "User"; // 协作人映射为 User 类型
            case "Relation" -> "Relation";
            case "Dropdown" -> "Dropdown";
            case "Link" -> "Link";
            default -> "Text";
        };
    }

    private String mapControlType(int type) {
        return switch (type) {
            case 2 -> "Text";
            case 3 -> "Textarea";
            case 4 -> "Number";
            case 5 -> "Date";
            case 6 -> "DateTime";
            case 7 -> "Time";
            case 8 -> "RichText";
            case 9 -> "SingleSelect";
            case 10 -> "MultipleSelect";
            case 11 -> "Dropdown";
            case 14 -> "Checkbox";
            case 16 -> "User";
            case 17 -> "Department";
            case 21 -> "Location";
            case 28 -> "Money";
            case 29 -> "Email";
            case 30 -> "PhoneNumber";
            case 31 -> "Attachment";
            case 34 -> "Relation";
            case 35 -> "Link";
            case 36 -> "Formula";
            case 37 -> "AutoNumber";
            case 40 -> "SubList";
            case 41 -> "Embed";
            case 43 -> "Signature";
            case 44 -> "OCR";
            case 46 -> "Cascader";
            case 48 -> "DateRange";
            case 50 -> "Score";
            case 51 -> "RelateWorksheet";
            case 52 -> "CascaderWorksheet";
            default -> "Text";
        };
    }

    private List<MockDataPlanner.RelationPair> buildRelationPairs(JsonNode worksheetResult) {
        List<MockDataPlanner.RelationPair> pairs = new ArrayList<>();

        JsonNode relationsArray = worksheetResult.path("relationPairs");
        if (relationsArray.isArray()) {
            Iterator<JsonNode> iter = relationsArray.elements();
            while (iter.hasNext()) {
                JsonNode pair = iter.next();
                String wsA = pair.path("worksheetAId").asText();
                String wsB = pair.path("worksheetBId").asText();
                String pairType = pair.path("pairType").asText("1-N");
                pairs.add(new MockDataPlanner.RelationPair(wsA, wsB, pairType));
            }
        }

        return pairs;
    }

    private List<MockDataPlanner.RelationEdge> buildRelationEdges(JsonNode worksheetResult) {
        List<MockDataPlanner.RelationEdge> edges = new ArrayList<>();

        JsonNode edgesArray = worksheetResult.path("relationEdges");
        if (edgesArray.isArray()) {
            Iterator<JsonNode> iter = edgesArray.elements();
            while (iter.hasNext()) {
                JsonNode edge = iter.next();
                String source = edge.path("sourceWorksheetId").asText();
                String target = edge.path("targetWorksheetId").asText();
                int subType = edge.path("subType").asInt(0);
                edges.add(new MockDataPlanner.RelationEdge(source, target, subType));
            }
        }

        return edges;
    }

    private ObjectNode buildMockDataPlanJson(String appId, MockDataPlanner.Output planOutput) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("schemaVersion", "mock_data_plan_v1");
        result.put("createdAt", OffsetDateTime.now().toString());

        ArrayNode plansArray = result.putArray("plans");
        for (MockDataPlanner.WorksheetMockPlan plan : planOutput.getPlans()) {
            ObjectNode planNode = plansArray.addObject();
            planNode.put("worksheetId", plan.getWorksheetId());
            planNode.put("worksheetName", plan.getWorksheetName());
            planNode.put("recordCount", plan.getRecordCount());

            ArrayNode recordsArray = planNode.putArray("records");
            for (MockDataPlanner.MockRecord record : plan.getRecords()) {
                ObjectNode recordNode = recordsArray.addObject();
                recordNode.put("recordSummary", record.getRecordSummary());

                ObjectNode valuesNode = recordNode.putObject("valuesByFieldId");
                for (Map.Entry<String, Object> entry : record.getValuesByFieldId().entrySet()) {
                    valuesNode.set(entry.getKey(), Jacksons.mapper().valueToTree(entry.getValue()));
                }
            }
        }

        return result;
    }

    private List<MockDataCreator.WorksheetMockPlan> convertToCreatorPlans(
            List<MockDataPlanner.WorksheetMockPlan> plannerPlans,
            JsonNode worksheetResult,
            JsonNode worksheetPlan) {
        List<MockDataCreator.WorksheetMockPlan> creatorPlans = new ArrayList<>();

        // 构建字段元数据映射
        Map<String, Map<String, MockDataCreator.FieldMeta>> fieldMetaMap = buildFieldMetaMap(worksheetResult, worksheetPlan);

        for (MockDataPlanner.WorksheetMockPlan plan : plannerPlans) {
            List<MockDataCreator.MockRecord> creatorRecords = new ArrayList<>();

            for (MockDataPlanner.MockRecord record : plan.getRecords()) {
                creatorRecords.add(new MockDataCreator.MockRecord(
                    record.getRecordSummary(),
                    record.getValuesByFieldId()
                ));
            }

            Map<String, MockDataCreator.FieldMeta> metaMap = fieldMetaMap.getOrDefault(
                plan.getWorksheetId(),
                Map.of()
            );

            creatorPlans.add(new MockDataCreator.WorksheetMockPlan(
                plan.getWorksheetId(),
                plan.getWorksheetName(),
                creatorRecords,
                metaMap
            ));
        }

        return creatorPlans;
    }

    private Map<String, Map<String, MockDataCreator.FieldMeta>> buildFieldMetaMap(JsonNode worksheetResult, JsonNode worksheetPlan) {
        Map<String, Map<String, MockDataCreator.FieldMeta>> result = new HashMap<>();

        // 从 created_worksheets 获取工作表ID和名称映射
        JsonNode worksheetsArray = worksheetResult.path("created_worksheets");
        if (!worksheetsArray.isArray()) {
            return result;
        }

        JsonNode planWorksheets = worksheetPlan.path("worksheets");
        if (!planWorksheets.isArray()) {
            return result;
        }

        // 构建工作表名称到ID的映射
        Map<String, String> nameToId = new HashMap<>();
        for (JsonNode ws : worksheetsArray) {
            String wsId = ws.path("worksheetId").asText();
            String wsName = ws.path("name").asText();
            nameToId.put(wsName, wsId);
        }

        // 为每个工作表构建字段元数据
        for (JsonNode planWs : planWorksheets) {
            String wsName = planWs.path("name").asText();
            String wsId = nameToId.get(wsName);
            if (wsId == null) {
                continue;
            }

            Map<String, MockDataCreator.FieldMeta> fieldMap = new HashMap<>();
            JsonNode fieldsNode = planWs.path("fields");
            if (fieldsNode.isArray()) {
                int fieldIndex = 0;
                for (JsonNode fieldNode : fieldsNode) {
                    String fieldName = fieldNode.path("name").asText();
                    String fieldType = fieldNode.path("type").asText("Text");
                    String fieldId = "field_" + fieldIndex++;

                    // 转换为 MockDataCreator 使用的类型
                    String creatorType = mapPlanTypeToCreatorType(fieldType);
                    int controlType = mapPlanTypeToControlType(fieldType);

                    fieldMap.put(fieldId, new MockDataCreator.FieldMeta(
                        fieldId, fieldName, creatorType, controlType
                    ));
                }
            }
            result.put(wsId, fieldMap);
        }

        return result;
    }

    private String mapPlanTypeToCreatorType(String planType) {
        return switch (planType) {
            case "Text" -> "Text";
            case "Phone" -> "PhoneNumber";
            case "Email" -> "Email";
            case "Number" -> "Number";
            case "Money" -> "Currency";
            case "Date" -> "Date";
            case "DateTime" -> "DateTime";
            case "SingleSelect" -> "SingleSelect";
            case "MultipleSelect" -> "MultipleSelect";
            case "Checkbox" -> "Checkbox";
            case "RichText" -> "RichText";
            case "Textarea" -> "Textarea";
            case "Area" -> "Region";
            case "Collaborator" -> "User";
            case "Relation" -> "Relation";
            case "Dropdown" -> "Dropdown";
            case "Link" -> "Link";
            default -> "Text";
        };
    }

    private int mapPlanTypeToControlType(String planType) {
        return switch (planType) {
            case "Text" -> 2;
            case "Textarea" -> 3;
            case "Number" -> 4;
            case "Date" -> 5;
            case "DateTime" -> 6;
            case "Time" -> 7;
            case "RichText" -> 8;
            case "SingleSelect", "Dropdown" -> 9;
            case "MultipleSelect" -> 10;
            case "Checkbox" -> 14;
            case "Collaborator" -> 16;
            case "Department" -> 17;
            case "Area" -> 19;
            case "Location" -> 21;
            case "Money" -> 28;
            case "Email" -> 29;
            case "Phone" -> 30;
            case "Attachment" -> 31;
            case "Relation" -> 34;
            case "Link" -> 35;
            default -> 2;
        };
    }

    private MockDataCreator.Output createDryRunResult(MockDataPlanner.Output planOutput) {
        Map<String, MockDataCreator.WorksheetResult> results = new HashMap<>();

        for (MockDataPlanner.WorksheetMockPlan plan : planOutput.getPlans()) {
            List<String> rowIds = new ArrayList<>();
            for (int i = 0; i < plan.getRecords().size(); i++) {
                rowIds.add("dry-run-row-" + (i + 1));
            }

            results.put(plan.getWorksheetId(), new MockDataCreator.WorksheetResult(
                plan.getWorksheetId(),
                plan.getWorksheetName(),
                true,
                rowIds.size(),
                rowIds,
                null
            ));
        }

        int totalCreated = planOutput.getPlans().stream()
            .mapToInt(p -> p.getRecords().size())
            .sum();

        return new MockDataCreator.Output(true, results, totalCreated, null);
    }

    private ObjectNode buildMockDataResultJson(String appId, MockDataPlanner.Output planOutput,
                                                MockDataCreator.Output createOutput, boolean dryRun) {
        ObjectNode result = Jacksons.mapper().createObjectNode();
        result.put("appId", appId);
        result.put("createdAt", OffsetDateTime.now().toString());

        int totalPlanned = planOutput.getPlans().stream()
            .mapToInt(MockDataPlanner.WorksheetMockPlan::getRecordCount)
            .sum();

        result.put("totalPlanned", totalPlanned);
        result.put("totalCreated", createOutput.getTotalCreated());
        result.put("dryRun", dryRun);
        result.put("success", createOutput.isSuccess());

        ArrayNode worksheetsArray = result.putArray("worksheets");
        for (MockDataPlanner.WorksheetMockPlan plan : planOutput.getPlans()) {
            ObjectNode wsNode = worksheetsArray.addObject();
            wsNode.put("worksheetId", plan.getWorksheetId());
            wsNode.put("worksheetName", plan.getWorksheetName());
            wsNode.put("recordCount", plan.getRecords().size());

            MockDataCreator.WorksheetResult wsResult = createOutput.getResults().get(plan.getWorksheetId());
            if (wsResult != null) {
                wsNode.put("createdCount", wsResult.getCreatedCount());
                wsNode.put("success", wsResult.isSuccess());
                if (wsResult.getErrorMessage() != null) {
                    wsNode.put("error", wsResult.getErrorMessage());
                }

                ArrayNode rowIdsArray = wsNode.putArray("rowIds");
                for (String rowId : wsResult.getRowIds()) {
                    rowIdsArray.add(rowId);
                }
            }
        }

        return result;
    }
}
