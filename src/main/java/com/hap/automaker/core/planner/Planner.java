package com.hap.automaker.core.planner;

/**
 * 规划器接口 - AI 规划任务的基接口
 *
 * @param <I> 输入上下文类型
 * @param <O> 规划结果类型
 */
public interface Planner<I, O> {

    /**
     * 执行 AI 规划
     *
     * @param input 输入上下文
     * @return 规划结果
     * @throws PlanningException 规划异常
     */
    O plan(I input) throws PlanningException;

    /**
     * 获取规划器名称
     */
    String getName();

    /**
     * 获取此规划器使用的 AI 模型
     */
    default String getAiModel() {
        return "gemini-2.5-pro";
    }

    /**
     * 验证规划结果是否有效
     *
     * @param result 规划结果
     * @return 验证结果
     */
    default boolean validate(O result) {
        return result != null;
    }
}
