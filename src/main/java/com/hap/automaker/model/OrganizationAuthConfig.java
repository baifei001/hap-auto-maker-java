package com.hap.automaker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrganizationAuthConfig(
        String appKey,
        String secretKey,
        String projectId,
        String ownerId,
        String groupIds) {
}
