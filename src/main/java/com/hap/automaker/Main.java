package com.hap.automaker;

import com.hap.automaker.cli.HapAutoMakerCli;

import picocli.CommandLine;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new HapAutoMakerCli()).execute(args);
        System.exit(exitCode);
    }
}
