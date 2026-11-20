package dev.rail8.node;

import dev.rail8.api.Component;
import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node's class path, which its jar's manifest and {@code target/lib} carry, names exactly the seven shipped modules —
 * direct, http, jq, mapping, sftp, xsd and xslt, in name order. Together they bring the direct, http and sftp components (no
 * mock: a node never names one), the jq, mapping, xsd and xslt steps and the xpath language, and the node's listing of its
 * modules says which module brings which. The tests' class path adds only test libraries, none of which is a module.
 */
class NodeModulesTest {

    @Test
    void theClassPathNamesTheSevenShippedModulesAndWhatTheyBring() {
        FakeRuntime rt = FakeRuntime.create();
        Vertx vertx = Vertx.vertx();
        try (Plugins plugins = Plugins.fromEnv(Map.of())) {
            List<String> lines = plugins.describe();
            Pattern line = Pattern.compile("module ([a-z]+) \\S+ \\(api " + Pattern.quote(Plugin.API) + ", sha256 (-|[0-9a-f]{64})\\) from \\S.*");
            assertTrue(lines.stream().allMatch(l -> line.matcher(l).matches()), lines.toString());
            assertEquals(List.of("direct", "http", "jq", "mapping", "sftp", "xsd", "xslt"),
                    lines.stream().map(l -> l.split(" ")[1]).toList(), "exactly the shipped modules, in name order");
            ComponentContext context = new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock(), HttpIngress.NONE);
            assertEquals(List.of("direct", "http", "sftp"), plugins.components(context).stream().map(Component::scheme).toList(),
                    "the components a node serves");
            assertEquals(List.of("jq", "mapping", "xsd", "xslt"), plugins.steps().stream().map(StepFactory::type).toList());
            assertEquals(List.of("xpath"), plugins.languages().stream().map(Expressions::language).toList());
            assertEquals(List.of(
                            List.of("direct", List.of("direct"), List.of(), List.of()),
                            List.of("http", List.of("http"), List.of(), List.of()),
                            List.of("jq", List.of(), List.of("jq"), List.of()),
                            List.of("mapping", List.of(), List.of("mapping"), List.of()),
                            List.of("sftp", List.of("sftp"), List.of(), List.of()),
                            List.of("xsd", List.of(), List.of("xsd"), List.of()),
                            List.of("xslt", List.of(), List.of("xslt"), List.of("xpath"))),
                    plugins.list().stream().map(m -> List.of(m.name(), m.schemes(), m.steps(), m.languages())).toList(),
                    "what each module brings, as GET /admin/modules lists it");
            assertEquals(Map.of(), plugins.commands(), "no shipped module brings a command; the first that brings one adds its run to LauncherIT");
        } finally {
            vertx.close().await();
        }
    }
}
