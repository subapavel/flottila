package io.flottila.schemas;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The four published schemas (flow, package, endpoint, test): shape, version stamp, and the validator's verdicts. */
class SchemasTest {

    @ParameterizedTest
    @EnumSource(Schemas.class)
    void everySchemaIsDraft202012CarriesAnIdAndRequiresSchemaVersionOne(Schemas schema) {
        JsonObject json = schema.json();
        assertEquals("https://json-schema.org/draft/2020-12/schema", json.getString("$schema"));
        assertEquals("urn:flottila:schema:" + schema.kind() + ":1", json.getString("$id"));
        assertTrue(json.getJsonArray("required").contains("schemaVersion"), "schemaVersion is required");
        assertTrue(json.getJsonArray("required").contains("kind"), "kind is required");
        assertEquals(1, json.getJsonObject("properties").getJsonObject("schemaVersion").getInteger("const"));
        assertEquals(schema.kind(), json.getJsonObject("properties").getJsonObject("kind").getString("const"));
        assertEquals(false, json.getBoolean("additionalProperties"), "unknown top-level keys are mistakes, not extensions");
        assertTrue(json.getString("title").toLowerCase().contains("draft"), "stamped draft — review at hand-over");
    }

    @Test
    void forKindResolvesTheFourKindsAndRejectsOthers() {
        assertEquals(Schemas.FLOW, Schemas.forKind("flow"));
        assertEquals(Schemas.PACKAGE, Schemas.forKind("package"));
        assertEquals(Schemas.ENDPOINT, Schemas.forKind("endpoint"));
        assertEquals(Schemas.TEST, Schemas.forKind("test"));
        assertThrows(IllegalArgumentException.class, () -> Schemas.forKind("partner"));
    }

    @Test
    void aMinimalValidFlowPassesAndTheReportSaysSo() {
        Schemas.Report r = Schemas.FLOW.validate(Documents.yaml("""
                schemaVersion: 1
                kind: flow
                id: ping
                version: 1.0.0
                from: { endpoint: http-in }
                delivery: { mode: sync }
                limits: { maxBodyBytes: 1024 }
                steps:
                  - { type: to, name: out, endpoint: echo }
                """));
        assertTrue(r.valid(), r.toString());
        assertTrue(r.problems().isEmpty());
    }

    @Test
    void aFlowMayTurnRecordingOffWithTheWordTheDocumentUses() {
        Schemas.Report r = Schemas.FLOW.validate(Documents.yaml("""
                schemaVersion: 1
                kind: flow
                id: ping
                version: 1.0.0
                from: { endpoint: http-in }
                delivery: { mode: at-most-once }
                recording: { level: off, payload: metadata-only }
                limits: { maxBodyBytes: 1024 }
                steps:
                  - { type: to, name: out, endpoint: echo }
                """));
        assertTrue(r.valid(), r.toString());
    }

    @Test
    void aFlowWithoutALimitIsRefusedWithThePathOfTheMissingKey() {
        Schemas.Report r = Schemas.FLOW.validate(Documents.yaml("""
                schemaVersion: 1
                kind: flow
                id: ping
                version: 1.0.0
                from: { endpoint: http-in }
                delivery: { mode: sync }
                steps:
                  - { type: to, name: out, endpoint: echo }
                """));
        assertFalse(r.valid());
        assertTrue(r.problems().stream().anyMatch(p -> p.path().equals("") && p.message().contains("limits")), r.toString());
    }

    @Test
    void aWrongSchemaVersionIsRefusedAtItsOwnPath() {
        Schemas.Report r = Schemas.PACKAGE.validate(new JsonObject().put("schemaVersion", 2).put("kind", "package").put("name", "x").put("version", "1.0.0"));
        assertFalse(r.valid());
        assertTrue(r.problems().stream().anyMatch(p -> p.path().equals("/schemaVersion")), r.toString());
    }

    @Test
    void problemsCarryTheInstancePathDeepInsideTheDocument() {
        Schemas.Report r = Schemas.FLOW.validate(Documents.yaml("""
                schemaVersion: 1
                kind: flow
                id: ping
                version: 1.0.0
                from: { endpoint: http-in }
                delivery: { mode: sync }
                limits: { maxBodyBytes: 1024 }
                steps:
                  - { type: to, name: out, endpoint: echo }
                  - { type: xslt, name: map }
                """));
        assertFalse(r.valid());
        assertTrue(r.problems().stream().anyMatch(p -> p.path().startsWith("/steps/1")), r.toString());
    }

    /**
     * P1-09d1a (D-059): a component's {@code configSchema()} is a JSON Schema string without {@code $schema} or {@code $id} (the
     * http component's shape); {@code check} validates an endpoint's config against it and reports like {@link Schemas#validate}.
     */
    @Test
    void checkValidatesAgainstAComponentSchemaString() {
        String component = """
                {"type":"object","required":["url"],"properties":{"url":{"type":"string"},
                 "port":{"type":"integer","minimum":0,"maximum":65535},"maxBodyBytes":{"type":"integer","minimum":1}}}""";

        Schemas.Report ok = Schemas.check(component, new JsonObject().put("url", "http://localhost:8090/echo").put("maxBodyBytes", 1_048_576));
        assertTrue(ok.valid(), "a config none of the four file schemas would accept is valid against the component's: " + ok);
        assertEquals(List.of(), ok.problems());

        Schemas.Report tooSmall = Schemas.check(component, new JsonObject().put("url", "http://localhost:8090/echo").put("maxBodyBytes", 0));
        assertFalse(tooSmall.valid());
        assertTrue(tooSmall.problems().stream().anyMatch(p -> p.path().equals("/maxBodyBytes")), "the problem's pointer: " + tooSmall);

        Schemas.Report wrongType = Schemas.check(component, new JsonObject().put("url", "http://localhost:8090/echo").put("port", "eighty"));
        assertFalse(wrongType.valid());
        assertTrue(wrongType.problems().stream().anyMatch(p -> p.path().equals("/port")), "the problem's pointer: " + wrongType);

        Schemas.Report missing = Schemas.check(component, new JsonObject().put("maxBodyBytes", 5));
        assertFalse(missing.valid());
        assertTrue(missing.problems().stream().anyMatch(p -> p.path().equals("") && p.message().contains("url")), "the missing key at the root: " + missing);

        assertTrue(Schemas.check("{\"type\":\"object\"}", new JsonObject().put("maxBodyBytes", 5)).valid(),
                "each call validates against the schema it is given, not one it saw before");
    }
}
