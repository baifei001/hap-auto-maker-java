package com.hap.automaker.core.wave;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 步骤执行结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StepResult {
    private final int stepId;
    private final String stepKey;
    private final String title;
    private final boolean success;
    private final boolean skipped;
    private final String reason;
    private final String error;
    private final String output;
    private final String outputPath;
    private final long durationMs;
    private final Map<String, Object> resultData;

    // 成功/失败构造器
    public StepResult(int stepId, String stepKey, String title,
                     boolean success, String error,
                     String output, String outputPath,
                     long durationMs) {
        this(stepId, stepKey, title, success, false, null, error,
             output, outputPath, durationMs, null);
    }

    // 跳过构造器
    public StepResult(int stepId, String stepKey, String title,
                     boolean skipped, String reason,
                     String outputPath, long durationMs) {
        this(stepId, stepKey, title, true, skipped, reason, null,
             null, outputPath, durationMs, null);
    }

    // 完整构造器
    public StepResult(int stepId, String stepKey, String title,
                     boolean success, boolean skipped, String reason, String error,
                     String output, String outputPath,
                     long durationMs, Map<String, Object> resultData) {
        this.stepId = stepId;
        this.stepKey = stepKey;
        this.title = title;
        this.success = success;
        this.skipped = skipped;
        this.reason = reason;
        this.error = error;
        this.output = output;
        this.outputPath = outputPath;
        this.durationMs = durationMs;
        this.resultData = resultData;
    }

    // Getters
    public int getStepId() {
        return stepId;
    }

    public String getStepKey() {
        return stepKey;
    }

    public String getTitle() {
        return title;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public String getReason() {
        return reason;
    }

    public String getError() {
        return error;
    }

    public String getOutput() {
        return output;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Map<String, Object> getResultData() {
        return resultData;
    }

    @Override
    public String toString() {
        if (skipped) {
            return String.format("[%d.%s] %s - 跳过 (%s)",
                stepId, stepKey, title, reason);
        }
        return String.format("[%d.%s] %s - %s (%dms)",
            stepId, stepKey, title,
            success ? "成功" : "失败: " + error,
            durationMs);
    }
}
