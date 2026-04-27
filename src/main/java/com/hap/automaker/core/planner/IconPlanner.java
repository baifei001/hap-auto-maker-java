package com.hap.automaker.core.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图标规划器
 *
 * Python 对应: match_worksheet_icons_gemini.py
 *
 * 职责:
 * - 根据工作表名称从图标库中匹配最合适的图标
 * - AI 智能推荐图标，使图标与工作表用途相符
 * - 支持回退到默认图标
 */
public class IconPlanner implements Planner<IconPlanner.Input, IconPlanner.Output> {

    private final AiTextClient aiClient;

    private static final int MAX_RETRIES = 3;
    private static final String DEFAULT_ICON = "sys_8_4_folder";
    private static final Logger logger = LoggerFactory.getLogger(IconPlanner.class);

    // 图标库缓存
    private List<String> iconCache;

    public IconPlanner(AiTextClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public String getName() {
        return "IconPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            List<String> iconNames = loadIconLibrary();
            if (iconNames.isEmpty()) {
                throw new PlanningException(getName(), "图标库为空");
            }

            List<WorksheetInfo> worksheets = input.getWorksheets();
            if (worksheets == null || worksheets.isEmpty()) {
                logger.warn("⚠ 没有工作表需要匹配图标");
                return new Output(input.getAppId(), List.of());
            }

            logger.info("→ 开始为 {} 个工作表匹配图标", worksheets.size());

            // 构建 AI prompt
            String prompt = buildPrompt(input, iconNames);
            String responseJson = callAiWithRetry(prompt);

            // 解析 AI 响应
            List<IconMapping> mappings = parseMappings(responseJson, worksheets, iconNames);

            // 检查是否有遗漏的工作表，使用默认图标填充
            List<IconMapping> completeMappings = fillMissingMappings(mappings, worksheets, iconNames);

            logger.info("✓ 图标规划完成: {} 个映射", completeMappings.size());

            return new Output(input.getAppId(), completeMappings);

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan icons", e);
        }
    }

    /**
     * 加载图标库
     */
    private List<String> loadIconLibrary() {
        if (iconCache != null) {
            return iconCache;
        }

        List<String> icons = new ArrayList<>();

        try (InputStream is = getClass().getResourceAsStream("/icons.json")) {
            if (is != null) {
                JsonNode root = Jacksons.mapper().readTree(is);
                collectIconNames(root, icons);
            }
        } catch (Exception e) {
            logger.warn("[warn] 无法加载图标库: {}", e.getMessage());
        }

        // 如果无法加载内置图标库，使用一些常用图标作为回退
        if (icons.isEmpty()) {
            icons = getDefaultIcons();
        }

        iconCache = icons;
        return icons;
    }

