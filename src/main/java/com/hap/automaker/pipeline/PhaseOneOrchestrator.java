package com.hap.automaker.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.service.AppBootstrapResult;
import com.hap.automaker.service.AppBootstrapper;
import com.hap.automaker.service.PagePipelineResult;
import com.hap.automaker.service.PagePipelineRunner;
import com.hap.automaker.service.ViewPipelineResult;
import com.hap.automaker.service.ViewPipelineRunner;
import com.hap.automaker.service.WorksheetPlannerResult;
import com.hap.automaker.service.WorksheetPlannerRunner;
import com.hap.automaker.service.WorksheetCreateResult;
import com.hap.automaker.service.WorksheetCreator;

public final class PhaseOneOrchestrator {

    private final AppBootstrapper appBootstrapper;
    private final WorksheetPlannerRunner worksheetPlannerRunner;
    private final WorksheetCreator worksheetCreator;
    private final ViewPipelineRunner viewPipelineRunner;
    private final PagePipelineRunner pagePipelineRunner;

    public PhaseOneOrchestrator() {
        this.appBootstrapper = null;
        this.worksheetPlannerRunner = null;
        this.worksheetCreator = null;
        this.viewPipelineRunner = null;
        this.pagePipelineRunner = null;
    }

    public PhaseOneOrchestrator(AppBootstrapper appBootstrapper) {
        this.appBootstrapper = appBootstrapper;
        this.worksheetPlannerRunner = null;
        this.worksheetCreator = null;
        this.viewPipelineRunner = null;
        this.pagePipelineRunner = null;
    }

    public PhaseOneOrchestrator(
            AppBootstrapper appBootstrapper,
            WorksheetPlannerRunner worksheetPlannerRunner,
            WorksheetCreator worksheetCreator) {
        this.appBootstrapper = appBootstrapper;
        this.worksheetPlannerRunner = worksheetPlannerRunner;
        this.worksheetCreator = worksheetCreator;
        this.viewPipelineRunner = null;
        this.pagePipelineRunner = null;
    }

    public PhaseOneOrchestrator(
            AppBootstrapper appBootstrapper,
            WorksheetPlannerRunner worksheetPlannerRunner,
            WorksheetCreator worksheetCreator,
            ViewPipelineRunner viewPipelineRunner) {
        this.appBootstrapper = appBootstrapper;
        this.worksheetPlannerRunner = worksheetPlannerRunner;
        this.worksheetCreator = worksheetCreator;
        this.viewPipelineRunner = viewPipelineRunner;
        this.pagePipelineRunner = null;
    }

    public PhaseOneOrchestrator(
            AppBootstrapper appBootstrapper,
            WorksheetPlannerRunner worksheetPlannerRunner,
            WorksheetCreator worksheetCreator,
            ViewPipelineRunner viewPipelineRunner,
            PagePipelineRunner pagePipelineRunner) {
        this.appBootstrapper = appBootstrapper;
        this.worksheetPlannerRunner = worksheetPlannerRunner;
        this.worksheetCreator = worksheetCreator;
        this.viewPipelineRunner = viewPipelineRunner;
        this.pagePipelineRunner = pagePipelineRunner;
    }

    public PipelineContext dryRunOnly(
            Path repoRoot,
            String appName,
            String groupIds,
            String businessContext,
            String worksheetRequirements,
            String language) {
        PipelineContext context = new PipelineContext();
        context.language = language;
        context.dryRun = true;
        context.failFast = true;
        context.steps.add(step(
                1,
                "create_app",
                "Create app and fetch auth",
                "java:openapi-create-and-authorize"));
        context.steps.add(step(
                2,
                "worksheets_plan",
                "Plan worksheets",
                "java:plan-worksheets"));
        context.steps.add(step(
                3,
                "worksheets_create",
                "Create worksheets",
                "java:create-worksheets-from-plan"));
        context.steps.add(step(
                4,
                "views",
                "Create worksheet views",
                "java:view-pipeline"));
        context.steps.add(step(
                5,
                "pages",
                "Create pages and charts",
                "java:page-pipeline"));
        return context;
    }

