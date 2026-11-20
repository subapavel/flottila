package dev.rail8.runtime;

import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.plugin.fixture.PluginFixtures;
import dev.rail8.testkit.PluginJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Main.start} with its modules and the plugins of {@code RAIL8_PLUGINS}, where it ends before Postgres: a plugin
 * directory, plugin, setting, step type, language or admin service that is refused refuses the configuration (2) before Vert.x starts; a
 * plugin's component that is refused stops the node's start (1) before it connects anywhere. Either way nothing is handed back
 * and the plugins' class loaders are closed again: the process holds none of their jars open.
 */
class MainPluginsTest {

    @TempDir Path dir;

    Map<String, String> env(Path plugins, Map<String, String> more) {
        Map<String, String> env = new HashMap<>(Map.of("RAIL8_ENV", "dev", "RAIL8_NODE_ID", "plugins-test", "RAIL8_DATA_DIR",
                dir.resolve("data").toString(), "RAIL8_PLUGINS", plugins.toString()));
        env.putAll(more);
        return env;
    }

    record Run(Main.Started started, String err) {}

    Run start(Path plugins) {
        return start(plugins, Map.of());
    }

    Run start(Path plugins, Map<String, String> more) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Main.Started started;
        try (PrintStream stream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            started = OnVirtualThread.call(() -> Main.start(env(plugins, more), stream));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(started.node().isEmpty() && started.vertx().isEmpty() && started.plugins().isEmpty(), "nothing handed back: " + started);
        assertEquals(List.of(), openFiles(plugins), "the plugins' class loaders are closed");
        return new Run(started, err.toString(StandardCharsets.UTF_8));
    }

    /**
     * The files under {@code plugins} this process holds open, from the links of {@code /proc/self/fd}; none where there is no
     * such directory (a system other than Linux) or no {@code plugins}.
     */
    static List<Path> openFiles(Path plugins) {
        Path fds = Path.of("/proc/self/fd");
        if (!Files.isDirectory(fds) || !Files.isDirectory(plugins)) return List.of();
        try (Stream<Path> links = Files.list(fds)) {
            Path real = plugins.toRealPath();
            return links.flatMap(fd -> {
                try {
                    return Stream.of(Files.readSymbolicLink(fd));
                } catch (IOException closedMeanwhile) {
                    return Stream.empty();
                }
            }).filter(target -> target.startsWith(real)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        assertTrue(run.err().startsWith("rail8: configuration refused: plugin shipped-step: the step type xslt is module xslt's already"), run.err());
    }

    @Test
    void aModulesSettingOutOfItsRangeRefusesTheConfigurationNamingIt() throws IOException {
        Run run = start(Files.createDirectories(dir.resolve("plugins")), Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", "4194305"));
        assertEquals(2, run.started().code());
        assertEquals("rail8: configuration refused: module jq: steps() failed: java.lang.IllegalArgumentException: "
                + "RAIL8_JQ_MAX_RECURSION_DEPTH must be between 1 and 4194304, was 4194305" + System.lineSeparator(), run.err());
    }

    @Test
    void aPluginThatBringsAnAdminServiceRefusesTheConfiguration() throws IOException {
        Run run = start(plugin("serves.jar", PluginFixtures.Serves.class));
        assertEquals(2, run.started().code());
        assertEquals("rail8: configuration refused: plugin serves: services() brings 2 admin services; only a module brings them, no plugin of RAIL8_PLUGINS"
                + System.lineSeparator(), run.err());
    }

    @Test
    void aPluginThatBringsACommandRefusesTheConfiguration() throws IOException {
        Run run = start(plugin("sample.jar", PluginFixtures.Commands.class));
        assertEquals(2, run.started().code());
        assertEquals("rail8: configuration refused: plugin sample: command() brings a command; only a module brings one, no plugin of RAIL8_PLUGINS"
                + System.lineSeparator(), run.err());
    }

    @Test
    void aPluginsComponentOfAShippedSchemeStopsTheStart() throws IOException {
        Run run = start(plugin("http.jar", PluginFixtures.ShippedScheme.class));
        assertEquals(1, run.started().code());
        assertTrue(run.err().startsWith("rail8: the node did not start: ") && run.err().contains("plugin shipped-scheme: the scheme http is module http's already"),
                run.err());
    }
}
