package com.hap.automaker.service;

import java.nio.file.Path;

/**
 * Icon Pipeline Runner 接口
 *
 * 定义图标流水线执行契约
 */
public interface IconPipelineRunner {

    /**
     * 执行图标流水线
     *
     * @param repoRoot 仓库根目录
     * @param appId 应用ID
     * @param worksheetCreateResult 工作表创建结果 JSON 路径
     * @param iconPlanOutput 图标规划输出路径
     * @param iconResultOutput 图标执行结果输出路径
     * @return 流水线执行结果
     */
    IconPipelineService.IconPipelineResult run(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path iconPlanOutput,
            Path iconResultOutput) throws Exception;
}
