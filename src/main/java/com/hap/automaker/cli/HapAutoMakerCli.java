package com.hap.automaker.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "hap-auto-maker",
        mixinStandardHelpOptions = true,
        version = "0.1.0-SNAPSHOT",
        description = "Windows-first Java CLI for HAP Auto Maker phase 1",
        subcommands = {
                SetupCommand.class,
                MakeAppCommand.class,
                ExecuteRequirementsCommand.class,
                AddWorksheetCommand.class,
                AddFieldCommand.class,
                AddViewCommand.class,
                AddChartCommand.class,
                ModifyViewCommand.class,
                DeleteViewCommand.class,
                DeleteDefaultViewsCommand.class,
                UpdateWorksheetIconsCommand.class,
                PageGetCommand.class,
                PageSaveCommand.class,
                PageDeleteCommand.class,
                ValidateCommand.class
        })
public class HapAutoMakerCli implements Callable<Integer> {

    @Option(names = "--repo-root", description = "Override the repository root directory")
    private String repoRoot;

    public String getRepoRoot() {
        return repoRoot;
    }

    @Override
    public Integer call() {
        return 0;
    }
}
