package com.hap.automaker.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

class SetupCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void setupWritesOnlyJavaConfigByDefault() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);

        SetupCommand command = new SetupCommand();
        int exitCode = new CommandLine(command).execute(
                "--repo-root", repo.toString(),
                "--provider", "deepseek",
                "--api-key", "test-key",
                "--model", "deepseek-chat",
                "--base-url", "https://api.deepseek.com/v1",
                "--org-app-key", "app-key",
                "--org-secret-key", "secret-key",
                "--project-id", "project-1",
                "--owner-id", "owner-1",
                "--group-ids", "group-a",
                "--account-id", "account-1",
                "--authorization", "auth-1",
                "--cookie", "cookie-1");

        assertEquals(0, exitCode);
        Path runtimeJson = repo.resolve("config").resolve("credentials").resolve("runtime.json");
        assertFalse(Files.exists(runtimeJson));
        assertTrue(Files.exists(repo.resolve("config").resolve("credentials").resolve("ai_auth.json")));
        assertTrue(Files.exists(repo.resolve("config").resolve("credentials").resolve("organization_auth.json")));
        assertTrue(Files.exists(repo.resolve("config").resolve("credentials").resolve("web_auth.json")));
        assertFalse(Files.exists(repo.resolve("config").resolve("credentials").resolve("auth_config.py")));
        assertFalse(Files.exists(repo.resolve("config").resolve("credentials").resolve("login_credentials.py")));
    }

    @Test
    void setupWritesLegacyPythonFilesWhenRequested() throws Exception {
        Path repo = tempDir.resolve("repo-legacy");
        Files.createDirectories(repo);

        SetupCommand command = new SetupCommand();
        int exitCode = new CommandLine(command).execute(
                "--repo-root", repo.toString(),
                "--provider", "deepseek",
                "--api-key", "test-key",
                "--model", "deepseek-chat",
                "--base-url", "https://api.deepseek.com/v1",
                "--org-app-key", "app-key",
                "--org-secret-key", "secret-key",
                "--project-id", "project-1",
                "--owner-id", "owner-1",
                "--group-ids", "group-a",
                "--account-id", "account-1",
                "--authorization", "auth-1",
                "--cookie", "cookie-1",
                "--write-legacy-python-files");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(repo.resolve("config").resolve("credentials").resolve("auth_config.py")));
        assertTrue(Files.exists(repo.resolve("config").resolve("credentials").resolve("login_credentials.py")));
    }
}
