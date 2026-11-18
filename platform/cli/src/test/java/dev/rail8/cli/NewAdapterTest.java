package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static dev.rail8.cli.CliRuns.run;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rail8 new-adapter}: the sample adapter under a new scheme, package and class names. A skeleton it writes compiles,
 * passes its own contract tests and component tests, and loads as a plugin under which its package validates and its flow
 * tests pass. A scheme that is no lower-case name, a Java keyword or one the node has, a package that is no Java name, a
 * directory that is not empty, and a missing scheme are usage errors with nothing written.
 */
class NewAdapterTest {

    static final String PACKAGE = "org.acme.rail8.adapter";
    static final String PATH = "src/main/java/org/acme/rail8/adapter/";

    @TempDir Path temp;

    @Test
    void theSkeletonIsTheSampleUnderTheNewNames() throws IOException {
        Path dir = temp.resolve("acme-adapter");
        Ran ran = run("new-adapter", dir.toString(), "--scheme", "acme", "--package", PACKAGE);
        assertEquals(0, ran.exit(), ran.toString());

        List<String> files = files(dir);
        assertEquals(List.of(
                "package/endpoints/orders-in.yaml",
                "package/endpoints/partner.yaml",
                "package/fixtures/order.xml",
                "package/flows/relay.test.yaml",
                "package/flows/relay.yaml",
                "package/package.yaml",
                "pom.xml",
                PATH + "AcmeComponent.java",
                PATH + "AcmePlugin.java",
                "src/main/resources/META-INF/services/dev.rail8.api.plugin.Plugin",
                "src/test/java/org/acme/rail8/adapter/AcmeComponentContractTest.java",
                "src/test/java/org/acme/rail8/adapter/AcmeComponentTest.java",
                "src/test/java/org/acme/rail8/adapter/AcmeMockContractTest.java"), files);
        assertTrue(ran.outLines().containsAll(files.stream().map(f -> "  " + f).toList()), "every file written is named: " + ran);
        for (String file : files) {
            String text = Files.readString(dir.resolve(file)).toLowerCase(Locale.ROOT);
            assertFalse(text.contains("webhook") || text.contains("examples") || text.contains("sample"), file + " keeps a name of the sample");
        }
        assertEquals("org.acme.rail8.adapter.AcmePlugin\n", Files.readString(dir.resolve("src/main/resources/META-INF/services/dev.rail8.api.plugin.Plugin")));
        assertTrue(Files.readString(dir.resolve(PATH + "AcmeComponent.java")).contains("public static final String SCHEME = \"acme\";"));
        assertTrue(Files.readString(dir.resolve("pom.xml")).contains("<groupId>org.acme.rail8.adapter</groupId>"));
        assertTrue(Files.readString(dir.resolve("package/endpoints/orders-in.yaml")).contains("scheme: acme"));
    }

    @Test
    void withoutAPackageThePackageComesFromTheSchemeAndAnEmptyDirectoryIsTaken() throws IOException {
        Path dir = Files.createDirectory(temp.resolve("empty"));
        Ran ran = run("new-adapter", dir.toString(), "--scheme", "files2");
        assertEquals(0, ran.exit(), ran.toString());
        assertTrue(Files.isRegularFile(dir.resolve("src/main/java/com/example/files2/Files2Component.java")), ran.toString());
    }

    @Test
    void theSkeletonBuildsPassesItsTestsAndItsPackageRunsWithIt() throws Exception {
        Path dir = temp.resolve("acme-adapter");
        assertEquals(0, run("new-adapter", dir.toString(), "--scheme", "acme", "--package", PACKAGE).exit());

        Path main = compile(dir.resolve("src/main/java"), temp.resolve("classes"), List.of());
        Path tests = compile(dir.resolve("src/test/java"), temp.resolve("test-classes"), List.of(main));
        Files.copy(dir.resolve("src/main/resources/META-INF/services/dev.rail8.api.plugin.Plugin"),
                Files.createDirectories(main.resolve("META-INF/services")).resolve("dev.rail8.api.plugin.Plugin"));

        try (URLClassLoader loader = new URLClassLoader(new URL[] {main.toUri().toURL(), tests.toUri().toURL()}, getClass().getClassLoader())) {
            List<String> classes = List.of("AcmeComponentContractTest", "AcmeMockContractTest", "AcmeComponentTest");
            LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request();
            for (String test : classes) request.selectors(selectClass(loader.loadClass(PACKAGE + "." + test)));
            TestExecutionSummary summary = launch(request.build(), loader);
            StringWriter failures = new StringWriter();
            summary.printFailuresTo(new PrintWriter(failures), 20);
            assertEquals(0, summary.getTotalFailureCount(), failures.toString());
            assertTrue(summary.getTestsSucceededCount() > 20, "the contract suites ran: " + summary.getTestsSucceededCount() + " passed");
        }

        Path plugins = Files.createDirectory(temp.resolve("plugins"));
        jar(main, plugins.resolve("acme.jar"));
        Ran validated = run("validate", dir.resolve("package").toString(), "--plugins", plugins.toString());
        assertEquals(0, validated.exit(), validated.toString());
        Ran tested = run("test", dir.resolve("package").toString(), "--plugins", plugins.toString());
        assertEquals(0, tested.exit(), tested.toString());
        assertTrue(tested.out().contains("PASS"), tested.toString());
    }

