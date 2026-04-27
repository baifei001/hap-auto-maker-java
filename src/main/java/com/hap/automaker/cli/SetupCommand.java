package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.model.OrganizationAuthConfig;
import com.hap.automaker.model.WebAuthConfig;
import com.hap.automaker.service.SetupService;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "setup", mixinStandardHelpOptions = true, description = "Write Phase 1 JSON config and optionally legacy compatibility files")
public class SetupCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(SetupCommand.class);

    @Option(names = "--provider", required = true)
    private String provider;

    @Option(names = "--api-key", required = true)
    private String apiKey;

    @Option(names = "--model", required = true)
    private String model;

    @Option(names = "--base-url", defaultValue = "")
    private String baseUrl;

    @Option(names = "--org-app-key", required = true)
    private String orgAppKey;

    @Option(names = "--org-secret-key", required = true)
    private String orgSecretKey;

    @Option(names = "--project-id", required = true)
    private String projectId;

    @Option(names = "--owner-id", required = true)
    private String ownerId;

    @Option(names = "--group-ids", defaultValue = "")
    private String groupIds;

    @Option(names = "--account-id", defaultValue = "")
    private String accountId;

    @Option(names = "--authorization", defaultValue = "")
    private String authorization;

    @Option(names = "--cookie", defaultValue = "")
    private String cookie;

    @Option(names = "--login-account", defaultValue = "")
    private String loginAccount;

    @Option(names = "--login-password", defaultValue = "")
    private String loginPassword;

    @Option(names = "--login-url", defaultValue = "https://www.mingdao.com/login")
    private String loginUrl;

    @Option(names = "--write-legacy-python-files", defaultValue = "false")
    private boolean writeLegacyPythonFiles;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));

        ConfigPaths paths = new ConfigPaths(detected.repoRoot());
        SetupService service = new SetupService();
        service.writeAll(
                paths,
                new AiAuthConfig(provider, apiKey, model, baseUrl),
                new OrganizationAuthConfig(orgAppKey, orgSecretKey, projectId, ownerId, groupIds),
                new WebAuthConfig(accountId, authorization, cookie, loginAccount, loginPassword, loginUrl),
                writeLegacyPythonFiles);

        logger.info("Wrote setup files under {}", paths.credentialsDir());
        return 0;
    }
}
