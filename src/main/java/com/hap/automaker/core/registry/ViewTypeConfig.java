package com.hap.automaker.core.registry;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.Map;

/**
 * 视图类型配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ViewTypeConfig extends TypedConfig {
    private int viewType;
    private String name;
    private String category;
    private boolean aiDisabled;
    private String aiDisabledReason;
    private String doc;
    private String[] requiredFieldTypes;
    private Map<String, Object> defaultConfig = new HashMap<>();
    private Map<String, String> configHints = new HashMap<>();

    // Getters and Setters
    public int getViewType() {
        return viewType;
    }

    public void setViewType(int viewType) {
        this.viewType = viewType;
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

    public String[] getRequiredFieldTypes() {
        return requiredFieldTypes;
    }

    public void setRequiredFieldTypes(String[] requiredFieldTypes) {
        this.requiredFieldTypes = requiredFieldTypes;
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
        sb.append("- ").append(name).append(" (viewType=").append(viewType).append(")");
        if (aiDisabled) {
            sb.append(" [AI禁用: ").append(aiDisabledReason).append("]");
        }
        if (doc != null && !doc.isEmpty()) {
            sb.append(" - ").append(doc);
        }
        if (requiredFieldTypes != null && requiredFieldTypes.length > 0) {
            sb.append(" [需要: ").append(String.join(",", requiredFieldTypes)).append("]");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ViewTypeConfig{" +
            "viewType=" + viewType +
            ", name='" + name + '\'' +
            ", category='" + category + '\'' +
            '}';
    }
}
