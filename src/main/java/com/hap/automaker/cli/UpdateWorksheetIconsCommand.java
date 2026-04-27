package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.UpdateWorksheetIconsService;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "update-worksheet-icons", mixinStandardHelpOptions = true, description = "Update worksheet icons in bulk")
public class UpdateWorksheetIconsCommand implements Callable<Integer> {

    private UpdateWorksheetIconsService updateWorksheetIconsServiceOverride;

    @Option(names = "--app-id", required = true)
    private String appId;

    @Option(names = "--item", split = ",")
    private List<String> item = new ArrayList<>();

    @Option(names = "--items", defaultValue = "")
    private String items;

    @Option(names = "--dry-run", defaultValue = "false")
    private boolean dryRun;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));
        JsonNode response = resolveService().updateIcons(detected.repoRoot(), appId, parseMappings(), dryRun);
        System.out.println(Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        return 0;
    }

    private List<UpdateWorksheetIconsService.IconMapping> parseMappings() {
        List<String> rawItems = new ArrayList<>();
        rawItems.addAll(item);
        if (!items.isBlank()) {
            for (String value : items.split(",")) {
                if (!value.isBlank()) {
                    rawItems.add(value.trim());
                }
            }
        }
        List<UpdateWorksheetIconsService.IconMapping> mappings = new ArrayList<>();
        for (String raw : rawItems) {
            String[] parts = raw.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("Invalid item mapping: " + raw);
            }
            mappings.add(new UpdateWorksheetIconsService.IconMapping(parts[0].trim(), parts[1].trim()));
        }
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("At least one --item or --items mapping is required");
        }
        return mappings;
    }

    void setUpdateWorksheetIconsServiceOverride(UpdateWorksheetIconsService updateWorksheetIconsServiceOverride) {
        this.updateWorksheetIconsServiceOverride = updateWorksheetIconsServiceOverride;
    }

    private UpdateWorksheetIconsService resolveService() {
        if (updateWorksheetIconsServiceOverride != null) {
            return updateWorksheetIconsServiceOverride;
        }
        return new UpdateWorksheetIconsService();
    }
}
