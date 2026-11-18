package dev.rail8.runtime.plugin;

import dev.rail8.api.Component;
import dev.rail8.api.Envelope;
import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.runtime.plugin.fixture.PluginFixtures;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.PluginJars;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin loader over plugin jars a test writes from {@link PluginFixtures}: each plugin in a class loader of its own that
 * sees the JDK, its own jars and the node's shared packages only; what plugins bring after what the node ships, in entry order,
 * never replacing it; and every refusal, each naming the plugin or entry it is about.
 */
class PluginsTest {

    static Vertx vertx;
    static final FakeRuntime RT = FakeRuntime.create();
    static final Function<ComponentContext, List<Component>> NOTHING_SHIPPED = context -> List.of();

    @TempDir Path dir;

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

    /** {@code dir/<entry>}: a jar of every fixture class whose service file names {@code providers}. */
    Path jar(String entry, Class<?>... providers) throws IOException {
        List<String> names = java.util.Arrays.stream(providers).map(Class::getName).toList();
        return PluginJars.write(dir.resolve(entry), "1.2.0", names, PluginFixtures.classes());
    }

    static List<String> types(List<StepFactory> steps) {
        return steps.stream().map(StepFactory::type).toList();
    }

    @Test
    void noPluginsAddNothing() {
        List<StepFactory> shipped = List.of(new PluginFixtures.Step("xslt"));
        for (Plugins none : List.of(Plugins.none(), Plugins.fromEnv(Map.of()), Plugins.fromEnv(Map.of(Plugins.ENV, " ")))) {
            assertEquals(shipped, none.steps(shipped));
            assertEquals(List.of(), none.languages(List.of()));
            assertEquals(List.of(), none.components(NOTHING_SHIPPED).apply(context()));
            assertEquals(List.of(), none.describe());
            none.close();
        }
    }

    @Test
    void aJarIsOnePluginAndWhatItBringsFollowsWhatTheNodeShips() throws IOException {
        jar("acme.jar", PluginFixtures.Acme.class);
        ComponentContext context = context();
        try (Plugins plugins = Plugins.load(dir)) {
            List<StepFactory> steps = plugins.steps(List.of(new PluginFixtures.Step("xslt")));
            assertEquals(List.of("xslt", "sign"), types(steps));
            assertEquals(PluginFixtures.Step.class.getName(), steps.get(1).getClass().getName());
            assertNotSame(PluginFixtures.Step.class, steps.get(1).getClass(), "the plugin's own copy, from its jar");
            List<Expressions> languages = plugins.languages(List.of(new PluginFixtures.Language("xpath")));
            assertEquals(List.of("xpath", "upper"), languages.stream().map(Expressions::language).toList());

            List<Component> components = plugins.components(c -> List.of(new PluginFixtures.Named("http", c))).apply(context);
            assertEquals(List.of("http", "acme"), components.stream().map(Component::scheme).toList());
            Object acmesVertx = invoke(components.get(1), "vertx");
            assertSame(vertx, acmesVertx, "the plugin's component holds the node's Vert.x: io.vertx.core is shared");
        }
    }

    @Test
    void aDirectoryOfJarsIsOnePlugin() throws IOException {
        Path acme = Files.createDirectory(dir.resolve("acme"));
        PluginJars.write(acme.resolve("acme.jar"), "1.2.0", List.of(PluginFixtures.Acme.class.getName()), PluginFixtures.class, PluginFixtures.Acme.class);
        PluginJars.write(acme.resolve("lib.jar"), "3.0", List.of(), PluginFixtures.Named.class, PluginFixtures.Step.class, PluginFixtures.Language.class);
        Files.writeString(acme.resolve("NOTICE.txt"), "a plugin's directory may hold more than jars");
        try (Plugins plugins = Plugins.load(dir)) {
            assertEquals(List.of("sign"), types(plugins.steps(List.of())));
            assertEquals(List.of("acme"), plugins.components(NOTHING_SHIPPED).apply(context()).stream().map(Component::scheme).toList());
            String line = plugins.describe().getFirst();
            assertTrue(line.startsWith("plugin acme 1.2.0 (api " + Plugin.API + ", sha256 "), line);
            assertTrue(line.endsWith(") from acme"), line);
        }
    }

