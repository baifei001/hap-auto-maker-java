package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.ChartPipelineService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "add-chart", mixinStandardHelpOptions = true, description = "Add charts incrementally for an app or worksheet")
public class AddChartCommand implements Callable<Integer> {

    private ChartPipelineService chartPipelineServiceOverride;

    @Option(names = "--app-id", required = true)
    private String appId;

    @Option(names = "--worksheet-id", defaultValue = "")
    private String worksheetId;

    @Option(names = "--page-id", defaultValue = "")
    private String pageId;

    @Option(names = "--description", defaultValue = "")
    private String description;

    @Option(names = "--target-count", defaultValue = "6")
    private int targetCount;

    @Option(names = "--no-execute", defaultValue = "false")
    private boolean noExecute;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));
        JsonNode response = resolveService().addCharts(
                detected.repoRoot(),
                appId,
                worksheetId,
                pageId,
                description,
                targetCount,
                !noExecute);
        System.out.println(Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        return 0;
    }

    void setChartPipelineServiceOverride(ChartPipelineService chartPipelineServiceOverride) {
        this.chartPipelineServiceOverride = chartPipelineServiceOverride;
    }

    private ChartPipelineService resolveService() {
        if (chartPipelineServiceOverride != null) {
            return chartPipelineServiceOverride;
        }
        return new ChartPipelineService();
    }
}
