package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 删除默认视图配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeleteDefaultViewsSpec {
    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("refresh_auth")
    private boolean refreshAuth = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRefreshAuth() {
        return refreshAuth;
    }

    public void setRefreshAuth(boolean refreshAuth) {
        this.refreshAuth = refreshAuth;
    }
}
