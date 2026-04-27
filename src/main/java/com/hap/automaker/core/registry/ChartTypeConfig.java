package com.hap.automaker.core.registry;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.Map;

/**
 * 图表类型配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChartTypeConfig extends TypedConfig {
    private int reportType;
    private String name;
    private String category;
    private boolean needsXAxis;
    private boolean needsYAxis;
    private String[] recommendedXAxisTypes;
    private String[] recommendedYAxisTypes;
    private boolean aiDisabled;
    private String aiDisabledReason;
    private String doc;
    private Map<String, Object> defaultConfig = new HashMap<>();
    private Map<String, String> configHints = new HashMap<>();

    // Getters and Setters
    public int getReportType() {
        return reportType;
    }

    public void setReportType(int reportType) {
        this.reportType = reportType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isNeedsXAxis() {
        return needsXAxis;
    }

    public void setNeedsXAxis(boolean needsXAxis) {
        this.needsXAxis = needsXAxis;
    }

    public boolean isNeedsYAxis() {
        return needsYAxis;
    }

    public void setNeedsYAxis(boolean needsYAxis) {
        this.needsYAxis = needsYAxis;
    }

    public String[] getRecommendedXAxisTypes() {
        return recommendedXAxisTypes;
    }

    public void setRecommendedXAxisTypes(String[] recommendedXAxisTypes) {
        this.recommendedXAxisTypes = recommendedXAxisTypes;
    }

    public String[] getRecommendedYAxisTypes() {
        return recommendedYAxisTypes;
    }

    public void setRecommendedYAxisTypes(String[] recommendedYAxisTypes) {
        this.recommendedYAxisTypes = recommendedYAxisTypes;
    }

    public boolean isAiDisabled() {
        return aiDisabled;
    }

    public void setAiDisabled(boolean aiDisabled) {
        this.aiDisabled = aiDisabled;
    }

    public String getAiDisabledReason() {
        return aiDisabledReason;
    }

    public void setAiDisabledReason(String aiDisabledReason) {
        this.aiDisabledReason = aiDisabledReason;
    }

    public String getDoc() {
        return doc;
    }

    public void setDoc(String doc) {
        this.doc = doc;
    }

    public Map<String, Object> getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(Map<String, Object> defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, String> getConfigHints() {
        return configHints;
    }

    public void setConfigHints(Map<String, String> configHints) {
        this.configHints = configHints;
    }

    @Override
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name).append(" (reportType=").append(reportType).append(")");
        if (aiDisabled) {
            sb.append(" [AI禁用: ").append(aiDisabledReason).append("]");
        }
        if (doc != null && !doc.isEmpty()) {
            sb.append(" - ").append(doc);
        }
        if (needsXAxis && recommendedXAxisTypes != null) {
            sb.append(" [X轴: ").append(String.join(",", recommendedXAxisTypes)).append("]");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ChartTypeConfig{" +
            "reportType=" + reportType +
            ", name='" + name + '\'' +
            ", category='" + category + '\'' +
            '}';
    }
}
