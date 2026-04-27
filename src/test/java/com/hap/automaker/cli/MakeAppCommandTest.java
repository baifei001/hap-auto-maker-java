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

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.model.OrganizationAuthConfig;
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

import picocli.CommandLine;

class MakeAppCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void noExecuteWritesLatestSpec() throws Exception {
        Path repo = prepareRepo("repo-no-execute");

        MakeAppCommand command = new MakeAppCommand();
        command.setAiTextClientOverride(new FakeAiTextClient());

        int exitCode = new CommandLine(command).execute(
                "--repo-root", repo.toString(),
                "--requirements", "创建包含客户和订单的应用",
                "--language", "zh",
                "--no-execute");

        assertEquals(0, exitCode);
        Path latestSpec = repo.resolve("data").resolve("outputs").resolve("requirement_specs").resolve("requirement_spec_latest.json");
        assertTrue(Files.exists(latestSpec));
        JsonNode spec = Jacksons.mapper().readTree(latestSpec.toFile());
        assertEquals("workflow_requirement_v1", spec.path("schema_version").asText());
    }

    @Test
    void executePathWritesExecutionReport() throws Exception {
        Path repo = prepareRepo("repo-execute");

        ExecuteRequirementsCommand executeRequirementsCommand = new ExecuteRequirementsCommand();
        executeRequirementsCommand.setAppBootstrapperOverride(new FakeBootstrapper());
        executeRequirementsCommand.setWorksheetPlannerRunnerOverride(new FakeWorksheetPlannerRunner());
        executeRequirementsCommand.setWorksheetCreatorOverride(new FakeWorksheetCreator());
        executeRequirementsCommand.setViewPipelineRunnerOverride(new FakeViewPipelineRunner());
        executeRequirementsCommand.setPagePipelineRunnerOverride(new FakePagePipelineRunner());

        MakeAppCommand command = new MakeAppCommand();
        command.setAiTextClientOverride(new FakeAiTextClient());
        command.setExecuteRequirementsCommandOverride(executeRequirementsCommand);

        int exitCode = new CommandLine(command).execute(
                "--repo-root", repo.toString(),
                "--requirements", "创建包含客户和订单的应用",
                "--language", "zh");

        assertEquals(0, exitCode);
        Path latestReport = repo.resolve("data").resolve("outputs").resolve("execution_runs").resolve("execution_run_latest.json");
        assertTrue(Files.exists(latestReport));
        JsonNode report = Jacksons.mapper().readTree(latestReport.toFile());
        assertEquals(5, report.path("steps").size());
    }

    @Test
    void specJsonPathPreservesSpecLanguageWhenLanguageNotProvided() throws Exception {
        Path repo = prepareRepo("repo-spec-json");
        Path spec = repo.resolve("existing-spec.json");
        Jacksons.mapper().writeValue(spec.toFile(), Jacksons.mapper().readTree("""
                {
                  "schema_version": "workflow_requirement_v1",
                  "meta": { "language": "en" },
                  "app": { "name": "Demo App", "group_ids": "group-a" },
                  "worksheets": { "enabled": true, "business_context": "Demo context", "requirements": "Customers and orders" },
                  "views": { "enabled": true },
                  "pages": { "enabled": true },
                  "execution": { "dry_run": true, "fail_fast": true }
                }
                """));

        CapturingExecuteRequirementsCommand executeRequirementsCommand = new CapturingExecuteRequirementsCommand();
        MakeAppCommand command = new MakeAppCommand();
        command.setExecuteRequirementsCommandOverride(executeRequirementsCommand);

        int exitCode = new CommandLine(command).execute(
                "--repo-root", repo.toString(),
                "--spec-json", spec.toString());

        assertEquals(0, exitCode);
        assertEquals("auto", executeRequirementsCommand.capturedLanguage);
    }

    private Path prepareRepo(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo.resolve("config").resolve("credentials"));
        Files.createDirectories(repo.resolve("data").resolve("outputs").resolve("execution_runs"));
        Jacksons.mapper().writeValue(
                repo.resolve("config").resolve("credentials").resolve("ai_auth.json").toFile(),
                new AiAuthConfig("gemini", "AIza-test", "gemini-2.5-flash", ""));
        Jacksons.mapper().writeValue(
                repo.resolve("config").resolve("credentials").resolve("organization_auth.json").toFile(),
                new OrganizationAuthConfig("app-key", "secret-key", "project-1", "owner-1", "group-a"));
        return repo;
    }

    private static final class FakeAiTextClient implements AiTextClient {

        @Override
        public String generateJson(String prompt, AiAuthConfig config) {
            return """
                    {
                      "schema_version": "workflow_requirement_v1",
                      "meta": { "language": "zh" },
                      "app": { "name": "演示应用", "group_ids": "group-a" },
                      "worksheets": { "enabled": true, "business_context": "演示上下文", "requirements": "客户和订单" },
                      "views": { "enabled": true },
                      "pages": { "enabled": true },
                      "execution": { "dry_run": false, "fail_fast": true }
                    }
                    """;
        }
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

    private static final class CapturingExecuteRequirementsCommand extends ExecuteRequirementsCommand {
        private String capturedLanguage;

        @Override
        public Integer executeWithSpec(Path repoRootPath, Path specPath, String languageArg, boolean dryRunFlag) {
            this.capturedLanguage = languageArg;
            return 0;
        }
    }
}
