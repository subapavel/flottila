package dev.rail8.runtime.plugin;

import dev.rail8.api.Component;
import dev.rail8.api.Envelope;
import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.api.plugin.CommandContext;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.runtime.plugin.fixture.PluginFixtures;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.PluginJars;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The loader over jars a test writes from {@link PluginFixtures}: modules found on a class path, in name order, then plugins of a
 * directory, in entry order, each plugin in a class loader of its own that sees the JDK, its own jars and the node's shared
 * packages only; what each brings, never replacing another's; and every refusal, each naming the module, plugin or entry it is
 * about. The class path here names only the modules a test puts on it, never the node's own.
 */
class PluginsTest {

    static Vertx vertx;
    static final FakeRuntime RT = FakeRuntime.create();
    /** A class path that names no module: the JDK and the node's shared packages, never a service file of the node's. */
    static final ClassLoader NO_MODULES = new Plugins.Gate(PluginsTest.class.getClassLoader());

    @TempDir Path dir;
    /** Where a test writes what is no plugin of {@link #dir}: the jars of its modules. */
    @TempDir Path elsewhere;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() {
        vertx.close().await();
    }

    static ComponentContext context() {
        return new ComponentContext(vertx, RT.blobs(), RT.ids(), RT.timer(), RT.clock(), HttpIngress.NONE);
    }

    /** {@code dir/<entry>}: a jar of every fixture class whose service file names {@code providers}. */
    Path jar(String entry, Class<?>... providers) throws IOException {
        List<String> names = java.util.Arrays.stream(providers).map(Class::getName).toList();
        return PluginJars.write(dir.resolve(entry), "1.2.0", names, PluginFixtures.classes());
    }

    /** The plugins of the test's directory, with no module and no setting. */
    Plugins load() {
        return Plugins.load(NO_MODULES, Optional.of(dir), Map.of());
    }

    /** A class path over {@code jars}, whose service files name its modules; the node's own modules are not on it. */
    static URLClassLoader classPath(Path... jars) throws IOException {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return new URLClassLoader(urls, NO_MODULES);
    }

    /** A jar of every fixture class, outside the plugin directory, whose service file names {@code providers}: modules. */
    Path moduleJar(String name, Class<?>... providers) throws IOException {
        List<String> names = java.util.Arrays.stream(providers).map(Class::getName).toList();
        return PluginJars.write(elsewhere.resolve(name), "2.0.1", names, PluginFixtures.classes());
    }

    static List<String> types(List<StepFactory> steps) {
        return steps.stream().map(StepFactory::type).toList();
    }

    static List<String> schemes(List<Component> components) {
        return components.stream().map(Component::scheme).toList();
    }

    @Test
    void noModulesAndNoPluginsAddNothing() {
        try (Plugins none = Plugins.load(NO_MODULES, Optional.empty(), Map.of())) {
            assertEquals(List.of(), none.steps());
            assertEquals(List.of(), none.languages());
            assertEquals(List.of(), none.components(context()));
            assertEquals(List.of(), none.describe());
        }
    }

    @Test
    void thePluginDirectoryIsTheVariablesUnlessItIsUnsetOrBlank() {
        assertEquals(Optional.empty(), Plugins.dir(Map.of()));
        assertEquals(Optional.empty(), Plugins.dir(Map.of(Plugins.ENV, "")));
        assertEquals(Optional.empty(), Plugins.dir(Map.of(Plugins.ENV, " \t")));
        assertEquals(Optional.of(Path.of("/opt/rail8/plugins")), Plugins.dir(Map.of(Plugins.ENV, "/opt/rail8/plugins")));
    }

