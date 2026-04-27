package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.core.planner.IconPlanner;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 图标更新执行器
 *
 * Python 对应: update_worksheet_icons.py
 *
 * 职责:
 * - 根据 IconPlanner 的输出批量更新工作表图标
 * - 支持并发请求提高效率
 * - 支持 dry-run 模式预览结果
 */
public class IconCreator implements Executor<IconPlanner.Output, IconCreator.Result> {

    private static final Logger logger = LoggerFactory.getLogger(IconCreator.class);

    private final HapApiClient apiClient;
    private final ObjectMapper mapper;

    // API 端点
    private static final String APP_INFO_ENDPOINT = "https://api.mingdao.com/v3/app";
    private static final String EDIT_ICON_ENDPOINT = "https://www.mingdao.com/api/AppManagement/EditWorkSheetInfoForApp";

    private static final int DEFAULT_MAX_WORKERS = 16;

    public IconCreator(HapApiClient apiClient) {
        this.apiClient = apiClient;
        this.mapper = Jacksons.mapper();
    }

    @Override
    public String getName() {
        return "IconCreator";
    }

    @Override
    public Result execute(IconPlanner.Output plan) throws ExecutorException {
        return execute(plan, new ExecuteOptions());
    }

    @Override
    public Result execute(IconPlanner.Output plan, ExecuteOptions options) throws ExecutorException {
        List<IconPlanner.IconMapping> mappings = plan.getMappings();
        if (mappings == null || mappings.isEmpty()) {
            logger.info("⚠ 没有图标映射需要更新");
            return new Result(plan.getAppId(), List.of());
        }

        boolean dryRun = options.isDryRun();
        int maxWorkers = DEFAULT_MAX_WORKERS;

        logger.info("→ 开始更新 {} 个工作表图标{}", mappings.size(), dryRun ? " [DRY RUN]" : "");

        // 获取工作表元数据（分组信息等）
        Map<String, WorksheetMeta> metaMap = fetchWorksheetMeta();

        // 准备更新任务
        List<UpdateTask> tasks = new ArrayList<>();
        for (IconPlanner.IconMapping mapping : mappings) {
            WorksheetMeta meta = metaMap.get(mapping.getWorkSheetId());
            if (meta == null) {
                logger.warn("  ⚠ 未找到工作表: {}", mapping.getWorkSheetId());
                continue;
            }

            tasks.add(new UpdateTask(
                mapping.getWorkSheetId(),
                mapping.getWorkSheetName(),
                meta.getSectionId(),
                mapping.getIcon()
            ));
        }

        // 执行更新
        List<UpdateResult> results = new ArrayList<>();

        if (dryRun) {
            // 仅预览，不实际调用
            for (UpdateTask task : tasks) {
                results.add(new UpdateResult(task, true, null, "dry-run"));
            }
        } else {
            // 并发执行更新
            results = executeConcurrently(tasks, maxWorkers);
        }

        int success = (int) results.stream().filter(UpdateResult::isSuccess).count();
        int failed = results.size() - success;

        logger.info("✓ 图标更新完成: {} 成功, {} 失败", success, failed);

        return new Result(plan.getAppId(), results);
    }

    /**
     * 并发执行图标更新
     */
    private List<UpdateResult> executeConcurrently(List<UpdateTask> tasks, int maxWorkers) {
        List<UpdateResult> results = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(maxWorkers);

        try {
            List<Future<UpdateResult>> futures = new ArrayList<>();

            for (UpdateTask task : tasks) {
                Future<UpdateResult> future = executor.submit(() -> updateIcon(task));
                futures.add(future);
            }

            for (Future<UpdateResult> future : futures) {
                try {
                    UpdateResult result = future.get(30, TimeUnit.SECONDS);
                    results.add(result);
                    if (result.isSuccess()) {
                        logger.info("  ✓ 更新图标: {} -> {}", result.getWorkSheetName(), result.getIcon());
                    } else {
                        logger.error("  ✗ 更新失败: {} - {}", result.getWorkSheetName(), result.getError());
                    }
                } catch (Exception e) {
                    results.add(new UpdateResult(null, false, e.getMessage(), null));
                }
            }

        } finally {
            executor.shutdown();
        }

        return results;
    }

    /**
     * 更新单个工作表图标
     */
    private UpdateResult updateIcon(UpdateTask task) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("workSheetId", task.getWorkSheetId());
            payload.put("workSheetName", task.getWorkSheetName());
            payload.put("appSectionId", task.getSectionId());
            payload.put("icon", task.getIcon());

            // 注意：这个 API 需要通过 Web API 调用，使用用户认证
            // 这里假设 apiClient 已经配置了用户认证
            JsonNode response = apiClient.post(EDIT_ICON_ENDPOINT, payload);

            // 检查响应状态
            boolean success = response.path("success").asBoolean(false);
            if (!success) {
                String msg = response.path("message").asText("Unknown error");
                return new UpdateResult(task, false, msg, null);
            }

