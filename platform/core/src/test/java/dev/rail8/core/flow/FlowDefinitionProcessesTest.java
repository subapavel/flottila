package dev.rail8.core.flow;

import dev.rail8.api.StepConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local processes: a flow file names blocks of steps under {@code processes}, and a step of type {@code process} stands for the
 * steps of one. The definition's steps are the run's steps with every call replaced by its process's steps, numbered in run
 * order and named {@code <call>/<step>}; the call itself is no step.
 */
class FlowDefinitionProcessesTest {

    static Map<String, Object> step(String type, String name, Object... options) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        s.put("name", name);
        for (int i = 0; i < options.length; i += 2) s.put((String) options[i], options[i + 1]);
        return s;
    }

    static Map<String, Object> call(String name, String process) {
        return step("process", name, "process", process);
    }

    static Map<String, Object> block(Object... steps) {
        return Map.of("steps", List.of(steps));
    }

    static Map<String, Object> flow(List<?> steps, Map<String, ?> processes) {
        Map<String, Object> doc = new HashMap<>(FlowDefinitionTest.demo());
        doc.put("steps", steps);
        if (processes != null) doc.put("processes", processes);
        return doc;
    }

    static String refusal(Map<String, Object> doc) {
        return assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(doc)).getMessage();
    }

    @Test
    void aCallIsReplacedByItsProcessStepsNumberedInRunOrderAndNamedAfterTheCall() {
        FlowDefinition f = FlowDefinition.from(flow(
                List.of(step("validate", "check", "required", "/Order/Id"), call("enrich", "enrichOrder"), step("to", "dispatch", "endpoint", "partner")),
                Map.of("enrichOrder", block(
                        step("extractKey", "orderId", "key", "orderId", "xpath", "/Order/Id"),
                        step("xslt", "map", "retryable", true, "stylesheet", "flows/enrich.xsl")))));

        assertEquals(List.of("1 check", "2 enrich/orderId", "3 enrich/map", "4 dispatch"), indexed(f.steps()));
        StepConfig map = f.steps().get(2);
        assertEquals("xslt", map.type());
        assertTrue(map.retryable(), "retryable is the inner step's own");
        assertEquals(Map.of("stylesheet", "flows/enrich.xsl"), map.config(), "an inner step's options are its own, unchanged");
        assertEquals(Map.of("key", "orderId", "xpath", "/Order/Id"), f.steps().get(1).config());
    }

    @Test
    void aProcessMayCallAnotherAndBeCalledTwiceEachCallUnderItsOwnName() {
        FlowDefinition f = FlowDefinition.from(flow(
                List.of(call("first", "outer"), call("second", "inner")),
                Map.of("outer", block(step("filter", "keep", "when", "/a"), call("deep", "inner")),
                        "inner", block(step("to", "send", "endpoint", "partner")))));

        assertEquals(List.of("1 first/keep", "2 first/deep/send", "3 second/send"), indexed(f.steps()));
        assertEquals(List.of("partner"), f.referencedEndpoints(), "a send inside a process is a send of the flow");
    }

    @Test
    void aProcessNoStepCallsIsAllowed() {
        FlowDefinition f = FlowDefinition.from(flow(List.of(step("to", "out", "endpoint", "partner")),
                Map.of("spare", block(step("to", "elsewhere", "endpoint", "other")))));

        assertEquals(List.of("1 out"), indexed(f.steps()));
        assertEquals(List.of("partner"), f.referencedEndpoints(), "a process nobody calls sends nothing");
    }

    @Test
    void aProcessThatCallsItselfIsRefusedNamingThePath() {
        String direct = refusal(flow(List.of(call("loop", "again")), Map.of("again", block(call("more", "again")))));
        assertTrue(direct.contains("processes.again.steps[0]") && direct.contains("again -> again"), direct);

        String through = refusal(flow(List.of(call("go", "a")), Map.of("a", block(call("toB", "b")), "b", block(call("toA", "a")))));
        assertTrue(through.contains("processes.b.steps[0]") && through.contains("a -> b -> a"), through);

        String unused = refusal(flow(List.of(step("to", "out", "endpoint", "partner")), Map.of("spare", block(call("self", "spare")))));
        assertTrue(unused.contains("processes.spare.steps[0]") && unused.contains("spare -> spare"),
                "a process nobody calls is still checked: " + unused);
    }

    @Test
    void aCallToAProcessTheFlowDoesNotNameIsRefused() {
        String top = refusal(flow(List.of(call("enrich", "missing")), Map.of("other", block(step("to", "out", "endpoint", "partner")))));
        assertTrue(top.contains("steps[0].process") && top.contains("missing"), top);

        String none = refusal(flow(List.of(call("enrich", "missing")), null));
        assertTrue(none.contains("steps[0].process") && none.contains("missing"), none);

        String inner = refusal(flow(List.of(call("enrich", "a")), Map.of("a", block(step("to", "out", "endpoint", "partner"), call("deep", "gone")))));
        assertTrue(inner.contains("processes.a.steps[1].process") && inner.contains("gone"), inner);
    }

    @Test
    void aCallTakesOnlyTypeNameAndProcess() {
        for (String key : List.of("retryable", "recording", "endpoint")) {
            Map<String, Object> c = call("enrich", "a");
            c.put(key, "retryable".equals(key) ? Boolean.TRUE : "x");
            String refused = refusal(flow(List.of(c), Map.of("a", block(step("to", "out", "endpoint", "partner")))));
            assertTrue(refused.contains("steps[0]") && refused.contains(key), key + ": " + refused);
        }
        String noProcess = refusal(flow(List.of(step("process", "enrich")), Map.of("a", block(step("to", "out", "endpoint", "partner")))));
        assertTrue(noProcess.contains("steps[0].process"), noProcess);
    }

    @Test
    void stepNamesAreUniqueInTheFlowAndInEachProcess() {
        String top = refusal(flow(List.of(call("same", "a"), step("to", "same", "endpoint", "partner")), Map.of("a", block(step("to", "out", "endpoint", "partner")))));
        assertTrue(top.contains("steps[1].name") && top.contains("same"), top);

        String inner = refusal(flow(List.of(call("enrich", "a")), Map.of("a", block(step("to", "twice", "endpoint", "p"), step("to", "twice", "endpoint", "q")))));
        assertTrue(inner.contains("processes.a.steps[1].name") && inner.contains("twice"), inner);

        FlowDefinition f = FlowDefinition.from(flow(List.of(call("send", "a"), step("to", "out", "endpoint", "partner")),
                Map.of("a", block(step("to", "out", "endpoint", "other")))));
        assertEquals(List.of("1 send/out", "2 out"), indexed(f.steps()), "a process's step may share a name with a flow step: the call names it apart");
    }

    @Test
    void theProcessesAndTheirStepsOfTheWrongShapeAreNamed() {
        Map<String, Object> listed = flow(List.of(call("enrich", "a")), null);
        listed.put("processes", List.of("a"));
        String notAnObject = refusal(listed);
        assertTrue(notAnObject.contains("processes"), notAnObject);

        String noSteps = refusal(flow(List.of(call("enrich", "a")), Map.of("a", Map.of())));
        assertTrue(noSteps.contains("processes.a.steps"), noSteps);

        String emptySteps = refusal(flow(List.of(call("enrich", "a")), Map.of("a", Map.of("steps", List.of()))));
        assertTrue(emptySteps.contains("processes.a.steps"), emptySteps);

        String badStep = refusal(flow(List.of(call("enrich", "a")), Map.of("a", block("validate"))));
        assertTrue(badStep.contains("processes.a.steps[0]"), badStep);

        String noType = refusal(flow(List.of(call("enrich", "a")), Map.of("a", block(Map.of("name", "x")))));
        assertTrue(noType.contains("processes.a.steps[0].type"), noType);
    }

    @Test
    void aFlowOfMoreThanTheMostStepsOnceExpandedIsRefusedWithoutExpandingItWhole() {
        List<Object> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) sixteen.add(step("to", "s" + i, "endpoint", "partner"));
        Map<String, Object> processes = new HashMap<>();
        processes.put("p0", Map.of("steps", sixteen));
        for (int level = 1; level <= 40; level++) {
            processes.put("p" + level, block(call("a", "p" + (level - 1)), call("b", "p" + (level - 1))));
        }
        String refused = refusal(flow(List.of(call("all", "p40")), processes));
        assertTrue(refused.contains(Integer.toString(FlowDefinition.MAX_STEPS)), "16 * 2^40 steps are refused at the cap, not expanded: " + refused);

        List<Object> most = new ArrayList<>();
        for (int i = 0; i < FlowDefinition.MAX_STEPS; i++) most.add(step("to", "s" + i, "endpoint", "partner"));
        assertEquals(FlowDefinition.MAX_STEPS, FlowDefinition.from(flow(most, null)).steps().size(), "the cap itself is allowed");
        most.add(step("to", "one-more", "endpoint", "partner"));
        assertTrue(refusal(flow(most, null)).contains(Integer.toString(FlowDefinition.MAX_STEPS)), "a flow without processes has the same cap");
    }

    private static List<String> indexed(List<StepConfig> steps) {
        return steps.stream().map(s -> s.index() + " " + s.name()).toList();
    }
}
