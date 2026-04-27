package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.ai.AiJsonParser;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;

class WorksheetPlannerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesWorksheetPlanViaLayeredAiFlow() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("java_phase1"));
        Jacksons.mapper().writeValue(
                repo.resolve("config").resolve("credentials").resolve("ai_auth.json").toFile(),
                new AiAuthConfig("deepseek", "test-key", "deepseek-chat", "https://api.deepseek.com/v1"));

        Path output = repo.resolve("data").resolve("outputs").resolve("java_phase1").resolve("worksheet_plan.json");

        WorksheetPlannerService service = new WorksheetPlannerService(new FakeAiTextClient(), new AiJsonParser());
        WorksheetPlannerResult result = service.plan(repo, "CRM", "Manage customers and orders", "Need customers and orders", "zh", output);

        assertEquals(output, result.outputJsonPath());
        assertTrue(Files.exists(output));
        JsonNode payload = Jacksons.mapper().readTree(output.toFile());
        assertEquals("CRM", payload.path("app_name").asText());
        assertEquals(2, payload.path("worksheets").size());
        assertEquals("Customers", payload.path("worksheets").path(0).path("name").asText());
        assertEquals("Orders", payload.path("worksheets").path(1).path("name").asText());
        assertEquals("Relation", payload.path("worksheets").path(1).path("fields").path(2).path("type").asText());
        assertEquals("Customers", payload.path("worksheets").path(1).path("fields").path(2).path("relation_target").asText());
    }

    private static final class FakeAiTextClient implements AiTextClient {
        private int callCount = 0;

        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            callCount++;
            return switch (callCount) {
                case 1 -> """
                        {
                          "app_name": "CRM",
                          "summary": "CRM summary",
                          "worksheets": [
                            {
                              "name": "Customers",
                              "purpose": "Manage customers",
                              "core_fields": [
                                { "name": "Customer Name", "type": "Text", "required": true, "option_values": [] }
                              ],
                              "depends_on": []
                            },
                            {
                              "name": "Orders",
                              "purpose": "Manage orders",
                              "core_fields": [
                                { "name": "Order No", "type": "Text", "required": true, "option_values": [] }
                              ],
                              "depends_on": ["Customers"]
                            }
                          ],
                          "relationships": [
                            {
                              "from": "Customers",
                              "field": "Customer",
                              "to": "Orders",
                              "cardinality": "1-N",
                              "description": "Customer has many orders"
                            }
                          ],
                          "creation_order": ["Customers", "Orders"]
                        }
                        """;
                case 2 -> """
                        {
                          "worksheetId": "",
                          "worksheetName": "Customers",
                          "fields": [
                            {
                              "name": "Customer Type",
                              "type": "SingleSelect",
                              "required": true,
                              "description": "Customer category",
                              "option_values": ["Retail", "Enterprise", "Agency"]
                            }
                          ]
                        }
                        """;
                case 3 -> """
                        {
                          "worksheetId": "",
                          "worksheetName": "Orders",
                          "fields": [
                            {
                              "name": "Amount",
                              "type": "Money",
                              "required": true,
                              "description": "Order amount",
                              "option_values": [],
                              "unit": "USD",
                              "dot": "2"
                            }
                          ]
                        }
                        """;
                default -> throw new AssertionError("Unexpected AI call");
            };
        }
    }
}
