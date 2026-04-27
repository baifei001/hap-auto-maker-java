package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;

/**
 * Mock HapApiClient for testing
 */
public class MockHapApiClient extends HapApiClient {
    private JsonNode nextResponse;

    public void setNextResponse(JsonNode response) {
        this.nextResponse = response;
    }

    public void clearResponse() {
        this.nextResponse = null;
    }

    @Override
    public JsonNode saveReportConfig(JsonNode reportConfig) throws Exception {
        return nextResponse != null ? nextResponse : createDefaultResponse("mock-report-id");
    }

    @Override
    public JsonNode saveReportPage(String pageId, JsonNode pageConfig) throws Exception {
        return nextResponse != null ? nextResponse : createDefaultResponse("mock-page-id");
    }

    @Override
    public JsonNode createRowsBatchV3(String worksheetId, JsonNode rows, boolean triggerWorkflow) throws Exception {
        return nextResponse != null ? nextResponse : createRowsResponse(2);
    }

    @Override
    public JsonNode updateRowV3(String worksheetId, String rowId, JsonNode fields, boolean triggerWorkflow) throws Exception {
        return nextResponse != null ? nextResponse : createDefaultResponse(rowId);
    }

    @Override
    public JsonNode post(String path, JsonNode body) throws Exception {
        return nextResponse != null ? nextResponse : createDefaultResponse("mock-id");
    }

    @Override
    public JsonNode saveWorksheetViewFilter(String worksheetId, String viewId, JsonNode filterConfig) throws Exception {
        return nextResponse != null ? nextResponse : createDefaultResponse("mock-filter-id");
    }

    private JsonNode createDefaultResponse(String id) {
        if (id.equals("mock-id")) {
            // For chatbot creation, return chatbotId
            return Jacksons.mapper().createObjectNode()
                .put("success", true)
                .set("data", Jacksons.mapper().createObjectNode()
                    .put("chatbotId", "mock-chatbot-id"));
        }
        if (id.equals("mock-filter-id")) {
            // For filter creation, return filterId
            return Jacksons.mapper().createObjectNode()
                .put("success", true)
                .set("data", Jacksons.mapper().createObjectNode()
                    .put("filterId", "mock-filter-id"));
        }
        return Jacksons.mapper().createObjectNode()
            .put("success", true)
            .set("data", Jacksons.mapper().createObjectNode()
                .put("id", id));
    }

    private JsonNode createRowsResponse(int count) {
        JsonNode ids = Jacksons.mapper().createArrayNode()
            .add("row-001")
            .add("row-002");
        return Jacksons.mapper().createObjectNode()
            .put("success", true)
            .set("data", Jacksons.mapper().createObjectNode()
                .set("rowIds", ids));
    }
}
