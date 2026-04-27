package com.hap.automaker.core.wave;

import java.nio.file.Path;
import java.util.List;

/**
 * Wave执行结果
 */
public class WaveResult {
    private final String appId;
    private final List<StepResult> stepsReport;
    private final boolean hasFailure;
    private final long durationSeconds;

    public WaveResult(String appId, List<StepResult> stepsReport, boolean hasFailure, long durationSeconds) {
        this.appId = appId;
        this.stepsReport = stepsReport;
        this.hasFailure = hasFailure;
        this.durationSeconds = durationSeconds;
    }

    public String getAppId() {
        return appId;
    }

    public List<StepResult> getStepsReport() {
        return stepsReport;
    }

    public boolean hasFailure() {
        return hasFailure;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    @Override
    public String toString() {
        return "WaveResult{" +
            "appId='" + appId + '\'' +
            ", steps=" + stepsReport.size() +
            ", hasFailure=" + hasFailure +
            ", duration=" + durationSeconds + "s" +
            '}';
    }
}
