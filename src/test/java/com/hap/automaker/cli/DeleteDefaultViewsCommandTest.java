package com.hap.automaker.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class DeleteDefaultViewsCommandTest {

    @Test
    void helpIsAvailable() {
        DeleteDefaultViewsCommand command = new DeleteDefaultViewsCommand();
        int exitCode = new CommandLine(command).execute("--help");

        assertEquals(0, exitCode);
    }

    @Test
    void refusesDeleteWithoutYesFlag() {
        DeleteDefaultViewsCommand command = new DeleteDefaultViewsCommand();
        int exitCode = new CommandLine(command).execute(
                "--app-id", "app-123");

        assertEquals(2, exitCode);
    }
}