            return new UpdateResult(task, true, null, task.getIcon());

        } catch (Exception e) {
            return new UpdateResult(task, false, e.getMessage(), null);
        }
    }

    /**
     * 获取应用中的工作表元数据
     */
    private Map<String, WorksheetMeta> fetchWorksheetMeta() throws ExecutorException {
        try {
            // 通过 V3 API 获取应用信息
            JsonNode response = apiClient.get("/v3/app");
            Map<String, WorksheetMeta> metaMap = new HashMap<>();

            JsonNode dataNode = response.path("data");
            JsonNode sectionsNode = dataNode.path("sections");

            if (sectionsNode.isArray()) {
                for (JsonNode section : sectionsNode) {
                    String sectionId = section.path("id").asText("");
                    String sectionName = section.path("name").asText("");

                    walkSection(section, sectionId, sectionName, metaMap);
                }
            }

            return metaMap;

        } catch (Exception e) {
            throw new ExecutorException(getName(), "获取工作表元数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 递归遍历分组结构
     */
    private void walkSection(JsonNode section, String sectionId, String sectionName,
                             Map<String, WorksheetMeta> metaMap) {
        // 处理分组中的工作表
        JsonNode itemsNode = section.path("items");
        if (itemsNode.isArray()) {
            for (JsonNode item : itemsNode) {
                // type=0 表示工作表
                if (item.path("type").asInt(-1) == 0) {
                    String wsId = item.path("id").asText("").trim();
                    String wsName = item.path("name").asText("");

                    if (!wsId.isEmpty()) {
                        metaMap.put(wsId, new WorksheetMeta(wsId, wsName, sectionId, sectionName));
                    }
                }
            }
        }

        // 递归处理子分组
        JsonNode childrenNode = section.path("childSections");
        if (childrenNode.isArray()) {
            for (JsonNode child : childrenNode) {
                String childId = child.path("id").asText(sectionId);
                String childName = child.path("name").asText(sectionName);
                walkSection(child, childId, childName, metaMap);
            }
        }
    }

    @Override
    public boolean rollback(Result result) throws ExecutorException {
        logger.info("→ 回滚图标更新...");
        // 图标更新通常不回滚，而是记录变更
        List<UpdateResult> results = result.getResults();

        logger.info("  以下工作表图标已更新，如需回滚请手动修改:");
        for (UpdateResult r : results) {
            if (r.isSuccess() && r.getIcon() != null) {
                logger.info("    - {} (图标: {})", r.getWorkSheetName(), r.getIcon());
            }
        }
        return true;
    }

    // ========== 内部类 ==========

    private static class WorksheetMeta {
        private final String workSheetId;
        private final String workSheetName;
        private final String sectionId;
        private final String sectionName;

        WorksheetMeta(String workSheetId, String workSheetName,
                     String sectionId, String sectionName) {
            this.workSheetId = workSheetId;
            this.workSheetName = workSheetName;
            this.sectionId = sectionId;
            this.sectionName = sectionName;
        }

        String getWorkSheetId() { return workSheetId; }
        String getWorkSheetName() { return workSheetName; }
        String getSectionId() { return sectionId; }
        String getSectionName() { return sectionName; }
    }

    private static class UpdateTask {
        private final String workSheetId;
        private final String workSheetName;
        private final String sectionId;
        private final String icon;

        UpdateTask(String workSheetId, String workSheetName,
                  String sectionId, String icon) {
            this.workSheetId = workSheetId;
            this.workSheetName = workSheetName;
            this.sectionId = sectionId;
            this.icon = icon;
        }

        String getWorkSheetId() { return workSheetId; }
        String getWorkSheetName() { return workSheetName; }
        String getSectionId() { return sectionId; }
        String getIcon() { return icon; }
    }

    // ========== 结果类 ==========

    public static class Result {
        private final String appId;
        private final List<UpdateResult> results;

        public Result(String appId, List<UpdateResult> results) {
            this.appId = appId;
            this.results = results != null ? results : List.of();
        }

        public String getAppId() { return appId; }
        public List<UpdateResult> getResults() { return results; }

        public boolean isSuccess() {
            return results.stream().allMatch(UpdateResult::isSuccess);
        }

        public int getSuccessCount() {
            return (int) results.stream().filter(UpdateResult::isSuccess).count();
        }

        public int getFailedCount() {
            return results.size() - getSuccessCount();
        }
    }

    public static class UpdateResult {
        private final String workSheetId;
        private final String workSheetName;
        private final String icon;
        private final boolean success;
        private final String error;

        UpdateResult(UpdateTask task, boolean success, String error, String icon) {
            this.workSheetId = task != null ? task.getWorkSheetId() : null;
            this.workSheetName = task != null ? task.getWorkSheetName() : null;
            this.icon = icon;
            this.success = success;
            this.error = error;
        }

        public String getWorkSheetId() { return workSheetId; }
        public String getWorkSheetName() { return workSheetName; }
        public String getIcon() { return icon; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }
    }
}
