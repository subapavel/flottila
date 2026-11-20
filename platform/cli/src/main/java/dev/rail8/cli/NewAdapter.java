package dev.rail8.cli;

import dev.rail8.api.Component;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;

/**
 * {@code rail8 new-adapter <dir> --scheme <scheme> [--package <java package>]}: writes the skeleton of a new adapter into
 * {@code <dir>}, which must not exist or be empty. The skeleton is the sample adapter, the webhook plugin, under the new names:
 * its Maven build, the component with its settings description, the plugin and its service file, the contract tests of the
 * component and of its mock, the component's own tests, and a package that uses it ({@code package/}). It builds and passes
 * the contract suite as written; its HTTP code is what the developer replaces with their protocol. {@code <dir>} is made a git
 * repository on {@code main} whose first commit holds every file written, with a {@code .gitattributes} of {@code * -text}, so git
 * keeps every file's bytes as written, and a {@code .gitignore} of the build's {@code target/}: a node deploys its package from
 * a commit ({@code RAIL8_GIT_PATH=package}). The commit's author is the user's git identity, as git reads it.
 *
 * <p>The scheme is a lower-case letter, then lower-case letters and digits, at most {@value #SCHEME_MAX} characters, not a Java
 * keyword, not {@code rail8} (the skeleton's HTTP prefix is {@code /<scheme>}, and {@code /rail8} is the platform's), and not the
 * scheme of a component the node ships or of the platform's mock. The class names start with the scheme,
 * its first letter upper-cased ({@code acme}: {@code AcmeComponent}, {@code AcmePlugin}). The Java package, also the Maven group,
 * is {@code --package}, else {@code com.example.<scheme>}. A wrong scheme, package or directory is a usage error, exit 2, nothing
 * written; done, it prints what it wrote and the next commands, exit 0.
 */
final class NewAdapter {

