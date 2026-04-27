package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.planner.LayoutPlanner;
import com.hap.automaker.core.executor.ExecuteOptions;
import com.hap.automaker.core.executor.Executor;
import com.hap.automaker.core.executor.ExecutorException;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * 布局创建执行器
 *
 * Python 对应: executors/pipeline_worksheet_layout_v2.py
 *
 * 职责:
 * - 根据 LayoutPlanner 的规划应用工作表布局
 * - 配置字段显示顺序和分组
 * - 调用 Web API 保存布局配置
 *
 * API 调用:
 * - POST /api/Worksheet/UpdateWorksheetControls - 更新工作表字段配置
 */
public class LayoutCreator implements Executor<LayoutPlanner.Output, LayoutCreator.Result> {

    private static final Logger logger = LoggerFactory.getLogger(LayoutCreator.class);

    private final HapApiClient apiClient;
    private final ObjectMapper mapper;

    private static final int DEFAULT_MAX_WORKERS = 4;

    public LayoutCreator(HapApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = Jacksons.mapper();
    }

    @Override
    public String getName() {
        return "LayoutCreator";
    }

    @Override
    public Result execute(LayoutPlanner.Output plan) throws ExecutorException {
        return execute(plan, new ExecuteOptions());
    }

    @Override
    public Result execute(LayoutPlanner.Output plan, ExecuteOptions options) throws ExecutorException {
        List<LayoutPlanner.WorksheetLayout> layouts = plan.getLayouts();
        if (layouts == null || layouts.isEmpty()) {
            logger.info("⚠ 没有布局需要应用");
            return new Result(List.of());
        }

        boolean dryRun = options.isDryRun();
        logger.info("→ 开始应用 {} 个工作表布局{}", layouts.size(), dryRun ? " [DRY RUN]" : "");

        List<LayoutUpdateResult> results = new ArrayList<>();

        for (LayoutPlanner.WorksheetLayout layout : layouts) {
            try {
                if (dryRun) {
                    results.add(new LayoutUpdateResult(
                        layout.getWorksheetId(),
                        layout.getWorksheetName(),
                        true,
                        "dry-run",
                        layout.getLayout().getGroups().size()
                    ));
                    logger.info("  [DryRun] Would update layout for: {}", layout.getWorksheetName());
                } else {
                    applyLayout(layout);
                    results.add(new LayoutUpdateResult(
                        layout.getWorksheetId(),
                        layout.getWorksheetName(),
                        true,
                        null,
                        layout.getLayout().getGroups().size()
                    ));
                    logger.info("  ✓ 布局应用成功: {}", layout.getWorksheetName());
                }
            } catch (Exception e) {
                results.add(new LayoutUpdateResult(
                    layout.getWorksheetId(),
                    layout.getWorksheetName(),
                    false,
                    e.getMessage(),
                    0
                ));
                logger.error("  ✗ 布局应用失败: {} - {}", layout.getWorksheetName(), e.getMessage());

                if (options.isFailFast()) {
                    throw new ExecutorException(getName(), "Layout update failed for " + layout.getWorksheetName(), e);
                }
            }
        }

        int success = (int) results.stream().filter(LayoutUpdateResult::isSuccess).count();
        int failed = results.size() - success;
        logger.info("✓ 布局应用完成: {} 成功, {} 失败", success, failed);

        return new Result(results);
    }

