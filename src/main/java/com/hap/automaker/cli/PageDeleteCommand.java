package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.PageAdminService;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "page-delete", mixinStandardHelpOptions = true, description = "Delete a custom page")
public class PageDeleteCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(PageDeleteCommand.class);

    @Option(names = "--app-id", required = true)
    private String appId;

    @Option(names = "--app-section-id", required = true)
    private String appSectionId;

    @Option(names = "--page-id", required = true)
    private String pageId;

    @Option(names = "--project-id", defaultValue = "")
    private String projectId;

    @Option(names = "--permanent", defaultValue = "false")
    private boolean permanent;

    @Option(names = "--yes", defaultValue = "false")
    private boolean yes;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        if (!yes) {
            logger.error("Refusing to delete without --yes");
            return 2;
        }
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));
        JsonNode response = new PageAdminService().deletePage(
                detected.repoRoot(),
                appId,
                appSectionId,
                pageId,
                projectId,
                permanent);
        System.out.println(Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        return 0;
    }
}
