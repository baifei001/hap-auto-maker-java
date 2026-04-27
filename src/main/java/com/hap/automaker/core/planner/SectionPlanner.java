package com.hap.automaker.core.planner;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.ConfigLoader;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * 分组规划器
 *
 * Python 对应: plan_app_sections_gemini.py
 *
 * 职责:
 * - 根据工作表列表规划分组结构
 * - 将工作表按业务领域分组（2-9个分组）
 * - 第一个分组固定为"仪表盘"，用于统计页面和对话机器人
 *
 * 分组原则:
 * - 第一个分组必须是"仪表盘"，worksheets 为空数组
 * - 同一业务领域的工作表放一组
 * - 每个分组 2-12 张工作表
 * - 主表排在前面，明细表排在后面
 */
public class SectionPlanner implements Planner<SectionPlanner.Input, SectionPlanner.Output> {

    private final AiTextClient aiClient;
    private static final int MIN_WORKSHEETS_FOR_AI = 4;
    private static final int MAX_WS_PER_SECTION = 12;
    private static final int MAX_RETRIES = 3;
    private static final Logger logger = LoggerFactory.getLogger(SectionPlanner.class);

    public SectionPlanner(AiTextClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public String getName() {
        return "SectionPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            List<WorksheetInfo> worksheets = input.getWorksheets();
            String lang = input.getLanguage() != null ? input.getLanguage() : "zh";
            String dashboardName = getDashboardSectionName(lang);
            String fallbackName = getAllWorksheetsSectionName(lang);

            // 工作表数量少，直接放默认分组
            if (worksheets.size() < MIN_WORKSHEETS_FOR_AI) {
                List<SectionPlan> sections = new ArrayList<>();
                sections.add(new SectionPlan(dashboardName, List.of())); // 仪表盘分组（空）
                sections.add(new SectionPlan(fallbackName,
                    worksheets.stream().map(WorksheetInfo::getName).toList()));

                logger.info("✓ 分组规划完成（简化模式）: {} 个分组", sections.size());
                return new Output(input.getAppName(), sections);
            }

            // 调用 AI 规划分组
            String prompt = buildPrompt(input.getAppName(), worksheets, lang, dashboardName);
            String responseJson = callAiWithRetry(prompt);

            // 解析 AI 响应
            Map<String, Object> planResult = Jacksons.mapper().readValue(responseJson,
                Jacksons.mapper().getTypeFactory().constructMapType(Map.class, String.class, Object.class));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sectionsData = (List<Map<String, Object>>) planResult.get("sections");

            List<SectionPlan> sections = parseAndValidateSections(
                sectionsData, worksheets, dashboardName, fallbackName);

            // 确保仪表盘分组存在且排第一
            ensureDashboardSection(sections, dashboardName);

            logger.info("✓ 分组规划完成: {} 个分组", sections.size());

            return new Output(input.getAppName(), sections);

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan sections", e);
        }
    }

