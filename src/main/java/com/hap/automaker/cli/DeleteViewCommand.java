package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.ViewAdminService;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "delete-view", mixinStandardHelpOptions = true, description = "Delete a worksheet view")
public class DeleteViewCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(DeleteViewCommand.class);

    @Option(names = "--app-id", required = true)
    private String appId;

    @Option(names = "--worksheet-id", required = true)
    private String worksheetId;

    @Option(names = "--view-id", required = true)
    private String viewId;

    @Option(names = "--dry-run", defaultValue = "false")
    private boolean dryRun;

    @Option(names = "--yes", defaultValue = "false")
    private boolean yes;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        if (dryRun) {
            logger.info("DRY RUN: appId={} worksheetId={} viewId={}", appId, worksheetId, viewId);
            return 0;
        }
        if (!yes) {
            logger.error("Refusing to delete without --yes");
            return 2;
        }
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));
        JsonNode response = new ViewAdminService().deleteView(detected.repoRoot(), appId, worksheetId, viewId);
        System.out.println(Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        return 0;
    }
}
