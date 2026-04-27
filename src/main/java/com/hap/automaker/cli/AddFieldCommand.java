package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.AddFieldService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "add-field", mixinStandardHelpOptions = true, description = "Add a field incrementally")
public class AddFieldCommand implements Callable<Integer> {

    private AddFieldService addFieldServiceOverride;

    @Option(names = "--app-id", required = true)
    private String appId;

    @Option(names = "--worksheet-id", required = true)
    private String worksheetId;

    @Option(names = "--name", required = true)
    private String name;

    @Option(names = "--type", defaultValue = "")
    private String type;

    @Option(names = "--description", defaultValue = "")
    private String description;

    @Option(names = "--required", defaultValue = "false")
    private boolean required;

    @Option(names = "--options", split = ",")
    private List<String> options = new ArrayList<>();

    @Option(names = "--no-execute", defaultValue = "false")
    private boolean noExecute;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));
        JsonNode response = resolveService().addField(
                detected.repoRoot(),
                appId,
                worksheetId,
                name,
                type,
                description,
                required,
                options,
                !noExecute);
        System.out.println(Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        return 0;
    }

    void setAddFieldServiceOverride(AddFieldService addFieldServiceOverride) {
        this.addFieldServiceOverride = addFieldServiceOverride;
    }

    private AddFieldService resolveService() {
        if (addFieldServiceOverride != null) {
            return addFieldServiceOverride;
        }
        return new AddFieldService();
    }
}
