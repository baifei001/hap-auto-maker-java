package com.hap.automaker.core.wave;

/**
 * 步骤执行接口
 */
@FunctionalInterface
public interface StepExecutable {

    /**
     * 执行步骤
     * @return 执行结果
     * @throws Exception 执行异常
     */
    ExecutableResult execute() throws Exception;

    /**
     * 执行结果
     */
    class ExecutableResult {
        private final String output;
        private final String outputPath;

        public ExecutableResult(String output, String outputPath) {
            this.output = output;
            this.outputPath = outputPath;
        }

        public static ExecutableResult success(String output) {
            return new ExecutableResult(output, null);
        }

        public static ExecutableResult success(String output, String outputPath) {
            return new ExecutableResult(output, outputPath);
        }

        public String getOutput() {
            return output;
        }

        public String getOutputPath() {
            return outputPath;
        }
    }
}
