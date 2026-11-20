package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import dev.rail8.cli.fixture.CliCommands;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.testkit.PluginJars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static dev.rail8.cli.CliRuns.DEMO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code rail8 <name> [arguments]} runs the command that a module of the CLI's class path brings, and {@code rail8 help} lists
 * each one after the CLI's own commands. The modules here are the node's seven, which bring none, and the fixtures of
 * {@link CliCommands}, named in a jar on a class path over the CLI's own. A plugin of {@code --plugins} or
 * {@code RAIL8_PLUGINS} brings no command, and a module named as one of the CLI's own commands that brings one is refused
 * wherever the CLI loads its modules.
 */
class ModuleCommandsTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T07:00:00Z"), ZoneOffset.UTC);
    static final String SAMPLE_LINE_1 = "<word>... | --fail | --throw | --io | --interrupt | --seven";
    static final String SAMPLE_LINE_2 = "print the words, the time and the greeting";

    @TempDir Path jars;
    final List<URLClassLoader> opened = new ArrayList<>();

    @AfterEach
    void close() throws IOException {
        for (URLClassLoader loader : opened) loader.close();
    }

    /** A class path over the CLI's own, with a jar whose service file names the modules {@code providers}. */
    ClassLoader modules(Class<?>... providers) throws IOException {
        Path jar = PluginJars.write(jars.resolve("modules-" + opened.size() + ".jar"), "3.0", Arrays.stream(providers).map(Class::getName).toList(),
                CliCommands.classes());
        URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, Cli.class.getClassLoader());
        opened.add(loader);
        return loader;
    }

    static Ran run(ClassLoader modules, String... args) {
        return CliRuns.run(modules, CLOCK, Map.of(), args);
    }

    @Test
    void helpListsEachModulesCommandAfterTheClisOwnInNameOrder() throws IOException {
        Ran help = run(modules(CliCommands.Sample.class, CliCommands.Bare.class), "help");
        assertEquals(0, help.exit(), help.toString());
        assertEquals(Cli.COMMANDS + "  bare\n      say hello\n  sample " + SAMPLE_LINE_1 + "\n      " + SAMPLE_LINE_2 + "\n" + Cli.EXIT_CODES, help.out());
        assertEquals("", help.err());
    }

    @Test
    void withTheNodesModulesAloneHelpIsTheClisOwnUsage() {
        Ran help = CliRuns.run("help");
        assertEquals(0, help.exit(), help.toString());
        assertEquals(Cli.USAGE, help.out());
    }

    @Test
    void aModulesCommandRunsOnTheArgumentsAfterItsNameTheEnvironmentAndTheClock() throws IOException {
        Ran ran = CliRuns.run(modules(CliCommands.Sample.class), CLOCK, Map.of("RAIL8_SAMPLE_GREETING", "hi"), "sample", "a", "--plugins", "b");
        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of("a --plugins b at 2026-11-20T07:00:00Z, greeting hi"), ran.outLines());
        assertEquals("", ran.err());
        Ran bare = run(modules(CliCommands.Bare.class), "bare");
        assertEquals(0, bare.exit(), bare.toString());
        assertEquals(List.of("hello"), bare.outLines());
    }

    @Test
    void aUsageErrorIsWhatTheCommandWroteThenItsUsage() throws IOException {
        Ran ran = run(modules(CliCommands.Sample.class), "sample");
        assertEquals(2, ran.exit(), ran.toString());
        assertEquals(List.of("sample: no word", "usage: rail8 sample " + SAMPLE_LINE_1, "      " + SAMPLE_LINE_2), ran.errLines());
        assertEquals("", ran.out());
    }

    @Test
    void problemsAreExit1AndWhatTheCommandThrowsOrAnyOtherCodeIsOneLineAndExit1() throws IOException {
        ClassLoader modules = modules(CliCommands.Sample.class);
        Map<String, String> lines = Map.of(
                "--fail", "sample: failed on purpose",
                "--throw", "rail8: sample: java.lang.IllegalStateException: thrown on purpose",
                "--io", "rail8: sample: java.io.IOException: no disk",
                "--interrupt", "rail8: sample: java.lang.InterruptedException: interrupted on purpose",
                "--seven", "rail8: sample: returned 7");
        lines.forEach((arg, line) -> {
            Ran ran = run(modules, "sample", arg);
            assertEquals(1, ran.exit(), ran.toString());
            assertEquals(List.of(line), ran.errLines(), arg);
        });
    }

    @Test
    void aNameOfNoModuleOrOfAModuleWithoutACommandIsAUsageError() {
        Ran http = CliRuns.run("http");
        assertEquals(2, http.exit(), http.toString());
        assertEquals("rail8: module http brings no command", http.errLines().getFirst());
        assertEquals(Cli.USAGE.lines().toList(), http.errLines().subList(1, http.errLines().size()));
        Ran unknown = CliRuns.run("frobnicate");
        assertEquals(2, unknown.exit(), unknown.toString());
        assertEquals("rail8: unknown command 'frobnicate'", unknown.errLines().getFirst());
    }

    @Test
    void aModuleNamedAsACommandOfTheClisOwnIsRefusedWhereverTheCliLoadsModules(@TempDir Path adapter) throws IOException {
        ClassLoader modules = modules(CliCommands.Deploy.class);
        List<String> refused = List.of("rail8: plugins refused: module deploy: its command is the CLI's own deploy");
        List<String[]> commands = List.of(new String[] {"validate", DEMO.toString()}, new String[] {"test", DEMO.toString()}, new String[] {"sample"},
                new String[] {"new-adapter", adapter.resolve("acme").toString(), "--scheme", "acme"});
        for (String[] args : commands) {
            Ran ran = run(modules, args);
            assertEquals(2, ran.exit(), ran.toString());
            assertEquals(refused, ran.errLines(), String.join(" ", args));
        }
        Ran help = run(modules, "help");
        assertEquals(2, help.exit(), help.toString());
        assertEquals(Cli.USAGE, help.out(), "the CLI's own usage all the same");
        assertEquals(refused, help.errLines());
    }

    @Test
    void aPluginThatBringsACommandIsRefusedNamingItAndHelpAndModuleCommandsLoadNoPlugin(@TempDir Path plugins) throws IOException {
        PluginJars.write(plugins.resolve("sample.jar"), "1.0", List.of(CliCommands.Sample.class.getName()), CliCommands.classes());
        Ran validate = CliRuns.run("validate", DEMO.toString(), "--plugins", plugins.toString());
        assertEquals(2, validate.exit(), validate.toString());
        assertEquals(List.of("rail8: plugins refused: plugin sample: command() brings a command; only a module brings one, no plugin of RAIL8_PLUGINS"),
                validate.errLines());
        Map<String, String> env = Map.of("RAIL8_PLUGINS", plugins.toString());
        Ran help = CliRuns.run(env, "help");
        assertEquals(0, help.exit(), help.toString());
        assertEquals(Cli.USAGE, help.out(), "no plugin's command");
        Ran sample = CliRuns.run(env, "sample", "a");
        assertEquals(2, sample.exit(), sample.toString());
        assertEquals("rail8: unknown command 'sample'", sample.errLines().getFirst());
        Ran broken = CliRuns.run(Map.of("RAIL8_PLUGINS", plugins.resolve("missing").toString()), "help");
        assertEquals(0, broken.exit(), "a broken plugin directory breaks no help: " + broken);
    }

    @Test
    void theClisOwnCommandsAreTheNamesWhoseModulesBringNoCommand() {
        List<String> own = Cli.COMMANDS.lines().filter(l -> l.matches("  [a-z][a-z-]*( .*)?")).map(l -> l.trim().split(" ")[0]).toList();
        assertEquals(Plugins.CLI_COMMANDS, own);
        for (String name : own) {
            Ran ran = CliRuns.run(name);
            assertFalse(ran.err().contains("unknown command") || ran.err().contains("brings no command"), name + " is the CLI's own: " + ran);
        }
    }
}
