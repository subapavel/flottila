package dev.rail8.cli;

import dev.rail8.api.Component;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.Secrets;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.schemas.Package;
import dev.rail8.testkit.FakeRuntime;
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
 * {@code rail8 validate <dir> [--env <env>] [--deps <dir>] [--plugins <dir>]}: {@link Package#problems()}, then its dependencies'
 * closure resolved from the candidates ({@link Dependencies#resolve}), then — when that found no problem — the node's dry
 * build ({@link Deployments#validate}) over a node's modules and the plugins of {@code --plugins} ({@link Plugins}) in the
 * environment {@code --env} (dev
 * unless given, whatever {@code RAIL8_ENV} says), with in-memory stores on one Vert.x, the closure held for that call so that
 * the node's own resolution runs over it; nothing of the package or its closure is deployed, started or stored. Prints the package's tree,
 * then one {@code problem: } line per problem, each once; exit 0 without a problem, 1 with one.
 */
final class Validate {

    /** The node id of the dry build's configuration: a DNS label, named nowhere in the output. */
    private static final String NODE_ID = "rail8-validate";

    private Validate() {}

    static int run(Cli.Options options, Plugins plugins, PrintStream out) throws IOException {
        Checked checked = check(options.dir(), options.value("--env").orElse("dev"), Dependencies.of(options), plugins);
        checked.pkg().ifPresent(pkg -> tree(pkg, out));
        checked.problems().forEach(p -> out.println("problem: " + p));
        return checked.problems().isEmpty() ? 0 : 1;
    }

    /**
     * What the check of a package directory found.
     *
     * @param pkg the package as loaded; empty when a file of it does not parse
     * @param problems each on one line, each once, in order: the load's, else {@link Package#problems()} and the dry build's
     * @param schemes the schemes of the components the dry build ran over, the plugins' included; empty when it did not run
     * @param dependencies the closure the dry build held, leaves first; empty when it did not run
     */
    record Checked(Optional<Package> pkg, List<String> problems, Set<String> schemes, List<Dependencies.Found> dependencies) {}

    /**
     * {@link Package#problems()} of {@code dir}, then its closure's from {@code deps}, then the dry build's over {@code plugins} in
     * the environment {@code env} (see the class comment).
     */
    static Checked check(Path dir, String env, Dependencies deps, Plugins plugins) throws IOException {
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
                DryBuild built = dryBuild(name, dir, env, resolved, plugins);
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
     * {@link Deployments#validate(String, Path, List)} of {@code dir} as {@code name}, {@code closure}'s folders held, on a
     * node of the environment {@code env} that runs nothing: {@code plugins}' components on a Vert.x of the run's own (closed
     * after it), its steps and languages, a fake clock, timer and ids, in-memory blobs and stores, and an empty data directory
     * of the run's own (deleted after it). Nothing of the closure is deployed or started, so no component of it listens or
     * connects, in every environment. The node is closed after the build. A plugin's component that is refused, as it would
     * stop a node's start (its HTTP prefix included), is the one problem.
     */
    private static DryBuild dryBuild(String name, Path dir, String env, List<Dependencies.Found> closure, Plugins plugins) throws IOException {
        Path data = Files.createTempDirectory("rail8-validate-");
        try {
            NodeConfig config = NodeConfig.from(Map.of("RAIL8_ENV", env, "RAIL8_NODE_ID", NODE_ID, "RAIL8_DATA_DIR", data.toString()))
                    .withSecrets(Secrets.placeholder());   // no secret off the node: references are syntax-checked, resolved to the placeholder
            FakeRuntime rt = FakeRuntime.create();
            Vertx vertx = Vertx.vertx();
            try {
                List<Component> built;
                try {
                    built = plugins.components(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock(), HttpIngress.NONE));
                    SharedHttpPort.prefixes(built);   // a prefix the node's start would refuse
                } catch (IllegalStateException refused) {
                    return new DryBuild(List.of(String.valueOf(refused.getMessage())), Set.of());
                }
                Map<String, Component> components = built.stream().collect(Collectors.toMap(Component::scheme, c -> c));
                Set<String> schemes = Set.copyOf(components.keySet());
                Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
                Deployments node = new Deployments(config, runtime, components, rt.clock(), rt.timer(), plugins.steps(), plugins.languages(),
                        new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), Meter.system());
                try {
                    return new DryBuild(node.validate(name, dir, closure.stream().map(Dependencies.Found::dir).toList()), schemes);
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
}