    public PipelineContext execute(
            Path repoRoot,
            String appName,
            String groupIds,
            String businessContext,
            String worksheetRequirements,
            String language,
            boolean failFast,
            boolean worksheetsEnabled,
            boolean viewsEnabled,
            boolean pagesEnabled) throws Exception {
        PipelineContext context = new PipelineContext();
        context.language = language;
        context.dryRun = false;
        context.failFast = failFast;

        Path phaseDir = repoRoot.resolve("data").resolve("outputs").resolve("java_phase1");
        Path appAuthDir = repoRoot.resolve("data").resolve("outputs").resolve("app_authorizations");
        Files.createDirectories(phaseDir);
        Files.createDirectories(appAuthDir);

        Path createAppResult = phaseDir.resolve("create_app_result.json");
        Path appAuth = appAuthDir.resolve("app_authorize_java_phase1.json");
        Path worksheetPlan = phaseDir.resolve("worksheet_plan.json");
        Path worksheetCreate = phaseDir.resolve("worksheet_create_result.json");
        Path viewResult = phaseDir.resolve("view_pipeline_result.json");
        Path pagePlan = phaseDir.resolve("page_plan.json");
        Path pageCreate = phaseDir.resolve("page_create_result.json");

        require(appBootstrapper, "AppBootstrapper is required for execute()");
        StepResult createAppStep = runJavaBootstrapStep(repoRoot, appName, groupIds, appAuth, createAppResult);
        context.steps.add(createAppStep);
        if (!createAppStep.ok) {
            appendSkipped(context, 2, "worksheets_plan", "Plan worksheets", "dependency_failed:create_app");
            appendSkipped(context, 3, "worksheets_create", "Create worksheets", "dependency_failed:create_app");
            appendSkipped(context, 4, "views", "Create worksheet views", "dependency_failed:create_app");
            appendSkipped(context, 5, "pages", "Create pages and charts", "dependency_failed:create_app");
            return context;
        }
        populateCreateAppContext(context, createAppResult, appAuth);

        if (worksheetsEnabled) {
            require(worksheetPlannerRunner, "WorksheetPlannerRunner is required when worksheets are enabled");
            StepResult planStep = runJavaWorksheetPlanStep(
                    repoRoot,
                    appName,
                    businessContext,
                    worksheetRequirements,
                    language,
                    worksheetPlan);
            context.steps.add(planStep);
            if (!planStep.ok) {
                if (!failFast) {
                    appendSkipped(context, 3, "worksheets_create", "Create worksheets", "dependency_failed:worksheets_plan");
                }
                return context;
            }
            context.worksheetPlanJson = worksheetPlan.toString();

            require(worksheetCreator, "WorksheetCreator is required when worksheets are enabled");
            StepResult createStep = runJavaWorksheetCreateStep(repoRoot, worksheetPlan, appAuth, worksheetCreate);
            context.steps.add(createStep);
            if (!createStep.ok) {
                if (!failFast) {
                    appendSkipped(context, 4, "views", "Create worksheet views", "dependency_failed:worksheets_create");
                }
                return context;
            }
            context.worksheetCreateResultJson = worksheetCreate.toString();
        }

        if (viewsEnabled) {
            require(viewPipelineRunner, "ViewPipelineRunner is required when views are enabled");
            StepResult viewsStep = runJavaViewsStep(repoRoot, appAuth, viewResult);
            context.steps.add(viewsStep);
            if (!viewsStep.ok) {
                if (failFast) {
                    return context;
                }
            }
            if (viewsStep.ok) {
                context.viewResultJson = viewResult.toString();
            }
        }

        if (pagesEnabled) {
            require(pagePipelineRunner, "PagePipelineRunner is required when pages are enabled");
            String pageAppId = resolvePageAppId(createAppResult, worksheetCreate);
            StepResult pagesStep = runJavaPagesStep(repoRoot, pageAppId, pagePlan, pageCreate);
            pagesStep.artifacts.put("pageAppId", pageAppId);
            context.steps.add(pagesStep);
            if (!pagesStep.ok) {
                if (failFast) {
                    return context;
                }
            }
            if (pagesStep.ok) {
                context.pageResultJson = pageCreate.toString();
            }
        }

        return context;
    }