    @Test
    void aWrongSchemePackageOrDirectoryIsAUsageErrorAndWritesNothing() throws IOException {
        Path dir = temp.resolve("none");
        for (String scheme : List.of("Acme", "1acme", "ac-me", "", "class", "http", "sftp", "mock", "a".repeat(NewAdapter.SCHEME_MAX + 1))) {
            Ran ran = run("new-adapter", dir.toString(), "--scheme", scheme);
            assertEquals(2, ran.exit(), scheme + ": " + ran);
            assertFalse(Files.exists(dir), scheme + ": nothing written");
        }
        for (String pkg : List.of("org.acme.", "org.int.acme", "org..acme", "1org.acme")) {
            Ran ran = run("new-adapter", dir.toString(), "--scheme", "acme", "--package", pkg);
            assertEquals(2, ran.exit(), pkg + ": " + ran);
            assertTrue(ran.err().contains("--package"), pkg + ": " + ran);
            assertFalse(Files.exists(dir), pkg + ": nothing written");
        }
        Ran missing = run("new-adapter", dir.toString());
        assertEquals(2, missing.exit(), missing.toString());
        assertTrue(missing.err().contains("--scheme is missing"), missing.toString());
        assertEquals(2, run("new-adapter", "--scheme", "acme").exit(), "no directory");

        Path full = Files.createDirectory(temp.resolve("full"));
        Files.writeString(full.resolve("notes.txt"), "mine");
        Ran taken = run("new-adapter", full.toString(), "--scheme", "acme");
        assertEquals(2, taken.exit(), taken.toString());
        assertTrue(taken.err().contains("is not an empty directory"), taken.toString());
        assertEquals(List.of("notes.txt"), files(full));
        Ran taken2 = run("new-adapter", full.resolve("notes.txt").toString(), "--scheme", "acme");
        assertEquals(2, taken2.exit(), "a file is no empty directory: " + taken2);

        Ran http = run("new-adapter", dir.toString(), "--scheme", "http");
        assertTrue(http.err().contains("the scheme 'http' is taken"), http.toString());
    }

    /** The regular files under {@code dir}, relative to it with '/', sorted. */
    static List<String> files(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    /** The Java sources under {@code sources} compiled into {@code classes}, over this test's class path and {@code more}. */
    static Path compile(Path sources, Path classes, List<Path> more) throws IOException {
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-proc:none", "--release", "25", "-cp", classPath(more)));
        try (Stream<Path> walk = Files.walk(sources)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = javac.run(null, null, errors, args.toArray(String[]::new));
        assertEquals(0, code, "the skeleton compiles: " + errors.toString(StandardCharsets.UTF_8));
        return classes;
    }

    private static String classPath(List<Path> more) {
        List<String> entries = new ArrayList<>(more.stream().map(Path::toString).toList());
        entries.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        return String.join(java.io.File.pathSeparator, entries);
    }

    /** Runs {@code request} with {@code loader} as the thread's context class loader, as a build's test run would. */
    private static TestExecutionSummary launch(LauncherDiscoveryRequest request, ClassLoader loader) {
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            launcher.execute(request, listener);
            return listener.getSummary();
        } finally {
            thread.setContextClassLoader(before);
        }
    }

    /** A plugin jar at {@code jar} of every file under {@code classes}, with the version the node logs. */
    private static void jar(Path classes, Path jar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "0.1.0-SNAPSHOT");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, out);
                out.closeEntry();
            }
        }
    }
}
