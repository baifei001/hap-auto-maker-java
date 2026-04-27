package com.hap.automaker.service;

import java.nio.file.Path;
import java.util.List;

public interface ChartPipelineRunner {

    ChartPipelineResult run(
            Path repoRoot,
            String appId,
            String appName,
            List<String> worksheetIds,
            String pageId,
            Path planOutput,
            Path outputJson) throws Exception;
}
