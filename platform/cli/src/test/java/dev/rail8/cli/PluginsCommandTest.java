package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import dev.rail8.cli.fixture.CliPlugins;
import dev.rail8.testkit.PluginJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.rail8.cli.CliRuns.FIXTURES;
import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rail8 validate} and {@code rail8 test} with plugins: those of {@code --plugins}, else of {@code RAIL8_PLUGINS}. The
 * package {@code plugin} sends to a partner of the plugin acme's scheme and runs a flow through its {@code drop} step: with the
 * plugin it validates and its cases pass (the platform's mock stands in for the plugin's scheme, the plugin's step runs), without
 * it both are problems. Plugins a node would refuse are one line on stderr and 2; a plugin's component a node would refuse is a
 * problem of the dry build.
 */
class PluginsCommandTest {

    static final Path PACKAGE = FIXTURES.resolve("plugin");

    @TempDir Path plugins;

    /** {@code plugins/<entry>}: a jar of every fixture class whose service file names {@code provider}. */
    Path plugin(String entry, Class<?> provider) throws IOException {
        return PluginJars.write(plugins.resolve(entry), "1.0", List.of(provider.getName()), CliPlugins.classes());
    }

    @Test
    void withoutThePluginItsSchemeAndStepAreProblems(@TempDir Path copy) throws IOException {
        Ran ran = run("validate", PACKAGE.toString());
        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().stream().anyMatch(p -> p.contains("no component on this node serves the scheme 'acme'")), ran.toString());
        Path pkg = CliRuns.copy(PACKAGE, copy.resolve("plugin"));
        Files.writeString(pkg.resolve("endpoints/partner.yaml"), """
                schemaVersion: 1
                kind: endpoint
                id: partner
                scheme: http
                direction: out
                config: { url: "http://127.0.0.1:18088/partner", maxBodyBytes: 1048576 }
                """);
        Ran overHttp = run("validate", pkg.toString());
        assertEquals(1, overHttp.exit(), overHttp.toString());
        assertEquals(List.of("problem: flow 'dropped' does not build: step 'drop' (steps[0], drop): unknown step type 'drop'"), overHttp.problems());
    }

    @Test
    void withThePluginThePackageValidates() throws IOException {
        plugin("acme.jar", CliPlugins.Acme.class);
        Ran ran = run("validate", PACKAGE.toString(), "--plugins", plugins.toString());
        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(), ran.problems());
        assertEquals("", ran.err());
    }

    @Test
    void withoutTheOptionThePluginsAreRail8Plugins() throws IOException {
        plugin("acme.jar", CliPlugins.Acme.class);
        Ran ran = run(Map.of("RAIL8_PLUGINS", plugins.toString()), "validate", PACKAGE.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    @Test
    void thePluginsComponentChecksItsEndpointsOptions(@TempDir Path copy) throws IOException {
        plugin("acme.jar", CliPlugins.Acme.class);
        Path pkg = CliRuns.copy(PACKAGE, copy.resolve("plugin"));
        Path partner = pkg.resolve("endpoints/partner.yaml");
        Files.writeString(partner, Files.readString(partner).replace("url: acme://partner", "url: http://partner"));
        Ran ran = run("validate", pkg.toString(), "--plugins", plugins.toString());
        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().stream().anyMatch(p -> p.contains("partner") && p.contains("url")), ran.toString());
    }

    @Test
    void theCasesRunThePluginsStepOnAMockForItsScheme() throws IOException {
        plugin("acme.jar", CliPlugins.Acme.class);
        Ran ran = run("test", PACKAGE.toString(), "--plugins", plugins.toString());
        assertEquals(0, ran.exit(), ran.toString());
        assertTrue(ran.outLines().stream().anyMatch(l -> l.startsWith("PASS dropped: ended before the partner")), ran.toString());
        assertTrue(ran.outLines().stream().anyMatch(l -> l.startsWith("PASS forward: sent to the partner")), ran.toString());
    }

    @Test
    void pluginsANodeWouldRefuseAreOneLineAndUsage() throws IOException {
        plugin("xslt.jar", CliPlugins.ShippedStepType.class);
        for (String command : List.of("validate", "test")) {
            Ran ran = run(command, PACKAGE.toString(), "--plugins", plugins.toString());
            assertEquals(2, ran.exit(), ran.toString());
            assertEquals(List.of("rail8: plugins refused: plugin shipped-step: the step type xslt is rail8's already; a plugin adds and never replaces"),
                    ran.errLines());
            assertEquals("", ran.out());
        }
        Ran missing = run("validate", PACKAGE.toString(), "--plugins", plugins.resolve("missing").toString());
        assertEquals(2, missing.exit(), missing.toString());
        assertTrue(missing.err().startsWith("rail8: validate: --plugins names no directory: "), missing.toString());
        Ran unloadable = run(Map.of("RAIL8_PLUGINS", plugins.resolve("missing").toString()), "validate", PACKAGE.toString());
        assertEquals(2, unloadable.exit(), unloadable.toString());
        assertEquals(List.of("rail8: plugins refused: RAIL8_PLUGINS names no directory: " + plugins.resolve("missing")), unloadable.errLines());
    }

    @Test
    void aPluginsComponentANodeWouldRefuseIsAProblem() throws IOException {
        plugin("acme.jar", CliPlugins.Acme.class);
        plugin("http.jar", CliPlugins.ShippedScheme.class);
        Ran ran = run("validate", PACKAGE.toString(), "--plugins", plugins.toString());
        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(List.of("problem: plugin shipped-scheme: the scheme http is rail8's already; a plugin adds and never replaces"), ran.problems());
    }
}
