package com.hap.automaker.core.executor;

/**
 * 执行器接口 - 所有业务执行器的基接口
 *
 * @param <I> 输入参数类型
 * @param <O> 输出结果类型
 */
public interface Executor<I, O> {

    /**
     * 执行核心逻辑
     *
     * @param input 输入参数
     * @return 执行结果
     * @throws ExecutorException 执行异常
     */
    O execute(I input) throws ExecutorException;

    /**
     * 带选项执行核心逻辑
     *
     * @param input 输入参数
     * @param options 执行选项
     * @return 执行结果
     * @throws ExecutorException 执行异常
     */
    default O execute(I input, ExecuteOptions options) throws ExecutorException {
        return execute(input);
    }

    /**
     * 获取执行器名称
     */
    String getName();

    /**
     * 检查是否支持回滚
     */
    default boolean isRollbackable() {
        return false;
    }

    /**
     * 回滚操作（如支持）
     *
     * @param executionId 执行ID
     * @return 回滚结果
     * @throws UnsupportedOperationException 如不支持回滚
     */
    default O rollback(String executionId) {
        throw new UnsupportedOperationException("Rollback not supported");
    }

    /**
     * 回滚执行结果
     *
     * @param result 执行结果
     * @return 是否回滚成功
     * @throws ExecutorException 执行异常
     */
    default boolean rollback(O result) throws ExecutorException {
        throw new UnsupportedOperationException("Rollback not supported");
    }
}
