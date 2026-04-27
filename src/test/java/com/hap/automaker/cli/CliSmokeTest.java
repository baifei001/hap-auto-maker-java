package com.hap.automaker.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class CliSmokeTest {

    @Test
    void rootCommandShowsHelp() {
        int exitCode = new CommandLine(new HapAutoMakerCli()).execute("--help");
        assertEquals(0, exitCode);
    }

    @Test
    void rootHelpIncludesPageCommands() {
        String usage = new CommandLine(new HapAutoMakerCli()).getUsageMessage();
        assertTrue(usage.contains("add-field"));
        assertTrue(usage.contains("add-view"));
        assertTrue(usage.contains("add-chart"));
        assertTrue(usage.contains("modify-view"));
        assertTrue(usage.contains("delete-view"));
        assertTrue(usage.contains("delete-default-views"));
        assertTrue(usage.contains("update-worksheet-icons"));
        assertTrue(usage.contains("add-worksheet"));
        assertTrue(usage.contains("page-get"));
        assertTrue(usage.contains("page-save"));
        assertTrue(usage.contains("page-delete"));
    }
}
