package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hap.automaker.ai.AiJsonParser;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.model.OrganizationAuthConfig;

class SpecGeneratorServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsNonObjectAiPayloadWithHelpfulMessage() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
        Jacksons.mapper().writeValue(
                repo.resolve("config").resolve("credentials").resolve("ai_auth.json").toFile(),
                new AiAuthConfig("gemini", "AIza-test", "gemini-2.5-flash", ""));
        Jacksons.mapper().writeValue(
                repo.resolve("config").resolve("credentials").resolve("organization_auth.json").toFile(),
                new OrganizationAuthConfig("app-key", "secret-key", "project-1", "owner-1", "group-a"));

        SpecGeneratorService service = new SpecGeneratorService(
                new ArrayAiTextClient(),
                new AiJsonParser(),
                new SpecNormalizer());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.generateSpec(repo, "Create app", "zh"));
        assertTrue(error.getMessage().contains("JSON object"));
    }

    private static final class ArrayAiTextClient implements AiTextClient {
        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            return "[]";
        }
    }
}
