package com.hap.automaker.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.service.DeleteDefaultViewsService;
import com.hap.automaker.service.MockDataPipelineRunner;
import com.hap.automaker.service.MockDataPipelineService;
import com.hap.automaker.service.SectionPipelineRunner;
import com.hap.automaker.service.SectionPipelineService;
import com.hap.automaker.service.RolePipelineRunner;
import com.hap.automaker.service.RolePipelineService;
import com.hap.automaker.service.ChatbotPipelineRunner;
import com.hap.automaker.service.ChatbotPipelineService;
import com.hap.automaker.service.ViewFilterPipelineRunner;
import com.hap.automaker.service.ViewFilterPipelineService;
import com.hap.automaker.service.IconPipelineRunner;
import com.hap.automaker.service.IconPipelineService;
import com.hap.automaker.service.LayoutPipelineRunner;
import com.hap.automaker.service.LayoutPipelineService;
import com.hap.automaker.service.ChartPipelineRunner;
import com.hap.automaker.service.ChartPipelineResult;
import com.hap.automaker.service.ChartPipelineService;

/**
 * Phase Two Orchestrator - 处理 Wave 2.5-8
 *
 * Wave 2.5: 分组 + 导航样式
 * Wave 3: 角色 + 图标
 * Wave 4: 布局 + 视图筛选
 * Wave 5: 图表（工作表级别）
 * Wave 7: 模拟数据 + 聊天机器人
 * Wave 8: 默认视图清理
 */
public final class PhaseTwoOrchestrator {

    private final DeleteDefaultViewsService deleteDefaultViewsService;
    private final SectionPipelineRunner sectionPipelineRunner;
    private final RolePipelineRunner rolePipelineRunner;
    private final ChatbotPipelineRunner chatbotPipelineRunner;
    private final ViewFilterPipelineRunner viewFilterPipelineRunner;
    private final IconPipelineRunner iconPipelineRunner;
    private final LayoutPipelineRunner layoutPipelineRunner;
    private final MockDataPipelineRunner mockDataPipelineRunner;
    private final ChartPipelineRunner chartPipelineRunner;

    public PhaseTwoOrchestrator() {
        this.deleteDefaultViewsService = null;
        this.sectionPipelineRunner = null;
        this.rolePipelineRunner = null;
        this.chatbotPipelineRunner = null;
        this.viewFilterPipelineRunner = null;
        this.iconPipelineRunner = null;
        this.layoutPipelineRunner = null;
        this.mockDataPipelineRunner = null;
        this.chartPipelineRunner = null;
    }

    public PhaseTwoOrchestrator(DeleteDefaultViewsService deleteDefaultViewsService) {
        this.deleteDefaultViewsService = deleteDefaultViewsService;
        this.sectionPipelineRunner = null;
        this.rolePipelineRunner = null;
        this.chatbotPipelineRunner = null;
        this.viewFilterPipelineRunner = null;
        this.iconPipelineRunner = null;
        this.layoutPipelineRunner = null;
        this.mockDataPipelineRunner = null;
        this.chartPipelineRunner = null;
    }

    public PhaseTwoOrchestrator(
            DeleteDefaultViewsService deleteDefaultViewsService,
            SectionPipelineRunner sectionPipelineRunner,
            RolePipelineRunner rolePipelineRunner,
            ChatbotPipelineRunner chatbotPipelineRunner,
            ViewFilterPipelineRunner viewFilterPipelineRunner,
            IconPipelineRunner iconPipelineRunner,
            LayoutPipelineRunner layoutPipelineRunner,
            MockDataPipelineRunner mockDataPipelineRunner,
            ChartPipelineRunner chartPipelineRunner) {
        this.deleteDefaultViewsService = deleteDefaultViewsService;
        this.sectionPipelineRunner = sectionPipelineRunner;
        this.rolePipelineRunner = rolePipelineRunner;
        this.chatbotPipelineRunner = chatbotPipelineRunner;
        this.viewFilterPipelineRunner = viewFilterPipelineRunner;
        this.iconPipelineRunner = iconPipelineRunner;
        this.layoutPipelineRunner = layoutPipelineRunner;
        this.mockDataPipelineRunner = mockDataPipelineRunner;
        this.chartPipelineRunner = chartPipelineRunner;
    }

