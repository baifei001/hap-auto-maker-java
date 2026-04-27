package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Spec 元数据
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SpecMeta {
    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("source")
    private String source;

    @JsonProperty("conversation_summary")
    private String conversationSummary;

    @JsonProperty("language")
    private String language = "zh";

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getConversationSummary() {
        return conversationSummary;
    }

    public void setConversationSummary(String conversationSummary) {
        this.conversationSummary = conversationSummary;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}