    private String buildPrompt(String appName, List<WorksheetInfo> worksheets,
                               String lang, String dashboardName) {
        StringBuilder wsList = new StringBuilder();
        for (WorksheetInfo ws : worksheets) {
            wsList.append("- ").append(ws.getName());
            if (ws.getPurpose() != null && !ws.getPurpose().isEmpty()) {
                wsList.append(": ").append(ws.getPurpose());
            }
            wsList.append("\n");
        }

        if ("en".equals(lang)) {
            return String.format("""
                You are an enterprise app architect. Plan the worksheet section structure for "%s".

                ## Worksheets (%d total)

                %s

                ## Task

                Split the worksheets into 2-9 business sections. Each section should group worksheets that belong to the same business capability.

                Rules:
                1. The first section must be "%s" and its worksheets must be an empty array []. This section is reserved for analytics pages and chatbots.
                2. Group related worksheets together by business domain, for example customer operations, finance, delivery, or support.
                3. Each business section should contain 2-12 worksheets whenever possible.
                4. Every worksheet must be assigned exactly once.
                5. Section names must be concise English business labels, ideally 1-3 words.
                6. If a section would contain only one worksheet, merge it into the most related section.
                7. Inside each section, place primary worksheets before detail or helper worksheets.
                8. If one worksheet is literally named "%s", treat it as a normal worksheet and still assign it to a business section. Do not consume it with the reserved "%s" section.

                Return strict JSON only:
                {
                  "sections": [
                    {
                      "name": "%s",
                      "worksheets": []
                    },
                    {
                      "name": "Business Section Name",
                      "worksheets": ["Worksheet A", "Worksheet B"]
                    }
                  ]
                }""",
                appName, worksheets.size(), wsList.toString(),
                dashboardName, dashboardName, dashboardName, dashboardName);
        } else {
            return String.format("""
                你是一名企业应用架构师，正在为「%s」规划应用内的工作表分组结构。

                ## 工作表列表（共 %d 张）

                %s

                ## 任务

                请将上述工作表划分为 2-9 个业务分组（Section），每个分组包含功能或业务上相关的工作表。

                分组原则：
                1. 第一个分组必须固定为"%s"，worksheets 为空数组 []，用于放置统计页面和对话机器人
                2. 同一业务领域的工作表放一组（如客户相关、财务相关、生产相关）
                3. 每个业务分组最少 2 张工作表，最多 12 张工作表
                4. 所有工作表都必须被分配，不能遗漏
                5. 分组名称用 2-6 个中文字，简洁明了
                6. 如果某分组只剩 1 张工作表，将其合并到最相关的分组中
                7. 每个分组内，主表（核心业务表）排在前面，明细表/子表/辅助表排在后面
                8. 如果某张工作表的名称刚好也是"%s"，它仍然是普通工作表，必须分配到业务分组中，不能被保留分组吞掉。

                ## 输出格式（严格 JSON，不要任何解释文字）

                {
                  "sections": [
                    {
                      "name": "%s",
                      "worksheets": []
                    },
                    {
                      "name": "业务分组名称",
                      "worksheets": ["工作表名1", "工作表名2"]
                    }
                  ]
                }""",
                appName, worksheets.size(), wsList.toString(),
                dashboardName, dashboardName, dashboardName);
        }
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

    @SuppressWarnings("unchecked")
    private List<SectionPlan> parseAndValidateSections(
            List<Map<String, Object>> sectionsData,
            List<WorksheetInfo> worksheets,
            String dashboardName,
            String fallbackName) {

        List<SectionPlan> sections = new ArrayList<>();
        Set<String> worksheetNames = new HashSet<>();
        for (WorksheetInfo ws : worksheets) {
            worksheetNames.add(ws.getName());
        }

        // 解析 AI 返回的分组
        for (Map<String, Object> secData : sectionsData) {
            String name = (String) secData.getOrDefault("name", "");
            if (name.isEmpty()) continue;

            List<String> wsList = new ArrayList<>();
            Object worksheetsObj = secData.get("worksheets");
            if (worksheetsObj instanceof List) {
                for (Object ws : (List<?>) worksheetsObj) {
                    String wsName = ws.toString().trim();
                    if (worksheetNames.contains(wsName)) {
                        wsList.add(wsName);
                    }
                }
            }

            // 处理超过12张工作表的分组（自动拆分）
            if (wsList.size() > MAX_WS_PER_SECTION && !name.equals(dashboardName)) {
                List<List<String>> chunks = splitIntoChunks(wsList, MAX_WS_PER_SECTION);
                for (int i = 0; i < chunks.size(); i++) {
                    String chunkName = i == 0 ? name : name + "（" + (i + 1) + "）";
                    sections.add(new SectionPlan(chunkName, chunks.get(i)));
                }
            } else {
                sections.add(new SectionPlan(name, wsList));
            }
        }

        // 确保有兜底业务分组
        SectionPlan fallbackSection = sections.stream()
            .filter(s -> !s.getName().equals(dashboardName))
            .findFirst()
            .orElse(null);

        if (fallbackSection == null) {
            fallbackSection = new SectionPlan(fallbackName, new ArrayList<>());
            sections.add(fallbackSection);
        }

        // 收集已分配的工作表
        Set<String> assigned = new HashSet<>();
        for (SectionPlan sec : sections) {
            if (!sec.getName().equals(dashboardName)) {
                assigned.addAll(sec.getWorksheets());
            }
        }

        // 处理未分配的工作表
        Set<String> missing = new HashSet<>(worksheetNames);
        missing.removeAll(assigned);

        if (!missing.isEmpty()) {
            logger.warn("[warn] 以下工作表未被 AI 分配，已自动追加到分组「{}」 : {}",
                fallbackSection.getName(), missing);
            List<String> fallbackWorksheets = new ArrayList<>(fallbackSection.getWorksheets());
            for (WorksheetInfo ws : worksheets) {
                if (missing.contains(ws.getName()) && !fallbackWorksheets.contains(ws.getName())) {
                    fallbackWorksheets.add(ws.getName());
                }
            }
            sections.remove(fallbackSection);
            sections.add(new SectionPlan(fallbackSection.getName(), fallbackWorksheets));
        }

        return sections;
    }

    private List<List<String>> splitIntoChunks(List<String> list, int chunkSize) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            chunks.add(new ArrayList<>(list.subList(i, Math.min(i + chunkSize, list.size()))));
        }
        return chunks;
    }

    private void ensureDashboardSection(List<SectionPlan> sections, String dashboardName) {
        // 找到仪表盘分组
        SectionPlan dashboardSection = null;
        int dashboardIndex = -1;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).getName().equals(dashboardName)) {
                dashboardSection = sections.get(i);
                dashboardIndex = i;
                break;
            }
        }

        // 仪表盘不存在，创建一个
        if (dashboardSection == null) {
            dashboardSection = new SectionPlan(dashboardName, List.of());
            sections.add(0, dashboardSection);
        } else if (dashboardIndex != 0) {
            // 仪表盘存在但不是第一个，移到最前面
            sections.remove(dashboardIndex);
            sections.add(0, dashboardSection);
        }

        // 清空仪表盘的 worksheets（防止 AI 错误分配）
        if (!dashboardSection.getWorksheets().isEmpty()) {
            logger.warn("[warn] 保留分组「{}」中出现工作表，已自动清空: {}",
                dashboardName, dashboardSection.getWorksheets());
        }
    }

    private String getDashboardSectionName(String lang) {
        return "en".equals(lang) ? "Dashboard" : "仪表盘";
    }

    private String getAllWorksheetsSectionName(String lang) {
        return "en".equals(lang) ? "All Worksheets" : "全部工作表";
    }

    // ========== 输入输出类 ==========
    public static class Input {
        private final String appName;
        private final List<WorksheetInfo> worksheets;
        private final String language;

        public Input(String appName, List<WorksheetInfo> worksheets, String language) {
            this.appName = appName;
            this.worksheets = worksheets;
            this.language = language != null ? language : "zh";
        }

        public String getAppName() { return appName; }
        public List<WorksheetInfo> getWorksheets() { return worksheets; }
        public String getLanguage() { return language; }
    }

    public static class WorksheetInfo {
        private final String name;
        private final String purpose;

        public WorksheetInfo(String name, String purpose) {
            this.name = name;
            this.purpose = purpose;
        }

        public String getName() { return name; }
        public String getPurpose() { return purpose; }
    }

    public static class Output {
        private final String appName;
        private final List<SectionPlan> sections;

        public Output(String appName, List<SectionPlan> sections) {
            this.appName = appName;
            this.sections = sections;
        }

        public String getAppName() { return appName; }
        public List<SectionPlan> getSections() { return sections; }
    }

    public static class SectionPlan {
        private final String name;
        private final List<String> worksheets;

        public SectionPlan(String name, List<String> worksheets) {
            this.name = name;
            this.worksheets = worksheets != null ? worksheets : List.of();
        }

        public String getName() { return name; }
        public List<String> getWorksheets() { return worksheets; }
    }
}
