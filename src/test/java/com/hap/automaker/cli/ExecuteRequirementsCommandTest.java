package com.hap.automaker.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;

class ExecuteRequirementsCommandTest {

    private static final Logger logger = LoggerFactory.getLogger(ExecuteRequirementsCommandTest.class);

    @TempDir
    Path tempDir;

    @Test
    void writesDryRunReportForPhaseOneFlow() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("execution_runs"));
        Files.createDirectories(repo.resolve("scripts"));

        Path spec = repo.resolve("spec.json");
        Jacksons.mapper().writeValue(spec.toFile(), Jacksons.mapper().readTree("""
                {
                  "schema_version": "workflow_requirement_v1",
                  "meta": { "language": "zh" },
                  "app": { "name": "演示应用", "group_ids": "group-a" },
                  "worksheets": { "business_context": "演示上下文", "requirements": "客户和订单" },
                  "views": { "enabled": true },
                  "pages": { "enabled": true },
                  "execution": { "dry_run": true, "fail_fast": true }
                }
                """));

        ExecuteRequirementsCommand command = new ExecuteRequirementsCommand();
        int exitCode = command.executeWithSpec(repo, spec, "zh", true);
        assertEquals(0, exitCode);

        Path latest = repo.resolve("data").resolve("outputs").resolve("execution_runs").resolve("execution_run_latest.json");
        assertTrue(Files.exists(latest));
        JsonNode report = Jacksons.mapper().readTree(latest.toFile());
        assertEquals(5, report.path("steps").size());
    }

    @Test
    void writesDryRunReportWithPhaseTwoFeatures() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("execution_runs"));
        Files.createDirectories(repo.resolve("scripts"));

        Path spec = repo.resolve("spec.json");
        Jacksons.mapper().writeValue(spec.toFile(), Jacksons.mapper().readTree("""
                {
                  "schema_version": "workflow_requirement_v1",
                  "meta": { "language": "zh" },
                  "app": { "name": "演示应用", "group_ids": "group-a" },
                  "worksheets": { "business_context": "演示上下文", "requirements": "客户和订单" },
                  "views": { "enabled": true },
                  "pages": { "enabled": true },
                  "execution": { "dry_run": true, "fail_fast": true }
                }
                """));

        ExecuteRequirementsCommand command = new ExecuteRequirementsCommand();
        // Enable all Phase Two features
        command.setEnableSections(true);
        command.setEnableRoles(true);
        command.setEnableChatbots(true);
        command.setEnableDeleteDefaultViews(true);

        int exitCode = command.executeWithSpec(repo, spec, "zh", true);
        assertEquals(0, exitCode);

        Path latest = repo.resolve("data").resolve("outputs").resolve("execution_runs").resolve("execution_run_latest.json");
        assertTrue(Files.exists(latest));
        JsonNode report = Jacksons.mapper().readTree(latest.toFile());
        // Should have Phase One (5 steps) + Phase Two steps (sections=1, roles=1, chatbots=1, delete=1)
        int stepCount = report.path("steps").size();
        logger.info("Total steps with Phase Two: {}", stepCount);
        assertTrue(stepCount > 5, "Expected more than 5 steps with Phase Two enabled, got " + stepCount);
        assertTrue(stepCount >= 9, "Expected at least 9 steps (5 Phase One + 4 Phase Two), got " + stepCount);
    }
}
