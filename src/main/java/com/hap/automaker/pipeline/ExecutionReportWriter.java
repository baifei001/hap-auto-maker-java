package com.hap.automaker.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.hap.automaker.config.Jacksons;

public final class ExecutionReportWriter {

    public Path write(Path repoRoot, PipelineContext context) throws Exception {
        Path outputDir = repoRoot.resolve("data").resolve("outputs").resolve("execution_runs");
        Files.createDirectories(outputDir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        Path report = outputDir.resolve("execution_run_" + stamp + ".json");
        Jacksons.mapper().writeValue(report.toFile(), context.buildReport());
        Jacksons.mapper().writeValue(outputDir.resolve("execution_run_latest.json").toFile(), context.buildReport());
        return report;
    }
}
