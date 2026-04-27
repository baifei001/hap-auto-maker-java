package com.hap.automaker.core.wave;

import java.util.List;

/**
 * Wave 定义 - 包含一组相关的步骤
 */
public class Wave {
    private final int waveNumber;
    private final String name;
    private final String description;
    private final List<StepDefinition> steps;
    private final boolean parallel;
    private final int maxWorkers;

    public Wave(int waveNumber, String name, String description,
                List<StepDefinition> steps, boolean parallel, int maxWorkers) {
        this.waveNumber = waveNumber;
        this.name = name;
        this.description = description;
        this.steps = steps;
        this.parallel = parallel;
        this.maxWorkers = maxWorkers;
    }

    public int getWaveNumber() {
        return waveNumber;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public boolean isParallel() {
        return parallel;
    }

    public int getMaxWorkers() {
        return maxWorkers;
    }

    @Override
    public String toString() {
        return String.format("Wave %d: %s (%s, %d steps, %s)",
            waveNumber, name, description, steps.size(),
            parallel ? "parallel" : "sequential");
    }
}
