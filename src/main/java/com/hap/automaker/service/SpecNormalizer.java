package com.hap.automaker.service;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class SpecNormalizer {

    public ObjectNode normalize(ObjectNode raw, String language) {
        ObjectNode spec = unwrapIfNeeded(raw);
        spec.put("schema_version", "workflow_requirement_v1");

        ObjectNode meta = ensureObject(spec, "meta");
        if (!meta.has("created_at")) {
            meta.put("created_at", OffsetDateTime.now().toString());
        }
        if (!meta.has("source")) {
            meta.put("source", "java_cli");
        }
        if (!meta.has("conversation_summary")) {
            meta.put("conversation_summary", "");
        }
        if (!meta.has("language")) {
            meta.put("language", language);
        }

        ObjectNode app = ensureObject(spec, "app");
        if (!app.has("target_mode")) {
            app.put("target_mode", "create_new");
        }
        if (!app.has("name")) {
            String fallbackName = textOrEmpty(spec, "name");
            app.put("name", fallbackName.isBlank() ? "未命名应用" : fallbackName);
        }
        if (!app.has("group_ids")) {
            app.put("group_ids", "");
        }
        if (!app.has("icon_mode")) {
            app.put("icon_mode", "ai_match");
        }
        if (!app.has("color_mode")) {
            app.put("color_mode", "random");
        }

        ObjectNode worksheets = ensureObject(spec, "worksheets");
        if (!worksheets.has("enabled")) {
            worksheets.put("enabled", true);
        }
        if (!worksheets.has("business_context")) {
            worksheets.put("business_context", textOrEmpty(spec, "description"));
        }
        if (!worksheets.has("requirements")) {
            worksheets.put("requirements", "");
        }

        ObjectNode views = ensureObject(spec, "views");
        if (!views.has("enabled")) {
            views.put("enabled", true);
        }

        ObjectNode pages = ensureObject(spec, "pages");
        if (!pages.has("enabled")) {
            pages.put("enabled", true);
        }

        ObjectNode execution = ensureObject(spec, "execution");
        if (!execution.has("fail_fast")) {
            execution.put("fail_fast", true);
        }
        if (!execution.has("dry_run")) {
            execution.put("dry_run", false);
        }

        return spec;
    }

    private ObjectNode unwrapIfNeeded(ObjectNode raw) {
        if (raw.path("workflow_requirement_v1").isObject()
                && !raw.has("app")
                && !raw.has("worksheets")) {
            return ((ObjectNode) raw.path("workflow_requirement_v1")).deepCopy();
        }
        return raw.deepCopy();
    }

    private String textOrEmpty(ObjectNode node, String fieldName) {
        return node.path(fieldName).isTextual() ? node.path(fieldName).asText("") : "";
    }

    private ObjectNode ensureObject(ObjectNode parent, String fieldName) {
        if (parent.path(fieldName).isObject()) {
            return (ObjectNode) parent.path(fieldName);
        }
        ObjectNode created = JsonNodeFactory.instance.objectNode();
        parent.set(fieldName, created);
        return created;
    }
}
