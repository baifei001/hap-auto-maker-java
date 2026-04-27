package com.hap.automaker.core.registry;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.Map;

/**
 * 字段类型配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldTypeConfig extends TypedConfig {
    private int controlType;
    private String name;
    private String category;
    private boolean verified;
    private boolean canBeTitle;
    private boolean aiDisabled;
    private String aiDisabledReason;
    private String doc;
    private Map<String, Object> advancedSetting = new HashMap<>();
    private Map<String, String> advancedSettingAllKeys = new HashMap<>();
    private Map<String, Object> extra = new HashMap<>();

    // Getters and Setters
    public int getControlType() {
        return controlType;
    }

    public void setControlType(int controlType) {
        this.controlType = controlType;
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

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public boolean isCanBeTitle() {
        return canBeTitle;
    }

    public void setCanBeTitle(boolean canBeTitle) {
        this.canBeTitle = canBeTitle;
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

    public Map<String, Object> getAdvancedSetting() {
        return advancedSetting;
    }

    public void setAdvancedSetting(Map<String, Object> advancedSetting) {
        this.advancedSetting = advancedSetting;
    }

    public Map<String, String> getAdvancedSettingAllKeys() {
        return advancedSettingAllKeys;
    }

    public void setAdvancedSettingAllKeys(Map<String, String> advancedSettingAllKeys) {
        this.advancedSettingAllKeys = advancedSettingAllKeys;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    @Override
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name).append(" (type=").append(controlType).append(")");
        if (aiDisabled) {
            sb.append(" [AI禁用: ").append(aiDisabledReason).append("]");
        }
        if (doc != null && !doc.isEmpty()) {
            sb.append(" - ").append(doc);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "FieldTypeConfig{" +
            "controlType=" + controlType +
            ", name='" + name + '\'' +
            ", category='" + category + '\'' +
            ", verified=" + verified +
            '}';
    }
}
