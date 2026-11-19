package dev.rail8.core.flow;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The names that start with {@code rail8.} are the platform's own keys and props: a flow file may not name one as the key
 * of an {@code extractKey}, the prop of a {@code route}, the {@code byProp} of a {@code to}, or its {@code partitionBy},
 * in its steps or in a process's. The match is case-sensitive, like keys and props; a name that only contains it is allowed.
 */
class FlowDefinitionReservedPrefixTest {

    private static Map<String, Object> step(String type, Object... options) {
        Map<String, Object> s = new HashMap<>();
        s.put("type", type);
        for (int i = 0; i < options.length; i += 2) s.put((String) options[i], options[i + 1]);
        return s;
    }

    private static Map<String, Object> flow(List<?> steps, Map<String, ?> processes, String partitionBy) {
        Map<String, Object> doc = new HashMap<>(FlowDefinitionTest.demo());
        doc.put("steps", steps);
        if (processes != null) doc.put("processes", processes);
        if (partitionBy == null) doc.remove("partitionBy");
        else doc.put("partitionBy", partitionBy);
        return doc;
    }

    private static Map<String, Object> flow(Object... steps) {
        return flow(List.of(steps), null, null);
    }

    private static String refusal(Map<String, Object> doc) {
        return assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(doc)).getMessage();
    }

    private static final Map<String, Object> KEY = step("extractKey", "key", "rail8.ref", "xpath", "/Order/Id");
    private static final Map<String, Object> ROUTE = step("route", "prop", "rail8.depth", "routes", List.of(Map.of("when", "true()", "set", "a")));
    private static final Map<String, Object> TO = step("to", "byProp", "rail8.partner", "endpoints", Map.of("a", "partner-a"));

    @Test
    void theKeyOfAnExtractKeyIsRefused() {
        assertEquals("steps[1].key 'rail8.ref' starts with 'rail8.', which is reserved for the platform's own keys and props: name it otherwise",
                refusal(flow(step("validate", "required", "/Order"), KEY)));
    }

    @Test
    void thePropOfARouteIsRefused() {
        assertEquals("steps[0].prop 'rail8.depth' starts with 'rail8.', which is reserved for the platform's own keys and props: name it otherwise",
                refusal(flow(ROUTE)));
    }

    @Test
    void theByPropOfAToIsRefused() {
        assertEquals("steps[0].byProp 'rail8.partner' starts with 'rail8.', which is reserved for the platform's own keys and props: name it otherwise",
                refusal(flow(TO)));
    }

    @Test
    void thePartitionByIsRefused() {
        assertEquals("partitionBy 'rail8.ref' starts with 'rail8.', which is reserved for the platform's own keys and props: name it otherwise",
                refusal(flow(List.of(step("extractKey", "key", "orderId", "xpath", "/Order/Id")), null, "rail8.ref")));
    }

    @Test
    void aNameInAProcessIsRefusedAtItsPathInTheProcess() {
        Map<String, Object> doc = flow(List.of(step("process", "process", "enrich")), Map.of("enrich", Map.of("steps", List.of(KEY))), null);

        assertEquals("processes.enrich.steps[0].key 'rail8.ref' starts with 'rail8.', which is reserved for the platform's own keys and props: name it otherwise",
                refusal(doc));
    }

    @Test
    void aNameThatOnlyContainsThePrefixOrDiffersInCaseIsAllowed() {
        assertDoesNotThrow(() -> FlowDefinition.from(flow(List.of(
                step("extractKey", "key", "order.rail8.ref", "xpath", "/Order/Id"),
                step("route", "prop", "Rail8.depth", "routes", List.of(Map.of("when", "true()", "set", "a"))),
                step("to", "byProp", "rail8", "endpoints", Map.of("a", "partner-a"))), null, "RAIL8.ref")));
    }
}
