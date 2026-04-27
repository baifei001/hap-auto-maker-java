package com.hap.automaker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WebAuthConfig(
        String accountId,
        String authorization,
        String cookie,
        String loginAccount,
        String loginPassword,
        String loginUrl) {
}
