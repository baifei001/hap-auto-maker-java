package com.hap.automaker.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.AiJsonParser;
import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.model.OrganizationAuthConfig;

public final class SpecGeneratorService {

    private final AiTextClient aiClient;
    private final AiJsonParser aiJsonParser;
    private final SpecNormalizer specNormalizer;

    public SpecGeneratorService(AiTextClient aiClient, AiJsonParser aiJsonParser, SpecNormalizer specNormalizer) {
        this.aiClient = aiClient;
        this.aiJsonParser = aiJsonParser;
        this.specNormalizer = specNormalizer;
    }

    public ObjectNode generateSpec(Path repoRoot, String requirements, String language) throws Exception {
        ConfigPaths paths = new ConfigPaths(repoRoot);
        AiAuthConfig aiAuth = Jacksons.mapper().readValue(paths.aiAuth().toFile(), AiAuthConfig.class);
        OrganizationAuthConfig orgAuth = Jacksons.mapper().readValue(paths.organizationAuth().toFile(), OrganizationAuthConfig.class);
        String prompt = buildSpecPrompt(requirements, language, orgAuth.groupIds());
        String raw = aiClient.generateJson(prompt, aiAuth);
        JsonNode parsed = aiJsonParser.parse(raw);
        if (!(parsed instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("AI response must be a JSON object");
        }
        return specNormalizer.normalize(objectNode, language);
    }

    public Path saveSpec(Path repoRoot, ObjectNode spec, Path output) throws Exception {
        Path specDir = repoRoot.resolve("data").resolve("outputs").resolve("requirement_specs");
        Files.createDirectories(specDir);
        Path finalOutput = output == null
                ? specDir.resolve("requirement_spec_"
                        + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now())
                        + ".json")
                : output;
        Jacksons.mapper().writeValue(finalOutput.toFile(), spec);
        Jacksons.mapper().writeValue(specDir.resolve("requirement_spec_latest.json").toFile(), spec);
        return finalOutput;
    }

    private String buildSpecPrompt(String requirements, String language, String groupIds) {
        boolean isEn = "en".equalsIgnoreCase(language);
        String appNameHint = isEn ? "Extract from requirement, in English"
                : "【从需求提取】应用的完整名称，若未明确则根据业务场景推断合理名称";
        String bizHint = isEn ? "Describe business scenario in English (1-3 sentences)"
                : "【从需求提取】用1-3句话描述业务场景";
        String reqHint = isEn ? "Worksheet quantity/functional requirements in English; empty string if not mentioned"
                : "【从需求提取】工作表数量/功能要求，若未提及则留空字符串";
        String layoutHint = isEn ? "Layout requirements in English; empty string if not mentioned"
                : "【从需求提取】布局要求，若未提及则留空字符串";
        String summaryHint = isEn ? "Summary in English within 100 chars" : "100字以内总结";
        String langRule = isEn ? "All natural-language values must be English." : "自然语言字段默认中文。";

        String intro = isEn
                ? "You are a requirements structuring engine. Convert the user requirements into strict JSON. schema_version must be workflow_requirement_v1."
                : "你是需求结构化引擎。请根据以下用户需求，输出严格 JSON，schema_version 必须为 workflow_requirement_v1。";

        String rulesBlock = isEn
                ? """
                  1. app.name must be replaced with a real app name.
                  2. worksheets.business_context must be replaced with real business context.
                  3. Replace all placeholder text; if not mentioned, use empty string.
                  4. If navigation is not specified, keep pcNaviStyle=1; if color not specified, keep color_mode=random.
                  5. Output JSON only (no markdown).
                  6. All natural-language fields must be in English.
                  """
                : """
                  1. app.name 必须替换为真实应用名称，禁止保留“从需求提取”字样。
                  2. worksheets.business_context 必须替换为真实业务场景描述。
                  3. 其余“从需求提取”占位符同理，无相关信息则填空字符串。
                  4. 若未提及导航布局，固定 pcNaviStyle=1；若未提及主题色，固定 color_mode=random。
                  5. 只输出 JSON，不要 markdown 代码块。
                  """;

        return """
                %s

                用户需求：
                %s

                输出 JSON 结构（只输出 JSON，不要 markdown）：
                {
                  "schema_version": "workflow_requirement_v1",
                  "meta": {
                    "created_at": "%s",
                    "source": "java_cli_chat",
                    "conversation_summary": "%s",
                    "language": "%s"
                  },
                  "app": {
                    "target_mode": "create_new",
                    "name": "%s",
                    "group_ids": "%s",
                    "icon_mode": "ai_match",
                    "color_mode": "random",
                    "navi_style": {
                      "enabled": true,
                      "pcNaviStyle": 1
                    }
                  },
                  "worksheets": {
                    "enabled": true,
                    "business_context": "%s",
                    "requirements": "%s",
                    "icon_update": {
                      "enabled": true,
                      "refresh_auth": false
                    },
                    "layout": {
                      "enabled": true,
                      "requirements": "%s",
                      "refresh_auth": false
                    }
                  },
                  "views": {
                    "enabled": true
                  },
                  "view_filters": {
                    "enabled": true
                  },
                  "mock_data": {
                    "enabled": true,
                    "dry_run": false,
                    "trigger_workflow": false
                  },
                  "execution": {
                    "fail_fast": true,
                    "dry_run": false
                  }
                }

                规则：
                %s
                额外语言要求：%s
                """.formatted(
                intro,
                requirements,
                java.time.OffsetDateTime.now(),
                summaryHint,
                language,
                appNameHint,
                groupIds == null ? "" : groupIds,
                bizHint,
                reqHint,
                layoutHint,
                rulesBlock.strip(),
                langRule);
    }
}
