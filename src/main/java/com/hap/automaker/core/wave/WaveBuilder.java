package com.hap.automaker.core.wave;

import com.hap.automaker.core.executor.*;
import com.hap.automaker.core.planner.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Wave 构建器 - 构建完整的 7-Wave 流水线
 */
public class WaveBuilder {

    private final List<Wave> waves = new ArrayList<>();

    // Executors
    private Function<Void, AppCreator> appCreatorSupplier;
    private Function<Void, SectionCreator> sectionCreatorSupplier;
    private Function<Void, WorksheetCreator> worksheetCreatorSupplier;
    private Function<Void, NaviStyleUpdater> naviStyleUpdaterSupplier;
    private Function<Void, ViewCreator> viewCreatorSupplier;
    private Function<Void, ChartCreator> chartCreatorSupplier;
    private Function<Void, PageChartCreator> pageChartCreatorSupplier;
    private Function<Void, MockDataCreator> mockDataCreatorSupplier;
    private Function<Void, ChatbotCreator> chatbotCreatorSupplier;
    private Function<Void, ViewFilterCreator> viewFilterCreatorSupplier;
    private Function<Void, IconPlanner> iconPlannerSupplier;

    // Planners (for planning steps)
    private Function<Void, WorksheetPlanner> worksheetPlannerSupplier;
    private Function<Void, SectionPlanner> sectionPlannerSupplier;
    private Function<Void, RolePlanner> rolePlannerSupplier;
    private Function<Void, ChartPlanner> chartPlannerSupplier;
    private Function<Void, PagePlanner> pagePlannerSupplier;
    private Function<Void, MockDataPlanner> mockDataPlannerSupplier;
    private Function<Void, ChatbotPlanner> chatbotPlannerSupplier;

    public static WaveBuilder create() {
        return new WaveBuilder();
    }

    // ===== Supplier Setters =====

    public WaveBuilder withAppCreator(Function<Void, AppCreator> supplier) {
        this.appCreatorSupplier = supplier;
        return this;
    }

    public WaveBuilder withSectionCreator(Function<Void, SectionCreator> supplier) {
        this.sectionCreatorSupplier = supplier;
        return this;
    }

    public WaveBuilder withWorksheetCreator(Function<Void, WorksheetCreator> supplier) {
        this.worksheetCreatorSupplier = supplier;
        return this;
    }

    public WaveBuilder withNaviStyleUpdater(Function<Void, NaviStyleUpdater> supplier) {
        this.naviStyleUpdaterSupplier = supplier;
        return this;
    }

    public WaveBuilder withViewCreator(Function<Void, ViewCreator> supplier) {
        this.viewCreatorSupplier = supplier;
        return this;
    }

    public WaveBuilder withChartCreator(Function<Void, ChartCreator> supplier) {
        this.chartCreatorSupplier = supplier;
        return this;
    }

    public WaveBuilder withPageChartCreator(Function<Void, PageChartCreator> supplier) {
        this.pageChartCreatorSupplier = supplier;
        return this;
    }

    public WaveBuilder withMockDataCreator(Function<Void, MockDataCreator> supplier) {
        this.mockDataCreatorSupplier = supplier;
        return this;
    }

    public WaveBuilder withChatbotCreator(Function<Void, ChatbotCreator> supplier) {
        this.chatbotCreatorSupplier = supplier;
        return this;
    }

    public WaveBuilder withViewFilterCreator(Function<Void, ViewFilterCreator> supplier) {
        this.viewFilterCreatorSupplier = supplier;
        return this;
    }

    public WaveBuilder withIconPlanner(Function<Void, IconPlanner> supplier) {
        this.iconPlannerSupplier = supplier;
        return this;
    }

    public WaveBuilder withWorksheetPlanner(Function<Void, WorksheetPlanner> supplier) {
        this.worksheetPlannerSupplier = supplier;
        return this;
    }

    public WaveBuilder withSectionPlanner(Function<Void, SectionPlanner> supplier) {
        this.sectionPlannerSupplier = supplier;
        return this;
    }

    public WaveBuilder withRolePlanner(Function<Void, RolePlanner> supplier) {
        this.rolePlannerSupplier = supplier;
        return this;
    }

    public WaveBuilder withChartPlanner(Function<Void, ChartPlanner> supplier) {
        this.chartPlannerSupplier = supplier;
        return this;
    }

    public WaveBuilder withPagePlanner(Function<Void, PagePlanner> supplier) {
        this.pagePlannerSupplier = supplier;
        return this;
    }

    public WaveBuilder withMockDataPlanner(Function<Void, MockDataPlanner> supplier) {
        this.mockDataPlannerSupplier = supplier;
        return this;
    }

    public WaveBuilder withChatbotPlanner(Function<Void, ChatbotPlanner> supplier) {
        this.chatbotPlannerSupplier = supplier;
        return this;
    }

    // ===== Wave Building Methods =====

