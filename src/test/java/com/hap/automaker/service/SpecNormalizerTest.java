package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

class SpecNormalizerTest {

    @Test
    void fillsWorkflowRequirementDefaults() {
        ObjectNode raw = JsonNodeFactory.instance.objectNode();
        raw.put("schema_version", "workflow_requirement_v1");

        ObjectNode normalized = new SpecNormalizer().normalize(raw, "zh");
        assertEquals("workflow_requirement_v1", normalized.path("schema_version").asText());
        assertEquals(true, normalized.path("worksheets").path("enabled").asBoolean());
        assertEquals(true, normalized.path("views").path("enabled").asBoolean());
        assertEquals(true, normalized.path("pages").path("enabled").asBoolean());
    }

    @Test
    void unwrapsNestedWorkflowRequirementPayload() {
        ObjectNode nested = JsonNodeFactory.instance.objectNode();
        nested.put("name", "Customer App");
        nested.put("description", "Customer and order app");

        ObjectNode raw = JsonNodeFactory.instance.objectNode();
        raw.set("workflow_requirement_v1", nested);

        ObjectNode normalized = new SpecNormalizer().normalize(raw, "zh");
        assertEquals("workflow_requirement_v1", normalized.path("schema_version").asText());
        assertEquals("Customer App", normalized.path("app").path("name").asText());
        assertEquals("Customer and order app", normalized.path("worksheets").path("business_context").asText());
    }
}
