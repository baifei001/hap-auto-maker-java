package com.hap.automaker.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.config.Jacksons;

/**
 * 断点续传管理器
 *
 * 每个 Wave 完成后保存 checkpoint.json，
 * 支持 --resume 参数跳过已完成的 wave。
 *
 * Checkpoint 文件位置: data/outputs/execution_runs/checkpoint.json
 */
public class CheckpointManager {

    private static final String CHECKPOINT_FILE = "checkpoint.json";

    private final Path checkpointDir;

    public CheckpointManager(Path repoRoot) {
        this.checkpointDir = repoRoot.resolve("data").resolve("outputs").resolve("execution_runs");
    }

    /**
     * 保存 checkpoint
     *
     * @param appId 应用 ID
     * @param completedWaves 已完成的 wave 列表（如 ["create_app", "worksheets_plan", "worksheets_create"]）
     * @param context 当前 PipelineContext
     */
    public void saveCheckpoint(String appId, List<String> completedWaves, PipelineContext context) throws Exception {
        Files.createDirectories(checkpointDir);

        ObjectNode checkpoint = Jacksons.mapper().createObjectNode();
        checkpoint.put("schema_version", "checkpoint_v1");
        checkpoint.put("created_at", OffsetDateTime.now().toString());
        checkpoint.put("app_id", appId != null ? appId : "");
        checkpoint.put("spec_path", context.specPath != null ? context.specPath.toString() : "");
        checkpoint.put("dry_run", context.dryRun);
        checkpoint.put("fail_fast", context.failFast);

        // 已完成的 wave
        ArrayNode wavesArray = checkpoint.putArray("completed_waves");
        for (String wave : completedWaves) {
            wavesArray.add(wave);
        }

        // 中间产物路径
        ObjectNode artifacts = checkpoint.putObject("artifacts");
        if (context.appAuthJson != null) artifacts.put("appAuthJson", context.appAuthJson);
        if (context.worksheetPlanJson != null) artifacts.put("worksheetPlanJson", context.worksheetPlanJson);
        if (context.worksheetCreateResultJson != null) artifacts.put("worksheetCreateResultJson", context.worksheetCreateResultJson);
        if (context.viewResultJson != null) artifacts.put("viewResultJson", context.viewResultJson);
        if (context.pageResultJson != null) artifacts.put("pageResultJson", context.pageResultJson);

        // 已完成步骤的结果
        ArrayNode stepsArray = checkpoint.putArray("steps");
        for (StepResult step : context.steps) {
            ObjectNode stepNode = stepsArray.addObject();
            stepNode.put("stepId", step.stepId);
            stepNode.put("stepKey", step.stepKey);
            stepNode.put("title", step.title);
            stepNode.put("ok", step.ok);
            stepNode.put("skipped", step.skipped);
            if (step.reason != null) stepNode.put("reason", step.reason);
        }

        Path checkpointPath = checkpointDir.resolve(CHECKPOINT_FILE);
        Jacksons.mapper().writerWithDefaultPrettyPrinter().writeValue(checkpointPath.toFile(), checkpoint);
    }

    /**
     * 加载最新的 checkpoint
     *
     * @return Checkpoint 数据，如果不存在返回 null
     */
    public Checkpoint loadCheckpoint() throws Exception {
        Path checkpointPath = checkpointDir.resolve(CHECKPOINT_FILE);
        if (!Files.exists(checkpointPath)) {
            return null;
        }

        JsonNode root = Jacksons.mapper().readTree(checkpointPath.toFile());

        String appId = root.path("app_id").asText("");
        String specPath = root.path("spec_path").asText("");
        boolean dryRun = root.path("dry_run").asBoolean(false);
        boolean failFast = root.path("fail_fast").asBoolean(true);

        List<String> completedWaves = new ArrayList<>();
        JsonNode wavesNode = root.path("completed_waves");
        if (wavesNode.isArray()) {
            for (JsonNode wave : wavesNode) {
                completedWaves.add(wave.asText());
            }
        }

        Map<String, String> artifacts = new LinkedHashMap<>();
        JsonNode artifactsNode = root.path("artifacts");
        if (artifactsNode.isObject()) {
            artifactsNode.fields().forEachRemaining(entry ->
                artifacts.put(entry.getKey(), entry.getValue().asText()));
        }

        List<StepResult> steps = new ArrayList<>();
        JsonNode stepsNode = root.path("steps");
        if (stepsNode.isArray()) {
            for (JsonNode stepNode : stepsNode) {
                StepResult step = new StepResult();
                step.stepId = stepNode.path("stepId").asInt(0);
                step.stepKey = stepNode.path("stepKey").asText("");
                step.title = stepNode.path("title").asText("");
                step.ok = stepNode.path("ok").asBoolean(false);
                step.skipped = stepNode.path("skipped").asBoolean(false);
                step.reason = stepNode.path("reason").asText(null);
                steps.add(step);
            }
        }

        return new Checkpoint(appId, specPath, dryRun, failFast, completedWaves, artifacts, steps);
    }

    /**
     * 清除 checkpoint
     */
    public void clearCheckpoint() throws Exception {
        Path checkpointPath = checkpointDir.resolve(CHECKPOINT_FILE);
        Files.deleteIfExists(checkpointPath);
    }

    /**
     * 检查某个 wave 是否已完成
     */
    public boolean isWaveCompleted(String waveKey) throws Exception {
        Checkpoint checkpoint = loadCheckpoint();
        if (checkpoint == null) return false;
        return checkpoint.completedWaves().contains(waveKey);
    }

    /**
     * Checkpoint 数据记录
     */
    public record Checkpoint(
        String appId,
        String specPath,
        boolean dryRun,
        boolean failFast,
        List<String> completedWaves,
        Map<String, String> artifacts,
        List<StepResult> steps
    ) {}
}