    /**
     * Wave 1: 创建应用
     */
    public WaveBuilder wave1CreateApp() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            1, "create_app", "创建应用+授权",
            () -> {
                if (appCreatorSupplier == null) {
                    throw new IllegalStateException("AppCreator not configured");
                }
                // 实际执行逻辑在 WaveExecutor 中实现
                return StepExecutable.ExecutableResult.success("App created");
            },
            false
        ));
        waves.add(new Wave(1, "应用创建", "创建新应用或复用现有应用", steps, false, 1));
        return this;
    }

    /**
     * Wave 2: 工作表规划 + 角色规划（并行）
     */
    public WaveBuilder wave2Planning() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            2, "worksheets_plan", "规划工作表",
            () -> StepExecutable.ExecutableResult.success("Worksheet plan generated"),
            true  // uses Gemini
        ));
        steps.add(new StepDefinition(
            3, "roles_plan", "规划应用角色",
            () -> StepExecutable.ExecutableResult.success("Role plan generated"),
            true  // uses Gemini
        ));
        waves.add(new Wave(2, "规划阶段", "工作表规划与角色规划（并行）", steps, true, 2));
        return this;
    }

    /**
     * Wave 2.5: 分组规划 + 导航风格
     */
    public WaveBuilder wave2_5Sections() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            4, "sections_plan", "规划工作表分组",
            () -> StepExecutable.ExecutableResult.success("Sections plan generated"),
            true  // uses Gemini
        ));
        steps.add(new StepDefinition(
            5, "navi_style", "设置应用导航风格",
            () -> StepExecutable.ExecutableResult.success("Navigation style updated"),
            false
        ));
        waves.add(new Wave(3, "分组与导航", "工作表分组规划与导航风格设置", steps, false, 1));
        return this;
    }

    /**
     * Wave 3: 创建工作表
     */
    public WaveBuilder wave3CreateWorksheets() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            6, "sections_create", "创建工作表分组",
            () -> StepExecutable.ExecutableResult.success("Sections created"),
            false
        ));
        steps.add(new StepDefinition(
            7, "worksheets_create", "创建工作表",
            () -> StepExecutable.ExecutableResult.success("Worksheets created"),
            false
        ));
        waves.add(new Wave(4, "工作表创建", "创建工作表分组与工作表", steps, false, 1));
        return this;
    }

    /**
     * Wave 3.5: 逐表创建视图（并行）
     */
    public WaveBuilder wave3_5Views() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            8, "views_create", "逐表创建视图",
            () -> StepExecutable.ExecutableResult.success("Views created per worksheet"),
            true  // uses Gemini for view recommendation
        ));
        waves.add(new Wave(5, "视图创建", "为每个工作表创建视图（可并行）", steps, true, 5));
        return this;
    }

    /**
     * Wave 3.5b: 逐表造数 + 关联字段填写
     */
    public WaveBuilder wave3_5bMockData() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            9, "mock_data", "生成模拟数据",
            () -> StepExecutable.ExecutableResult.success("Mock data generated"),
            true  // uses Gemini for data generation
        ));
        steps.add(new StepDefinition(
            10, "relation_fields", "填写关联字段",
            () -> StepExecutable.ExecutableResult.success("Relation fields filled"),
            false
        ));
        waves.add(new Wave(6, "数据填充", "生成模拟数据并填写关联字段", steps, true, 5));
        return this;
    }

    /**
     * Wave 4: 图表配置 + 视图筛选器
     */
    public WaveBuilder wave4Charts() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            11, "charts_plan", "规划图表",
            () -> StepExecutable.ExecutableResult.success("Charts planned"),
            true  // uses Gemini
        ));
        steps.add(new StepDefinition(
            12, "charts_create", "创建图表",
            () -> StepExecutable.ExecutableResult.success("Charts created"),
            false
        ));
        steps.add(new StepDefinition(
            13, "view_filters", "创建视图筛选器",
            () -> StepExecutable.ExecutableResult.success("View filters created"),
            true  // uses Gemini
        ));
        waves.add(new Wave(7, "图表与筛选", "创建图表与视图筛选器", steps, false, 1));
        return this;
    }

    /**
     * Wave 5: 图标匹配 + 工作表图标更新
     */
    public WaveBuilder wave5Icons() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            14, "icon_match", "匹配工作表图标",
            () -> StepExecutable.ExecutableResult.success("Icons matched"),
            true  // uses Gemini
        ));
        steps.add(new StepDefinition(
            15, "update_icons", "更新工作表图标",
            () -> StepExecutable.ExecutableResult.success("Worksheet icons updated"),
            false
        ));
        waves.add(new Wave(8, "图标更新", "匹配并更新工作表图标", steps, false, 1));
        return this;
    }

    /**
     * Wave 6: 自定义页面 + Chatbot
     */
    public WaveBuilder wave6Pages() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            16, "pages_plan", "规划自定义页面",
            () -> StepExecutable.ExecutableResult.success("Pages planned"),
            true  // uses Gemini
        ));
        steps.add(new StepDefinition(
            17, "pages_create", "创建自定义页面",
            () -> StepExecutable.ExecutableResult.success("Pages created"),
            false
        ));
        steps.add(new StepDefinition(
            18, "chatbots", "创建智能助手",
            () -> StepExecutable.ExecutableResult.success("Chatbots created"),
            true  // uses Gemini
        ));
        waves.add(new Wave(9, "页面与助手", "创建自定义页面与智能助手", steps, false, 1));
        return this;
    }

    /**
     * Wave 7: 删除默认视图 + 清理
     */
    public WaveBuilder wave7Cleanup() {
        List<StepDefinition> steps = new ArrayList<>();
        steps.add(new StepDefinition(
            19, "delete_default_views", "删除默认视图",
            () -> StepExecutable.ExecutableResult.success("Default views deleted"),
            false
        ));
        waves.add(new Wave(10, "清理阶段", "删除默认视图等清理工作", steps, false, 1));
        return this;
    }

    /**
     * 构建标准 7-Wave 流水线
     */
    public List<Wave> buildStandard() {
        return waves.isEmpty()
            ? wave1CreateApp()
                .wave2Planning()
                .wave2_5Sections()
                .wave3CreateWorksheets()
                .wave3_5Views()
                .wave3_5bMockData()
                .wave4Charts()
                .wave5Icons()
                .wave6Pages()
                .wave7Cleanup()
                .build()
            : waves;
    }

    /**
     * 构建并返回所有 Wave
     */
    public List<Wave> build() {
        return new ArrayList<>(waves);
    }
}