    /**
     * 应用工作表布局
     */
    private void applyLayout(LayoutPlanner.WorksheetLayout layout) throws Exception {
        String worksheetId = layout.getWorksheetId();
        LayoutPlanner.FieldLayout fieldLayout = layout.getLayout();

        // 构建字段配置
        List<Map<String, Object>> controls = buildControlsConfig(fieldLayout);

        // 调用 API 更新工作表字段配置
        ObjectNode payload = mapper.createObjectNode();
        payload.put("worksheetId", worksheetId);

        ArrayNode controlsArray = payload.putArray("controls");
        for (Map<String, Object> control : controls) {
            ObjectNode controlNode = controlsArray.addObject();
            control.forEach((key, value) -> {
                if (value instanceof String) {
                    controlNode.put(key, (String) value);
                } else if (value instanceof Integer) {
                    controlNode.put(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    controlNode.put(key, (Boolean) value);
                }
            });
        }

        // 调用更新工作表控件 API
        JsonNode response = apiClient.post("/api/Worksheet/UpdateWorksheetControls", payload);

        // 检查响应
        boolean success = response.path("success").asBoolean(false);
        if (!success) {
            String message = response.path("message").asText("Unknown error");
            throw new Exception(message);
        }
    }

    /**
     * 构建字段配置
     */
    private List<Map<String, Object>> buildControlsConfig(LayoutPlanner.FieldLayout layout) {
        List<Map<String, Object>> controls = new ArrayList<>();
        int row = 0;

        for (LayoutPlanner.FieldGroup group : layout.getGroups()) {
            // 如果有分组标题，添加分割线
            if (group.getTitle() != null && !group.getTitle().isEmpty()) {
                Map<String, Object> divider = new HashMap<>();
                divider.put("type", 22); // 分割线类型
                divider.put("value", group.getTitle());
                divider.put("row", row++);
                controls.add(divider);
            }

            // 添加分组中的字段
            for (LayoutPlanner.FieldInfo field : group.getFields()) {
                Map<String, Object> control = new HashMap<>();
                control.put("controlId", field.getControlId());
                control.put("controlName", field.getControlName());
                control.put("type", mapFieldType(field.getType()));
                control.put("row", row++);
                controls.add(control);
            }
        }

        return controls;
    }

    /**
     * 映射字段类型到 API 类型值
     */
    private int mapFieldType(String type) {
        return switch (type) {
            case "Text" -> 2;
            case "Textarea" -> 3;
            case "Number" -> 6;
            case "Money" -> 8;
            case "Date" -> 16;
            case "DateTime" -> 17;
            case "SingleSelect" -> 9;
            case "MultipleSelect" -> 10;
            case "Dropdown" -> 11;
            case "Checkbox" -> 24;
            case "PhoneNumber" -> 5;
            case "Email" -> 7;
            case "Link" -> 17;
            case "Attachment" -> 14;
            case "Area" -> 29;
            case "Location" -> 40;
            case "RichText" -> 21;
            case "Relation" -> 20;
            case "SubForm" -> 34;
            case "Divider" -> 22;
            default -> 2; // 默认为文本类型
        };
    }

    @Override
    public boolean rollback(Result result) throws ExecutorException {
        logger.info("→ 回滚布局更新...");
        // 布局更新通常不回滚，而是记录变更
        List<LayoutUpdateResult> results = result.getResults();

        logger.info("  以下工作表布局已更新，如需回滚请手动修改:");
        for (LayoutUpdateResult r : results) {
            if (r.isSuccess()) {
                logger.info("    - {} (分组数: {})", r.getWorksheetName(), r.getGroupCount());
            }
        }
        return true;
    }

    // ========== 结果类 ==========

    public static class Result {
        private final List<LayoutUpdateResult> results;

        public Result(List<LayoutUpdateResult> results) {
            this.results = results != null ? results : List.of();
        }

        public List<LayoutUpdateResult> getResults() { return results; }

        public boolean isSuccess() {
            return results.stream().allMatch(LayoutUpdateResult::isSuccess);
        }

        public int getSuccessCount() {
            return (int) results.stream().filter(LayoutUpdateResult::isSuccess).count();
        }

        public int getFailedCount() {
            return results.size() - getSuccessCount();
        }
    }

    public static class LayoutUpdateResult {
        private final String worksheetId;
        private final String worksheetName;
        private final boolean success;
        private final String error;
        private final int groupCount;

        public LayoutUpdateResult(String worksheetId, String worksheetName,
                                   boolean success, String error, int groupCount) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.success = success;
            this.error = error;
            this.groupCount = groupCount;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public int getGroupCount() { return groupCount; }
    }
}
