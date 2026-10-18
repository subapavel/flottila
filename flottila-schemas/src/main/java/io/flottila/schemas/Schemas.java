package io.flottila.schemas;

import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The four published JSON Schemas (draft 2020-12) of the v1 file model — flow, package, endpoint and
 * {@code *.test.yaml} — loaded from {@code schemas/<kind>.schema.json} on the classpath, each stamped
 * {@code schemaVersion: 1} and {@code draft — review at hand-over} (ARCHITECTURE amendment 4, P0-12).
 * Validation is pure: no I/O, no Vert.x instance.
 *
 * @since 0.1
 */
public enum Schemas {
    FLOW("flow"), PACKAGE("package"), ENDPOINT("endpoint"), TEST("test");

    private final String kind;
    private final JsonObject json;
    private final Validator validator;

    Schemas(String kind) {
        this.kind = kind;
        this.json = load("/schemas/" + kind + ".schema.json");
        this.validator = Validator.create(JsonSchema.of(json), new JsonSchemaOptions()
                .setDraft(Draft.DRAFT202012).setBaseUri("urn:flottila:schema:").setOutputFormat(OutputFormat.Basic));
    }

    private static JsonObject load(String resource) {
        try (InputStream in = Schemas.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("schema resource missing: " + resource);
            return new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The value of the document's {@code kind} key this schema validates. */
    public String kind() { return kind; }

    /** A copy of the schema document, e.g. for publishing or for an editor. */
    public JsonObject json() { return json.copy(); }

    public static Schemas forKind(String kind) {
        for (Schemas s : values()) if (s.kind.equals(kind)) return s;
        throw new IllegalArgumentException("no schema for kind '" + kind + "' (one of flow, package, endpoint, test)");
    }

    public Report validate(JsonObject document) {
        Objects.requireNonNull(document, "document");
        OutputUnit out = validator.validate(document);
        if (Boolean.TRUE.equals(out.getValid())) return new Report(true, List.of());
        List<Problem> problems = new ArrayList<>();
        collect(out, problems);
        return new Report(false, List.copyOf(problems));
    }

    private static void collect(OutputUnit unit, List<Problem> into) {
        if (unit.getError() != null) into.add(new Problem(pointer(unit.getInstanceLocation()), unit.getError()));
        if (unit.getErrors() != null) for (OutputUnit e : unit.getErrors()) collect(e, into);
    }

    /** Instance locations as JSON pointers relative to the document root: "" for the root, "/steps/1" below it. */
    private static String pointer(String location) {
        // the validator reports "#" for the root and "#/steps/1" below it; the fragment marker goes, a bare "/" is the root too
        return Objects.toString(location, "").replaceFirst("^#", "").replaceFirst("^/$", "");
    }

    /**
     * One violation: where in the document (JSON pointer, "" = root) and what the schema says.
     *
     * @since 0.1
     */
    public record Problem(String path, String message) {}

    /**
     * The verdict for one document.
     *
     * @since 0.1
     */
    public record Report(boolean valid, List<Problem> problems) {
        @Override
        public String toString() {
            if (valid) return "valid";
            StringBuilder b = new StringBuilder("invalid:");
            for (Problem p : problems) b.append("\n  at '").append(p.path()).append("': ").append(p.message());
            return b.toString();
        }
    }
}