    @Test
    void pluginsComeInEntryOrderEachInALoaderOfItsOwn() throws IOException {
        jar("b.jar", PluginFixtures.Acme.class);
        jar("a.jar", PluginFixtures.ChecksTheContextLoader.class);
        try (Plugins plugins = Plugins.load(dir)) {
            List<StepFactory> steps = plugins.steps(List.of());
            assertEquals(List.of("own-loader", "sign"), types(steps), "a.jar's, then b.jar's");
            assertNotSame(steps.get(0).getClass(), steps.get(1).getClass(), "two plugins, two copies of one class");
            assertEquals(2, plugins.describe().size());
            assertTrue(plugins.describe().get(0).startsWith("plugin checks ") && plugins.describe().get(1).startsWith("plugin acme "), plugins.describe().toString());
        }
    }

    @Test
    void eachCallIntoAPluginRunsWithItsOwnContextClassLoaderAndOnlyThat() throws IOException {
        jar("checks.jar", PluginFixtures.ChecksTheContextLoader.class);
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        try (Plugins plugins = Plugins.load(dir)) {
            assertEquals(List.of("own-loader"), types(plugins.steps(List.of())), "the constructor, name(), api() and steps() saw the plugin's loader");
            assertSame(before, Thread.currentThread().getContextClassLoader(), "and the caller's is back after each call");
        }
    }

    @Test
    void aPluginSeesTheJdkItsOwnJarsAndTheSharedPackagesOnly() throws Exception {
        jar("acme.jar", PluginFixtures.Acme.class);
        try (Plugins plugins = Plugins.load(dir)) {
            ClassLoader loader = plugins.steps(List.of()).getFirst().getClass().getClassLoader();
            for (String shared : List.of(Plugin.class.getName(), Envelope.class.getName(), Vertx.class.getName(), "io.netty.buffer.ByteBuf", "org.slf4j.Logger")) {
                assertSame(Class.forName(shared), loader.loadClass(shared), shared + " is the node's");
            }
            assertSame(String.class, loader.loadClass("java.lang.String"));
            for (String hidden : List.of("dev.rail8.core.flow.FlowBuilder", "dev.rail8.runtime.Main", Plugins.class.getName(), "io.vertx.pgclient.PgBuilder")) {
                assertThrows(ClassNotFoundException.class, () -> loader.loadClass(hidden), hidden + " is not a plugin's to see");
            }
            assertTrue(loader.getResource("dev/rail8/api/Envelope.class") != null, "a shared package's resources pass");
            assertEquals(null, loader.getResource("dev/rail8/runtime/Main.class"), "the node's other resources do not");
        }
    }

