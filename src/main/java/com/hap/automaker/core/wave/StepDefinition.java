package com.hap.automaker.core.wave;

import java.util.concurrent.Callable;

/**
 * 步骤定义
 */
public class StepDefinition {
    private final int stepId;
    private final String stepKey;
    private final String title;
    private final StepExecutable executable;
    private final boolean usesGemini;

    public StepDefinition(int stepId, String stepKey, String title,
                         StepExecutable executable, boolean usesGemini) {
        this.stepId = stepId;
        this.stepKey = stepKey;
        this.title = title;
        this.executable = executable;
        this.usesGemini = usesGemini;
    }

    public int getStepId() {
        return stepId;
    }

    public String getStepKey() {
        return stepKey;
    }

    public String getTitle() {
        return title;
    }

    public StepExecutable getExecutable() {
        return executable;
    }

    public boolean isUsesGemini() {
        return usesGemini;
    }

    @Override
    public String toString() {
        return stepId + "." + stepKey + ": " + title;
    }
}
