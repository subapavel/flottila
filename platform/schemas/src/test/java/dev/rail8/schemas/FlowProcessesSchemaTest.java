package dev.rail8.schemas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local processes in the flow file: {@code processes} names blocks of steps, and a step of type {@code process} runs one. The
 * schema types their shape; which process a call names, and that no process calls itself, are checked when the flow loads.
 */
class FlowProcessesSchemaTest {

    static Schemas.Report flowWith(String steps, String processes) {
        return Schemas.FLOW.validate(Documents.yaml("""
                schemaVersion: 1
                kind: flow
                id: orders
                version: 1.0.0
                from: { endpoint: http-in }
                delivery: { mode: sync }
                limits: { maxBodyBytes: 1024 }
                steps: %s
                processes: %s
                """.formatted(steps, processes)));
    }

    @Test
    void aFlowMayNameProcessesAndCallThem() {
        Schemas.Report r = flowWith("[{ type: process, name: enrich, process: enrichOrder }, { type: to, name: out, endpoint: partner }]",
                "{ enrichOrder: { steps: [{ type: xslt, name: map, stylesheet: flows/enrich.xsl }, { type: process, name: deep, process: other }] },"
                        + " other: { steps: [{ type: filter, name: keep, when: /a }] } }");
        assertTrue(r.valid(), r.toString());
    }

    @ParameterizedTest(name = "{0} at {2}")
    @CsvSource(delimiter = '|', value = {
            "a call without its process|[{ type: process, name: enrich }]|/steps/0",
            "a call with an option|[{ type: process, name: enrich, process: a, retryable: true }]|/steps/0",
            "a call naming a process of the wrong form|[{ type: process, name: enrich, process: Not-A-Name }]|/steps/0/process",
    })
    void aCallOfTheWrongShapeIsRefusedAtItsPath(String what, String steps, String path) {
        Schemas.Report r = flowWith(steps, "{ a: { steps: [{ type: to, name: out, endpoint: partner }] } }");
        assertFalse(r.valid(), what + " must be invalid");
        assertTrue(r.problems().stream().anyMatch(p -> p.path().equals(path)), what + ": a problem at " + path + ", got " + r);
    }

    @ParameterizedTest(name = "{0} at {2}")
    @CsvSource(delimiter = '|', value = {
            "processes that are a list|[a]|/processes",
            "a process without steps|{ a: {} }|/processes/a",
            "a process with no step|{ a: { steps: [] } }|/processes/a/steps",
            "a process with a key besides steps|{ a: { steps: [{ type: to, name: out, endpoint: partner }], retry: 3 } }|/processes/a",
            "a process step without a name|{ a: { steps: [{ type: to, endpoint: partner }] } }|/processes/a/steps/0",
            "a process step of a typed type missing its option|{ a: { steps: [{ type: xslt, name: map }] } }|/processes/a/steps/0",
            "a process name of the wrong form|{ Bad.Name: { steps: [{ type: to, name: out, endpoint: partner }] } }|/processes",
    })
    void processesOfTheWrongShapeAreRefusedAtTheirPath(String what, String processes, String path) {
        Schemas.Report r = flowWith("[{ type: to, name: out, endpoint: partner }]", processes);
        assertFalse(r.valid(), what + " must be invalid");
        assertTrue(r.problems().stream().anyMatch(p -> p.path().equals(path)), what + ": a problem at " + path + ", got " + r);
    }
}
