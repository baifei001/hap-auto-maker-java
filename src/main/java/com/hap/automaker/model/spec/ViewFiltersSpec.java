package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 视图筛选器配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ViewFiltersSpec {
    @JsonProperty("enabled")
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
