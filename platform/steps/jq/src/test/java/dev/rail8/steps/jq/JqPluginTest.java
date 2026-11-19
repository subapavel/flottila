package dev.rail8.steps.jq;

import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepResources;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.api.plugin.PluginSettings;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The jq module: found on the class path as a {@link Plugin} named {@code jq}, it brings the jq step, whose recursion depth for a
 * flow that sets none is the setting {@code RAIL8_JQ_MAX_RECURSION_DEPTH}: 10 000 unset, 1 to 4 194 304 as written.
 */
class JqPluginTest {

    final FakeRuntime rt = FakeRuntime.create();

    @Test
    void theModuleIsFoundOnTheClassPathAndBringsTheJqStepOnly() {
        List<Plugin> found = ServiceLoader.load(Plugin.class).stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(List.of(JqPlugin.class), found.stream().map(Object::getClass).toList());
        Plugin jq = found.getFirst();
        assertEquals("jq", jq.name());
        assertEquals(Plugin.API, jq.api());
        assertEquals(List.of("jq"), jq.steps(PluginSettings.of("jq", Map.of())).stream().map(StepFactory::type).toList());
        assertEquals(List.of(), jq.languages());
    }

    @Test
    void theDepthIsTenThousandUnsetAndTheSettingOtherwise() {
        JqPlugin jq = new JqPlugin();
        assertEquals("5000", countDown(jq.steps(PluginSettings.of("jq", Map.of())), "5000"));
        assertThrows(RuntimeException.class, () -> countDown(jq.steps(PluginSettings.of("jq", Map.of())), "20000"));
        Map<String, String> shallow = Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", "10");
        assertThrows(RuntimeException.class, () -> countDown(jq.steps(PluginSettings.of("jq", shallow)), "50"));
        assertEquals("50", countDown(jq.steps(PluginSettings.of("jq", Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", "100"))), "50"));
        assertEquals("20000", countDown(jq.steps(PluginSettings.of("jq", Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", "4194304"))), "20000"));
    }

    @Test
    void aDepthOutOfItsRangeOrUnparseableIsRefusedNamingTheVariable() {
        for (String value : List.of("0", "-1", "4194305", "deep", "")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new JqPlugin().steps(PluginSettings.of("jq", Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", value))));
            assertEquals(true, e.getMessage().startsWith("RAIL8_JQ_MAX_RECURSION_DEPTH must be "), e.getMessage());
        }
    }

    /** What the jq step of {@code steps} answers to a count-down from {@code from}: one live activation per level. */
    private String countDown(List<StepFactory> steps, String from) {
        StepFactory jq = steps.getFirst();
        StepResources resources = new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), 64, Path.of("."));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer(from)).advance();
        return jq.create(new StepConfig(1, "jq", "count", false, Map.of("filter", JqStepFactoryTest.COUNT_DOWN)), resources)
                .apply(in, e -> e).body().whole().toString();
    }
}
