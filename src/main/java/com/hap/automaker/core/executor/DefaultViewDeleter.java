package com.hap.automaker.core.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.api.HapApiClient;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * 默认视图删除执行器
 *
 * Python 对应: delete_default_views.py
 *
 * 职责:
 * - 删除工作表中名称为"全部"或"视图"的默认视图
 * - 支持删除所有视图（--all-views 模式）
 * - 默认模式下只删除系统默认视图名称，且只在存在其他视图时删除
 */
public class DefaultViewDeleter implements Executor<DefaultViewDeleter.Input, DefaultViewDeleter.Output> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultViewDeleter.class);

    private final HapApiClient apiClient;
    private final ExecutorService executor;

    // 系统默认视图的常见名称集合（从 Python i18n.py）
    private static final Set<String> DEFAULT_VIEW_NAMES = Set.of(
        "全部", "视图",  // 中文
        "All", "View", "Views"  // 英文
    );

    public DefaultViewDeleter(HapApiClient apiClient, int maxConcurrency) {
        this.apiClient = apiClient;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    @Override
    public String getName() {
        return "DefaultViewDeleter";
    }

    @Override
    public Output execute(Input input) throws ExecutorException {
        List<ViewDeletionResult> results = new ArrayList<>();

        try {
            // 获取应用所有工作表
            List<WorksheetInfo> worksheets = fetchWorksheets(input.getAppId());

            List<Callable<List<ViewDeletionResult>>> tasks = new ArrayList<>();

            for (WorksheetInfo ws : worksheets) {
                tasks.add(() -> processWorksheet(ws, input));
            }

            List<Future<List<ViewDeletionResult>>> futures = executor.invokeAll(tasks);
            for (Future<List<ViewDeletionResult>> future : futures) {
                try {
                    results.addAll(future.get());
                } catch (ExecutionException e) {
                    logger.error("Failed to process worksheet: {}", e.getCause().getMessage());
                }
            }

            long deletedCount = results.stream().filter(ViewDeletionResult::isDeleted).count();
            long failedCount = results.stream().filter(r -> !r.isDeleted() && r.getErrorMessage() != null).count();

            logger.info("✓ 默认视图删除完成: 删除={}, 失败={}", deletedCount, failedCount);

            return new Output(true, results, (int) deletedCount, null);

        } catch (Exception e) {
            throw new ExecutorException(getName(), "Failed to delete default views", e);
        }
    }

    private List<WorksheetInfo> fetchWorksheets(String appId) throws Exception {
        List<WorksheetInfo> worksheets = new ArrayList<>();

        JsonNode response = apiClient.get("/v3/app");
        JsonNode sections = response.path("data").path("sections");

        if (sections.isArray()) {
            for (JsonNode section : sections) {
                walkSection(section, worksheets);
            }
        }

        return worksheets;
    }

    private void walkSection(JsonNode section, List<WorksheetInfo> worksheets) {
        JsonNode items = section.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (item.path("type").asInt(-1) == 0) {
                    worksheets.add(new WorksheetInfo(
                        item.path("id").asText(),
                        item.path("name").asText()
                    ));
                }
            }
        }

        JsonNode childSections = section.path("childSections");
        if (childSections.isArray()) {
            for (JsonNode child : childSections) {
                walkSection(child, worksheets);
            }
        }
    }

    private List<ViewDeletionResult> processWorksheet(WorksheetInfo ws, Input input) throws Exception {
        List<ViewDeletionResult> results = new ArrayList<>();

        // 获取工作表的视图列表
        JsonNode response = apiClient.get("/v3/app/worksheets/" + ws.getId());
        JsonNode views = response.path("data").path("views");

        if (!views.isArray() || views.size() == 0) {
            return results;
        }

        List<ViewInfo> allViews = new ArrayList<>();
        for (JsonNode view : views) {
            allViews.add(new ViewInfo(
                view.path("id").asText(),
                view.path("name").asText()
            ));
        }

        // 确定要删除的视图
        List<ViewInfo> targetViews;
        if (input.isAllViews()) {
            // 删除所有视图（至少保留一个）
            targetViews = allViews.size() > 1 ? allViews.subList(0, allViews.size() - 1) : List.of();
        } else {
            // 只删除默认视图名称，且只在存在其他视图时删除
            List<ViewInfo> defaultViews = allViews.stream()
                .filter(v -> DEFAULT_VIEW_NAMES.contains(v.getName()))
                .toList();
            List<ViewInfo> nonDefaultViews = allViews.stream()
                .filter(v -> !DEFAULT_VIEW_NAMES.contains(v.getName()))
                .toList();

            targetViews = nonDefaultViews.isEmpty() ? List.of() : defaultViews;
        }

        for (ViewInfo view : targetViews) {
            if (input.isDryRun()) {
                logger.info("[DryRun] 将删除视图「{}」({}) 来自工作表《{}》", view.getName(), view.getId(), ws.getName());
                results.add(new ViewDeletionResult(ws.getId(), ws.getName(), view.getId(), view.getName(), true, null));
                continue;
            }

            try {
                deleteView(input.getAppId(), ws.getId(), view.getId());
                logger.info("  ✓ 已删除视图「{}」({}) 来自工作表《{}》", view.getName(), view.getId(), ws.getName());
                results.add(new ViewDeletionResult(ws.getId(), ws.getName(), view.getId(), view.getName(), true, null));
            } catch (Exception e) {
                logger.error("  ✗ 删除失败「{}」({}): {}", view.getName(), view.getId(), e.getMessage());
                results.add(new ViewDeletionResult(ws.getId(), ws.getName(), view.getId(), view.getName(), false, e.getMessage()));
            }
        }

        return results;
    }

    private void deleteView(String appId, String worksheetId, String viewId) throws Exception {
        // 调用 API 删除视图
        // HapApiClient 需要配置认证信息，假设已通过外部配置
        apiClient.deleteWorksheetView(viewId);
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ========== 内部数据类 ==========
    private static class WorksheetInfo {
        private final String id;
        private final String name;

        WorksheetInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }

        String getId() { return id; }
        String getName() { return name; }
    }

    private static class ViewInfo {
        private final String id;
        private final String name;

        ViewInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }

        String getId() { return id; }
        String getName() { return name; }
    }

    // ========== 结果数据类 ==========
    public static class ViewDeletionResult {
        private final String worksheetId;
        private final String worksheetName;
        private final String viewId;
        private final String viewName;
        private final boolean deleted;
        private final String errorMessage;

        public ViewDeletionResult(String worksheetId, String worksheetName,
                                  String viewId, String viewName,
                                  boolean deleted, String errorMessage) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.viewId = viewId;
            this.viewName = viewName;
            this.deleted = deleted;
            this.errorMessage = errorMessage;
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public String getViewId() { return viewId; }
        public String getViewName() { return viewName; }
        public boolean isDeleted() { return deleted; }
        public String getErrorMessage() { return errorMessage; }
    }

    // ========== 输入输出类 ==========
    public static class Input {
        private final String appId;
        private final boolean dryRun;
        private final boolean allViews;

        public Input(String appId, boolean dryRun, boolean allViews) {
            this.appId = appId;
            this.dryRun = dryRun;
            this.allViews = allViews;
        }

        public String getAppId() { return appId; }
        public boolean isDryRun() { return dryRun; }
        public boolean isAllViews() { return allViews; }
    }

    public static class Output {
        private final boolean success;
        private final List<ViewDeletionResult> results;
        private final int deletedCount;
        private final String errorMessage;

        public Output(boolean success, List<ViewDeletionResult> results, int deletedCount, String errorMessage) {
            this.success = success;
            this.results = results != null ? results : List.of();
            this.deletedCount = deletedCount;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public List<ViewDeletionResult> getResults() { return results; }
        public int getDeletedCount() { return deletedCount; }
        public String getErrorMessage() { return errorMessage; }
    }
}
