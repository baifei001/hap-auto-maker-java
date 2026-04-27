package com.hap.automaker.service;

import java.nio.file.Path;

/**
 * Role Pipeline Runner 接口
 *
 * 定义角色流水线的执行契约
 */
public interface RolePipelineRunner {

    /**
     * 执行角色流水线
     *
     * @param repoRoot 仓库根目录
     * @param appId 应用ID
     * @param appAuth 应用授权文件路径
     * @param worksheetCreateResult 工作表创建结果 JSON 路径
     * @param rolePlanOutput 角色规划输出路径
     * @param roleResultOutput 角色执行结果输出路径
     * @return 流水线执行结果
     */
    RolePipelineService.RolePipelineResult run(
            Path repoRoot,
            String appId,
            Path appAuth,
            Path worksheetCreateResult,
            Path rolePlanOutput,
            Path roleResultOutput) throws Exception;
}
