package com.hap.automaker.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class AddViewCommandTest {

    @Test
    void helpIsAvailable() {
        AddViewCommand command = new AddViewCommand();
        int exitCode = new CommandLine(command).execute("--help");

        assertEquals(0, exitCode);
    }
}
