package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 工作表配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorksheetsSpec {
    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("skip_existing")
    private boolean skipExisting = true;

    @JsonProperty("business_context")
    private String businessContext;

    @JsonProperty("requirements")
    private String requirements;

    @JsonProperty("max_worksheets")
    private Integer maxWorksheets;

    @JsonProperty("icon_update")
    private IconUpdateSpec iconUpdate = new IconUpdateSpec();

    @JsonProperty("layout")
    private LayoutSpec layout = new LayoutSpec();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSkipExisting() {
        return skipExisting;
    }

    public void setSkipExisting(boolean skipExisting) {
        this.skipExisting = skipExisting;
    }

    public String getBusinessContext() {
        return businessContext;
    }

    public void setBusinessContext(String businessContext) {
        this.businessContext = businessContext;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public Integer getMaxWorksheets() {
        return maxWorksheets;
    }

    public void setMaxWorksheets(Integer maxWorksheets) {
        this.maxWorksheets = maxWorksheets;
    }

    public IconUpdateSpec getIconUpdate() {
        return iconUpdate;
    }

    public void setIconUpdate(IconUpdateSpec iconUpdate) {
        this.iconUpdate = iconUpdate;
    }

    public LayoutSpec getLayout() {
        return layout;
    }

    public void setLayout(LayoutSpec layout) {
        this.layout = layout;
    }
}
