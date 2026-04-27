package com.hap.automaker.service;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

public record WorksheetCreateResult(
        Path outputJsonPath,
        JsonNode summary) {
}
