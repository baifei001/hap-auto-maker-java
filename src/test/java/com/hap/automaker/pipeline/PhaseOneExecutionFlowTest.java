package com.hap.automaker.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.service.AppBootstrapResult;
import com.hap.automaker.service.AppBootstrapper;
import com.hap.automaker.service.PagePipelineResult;
import com.hap.automaker.service.PagePipelineRunner;
import com.hap.automaker.service.WorksheetPlannerResult;
import com.hap.automaker.service.WorksheetPlannerRunner;
import com.hap.automaker.service.ViewPipelineResult;
import com.hap.automaker.service.ViewPipelineRunner;
import com.hap.automaker.service.WorksheetCreateResult;
import com.hap.automaker.service.WorksheetCreator;

class PhaseOneExecutionFlowTest {

    @TempDir
    Path tempDir;

    @Test
    void executeRunsJavaStepsAndCapturesArtifactsWithoutCompatRunner() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("data").resolve("outputs"));

        PhaseOneOrchestrator orchestrator = new PhaseOneOrchestrator(
                new FakeBootstrapper(),
                new FakeWorksheetPlannerRunner(),
                new FakeWorksheetCreator(),
                new FakeViewPipelineRunner(),
                new FakePagePipelineRunner());
        PipelineContext context = orchestrator.execute(
                repo,
                "Demo App",
                "group-1",
                "Demo context",
                "Need customers and invoices",
                "zh",
                true,
                true,
                true,
                true);

        assertEquals("app-123", context.appId);
        assertEquals("java:openapi-create-and-authorize", context.steps.get(0).command);
        assertTrue(context.appAuthJson.endsWith("app_authorize_java_phase1.json"));
        assertTrue(context.worksheetPlanJson.endsWith("worksheet_plan.json"));
        assertTrue(context.worksheetCreateResultJson.endsWith("worksheet_create_result.json"));
        assertTrue(context.viewResultJson.endsWith("view_pipeline_result.json"));
        assertTrue(context.pageResultJson.endsWith("page_create_result.json"));
        assertEquals(5, context.steps.size());
        assertTrue(context.steps.stream().allMatch(step -> step.ok));
        assertEquals("java:plan-worksheets", context.steps.get(1).command);
        assertEquals("java:create-worksheets-from-plan", context.steps.get(2).command);
        assertEquals("java:view-pipeline", context.steps.get(3).command);
        assertEquals("java:page-pipeline", context.steps.get(4).command);
        assertEquals("app-123", context.steps.get(4).artifacts.get("pageAppId"));
    }

    private static final class FakeBootstrapper implements AppBootstrapper {

        @Override
        public AppBootstrapResult createAndAuthorize(Path repoRoot, String appName, String groupIds) throws Exception {
            Path authOutput = repoRoot.resolve("data").resolve("outputs").resolve("app_authorizations")
                    .resolve("app_authorize_java_phase1.json");
            Files.createDirectories(authOutput.getParent());
            Jacksons.mapper().writeValue(authOutput.toFile(), Map.of("data", List.of(Map.of("appId", "app-123"))));
            return new AppBootstrapResult(
                    "app-123",
                    authOutput,
                    Jacksons.mapper().readTree("{\"data\":{\"appId\":\"app-123\"},\"success\":true,\"error_code\":1}"),
                    Jacksons.mapper().readTree("{\"data\":[{\"appId\":\"app-123\"}],\"success\":true,\"error_code\":1}"));
        }
    }

    private static final class FakeWorksheetPlannerRunner implements WorksheetPlannerRunner {
        @Override
        public WorksheetPlannerResult plan(
                Path repoRoot,
                String appName,
                String businessContext,
                String requirements,
                String language,
                Path outputJson) throws Exception {
            Files.createDirectories(outputJson.getParent());
            JsonNode summary = Jacksons.mapper().readTree("""
                    {
                      "worksheets": []
                    }
                    """);
            Jacksons.mapper().writeValue(outputJson.toFile(), summary);
            return new WorksheetPlannerResult(outputJson, summary);
        }
    }

    private static final class FakeWorksheetCreator implements WorksheetCreator {

        @Override
        public WorksheetCreateResult createFromPlan(Path repoRoot, Path planJson, Path appAuthJson, Path outputJson)
                throws Exception {
            Files.createDirectories(outputJson.getParent());
            JsonNode summary = Jacksons.mapper().readTree("""
                    {
                      "name_to_worksheet_id": {
                        "Customers": "ws-001"
                      }
                    }
                    """);
            Jacksons.mapper().writeValue(outputJson.toFile(), summary);
            return new WorksheetCreateResult(outputJson, summary);
        }
    }

    private static final class FakeViewPipelineRunner implements ViewPipelineRunner {

        @Override
        public ViewPipelineResult run(Path repoRoot, Path appAuthJson, Path outputJson) throws Exception {
            Files.createDirectories(outputJson.getParent());
            JsonNode summary = Jacksons.mapper().readTree("""
                    {
                      "worksheets": []
                    }
                    """);
            Jacksons.mapper().writeValue(outputJson.toFile(), summary);
            return new ViewPipelineResult(outputJson, summary);
        }
    }

    private static final class FakePagePipelineRunner implements PagePipelineRunner {
        @Override
        public PagePipelineResult run(Path repoRoot, String pageAppId, Path planOutput, Path outputJson) throws Exception {
            if (!"app-123".equals(pageAppId)) {
                throw new AssertionError("Page pipeline should receive appId app-123, got: " + pageAppId);
            }
            Files.createDirectories(planOutput.getParent());
            Jacksons.mapper().writeValue(planOutput.toFile(), Map.of("pages", List.of()));
            Files.createDirectories(outputJson.getParent());
            JsonNode summary = Jacksons.mapper().readTree("""
                    {
                      "results": []
                    }
                    """);
            Jacksons.mapper().writeValue(outputJson.toFile(), summary);
            return new PagePipelineResult(planOutput, outputJson, summary);
        }
    }
}
