package io.flottila.schemas;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
}
