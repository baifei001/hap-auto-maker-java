package com.hap.automaker.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.pipeline.CheckpointManager;
import com.hap.automaker.pipeline.ExecutionReportWriter;
import com.hap.automaker.pipeline.PhaseOneOrchestrator;
import com.hap.automaker.pipeline.PhaseTwoOrchestrator;
import com.hap.automaker.pipeline.PipelineContext;
import com.hap.automaker.pipeline.StepResult;
import com.hap.automaker.service.AppBootstrapService;
import com.hap.automaker.service.AppBootstrapper;
import com.hap.automaker.service.PagePipelineRunner;
import com.hap.automaker.service.PagePipelineService;
import com.hap.automaker.service.ViewPipelineRunner;
import com.hap.automaker.service.ViewPipelineService;
import com.hap.automaker.service.WorksheetPlannerRunner;
import com.hap.automaker.service.WorksheetPlannerService;
import com.hap.automaker.service.WorksheetCreateService;
import com.hap.automaker.service.WorksheetCreator;
import com.hap.automaker.service.DeleteDefaultViewsService;
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
import com.hap.automaker.service.MockDataPipelineRunner;
import com.hap.automaker.service.MockDataPipelineService;
import com.hap.automaker.service.ChartPipelineRunner;
import com.hap.automaker.service.ChartPipelineService;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "execute-requirements", mixinStandardHelpOptions = true, description = "Execute the phase 1 Windows flow")
public class ExecuteRequirementsCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ExecuteRequirementsCommand.class);

    private AppBootstrapper appBootstrapperOverride;
    private WorksheetPlannerRunner worksheetPlannerRunnerOverride;
    private WorksheetCreator worksheetCreatorOverride;
    private ViewPipelineRunner viewPipelineRunnerOverride;
    private PagePipelineRunner pagePipelineRunnerOverride;

    @Option(names = "--spec-json", required = true)
    private String specJson;

    @Option(names = "--dry-run", defaultValue = "false")
    private boolean dryRun;

    @Option(names = "--language", defaultValue = "auto")
    private String language;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    // Phase Two feature flags
    @Option(names = "--enable-sections", defaultValue = "false")
    private boolean enableSections;

    @Option(names = "--enable-roles", defaultValue = "false")
    private boolean enableRoles;

    @Option(names = "--enable-icons", defaultValue = "false")
    private boolean enableIcons;

    @Option(names = "--enable-layouts", defaultValue = "false")
    private boolean enableLayouts;

    @Option(names = "--enable-view-filters", defaultValue = "false")
    private boolean enableViewFilters;

    @Option(names = "--enable-charts", defaultValue = "false")
    private boolean enableCharts;

    @Option(names = "--enable-mock-data", defaultValue = "false")
    private boolean enableMockData;

    @Option(names = "--enable-chatbots", defaultValue = "false")
    private boolean enableChatbots;

    @Option(names = "--enable-delete-default-views", defaultValue = "false")
    private boolean enableDeleteDefaultViews;

    @Option(names = "--resume", defaultValue = "false",
            description = "Resume from last checkpoint, skipping completed waves")
    private boolean resume;

    @Option(names = "--clean", defaultValue = "false",
            description = "Clear checkpoint and start from scratch")
    private boolean clean;

    @Override
    public Integer call() throws Exception {
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(), Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));

        CheckpointManager checkpointManager = new CheckpointManager(detected.repoRoot());

        // --clean: 清除 checkpoint 从头开始
        if (clean) {
            checkpointManager.clearCheckpoint();
            logger.info("Checkpoint cleared, starting from scratch");
        }

        // --resume: 从 checkpoint 恢复
        if (resume) {
            return resumeFromCheckpoint(detected.repoRoot(), checkpointManager);
        }

        return executeWithSpec(detected.repoRoot(), Path.of(specJson).toAbsolutePath().normalize(), language, dryRun);
    }

    /**
     * 从 checkpoint 恢复执行
     */
    private Integer resumeFromCheckpoint(Path repoRootPath, CheckpointManager checkpointManager) throws Exception {
        CheckpointManager.Checkpoint checkpoint = checkpointManager.loadCheckpoint();
        if (checkpoint == null) {
            logger.error("No checkpoint found. Cannot resume. Run without --resume first.");
            return 1;
        }

        logger.info("Resuming from checkpoint (appId: {}, completed waves: {})",
                checkpoint.appId(), checkpoint.completedWaves());

        // 从 checkpoint 恢复 specPath
        String specPathStr = checkpoint.specPath();
        if (specPathStr == null || specPathStr.isEmpty()) {
            logger.error("Checkpoint has no spec_path. Cannot resume.");
            return 1;
        }

        Path specPath = Path.of(specPathStr);
        if (!Files.exists(specPath)) {
            logger.error("Spec file not found: {}", specPath);
            return 1;
        }

        // 使用 checkpoint 中的 dryRun 和 failFast 设置
        this.dryRun = checkpoint.dryRun();

        return executeWithSpec(repoRootPath, specPath, language, dryRun);
    }

    public Integer executeWithSpec(Path repoRootPath, Path specPath, String languageArg, boolean dryRunFlag)
            throws Exception {
        JsonNode spec = Jacksons.mapper().readTree(specPath.toFile());
        String effectiveLanguage = "auto".equals(languageArg)
                ? spec.path("meta").path("language").asText("zh")
                : languageArg;

        String appName = spec.path("app").path("name").asText();
        String groupIds = spec.path("app").path("group_ids").asText("");
        String businessContext = spec.path("worksheets").path("business_context").asText("");
        String worksheetRequirements = spec.path("worksheets").path("requirements").asText("");
        boolean worksheetsEnabled = spec.path("worksheets").path("enabled").asBoolean(true);
        boolean viewsEnabled = spec.path("views").path("enabled").asBoolean(true);
        boolean pagesEnabled = spec.path("pages").path("enabled").asBoolean(true);
        boolean effectiveDryRun = dryRunFlag || spec.path("execution").path("dry_run").asBoolean(false);
        boolean failFast = spec.path("execution").path("fail_fast").asBoolean(true);

        PhaseOneOrchestrator orchestrator = effectiveDryRun
                ? new PhaseOneOrchestrator()
                : new PhaseOneOrchestrator(
                        resolveAppBootstrapper(repoRootPath),
                        resolveWorksheetPlannerRunner(repoRootPath),
                        resolveWorksheetCreator(repoRootPath),
                        resolveViewPipelineRunner(repoRootPath),
                        resolvePagePipelineRunner(repoRootPath));

        PipelineContext context;
        if (effectiveDryRun) {
            context = orchestrator.dryRunOnly(
                    repoRootPath,
                    appName,
                    groupIds,
                    businessContext,
                    worksheetRequirements,
                    effectiveLanguage);
            if (!worksheetsEnabled) {
                context.steps.removeIf(step -> "worksheets_plan".equals(step.stepKey) || "worksheets_create".equals(step.stepKey));
            }
            if (!viewsEnabled) {
                context.steps.removeIf(step -> "views".equals(step.stepKey));
            }
            if (!pagesEnabled) {
                context.steps.removeIf(step -> "pages".equals(step.stepKey));
            }
        } else {
            context = orchestrator.execute(
                    repoRootPath,
                    appName,
                    groupIds,
                    businessContext,
                    worksheetRequirements,
                    effectiveLanguage,
                    failFast,
                    worksheetsEnabled,
                    viewsEnabled,
                    pagesEnabled);
        }
        context.specPath = specPath;
        context.dryRun = effectiveDryRun;
        context.failFast = failFast;

        // Phase Two execution if any wave is enabled
        boolean phaseTwoEnabled = enableSections || enableRoles || enableIcons || enableLayouts
                || enableViewFilters || enableCharts || enableMockData || enableChatbots
                || enableDeleteDefaultViews;

        boolean phaseOneSuccess = context.steps.stream()
                .noneMatch(s -> !s.ok && !s.skipped);

        if (phaseTwoEnabled && phaseOneSuccess) {
            String phaseTwoAppId = context.appId != null ? context.appId
                    : context.steps.stream().filter(s -> "create_app".equals(s.stepKey)).findFirst()
                            .map(s -> (String) s.artifacts.get("appId")).orElse("");

            // In dry-run mode, use a mock appId if no real one is available
            if (phaseTwoAppId.isEmpty() && effectiveDryRun) {
                phaseTwoAppId = "dry-run-mock-app-id";
            }

            if (!phaseTwoAppId.isEmpty()) {
                PhaseTwoOrchestrator phaseTwoOrchestrator = effectiveDryRun
                        ? new PhaseTwoOrchestrator()
                        : new PhaseTwoOrchestrator(
                                resolveDeleteDefaultViewsService(repoRootPath),
                                resolveSectionPipelineRunner(),
                                resolveRolePipelineRunner(),
                                resolveChatbotPipelineRunner(),
                                resolveViewFilterPipelineRunner(),
                                resolveIconPipelineRunner(),
                                resolveLayoutPipelineRunner(),
                                resolveMockDataPipelineRunner(),
                                resolveChartPipelineRunner());

                if (effectiveDryRun) {
                    PipelineContext phaseTwoContext = phaseTwoOrchestrator.dryRunOnly(
                            repoRootPath, phaseTwoAppId, enableSections, enableRoles, enableIcons,
                            enableLayouts, enableViewFilters, enableCharts, enableMockData,
                            enableChatbots, enableDeleteDefaultViews);
                    context.steps.addAll(phaseTwoContext.steps);
                } else {
                    PipelineContext phaseTwoContext = phaseTwoOrchestrator.execute(
                            repoRootPath, phaseTwoAppId, enableSections, enableRoles, enableIcons,
                            enableLayouts, enableViewFilters, enableCharts, enableMockData,
                            enableChatbots, enableDeleteDefaultViews, failFast, effectiveLanguage);
                    context.steps.addAll(phaseTwoContext.steps);
                }
            }
        }

        Path report = new ExecutionReportWriter().write(repoRootPath, context);
        logger.info("Execution report: {}", report);

        // 保存 checkpoint（无论成功失败，都保存进度以便断点续传）
        saveCheckpoint(repoRootPath, context);

        boolean failed = context.steps.stream().anyMatch(step -> !step.ok && !step.skipped);
        return failed ? 1 : 0;
    }

    /**
     * 保存 checkpoint，记录已完成的 wave
     */
    private void saveCheckpoint(Path repoRootPath, PipelineContext context) {
        try {
            CheckpointManager checkpointManager = new CheckpointManager(repoRootPath);
            java.util.List<String> completedWaves = new ArrayList<>();
            for (StepResult step : context.steps) {
                if (step.ok && !step.skipped) {
                    completedWaves.add(step.stepKey);
                }
            }
            checkpointManager.saveCheckpoint(context.appId, completedWaves, context);
            logger.info("Checkpoint saved ({} waves completed)", completedWaves.size());
        } catch (Exception e) {
            logger.warn("Failed to save checkpoint: {}", e.getMessage());
        }
    }

    void setAppBootstrapperOverride(AppBootstrapper appBootstrapperOverride) {
        this.appBootstrapperOverride = appBootstrapperOverride;
    }

    void setWorksheetPlannerRunnerOverride(WorksheetPlannerRunner worksheetPlannerRunnerOverride) {
        this.worksheetPlannerRunnerOverride = worksheetPlannerRunnerOverride;
    }

    void setWorksheetCreatorOverride(WorksheetCreator worksheetCreatorOverride) {
        this.worksheetCreatorOverride = worksheetCreatorOverride;
    }

    void setViewPipelineRunnerOverride(ViewPipelineRunner viewPipelineRunnerOverride) {
        this.viewPipelineRunnerOverride = viewPipelineRunnerOverride;
    }

    void setPagePipelineRunnerOverride(PagePipelineRunner pagePipelineRunnerOverride) {
        this.pagePipelineRunnerOverride = pagePipelineRunnerOverride;
    }

    // Phase Two feature flag setters for testing
    void setEnableSections(boolean enableSections) {
        this.enableSections = enableSections;
    }

    void setEnableRoles(boolean enableRoles) {
        this.enableRoles = enableRoles;
    }

    void setEnableChatbots(boolean enableChatbots) {
        this.enableChatbots = enableChatbots;
    }

    void setEnableDeleteDefaultViews(boolean enableDeleteDefaultViews) {
        this.enableDeleteDefaultViews = enableDeleteDefaultViews;
    }

    void setEnableIcons(boolean enableIcons) {
        this.enableIcons = enableIcons;
    }

    void setEnableViewFilters(boolean enableViewFilters) {
        this.enableViewFilters = enableViewFilters;
    }

    private AppBootstrapper resolveAppBootstrapper(Path repoRootPath) {
        if (appBootstrapperOverride != null) {
            return appBootstrapperOverride;
        }
        return new AppBootstrapService();
    }

    private WorksheetPlannerRunner resolveWorksheetPlannerRunner(Path repoRootPath) {
        if (worksheetPlannerRunnerOverride != null) {
            return worksheetPlannerRunnerOverride;
        }
        return new WorksheetPlannerService();
    }

    private WorksheetCreator resolveWorksheetCreator(Path repoRootPath) {
        if (worksheetCreatorOverride != null) {
            return worksheetCreatorOverride;
        }
        return new WorksheetCreateService();
    }

    private ViewPipelineRunner resolveViewPipelineRunner(Path repoRootPath) throws Exception {
        if (viewPipelineRunnerOverride != null) {
            return viewPipelineRunnerOverride;
        }
        return new ViewPipelineService();
    }

    private PagePipelineRunner resolvePagePipelineRunner(Path repoRootPath) throws Exception {
        if (pagePipelineRunnerOverride != null) {
            return pagePipelineRunnerOverride;
        }
        return new PagePipelineService();
    }

    private DeleteDefaultViewsService resolveDeleteDefaultViewsService(Path repoRootPath) {
        return new DeleteDefaultViewsService();
    }

    private SectionPipelineRunner resolveSectionPipelineRunner() {
        return new SectionPipelineService();
    }

    private RolePipelineRunner resolveRolePipelineRunner() {
        return new RolePipelineService();
    }

    private ChatbotPipelineRunner resolveChatbotPipelineRunner() {
        return new ChatbotPipelineService();
    }

    private ViewFilterPipelineRunner resolveViewFilterPipelineRunner() {
        return new ViewFilterPipelineService();
    }

    private IconPipelineRunner resolveIconPipelineRunner() {
        return new IconPipelineService();
    }

    private LayoutPipelineRunner resolveLayoutPipelineRunner() {
        return new LayoutPipelineService();
    }

    private MockDataPipelineRunner resolveMockDataPipelineRunner() {
        return new MockDataPipelineService();
    }

    private ChartPipelineRunner resolveChartPipelineRunner() {
        return new ChartPipelineService();
    }
}
