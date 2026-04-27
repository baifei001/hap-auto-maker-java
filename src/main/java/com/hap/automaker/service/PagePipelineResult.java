package com.hap.automaker.service;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

public record PagePipelineResult(
        Path planOutputPath,
        Path outputJsonPath,
        JsonNode summary) {
}
