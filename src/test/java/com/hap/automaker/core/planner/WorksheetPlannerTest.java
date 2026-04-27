package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.config.Jacksons;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorksheetPlanner 测试类
 */
class WorksheetPlannerTest {

    private WorksheetPlanner planner;

    @BeforeEach
    void setUp() {
        // 使用 mock AI 客户端进行测试
        planner = new WorksheetPlanner(new MockAiTextClient());
    }

    @Test
    void testGetName() {
        assertEquals("WorksheetPlanner", planner.getName());
    }

    @Test
    void testSkeletonPlanParsing() throws Exception {
        // 测试骨架规划的 JSON 解析
        String skeletonJson = """
            {
                "appName": "客户管理系统",
                "summary": "管理客户信息和销售跟进",
                "worksheets": [
                    {
                        "name": "客户信息",
                        "purpose": "存储客户基本资料",
                        "coreFields": ["客户名称", "联系电话"],
                        "relations": [
                            {"target": "跟进记录", "type": "1-N", "description": "一个客户有多条跟进"}
                        ]
                    },
                    {
                        "name": "跟进记录",
                        "purpose": "记录销售跟进情况",
                        "coreFields": ["跟进内容", "跟进时间"],
                        "relations": []
                    }
                ]
            }
            """;

        // 使用Jackson验证解析
        com.fasterxml.jackson.databind.JsonNode root = Jacksons.mapper().readTree(skeletonJson);

        assertEquals("客户管理系统", root.get("appName").asText());
        assertEquals(2, root.get("worksheets").size());
        assertEquals("客户信息", root.get("worksheets").get(0).get("name").asText());
    }

    @Test
    void testWorksheetPlanParsing() throws Exception {
        // 测试完整工作表规划的 JSON 解析
        String fieldsJson = """
            {
                "name": "客户信息",
                "displayName": "客户信息管理",
                "fields": [
                    {
                        "controlId": "field_001",
                        "controlName": "客户名称",
                        "controlType": 2,
                        "required": true,
                        "description": "客户的公司名称或个人姓名"
                    },
                    {
                        "controlId": "field_002",
                        "controlName": "联系电话",
                        "controlType": 5,
                        "required": false,
                        "description": "客户联系电话"
                    }
                ]
            }
            """;

        com.fasterxml.jackson.databind.JsonNode root = Jacksons.mapper().readTree(fieldsJson);

        assertEquals("客户信息", root.get("name").asText());
        assertEquals(2, root.get("fields").size());
        assertEquals("客户名称", root.get("fields").get(0).get("controlName").asText());
        assertEquals(2, root.get("fields").get(0).get("controlType").asInt());
        assertTrue(root.get("fields").get(0).get("required").asBoolean());
    }

    @Test
    void testInputCreation() {
        WorksheetPlanner.Input input = new WorksheetPlanner.Input(
            "CRM系统",
            "管理客户关系和销售人员的工作",
            "需要支持客户分级和跟进提醒",
            3,
            10,
            "zh"
        );

        assertEquals("CRM系统", input.getAppName());
        assertEquals("管理客户关系和销售人员的工作", input.getBusinessContext());
        assertEquals("需要支持客户分级和跟进提醒", input.getExtraRequirements());
        assertEquals(3, input.getMinWorksheets());
        assertEquals(10, input.getMaxWorksheets());
        assertEquals("zh", input.getLanguage());
    }

    @Test
    void testInputWithDefaults() {
        WorksheetPlanner.Input input = new WorksheetPlanner.Input(
            "简单应用",
            "描述",
            null,
            0,
            0,
            null
        );

        assertEquals("zh", input.getLanguage()); // 默认为 zh
    }

    @Test
    void testOutputCreation() {
        // 创建模拟的 WorksheetPlan
        WorksheetPlanner.WorksheetPlan plan1 = new WorksheetPlanner.WorksheetPlan();
        plan1.setName("客户表");
        plan1.setDisplayName("客户信息管理");

        WorksheetPlanner.FieldDef field1 = new WorksheetPlanner.FieldDef();
        field1.setControlId("f001");
        field1.setControlName("客户名称");
        field1.setControlType(2);
        field1.setRequired(true);

        plan1.setFields(List.of(field1));

        WorksheetPlanner.Output output = new WorksheetPlanner.Output(
            "CRM系统",
            "客户管理应用",
            List.of(plan1)
        );

        assertEquals("CRM系统", output.getAppName());
        assertEquals("客户管理应用", output.getSummary());
        assertEquals(1, output.getWorksheets().size());
        assertEquals("客户表", output.getWorksheets().get(0).getName());
    }

    @Test
    void testRelationDefParsing() throws Exception {
        String relationJson = """
            {
                "target": "订单表",
                "type": "1-N",
                "description": "一个客户有多个订单"
            }
            """;

        WorksheetPlanner.RelationDef relation = Jacksons.mapper().readValue(relationJson, WorksheetPlanner.RelationDef.class);

        assertEquals("订单表", relation.getTarget());
        assertEquals("1-N", relation.getType());
        assertEquals("一个客户有多个订单", relation.getDescription());
    }

    @Test
    void testFieldDefCreation() {
        WorksheetPlanner.FieldDef field = new WorksheetPlanner.FieldDef();
        field.setControlId("field_001");
        field.setControlName("金额");
        field.setControlType(8); // Money
        field.setRequired(true);
        field.setDescription("订单金额");

        assertEquals("field_001", field.getControlId());
        assertEquals("金额", field.getControlName());
        assertEquals(8, field.getControlType());
        assertTrue(field.isRequired());
        assertEquals("订单金额", field.getDescription());
    }

    @Test
    void testValidateEmptyResult() {
        // 验证 null 结果
        assertFalse(planner.validate(null));
    }

    @Test
    void testValidateValidResult() {
        // 验证非空结果
        WorksheetPlanner.WorksheetPlan plan = new WorksheetPlanner.WorksheetPlan();
        plan.setName("测试表");

        WorksheetPlanner.Output output = new WorksheetPlanner.Output(
            "测试应用", "描述", List.of(plan)
        );

        assertTrue(planner.validate(output));
    }

    @Test
    void testSkeletonWorksheetRelations() {
        WorksheetPlanner.SkeletonWorksheet ws = new WorksheetPlanner.SkeletonWorksheet();
        ws.setName("客户表");
        ws.setPurpose("存储客户信息");
        ws.setCoreFields(List.of("名称", "电话"));

        WorksheetPlanner.RelationDef rel = new WorksheetPlanner.RelationDef();
        rel.setTarget("订单表");
        rel.setType("1-N");
        rel.setDescription("关联订单");

        ws.setRelations(List.of(rel));

        assertEquals("客户表", ws.getName());
        assertEquals(1, ws.getRelations().size());
        assertEquals("订单表", ws.getRelations().get(0).getTarget());
    }

    /**
     * Mock AI Text Client for testing
     */
    private static class MockAiTextClient implements AiTextClient {
        @Override
        public String generateJson(String prompt, AiAuthConfig config) throws Exception {
            // 返回一个简单的 skeleton JSON
            return """
                {
                    "appName": "测试应用",
                    "summary": "这是一个测试应用",
                    "worksheets": [
                        {
                            "name": "测试表",
                            "purpose": "测试用途",
                            "coreFields": ["字段1", "字段2"],
                            "relations": []
                        }
                    ]
                }
                """;
        }
    }
}
