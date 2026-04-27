package com.hap.automaker.pipeline;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PipelineContext {

    public Path specPath;
    public boolean dryRun;
    public boolean failFast;
    public String language;
    public String appId;
    public String appAuthJson;
    public String worksheetPlanJson;
    public String worksheetCreateResultJson;
    public String viewResultJson;
    public String pageResultJson;
    public final List<StepResult> steps = new ArrayList<>();

    public Map<String, Object> buildReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", "hap_requirement_v1_execution_report");
        report.put("created_at", OffsetDateTime.now().toString());
        report.put("spec_json", specPath == null ? "" : specPath.toString());
        report.put("dry_run", dryRun);
        report.put("fail_fast", failFast);
        report.put("app_id", appId);
        report.put("app_auth_json", appAuthJson);
        report.put("worksheet_plan_json", worksheetPlanJson);
        report.put("worksheet_create_result_json", worksheetCreateResultJson);
        report.put("view_result_json", viewResultJson);
        report.put("page_result_json", pageResultJson);
        report.put("steps", steps);
        return report;
    }
}
