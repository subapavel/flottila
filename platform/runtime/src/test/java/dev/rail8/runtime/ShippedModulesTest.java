package dev.rail8.runtime;

import dev.rail8.api.Component;
import dev.rail8.api.Envelope;
import dev.rail8.api.Expressions;
import dev.rail8.api.Headers;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.runtime.plugin.fixture.PluginFixtures;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.PluginJars;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a node ships, it finds as a plugin finds its way in: the node's class path names exactly the seven shipped modules —
 * direct, http, jq, mapping, sftp, xsd and xslt, in name order — and nothing in the runtime names one. Together they bring the
 * direct, http and sftp components (no mock: a node never names one), the jq, mapping, xsd and xslt steps and the xpath
 * language. A plugin of {@code RAIL8_PLUGINS} comes after them and takes nothing of theirs; the jq module reads its own setting.
 */
class ShippedModulesTest {

    static final FakeRuntime RT = FakeRuntime.create();
    static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() {
        vertx.close().await();
    }

    static ComponentContext context() {
        return new ComponentContext(vertx, RT.blobs(), RT.ids(), RT.timer(), RT.clock());
    }

    static List<String> types(List<StepFactory> steps) {
        return steps.stream().map(StepFactory::type).toList();
    }

    static List<String> schemes(List<Component> components) {
        return components.stream().map(Component::scheme).toList();
    }

    static Path plugin(Path dir, Class<?> provider) throws IOException {
        PluginJars.write(dir.resolve(provider.getSimpleName() + ".jar"), "1.0", List.of(provider.getName()), PluginFixtures.classes());
        return dir;
    }

    @Test
    void theClassPathNamesTheSevenShippedModulesAndWhatTheyBring() {
        try (Plugins plugins = Plugins.fromEnv(Map.of())) {
            List<String> lines = plugins.describe();
            Pattern line = Pattern.compile("module ([a-z]+) \\S+ \\(api " + Pattern.quote(Plugin.API) + ", sha256 (-|[0-9a-f]{64})\\) from \\S.*");
            assertTrue(lines.stream().allMatch(l -> line.matcher(l).matches()), lines.toString());
            assertEquals(List.of("direct", "http", "jq", "mapping", "sftp", "xsd", "xslt"),
                    lines.stream().map(l -> l.split(" ")[1]).toList(), "exactly the shipped modules, in name order");
            assertEquals(List.of("direct", "http", "sftp"), schemes(plugins.components(context())), "the components a node serves");
            assertEquals(List.of("jq", "mapping", "xsd", "xslt"), types(plugins.steps()));
            assertEquals(List.of("xpath"), plugins.languages().stream().map(Expressions::language).toList());
        }
    }

    @Test
    void aPluginsContributionsFollowTheModules(@TempDir Path dir) throws IOException {
        try (Plugins plugins = Plugins.fromEnv(Map.of(Plugins.ENV, plugin(dir, PluginFixtures.Acme.class).toString()))) {
            assertEquals(List.of("direct", "http", "sftp", "acme"), schemes(plugins.components(context())));
            assertEquals(List.of("jq", "mapping", "xsd", "xslt", "sign"), types(plugins.steps()));
            assertEquals(List.of("xpath", "upper"), plugins.languages().stream().map(Expressions::language).toList());
            assertTrue(plugins.describe().getLast().startsWith("plugin acme 1.0 "), plugins.describe().toString());
        }
    }

    @Test
    void aPluginBringingAShippedStepTypeOrSchemeIsRefusedNamingBothOwners(@TempDir Path dir) throws IOException {
        Path steps = plugin(Files.createDirectory(dir.resolve("step")), PluginFixtures.ShippedStepType.class);
        IllegalArgumentException step = assertThrows(IllegalArgumentException.class, () -> Plugins.fromEnv(Map.of(Plugins.ENV, steps.toString())));
        assertEquals("plugin shipped-step: the step type xslt is module xslt's already; a plugin adds and never replaces", step.getMessage());
        Path schemes = plugin(Files.createDirectory(dir.resolve("scheme")), PluginFixtures.ShippedScheme.class);
        try (Plugins plugins = Plugins.fromEnv(Map.of(Plugins.ENV, schemes.toString()))) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(context())).getMessage();
            assertEquals("plugin shipped-scheme: the scheme http is module http's already; a plugin adds and never replaces", refused);
        }
    }

    @Test
    void aBlankPluginDirectoryLoadsTheModulesAlone() {
        try (Plugins unset = Plugins.fromEnv(Map.of()); Plugins blank = Plugins.fromEnv(Map.of(Plugins.ENV, " "))) {
            assertEquals(unset.describe(), blank.describe());
        }
    }

    /** {@code RAIL8_JQ_MAX_RECURSION_DEPTH} is the jq module's: 100 holds a count-down from 50, 10 does not, 0 is refused. */
    @Test
    void theJqModuleReadsItsRecursionDepthFromTheEnvironment() {
        try (Plugins deep = Plugins.fromEnv(Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", "100"));
             Plugins shallow = Plugins.fromEnv(Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", "10"))) {
            assertEquals("50", countDown(deep, "50"));
            assertThrows(RuntimeException.class, () -> countDown(shallow, "50"));
        }
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Plugins.fromEnv(Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", "0")));
        assertEquals("module jq: steps() failed: java.lang.IllegalArgumentException: RAIL8_JQ_MAX_RECURSION_DEPTH must be between 1 and 4194304, was 0",
                refused.getMessage());
    }

    private static String countDown(Plugins plugins, String from) {
        StepFactory jq = plugins.steps().stream().filter(f -> f.type().equals("jq")).findFirst().orElseThrow();
        Stage stage = jq.create(new StepConfig(1, "jq", "count", false, Map.of("filter", "def f: if . == 0 then 0 else (. - 1 | f) + 1 end; f")),
                new StepResources(RT.clock(), RT.timer(), RT.ids(), RT.blobs(), RT.hasher(), 64, Path.of(".")));
        Envelope in = RT.ingress("f", Headers.empty(), Buffer.buffer(from)).advance();
        return stage.apply(in, e -> e).body().whole().toString();
    }
}
