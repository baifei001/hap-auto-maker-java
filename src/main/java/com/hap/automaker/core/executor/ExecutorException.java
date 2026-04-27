package com.hap.automaker.core.executor;

/**
 * 执行器异常
 */
public class ExecutorException extends RuntimeException {

    private final String executorName;
    private final String errorCode;

    public ExecutorException(String executorName, String message) {
        super(message);
        this.executorName = executorName;
        this.errorCode = "EXECUTION_ERROR";
    }

    public ExecutorException(String executorName, String message, Throwable cause) {
        super(message, cause);
        this.executorName = executorName;
        this.errorCode = "EXECUTION_ERROR";
    }

    public ExecutorException(String executorName, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.executorName = executorName;
        this.errorCode = errorCode;
    }

    public String getExecutorName() { return executorName; }
    public String getErrorCode() { return errorCode; }

    @Override
    public String toString() {
        return String.format("ExecutorException[%s:%s]: %s", executorName, errorCode, getMessage());
    }
}
