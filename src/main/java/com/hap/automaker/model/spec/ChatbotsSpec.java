package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 智能机器人配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatbotsSpec {
    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("auto")
    private boolean auto = true;

    @JsonProperty("dry_run")
    private boolean dryRun = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAuto() {
        return auto;
    }

    public void setAuto(boolean auto) {
        this.auto = auto;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }
}
