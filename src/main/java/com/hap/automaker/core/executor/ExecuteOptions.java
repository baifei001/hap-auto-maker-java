package com.hap.automaker.core.executor;

/**
 * 执行选项
 */
public class ExecuteOptions {

    private boolean skipExisting = true;
    private boolean dryRun = false;
    private boolean failFast = false;

    public ExecuteOptions() {
    }

    public ExecuteOptions(boolean skipExisting, boolean dryRun) {
        this.skipExisting = skipExisting;
        this.dryRun = dryRun;
        this.failFast = false;
    }

    public ExecuteOptions(boolean skipExisting, boolean dryRun, boolean failFast) {
        this.skipExisting = skipExisting;
        this.dryRun = dryRun;
        this.failFast = failFast;
    }

    public boolean isSkipExisting() {
        return skipExisting;
    }

    public void setSkipExisting(boolean skipExisting) {
        this.skipExisting = skipExisting;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public static ExecuteOptions defaults() {
        return new ExecuteOptions();
    }

    public static ExecuteOptions skipExisting() {
        return new ExecuteOptions(true, false);
    }

    public static ExecuteOptions failOnExisting() {
        return new ExecuteOptions(false, false);
    }
}
