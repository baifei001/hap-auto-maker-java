package com.hap.automaker.ai;

import com.hap.automaker.model.AiAuthConfig;

public interface AiTextClient {

    String generateJson(String prompt, AiAuthConfig config) throws Exception;
}
