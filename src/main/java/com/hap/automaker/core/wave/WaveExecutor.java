package com.hap.automaker.core.wave;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.spec.RequirementSpec;
import com.hap.automaker.pipeline.PipelineContext;
import com.hap.automaker.util.LoggerFactory;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wave 引擎 - Python waves.py 的 Java 实现
 *
 * 7-Wave 并行执行流水线：
 * - Wave 1: 创建/使用现有应用
 * - Wave 2: 工作表规划 + 角色规划（并行）
 * - Wave 2.5: 分组规划 + 导航风格
 * - Wave 3: 创建工作表 + 分组
 * - Wave 3.5: 逐表创建视图（并行）
 * - Wave 3.5b: 逐表造数 + 关联字段填写（并行）
 * - Wave 4: 图表规划与创建 + 视图筛选器
 * - Wave 5: 图标匹配与更新
 * - Wave 6: 自定义页面 + Chatbot
 * - Wave 7: 删除默认视图 + 清理
 */
public class WaveExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WaveExecutor.class);
    private static final ObjectMapper mapper = Jacksons.mapper();

    // 并发控制
    private final Semaphore geminiSemaphore;
    private final ExecutorService executor;
    private final ReentrantLock stepsLock = new ReentrantLock();

    // App ID 提取模式
    private static final Pattern APP_ID_PATTERN = Pattern.compile(
        "\"appId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
    private static final Pattern APP_ID_ALT_PATTERN = Pattern.compile(
        "appId:\\s*([0-9a-fA-F-]{36})");

    // 执行配置
    private final int maxGeminiConcurrency;
    private final boolean failFast;
    private final boolean rollbackOnFailure;
    private final boolean forceReplan;

    // Wave 定义
    private final List<Wave> waves;

    public WaveExecutor(int maxGeminiConcurrency) {
        this(maxGeminiConcurrency, true, false, false, WaveBuilder.create().buildStandard());
    }

    public WaveExecutor(int maxGeminiConcurrency, List<Wave> waves) {
        this(maxGeminiConcurrency, true, false, false, waves);
    }

    public WaveExecutor(int maxGeminiConcurrency, boolean failFast,
                       boolean rollbackOnFailure, boolean forceReplan,
                       List<Wave> waves) {
        this.maxGeminiConcurrency = maxGeminiConcurrency;
        this.failFast = failFast;
        this.rollbackOnFailure = rollbackOnFailure;
        this.forceReplan = forceReplan;
        this.geminiSemaphore = new Semaphore(maxGeminiConcurrency);
        this.executor = Executors.newCachedThreadPool();
        this.waves = waves;
    }

    /**
     * 执行全部 Wave
     *
     * @param spec 需求规格
     * @param specPath spec 文件路径
     * @param dryRun 是否仅模拟执行
     * @param language 语言设置
     * @return 执行结果
     */
    public WaveResult executeAll(RequirementSpec spec,
                                  Path specPath,
                                  boolean dryRun,
                                  String language) {
        long startTime = System.currentTimeMillis();
        WaveContext ctx = new WaveContext(specPath, dryRun, failFast, spec);

        logger.info("========== HAP Wave Executor ==========");
        logger.info("App Name: {}", spec.getApp() != null ? spec.getApp().getName() : "N/A");
        logger.info("Language: {}", language);
        logger.info("Dry Run: {}", dryRun);
        logger.info("Fail Fast: {}", failFast);
        logger.info("Force Replan: {}", forceReplan);
        logger.info("Max Gemini Concurrency: {}", maxGeminiConcurrency);
        logger.info("=======================================");

        try {
            // 按顺序执行每个 Wave
            for (Wave wave : waves) {
                if (shouldAbort(ctx)) {
                    logger.warn("检测到失败，根据 failFast={} 终止执行", failFast);
                    break;
                }

                logger.info("\n-- {}: {} --- 总计 {}s",
                    wave.getName(), wave.getDescription(),
                    (System.currentTimeMillis() - startTime) / 1000);

                boolean waveSuccess = executeWave(wave, ctx, dryRun, language);

                if (!waveSuccess && failFast) {
                    logger.error("Wave {} 执行失败，终止流水线", wave.getWaveNumber());
                    break;
                }
            }

            // 执行完成，生成报告
            Path reportPath = saveReport(ctx, startTime);
            logger.info("执行报告: {}", reportPath);

        } catch (Exception e) {
            logger.error("执行异常: {}", e.getMessage(), e);
            ctx.addStepResult(new StepResult(
                -1, "executor", "执行器",
                false, e.getMessage(),
                null, null,
                System.currentTimeMillis() - startTime
            ));
        }

        return createResult(ctx, startTime);
    }

    /**
     * 执行单个 Wave
     */
    private boolean executeWave(Wave wave, WaveContext ctx,
                                boolean dryRun, String language) {
        List<StepDefinition> steps = wave.getSteps();

        if (steps.isEmpty()) {
            return true;
        }

        if (wave.isParallel()) {
            return executeWaveParallel(wave, ctx, dryRun, language);
        } else {
            return executeWaveSequential(wave, ctx, dryRun, language);
        }
    }

    /**
     * 串行执行 Wave
     */
    private boolean executeWaveSequential(Wave wave, WaveContext ctx,
                                          boolean dryRun, String language) {
        StepExecutor stepExecutor = new StepExecutor();
        boolean allSuccess = true;

        for (StepDefinition step : wave.getSteps()) {
            if (shouldAbort(ctx)) {
                return false;
            }

            StepResult result;
            if (isStepSkipped(step, ctx, dryRun)) {
                result = stepExecutor.skip(step, ctx, "disabled_by_spec");
            } else {
                result = executeStepWithGeminiControl(step, ctx, stepExecutor, dryRun);
            }

            if (!result.isSuccess() && !result.isSkipped()) {
                allSuccess = false;
                if (failFast) {
                    return false;
                }
            }
        }

        return allSuccess;
    }

    /**
     * 并行执行 Wave
     */
    private boolean executeWaveParallel(Wave wave, WaveContext ctx,
                                        boolean dryRun, String language) {
        int maxWorkers = wave.getMaxWorkers();
        List<StepDefinition> steps = wave.getSteps();
        List<Future<StepResult>> futures = new ArrayList<>();
        StepExecutor stepExecutor = new StepExecutor();

        // 提交所有步骤
        for (StepDefinition step : steps) {
            Future<StepResult> future = executor.submit(() -> {
                if (isStepSkipped(step, ctx, dryRun)) {
                    return stepExecutor.skip(step, ctx, "disabled_by_spec");
                }
                return executeStepWithGeminiControl(step, ctx, stepExecutor, dryRun);
            });
            futures.add(future);
        }

        // 收集结果
        boolean allSuccess = true;
        for (Future<StepResult> future : futures) {
            try {
                StepResult result = future.get();
                if (!result.isSuccess() && !result.isSkipped()) {
                    allSuccess = false;
                    if (failFast) {
                        return false;
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                logger.error("并行步骤执行异常: {}", e.getMessage());
                allSuccess = false;
            }
        }

        return allSuccess;
    }

    /**
     * 执行步骤（带 Gemini 并发控制）
     */
    private StepResult executeStepWithGeminiControl(StepDefinition step,
                                                     WaveContext ctx,
                                                     StepExecutor stepExecutor,
                                                     boolean dryRun) {
        if (step.isUsesGemini()) {
            try {
                geminiSemaphore.acquire();
                try {
                    return stepExecutor.execute(step, ctx, null);
                } finally {
                    geminiSemaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new StepResult(
                    step.getStepId(), step.getStepKey(), step.getTitle(),
                    false, "Interrupted waiting for Gemini semaphore",
                    null, null, 0
                );
            }
        } else {
            return stepExecutor.execute(step, ctx, null);
        }
    }

    /**
     * 检查步骤是否应该被跳过
     */
    private boolean isStepSkipped(StepDefinition step, WaveContext ctx, boolean dryRun) {
        // 根据 spec 配置和步骤类型决定是否跳过
        RequirementSpec spec = ctx.getSpec();

        // 这里可以根据 spec 中的配置决定跳过哪些步骤
        // 例如：如果 worksheets 被禁用，跳过相关工作表步骤
        // 实际实现需要根据 spec 结构调整

        return false;
    }

    /**
     * 检查是否应该终止执行
     */
    private boolean shouldAbort(WaveContext ctx) {
        return failFast && ctx.hasFailure();
    }

    /**
     * 保存执行报告
     */
    private Path saveReport(WaveContext ctx, long startTime) {
        // 实际报告保存逻辑
        // 可以使用 PipelineContext 的报告机制
        return ctx.getSpecPath().getParent()
            .resolve("execution_runs")
            .resolve("wave_execution_report.json");
    }

    /**
     * 创建执行结果
     */
    private WaveResult createResult(WaveContext ctx, long startTime) {
        long durationSeconds = (System.currentTimeMillis() - startTime) / 1000;
        return new WaveResult(
            ctx.getAppId(),
            ctx.getStepsReport(),
            ctx.hasFailure(),
            durationSeconds
        );
    }

    /**
     * 提取 App ID
     */
    private String extractAppId(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        Matcher m = APP_ID_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }

        m = APP_ID_ALT_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }

        return null;
    }

    /**
     * 关闭执行器
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ===== 工厂方法 =====

    /**
     * 创建标准 WaveExecutor
     */
    public static WaveExecutor standard(int maxGeminiConcurrency) {
        return new WaveExecutor(maxGeminiConcurrency);
    }

    /**
     * 创建标准 WaveExecutor（串行模式，用于调试）
     */
    public static WaveExecutor sequential(int maxGeminiConcurrency) {
        return new WaveExecutor(maxGeminiConcurrency, WaveBuilder.create().buildStandard());
    }
}
