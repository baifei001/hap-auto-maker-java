package com.hap.automaker.service;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

public record WorksheetPlannerResult(
        Path outputJsonPath,
        JsonNode summary) {
}
