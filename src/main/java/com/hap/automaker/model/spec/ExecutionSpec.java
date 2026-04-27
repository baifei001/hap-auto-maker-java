package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 执行配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecutionSpec {
    @JsonProperty("fail_fast")
    private boolean failFast = true;

    @JsonProperty("dry_run")
    private boolean dryRun = false;

    @JsonProperty("force_replan")
    private boolean forceReplan = false;

    @JsonProperty("rollback_on_failure")
    private boolean rollbackOnFailure = false;

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isForceReplan() {
        return forceReplan;
    }

    public void setForceReplan(boolean forceReplan) {
        this.forceReplan = forceReplan;
    }

    public boolean isRollbackOnFailure() {
        return rollbackOnFailure;
    }

    public void setRollbackOnFailure(boolean rollbackOnFailure) {
        this.rollbackOnFailure = rollbackOnFailure;
    }
}
