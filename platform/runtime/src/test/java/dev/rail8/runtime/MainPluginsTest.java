package dev.rail8.runtime;

import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.plugin.fixture.PluginFixtures;
import dev.rail8.testkit.PluginJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Main.start} with the plugins of {@code RAIL8_PLUGINS}, where it ends before Postgres: a plugin directory, plugin, step
 * type or language that is refused refuses the configuration (2) before Vert.x starts; a plugin's component that is refused
 * stops the node's start (1) before it connects anywhere. Either way nothing is handed back.
 */
class MainPluginsTest {

    @TempDir Path dir;

    Map<String, String> env(Path plugins) {
        return Map.of("RAIL8_ENV", "dev", "RAIL8_NODE_ID", "plugins-test", "RAIL8_DATA_DIR", dir.resolve("data").toString(),
                "RAIL8_PLUGINS", plugins.toString());
    }

    record Run(Main.Started started, String err) {}

    Run start(Path plugins) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Main.Started started;
        try (PrintStream stream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            started = OnVirtualThread.call(() -> Main.start(env(plugins), stream));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(started.node().isEmpty() && started.vertx().isEmpty() && started.plugins().isEmpty(), "nothing handed back: " + started);
        return new Run(started, err.toString(StandardCharsets.UTF_8));
    }

    Path plugin(String entry, Class<?> provider) throws IOException {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        PluginJars.write(plugins.resolve(entry), "1.0", List.of(provider.getName()), PluginFixtures.classes());
        return plugins;
    }

    @Test
    void aPluginDirectoryThatIsNoneRefusesTheConfiguration() {
        Path missing = dir.resolve("missing");
        Run run = start(missing);
        assertEquals(2, run.started().code());
        assertEquals("rail8: configuration refused: RAIL8_PLUGINS names no directory: " + missing + System.lineSeparator(), run.err());
    }

    @Test
    void aPluginsStepTypeTheNodeShipsRefusesTheConfiguration() throws IOException {
        Run run = start(plugin("xslt.jar", PluginFixtures.ShippedStepType.class));
        assertEquals(2, run.started().code());
        assertTrue(run.err().startsWith("rail8: configuration refused: plugin shipped-step: the step type xslt is rail8's already"), run.err());
    }

    @Test
    void aPluginsComponentOfAShippedSchemeStopsTheStart() throws IOException {
        Run run = start(plugin("http.jar", PluginFixtures.ShippedScheme.class));
        assertEquals(1, run.started().code());
        assertTrue(run.err().startsWith("rail8: the node did not start: ") && run.err().contains("plugin shipped-scheme: the scheme http is rail8's already"),
                run.err());
    }
}
