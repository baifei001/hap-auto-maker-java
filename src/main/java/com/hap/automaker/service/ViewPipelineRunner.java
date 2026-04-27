package com.hap.automaker.service;

import java.nio.file.Path;

public interface ViewPipelineRunner {

    ViewPipelineResult run(Path repoRoot, Path appAuthJson, Path outputJson) throws Exception;
}
