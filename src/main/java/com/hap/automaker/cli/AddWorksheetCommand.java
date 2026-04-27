package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.AddWorksheetService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "add-worksheet", mixinStandardHelpOptions = true, description = "Add a worksheet incrementally")
public class AddWorksheetCommand implements Callable<Integer> {

    private AddWorksheetService addWorksheetServiceOverride;

    @Option(names = "--app-id", required = true)
    private String appId;

    @Option(names = "--name", required = true)
    private String name;

    @Option(names = "--description", defaultValue = "")
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
        JsonNode response = resolveService().addWorksheet(
                detected.repoRoot(),
                appId,
                name,
                description,
                !noExecute);
        System.out.println(Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        return 0;
    }

    void setAddWorksheetServiceOverride(AddWorksheetService addWorksheetServiceOverride) {
        this.addWorksheetServiceOverride = addWorksheetServiceOverride;
    }

    private AddWorksheetService resolveService() {
        if (addWorksheetServiceOverride != null) {
            return addWorksheetServiceOverride;
        }
        return new AddWorksheetService();
    }
}
