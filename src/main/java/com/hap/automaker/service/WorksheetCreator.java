package com.hap.automaker.service;

import java.nio.file.Path;

public interface WorksheetCreator {

    WorksheetCreateResult createFromPlan(Path repoRoot, Path planJson, Path appAuthJson, Path outputJson) throws Exception;
}
