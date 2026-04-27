package com.hap.automaker.service;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

public record AppBootstrapResult(
        String appId,
        Path appAuthJsonPath,
        JsonNode createResponse,
        JsonNode authorizeResponse) {
}
