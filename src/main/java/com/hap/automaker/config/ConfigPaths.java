package com.hap.automaker.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class ConfigPaths {

    // 项目根目录 (相对于当前工作目录)
    private static final Path REPO_ROOT = Paths.get(".").toAbsolutePath().normalize();
    private static final Path CONFIG_DIR = REPO_ROOT.resolve("config");
    private static final Path CREDENTIALS_DIR = CONFIG_DIR.resolve("credentials");

    private final Path credentialsDir;

    public ConfigPaths(Path repoRoot) {
        this.credentialsDir = repoRoot.resolve("config").resolve("credentials");
    }

    public Path credentialsDir() {
        return credentialsDir;
    }

    public Path aiAuth() {
        return credentialsDir.resolve("ai_auth.json");
    }

    public Path organizationAuth() {
        return credentialsDir.resolve("organization_auth.json");
    }

    public Path webAuth() {
        return credentialsDir.resolve("web_auth.json");
    }

    public Path authConfigPy() {
        return credentialsDir.resolve("auth_config.py");
    }

    public Path loginCredentialsPy() {
        return credentialsDir.resolve("login_credentials.py");
    }

    // ========== 静态方法 ==========

    public static Path getRepoRoot() {
        return REPO_ROOT;
    }

    public static Path getConfigDir() {
        return CONFIG_DIR;
    }

    public static Path getCredentialsDir() {
        return CREDENTIALS_DIR;
    }

    public static Path getAiConfigPath() {
        return CREDENTIALS_DIR.resolve("ai_auth.json");
    }

    public static Path getOrganizationAuthPath() {
        return CREDENTIALS_DIR.resolve("organization_auth.json");
    }

    public static Path getWebAuthPath() {
        return CREDENTIALS_DIR.resolve("auth_config.py");
    }
}
