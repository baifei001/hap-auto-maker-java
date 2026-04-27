package com.hap.automaker.service;

import java.nio.file.Path;

/**
 * Section Pipeline Runner 接口
 *
 * 定义分组流水线的执行契约
 */
public interface SectionPipelineRunner {

    /**
     * 执行分组流水线
     *
     * @param repoRoot 仓库根目录
     * @param appId 应用ID
     * @param appAuth 应用授权文件路径
     * @param worksheetCreateResult 工作表创建结果 JSON 路径
     * @param sectionPlanOutput 分组规划输出路径
     * @param sectionResultOutput 分组执行结果输出路径
     * @return 流水线执行结果
     */
    SectionPipelineService.SectionPipelineResult run(
            Path repoRoot,
            String appId,
            Path appAuth,
            Path worksheetCreateResult,
            Path sectionPlanOutput,
            Path sectionResultOutput) throws Exception;
}
