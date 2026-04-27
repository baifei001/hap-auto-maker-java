package com.hap.automaker.cli;

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

class ExecuteRequirementsCommandRealExecutionTest {

    @TempDir
    Path tempDir;

    @Test
    void writesReportForRealExecutionPathWithoutCompatRunner() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("execution_runs"));

        Path spec = repo.resolve("spec.json");
        Jacksons.mapper().writeValue(spec.toFile(), Jacksons.mapper().readTree("""
                {
                  "schema_version": "workflow_requirement_v1",
                  "meta": { "language": "zh" },
                  "app": { "name": "演示应用", "group_ids": "group-a" },
                  "worksheets": { "enabled": true, "business_context": "演示上下文", "requirements": "客户和订单" },
                  "views": { "enabled": true },
                  "pages": { "enabled": true },
                  "execution": { "dry_run": false, "fail_fast": true }
                }
                """));

        ExecuteRequirementsCommand command = new ExecuteRequirementsCommand();
        command.setAppBootstrapperOverride(new FakeBootstrapper());
        command.setWorksheetPlannerRunnerOverride(new FakeWorksheetPlannerRunner());
        command.setWorksheetCreatorOverride(new FakeWorksheetCreator());
        command.setViewPipelineRunnerOverride(new FakeViewPipelineRunner());
        command.setPagePipelineRunnerOverride(new FakePagePipelineRunner());
        int exitCode = command.executeWithSpec(repo, spec, "zh", false);
        assertEquals(0, exitCode);

        Path latest = repo.resolve("data").resolve("outputs").resolve("execution_runs").resolve("execution_run_latest.json");
        JsonNode report = Jacksons.mapper().readTree(latest.toFile());
        assertEquals("app-123", report.path("app_id").asText());
        assertEquals(5, report.path("steps").size());
        assertTrue(report.path("app_auth_json").asText().endsWith("app_authorize_java_phase1.json"));
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
