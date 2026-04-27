package com.hap.automaker.core.wave;

import com.hap.automaker.util.LoggerFactory;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 步骤执行器 - 执行单个步骤并处理重试逻辑
 */
public class StepExecutor {

    private static final Logger logger = LoggerFactory.getLogger(StepExecutor.class);

    // 默认重试配置
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_RETRY_DELAY_MS = 1000;
    public static final double RETRY_BACKOFF_MULTIPLIER = 2.0;

    private final AtomicInteger stepCounter = new AtomicInteger(1);
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private long retryDelayMs = DEFAULT_RETRY_DELAY_MS;
    private boolean useExponentialBackoff = true;

    public StepExecutor() {
    }

    public StepExecutor(int maxRetries, long retryDelayMs, boolean useExponentialBackoff) {
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.useExponentialBackoff = useExponentialBackoff;
    }

    /**
     * 执行步骤（带重试）
     *
     * @param step 步骤定义
     * @param context 执行上下文
     * @param onResult 结果回调
     * @return 执行结果
     */
    public StepResult execute(StepDefinition step, WaveContext context, Consumer<StepResult> onResult) {
        long startTime = System.currentTimeMillis();
        int stepId = step.getStepId();
        String stepKey = step.getStepKey();
        String title = step.getTitle();

        logger.info("[{}] {} - 开始执行", stepKey, title);

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    long delay = calculateRetryDelay(attempt - 1);
                    logger.info("[{}] 第 {} 次重试，等待 {}ms...", stepKey, attempt, delay);
                    Thread.sleep(delay);
                }

                StepExecutable.ExecutableResult result = step.getExecutable().execute();

                long durationMs = System.currentTimeMillis() - startTime;
                StepResult stepResult = new StepResult(
                    stepId, stepKey, title,
                    true, null,
                    result.getOutput(), result.getOutputPath(),
                    durationMs
                );

                context.addStepResult(stepResult);
                if (onResult != null) {
                    onResult.accept(stepResult);
                }

                logger.info("[{}] {} - 执行成功 ({}ms)", stepKey, title, durationMs);
                return stepResult;

            } catch (Exception e) {
                lastException = e;
                logger.warn("[{}] 第 {} 次尝试失败: {}", stepKey, attempt, e.getMessage());

                if (attempt == maxRetries) {
                    break;
                }
            }
        }

        // 所有重试都失败了
        long durationMs = System.currentTimeMillis() - startTime;
        String errorMsg = lastException != null ? lastException.getMessage() : "Unknown error";
        if (errorMsg == null || errorMsg.isEmpty()) {
            errorMsg = lastException != null ? lastException.getClass().getSimpleName() : "失败";
        }

        StepResult stepResult = new StepResult(
            stepId, stepKey, title,
            false, errorMsg,
            null, null,
            durationMs
        );

        context.addStepResult(stepResult);
        if (onResult != null) {
            onResult.accept(stepResult);
        }

        logger.error("[{}] {} - 执行失败，已重试 {} 次 ({}ms): {}",
            stepKey, title, maxRetries, durationMs, errorMsg);
        return stepResult;
    }

    /**
     * 跳过步骤
     *
     * @param step 步骤定义
     * @param context 执行上下文
     * @param reason 跳过原因
     * @return 跳过结果
     */
    public StepResult skip(StepDefinition step, WaveContext context, String reason) {
        int stepId = step.getStepId();
        String stepKey = step.getStepKey();
        String title = step.getTitle();

        StepResult stepResult = new StepResult(
            stepId, stepKey, title,
            true, reason,
            null, 0
        );

        context.addStepResult(stepResult);
        logger.info("[{}] {} - 跳过: {}", stepKey, title, reason);
        return stepResult;
    }

    /**
     * 计算重试延迟
     */
    private long calculateRetryDelay(int retryNumber) {
        if (!useExponentialBackoff) {
            return retryDelayMs;
        }
        return (long) (retryDelayMs * Math.pow(RETRY_BACKOFF_MULTIPLIER, retryNumber - 1));
    }

    public int getNextStepId() {
        return stepCounter.getAndIncrement();
    }

    // Setters
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public void setUseExponentialBackoff(boolean useExponentialBackoff) {
        this.useExponentialBackoff = useExponentialBackoff;
    }
}
