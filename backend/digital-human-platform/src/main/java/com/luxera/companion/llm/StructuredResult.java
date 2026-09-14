package com.luxera.companion.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.io.UncheckedIOException;

@Getter
public class StructuredResult {
    private final String raw;
    private final JsonNode json;

    public StructuredResult(String raw, ObjectMapper mapper) {
        this.raw = raw == null ? "" : raw.trim();
        try {
            this.json = mapper.readTree(this.raw);
        } catch (Exception e) {
            throw new UncheckedIOException("LLM 结构化输出不是合法 JSON: " + this.raw, e instanceof java.io.IOException ioe ? ioe : new java.io.IOException(e));
        }
    }

    public JsonNode path(String... parts) {
        JsonNode node = json;
        for (String p : parts) {
            node = node == null ? null : node.get(p);
        }
        return node;
    }
}