    /**
     * Dry-run mode - 只生成步骤计划，不实际执行
     */
    public PipelineContext dryRunOnly(
            Path repoRoot,
            String appId,
            boolean sectionsEnabled,
            boolean rolesEnabled,
            boolean iconsEnabled,
            boolean layoutsEnabled,
            boolean viewFiltersEnabled,
            boolean chartsEnabled,
            boolean mockDataEnabled,
            boolean chatbotsEnabled,
            boolean deleteDefaultViewsEnabled) {

        PipelineContext context = new PipelineContext();
        context.appId = appId;
        context.dryRun = true;
        context.failFast = true;

        int stepId = 10; // Start after Phase One (which ends at step 5)

        // Wave 2.5: Sections
        if (sectionsEnabled) {
            context.steps.add(step(stepId++, "sections_plan", "Plan app sections", "java:plan-sections"));
            context.steps.add(step(stepId++, "sections_create", "Create sections and move worksheets", "java:create-sections"));
            context.steps.add(step(stepId++, "navi_style", "Update navigation style", "java:update-navi-style"));
        }

        // Wave 3: Roles and Icons
        if (rolesEnabled) {
            context.steps.add(step(stepId++, "roles_plan", "Plan app roles", "java:plan-roles"));
            context.steps.add(step(stepId++, "roles_create", "Create roles", "java:create-roles"));
        }
        if (iconsEnabled) {
            context.steps.add(step(stepId++, "icons_plan", "Plan worksheet icons", "java:plan-icons"));
            context.steps.add(step(stepId++, "icons_update", "Update worksheet icons", "java:update-icons"));
        }

        // Wave 4: Layouts and View Filters
        if (layoutsEnabled) {
            context.steps.add(step(stepId++, "layouts_plan", "Plan worksheet layouts", "java:plan-layouts"));
            context.steps.add(step(stepId++, "layouts_apply", "Apply worksheet layouts", "java:apply-layouts"));
        }
        if (viewFiltersEnabled) {
            context.steps.add(step(stepId++, "view_filters", "Create view filters", "java:create-view-filters"));
        }

        // Wave 5: Worksheet Charts
        if (chartsEnabled) {
            context.steps.add(step(stepId++, "charts_plan", "Plan worksheet charts", "java:plan-charts"));
            context.steps.add(step(stepId++, "charts_create", "Create worksheet charts", "java:create-charts"));
        }

        // Wave 7: Mock Data and Chatbots
        if (mockDataEnabled) {
            context.steps.add(step(stepId++, "mock_data_plan", "Plan mock data", "java:plan-mock-data"));
            context.steps.add(step(stepId++, "mock_data_create", "Create mock data records", "java:create-mock-data"));
            context.steps.add(step(stepId++, "mock_relations_plan", "Plan mock relations", "java:plan-mock-relations"));
            context.steps.add(step(stepId++, "mock_relations_apply", "Apply mock relations", "java:apply-mock-relations"));
        }
        if (chatbotsEnabled) {
            context.steps.add(step(stepId++, "chatbots_plan", "Plan chatbots", "java:plan-chatbots"));
            context.steps.add(step(stepId++, "chatbots_create", "Create chatbots", "java:create-chatbots"));
        }

        // Wave 8: Cleanup
        if (deleteDefaultViewsEnabled) {
            context.steps.add(step(stepId++, "delete_default_views", "Delete default views", "java:delete-default-views"));
        }

        return context;
    }

