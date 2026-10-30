package io.flottila.cli;

import io.flottila.api.Component;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.runtime.Main;
import io.flottila.runtime.deploy.DeployRefusedException;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.Secrets;
import io.flottila.runtime.node.ComponentContext;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.schemas.Package;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code flottila validate <dir> [--env <env>] [--deps <dir>]} (D-079): {@link Package#problems()}, then its dependencies'
 * closure resolved from the candidates ({@link Dependencies#resolve}, D-089), then — when that found no problem — the node's dry
 * build ({@link Deployments#validate}) over what a node ships ({@link Main#shipped()}) in the environment {@code --env} (dev
 * unless given, whatever {@code FLOTTILA_ENV} says), with in-memory stores on one Vert.x, the closure deployed into it leaves
 * first so that the node's own resolution (D-087) runs; nothing of the package is started or stored. Prints the package's tree,
 * then one {@code problem: } line per problem, each once; exit 0 without a problem, 1 with one.
 */
final class Validate {

    /** The node id of the dry build's configuration: a DNS label, named nowhere in the output. */
    private static final String NODE_ID = "flottila-validate";

    private Validate() {}

    static int run(Cli.Options options, PrintStream out) throws IOException {
        Checked checked = check(options.dir(), options.value("--env").orElse("dev"), Dependencies.of(options));
        checked.pkg().ifPresent(pkg -> tree(pkg, out));
        checked.problems().forEach(p -> out.println("problem: " + p));
        return checked.problems().isEmpty() ? 0 : 1;
    }

    /**
     * What the check of a package directory found.
     *
     * @param pkg the package as loaded; empty when a file of it does not parse
     * @param problems each on one line, each once, in order: the load's, else {@link Package#problems()} and the dry build's
     * @param schemes the schemes of the components the dry build ran over ({@link Main#shipped()}); empty when it did not run
     * @param dependencies the closure the dry build deployed, leaves first; empty when it did not run
     */
    record Checked(Optional<Package> pkg, List<String> problems, Set<String> schemes, List<Dependencies.Found> dependencies) {}

    /**
     * {@link Package#problems()} of {@code dir}, then its closure's from {@code deps}, then the dry build's in the environment
     * {@code env} (see the class comment).
     */
    static Checked check(Path dir, String env, Dependencies deps) throws IOException {
        Package pkg;
        try {
            pkg = Package.load(dir);
        } catch (IllegalArgumentException unreadable) {   // a file that does not parse stops the load; the message starts with its path from the root
            return new Checked(Optional.empty(), List.of(Cli.oneLine(unreadable.getMessage())), Set.of(), List.of());
        }
        Set<String> problems = new LinkedHashSet<>();   // the dry build repeats Package.problems(): each line once, in order
        pkg.problems().forEach(p -> problems.add(Cli.oneLine(p)));
        Set<String> schemes = Set.of();
        List<Dependencies.Found> closure = List.of();
        if (pkg.manifest().getValue("name") instanceof String name) {   // without a name the problems above say so; nothing to build as
            List<String> unresolved = new ArrayList<>();
            List<Dependencies.Found> resolved = deps.resolve(name, pkg.lock(), unresolved);
            unresolved.forEach(p -> problems.add(Cli.oneLine(p)));
            if (unresolved.isEmpty()) {   // else the node would only say again that they are not deployed on it
                DryBuild built = dryBuild(name, dir, env, resolved);
                built.problems().forEach(p -> problems.add(Cli.oneLine(p)));
                schemes = built.schemes();
                closure = resolved;
            }
        }
        return new Checked(Optional.of(pkg), List.copyOf(problems), schemes, closure);
    }

    /**
     * {@code package <name> <version>}; then the artifacts at the root under {@code ./  -  -} when there is one; then every folder in
     * path order, {@code <path>/  <namespace|->  <description|->}, each followed by its own artifacts, {@code   <file>  <kind>  <id|->}.
     */
    private static void tree(Package pkg, PrintStream out) {
        JsonObject manifest = pkg.manifest();
        out.println("package " + Objects.toString(manifest.getValue("name"), "-") + " " + Objects.toString(manifest.getValue("version"), "-"));
        Map<String, List<Package.Artifact>> byFolder = pkg.artifacts().stream()
                .collect(Collectors.groupingBy(Validate::folder, LinkedHashMap::new, Collectors.toList()));
        List<Package.Artifact> atRoot = byFolder.getOrDefault("", List.of());
        if (!atRoot.isEmpty()) out.println("./  -  -");
        atRoot.forEach(a -> artifact(a, out));
        for (Package.Folder f : pkg.folders()) {
            out.println(f.path() + "/  " + f.namespace().map(Cli::oneLine).orElse("-") + "  " + f.description().map(Cli::oneLine).orElse("-"));
            byFolder.getOrDefault(f.path(), List.of()).forEach(a -> artifact(a, out));
        }
    }

    /** The folder {@code a} lies in, from the package root; {@code ""} for the root. */
    private static String folder(Package.Artifact a) {
        int slash = a.path().lastIndexOf('/');
        return slash < 0 ? "" : a.path().substring(0, slash);
    }

    private static void artifact(Package.Artifact a, PrintStream out) {
        out.println("  " + a.path().substring(a.path().lastIndexOf('/') + 1) + "  " + a.kind() + "  " + a.id().orElse("-"));
    }

    /** The dry build's problems and the schemes of the components it ran over. */
    private record DryBuild(List<String> problems, Set<String> schemes) {}

    /**
     * {@link Deployments#validate} of {@code dir} as {@code name} on a node of the environment {@code env} that runs nothing of
     * the package: the shipped components on a Vert.x of the run's own (closed after it), the shipped steps and languages, a fake
     * clock, timer and ids, in-memory blobs and stores, and a data directory of the run's own (deleted after it), into which
     * {@code closure} is deployed first, leaves first; a dependency the node refuses is the one problem, and nothing is built then.
     * The node is closed after the build.
     */
    private static DryBuild dryBuild(String name, Path dir, String env, List<Dependencies.Found> closure) throws IOException {
        Path data = Files.createTempDirectory("flottila-validate-");
        try {
            NodeConfig config = NodeConfig.from(Map.of("FLOTTILA_ENV", env, "FLOTTILA_NODE_ID", NODE_ID, "FLOTTILA_DATA_DIR", data.toString()))
                    .withSecrets(Secrets.placeholder());   // no secret off the node: references are syntax-checked, resolved to the placeholder (D-081)
            FakeRuntime rt = FakeRuntime.create();
            Main.Shipped shipped = Main.shipped();
            Vertx vertx = Vertx.vertx();
            try {
                Map<String, Component> components = shipped.components().apply(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock()))
                        .stream().collect(Collectors.toMap(Component::scheme, c -> c));
                Set<String> schemes = Set.copyOf(components.keySet());
                Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
                Deployments node = new Deployments(config, runtime, components, rt.clock(), rt.timer(), shipped.steps(), shipped.languages(),
                        new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), Meter.system());
                try {
                    for (Dependencies.Found dep : closure) {
                        try {
                            deploy(node, dep.pin().name(), Deploy.zip(dep.dir()), data);
                        } catch (DeployRefusedException refused) {
                            return new DryBuild(List.of("dependency " + dep.pin().name() + "@" + dep.pin().version() + " refused by the dry build's node: "
                                    + refused.getMessage()), schemes);
                        }
                    }
                    return new DryBuild(node.validate(name, dir), schemes);
                } finally {
                    node.close();
                }
            } finally {
                vertx.close().await();
            }
        } finally {
            FlowTests.deleteTree(data);
        }
    }

    /** Deploys a dependency's archive {@code zip} into {@code node} as {@code name}, written into {@code data} as {@code dependency-<name>.zip} first. */
    static void deploy(Deployments node, String name, byte[] zip, Path data) throws DeployRefusedException, IOException {
        node.deploy(name, Optional.empty(), Files.write(data.resolve("dependency-" + name + ".zip"), zip));
    }
}
