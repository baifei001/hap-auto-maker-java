package com.hap.automaker.pipeline;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StepResult {

    public int stepId;
    public String stepKey;
    public String title;
    public boolean ok;
    public boolean skipped;
    public String reason;
    public OffsetDateTime startedAt;
    public OffsetDateTime endedAt;
    public String command;
    public String stdout;
    public String stderr;
    public Map<String, Object> artifacts = new LinkedHashMap<>();
}
