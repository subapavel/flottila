package dev.rail8.steps.jq;

import dev.rail8.api.BodyTooLargeException;
import dev.rail8.api.Envelope;
import dev.rail8.api.Fault;
import dev.rail8.api.Headers;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepResources;
import dev.rail8.jq.JqTermination;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The jq step: one filter compiled per step, exactly one compact JSON result per message, within the step's budgets. */
class JqStepFactoryTest {

    static final String ORDER = "{\"order\":{\"id\":42},\"items\":[{\"price\":10},{\"price\":20}]}";

    /** Recursion that is not a tail call: each level of {@code n} holds one more live activation. */
    static final String COUNT_DOWN = "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; f";

    final FakeRuntime rt = FakeRuntime.create();
    final JqStepFactory factory = new JqStepFactory(JqStepFactory.DEFAULT_MAX_RECURSION_DEPTH);

    StepResources resources(long maxBodyBytes) {
        return new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), maxBodyBytes, Path.of("."));
    }

    static StepConfig step(String filter) { return step(filter, null); }

    static StepConfig step(String filter, Object maxRecursionDepth) {
        Map<String, Object> config = new HashMap<>();
        config.put("filter", filter);
        if (maxRecursionDepth != null) config.put("maxRecursionDepth", maxRecursionDepth);
        return new StepConfig(2, "jq", "toPartner", false, config);
    }

    Envelope message(String body) {
        return rt.ingress("orders", Headers.empty().with("x-request-id", "r1"), Buffer.buffer(body)).advance();
    }

    Envelope apply(JqStepFactory on, StepConfig step, long maxBodyBytes, String body) {
        Stage stage = on.create(step, resources(maxBodyBytes));
        return stage.apply(message(body), e -> e);
    }

    @Test
    void transformsTheBodyIntoOneCompactJsonResult() {
        assertEquals("jq", factory.type());
        assertTrue(factory.configSchema().contains("\"filter\"") && factory.configSchema().contains("\"maxRecursionDepth\""));
        Envelope in = message(ORDER);
        Envelope out = factory.create(step("{id: .order.id, total: (.items | map(.price) | add)}"), resources(65536)).apply(in, e -> e);
        assertEquals("{\"id\":42,\"total\":30}", out.body().whole().toString());
        assertEquals("application/json", out.header(Envelope.H_CONTENT_TYPE).orElseThrow());
        assertEquals("r1", out.header("x-request-id").orElseThrow(), "headers carried over");
        assertEquals(in.id(), out.id());
        assertEquals(2, out.step());
        assertEquals(2, out.body().key().step(), "written under the envelope's next key");
    }

    @Test
    void oneStageRunsManyMessages() {
        Stage stage = factory.create(step(".n + 1"), resources(64));
        for (int n = 0; n < 3; n++) {
            assertEquals(String.valueOf(n + 1), stage.apply(message("{\"n\":" + n + "}"), e -> e).body().whole().toString());
        }
    }

    @Test
    void aFilterThatDoesNotCompileIsABuildErrorNamingTheStepAndTheKey() {
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> factory.create(step(".a |"), resources(64)));
        assertTrue(bad.getMessage().startsWith("step 'toPartner' (steps[1], jq): 'filter' does not compile"), bad.getMessage());
        IllegalArgumentException variable = assertThrows(IllegalArgumentException.class, () -> factory.create(step("$x"), resources(64)));
        assertTrue(variable.getMessage().contains("'filter' does not compile"), "no host variables: " + variable.getMessage());
        assertThrows(IllegalArgumentException.class, () -> factory.create(new StepConfig(2, "jq", "toPartner", false, Map.of()), resources(64)),
                "filter is required");
    }

    @Test
    void aBodyThatIsNotOneJsonDocumentIsMalformed() {
        for (String body : new String[] {"<Order/>", "", "{} {}"}) {
            JqStepException x = assertThrows(JqStepException.class, () -> apply(factory, step("."), 64, body), body);
            assertEquals(Fault.Kind.MALFORMED, x.kind(), body);
            assertTrue(x.getMessage().contains("the body is not one JSON document"), x.getMessage());
        }
    }

    @Test
    void aBodyNestedDeeperThanTheEnginesLimitIsMalformed() {
        String deep = "[".repeat(10_001) + "]".repeat(10_001);
        JqStepException x = assertThrows(JqStepException.class, () -> apply(factory, step("."), 1 << 20, deep));
        assertEquals(Fault.Kind.MALFORMED, x.kind());
    }

    @Test
    void anErrorTheFilterRaisesIsTheFlowsFailure() {
        JqStepException x = assertThrows(JqStepException.class, () -> apply(factory, step("error(\"no customer\")"), 64, ORDER));
        assertEquals(Fault.Kind.INTERNAL, x.kind());
        assertTrue(x.getMessage().contains("the filter raised an error: no customer"), x.getMessage());
        JqStepException value = assertThrows(JqStepException.class, () -> apply(factory, step("error({code: 7})"), 64, ORDER));
        assertTrue(value.getMessage().contains("the filter raised an error: {\"code\":7}"), value.getMessage());
        JqStepException type = assertThrows(JqStepException.class, () -> apply(factory, step(".order + 1"), 64, ORDER));
        assertEquals(Fault.Kind.INTERNAL, type.kind());
    }

    @Test
    void theFilterGivesExactlyOneResult() {
        JqStepException none = assertThrows(JqStepException.class, () -> apply(factory, step("empty"), 64, ORDER));
        assertEquals(Fault.Kind.INTERNAL, none.kind());
        assertTrue(none.getMessage().contains("the filter gave no result"), none.getMessage());
        // the run stops at the second result: the error after it is never reached
        JqStepException two = assertThrows(JqStepException.class, () -> apply(factory, step("1, 2, error(\"never\")"), 64, ORDER));
        assertEquals(Fault.Kind.INTERNAL, two.kind());
        assertTrue(two.getMessage().contains("the filter gave more than one result"), two.getMessage());
    }

    @Test
    void theRecursionDepthIsTheInstallationsUnlessTheStepSetsItsOwn() {
        String input = "20000";
        JqStepException tooDeep = assertThrows(JqStepException.class, () -> apply(factory, step(COUNT_DOWN), 64, input));
        assertEquals(Fault.Kind.INTERNAL, tooDeep.kind());
        assertTrue(tooDeep.getMessage().contains("the filter was stopped"), tooDeep.getMessage());
        assertEquals(JqTermination.Budget.RECURSION_DEPTH, assertInstanceOf(JqTermination.class, tooDeep.getCause()).budget());

        assertEquals("20000", apply(factory, step(COUNT_DOWN, 30_000), 64, input).body().whole().toString(), "the step's own depth, above the default");
        JqStepFactory deepInstallation = new JqStepFactory(30_000);
        assertEquals("20000", apply(deepInstallation, step(COUNT_DOWN), 64, input).body().whole().toString(), "the installation's depth");
        JqStepException shallow = assertThrows(JqStepException.class, () -> apply(deepInstallation, step(COUNT_DOWN, 100), 64, "1000"),
                "the step's own depth, below the installation's");
        assertEquals(JqTermination.Budget.RECURSION_DEPTH, assertInstanceOf(JqTermination.class, shallow.getCause()).budget());
        assertEquals("50", apply(deepInstallation, step(COUNT_DOWN, 100), 64, "50").body().whole().toString());
    }

    @Test
    void aDepthOutsideItsRangeIsRefused() {
        for (Object bad : new Object[] {0, -1, 4_194_305, 4_194_305L, "100", 1.5}) {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> factory.create(step(".", bad), resources(64)), String.valueOf(bad));
            assertTrue(x.getMessage().contains("'maxRecursionDepth' must be an integer from 1 to 4194304"), x.getMessage());
        }
        assertEquals("1", apply(factory, step(".", 4_194_304L), 64, "1").body().whole().toString(), "the largest depth, as a long");
        assertThrows(IllegalArgumentException.class, () -> new JqStepFactory(0));
        assertThrows(IllegalArgumentException.class, () -> new JqStepFactory(JqStepFactory.MAX_RECURSION_DEPTH + 1));
    }

    @Test
    void anOutputAboveTheFlowsBodyLimitIsTooLarge() {
        assertEquals("\"12345678\"", apply(factory, step("\"12345678\""), 10, "null").body().whole().toString(), "exactly the limit fits");
        assertThrows(BodyTooLargeException.class, () -> apply(factory, step("\"123456789\""), 10, "null"));
        assertThrows(BodyTooLargeException.class, () -> apply(factory, step("[range(100000)]"), 1000, "null"),
                "stopped by the bytes-written budget before the whole output is held");
    }
}
