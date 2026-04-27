package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 布局配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LayoutSpec {
    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("requirements")
    private String requirements = "";

    @JsonProperty("refresh_auth")
    private boolean refreshAuth = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public boolean isRefreshAuth() {
        return refreshAuth;
    }

    public void setRefreshAuth(boolean refreshAuth) {
        this.refreshAuth = refreshAuth;
    }
}
