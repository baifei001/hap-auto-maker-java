package com.hap.automaker.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.model.OrganizationAuthConfig;
import com.hap.automaker.model.WebAuthConfig;

class SetupServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesOnlyJavaConfigByDefault() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot.resolve("config").resolve("credentials"));
        ConfigPaths paths = new ConfigPaths(repoRoot);

        SetupService service = new SetupService();
        service.writeAll(
                paths,
                new AiAuthConfig("gemini", "AIza-test", "gemini-2.5-flash", ""),
                new OrganizationAuthConfig("app-key", "secret-key", "project-1", "owner-1", "group-a"),
                new WebAuthConfig("account-1", "md_pss_id=token", "a=b; c=d", "user@example.com", "secret", "https://www.mingdao.com/login"));

        assertTrue(Files.exists(paths.aiAuth()));
        assertTrue(Files.exists(paths.organizationAuth()));
        assertTrue(Files.notExists(paths.credentialsDir().resolve("runtime.json")));
        assertTrue(Files.exists(paths.webAuth()));
        assertTrue(Files.notExists(paths.authConfigPy()));
        assertTrue(Files.notExists(paths.loginCredentialsPy()));
    }

    @Test
    void writesLegacyPythonCompatibilityFilesWhenRequested() throws Exception {
        Path repoRoot = tempDir.resolve("repo-legacy");
        Files.createDirectories(repoRoot.resolve("config").resolve("credentials"));
        ConfigPaths paths = new ConfigPaths(repoRoot);

        SetupService service = new SetupService();
        service.writeAll(
                paths,
                new AiAuthConfig("gemini", "AIza-test", "gemini-2.5-flash", ""),
                new OrganizationAuthConfig("app-key", "secret-key", "project-1", "owner-1", "group-a"),
                new WebAuthConfig("account-1", "md_pss_id=token", "a=b; c=d", "user@example.com", "secret", "https://www.mingdao.com/login"),
                true);

        assertTrue(Files.exists(paths.authConfigPy()));
        assertTrue(Files.exists(paths.loginCredentialsPy()));
    }

    @Test
    void writesSnakeCaseJsonKeysForPythonCompatibility() throws Exception {
        Path repoRoot = tempDir.resolve("repo2");
        Files.createDirectories(repoRoot.resolve("config").resolve("credentials"));
        ConfigPaths paths = new ConfigPaths(repoRoot);

        SetupService service = new SetupService();
        service.writeAll(
                paths,
                new AiAuthConfig("gemini", "AIza-test", "gemini-2.5-flash", "https://example.com"),
                new OrganizationAuthConfig("app-key", "secret-key", "project-1", "owner-1", "group-a"),
                new WebAuthConfig("account-1", "md_pss_id=token", "a=b; c=d", "user@example.com", "secret", "https://www.mingdao.com/login"));

        JsonNode aiAuth = Jacksons.mapper().readTree(paths.aiAuth().toFile());
        JsonNode orgAuth = Jacksons.mapper().readTree(paths.organizationAuth().toFile());

        assertEquals("AIza-test", aiAuth.path("api_key").asText());
        assertEquals("https://example.com", aiAuth.path("base_url").asText());
        assertEquals("app-key", orgAuth.path("app_key").asText());
        assertEquals("secret-key", orgAuth.path("secret_key").asText());
        assertEquals("project-1", orgAuth.path("project_id").asText());
        assertEquals("owner-1", orgAuth.path("owner_id").asText());
        assertEquals("group-a", orgAuth.path("group_ids").asText());
    }
}
