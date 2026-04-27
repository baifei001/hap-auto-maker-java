package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NaviStyle 导航风格配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NaviStyle {
    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("pcNaviStyle")
    private int pcNaviStyle = 1;

    @JsonProperty("refresh_auth")
    private boolean refreshAuth = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPcNaviStyle() {
        return pcNaviStyle;
    }

    public void setPcNaviStyle(int pcNaviStyle) {
        this.pcNaviStyle = pcNaviStyle;
    }

    public boolean isRefreshAuth() {
        return refreshAuth;
    }

    public void setRefreshAuth(boolean refreshAuth) {
        this.refreshAuth = refreshAuth;
    }
}
