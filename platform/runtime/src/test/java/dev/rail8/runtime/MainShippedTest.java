package dev.rail8.runtime;

import dev.rail8.api.Component;
import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepResources;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.runtime.plugin.fixture.PluginFixtures;
import dev.rail8.steps.validate.XsdValidate;
import dev.rail8.steps.xslt.SaxonXml;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.PluginJars;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code Main.shipped(int)} is the one place that names what a node ships — the node ({@code Main.start}) and
 * the CLI both take it from there. It ships the http, sftp and direct components (no mock: a node never names one), the xslt steps of
 * {@link SaxonXml}, the xsd step of {@link XsdValidate}, the jq step and the mapping step, in that order, and the xpath language. Its argument is the
 * jq step's recursion depth for a flow that sets none. What plugins bring comes after.
 */
class MainShippedTest {

    @Test
    void shippedIsTheHttpSftpAndDirectComponentsTheXsltXsdJqAndMappingStepsAndXpath() {
        Main.Shipped shipped = Main.shipped(NodeConfig.JQ_MAX_RECURSION_DEPTH_DEFAULT);
        FakeRuntime rt = FakeRuntime.create();
        Vertx vertx = Vertx.vertx();
        try {
            List<Component> components = shipped.components().apply(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock()));

            assertEquals(List.of("http", "sftp", "direct"), components.stream().map(Component::scheme).toList(), "the components a node serves");
        } finally {
            vertx.close().await();
        }
        List<String> wired = Stream.concat(Stream.concat(new SaxonXml().steps().stream(), new XsdValidate().steps().stream()).map(StepFactory::type),
                Stream.of("jq", "mapping")).toList();
        assertEquals(wired, shipped.steps().stream().map(StepFactory::type).toList(), "the step types Main wired: SaxonXml's, XsdValidate's, jq, then mapping");
        assertEquals(new SaxonXml().languages().stream().map(Expressions::language).toList(),
                shipped.languages().stream().map(Expressions::language).toList(), "SaxonXml's languages");
        assertEquals(List.of("xpath"), shipped.languages().stream().map(Expressions::language).toList(), "xpath");
    }

    /**
     * What a plugin brings follows what the node ships: its component after http, sftp and direct, built over the node's context, its
     * step after the mapping step and its language after xpath.
     */
    @Test
    void aPluginsComponentsStepsAndLanguagesFollowTheShippedOnes(@TempDir Path dir) throws IOException {
        PluginJars.write(dir.resolve("acme.jar"), "1.0", List.of(PluginFixtures.Acme.class.getName()), PluginFixtures.classes());
        FakeRuntime rt = FakeRuntime.create();
        Vertx vertx = Vertx.vertx();
        try (Plugins plugins = Plugins.load(dir)) {
            Main.Shipped shipped = Main.shipped(NodeConfig.JQ_MAX_RECURSION_DEPTH_DEFAULT, plugins);
            List<Component> components = shipped.components().apply(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock()));
            assertEquals(List.of("http", "sftp", "direct", "acme"), components.stream().map(Component::scheme).toList());
            List<String> steps = shipped.steps().stream().map(StepFactory::type).toList();
            assertEquals(Main.shipped(NodeConfig.JQ_MAX_RECURSION_DEPTH_DEFAULT).steps().size() + 1, steps.size());
            assertEquals(List.of("mapping", "sign"), steps.subList(steps.size() - 2, steps.size()));
            assertEquals(List.of("xpath", "upper"), shipped.languages().stream().map(Expressions::language).toList());
        } finally {
            vertx.close().await();
        }
    }

    /** The argument is the jq step's recursion depth when its flow sets none: 100 holds a count-down from 50, 10 does not. */
    @Test
    void theJqStepsRecursionDepthIsTheArgument() {
        assertEquals("50", countDown(Main.shipped(100), "50"));
        assertThrows(RuntimeException.class, () -> countDown(Main.shipped(10), "50"));
    }

    private static String countDown(Main.Shipped shipped, String from) {
        FakeRuntime rt = FakeRuntime.create();
        StepFactory jq = shipped.steps().stream().filter(f -> f.type().equals("jq")).findFirst().orElseThrow();
        Stage stage = jq.create(new StepConfig(1, "jq", "count", false, Map.of("filter", "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; f")),
                new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), 64, Path.of(".")));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer(from)).advance();
        return stage.apply(in, e -> e).body().whole().toString();
    }
}