    @Test
    void theComponentsOfOneModuleOrPluginAreBuiltAloneAndCheckedAsAllAre() throws IOException {
        jar("acme.jar", PluginFixtures.Acme.class);
        jar("badly.jar", PluginFixtures.BadSettings.class);
        try (Plugins plugins = load()) {
            assertEquals(List.of("acme"), schemes(plugins.components("acme", context())), "the badly described component is not built");
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components("badly-described", context())).getMessage();
            assertTrue(refused.startsWith("plugin badly-described: settings() of " + PluginFixtures.BadlyDescribed.class.getName() + " failed: "), refused);
            assertEquals(List.of(), plugins.components("none", context()));
        }
    }

    @Test
    void aJarIsOnePluginAndWhatItBringsIsTheNodes() throws IOException {
        jar("acme.jar", PluginFixtures.Acme.class);
        ComponentContext context = context();
        try (Plugins plugins = load()) {
            List<StepFactory> steps = plugins.steps();
            assertEquals(List.of("sign"), types(steps));
            assertEquals(PluginFixtures.Step.class.getName(), steps.getFirst().getClass().getName());
            assertNotSame(PluginFixtures.Step.class, steps.getFirst().getClass(), "the plugin's own copy, from its jar");
            assertEquals(List.of("upper"), plugins.languages().stream().map(Expressions::language).toList());

            List<Component> components = plugins.components(context);
            assertEquals(List.of("acme"), schemes(components));
            Object acmesVertx = invoke(components.getFirst(), "vertx");
            assertSame(vertx, acmesVertx, "the plugin's component holds the node's Vert.x: io.vertx.core is shared");
        }
    }

    @Test
    void aDirectoryOfJarsIsOnePlugin() throws IOException {
        Path acme = Files.createDirectory(dir.resolve("acme"));
        PluginJars.write(acme.resolve("acme.jar"), "1.2.0", List.of(PluginFixtures.Acme.class.getName()), PluginFixtures.class, PluginFixtures.Acme.class);
        PluginJars.write(acme.resolve("lib.jar"), "3.0", List.of(), PluginFixtures.Named.class, PluginFixtures.Step.class, PluginFixtures.Language.class);
        Files.writeString(acme.resolve("NOTICE.txt"), "a plugin's directory may hold more than jars");
        try (Plugins plugins = load()) {
            assertEquals(List.of("sign"), types(plugins.steps()));
            assertEquals(List.of("acme"), schemes(plugins.components(context())));
            String line = plugins.describe().getFirst();
            assertTrue(line.startsWith("plugin acme 1.2.0 (api " + Plugin.API + ", sha256 "), line);
            assertTrue(line.endsWith(") from acme"), line);
        }
    }

    @Test
    void pluginsComeInEntryOrderEachInALoaderOfItsOwn() throws IOException {
        jar("b.jar", PluginFixtures.Acme.class);
        jar("a.jar", PluginFixtures.ChecksTheContextLoader.class);
        try (Plugins plugins = load()) {
            List<StepFactory> steps = plugins.steps();
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
        try (Plugins plugins = load()) {
            assertEquals(List.of("own-loader"), types(plugins.steps()), "the constructor, name(), api() and steps() saw the plugin's loader");
            assertSame(before, Thread.currentThread().getContextClassLoader(), "and the caller's is back after each call");
        }
    }

    @Test
    void aPluginSeesTheJdkItsOwnJarsAndTheSharedPackagesOnly() throws Exception {
        jar("acme.jar", PluginFixtures.Acme.class);
        try (Plugins plugins = load()) {
            ClassLoader loader = plugins.steps().getFirst().getClass().getClassLoader();
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
        try (Plugins plugins = load()) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(context())).getMessage();
            assertTrue(refused.startsWith("plugin reaches: components() failed: java.lang.NoClassDefFoundError: dev/rail8/runtime/Main"), refused);
            assertTrue(refused.contains("a plugin sees the JDK, its own jars and the node's dev.rail8.api, io.vertx.core, io.netty and org.slf4j"), refused);
        }
    }

    @Test
    void thePluginLineNamesItsVersionApiAndTheDigestOfItsJar() throws Exception {
        Path jar = jar("acme.jar", PluginFixtures.Acme.class);
        String digest = HexFormat.of().formatHex(sha256(Files.readAllBytes(jar)));
        try (Plugins plugins = load()) {
            assertEquals(List.of("plugin acme 1.2.0 (api " + Plugin.API + ", sha256 " + digest + ") from acme.jar"), plugins.describe());
        }
    }

    @Test
    void closingClosesThePluginsClassLoaders() throws Exception {
        jar("acme.jar", PluginFixtures.Acme.class);
        Plugins plugins = load();
        ClassLoader loader = plugins.steps().getFirst().getClass().getClassLoader();
        String notYetLoaded = PluginFixtures.NoVersion.class.getName();
        plugins.close();
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass(notYetLoaded), "a closed loader reads its jars no more");
    }

    @Test
    void aPluginsStepsAreBuiltOverItsSettings() throws IOException {
        jar("settled.jar", PluginFixtures.Settled.class);
        try (Plugins plugins = Plugins.load(NO_MODULES, Optional.of(dir), Map.of("RAIL8_SETTLED_DEPTH", "7", "RAIL8_OTHER_DEPTH", "3"))) {
            assertEquals(List.of("depth-7"), types(plugins.steps()));
        }
        try (Plugins plugins = load()) {
            assertEquals(List.of("depth-5"), types(plugins.steps()), "no settings: the fallback");
        }
        assertRefused("plugin settled: steps() failed: java.lang.IllegalArgumentException: RAIL8_SETTLED_DEPTH must be between 1 and 9, was 0",
                () -> Plugins.load(NO_MODULES, Optional.of(dir), Map.of("RAIL8_SETTLED_DEPTH", "0")));
    }

    // --- modules: found on the class path, in name order, before the plugins

    @Test
    void modulesComeInNameOrderThenThePlugins() throws Exception {
        Path modules = moduleJar("modules.jar", PluginFixtures.ChecksTheContextLoader.class, PluginFixtures.Acme.class);
        jar("settled.jar", PluginFixtures.Settled.class);
        try (URLClassLoader classPath = classPath(modules); Plugins plugins = Plugins.load(classPath, Optional.of(dir), Map.of())) {
            assertEquals(List.of("sign", "own-loader", "depth-5"), types(plugins.steps()), "module acme's, module checks', then plugin settled's");
            assertSame(classPath, plugins.steps().getFirst().getClass().getClassLoader(), "a module is the class path's, in no loader of its own");
            assertEquals(List.of("acme"), schemes(plugins.components(context())));
            List<String> lines = plugins.describe();
            String digest = HexFormat.of().formatHex(sha256(Files.readAllBytes(modules)));
            assertEquals("module acme 2.0.1 (api " + Plugin.API + ", sha256 " + digest + ") from modules.jar", lines.get(0));
            assertEquals("module checks 2.0.1 (api " + Plugin.API + ", sha256 " + digest + ") from modules.jar", lines.get(1));
            assertTrue(lines.get(2).startsWith("plugin settled 1.2.0 "), lines.toString());
        }
    }

    @Test
    void aModuleOfADirectoryOfClassesHasNoDigest() throws IOException {
        Path services = Files.createDirectories(elsewhere.resolve("META-INF/services"));
        Files.writeString(services.resolve(Plugin.class.getName()), PluginFixtures.Acme.class.getName() + "\n");
        Path classes = Path.of(PluginFixtures.Acme.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        try (URLClassLoader classPath = new URLClassLoader(new URL[] {elsewhere.toUri().toURL()}, PluginsTest.class.getClassLoader());
             Plugins plugins = Plugins.load(classPath, Optional.empty(), Map.of())) {
            assertTrue(plugins.describe().contains("module acme - (api " + Plugin.API + ", sha256 -) from " + classes), plugins.describe().toString());
        }
    }

    @Test
    void eachModuleThenEachPluginIsListedWithWhatItBringsItsSchemesOnceEveryComponentIsBuilt() throws Exception {
        Path modules = moduleJar("modules.jar", PluginFixtures.Acme.class, PluginFixtures.Serves.class);
        Path settled = jar("settled.jar", PluginFixtures.Settled.class);
        try (URLClassLoader classPath = classPath(modules); Plugins plugins = Plugins.load(classPath, Optional.of(dir), Map.of())) {
            Optional<String> moduleDigest = Optional.of(HexFormat.of().formatHex(sha256(Files.readAllBytes(modules))));
            Optional<String> pluginDigest = Optional.of(HexFormat.of().formatHex(sha256(Files.readAllBytes(settled))));
            Plugins.Listed serves = new Plugins.Listed("module", "serves", Optional.of("2.0.1"), Plugin.API, moduleDigest, Optional.of("modules.jar"),
                    List.of(), List.of(), List.of(), false);
            Plugins.Listed settledPlugin = new Plugins.Listed("plugin", "settled", Optional.of("1.2.0"), Plugin.API, pluginDigest, Optional.of("settled.jar"),
                    List.of(), List.of("depth-5"), List.of(), false);
            List<Plugins.Listed> before = List.of(new Plugins.Listed("module", "acme", Optional.of("2.0.1"), Plugin.API, moduleDigest,
                    Optional.of("modules.jar"), List.of(), List.of("sign"), List.of("upper"), false), serves, settledPlugin);
            assertEquals(before, plugins.list(), "no scheme before the components are built");
            plugins.components("acme", context());
            assertEquals(before, plugins.list(), "nor once one module's alone are built");
            plugins.components(context());
            assertEquals(List.of(new Plugins.Listed("module", "acme", Optional.of("2.0.1"), Plugin.API, moduleDigest, Optional.of("modules.jar"),
                    List.of("acme"), List.of("sign"), List.of("upper"), false), serves, settledPlugin), plugins.list(), "the schemes once every component is built");
        }
    }

    @Test
    void aModuleWithoutAVersionOrADigestIsListedWithoutThem() throws IOException {
        Path services = Files.createDirectories(elsewhere.resolve("META-INF/services"));
        Files.writeString(services.resolve(Plugin.class.getName()), PluginFixtures.Acme.class.getName() + "\n");
        Path classes = Path.of(PluginFixtures.Acme.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        try (URLClassLoader classPath = new URLClassLoader(new URL[] {elsewhere.toUri().toURL()}, PluginsTest.class.getClassLoader());
             Plugins plugins = Plugins.load(classPath, Optional.empty(), Map.of())) {
            Plugins.Listed acme = plugins.list().stream().filter(l -> l.name().equals("acme")).findFirst().orElseThrow();
            assertEquals(new Plugins.Listed("module", "acme", Optional.empty(), Plugin.API, Optional.empty(), Optional.of(classes.toString()), List.of(),
                    List.of("sign"), List.of("upper"), false), acme);
        }
    }

    @Test
    void aPluginNeitherTakesAModulesNameNorReplacesWhatItBrings() throws IOException {
        Path modules = moduleJar("acme.jar", PluginFixtures.Acme.class);
        jar("acme.jar", PluginFixtures.AcmeAgain.class);
        try (URLClassLoader classPath = classPath(modules)) {
            assertRefused("plugin acme.jar: the name acme is module " + PluginFixtures.Acme.class.getName() + "'s already",
                    () -> Plugins.load(classPath, Optional.of(dir), Map.of()));
            Files.delete(dir.resolve("acme.jar"));
            jar("beta.jar", PluginFixtures.BetaWithAcmesScheme.class);
            try (Plugins plugins = Plugins.load(classPath, Optional.of(dir), Map.of())) {
                String refused = assertThrows(IllegalStateException.class, () -> plugins.components(context())).getMessage();
                assertEquals("plugin beta: the scheme acme is module acme's already; a plugin adds and never replaces", refused);
            }
        }
    }

    @Test
    void aModuleIsCheckedAsAPluginIs() throws IOException {
        try (URLClassLoader twice = classPath(moduleJar("twice.jar", PluginFixtures.Acme.class, PluginFixtures.AcmeAgain.class))) {
            assertRefused("module " + PluginFixtures.AcmeAgain.class.getName() + ": the name acme is module " + PluginFixtures.Acme.class.getName() + "'s already",
                    () -> Plugins.load(twice, Optional.empty(), Map.of()));
        }
        try (URLClassLoader bad = classPath(moduleJar("bad.jar", PluginFixtures.BadName.class))) {
            assertRefused("module " + PluginFixtures.BadName.class.getName() + ": the name 'Acme' is not a lower-case letter",
                    () -> Plugins.load(bad, Optional.empty(), Map.of()));
        }
        try (URLClassLoader rail8 = classPath(moduleJar("rail8.jar", PluginFixtures.NamedRail8.class))) {
            assertRefused("module " + PluginFixtures.NamedRail8.class.getName() + ": the name rail8 stands for the platform itself",
                    () -> Plugins.load(rail8, Optional.empty(), Map.of()));
        }
        try (URLClassLoader next = classPath(moduleJar("next.jar", PluginFixtures.NextMinor.class))) {
            assertRefused("module " + PluginFixtures.NextMinor.class.getName() + ": built against api 0.4, which a node of api " + Plugin.API + " does not load",
                    () -> Plugins.load(next, Optional.empty(), Map.of()));
        }
        try (URLClassLoader failing = classPath(moduleJar("failing.jar", PluginFixtures.FailingSteps.class))) {
            assertRefused("module failing: steps() failed: java.lang.IllegalStateException: no steps today", () -> Plugins.load(failing, Optional.empty(), Map.of()));
        }
        Path missing = PluginJars.write(elsewhere.resolve("missing.jar"), "1", List.of("com.example.Missing"), PluginFixtures.classes());
        try (URLClassLoader broken = classPath(missing)) {
            String refused = assertRefused("the class path names a module that cannot be found: ", () -> Plugins.load(broken, Optional.empty(), Map.of()));
            assertTrue(refused.contains("com.example.Missing"), refused);
        }
    }

    @Test
    void theClassPathNamesAtMostSoManyModules(@TempDir Path build) throws IOException {
        Path sources = Files.createDirectories(build.resolve("src/m"));
        Path classes = Files.createDirectories(build.resolve("classes"));
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i <= Plugins.MAX_MODULES; i++) {
            Files.writeString(sources.resolve("M" + i + ".java"), "package m; public final class M" + i + " implements dev.rail8.api.plugin.Plugin {"
                    + " public String name() { return \"m" + i + "\"; } public String api() { return API; } }");
            names.add("m.M" + i);
        }
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        String api = Path.of(Plugin.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toString();
        List<String> args = new java.util.ArrayList<>(List.of("-d", classes.toString(), "-classpath", api));
        try (Stream<Path> files = Files.list(sources)) {
            files.forEach(f -> args.add(f.toString()));
        }
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "the test's modules compile");
        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(services.resolve(Plugin.class.getName()), String.join("\n", names) + "\n");
        try (URLClassLoader many = new URLClassLoader(new URL[] {classes.toUri().toURL()}, NO_MODULES)) {
            assertRefused("the class path names more than " + Plugins.MAX_MODULES + " modules", () -> Plugins.load(many, Optional.empty(), Map.of()));
        }
        Files.writeString(services.resolve(Plugin.class.getName()), String.join("\n", names.subList(0, Plugins.MAX_MODULES)) + "\n");
        try (URLClassLoader enough = new URLClassLoader(new URL[] {classes.toUri().toURL()}, NO_MODULES);
             Plugins plugins = Plugins.load(enough, Optional.empty(), Map.of())) {
            assertEquals(Plugins.MAX_MODULES, plugins.describe().size());
        }
    }

    // --- admin services: a module's only, taken when the node asks for them

    static List<String> routes(List<AdminService> services) {
        return services.stream().map(s -> s.method() + " " + s.path()).toList();
    }

    @Test
    void aModulesAdminServicesAreBuiltOverItsSettingsWhenTheNodeAsks() throws IOException {
        try (URLClassLoader classPath = classPath(moduleJar("modules.jar", PluginFixtures.Serves.class, PluginFixtures.Acme.class));
             Plugins plugins = Plugins.load(classPath, Optional.empty(), Map.of())) {
            Map<String, List<AdminService>> services = plugins.services(Map.of());
            assertEquals(List.of("serves"), List.copyOf(services.keySet()), "a module that brings none is not listed");
            assertEquals(List.of("GET status", "POST status"), routes(services.get("serves")));
            assertEquals(List.of("GET status", "POST status", "POST extra"), routes(plugins.services(Map.of("RAIL8_SERVES_EXTRA", "1")).get("serves")));
            assertRefused("module serves: services() failed: java.lang.IllegalArgumentException: RAIL8_SERVES_EXTRA must be between 0 and 1, was 2",
                    () -> plugins.services(Map.of("RAIL8_SERVES_EXTRA", "2")));
        }
    }

    /** Loading, as {@code rail8 validate} and {@code rail8 test} do, never calls {@code services()}: only the node does. */
    @Test
    void loadingAloneNeverAsksForTheAdminServices() throws IOException {
        try (URLClassLoader classPath = classPath(moduleJar("failing.jar", PluginFixtures.FailingServices.class));
             Plugins plugins = Plugins.load(classPath, Optional.empty(), Map.of())) {
            assertEquals(List.of("failing-services"), plugins.describe().stream().map(line -> line.split(" ")[1]).toList(), "loaded");
            assertRefused("module failing-services: services() failed: java.lang.IllegalStateException: no services today", () -> plugins.services(Map.of()));
        }
    }

    @Test
    void aPluginOfTheDirectoryThatBringsAnAdminServiceIsRefusedNamingIt() throws IOException {
        jar("serves.jar", PluginFixtures.Serves.class);
        try (Plugins plugins = load()) {
            assertRefused("plugin serves: services() brings 2 admin services; only a module brings them, no plugin of RAIL8_PLUGINS",
                    () -> plugins.services(Map.of()));
        }
        jar("acme.jar", PluginFixtures.Acme.class);
        Files.delete(dir.resolve("serves.jar"));
        try (Plugins plugins = load()) {
            assertEquals(Map.of(), plugins.services(Map.of()), "a plugin that brings none is taken");
        }
    }

    @Test
    void anAdminServiceOutsideTheRulesIsRefusedNamingTheModule() throws IOException {
        String service = PluginFixtures.Service.class.getName();
        Map<Class<?>, String> refusals = Map.of(
                PluginFixtures.ServesTwice.class, "module twice: two admin services are POST a/b",
                PluginFixtures.ServesAPut.class, "module puts: the method of " + service + " is 'PUT'; an admin service is GET or POST",
                PluginFixtures.ServesNoPath.class, "module nulls: the path of " + service + " is 'null'; an admin service's path is 1 to 4 segments of [a-z0-9-]{1,32}",
                PluginFixtures.ServesTooMany.class, "module many: services() brings " + (Plugin.MAX_CONTRIBUTIONS + 1) + "; at most " + Plugin.MAX_CONTRIBUTIONS);
        for (Map.Entry<Class<?>, String> refused : refusals.entrySet()) {
            try (URLClassLoader classPath = classPath(moduleJar(refused.getKey().getSimpleName() + ".jar", refused.getKey()));
                 Plugins plugins = Plugins.load(classPath, Optional.empty(), Map.of())) {
                assertRefused(refused.getValue(), () -> plugins.services(Map.of()));
            }
        }
    }

    @Test
    void anAdminServicesPathIsOneToFourSegmentsOfLowerCaseLettersDigitsAndDashes() {
        String longest = "a".repeat(32);
        for (String path : List.of("a", "status", "0-9", "a/b", "mappings/compile", "a/b/c/d", longest, longest + "/" + longest)) {
            assertTrue(Plugins.servicePath(path), path);
        }
        for (String path : List.of("", "/a", "a/", "a//b", "A", "a_b", "a.b", "..", "a/../b", "a b", "a/b/c/d/e", "a".repeat(33), "é")) {
            assertTrue(!Plugins.servicePath(path), path);
        }
    }

    @Test
    void aModulesCommandIsTakenWithItsUsageAndRunsWithTheModulesContextClassLoader() throws Exception {
        try (URLClassLoader classPath = classPath(moduleJar("modules.jar", PluginFixtures.Commands.class, PluginFixtures.Acme.class));
             Plugins plugins = Plugins.load(classPath, Optional.empty(), Map.of())) {
            assertEquals(Map.of("sample", "<words>\nsay the words"), plugins.commands());
            assertEquals(List.of(false, true), plugins.list().stream().map(Plugins.Listed::command).toList(), "acme brings none, sample one");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ClassLoader before = Thread.currentThread().getContextClassLoader();
            try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8)) {
                assertEquals(0, plugins.run("sample", new CommandContext(List.of("a", "b"), Map.of(), o, o, RT.clock())));
            }
            assertEquals(List.of("a b at " + RT.clock().instant() + ", own loader true"), out.toString(StandardCharsets.UTF_8).lines().toList());
            assertSame(before, Thread.currentThread().getContextClassLoader(), "the caller's context class loader back");
            String none = assertThrows(IllegalArgumentException.class,
                    () -> plugins.run("acme", new CommandContext(List.of(), Map.of(), System.out, System.err, RT.clock()))).getMessage();
            assertEquals("module acme brings no command", none);
        }
    }

    @Test
    void aPluginOfTheDirectoryThatBringsACommandIsRefusedNamingIt() throws IOException {
        jar("sample.jar", PluginFixtures.Commands.class);
        assertRefused("plugin sample: command() brings a command; only a module brings one, no plugin of RAIL8_PLUGINS", () -> load().close());
    }

    @Test
    void aModuleNamedAsACommandOfTheCommandLinesOwnBringsNoCommand() throws IOException {
        assertEquals(List.of("validate", "test", "lock", "deploy", "new-adapter", "help"), Plugins.CLI_COMMANDS);
        try (URLClassLoader deploy = classPath(moduleJar("deploy.jar", PluginFixtures.CommandsAsDeploy.class))) {
            assertRefused("module deploy: its command is the CLI's own deploy", () -> Plugins.load(deploy, Optional.empty(), Map.of()).close());
        }
        try (URLClassLoader named = classPath(moduleJar("named.jar", PluginFixtures.NamedDeploy.class));
             Plugins plugins = Plugins.load(named, Optional.empty(), Map.of())) {
            assertEquals(Map.of(), plugins.commands(), "without a command it loads");
        }
    }

    @Test
    void aCommandOutsideTheRulesIsRefusedNamingTheModule() throws IOException {
        String says = PluginFixtures.Says.class.getName();
        Map<Class<?>, String> refusals = Map.of(
                PluginFixtures.NullCommand.class, "module null-command: command() is null",
                PluginFixtures.FailingCommand.class, "module failing-command: command() failed: java.lang.IllegalStateException: no command today",
                PluginFixtures.NullUsage.class, "module null-usage: the usage of " + says + " is null",
                PluginFixtures.TabbedUsage.class, "module tabbed: the usage of " + says + " holds U+0009 on line 1; no control or format character");
        for (Map.Entry<Class<?>, String> refused : refusals.entrySet()) {
            try (URLClassLoader classPath = classPath(moduleJar(refused.getKey().getSimpleName() + ".jar", refused.getKey()))) {
                assertRefused(refused.getValue(), () -> Plugins.load(classPath, Optional.empty(), Map.of()).close());
            }
        }
    }

    @Test
    void aUsageIsAtMostSixteenLinesOfAHundredAndTwentyCharsWithNoControlOrFormatCharacter() {
        String sixteen = String.join("\n", java.util.Collections.nCopies(16, "x"));
        for (String usage : List.of("", "<file> [--twice]", "<file>\nprint the file", "\n", sixteen, "x".repeat(120), "é ü 日本 ✓")) {
            assertEquals(Optional.empty(), Plugins.usageRefusal(usage), usage);
        }
        Map<String, String> refused = Map.of(
                sixteen + "\nx", "has 17 lines; at most 16",
                sixteen + "\n", "has 17 lines; at most 16",
                "a\n" + "x".repeat(121), "has 121 chars on line 2; at most 120",
                "a\tb", "holds U+0009 on line 1; no control or format character",
                "a\r\nb", "holds U+000D on line 1; no control or format character",
                "a\u0085b", "holds U+0085 on line 1; no control or format character",
                "a\nb\u202Ec", "holds U+202E on line 2; no control or format character",
                "\u200B", "holds U+200B on line 1; no control or format character");
        refused.forEach((usage, why) -> assertEquals(Optional.of(why), Plugins.usageRefusal(usage), usage));
    }

    // --- refused while loading: IllegalArgumentException, the configuration is refused

    @Test
    void theDirectoryMustBeOne() throws IOException {
        assertRefused("RAIL8_PLUGINS names no directory: " + dir.resolve("missing"), () -> Plugins.load(NO_MODULES, Optional.of(dir.resolve("missing")), Map.of()));
        Path file = Files.writeString(dir.resolve("plugins"), "");
        assertRefused("RAIL8_PLUGINS names no directory: " + file, () -> Plugins.fromEnv(Map.of(Plugins.ENV, file.toString())));
    }

    @Test
    void anEntryIsAJarOrADirectoryOfJars() throws IOException {
        Files.writeString(dir.resolve("README.txt"), "");
        assertRefused("plugin README.txt: an entry of RAIL8_PLUGINS is a .jar or a directory of jars", this::load);
        Files.delete(dir.resolve("README.txt"));
        Files.createDirectory(dir.resolve("empty"));
        assertRefused("plugin empty: the directory holds no .jar", this::load);
    }

    @Test
    void anEntryNamesExactlyOnePluginOfItsOwn() throws IOException {
        Path none = jar("none.jar");
        assertRefused("plugin none.jar: its jars name 0 implementations of dev.rail8.api.plugin.Plugin in META-INF/services; a plugin names exactly one",
                this::load);
        Files.delete(none);
        Path two = jar("two.jar", PluginFixtures.Acme.class, PluginFixtures.BetaWithAcmesScheme.class);
        assertRefused("plugin two.jar: its jars name 2 implementations", this::load);
        Files.delete(two);
        PluginJars.write(dir.resolve("missing.jar"), "1", List.of("com.example.Missing"), PluginFixtures.classes());
        String broken = assertRefused("plugin missing.jar: its implementation cannot be built: ", this::load);
        assertTrue(broken.contains("com.example.Missing"), broken);
    }

    @Test
    void aPluginWhoseJarCannotBeReadIsRefusedNamingIt() throws IOException {
        Path mem = Path.of("/proc/self/mem");
        assumeTrue(Files.isReadable(mem), "a file every read of which fails from its first byte: Linux's /proc/self/mem");
        Path acme = Files.createDirectory(dir.resolve("acme"));
        PluginJars.write(acme.resolve("a.jar"), "1", List.of(PluginFixtures.Acme.class.getName()), PluginFixtures.classes());
        Files.createSymbolicLink(acme.resolve("b.jar"), mem);
        assertRefused("plugin acme: its jars cannot be read: ", this::load);
    }

    @Test
    void aNameIsALowerCaseLabelNoOtherPluginHasAndNotRail8() throws IOException {
        Path bad = jar("bad.jar", PluginFixtures.BadName.class);
        assertRefused("plugin bad.jar: the name 'Acme' is not a lower-case letter followed by at most 63 lower-case letters, digits or dashes",
                this::load);
        Files.delete(bad);
        Path none = jar("none.jar", PluginFixtures.NullName.class);
        assertRefused("plugin none.jar: the name 'null' is not a lower-case letter", this::load);
        Files.delete(none);
        Path rail8 = jar("rail8.jar", PluginFixtures.NamedRail8.class);
        assertRefused("plugin rail8.jar: the name rail8 stands for the platform itself", this::load);
        Files.delete(rail8);
        jar("a.jar", PluginFixtures.Acme.class);
        jar("b.jar", PluginFixtures.AcmeAgain.class);
        assertRefused("plugin b.jar: the name acme is plugin a.jar's already", this::load);
    }

    @Test
    void aPluginBuiltAgainstAnotherApiIsRefused() throws IOException {
        Path next = jar("next.jar", PluginFixtures.NextMinor.class);
        assertRefused("plugin next.jar: built against api 0.4, which a node of api " + Plugin.API + " does not load", this::load);
        Files.delete(next);
        jar("latest.jar", PluginFixtures.NoVersion.class);
        assertRefused("plugin latest.jar: built against api latest, which a node of api " + Plugin.API + " does not load", this::load);
    }

    @Test
    void whileTheApiIs0xOnlyItsOwnMinorLoadsFrom1OnAMinorUpToTheNodes() {
        assertTrue(Plugins.loads("0.1", "0.1") && Plugins.loads("0.2", "0.2"));
        assertTrue(!Plugins.loads("0.1", "0.2") && !Plugins.loads("0.2", "0.1") && !Plugins.loads("0.1", "1.0"));
        assertTrue(Plugins.loads("1.2", "1.0") && Plugins.loads("1.2", "1.2"));
        assertTrue(!Plugins.loads("1.2", "1.3") && !Plugins.loads("1.2", "2.0") && !Plugins.loads("2.0", "1.0"));
        assertTrue(!Plugins.loads("1.2", "1") && !Plugins.loads("1.2", "1.2.0") && !Plugins.loads("1.2", "1.x") && !Plugins.loads("1.2", ""));
    }

    @Test
    void everyLimitIsFinite() throws IOException {
        for (int i = 0; i <= Plugins.MAX_PLUGINS; i++) Files.createDirectory(dir.resolve("p" + i));
        assertRefused("RAIL8_PLUGINS holds " + (Plugins.MAX_PLUGINS + 1) + " entries; at most " + Plugins.MAX_PLUGINS, this::load);
        for (int i = 1; i <= Plugins.MAX_PLUGINS; i++) Files.delete(dir.resolve("p" + i));
        for (int i = 0; i <= Plugins.MAX_JARS; i++) Files.writeString(dir.resolve("p0").resolve("lib" + i + ".jar"), "");
        assertRefused("plugin p0: the directory holds " + (Plugins.MAX_JARS + 1) + " jars; at most " + Plugins.MAX_JARS, this::load);
    }

    // --- refused while merging: a plugin adds and never replaces

    @Test
    void aStepTypeOrLanguageAModuleHasRefusesTheConfigurationNamingBothOwners() throws IOException {
        try (URLClassLoader classPath = classPath(moduleJar("acme.jar", PluginFixtures.Acme.class))) {
            jar("signs.jar", PluginFixtures.SignsToo.class);
            assertRefused("plugin signs-too: the step type sign is module acme's already; a plugin adds and never replaces",
                    () -> Plugins.load(classPath, Optional.of(dir), Map.of()));
            Files.delete(dir.resolve("signs.jar"));
            jar("upper.jar", PluginFixtures.UpperToo.class);
            assertRefused("plugin upper-too: the expression language upper is module acme's already; a plugin adds and never replaces",
                    () -> Plugins.load(classPath, Optional.of(dir), Map.of()));
        }
    }

    @Test
    void aBuiltInStepTypeRefusesTheConfigurationNamingTheModuleOrPlugin() throws IOException {
        jar("built-in.jar", PluginFixtures.BuiltInStepType.class);
        assertRefused("plugin built-in-step: the step type route is built in", this::load);
        try (URLClassLoader classPath = classPath(moduleJar("route.jar", PluginFixtures.BuiltInStepType.class))) {
            assertRefused("module built-in-step: the step type route is built in", () -> Plugins.load(classPath, Optional.empty(), Map.of()));
        }
    }

    @Test
    void aSchemeAnotherPluginHasRefusesTheStartNamingBothOwners() throws IOException {
        jar("a.jar", PluginFixtures.Acme.class);
        jar("b.jar", PluginFixtures.BetaWithAcmesScheme.class);
        try (Plugins plugins = load()) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(context())).getMessage();
            assertEquals("plugin beta: the scheme acme is plugin acme's already; a plugin adds and never replaces", refused);
        }
        Files.delete(dir.resolve("a.jar"));
        Files.delete(dir.resolve("b.jar"));
        jar("mock.jar", PluginFixtures.MockScheme.class);
        try (Plugins plugins = load()) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(context())).getMessage();
            assertEquals("plugin mock-scheme: the scheme mock is the platform's mock", refused);
        }
    }

    @Test
    void aNullSchemeOrLanguageIsRefusedNamingThePlugin() throws IOException {
        jar("null.jar", PluginFixtures.NullKeys.class);
        try (Plugins plugins = load()) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(context())).getMessage();
            assertEquals("plugin null-keys: the scheme of " + PluginFixtures.Named.class.getName() + " is null", refused);
        }
        Files.delete(dir.resolve("null.jar"));
        jar("null.jar", PluginFixtures.NullLanguage.class);
        assertRefused("plugin null-language: the expression language of " + PluginFixtures.Language.class.getName() + " is null", this::load);
    }

    @Test
    void aComponentWhoseSettingsCannotBeDescribedRefusesTheStartNamingThePlugin() throws IOException {
        jar("badly.jar", PluginFixtures.BadSettings.class);
        try (Plugins plugins = load()) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(context())).getMessage();
            assertEquals("plugin badly-described: settings() of " + PluginFixtures.BadlyDescribed.class.getName()
                    + " failed: java.lang.IllegalArgumentException: field 'Max': a name is a lower-case letter, then at most 63 letters or digits", refused);
        }
    }

    @Test
    void aComponentWhoseSettingsAreNullRefusesTheStartNamingThePlugin() throws IOException {
        jar("unsaid.jar", PluginFixtures.NullSettings.class);
        try (Plugins plugins = load()) {
            String refused = assertThrows(IllegalStateException.class, () -> plugins.components(context())).getMessage();
            assertEquals("plugin null-settings: settings() of " + PluginFixtures.NullDescribed.class.getName() + " is null", refused);
        }
    }

    @Test
    void aPluginThatThrowsOrBringsTooMuchRefusesTheConfigurationNamingIt() throws IOException {
        jar("failing.jar", PluginFixtures.FailingSteps.class);
        assertRefused("plugin failing: steps() failed: java.lang.IllegalStateException: no steps today", this::load);
        Files.delete(dir.resolve("failing.jar"));
        jar("many.jar", PluginFixtures.TooManySteps.class);
        assertRefused("plugin too-many: steps() brings " + (Plugin.MAX_CONTRIBUTIONS + 1) + "; at most " + Plugin.MAX_CONTRIBUTIONS, this::load);
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
