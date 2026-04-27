package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 角色配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RolesSpec {
    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("skip_existing")
    private boolean skipExisting = true;

    @JsonProperty("video_mode")
    private String videoMode = "skip";

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

    public String getVideoMode() {
        return videoMode;
    }

    public void setVideoMode(String videoMode) {
        this.videoMode = videoMode;
    }
}
