package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.ViewPipelineService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "modify-view", mixinStandardHelpOptions = true, description = "Modify an existing worksheet view incrementally")
public class ModifyViewCommand implements Callable<Integer> {

    private ViewPipelineService viewPipelineServiceOverride;

    @Option(names = "--app-id", required = true)
    private String appId;

    @Option(names = "--worksheet-id", required = true)
    private String worksheetId;

    @Option(names = "--view-id", required = true)
    private String viewId;

    @Option(names = "--description", required = true)
    private String description;

    @Option(names = "--no-execute", defaultValue = "false")
    private boolean noExecute;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));
        JsonNode response = resolveService().modifySingleView(
                detected.repoRoot(),
                appId,
                worksheetId,
                viewId,
                description,
                !noExecute);
        System.out.println(Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        return 0;
    }

    void setViewPipelineServiceOverride(ViewPipelineService viewPipelineServiceOverride) {
        this.viewPipelineServiceOverride = viewPipelineServiceOverride;
    }

    private ViewPipelineService resolveService() {
        if (viewPipelineServiceOverride != null) {
            return viewPipelineServiceOverride;
        }
        return new ViewPipelineService();
    }
}
