package io.flottila.schemas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads a flow / package / endpoint / test document — YAML or JSON by file extension — into the one
 * shape the schemas validate, a {@link JsonObject}. YAML scalars stay what the author wrote: a quoted
 * {@code "0080"} is a string, {@code 100ms} is a string, {@code 1.5} a number, and the YAML 1.1 words
 * {@code off}/{@code on}/{@code yes}/{@code no} are strings (only {@code true}/{@code false} are booleans).
 *
 * @since 0.1
 */
public final class Documents {

    // YAML 1.1 boolean words (off, on, yes, no, y, n) stay strings: `recording: { level: off }` is a level, not false
    // (decided by observation: the default factory parsed `level: off` as false — jshell, 2026-10-18)
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder().enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS).build());

    private Documents() {}

    public static JsonObject yaml(String text) {
        Object value;
        try {
            value = YAML.readValue(text, Object.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("not valid YAML: " + e.getMessage(), e);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("a document must be a YAML mapping at the top level, was " + (value == null ? "empty" : value.getClass().getSimpleName()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> keyed = (Map<String, Object>) map;
        return new JsonObject(keyed);
    }

    public static JsonObject read(Path file) {
        String name = file.getFileName().toString();
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return yaml(text);
        if (name.endsWith(".json")) return new JsonObject(text);
        throw new IllegalArgumentException("not a flow/package/endpoint/test document (expected .yaml, .yml or .json): " + file);
    }
}
