package com.hap.automaker.service;

import java.nio.file.Path;

public interface WorksheetPlannerRunner {

    WorksheetPlannerResult plan(
            Path repoRoot,
            String appName,
            String businessContext,
            String requirements,
            String language,
            Path outputJson) throws Exception;
}
