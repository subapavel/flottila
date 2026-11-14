package io.flottila.schemas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14a P1 (D-135 draft): the flow file's {@code delivery.retries {count 0..100, interval, multiplier ≥ 1, maxInterval}} — the
 * durations in the file's own form ({@code 1m}); its shape is the schema's, the duration ranges and the delivery mode are checked at
 * load (FlowDefinition), naming the key.
 */
class FlowRetriesSchemaTest {

    static Schemas.Report flowWith(String retries) {
        return Schemas.FLOW.validate(Documents.yaml("""
                schemaVersion: 1
                kind: flow
                id: orders
                version: 1.0.0
                from: { endpoint: http-in }
                delivery: { mode: at-least-once, retries: %s }
                limits: { maxBodyBytes: 1024 }
                steps:
                  - { type: to, name: out, endpoint: partner }
                """.formatted(retries)));
    }

    @Test
    void aFlowMayGiveItsRetriesASchedule() {
        Schemas.Report full = flowWith("{ count: 3, interval: 1m, multiplier: 2, maxInterval: 1h }");
        assertTrue(full.valid(), full.toString());
        Schemas.Report minimal = flowWith("{ count: 0, interval: 30s }");
        assertTrue(minimal.valid(), "multiplier and maxInterval have defaults: " + minimal);
    }

    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource(delimiter = '|', value = {
            "{ count: 101, interval: 1m }|/delivery/retries/count",
            "{ count: -1, interval: 1m }|/delivery/retries/count",
            "{ count: 1.5, interval: 1m }|/delivery/retries/count",
            "{ interval: 1m }|/delivery/retries",
            "{ count: 1 }|/delivery/retries",
            "{ count: 1, interval: PT5M }|/delivery/retries/interval",
            "{ count: 1, interval: 1m, multiplier: 0.5 }|/delivery/retries/multiplier",
            "{ count: 1, interval: 1m, maxInterval: forever }|/delivery/retries/maxInterval",
            "{ count: 1, interval: 1m, attempts: 2 }|/delivery/retries",
            "3|/delivery/retries",
    })
    void aRetriesValueOfTheWrongShapeIsRefusedAtItsPath(String retries, String path) {
        Schemas.Report r = flowWith(retries);
        assertFalse(r.valid(), retries + " must be invalid");
        assertTrue(r.problems().stream().anyMatch(p -> p.path().equals(path)), retries + ": a problem at " + path + ", got " + r);
    }
}
