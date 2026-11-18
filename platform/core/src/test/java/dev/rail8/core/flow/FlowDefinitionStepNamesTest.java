package dev.rail8.core.flow;

import dev.rail8.api.StepConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step's name is optional in the flow file. A step without one is named after its type, and a call of a local process after
 * the process it calls. Names, written or not, are unique in the flow's steps and in each process's, and no step is named
 * {@code ingress}, the name of the arrival.
 */
class FlowDefinitionStepNamesTest {

    /** The cases of the default name that the schema module's problem lines follow too: each a step entry and its name. */
    static final Path SHARED_CASES = Path.of("..", "schemas", "src", "test", "resources", "step-names.json");

    static Map<String, Object> entry(String type, Object... options) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        for (int i = 0; i < options.length; i += 2) s.put((String) options[i], options[i + 1]);
        return s;
    }

    static Map<String, Object> flow(List<?> steps, Map<String, ?> processes) {
        Map<String, Object> doc = new HashMap<>(FlowDefinitionTest.demo());
        doc.put("steps", steps);
        if (processes != null) doc.put("processes", processes);
        return doc;
    }

    static Map<String, Object> block(Object... steps) {
        return Map.of("steps", List.of(steps));
    }

    static String refusal(Map<String, Object> doc) {
        return assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(doc)).getMessage();
    }

    static List<String> names(FlowDefinition f) {
        return f.steps().stream().map(StepConfig::name).toList();
    }

    @Test
    void aStepWithoutANameIsNamedAfterItsTypeAndACallAfterItsProcess() {
        FlowDefinition f = FlowDefinition.from(flow(
                List.of(entry("xsd", "schema", "flows/invoice.xsd"), entry("process", "process", "enrichOrder"),
                        entry("to", "name", "partner", "endpoint", "partner-out")),
                Map.of("enrichOrder", block(entry("extractKey", "key", "orderId", "xpath", "/Order/Id"), entry("xslt", "name", "map", "stylesheet", "e.xsl")))));

        assertEquals(List.of("xsd", "enrichOrder/extractKey", "enrichOrder/map", "partner"), names(f));
        assertEquals(Map.of("schema", "flows/invoice.xsd"), f.steps().getFirst().config(), "the name is no option of the step");
    }

    @Test
    void theDefaultNameFollowsTheCasesTheSchemaModuleSharesWithCore() throws IOException {
        JsonArray cases = new JsonArray(Files.readString(SHARED_CASES));
        assertTrue(cases.size() >= 4, "the shared cases are read: " + cases);
        for (Object c : cases) {
            JsonObject one = (JsonObject) c;
            Map<String, Object> step = one.getJsonObject("step").getMap();
            String name = one.getString("name");
            boolean call = FlowDefinition.PROCESS.equals(step.get("type"));
            FlowDefinition f = FlowDefinition.from(flow(List.of(step), Map.of("enrichOrder", block(entry("to", "name", "send", "endpoint", "p")))));
            assertEquals(List.of(call ? name + "/send" : name), names(f), one.encode());
        }
    }

    @Test
    void twoStepsOfOneListWithTheSameNameAreRefusedNamingBoth() {
        String types = refusal(flow(List.of(entry("to", "endpoint", "a"), entry("to", "endpoint", "b")), null));
        assertTrue(types.contains("steps[1]") && types.contains("'to'") && types.contains("steps[0]") && types.contains("name one of them"), types);

        String written = refusal(flow(List.of(entry("to", "name", "to", "endpoint", "a"), entry("to", "endpoint", "b")), null));
        assertTrue(written.contains("steps[1]") && written.contains("steps[0]") && written.contains("'to'"),
                "a default name clashes with a written one: " + written);

        String inner = refusal(flow(List.of(entry("process", "process", "p")), Map.of("p", block(entry("xslt", "stylesheet", "a.xsl"), entry("xslt", "stylesheet", "b.xsl")))));
        assertTrue(inner.contains("processes.p.steps[1]") && inner.contains("processes.p.steps[0]"), inner);

        String calls = refusal(flow(List.of(entry("process", "process", "p"), entry("process", "process", "p")), Map.of("p", block(entry("to", "endpoint", "a")))));
        assertTrue(calls.contains("steps[1]") && calls.contains("'p'") && calls.contains("the process it calls"), "a process called twice: " + calls);

        FlowDefinition f = FlowDefinition.from(flow(List.of(entry("to", "endpoint", "a"), entry("to", "name", "archive", "endpoint", "b")), null));
        assertEquals(List.of("to", "archive"), names(f), "naming the second step is enough");
    }

    @Test
    void noStepIsNamedIngressWrittenOrByDefaultAtAnyLevel() {
        String written = refusal(flow(List.of(entry("to", "name", "ingress", "endpoint", "a")), null));
        assertTrue(written.contains("steps[0].name") && written.contains("ingress"), written);

        String byType = refusal(flow(List.of(entry("ingress")), null));
        assertTrue(byType.contains("steps[0]") && byType.contains("ingress"), "a step type of that name: " + byType);

        String inner = refusal(flow(List.of(entry("process", "name", "enrich", "process", "p")), Map.of("p", block(entry("to", "name", "ingress", "endpoint", "a")))));
        assertTrue(inner.contains("processes.p.steps[0].name") && inner.contains("ingress"), inner);

        String call = refusal(flow(List.of(entry("process", "process", "ingress")), Map.of("ingress", block(entry("to", "endpoint", "a")))));
        assertTrue(call.contains("steps[0]") && call.contains("ingress"), "an unnamed call of a process of that name: " + call);
    }

    @Test
    void aNameOfMoreThanSixtyFourCharactersIsRefusedAndALongTypeMustBeNamed() {
        String type = "x".repeat(FlowDefinition.MAX_NAME + 1);
        String refused = refusal(flow(List.of(entry(type)), null));
        assertTrue(refused.contains("steps[0]") && refused.contains(Integer.toString(FlowDefinition.MAX_NAME)), refused);

        String written = refusal(flow(List.of(entry("to", "name", type, "endpoint", "a")), null));
        assertTrue(written.contains("steps[0].name"), written);

        assertEquals(List.of("short"), names(FlowDefinition.from(flow(List.of(entry(type, "name", "short")), null))), "a long type with a name of its own");
        String most = "x".repeat(FlowDefinition.MAX_NAME);
        assertEquals(List.of(most), names(FlowDefinition.from(flow(List.of(entry(most)), null))), "a type of the most characters is a name");
    }
}
