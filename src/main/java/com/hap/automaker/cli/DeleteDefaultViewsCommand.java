package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.DeleteDefaultViewsService;
import com.hap.automaker.service.ViewAdminService;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "delete-default-views", mixinStandardHelpOptions = true, description = "Delete default worksheet views across an app")
public class DeleteDefaultViewsCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(DeleteDefaultViewsCommand.class);

    private DeleteDefaultViewsService deleteDefaultViewsServiceOverride;

    @Option(names = "--app-id", required = true)
    private String appId;

    @Option(names = "--dry-run", defaultValue = "false")
    private boolean dryRun;

    @Option(names = "--all-views", defaultValue = "false")
    private boolean allViews;

    @Option(names = "--yes", defaultValue = "false")
    private boolean yes;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        if (!dryRun && !yes) {
            logger.error("Refusing to delete without --yes");
            return 2;
        }
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));
        JsonNode response = resolveService().deleteDefaultViews(detected.repoRoot(), appId, dryRun, allViews);
        System.out.println(Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        return 0;
    }

    void setDeleteDefaultViewsServiceOverride(DeleteDefaultViewsService deleteDefaultViewsServiceOverride) {
        this.deleteDefaultViewsServiceOverride = deleteDefaultViewsServiceOverride;
    }

    private DeleteDefaultViewsService resolveService() {
        if (deleteDefaultViewsServiceOverride != null) {
            return deleteDefaultViewsServiceOverride;
        }
        return new DeleteDefaultViewsService();
    }
}
