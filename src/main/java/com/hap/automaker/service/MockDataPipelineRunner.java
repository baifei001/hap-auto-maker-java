package com.hap.automaker.service;

import java.nio.file.Path;

/**
 * Mock数据流水线Runner接口
 *
 * Python对应: pipeline_mock_data.py
 */
public interface MockDataPipelineRunner {

    /**
     * 执行Mock数据流水线
     *
     * @param repoRoot 仓库根目录
     * @param appId 应用ID
     * @param worksheetCreateResult 工作表创建结果JSON路径
     * @param mockDataPlanOutput Mock数据规划输出路径
     * @param mockDataResultOutput Mock数据创建结果输出路径
     * @param dryRun 是否为干跑模式
     * @return 流水线执行结果
     * @throws Exception 执行异常
     */
    MockDataPipelineResult run(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path mockDataPlanOutput,
            Path mockDataResultOutput,
            boolean dryRun) throws Exception;

    /**
     * 流水线执行结果
     */
    record MockDataPipelineResult(
            Path planOutputPath,
            Path resultOutputPath,
            int totalWorksheets,
            int totalRecords,
            int createdRecords,
            boolean success
    ) {
        public java.util.Map<String, Object> summary() {
            java.util.Map<String, Object> summary = new java.util.HashMap<>();
            summary.put("planOutputPath", planOutputPath.toString());
            summary.put("resultOutputPath", resultOutputPath.toString());
            summary.put("totalWorksheets", totalWorksheets);
            summary.put("totalRecords", totalRecords);
            summary.put("createdRecords", createdRecords);
            summary.put("success", success);
            return summary;
        }
    }
}
