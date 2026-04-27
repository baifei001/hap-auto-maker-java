package com.hap.automaker.core.wave;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hap.automaker.model.spec.RequirementSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wave 执行上下文 - 保存流水线状态
 */
public class WaveContext {

    // 输入
    private final Path specPath;
    private final boolean dryRun;
    private final boolean failFast;
    private final RequirementSpec spec;

    // 执行状态
    @JsonIgnore
    private final List<StepResult> stepsReport = new CopyOnWriteArrayList<>();
    private volatile boolean hasFailure = false;

    // Wave 1 输出
    private volatile String appId;
    private volatile String appAuthJson;

    // Wave 2 输出
    private volatile String worksheetPlanJson;
    private volatile String rolePlanJson;
    private volatile String roleCreateResultJson;

    // Wave 2.5 输出
    private volatile String sectionsPlanJson;
    private volatile String sectionsCreateResultJson;
    private volatile String pageRegistryJson;

    // Wave 3 输出
    private volatile String worksheetCreateResultJson;

    // Wave 4 输出
    private volatile String viewCreateResultJson;
    private volatile String chartPlanJson;
    private volatile String chartCreateResultJson;
    private volatile String viewFilterResultJson;
    private volatile String iconMatchResultJson;
    private volatile String worksheetIconsResultJson;

    // Wave 5 输出
    private volatile String mockDataResultJson;
    private volatile String viewRepairResultJson;

    // Wave 6 输出
    private volatile String pageConfigResultJson;

    // Wave 7 输出
    private volatile String deleteDefaultViewsResultJson;

    public WaveContext(Path specPath, boolean dryRun, boolean failFast, RequirementSpec spec) {
        this.specPath = specPath;
        this.dryRun = dryRun;
        this.failFast = failFast;
        this.spec = spec;
    }

    // Getters
    public Path getSpecPath() {
        return specPath;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public RequirementSpec getSpec() {
        return spec;
    }

    public List<StepResult> getStepsReport() {
        return new ArrayList<>(stepsReport);
    }

    public synchronized void addStepResult(StepResult result) {
        stepsReport.add(result);
        if (!result.isSuccess() && !result.isSkipped()) {
            hasFailure = true;
        }
    }

    public boolean hasFailure() {
        return hasFailure;
    }

    // App
    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppAuthJson() {
        return appAuthJson;
    }

    public void setAppAuthJson(String appAuthJson) {
        this.appAuthJson = appAuthJson;
    }

    // Worksheet Plan
    public String getWorksheetPlanJson() {
        return worksheetPlanJson;
    }

    public void setWorksheetPlanJson(String worksheetPlanJson) {
        this.worksheetPlanJson = worksheetPlanJson;
    }

    // Role
    public String getRolePlanJson() {
        return rolePlanJson;
    }

    public void setRolePlanJson(String rolePlanJson) {
        this.rolePlanJson = rolePlanJson;
    }

    public String getRoleCreateResultJson() {
        return roleCreateResultJson;
    }

    public void setRoleCreateResultJson(String roleCreateResultJson) {
        this.roleCreateResultJson = roleCreateResultJson;
    }

    // Sections
    public String getSectionsPlanJson() {
        return sectionsPlanJson;
    }

    public void setSectionsPlanJson(String sectionsPlanJson) {
        this.sectionsPlanJson = sectionsPlanJson;
    }

    public String getSectionsCreateResultJson() {
        return sectionsCreateResultJson;
    }

    public void setSectionsCreateResultJson(String sectionsCreateResultJson) {
        this.sectionsCreateResultJson = sectionsCreateResultJson;
    }

    // Page Registry
    public String getPageRegistryJson() {
        return pageRegistryJson;
    }

    public void setPageRegistryJson(String pageRegistryJson) {
        this.pageRegistryJson = pageRegistryJson;
    }

    // Worksheet Create
    public String getWorksheetCreateResultJson() {
        return worksheetCreateResultJson;
    }

    public void setWorksheetCreateResultJson(String worksheetCreateResultJson) {
        this.worksheetCreateResultJson = worksheetCreateResultJson;
    }

    // View
    public String getViewCreateResultJson() {
        return viewCreateResultJson;
    }

    public void setViewCreateResultJson(String viewCreateResultJson) {
        this.viewCreateResultJson = viewCreateResultJson;
    }

    // Chart
    public String getChartPlanJson() {
        return chartPlanJson;
    }

    public void setChartPlanJson(String chartPlanJson) {
        this.chartPlanJson = chartPlanJson;
    }

    public String getChartCreateResultJson() {
        return chartCreateResultJson;
    }

    public void setChartCreateResultJson(String chartCreateResultJson) {
        this.chartCreateResultJson = chartCreateResultJson;
    }

    // View Filter
    public String getViewFilterResultJson() {
        return viewFilterResultJson;
    }

    public void setViewFilterResultJson(String viewFilterResultJson) {
        this.viewFilterResultJson = viewFilterResultJson;
    }

    // Icon
    public String getIconMatchResultJson() {
        return iconMatchResultJson;
    }

    public void setIconMatchResultJson(String iconMatchResultJson) {
        this.iconMatchResultJson = iconMatchResultJson;
    }

    // Worksheet Icons
    public String getWorksheetIconsResultJson() {
        return worksheetIconsResultJson;
    }

    public void setWorksheetIconsResultJson(String worksheetIconsResultJson) {
        this.worksheetIconsResultJson = worksheetIconsResultJson;
    }

    // Mock Data
    public String getMockDataResultJson() {
        return mockDataResultJson;
    }

    public void setMockDataResultJson(String mockDataResultJson) {
        this.mockDataResultJson = mockDataResultJson;
    }

    // View Repair
    public String getViewRepairResultJson() {
        return viewRepairResultJson;
    }

    public void setViewRepairResultJson(String viewRepairResultJson) {
        this.viewRepairResultJson = viewRepairResultJson;
    }

    // Page Config
    public String getPageConfigResultJson() {
        return pageConfigResultJson;
    }

    public void setPageConfigResultJson(String pageConfigResultJson) {
        this.pageConfigResultJson = pageConfigResultJson;
    }

    // Delete Default Views
    public String getDeleteDefaultViewsResultJson() {
        return deleteDefaultViewsResultJson;
    }

    public void setDeleteDefaultViewsResultJson(String deleteDefaultViewsResultJson) {
        this.deleteDefaultViewsResultJson = deleteDefaultViewsResultJson;
    }
}
