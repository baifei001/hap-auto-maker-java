package com.hap.automaker.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class PageDeleteCommandTest {

    @Test
    void refusesDeleteWithoutYesFlag() {
        PageDeleteCommand command = new PageDeleteCommand();
        int exitCode = new CommandLine(command).execute(
                "--app-id", "app-123",
                "--app-section-id", "section-1",
                "--page-id", "page-001");

        assertEquals(2, exitCode);
    }
}
