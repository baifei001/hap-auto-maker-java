package com.hap.automaker.service;

import java.nio.file.Path;

public interface PagePipelineRunner {

    PagePipelineResult run(Path repoRoot, String pageAppId, Path planOutput, Path outputJson) throws Exception;
}
