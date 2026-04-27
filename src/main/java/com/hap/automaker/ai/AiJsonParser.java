package com.hap.automaker.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import com.hap.automaker.config.Jacksons;

public final class AiJsonParser {

    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*(\\{.*\\})\\s*```", Pattern.DOTALL);

    public JsonNode parse(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("AI response is empty");
        }
        try {
            return Jacksons.mapper().readTree(text);
        } catch (Exception ignored) {
        }

        Matcher matcher = CODE_FENCE.matcher(text);
        if (matcher.find()) {
            return Jacksons.mapper().readTree(matcher.group(1));
        }

        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return Jacksons.mapper().readTree(text.substring(firstBrace, lastBrace + 1));
        }
        throw new IllegalArgumentException("Unable to extract JSON from AI response");
    }
}