    /**
     * 递归收集图标名称
     */
    private void collectIconNames(JsonNode node, List<String> result) {
        if (node.isObject()) {
            JsonNode fileName = node.get("fileName");
            if (fileName != null && fileName.isTextual()) {
                String name = fileName.asText().trim();
                if (!name.isEmpty()) {
                    result.add(name);
                }
            }
            node.fields().forEachRemaining(entry -> collectIconNames(entry.getValue(), result));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectIconNames(item, result);
            }
        }
    }

    /**
     * 默认图标列表（回退使用）
     */
    private List<String> getDefaultIcons() {
        return List.of(
            "sys_8_4_folder",      // 文件夹
            "sys_6_1_user_group",  // 用户组
            "sys_5_1_document",    // 文档
            "sys_4_1_home",        // 主页
            "sys_3_1_calendar",    // 日历
            "sys_2_1_chart",       // 图表
            "sys_1_1_settings"     // 设置
        );
    }

    /**
     * 构建 AI 提示词
     */
    private String buildPrompt(Input input, List<String> iconNames) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是企业应用 UI 设计助手。请根据工作表名称从图标库中选择最匹配的图标。\n\n");

        sb.append("工作表列表:\n");
        for (WorksheetInfo ws : input.getWorksheets()) {
            sb.append("- ").append(ws.getWorkSheetId()).append(": ").append(ws.getWorkSheetName()).append("\n");
        }
        sb.append("\n");

        sb.append("图标库（fileName）:\n");
        // 限制图标数量，避免 prompt 过长
        int maxIcons = Math.min(iconNames.size(), 200);
        for (int i = 0; i < maxIcons; i++) {
            if (i > 0) sb.append(", ");
            sb.append(iconNames.get(i));
        }
        if (iconNames.size() > maxIcons) {
            sb.append("... (共 ").append(iconNames.size()).append(" 个图标)");
        }
        sb.append("\n\n");

        sb.append("请只输出 JSON，对每个工作表都给出一个 icon，格式严格如下:\n");
        sb.append("{\n");
        sb.append("  \"mappings\": [\n");
        sb.append("    {\n");
        sb.append("      \"workSheetId\": \"xxx\",\n");
        sb.append("      \"workSheetName\": \"xxx\",\n");
        sb.append("      \"icon\": \"sys_xxx\",\n");
        sb.append("      \"reason\": \"简短理由\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("\n");

        sb.append("约束:\n");
        sb.append("1. icon 必须来自图标库\n");
        sb.append("2. 每个 workSheetId 只出现一次，且必须全部覆盖\n");
        sb.append("3. 不要输出 markdown，不要输出额外文本\n");

        return sb.toString();
    }

    /**
     * 解析 AI 响应中的图标映射
     */
    private List<IconMapping> parseMappings(String responseJson,
                                             List<WorksheetInfo> worksheets,
                                             List<String> validIcons) {
        List<IconMapping> mappings = new ArrayList<>();
        Map<String, String> worksheetNames = new HashMap<>();
        for (WorksheetInfo ws : worksheets) {
            worksheetNames.put(ws.getWorkSheetId(), ws.getWorkSheetName());
        }

        try {
            JsonNode root = Jacksons.mapper().readTree(responseJson);
            JsonNode mappingsNode = root.path("mappings");

            if (mappingsNode.isArray()) {
                for (JsonNode m : mappingsNode) {
                    String wsId = m.path("workSheetId").asText("").trim();
                    String wsName = m.path("workSheetName").asText("").trim();
                    String icon = m.path("icon").asText("").trim();
                    String reason = m.path("reason").asText("").trim();

                    // 验证图标是否有效
                    if (!validIcons.contains(icon)) {
                        logger.warn("[warn] 无效图标 '{}'，使用默认图标", icon);
                        icon = DEFAULT_ICON;
                    }

                    // 使用 worksheetNames 中的名称（AI 可能返回的名称不准确）
                    String correctName = worksheetNames.getOrDefault(wsId, wsName);

                    if (!wsId.isEmpty() && !icon.isEmpty()) {
                        mappings.add(new IconMapping(wsId, correctName, icon, reason));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[error] 解析图标映射失败: {}", e.getMessage());
        }

        return mappings;
    }

    /**
     * 填充缺失的映射
     */
    private List<IconMapping> fillMissingMappings(List<IconMapping> existing,
                                                   List<WorksheetInfo> worksheets,
                                                   List<String> validIcons) {
        Map<String, IconMapping> mappedById = new HashMap<>();
        for (IconMapping m : existing) {
            mappedById.put(m.getWorkSheetId(), m);
        }

        List<IconMapping> complete = new ArrayList<>();
        String fallbackIcon = validIcons.contains(DEFAULT_ICON) ? DEFAULT_ICON : validIcons.get(0);

        for (WorksheetInfo ws : worksheets) {
            IconMapping mapping = mappedById.get(ws.getWorkSheetId());
            if (mapping != null) {
                complete.add(mapping);
            } else {
                // 为未映射的工作表添加默认图标
                complete.add(new IconMapping(ws.getWorkSheetId(), ws.getWorkSheetName(),
                                             fallbackIcon, "fallback"));
                logger.info("  ↷ 为 '{}' 使用默认图标", ws.getWorkSheetName());
            }
        }

        return complete;
    }

    private String callAiWithRetry(String prompt) throws Exception {
        Exception lastError = null;

        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                AiAuthConfig config = ConfigLoader.loadAiConfig();
                return aiClient.generateJson(prompt, config);
            } catch (Exception e) {
                lastError = e;
                logger.error("AI call failed (attempt {}/{}): {}", i + 1, MAX_RETRIES, e.getMessage());
                if (i < MAX_RETRIES - 1) {
                    Thread.sleep(1000 * (i + 1));
                }
            }
        }

        throw new PlanningException(getName(), "AI call failed after " + MAX_RETRIES + " retries", lastError);
    }

    // ========== 输入类 ==========
    public static class Input {
        private final String appId;
        private final List<WorksheetInfo> worksheets;

        public Input(String appId, List<WorksheetInfo> worksheets) {
            this.appId = appId;
            this.worksheets = worksheets != null ? worksheets : List.of();
        }

        public String getAppId() { return appId; }
        public List<WorksheetInfo> getWorksheets() { return worksheets; }
    }

    public static class WorksheetInfo {
        private final String workSheetId;
        private final String workSheetName;

        public WorksheetInfo(String workSheetId, String workSheetName) {
            this.workSheetId = workSheetId;
            this.workSheetName = workSheetName;
        }

        public String getWorkSheetId() { return workSheetId; }
        public String getWorkSheetName() { return workSheetName; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final String appId;
        private final List<IconMapping> mappings;

        public Output(String appId, List<IconMapping> mappings) {
            this.appId = appId;
            this.mappings = mappings != null ? mappings : List.of();
        }

        public String getAppId() { return appId; }
        public List<IconMapping> getMappings() { return mappings; }
    }

    public static class IconMapping {
        private final String workSheetId;
        private final String workSheetName;
        private final String icon;
        private final String reason;

        public IconMapping(String workSheetId, String workSheetName,
                          String icon, String reason) {
            this.workSheetId = workSheetId;
            this.workSheetName = workSheetName;
            this.icon = icon;
            this.reason = reason;
        }

        public String getWorkSheetId() { return workSheetId; }
        public String getWorkSheetName() { return workSheetName; }
        public String getIcon() { return icon; }
        public String getReason() { return reason; }
    }
}