    /**
     * Execute Phase Two with all enabled waves
     */
    public PipelineContext execute(
            Path repoRoot,
            String appId,
            boolean sectionsEnabled,
            boolean rolesEnabled,
            boolean iconsEnabled,
            boolean layoutsEnabled,
            boolean viewFiltersEnabled,
            boolean chartsEnabled,
            boolean mockDataEnabled,
            boolean chatbotsEnabled,
            boolean deleteDefaultViewsEnabled,
            boolean failFast,
            String language) throws Exception {

        PipelineContext context = new PipelineContext();
        context.appId = appId;
        context.dryRun = false;
        context.failFast = failFast;

        Path phaseDir = repoRoot.resolve("data").resolve("outputs").resolve("java_phase2");
        Path phase1Dir = repoRoot.resolve("data").resolve("outputs").resolve("java_phase1");
        Files.createDirectories(phaseDir);

        int stepId = 10;
        boolean previousStepOk = true;

        // Wave 2.5: Sections
        Path worksheetCreateResult = phase1Dir.resolve("worksheet_create_result.json");
        if (sectionsEnabled && previousStepOk) {
            StepResult sectionStep = runSectionsStep(repoRoot, appId, worksheetCreateResult, phaseDir, stepId++, failFast);
            context.steps.add(sectionStep);
            previousStepOk = sectionStep.ok;
        }

        // Wave 3: Roles and Icons
        if (rolesEnabled && (previousStepOk || !failFast)) {
            StepResult roleStep = runRolesStep(repoRoot, appId, worksheetCreateResult, phaseDir, stepId++, failFast);
            context.steps.add(roleStep);
            previousStepOk = roleStep.ok;
        }

        if (iconsEnabled && (previousStepOk || !failFast)) {
            StepResult iconStep = runIconsStep(repoRoot, appId, worksheetCreateResult, phaseDir, stepId++, failFast);
            context.steps.add(iconStep);
            previousStepOk = iconStep.ok;
        }

        // Wave 4: Layouts and View Filters
        if (layoutsEnabled && (previousStepOk || !failFast)) {
            StepResult planStep = createSkippedStep(stepId++, "layouts_plan", "Plan worksheet layouts", "not_yet_implemented");
            context.steps.add(planStep);
            previousStepOk = planStep.ok;

            if (previousStepOk || !failFast) {
                StepResult applyStep = createSkippedStep(stepId++, "layouts_apply", "Apply worksheet layouts", "not_yet_implemented");
                context.steps.add(applyStep);
                previousStepOk = applyStep.ok;
            }
        }

        if (viewFiltersEnabled && (previousStepOk || !failFast)) {
            // View filters require view creation result
            Path viewResult = phaseDir.resolve("view_pipeline_result.json");
            StepResult filterStep = runViewFiltersStep(repoRoot, appId, viewResult, phaseDir, stepId++, failFast, context.dryRun);
            context.steps.add(filterStep);
            previousStepOk = filterStep.ok;
        }

        // Wave 5: Charts
        if (chartsEnabled && (previousStepOk || !failFast)) {
            StepResult chartStep = runChartsStep(repoRoot, appId, worksheetCreateResult, phaseDir, stepId++, failFast, context.dryRun);
            context.steps.add(chartStep);
            previousStepOk = chartStep.ok;
        }

        // Wave 7: Mock Data and Chatbots
        if (mockDataEnabled && (previousStepOk || !failFast)) {
            StepResult mockDataStep = runMockDataStep(repoRoot, appId, worksheetCreateResult, phaseDir, stepId++, failFast, context.dryRun);
            context.steps.add(mockDataStep);
            previousStepOk = mockDataStep.ok;
        }

        if (chatbotsEnabled && (previousStepOk || !failFast)) {
            StepResult chatbotStep = runChatbotsStep(repoRoot, appId, worksheetCreateResult, phaseDir, stepId++, failFast, context.dryRun);
            context.steps.add(chatbotStep);
            previousStepOk = chatbotStep.ok;
        }

        // Wave 8: Delete Default Views
        if (deleteDefaultViewsEnabled && (previousStepOk || !failFast)) {
            StepResult deleteStep = runDeleteDefaultViewsStep(repoRoot, appId, stepId++);
            context.steps.add(deleteStep);
            previousStepOk = deleteStep.ok;
        }

        return context;
    }

    private StepResult runDeleteDefaultViewsStep(Path repoRoot, String appId, int stepId) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = stepId;
        step.stepKey = "delete_default_views";
        step.title = "Delete default views";
        step.command = "java:delete-default-views";

        try {
            require(deleteDefaultViewsService, "DeleteDefaultViewsService is required");
            JsonNode result = deleteDefaultViewsService.deleteDefaultViews(repoRoot, appId, false, false);
            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result);
            step.stderr = "";
            step.artifacts.put("deletedCount", String.valueOf(result.path("deletedCount").asInt(0)));
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

    private StepResult runSectionsStep(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path phaseDir,
            int stepId,
            boolean failFast) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = stepId;
        step.stepKey = "sections_pipeline";
        step.title = "Plan, create sections and update navigation style";
        step.command = "java:section-pipeline";

