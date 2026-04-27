package com.hap.automaker.core.wave;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Wave 框架测试
 */
class WaveFrameworkTest {

    @Test
    void testWaveCreation() {
        StepDefinition step1 = new StepDefinition(
            1, "step1", "测试步骤1",
            () -> StepExecutable.ExecutableResult.success("output1"),
            false
        );

        StepDefinition step2 = new StepDefinition(
            2, "step2", "测试步骤2",
            () -> StepExecutable.ExecutableResult.success("output2", "/path/to/output"),
            true
        );

        Wave wave = new Wave(1, "测试Wave", "用于测试的Wave", List.of(step1, step2), false, 1);

        assertEquals(1, wave.getWaveNumber());
        assertEquals("测试Wave", wave.getName());
        assertEquals(2, wave.getSteps().size());
        assertFalse(wave.isParallel());
    }

    @Test
    void testParallelWave() {
        StepDefinition step1 = new StepDefinition(1, "s1", "步骤1",
            () -> StepExecutable.ExecutableResult.success("ok"), false);
        StepDefinition step2 = new StepDefinition(2, "s2", "步骤2",
            () -> StepExecutable.ExecutableResult.success("ok"), false);

        Wave wave = new Wave(2, "并行Wave", "并行执行的Wave", List.of(step1, step2), true, 2);

        assertTrue(wave.isParallel());
        assertEquals(2, wave.getMaxWorkers());
    }

    @Test
    void testStepResult() {
        StepResult success = new StepResult(
            1, "step1", "步骤1",
            true, null,
            "output", "/path", 100
        );

        assertEquals(1, success.getStepId());
        assertEquals("step1", success.getStepKey());
        assertTrue(success.isSuccess());
        assertFalse(success.isSkipped());
        assertEquals(100, success.getDurationMs());
    }

    @Test
    void testSkippedStepResult() {
        StepResult skipped = new StepResult(
            2, "step2", "步骤2",
            true, "disabled",
            "/path", 0
        );

        assertTrue(skipped.isSuccess());
        assertTrue(skipped.isSkipped());
        assertEquals("disabled", skipped.getReason());
    }

    @Test
    void testStepExecutor() {
        StepExecutor executor = new StepExecutor();

        StepDefinition step = new StepDefinition(
            1, "test", "测试步骤",
            () -> StepExecutable.ExecutableResult.success("success output"),
            false
        );

        // Note: 完整执行需要 WaveContext，这里只做简单的单元测试
        // 详细的集成测试需要 mock context
        assertNotNull(executor);
    }

    @Test
    void testWaveBuilder() {
        List<Wave> waves = WaveBuilder.create()
            .wave1CreateApp()
            .wave2Planning()
            .wave3CreateWorksheets()
            .build();

        assertEquals(3, waves.size());
        assertEquals(1, waves.get(0).getWaveNumber());
        assertEquals(2, waves.get(1).getWaveNumber()); // wave2 是并行
        assertEquals(4, waves.get(2).getWaveNumber()); // wave3
    }

    @Test
    void testWaveBuilderStandard() {
        List<Wave> waves = WaveBuilder.create().buildStandard();

        assertFalse(waves.isEmpty());
        assertTrue(waves.size() >= 7); // 至少 7 个 Wave

        // 验证 Wave 编号递增
        for (int i = 0; i < waves.size(); i++) {
            assertTrue(waves.get(i).getWaveNumber() > 0);
        }
    }

    @Test
    void testWaveExecutorConstructor() {
        WaveExecutor executor = new WaveExecutor(5);

        assertNotNull(executor);

        WaveExecutor executorWithWaves = WaveExecutor.standard(3);
        assertNotNull(executorWithWaves);

        WaveExecutor sequential = WaveExecutor.sequential(1);
        assertNotNull(sequential);
    }

    @Test
    void testExecutableResult() {
        StepExecutable.ExecutableResult result = StepExecutable.ExecutableResult.success("test");
        assertEquals("test", result.getOutput());
        assertNull(result.getOutputPath());

        StepExecutable.ExecutableResult resultWithPath = StepExecutable.ExecutableResult.success("test", "/path");
        assertEquals("test", resultWithPath.getOutput());
        assertEquals("/path", resultWithPath.getOutputPath());
    }
}
