package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mock数据配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MockDataSpec {
    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("dry_run")
    private boolean dryRun = false;

    @JsonProperty("trigger_workflow")
    private boolean triggerWorkflow = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isTriggerWorkflow() {
        return triggerWorkflow;
    }

    public void setTriggerWorkflow(boolean triggerWorkflow) {
        this.triggerWorkflow = triggerWorkflow;
    }
}
