package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 统计图表页面配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PagesSpec {
    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("skip_existing")
    private boolean skipExisting = true;

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
}
