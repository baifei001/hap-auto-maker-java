package com.hap.automaker.service;

import java.nio.file.Path;

/**
 * ViewFilter Pipeline Runner 接口
 *
 * 定义视图筛选流水线执行契约
 */
public interface ViewFilterPipelineRunner {

    /**
     * 执行视图筛选流水线
     *
     * @param repoRoot 仓库根目录
     * @param appId 应用ID
     * @param viewCreateResult 视图创建结果 JSON 路径
     * @param viewFilterPlanOutput 视图筛选规划输出路径
     * @param viewFilterResultOutput 视图筛选执行结果输出路径
     * @return 流水线执行结果
     */
    ViewFilterPipelineService.ViewFilterPipelineResult run(
            Path repoRoot,
            String appId,
            Path viewCreateResult,
            Path viewFilterPlanOutput,
            Path viewFilterResultOutput,
            boolean dryRun) throws Exception;
}
