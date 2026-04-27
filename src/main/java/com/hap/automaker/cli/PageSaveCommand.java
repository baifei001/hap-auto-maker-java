package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.PageAdminService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "page-save", mixinStandardHelpOptions = true, description = "Initialize or save an empty page layout")
public class PageSaveCommand implements Callable<Integer> {

    @Option(names = "--page-id", required = true)
    private String pageId;

    @Option(names = "--page-version", defaultValue = "0")
    private int pageVersion;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));
        JsonNode response = new PageAdminService().saveBlankPage(detected.repoRoot(), pageId, pageVersion);
        System.out.println(Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        return 0;
    }
}
