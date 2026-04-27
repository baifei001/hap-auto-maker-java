package com.hap.automaker.service;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

public record ChartPipelineResult(
        Path planOutputPath,
        Path outputJsonPath,
        JsonNode summary) {

    public Path resultOutputPath() {
        return outputJsonPath;
    }

    public int totalCharts() {
        return summary.path("totalCharts").asInt(0);
    }

    public int createdCharts() {
        return summary.path("successCount").asInt(0);
    }
}
