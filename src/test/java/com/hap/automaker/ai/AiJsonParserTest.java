package com.hap.automaker.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class AiJsonParserTest {

    @Test
    void extractsJsonFromCodeFence() throws Exception {
        String raw = "```json\n{\"app\":{\"name\":\"Invoice\"}}\n```";
        JsonNode node = new AiJsonParser().parse(raw);
        assertEquals("Invoice", node.path("app").path("name").asText());
    }
}