        try {
            require(sectionPipelineRunner, "SectionPipelineRunner is required");
            require(Files.exists(worksheetCreateResult), "Worksheet create result not found: " + worksheetCreateResult);

            Path appAuth = repoRoot.resolve("data").resolve("outputs")
                .resolve("app_authorizations").resolve("app_authorize_java_phase1.json");
            Path sectionPlanOutput = phaseDir.resolve("section_plan.json");
            Path sectionResultOutput = phaseDir.resolve("section_result.json");

            SectionPipelineService.SectionPipelineResult result = sectionPipelineRunner.run(
                repoRoot, appId, appAuth, worksheetCreateResult, sectionPlanOutput, sectionResultOutput);

            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("sectionPlanJson", result.planOutputPath().toString());
            step.artifacts.put("sectionResultJson", result.resultOutputPath().toString());
            step.artifacts.put("totalSections", String.valueOf(result.totalSections()));
            step.artifacts.put("createdSections", String.valueOf(result.createdSections()));
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

    private StepResult runRolesStep(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path phaseDir,
            int stepId,
            boolean failFast) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = stepId;
        step.stepKey = "roles_pipeline";
        step.title = "Plan and create roles";
        step.command = "java:role-pipeline";

        try {
            require(rolePipelineRunner, "RolePipelineRunner is required");
            require(Files.exists(worksheetCreateResult), "Worksheet create result not found: " + worksheetCreateResult);

            Path appAuth = repoRoot.resolve("data").resolve("outputs")
                .resolve("app_authorizations").resolve("app_authorize_java_phase1.json");
            Path rolePlanOutput = phaseDir.resolve("role_plan.json");
            Path roleResultOutput = phaseDir.resolve("role_result.json");

            RolePipelineService.RolePipelineResult result = rolePipelineRunner.run(
                repoRoot, appId, appAuth, worksheetCreateResult, rolePlanOutput, roleResultOutput);

            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("rolePlanJson", result.planOutputPath().toString());
            step.artifacts.put("roleResultJson", result.resultOutputPath().toString());
            step.artifacts.put("totalRoles", String.valueOf(result.totalRoles()));
            step.artifacts.put("createdRoles", String.valueOf(result.createdRoles()));
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

    private StepResult runChatbotsStep(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path phaseDir,
            int stepId,
            boolean failFast,
            boolean dryRun) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = stepId;
        step.stepKey = "chatbots_pipeline";
        step.title = "Plan and create chatbots";
        step.command = "java:chatbot-pipeline";

        try {
            require(chatbotPipelineRunner, "ChatbotPipelineRunner is required");
            require(Files.exists(worksheetCreateResult), "Worksheet create result not found: " + worksheetCreateResult);

            Path appAuth = repoRoot.resolve("data").resolve("outputs")
                .resolve("app_authorizations").resolve("app_authorize_java_phase1.json");
            Path chatbotPlanOutput = phaseDir.resolve("chatbot_plan.json");
            Path chatbotResultOutput = phaseDir.resolve("chatbot_result.json");

            ChatbotPipelineService.ChatbotPipelineResult result = chatbotPipelineRunner.run(
                repoRoot, appId, appAuth, worksheetCreateResult, chatbotPlanOutput, chatbotResultOutput, dryRun);

            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("chatbotPlanJson", result.planOutputPath().toString());
            step.artifacts.put("chatbotResultJson", result.resultOutputPath().toString());
            step.artifacts.put("totalProposals", String.valueOf(result.totalProposals()));
            step.artifacts.put("createdCount", String.valueOf(result.createdCount()));
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

    private StepResult runIconsStep(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path phaseDir,
            int stepId,
            boolean failFast) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = stepId;
        step.stepKey = "icons_pipeline";
        step.title = "Plan and update worksheet icons";
        step.command = "java:icon-pipeline";

        try {
            require(iconPipelineRunner, "IconPipelineRunner is required");
            require(Files.exists(worksheetCreateResult), "Worksheet create result not found: " + worksheetCreateResult);

            Path iconPlanOutput = phaseDir.resolve("icon_plan.json");
            Path iconResultOutput = phaseDir.resolve("icon_result.json");

            IconPipelineService.IconPipelineResult result = iconPipelineRunner.run(
                repoRoot, appId, worksheetCreateResult, iconPlanOutput, iconResultOutput);

            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("iconPlanJson", result.planOutputPath().toString());
            step.artifacts.put("iconResultJson", result.resultOutputPath().toString());
            step.artifacts.put("totalMappings", String.valueOf(result.totalMappings()));
            step.artifacts.put("successCount", String.valueOf(result.successCount()));
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

    private StepResult runViewFiltersStep(
            Path repoRoot,
            String appId,
            Path viewResult,
            Path phaseDir,
            int stepId,
            boolean failFast,
            boolean dryRun) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = stepId;
        step.stepKey = "view_filters_pipeline";
        step.title = "Plan and create view filters";
        step.command = "java:view-filter-pipeline";

        try {
            require(viewFilterPipelineRunner, "ViewFilterPipelineRunner is required");

            Path viewFilterPlanOutput = phaseDir.resolve("view_filter_plan.json");
            Path viewFilterResultOutput = phaseDir.resolve("view_filter_result.json");

            ViewFilterPipelineService.ViewFilterPipelineResult result = viewFilterPipelineRunner.run(
                repoRoot, appId, viewResult, viewFilterPlanOutput, viewFilterResultOutput, dryRun);

            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("viewFilterPlanJson", result.planOutputPath().toString());
            step.artifacts.put("viewFilterResultJson", result.resultOutputPath().toString());
            step.artifacts.put("totalViews", String.valueOf(result.totalViews()));
            step.artifacts.put("totalFilters", String.valueOf(result.totalFilters()));
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

    private StepResult runMockDataStep(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path phaseDir,
            int stepId,
            boolean failFast,
            boolean dryRun) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = stepId;
        step.stepKey = "mock_data_pipeline";
        step.title = "Plan and create mock data records";
        step.command = "java:mock-data-pipeline";

        try {
            require(mockDataPipelineRunner, "MockDataPipelineRunner is required");
            require(Files.exists(worksheetCreateResult), "Worksheet create result not found: " + worksheetCreateResult);

            Path mockDataPlanOutput = phaseDir.resolve("mock_data_plan.json");
            Path mockDataResultOutput = phaseDir.resolve("mock_data_result.json");

            MockDataPipelineService.MockDataPipelineResult result = mockDataPipelineRunner.run(
                repoRoot, appId, worksheetCreateResult, mockDataPlanOutput, mockDataResultOutput, dryRun);

            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("mockDataPlanJson", result.planOutputPath().toString());
            step.artifacts.put("mockDataResultJson", result.resultOutputPath().toString());
            step.artifacts.put("totalWorksheets", String.valueOf(result.totalWorksheets()));
            step.artifacts.put("totalRecords", String.valueOf(result.totalRecords()));
            step.artifacts.put("createdRecords", String.valueOf(result.createdRecords()));
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

    private StepResult runChartsStep(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path phaseDir,
            int stepId,
            boolean failFast,
            boolean dryRun) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        StepResult step = new StepResult();
        step.stepId = stepId;
        step.stepKey = "charts_pipeline";
        step.title = "Plan and create worksheet charts";
        step.command = "java:chart-pipeline";

        try {
            require(chartPipelineRunner, "ChartPipelineRunner is required");
            require(Files.exists(worksheetCreateResult), "Worksheet create result not found: " + worksheetCreateResult);

            Path chartPlanOutput = phaseDir.resolve("chart_plan.json");
            Path chartResultOutput = phaseDir.resolve("chart_result.json");

            // Load worksheet IDs from creation result
            JsonNode createResult = Jacksons.mapper().readTree(worksheetCreateResult.toFile());
            List<String> worksheetIds = new ArrayList<>();
            JsonNode createdWorksheets = createResult.path("created_worksheets");
            if (createdWorksheets.isArray()) {
                for (JsonNode ws : createdWorksheets) {
                    String wsId = ws.path("worksheetId").asText();
                    if (!wsId.isEmpty()) {
                        worksheetIds.add(wsId);
                    }
                }
            }

            if (worksheetIds.isEmpty()) {
                throw new IllegalStateException("No worksheets found in creation result");
            }

            // Use empty pageId for now (charts on worksheets don't need page)
            String pageId = "";

            ChartPipelineResult result = chartPipelineRunner.run(
                repoRoot, appId, "", worksheetIds, pageId, chartPlanOutput, chartResultOutput);

            step.ok = true;
            step.skipped = false;
            step.stdout = Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary());
            step.stderr = "";
            step.artifacts.put("chartPlanJson", result.planOutputPath().toString());
            step.artifacts.put("chartResultJson", result.resultOutputPath().toString());
            step.artifacts.put("totalCharts", String.valueOf(result.totalCharts()));
            step.artifacts.put("createdCharts", String.valueOf(result.createdCharts()));
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

    private StepResult createSkippedStep(int id, String key, String title, String reason) {
        StepResult skipped = new StepResult();
        skipped.stepId = id;
        skipped.stepKey = key;
        skipped.title = title;
        skipped.ok = true; // Skipped steps are not failures
        skipped.skipped = true;
        skipped.reason = reason;
        skipped.startedAt = OffsetDateTime.now();
        skipped.endedAt = skipped.startedAt;
        skipped.command = "";
        skipped.stdout = "";
        skipped.stderr = "";
        return skipped;
    }

    private static void require(Object dependency, String message) {
        if (dependency == null) {
            throw new IllegalStateException(message);
        }
    }
}
