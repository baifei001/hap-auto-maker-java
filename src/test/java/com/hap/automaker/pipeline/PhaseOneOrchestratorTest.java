package com.hap.automaker.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhaseOneOrchestratorTest {

    @TempDir
    Path tempDir;

    @Test
    void dryRunBuildsExpectedCoreSteps() throws Exception {
        PhaseOneOrchestrator orchestrator = new PhaseOneOrchestrator();
        PipelineContext context = orchestrator.dryRunOnly(
                tempDir,
                "Demo App",
                "group-1",
                "Demo context",
                "Need customers and invoices",
                "zh");

        assertEquals(5, context.steps.size());
        assertEquals("create_app", context.steps.get(0).stepKey);
        assertEquals("worksheets_plan", context.steps.get(1).stepKey);
        assertEquals("worksheets_create", context.steps.get(2).stepKey);
        assertEquals("java:openapi-create-and-authorize", context.steps.get(0).command);
        assertEquals("java:create-worksheets-from-plan", context.steps.get(2).command);
    }
}