    @Test
    void aPluginReachingIntoTheNodeRefusesTheStartNamingIt() throws IOException {
        jar("reaches.jar", PluginFixtures.ReachesIntoTheNode.class);
        try (Plugins plugins = Plugins.load(dir)) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(NOTHING_SHIPPED).apply(context())).getMessage();
            assertTrue(refused.startsWith("plugin reaches: components() failed: java.lang.NoClassDefFoundError: dev/rail8/runtime/Main"), refused);
            assertTrue(refused.contains("a plugin sees the JDK, its own jars and the node's dev.rail8.api, io.vertx.core, io.netty and org.slf4j"), refused);
        }
    }

    @Test
    void thePluginLineNamesItsVersionApiAndTheDigestOfItsJar() throws Exception {
        Path jar = jar("acme.jar", PluginFixtures.Acme.class);
        String digest = HexFormat.of().formatHex(sha256(Files.readAllBytes(jar)));
        try (Plugins plugins = Plugins.load(dir)) {
            assertEquals(List.of("plugin acme 1.2.0 (api " + Plugin.API + ", sha256 " + digest + ") from acme.jar"), plugins.describe());
        }
    }

    @Test
    void closingClosesThePluginsClassLoaders() throws Exception {
        jar("acme.jar", PluginFixtures.Acme.class);
        Plugins plugins = Plugins.load(dir);
        ClassLoader loader = plugins.steps(List.of()).getFirst().getClass().getClassLoader();
        String notYetLoaded = PluginFixtures.NoVersion.class.getName();
        plugins.close();
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass(notYetLoaded), "a closed loader reads its jars no more");
    }

    // --- refused while loading: IllegalArgumentException, the configuration is refused

    @Test
    void theDirectoryMustBeOne() throws IOException {
        assertRefused("RAIL8_PLUGINS names no directory: " + dir.resolve("missing"), () -> Plugins.load(dir.resolve("missing")));
        Path file = Files.writeString(dir.resolve("plugins"), "");
        assertRefused("RAIL8_PLUGINS names no directory: " + file, () -> Plugins.fromEnv(Map.of(Plugins.ENV, file.toString())));
    }

    @Test
    void anEntryIsAJarOrADirectoryOfJars() throws IOException {
        Files.writeString(dir.resolve("README.txt"), "");
        assertRefused("plugin README.txt: an entry of RAIL8_PLUGINS is a .jar or a directory of jars", () -> Plugins.load(dir));
        Files.delete(dir.resolve("README.txt"));
        Files.createDirectory(dir.resolve("empty"));
        assertRefused("plugin empty: the directory holds no .jar", () -> Plugins.load(dir));
    }

    @Test
    void anEntryNamesExactlyOnePluginOfItsOwn() throws IOException {
        Path none = jar("none.jar");
        assertRefused("plugin none.jar: its jars name 0 implementations of dev.rail8.api.plugin.Plugin in META-INF/services; a plugin names exactly one",
                () -> Plugins.load(dir));
        Files.delete(none);
        Path two = jar("two.jar", PluginFixtures.Acme.class, PluginFixtures.BetaWithAcmesScheme.class);
        assertRefused("plugin two.jar: its jars name 2 implementations", () -> Plugins.load(dir));
        Files.delete(two);
        PluginJars.write(dir.resolve("missing.jar"), "1", List.of("com.example.Missing"), PluginFixtures.classes());
        String broken = assertRefused("plugin missing.jar: its implementation cannot be built: ", () -> Plugins.load(dir));
        assertTrue(broken.contains("com.example.Missing"), broken);
    }

    @Test
    void aNameIsALowerCaseLabelNoOtherPluginHasAndNotRail8() throws IOException {
        Path bad = jar("bad.jar", PluginFixtures.BadName.class);
        assertRefused("plugin bad.jar: the name 'Acme' is not a lower-case letter followed by at most 63 lower-case letters, digits or dashes",
                () -> Plugins.load(dir));
        Files.delete(bad);
        Path rail8 = jar("rail8.jar", PluginFixtures.NamedRail8.class);
        assertRefused("plugin rail8.jar: the name rail8 stands for what the node ships", () -> Plugins.load(dir));
        Files.delete(rail8);
        jar("a.jar", PluginFixtures.Acme.class);
        jar("b.jar", PluginFixtures.AcmeAgain.class);
        assertRefused("plugin b.jar: the name acme is a.jar's already", () -> Plugins.load(dir));
    }

    @Test
    void aPluginBuiltAgainstAnotherApiIsRefused() throws IOException {
        Path next = jar("next.jar", PluginFixtures.NextMinor.class);
        assertRefused("plugin next.jar: built against api 0.2, which a node of api " + Plugin.API + " does not load", () -> Plugins.load(dir));
        Files.delete(next);
        jar("latest.jar", PluginFixtures.NoVersion.class);
        assertRefused("plugin latest.jar: built against api latest, which a node of api " + Plugin.API + " does not load", () -> Plugins.load(dir));
    }

    @Test
    void whileTheApiIs0xOnlyItsOwnMinorLoadsFrom1OnAMinorUpToTheNodes() {
        assertTrue(Plugins.loads("0.1", "0.1"));
        assertTrue(!Plugins.loads("0.1", "0.2") && !Plugins.loads("0.2", "0.1") && !Plugins.loads("0.1", "1.0"));
        assertTrue(Plugins.loads("1.2", "1.0") && Plugins.loads("1.2", "1.2"));
        assertTrue(!Plugins.loads("1.2", "1.3") && !Plugins.loads("1.2", "2.0") && !Plugins.loads("2.0", "1.0"));
        assertTrue(!Plugins.loads("1.2", "1") && !Plugins.loads("1.2", "1.2.0") && !Plugins.loads("1.2", "1.x") && !Plugins.loads("1.2", ""));
    }

    @Test
    void everyLimitIsFinite() throws IOException {
        for (int i = 0; i <= Plugins.MAX_PLUGINS; i++) Files.createDirectory(dir.resolve("p" + i));
        assertRefused("RAIL8_PLUGINS holds " + (Plugins.MAX_PLUGINS + 1) + " entries; at most " + Plugins.MAX_PLUGINS, () -> Plugins.load(dir));
        for (int i = 1; i <= Plugins.MAX_PLUGINS; i++) Files.delete(dir.resolve("p" + i));
        for (int i = 0; i <= Plugins.MAX_JARS; i++) Files.writeString(dir.resolve("p0").resolve("lib" + i + ".jar"), "");
        assertRefused("plugin p0: the directory holds " + (Plugins.MAX_JARS + 1) + " jars; at most " + Plugins.MAX_JARS, () -> Plugins.load(dir));
    }

    // --- refused while merging: a plugin adds and never replaces

    @Test
    void aStepTypeOrLanguageTheNodeHasRefusesTheConfigurationNamingBothOwners() throws IOException {
        jar("shipped-step.jar", PluginFixtures.ShippedStepType.class);
        try (Plugins plugins = Plugins.load(dir)) {
            assertRefused("plugin shipped-step: the step type xslt is rail8's already; a plugin adds and never replaces",
                    () -> plugins.steps(List.of(new PluginFixtures.Step("xslt"))));
        }
        Files.delete(dir.resolve("shipped-step.jar"));
        jar("built-in.jar", PluginFixtures.BuiltInStepType.class);
        try (Plugins plugins = Plugins.load(dir)) {
            assertRefused("plugin built-in-step: the step type route is built in", () -> plugins.steps(List.of()));
        }
        Files.delete(dir.resolve("built-in.jar"));
        jar("language.jar", PluginFixtures.ShippedLanguage.class);
        try (Plugins plugins = Plugins.load(dir)) {
            assertRefused("plugin shipped-language: the expression language xpath is rail8's already",
                    () -> plugins.languages(List.of(new PluginFixtures.Language("xpath"))));
        }
    }

    @Test
    void aSchemeTheNodeOrAnotherPluginHasRefusesTheStartNamingBothOwners() throws IOException {
        jar("a.jar", PluginFixtures.Acme.class);
        jar("b.jar", PluginFixtures.BetaWithAcmesScheme.class);
        try (Plugins plugins = Plugins.load(dir)) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(NOTHING_SHIPPED).apply(context())).getMessage();
            assertEquals("plugin beta: the scheme acme is plugin acme's already; a plugin adds and never replaces", refused);
        }
        Files.delete(dir.resolve("a.jar"));
        Files.delete(dir.resolve("b.jar"));
        jar("http.jar", PluginFixtures.ShippedScheme.class);
        try (Plugins plugins = Plugins.load(dir)) {
            String refused = assertThrows(IllegalStateException.class,
                    () -> plugins.components(c -> List.of(new PluginFixtures.Named("http", c))).apply(context())).getMessage();
            assertTrue(refused.startsWith("plugin shipped-scheme: the scheme http is rail8's already"), refused);
        }
        Files.delete(dir.resolve("http.jar"));
        jar("mock.jar", PluginFixtures.MockScheme.class);
        try (Plugins plugins = Plugins.load(dir)) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(NOTHING_SHIPPED).apply(context())).getMessage();
            assertEquals("plugin mock-scheme: the scheme mock is the platform's mock", refused);
        }
    }

    @Test
    void aPluginThatThrowsOrBringsTooMuchRefusesTheConfigurationNamingIt() throws IOException {
        jar("failing.jar", PluginFixtures.FailingSteps.class);
        try (Plugins plugins = Plugins.load(dir)) {
            assertRefused("plugin failing: steps() failed: java.lang.IllegalStateException: no steps today", () -> plugins.steps(List.of()));
        }
        Files.delete(dir.resolve("failing.jar"));
        jar("many.jar", PluginFixtures.TooManySteps.class);
        try (Plugins plugins = Plugins.load(dir)) {
            assertRefused("plugin too-many: steps() brings " + (Plugin.MAX_CONTRIBUTIONS + 1) + "; at most " + Plugin.MAX_CONTRIBUTIONS,
                    () -> plugins.steps(List.of()));
        }
    }

    /** {@code call} throws an {@link IllegalArgumentException} whose message starts with {@code start}; the message. */
    private static String assertRefused(String start, org.junit.jupiter.api.function.Executable call) {
        String message = assertThrows(IllegalArgumentException.class, call).getMessage();
        assertTrue(message.startsWith(start), () -> "expected a refusal starting with\n  " + start + "\nbut was\n  " + message);
        return message;
    }

    private static Object invoke(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
}
