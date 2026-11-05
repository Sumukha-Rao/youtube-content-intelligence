package com.yci.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yci.exception.LlmServiceException;

/** Shared strict boundary for JSON produced by the existing LLM. */
public final class StructuredOutput {
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private StructuredOutput() {}

    public static ObjectNode object(String raw) {
        try {
            String text = raw == null ? "" : raw.trim();
            if (text.startsWith("```")) {
                int newline = text.indexOf('\n');
                if (newline >= 0 && text.endsWith("```")) text = text.substring(newline + 1, text.length() - 3);
            }
            JsonNode node = JSON.readTree(text);
            if (node != null && node.isObject()) return (ObjectNode) node;
        } catch (Exception ignored) { }
        throw invalid();
    }

    public static String text(JsonNode node, String key, int max) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw invalid();
        return value.asText();
    }

    public static ArrayNode array(JsonNode node, String key, int max) {
        JsonNode value = node.path(key);
        if (!value.isArray() || value.size() > max) throw invalid();
        return (ArrayNode) value;
    }

    public static ArrayNode strings(JsonNode node, String key, int max) {
        ArrayNode values = array(node, key, max);
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 2000) throw invalid();
        }
        return values;
    }

    public static String limit(String text, int max) {
        return text == null ? "" : text.substring(0, Math.min(text.length(), Math.max(0, max)));
    }

    public static LlmServiceException invalid() {
        return new LlmServiceException("The model returned invalid structured data. Please try again.");
    }
}
