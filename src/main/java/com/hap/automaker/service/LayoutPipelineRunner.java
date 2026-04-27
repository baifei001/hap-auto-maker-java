package com.hap.automaker.service;

import java.nio.file.Path;

/**
 * Layout Pipeline Runner 接口
 *
 * 定义布局流水线执行契约
 */
public interface LayoutPipelineRunner {

    /**
     * 执行布局流水线
     *
     * @param repoRoot 仓库根目录
     * @param appId 应用ID
     * @param worksheetCreateResult 工作表创建结果 JSON 路径
     * @param layoutPlanOutput 布局规划输出路径
     * @param layoutResultOutput 布局执行结果输出路径
     * @return 流水线执行结果
     */
    LayoutPipelineService.LayoutPipelineResult run(
            Path repoRoot,
            String appId,
            Path worksheetCreateResult,
            Path layoutPlanOutput,
            Path layoutResultOutput) throws Exception;
}
