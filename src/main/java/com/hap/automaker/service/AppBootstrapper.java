package com.hap.automaker.service;

import java.nio.file.Path;

public interface AppBootstrapper {

    AppBootstrapResult createAndAuthorize(Path repoRoot, String appName, String groupIds) throws Exception;
}