    private StepResult runJavaBootstrapStep(
            Path repoRoot,
            String appName,
            String groupIds,
            Path appAuth,
            Path createAppResult) throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = 1;
        step.stepKey = "create_app";
        step.title = "Create app and fetch auth";
        step.command = "java:openapi-create-and-authorize";
        try {
            AppBootstrapResult result = appBootstrapper.createAndAuthorize(repoRoot, appName, groupIds);
            Files.createDirectories(createAppResult.getParent());
            Jacksons.mapper().writeValue(
                    createAppResult.toFile(),
                    java.util.Map.of(
                            "appId", result.appId(),
                            "appAuthJson", result.appAuthJsonPath().toString()));
            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.createResponse());
            step.stderr = "";
            step.artifacts.put("appId", result.appId());
            step.artifacts.put("appAuthJson", result.appAuthJsonPath().toString());
        } catch (Exception exc) {
            step.ok = false;
            step.skipped = false;
            step.stdout = "";
            step.stderr = exc.toString();
        }
        step.startedAt = startedAt;
        step.endedAt = OffsetDateTime.now();
        return step;
    }

    private StepResult runJavaWorksheetCreateStep(
            Path repoRoot,
            Path worksheetPlan,
            Path appAuth,
            Path worksheetCreate) throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = 3;
        step.stepKey = "worksheets_create";
        step.title = "Create worksheets";
        step.command = "java:create-worksheets-from-plan";
        try {
            WorksheetCreateResult result = worksheetCreator.createFromPlan(repoRoot, worksheetPlan, appAuth, worksheetCreate);
            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("worksheetCreateResultJson", result.outputJsonPath().toString());
        } catch (Exception exc) {
            step.ok = false;
            step.skipped = false;
            step.stdout = "";
            step.stderr = exc.toString();
        }
        step.startedAt = startedAt;
        step.endedAt = OffsetDateTime.now();
        return step;
    }

    private StepResult runJavaWorksheetPlanStep(
            Path repoRoot,
            String appName,
            String businessContext,
            String worksheetRequirements,
            String language,
            Path worksheetPlan) throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = 2;
        step.stepKey = "worksheets_plan";
        step.title = "Plan worksheets";
        step.command = "java:plan-worksheets";
        try {
            WorksheetPlannerResult result = worksheetPlannerRunner.plan(
                    repoRoot,
                    appName,
                    businessContext,
                    worksheetRequirements,
                    language,
                    worksheetPlan);
            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("worksheetPlanJson", result.outputJsonPath().toString());
        } catch (Exception exc) {
            step.ok = false;
            step.skipped = false;
            step.stdout = "";
            step.stderr = exc.toString();
        }
        step.startedAt = startedAt;
        step.endedAt = OffsetDateTime.now();
        return step;
    }

    private StepResult runJavaViewsStep(
            Path repoRoot,
            Path appAuth,
            Path viewResult) throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = 4;
        step.stepKey = "views";
        step.title = "Create worksheet views";
        step.command = "java:view-pipeline";
        try {
            ViewPipelineResult result = viewPipelineRunner.run(repoRoot, appAuth, viewResult);
            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("viewResultJson", result.outputJsonPath().toString());
        } catch (Exception exc) {
            step.ok = false;
            step.skipped = false;
            step.stdout = "";
            step.stderr = exc.toString();
        }
        step.startedAt = startedAt;
        step.endedAt = OffsetDateTime.now();
        return step;
    }

    private StepResult runJavaPagesStep(
            Path repoRoot,
            String pageAppId,
            Path pagePlan,
            Path pageCreate) throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = 5;
        step.stepKey = "pages";
        step.title = "Create pages and charts";
        step.command = "java:page-pipeline";
        try {
            PagePipelineResult result = pagePipelineRunner.run(repoRoot, pageAppId, pagePlan, pageCreate);
            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("pagePlanJson", result.planOutputPath().toString());
            step.artifacts.put("pageResultJson", result.outputJsonPath().toString());
        } catch (Exception exc) {
            step.ok = false;
            step.skipped = false;
            step.stdout = "";
            step.stderr = exc.toString();
        }
        step.startedAt = startedAt;
        step.endedAt = OffsetDateTime.now();
        return step;
    }

    private void populateCreateAppContext(PipelineContext context, Path createAppResult, Path appAuth) throws Exception {
        JsonNode result = Jacksons.mapper().readTree(createAppResult.toFile());
        context.appId = result.path("appId").asText("");
        context.appAuthJson = result.path("appAuthJson").asText(appAuth.toString());
    }

    private String resolvePageAppId(Path createAppResult, Path worksheetCreateResult) throws Exception {
        // 首先尝试从create_app结果中获取appId (优先，这样PagePipelineService会使用GetApp API)
        JsonNode createApp = Jacksons.mapper().readTree(createAppResult.toFile());
        String appId = createApp.path("appId").asText("");
        if (!appId.isBlank()) {
            return appId;
        }
        // 回退：从worksheet_create结果中获取第一个工作表ID
        if (Files.exists(worksheetCreateResult)) {
            JsonNode worksheetCreate = Jacksons.mapper().readTree(worksheetCreateResult.toFile());
            JsonNode mapping = worksheetCreate.path("name_to_worksheet_id");
            if (mapping.isObject()) {
                java.util.Iterator<JsonNode> values = mapping.elements();
                if (values.hasNext()) {
                    String worksheetId = values.next().asText("");
                    if (!worksheetId.isBlank()) {
                        return worksheetId;
                    }
                }
            }
        }
        return "";
    }

    private StepResult step(int id, String key, String title, String command) {
        StepResult result = new StepResult();
        result.stepId = id;
        result.stepKey = key;
        result.title = title;
        result.ok = true;
        result.skipped = false;
        result.startedAt = OffsetDateTime.now();
        result.endedAt = OffsetDateTime.now();
        result.command = command;
        result.stdout = "";
        result.stderr = "";
        return result;
    }

    private void appendSkipped(PipelineContext context, int id, String key, String title, String reason) {
        StepResult skipped = new StepResult();
        skipped.stepId = id;
        skipped.stepKey = key;
        skipped.title = title;
        skipped.ok = false;
        skipped.skipped = true;
        skipped.reason = reason;
        skipped.startedAt = OffsetDateTime.now();
        skipped.endedAt = skipped.startedAt;
        skipped.command = "";
        skipped.stdout = "";
        skipped.stderr = "";
        context.steps.add(skipped);
    }

    private static void require(Object dependency, String message) {
        if (dependency == null) {
            throw new IllegalStateException(message);
        }
    }
}
