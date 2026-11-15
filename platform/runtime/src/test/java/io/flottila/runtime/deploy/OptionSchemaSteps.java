package io.flottila.runtime.deploy;

import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Step types whose {@code configSchema()} the deploy judges a step's options by (P1-09f, D-057 amendment). Both build a stage that
 * passes the envelope on whatever their options say: what refuses a bad option is the deploy's schema check, never the factory.
 */
public final class OptionSchemaSteps {

    private OptionSchemaSteps() {}

    /**
     * Type {@code sheet}, xslt-like: its options must be exactly {@code template}, a non-empty string. No other key is allowed, so a
     * deploy that judged the whole step map (with {@code type}, {@code name}, {@code retryable}) instead of the options would refuse
     * every step of this type. The key is not {@code stylesheet}: the package cross-check (flottila-schemas {@code Package}) reads that
     * key on every step type, file existence and string type, and its lines would stand in for the schema's.
     */
    public record Sheet() implements StepFactory {
        public static final String TYPE = "sheet";
        public static final String SCHEMA = new JsonObject().put("type", "object").put("required", new JsonArray().add("template"))
                .put("properties", new JsonObject().put("template", new JsonObject().put("type", "string").put("minLength", 1)))
                .put("additionalProperties", false).encode();

        @Override public String type() { return TYPE; }
        @Override public String configSchema() { return SCHEMA; }
        @Override public Stage create(StepConfig step, StepResources resources) { return (e, rest) -> rest.apply(e); }

        /** A {@code sheet} step named {@code name} with {@code template} set to {@code value} ({@code null}: the key is absent). */
        public static JsonObject step(String name, Object value) {
            JsonObject s = new JsonObject().put("type", TYPE).put("name", name);
            return value == null ? s : s.put("template", value);
        }
    }

    /** Type {@code garbled}: its {@code configSchema()} is {@code schema}, text that is not a usable JSON Schema. */
    public record Garbled(String schema) implements StepFactory {
        public static final String TYPE = "garbled";
        /** Not JSON at all. */
        public static final String NOT_JSON = "not json {";

        @Override public String type() { return TYPE; }
        @Override public String configSchema() { return schema; }
        @Override public Stage create(StepConfig step, StepResources resources) { return (e, rest) -> rest.apply(e); }
    }
}