    /** Where the sample adapter's files are on the class path: the build copies them there. */
    static final String TEMPLATE = "/adapter-template";
    /** The longest scheme taken. */
    static final int SCHEME_MAX = 32;
    /** The longest Java package taken. */
    static final int PACKAGE_MAX = 200;

    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9]*");
    /** The sample's names, longest first: each is replaced in one pass, so a new name that contains an old one stays as given. */
    private static final Pattern NAMES = Pattern.compile("dev\\.rail8\\.examples\\.webhook|dev/rail8/examples/webhook|dev\\.rail8\\.examples|Webhook|webhook");
    /** The files of the adapter's repository beside the template's, by path: git keeps the bytes as written, and leaves the build's output out. */
    private static final Map<String, String> REPOSITORY_FILES = Map.of(".gitattributes", "* -text\n", ".gitignore", "target/\n");

    private NewAdapter() {}

    static int run(Cli.Options options, PrintStream out) throws IOException {
        Path dir = options.dir();
        String scheme = options.value("--scheme").orElseThrow(() -> new Cli.Usage("new-adapter: --scheme is missing"));
        checkScheme(scheme);
        String pkg = options.value("--package").orElse("com.example." + scheme);
        if (pkg.length() > PACKAGE_MAX || !SourceVersion.isName(pkg)) {
            throw new Cli.Usage("new-adapter: --package '" + pkg + "' is not a Java package name of at most " + PACKAGE_MAX + " characters");
        }
        if (Files.exists(dir) && !emptyDirectory(dir)) throw new Cli.Usage("new-adapter: " + dir + " is not an empty directory");

        String name = Character.toUpperCase(scheme.charAt(0)) + scheme.substring(1);
        Map<String, String> names = Map.of(
                "dev.rail8.examples.webhook", pkg,
                "dev/rail8/examples/webhook", pkg.replace('.', '/'),
                "dev.rail8.examples", pkg,
                "Webhook", name,
                "webhook", scheme);
        List<String> written = new ArrayList<>(copy(names, dir));
        for (Map.Entry<String, String> file : REPOSITORY_FILES.entrySet()) {
            Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
            written.add(file.getKey());
        }
        written.sort(null);
        String commit = commit(dir);
        out.println("wrote the adapter '" + scheme + "' (package " + pkg + ") into " + dir + ", a git repository with the first commit " + commit + ":");
        written.forEach(file -> out.println("  " + file));
        out.println("next: mvn -f " + dir.resolve("pom.xml") + " verify   # its build and contract tests");
        out.println("      bin/run-adapter " + dir + "   # a node with the adapter, and the UI");
        return 0;
    }

    /**
     * Refuses a scheme that is not a lower-case name, is a Java keyword, would make the skeleton's HTTP prefix ({@code /<scheme>})
     * the one the platform keeps for itself, or is held by a shipped component or the mock.
     */
    private static void checkScheme(String scheme) {
        if (scheme.length() > SCHEME_MAX || !SCHEME.matcher(scheme).matches() || SourceVersion.isKeyword(scheme)) {
            throw new Cli.Usage("new-adapter: --scheme '" + scheme + "' is not a lower-case letter followed by lower-case letters and digits, "
                    + "at most " + SCHEME_MAX + " characters and no Java keyword");
        }
        if (("/" + scheme).equals(HttpIngress.RESERVED_PREFIX)) {
            throw new Cli.Usage("new-adapter: the scheme '" + scheme + "' is taken: its HTTP prefix " + HttpIngress.RESERVED_PREFIX
                    + " is kept for the platform itself");
        }
        Set<String> taken = shippedSchemes();
        if (taken.contains(scheme)) throw new Cli.Usage("new-adapter: the scheme '" + scheme + "' is taken: the node has " + taken);
    }

    /**
     * The schemes a node has without a plugin: its modules' components, built over a Vert.x of the run's own and fakes (building
     * opens nothing), and the platform's mock.
     */
    static Set<String> shippedSchemes() {
        FakeRuntime rt = FakeRuntime.create();
        Vertx vertx = Vertx.vertx();
        try (Plugins modules = Cli.plugins(Optional.empty())) {
            List<Component> shipped = modules.components(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock(), HttpIngress.NONE));
            Set<String> schemes = shipped.stream().map(Component::scheme).collect(Collectors.toCollection(TreeSet::new));
            schemes.add("mock");
            return schemes;
        } finally {
            vertx.close().await();
        }
    }

    /** Makes {@code dir} a git repository on {@code main} whose one commit holds every file in it; the commit's id. */
    private static String commit(Path dir) throws IOException {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            return git.commit().setMessage("The adapter as rail8 new-adapter wrote it\n").setSign(false).call().name();
        } catch (GitAPIException failed) {
            throw new IOException("the adapter's first commit failed: " + failed.getMessage(), failed);
        }
    }

    private static boolean emptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * Copies every file of the template into {@code dir}, each path and text with the sample's names replaced by
     * {@code names}; returns the written paths relative to {@code dir}, sorted.
     */
    private static List<String> copy(Map<String, String> names, Path dir) throws IOException {
        URL marker = NewAdapter.class.getResource(TEMPLATE + "/pom.xml");
        if (marker == null) throw new IOException("the adapter template is missing from the cli's class path at " + TEMPLATE);
        URI uri;
        try {
            uri = marker.toURI();
        } catch (URISyntaxException wrong) {
            throw new IOException("the adapter template's location " + marker + " is no URI", wrong);
        }
        if ("jar".equals(uri.getScheme())) {
            try (FileSystem jar = FileSystems.newFileSystem(uri, Map.of())) {
                return copy(jar.getPath(TEMPLATE), names, dir);
            }
        }
        return copy(Path.of(uri).getParent(), names, dir);
    }

    private static List<String> copy(Path template, Map<String, String> names, Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(template)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        List<String> written = new ArrayList<>();
        for (Path file : files) {
            String relative = rename(template.relativize(file).toString().replace('\\', '/'), names);
            Path target = dir.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.writeString(target, rename(Files.readString(file, StandardCharsets.UTF_8), names), StandardCharsets.UTF_8);
            written.add(relative);
        }
        return written;
    }

    /** {@code text} with each of the sample's names replaced by its new one, in one pass. */
    static String rename(String text, Map<String, String> names) {
        Matcher m = NAMES.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(names.get(m.group())));
        m.appendTail(out);
        return out.toString();
    }
}
