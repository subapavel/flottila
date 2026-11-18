package dev.rail8.schemas;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step's name is optional in the flow file; no step is named {@code ingress}. Without a name, a problem line names the step
 * as the flow names it when it loads: after its type, a process call after the process it calls.
 */
class FlowStepNamesSchemaTest {

    /** The cases of the default name that core's loading follows too: each a step entry and its name. */
    static final Path SHARED_CASES = Path.of("src", "test", "resources", "step-names.json");

    @Test
    void aStepAndACallMayHaveNoName() {
        Schemas.Report r = FlowProcessesSchemaTest.flowWith("[{ type: xsd, schema: flows/invoice.xsd }, { type: process, process: enrich }]",
                "{ enrich: { steps: [{ type: xslt, stylesheet: flows/enrich.xsl }, { type: to, name: out, endpoint: partner }] } }");
        assertTrue(r.valid(), r.toString());
    }

    @Test
    void noStepIsNamedIngress() {
        Schemas.Report top = FlowProcessesSchemaTest.flowWith("[{ type: to, name: ingress, endpoint: partner }]", "{}");
        assertFalse(top.valid(), "ingress names the arrival");
        assertTrue(top.problems().stream().anyMatch(p -> p.path().equals("/steps/0/name")), top.toString());

        Schemas.Report inner = FlowProcessesSchemaTest.flowWith("[{ type: process, process: p }]", "{ p: { steps: [{ type: to, name: ingress, endpoint: partner }] } }");
        assertTrue(inner.problems().stream().anyMatch(p -> p.path().equals("/processes/p/steps/0/name")), inner.toString());
    }

    @Test
    void aProblemLineNamesAnUnnamedStepAsTheSharedCasesDo() throws IOException {
        JsonArray cases = new JsonArray(Files.readString(SHARED_CASES));
        assertTrue(cases.size() >= 4, "the shared cases are read: " + cases);
        for (Object c : cases) {
            JsonObject one = (JsonObject) c;
            Package.WrittenStep step = new Package.WrittenStep(Optional.empty(), one.getJsonObject("step"));
            assertEquals("step '" + one.getString("name") + "'", step.label(), one.encode());
            assertEquals("process 'p' step '" + one.getString("name") + "'", new Package.WrittenStep(Optional.of("p"), one.getJsonObject("step")).label());
        }
    }
}
