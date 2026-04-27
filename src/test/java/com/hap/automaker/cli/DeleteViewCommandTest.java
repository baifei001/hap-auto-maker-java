package com.hap.automaker.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class DeleteViewCommandTest {

    @Test
    void refusesDeleteWithoutYesFlag() {
        DeleteViewCommand command = new DeleteViewCommand();
        int exitCode = new CommandLine(command).execute(
                "--app-id", "app-123",
                "--worksheet-id", "ws-001",
                "--view-id", "view-001");

        assertEquals(2, exitCode);
    }

    @Test
    void dryRunDoesNotRequireYesFlag() {
        DeleteViewCommand command = new DeleteViewCommand();
        int exitCode = new CommandLine(command).execute(
                "--app-id", "app-123",
                "--worksheet-id", "ws-001",
                "--view-id", "view-001",
                "--dry-run");

        assertEquals(0, exitCode);
    }
}
