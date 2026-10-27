package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Runtime;
import io.flottila.api.StepFactory;
import io.flottila.api.Timer;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.flow.Delivery;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.flottila.core.record.MessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.schemas.Package;
import io.flottila.schemas.Schemas;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The packages deployed on this node and their running flows (D-059, D-060 a/b, D-063). The deployed state lives in memory until
 * P1-09c (F-5). A deploy of a package that runs already is a redeploy: it swaps only the flows whose effective hash changed.
 *
 * <p>{@link #deploy} blocks: call it on a virtual thread. Its order: a bad name or revision ({@link Kind#BAD_REQUEST}); a
 * deployed environment ({@link Kind#FORBIDDEN}: manual deploy is non-production only, ARCHITECTURE §9); a closing node
 * ({@link Kind#STOPPING}); another deploy holding the node's lock ({@link Kind#CONFLICT}: one deploy at a time per node, whatever
 * the package) — all of them before the archive is read. Then the archive is unpacked into {@code <dataDir>/.incoming} and moved
 * atomically to {@code <dataDir>/packages/<name>/<sha256>/} (a directory an earlier node left there is replaced), unless that
 * directory is one this node keeps for the package (below) and holds the files recorded when it was unpacked, path and SHA-256
 * alike: then it is used as it is and nothing is unpacked. A kept directory whose files differ (one written into it, changed,
 * deleted, or one the node cannot read) is not trusted: the archive is unpacked afresh over it, and it stays kept whatever happens
 * next. A refused archive is {@link Kind#BAD_REQUEST}. Then the package is checked whole — {@code Package.problems()}, the
 * manifest's name, no
 * {@code dependencies}, a component for every scheme, every endpoint's config (its environment's keys laid over it) against
 * its component's {@code configSchema()}, no in endpoint setting {@code flow} or {@code maxBodyBytes}, one flow per in endpoint,
 * no flow from an out endpoint, every flow definition, no at-least-once flow above {@code FLOTTILA_BLOB_DURABLE_MAX} (D-058), and
 * no at-least-once flow the package runs that the new version removes or makes non-at-least-once while the work queue holds
 * items of it (queued, leased or held: they would never run; a flow whose new yaml does not read is not asked, its own problems
 * are listed; a queue that cannot answer is the node's failure, unless the package has problems: then it is their 422) — and
 * every flow built: its delivery, its worker (at-least-once)
 * and its effective hash ({@link DeployedFlow#hash()}). Each flow is then added (an id the package does not run, or one it lists
 * {@link DeployedFlow.State#FAILED}: nothing of that one runs), unchanged (the hash of the flow running: that flow runs on
 * untouched, with the stamps it was built with, and the new build is stopped with a zero deadline and closed), changed, or removed
 * (the package lists it, the new version has no such flow; a FAILED one has nothing to stop); a consumer is built for each added
 * and changed flow. Every problem is one line of one {@link Kind#INVALID}, and nothing running stops and nothing starts before
 * every check passed (a file the hash cannot read is the node's {@link IOException}).
 *
 * <p>Then the swap (D-063, F-5 amended). Phase A stops every changed and removed flow at once ({@link #stop(Flow, Duration)}: its
 * worker first, then its delivery within the node's stop deadline and its consumer together, that consumer's stop bounded by the
 * stop deadline once the delivery stopped: ≤ 2× the stop deadline), then closes its delivery's producers and asks its worker to
 * close its own (FIX-30); a stop that fails or does not complete is logged at WARNING and the swap goes on. Phase B starts every
 * added and changed flow at once — its consumer, whose start is bounded by the node's stop deadline on the node's timer, then,
 * once that start succeeded, its worker, as worker {@code nodeId} — only once
 * every flow of phase A has settled: a new flow on the in endpoint of an old one never meets the old consumer there. A removed
 * at-least-once flow, or one the new version runs otherwise, is asked for queue items again once its old worker drained: items
 * left are a WARNING naming it, never a delay of the answer. Then the package record is replaced, and {@code packages/<name>/} is
 * pruned to the current directory, the previous one (the last other archive the package ran: a deploy of the running archive
 * keeps it) and every one a running flow was built from — the directories this node keeps; one that cannot be deleted is a
 * WARNING.
 *
 * <p>A deploy is atomic to readers (D-063 "Listing"): while it runs, {@link #flows()} and {@link #packages()} answer what they
 * answered before it, phase A and B and a rollback included; the listing changes once, when the deploy returns (the new version)
 * or throws (the version before; each flow phase A stopped that does not run again then is listed {@link DeployedFlow.State#FAILED},
 * with nothing of it running).
 *
 * <p>A flow that does not start — its consumer's start fails, throws, or has not completed at the deadline (then it is stopped
 * once that start completes), or its worker's start throws (FIX-44) — is {@link Kind#FAILED}. On a first deploy that is the
 * refusal "flow '&lt;id&gt;' did not start: ..." and the cleanup below. On a redeploy the deploy rolls back ({@link #rollBack}): every flow of the new version stops — one that started
 * within the stop deadline, the others at once — and closes (one whose start was still pending at the deadline stops only once
 * that start completes, maybe after the old flow started again: FIX-43); then every changed and removed flow is built again from
 * the directory it was built from, with its stamps, and started through the same phase B; one that does not build or start again is
 * {@link DeployedFlow.State#FAILED}: listed, with nothing of it running. The refusal (500) is "version X did not start; Y runs
 * again" ("; flows [..] FAILED" when the package lists FAILED flows) with a problem line per flow that did not start, build or
 * start again; the package record is unchanged. An Error a component or a step factory throws in the rollback breaks it off:
 * what it built is stopped as below, every flow phase A stopped is listed FAILED, and the Error is a problem line and is added to
 * the refusal as suppressed.
 *
 * <p>Whatever refuses the deploy after the unpack began stops every flow it built and did not close yet (zero deadline), then
 * closes their deliveries' producers and asks their workers to close theirs, a worker's once the items it still runs finished
 * (FIX-30) — a flow that failed half way does the same for what it built of itself at once — and deletes the directories it
 * wrote, {@code packages/<name>/} too when nothing else is in it: nothing of it is started again, is listed or stays on disk, and
 * no producer stays open past the last item that sends. Every flow is stopped whatever another's stop does; a cleanup step that
 * fails does not replace the refusal: its failure is added to it as suppressed (FIX-32), and the steps after it run all the same.
 *
 * <p>A flow runs as {@code <package>/<flow>} (D-060 a): its snapshots, DLQ rows and queue items carry that id, and so does
 * the consumer option {@code flow}; the deploy also sets {@code maxBodyBytes}, the flow's limit. Every message carries the
 * versions {@code package=<name>@<version>} and, when the deploy names one, {@code revision}.
 *
 * <p>{@link #close} refuses deploys from then on, waits for a running one, then stops every flow that runs at once: its worker
 * first, then its delivery (within the node's stop deadline) and its consumer (bounded by the stop deadline once the delivery
 * stopped: ≤ 2× the stop deadline) together; once all have stopped it
 * closes every delivery's producers (FIX-30; a failed close is logged), then returns and rethrows the first stop failure, in flow
 * order.
 * Worker items still running are not awaited (P1-09e): each runs to its end through its worker's open producers, renewing
 * its lease, and the worker closes its producers once the last of them finished; only an item whose process ends first is
 * claimed again when its lease runs out.
 *
 * <p>The {@link Runtime} only goes into each deploy's {@code new FlowBuilder}: nothing here reads it, and no flow or lambda
 * captures it (CLAUDE.md); the components, the clock and the timer come in on their own.
 *
 * @since 0.1
 */
public final class Deployments {

    private static final System.Logger LOG = System.getLogger(Deployments.class.getName());
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern REVISION = Pattern.compile("[0-9A-Za-z._-]{1,64}");
    private static final String AT_LEAST_ONCE = "at-least-once";
    /** The consumer options the deploy sets on every in endpoint (D-059): a package that sets one itself is refused. */
    private static final List<String> RESERVED = List.of("flow", "maxBodyBytes");

    private final NodeConfig config;
    private final Runtime runtime;
    private final Map<String, Component> components;
    private final Clock clock;
    private final Timer timer;
    private final List<StepFactory> steps;
    private final List<Expressions> languages;
    private final MessageStore messages;
    private final Dlq dlq;
    private final WorkQueue queue;
    private final Meter meter;
    /** One deploy at a time; {@link #close} takes it to wait for the one running. */
    private final ReentrantLock deploying = new ReentrantLock();
    /** By name, so {@link #packages()} is sorted; written under {@link #deploying} — by a deploy once, as it returns or throws — read by anyone. */
    private final Map<String, Deployed> deployed = new ConcurrentSkipListMap<>();
    private volatile boolean closing;

    /**
     * @param runtime handed to each deploy's {@code new FlowBuilder} and read nowhere else
     * @param components the node's components by scheme: the consumers, and the config schemas the endpoints are checked against
     * @param clock {@code deployedAt}
     * @param timer the node's (the one the runtime carries): it bounds a consumer's start and stop by the node's stop deadline
     */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.components = Map.copyOf(components);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.steps = List.copyOf(steps);
        this.languages = List.copyOf(languages);
        this.messages = Objects.requireNonNull(messages, "messages");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.meter = Objects.requireNonNull(meter, "meter");
    }

    /**
     * One flow as the deploy built it: {@code archive} names the directory it was built from ({@code packages/<name>/<archive>}) and
     * {@code recipe} what its build took, so a failed redeploy can build it again (D-063); {@code from} is its in endpoint as its
     * consumer gets it; {@code worker} is null unless the flow is at-least-once, and {@code consumer} is null until one is built for
     * it ({@link #with}). A {@link DeployedFlow.State#FAILED} flow ({@link #failed}) has no delivery, worker or consumer.
     */
    private record Flow(DeployedFlow flow, String archive, Recipe recipe, Delivery delivery, FlowWorker worker, EndpointConfig from, Consumer consumer) {
        String id() {
            return flow.id();
        }

        /** Whether it runs: a FAILED flow has nothing to stop or close. */
        boolean runs() {
            return flow.state() == DeployedFlow.State.RUNNING;
        }

        Flow with(Consumer built) {
            return new Flow(flow, archive, recipe, delivery, worker, from, built);
        }

        /** This flow as a failed redeploy lists it when it cannot run it again: its record, FAILED; nothing of it built. */
        Flow failed() {
            DeployedFlow f = flow;
            return new Flow(new DeployedFlow(f.id(), f.pkg(), f.packageVersion(), f.revision(), f.flowVersion(), f.delivery(), f.hash(), DeployedFlow.State.FAILED),
                    archive, recipe, null, null, from, null);
        }

        /**
         * Asks its stopped worker to close its producers, never awaited — that close waits for the items still running — and
         * closes its delivery's producers once the delivery stopped; completes with the delivery's close, never fails (FIX-30).
         */
        Future<Void> close() {
            if (worker != null) worker.close();
            return delivery.close();
        }
    }

    /** What a flow's build took besides the builder's stamps: its definition, its yaml, and the package's endpoints as built. */
    private record Recipe(FlowDefinition def, Path flowFile, Map<String, EndpointConfig> endpoints) {}

    /**
     * A package deployed: its record, its flows, the directories this node keeps for it (D-063) — current, previous and in use —
     * by name, each with the files unpacked into it (path and SHA-256, as {@link DeployedPackage#files()}), and {@code previous},
     * the directory of the last other archive the package ran (null when there was none).
     */
    private record Deployed(DeployedPackage pkg, List<Flow> flows, Map<String, List<DeployedPackage.PackageFile>> kept, String previous) {}

    /** A flow phase B started ({@code failure} null) or did not, as it is to be stopped from here on ({@link #start}). */
    private record Start(Flow flow, Throwable failure) {}

    /**
     * What the checks hand the build: each valid endpoint's scheme and config (environment laid over), each valid flow's
     * definition, and the qualified ids of the at-least-once flows running that the new version does not run at-least-once.
     */
    private record Checked(Map<String, String> schemes, Map<String, JsonObject> configs, Map<String, FlowDefinition> flows, Set<String> leaving) {}

    /** A cleanup or housekeeping step that may fail. */
    @FunctionalInterface
    private interface Cleanup {
        void run() throws IOException;
    }

    /** {@code FLOTTILA_MAX_PACKAGE_BYTES}: the largest archive the admin API takes; it may inflate to four times that. */
    public long maxPackageBytes() {
        return config.maxPackageBytes();
    }

    /** {@code <dataDir>/.incoming}: uploads and unpacks land here, on the file system of the packages, so the move is atomic. */
    public Path incomingDir() {
        return config.dataDir().resolve(".incoming");
    }

    /** Every flow of every package deployed, RUNNING or FAILED, sorted by its qualified id; during a deploy as before it. */
    public List<DeployedFlow> flows() {
        return deployed.values().stream().flatMap(d -> d.flows().stream()).map(Flow::flow).sorted(Comparator.comparing(DeployedFlow::id)).toList();
    }

    /** Every package deployed, sorted by name; during a deploy as before it. */
    public List<DeployedPackage> packages() {
        return deployed.values().stream().map(Deployed::pkg).toList();
    }

    /**
     * Deploys the package archive {@code archive} as {@code name}, or redeploys it when the package runs already; see the class
     * comment for the order. Blocking: on a virtual thread.
     *
     * @return the package deployed and the flows added, swapped and removed
     * @throws DeployRefusedException what the admin API answers with its own status
     * @throws IOException the data directory cannot be written or the archive read: the node's failure, not the package's
     */
    public DeployResult deploy(String name, Optional<String> revision, Path archive) throws DeployRefusedException, IOException {
        Objects.requireNonNull(archive, "archive");
        if (!NAME.matcher(name).matches()) {
            throw refused(Kind.BAD_REQUEST, "the package name '" + name + "' must be a lower-case letter, then at most 63 lower-case letters, digits and dashes");
        }
        if (revision.isPresent() && !REVISION.matcher(revision.get()).matches()) {
            throw refused(Kind.BAD_REQUEST, "the revision '" + revision.get() + "' must be 1 to 64 letters, digits, dots, underscores and dashes");
        }
        if (config.deployed()) throw refused(Kind.FORBIDDEN, "manual deploy is non-production only (ARCHITECTURE §9)");
        // closing is read where the lock decides: a close that holds the lock (stopping) or took it before this deploy (closed)
        // has set it first, so no deploy starts a flow after close stopped them all
        if (!deploying.tryLock()) throw closing ? stopping() : refused(Kind.CONFLICT, "another deploy is running: try again once it is done");
        try {
            if (closing) throw stopping();
            return install(name, revision, archive);
        } finally {
            deploying.unlock();
        }
    }

    /**
     * Refuses deploys from now on, waits for a running one, then stops every flow at once and closes their producers (see the
     * class comment) and returns once all have stopped and every delivery's producers closed — a worker's close is not awaited;
     * the first stop failure, in flow order, is rethrown then.
     * Blocking: on a virtual thread.
     */
    public void close() {
        closing = true;
        deploying.lock();
        try {
            List<Flow> running = deployed.values().stream().flatMap(d -> d.flows().stream()).filter(Flow::runs).toList();
            deployed.clear();
            stop(running, config.stopDeadline()).eventually(() -> close(running)).await();
        } finally {
            deploying.unlock();
        }
    }

    // --- one deploy, under the lock

    /**
     * Unpacks (or reuses a kept directory that holds what was unpacked into it), checks, builds, classifies, swaps, then replaces
     * the package record and prunes — or, when phase B does not start every flow of a redeploy, rolls back; whatever refuses it
     * stops what it built and did not close yet and deletes what it wrote, unchecked failures included. It writes {@code deployed}
     * once: the new version as it returns, or — thrown once phase A stopped flows — the version before with each of those listed
     * FAILED unless the rollback runs it again ({@link #afterFailure}); {@code deployed.get(name)} is {@code running} until then.
     */
    private DeployResult install(String name, Optional<String> revision, Path archive) throws DeployRefusedException, IOException {
        String sha256 = FlowHash.sha256(archive);
        Deployed running = deployed.get(name);
        List<Flow> before = running == null ? List.of() : running.flows();
        List<DeployedPackage.PackageFile> unpackedInto = running == null ? null : running.kept().get(sha256);   // null: no kept directory
        Path incoming = incomingDir();
        Path packageDir = config.dataDir().resolve("packages").resolve(name);
        Path dir = packageDir.resolve(sha256);
        Files.createDirectories(incoming);
        Files.createDirectories(packageDir);
        List<Path> written = new ArrayList<>();   // what this deploy wrote: a refusal deletes it
        List<Flow> built = new ArrayList<>();     // every flow built and neither run nor closed yet: a refusal stops and closes them
        List<Flow> stopped = List.of();           // the running flows phase A stopped: a refusal lists each FAILED unless it runs again
        List<Flow> again = new ArrayList<>();     // of those, the ones the rollback runs again, once its whole restart settled
        Deployed next;
        DeployResult result;
        try {
            if (unpackedInto == null || !holds(dir, unpackedInto)) {   // a kept directory is reused only as it was unpacked
                Path unpacked = Files.createTempDirectory(incoming, "unpack-");
                written.add(unpacked);
                if (unpackedInto == null) written.add(dir);   // a kept directory unpacked afresh stays whatever happens: it runs or ran
                unpack(archive, unpacked);
                deleteTree(dir);   // what an earlier node left there, or a kept directory's changed files, is replaced, never merged
                Files.move(unpacked, dir, StandardCopyOption.ATOMIC_MOVE);
            }
            Package pkg = load(name, dir);
            Checked checked = check(name, pkg, before);
            String version = pkg.manifest().getString("version");
            List<String> problems = build(name, revision, version, sha256, pkg, dir, checked, built);

            Map<String, Flow> gone = new LinkedHashMap<>();   // by id: the flows listed that the new version has not classified yet
            before.forEach(f -> gone.put(f.id(), f));
            List<Flow> now = new ArrayList<>();        // the package's flows once deployed
            List<Flow> retiring = new ArrayList<>();   // phase A: the running flows changed or removed
            List<Flow> spare = new ArrayList<>();      // the new builds of unchanged flows: closed, never run
            List<String> added = new ArrayList<>();
            List<String> swapped = new ArrayList<>();
            for (Iterator<Flow> it = built.iterator(); it.hasNext(); ) {
                Flow f = it.next();
                Flow old = gone.remove(f.id());
                boolean runs = old != null && old.runs();   // a FAILED flow counts as added: nothing of it runs to stop
                if (runs && old.flow().hash().equals(f.flow().hash())) {
                    it.remove();
                    spare.add(f);
                    now.add(old);
                } else if (runs) {
                    swapped.add(f.id());
                    retiring.add(old);
                } else {
                    added.add(f.id());
                }
            }
            gone.values().stream().filter(Flow::runs).forEach(retiring::add);   // removed: a FAILED one has nothing to stop
            discard(List.of(), spare);
            List<Flow> starting = consumers(built, problems);   // added and changed, each with its consumer, none started
            if (!problems.isEmpty()) throw invalid(name, problems);
            now.addAll(starting);
            now.sort(Comparator.comparing(Flow::id));
            DeployedPackage deployedPackage = new DeployedPackage(name, version, revision, sha256, now.stream().map(Flow::id).toList(), clock.instant(), files(dir));
            retire(retiring, checked.leaving());   // phase A, settled when it returns; it never throws
            stopped = retiring;
            built.clear();
            built.addAll(starting);   // phase B calls every start: from here on a refusal stops their consumers too
            List<Start> started = start(starting);
            built.clear();
            started.forEach(s -> built.add(s.flow()));   // as each is to be stopped: one whose start is pending stops on its own
            List<Start> failed = started.stream().filter(s -> s.failure() != null).toList();
            if (!failed.isEmpty()) {
                if (running == null) throw refused(Kind.FAILED, problem(failed.getFirst(), "did not start"));   // a first deploy: the cleanup stops them all
                throw rollBack(version, running, retiring, started, packageDir, built, again);
            }
            String previous = null;   // the directory of the last other archive the package ran: none on a first deploy
            if (running != null) previous = sha256.equals(running.pkg().archiveSha256()) ? running.previous() : running.pkg().archiveSha256();
            Map<String, List<DeployedPackage.PackageFile>> kept = new HashMap<>();
            kept.put(sha256, deployedPackage.files());
            if (previous != null) kept.put(previous, running.kept().get(previous));
            now.forEach(f -> kept.computeIfAbsent(f.archive(), a -> running.kept().get(a)));   // in use: an old flow's was kept with it
            next = new Deployed(deployedPackage, List.copyOf(now), Map.copyOf(kept), previous);
            result = new DeployResult(deployedPackage, added, swapped, List.copyOf(gone.keySet()));
        } catch (Throwable failure) {
            if (!stopped.isEmpty()) deployed.put(name, afterFailure(running, stopped, again));   // the one write of a refused deploy
            cleanUp(failure, () -> {
                Throwable unstopped = discard(List.of(), built);
                if (unstopped != null) failure.addSuppressed(unstopped);
            });
            for (Path p : written) cleanUp(failure, () -> deleteTree(p));
            cleanUp(failure, () -> deleteIfEmpty(packageDir));   // packages/<name>/ goes with a refused first deploy; an earlier node's leftovers stay
            throw failure;
        }
        // the new flows run: from here on nothing undoes the deploy
        deployed.put(name, next);
        prune(name, packageDir, next.kept().keySet());
        return result;
    }

    /**
     * Rolls back a redeploy whose phase B did not start every flow (D-063) and returns the refusal to throw; it writes no listing:
     * {@code deployed} lists {@code running} throughout, and {@link #install} writes what the refusal leaves. First every flow of
     * the new version ({@code phaseB}) stops — one that started within the node's stop deadline, the others at once — and closes
     * (its delivery's producers awaited, FIX-30; a consumer whose start was still pending at the deadline stops only once that
     * start completes, FIX-43); only then is each flow phase A stopped ({@code retired}: changed or removed) built again, from the
     * directory it was built from and with its stamps, and started through phase B ({@link #start}). One that does not build or
     * start again is stopped and closed in turn. Once the whole restart settled, the ones that started again are added to
     * {@code again} — the one object of each, so a later stop stops each consumer once — and {@link #afterFailure} lists every
     * other retired flow {@link DeployedFlow.State#FAILED}. An unchecked failure that breaks the rollback off (an Error a component
     * or a step factory throws) leaves {@code again} empty — every retired flow FAILED — and what {@code built} holds to the
     * cleanup of {@link #install}, which stops it; it is a problem line and added as suppressed. The refusal is {@link Kind#FAILED}:
     * "version {@code version} did not start; &lt;running version&gt; runs again", with "; flows [..] FAILED" naming every flow
     * the package lists FAILED after it (a retired one {@link #afterFailure} lists FAILED, or one FAILED before the deploy), a
     * problem line per flow that did not start, build or start again, and each stop failure added as suppressed. {@code built}
     * holds what a throw would leave to stop wherever one can happen: discard never throws, so a list it stopped is cleared before
     * anything that may throw runs.
     */
    private DeployRefusedException rollBack(String version, Deployed running, List<Flow> retired, List<Start> phaseB, Path packageDir, List<Flow> built,
                                            List<Flow> again) {
        List<String> problems = new ArrayList<>();
        List<Flow> started = new ArrayList<>();
        List<Flow> unstarted = new ArrayList<>();
        for (Start s : phaseB) {
            if (s.failure() == null) {
                started.add(s.flow());
            } else {
                unstarted.add(s.flow());
                problems.add(problem(s, "did not start"));
            }
        }
        List<Throwable> unstopped = new ArrayList<>();
        try {
            // the failed version's phase A: each of its flows settles before the old one starts again, but for a consumer whose
            // start was still pending at the deadline: that one stops only once its start completes, maybe after (FIX-43)
            unstopped.add(discard(started, unstarted));
            built.clear();
            for (Flow f : retired) {
                Flow rebuilt = rebuild(f, packageDir, problems);
                if (rebuilt != null) built.add(rebuilt);
            }
            List<Flow> starting = consumers(built, problems);
            Set<String> withConsumer = new HashSet<>();
            starting.forEach(f -> withConsumer.add(f.id()));
            unstopped.add(discard(List.of(), built.stream().filter(f -> !withConsumer.contains(f.id())).toList()));   // its consumer did not build
            built.clear();
            built.addAll(starting);
            List<Start> restarted = start(starting);
            built.clear();
            List<Flow> back = new ArrayList<>();   // the retired flows that started again
            List<Flow> failedAgain = new ArrayList<>();
            for (Start s : restarted) {
                built.add(s.flow());
                if (s.failure() == null) {
                    back.add(s.flow());
                } else {
                    failedAgain.add(s.flow());
                    problems.add(problem(s, "did not start again"));
                }
            }
            unstopped.add(discard(List.of(), failedAgain));
            built.clear();
            again.addAll(back);   // the whole restart settled: they run on, listed by install over the ones phase A stopped
        } catch (RuntimeException | Error broke) {   // e.g. an Error a component or a step factory threw: install's cleanup stops what built holds
            problems.add("the rollback broke off: " + reason(broke));
            unstopped.add(broke);
        }
        List<String> failed = afterFailure(running, retired, again).flows().stream().filter(f -> !f.runs()).map(Flow::id).sorted().toList();
        DeployRefusedException refusal = new DeployRefusedException(Kind.FAILED, "version " + version + " did not start; " + running.pkg().version() + " runs again"
                + (failed.isEmpty() ? "" : "; flows " + failed + " FAILED"), problems);
        unstopped.stream().filter(Objects::nonNull).forEach(refusal::addSuppressed);
        return refusal;
    }

    /**
     * The flow {@code ran} built again — delivery, worker, effective hash; no consumer yet — from the directory it was built from,
     * as its build took it ({@link Recipe}) and with its stamps; null when it does not build, one problem line added.
     */
    private Flow rebuild(Flow ran, Path packageDir, List<String> problems) {
        DeployedFlow f = ran.flow();
        Path dir = packageDir.resolve(ran.archive());
        try {
            return flow(builder(f.pkg(), f.packageVersion(), f.revision(), ran.recipe().endpoints()), f.pkg(), f.packageVersion(), f.revision(), ran.archive(),
                    ran.recipe(), dir);
        } catch (IOException | RuntimeException refused) {
            problems.add("flow '" + local(f) + "' does not build again: " + reason(refused).replace(dir + File.separator, ""));
            return null;
        }
    }

    /**
     * The package as a deploy refused once phase A stopped {@code retired} leaves it listed: {@code running} with each retired flow
     * replaced by the flow of its id in {@code again} (the rollback ran it again), else by itself {@link DeployedFlow.State#FAILED}
     * ({@link Flow#failed}); every other flow, the record, the kept directories and previous as they are. Pure: {@code deployed}
     * holds {@code running} until {@link #install} writes this.
     */
    private static Deployed afterFailure(Deployed running, List<Flow> retired, List<Flow> again) {
        Map<String, Flow> back = new HashMap<>();
        again.forEach(f -> back.put(f.id(), f));
        return relisted(running, retired.stream().map(f -> back.getOrDefault(f.id(), f.failed())).toList());
    }

    /** {@code d} with each of {@code flows} listed in place of the flow of its id; its record, kept directories and previous as they are. */
    private static Deployed relisted(Deployed d, List<Flow> flows) {
        Map<String, Flow> byId = new HashMap<>();
        flows.forEach(f -> byId.put(f.id(), f));
        return new Deployed(d.pkg(), d.flows().stream().map(f -> byId.getOrDefault(f.id(), f)).toList(), d.kept(), d.previous());
    }

    private void unpack(Path archive, Path into) throws DeployRefusedException, IOException {
        try {
            PackageArchive.unpack(archive, into, config.maxPackageBytes());
        } catch (IllegalArgumentException refused) {
            throw refused(Kind.BAD_REQUEST, "the archive is refused: " + refused.getMessage());
        }
    }

    /** The package as unpacked; a file that does not parse stops the load, and the 422 names it (package-relative). */
    private static Package load(String name, Path dir) throws DeployRefusedException, IOException {
        try {
            return Package.load(dir);
        } catch (IllegalArgumentException unreadable) {
            throw new DeployRefusedException(Kind.INVALID, "package '" + name + "' cannot be read", List.of(unreadable.getMessage()));
        }
    }

    /**
     * Every check that needs no component built; all problems at once, as one {@link Kind#INVALID}. Of the flows {@code running}
     * (the package's, empty on a first deploy), every at-least-once one the new version does not run at-least-once — removed, or
     * another delivery mode — is asked whether the work queue holds items of it (blocking): if so, those items would never run.
     * One the new version names with a yaml that does not read is not asked: its problems are listed already. A question the queue
     * fails is the node's {@link IOException} (its cause the queue's failure) once every one was asked — unless there are
     * problems: then they are the answer.
     */
    private Checked check(String name, Package pkg, List<Flow> running) throws DeployRefusedException, IOException {
        List<String> problems = new ArrayList<>(pkg.problems());
        JsonObject manifest = pkg.manifest();
        if (!name.equals(manifest.getValue("name"))) problems.add("package.yaml names the package '" + manifest.getValue("name") + "', the deploy '" + name + "'");
        if (manifest.getValue("dependencies") instanceof JsonArray dependencies && !dependencies.isEmpty()) {
            problems.add("package.yaml: dependencies " + dependencies.encode() + " are refused until package.lock exists (P2-02b)");
        }
        Map<String, String> schemes = new LinkedHashMap<>();
        Map<String, JsonObject> configs = new LinkedHashMap<>();
        Set<String> outs = new HashSet<>();
        pkg.endpoints().forEach((id, endpoint) -> {
            if (!Schemas.ENDPOINT.validate(endpoint).valid()) return;   // what its schema says is listed already; nothing here relies on its shape
            String scheme = endpoint.getString("scheme");
            JsonObject cfg = overlaid(endpoint);
            schemes.put(id, scheme);
            configs.put(id, cfg);
            Component component = components.get(scheme);
            if (component == null) {
                problems.add("endpoint '" + id + "': no component on this node serves the scheme '" + scheme + "'");
            } else {
                for (Schemas.Problem p : Schemas.check(component.configSchema(), cfg).problems()) {
                    problems.add("endpoint '" + id + "' config (environment " + config.env() + ") at '" + p.path() + "': " + p.message());
                }
            }
            if ("in".equals(endpoint.getString("direction"))) {
                for (String key : RESERVED) {
                    if (cfg.containsKey(key)) problems.add("endpoint '" + id + "' sets '" + key + "': the deploy sets it on every in endpoint (the flow's qualified id, its maxBodyBytes)");
                }
            } else {
                outs.add(id);   // the schema allows in and out only
            }
        });
        Map<String, FlowDefinition> flows = new LinkedHashMap<>();
        Map<String, List<String>> feeds = new LinkedHashMap<>();
        pkg.flows().forEach((id, flow) -> {
            if (!Schemas.FLOW.validate(flow).valid()) return;
            FlowDefinition def;
            try {
                def = FlowDefinition.from(map(flow.put("id", name + "/" + id)));
            } catch (RuntimeException refused) {
                problems.add("flow '" + id + "': " + reason(refused));
                return;
            }
            flows.put(id, def);
            feeds.computeIfAbsent(def.fromEndpoint(), endpoint -> new ArrayList<>()).add(id);
            if (outs.contains(def.fromEndpoint())) {
                problems.add("flow '" + id + "' consumes from endpoint '" + def.fromEndpoint() + "', whose direction is out: a flow's from is an in endpoint");
            }
            long durableMax = config.blobs().durableMax();
            if (AT_LEAST_ONCE.equals(def.deliveryMode()) && def.maxBodyBytes() > durableMax) {
                problems.add("flow '" + id + "' is at-least-once with limits.maxBodyBytes " + def.maxBodyBytes() + " above FLOTTILA_BLOB_DURABLE_MAX " + durableMax + " (D-058)");
            }
        });
        feeds.forEach((endpoint, fed) -> {
            if (fed.size() > 1) problems.add("endpoint '" + endpoint + "' is the from endpoint of the flows " + fed + ": an in endpoint feeds one flow");
        });
        Set<String> stayQueued = new HashSet<>();   // the qualified ids the new version runs at-least-once
        flows.values().forEach(def -> {
            if (AT_LEAST_ONCE.equals(def.deliveryMode())) stayQueued.add(def.id());
        });
        Set<String> unread = new HashSet<>(pkg.flows().keySet());   // the flows the new version names whose yaml did not read: their problems say it all
        unread.removeAll(flows.keySet());
        Set<String> leaving = new HashSet<>();
        IOException unanswered = null;   // a question the queue failed: the node's failure, unless the package is refused anyway
        for (Flow f : running) {
            if (!AT_LEAST_ONCE.equals(f.flow().delivery()) || stayQueued.contains(f.id()) || unread.contains(local(f.flow()))) continue;
            leaving.add(f.id());
            Future<Boolean> items = queue.hasItems(f.id());
            if (Boolean.TRUE.equals(items.otherwiseEmpty().await())) {
                problems.add("flow '" + local(f.flow()) + "' runs at-least-once and the work queue holds items of it (queued, leased or held): this version "
                        + "does not run it at-least-once, so they would never run; deploy it once they are done");
            } else if (items.failed()) {
                unanswered = new IOException("the work queue could not say whether it holds items of flow '" + f.id() + "': " + reason(items.cause()), items.cause());
            }
        }
        if (!problems.isEmpty()) throw invalid(name, problems);
        if (unanswered != null) throw unanswered;
        return new Checked(schemes, configs, flows, Set.copyOf(leaving));
    }

    /** The endpoint's {@code config} with {@code environments.<env>.config} laid over it: a key the environment names is replaced whole, never merged. */
    private JsonObject overlaid(JsonObject endpoint) {
        JsonObject cfg = endpoint.getJsonObject("config");
        JsonObject environment = endpoint.getJsonObject("environments", new JsonObject()).getJsonObject(config.env(), new JsonObject());
        environment.getJsonObject("config", new JsonObject()).forEach(key -> cfg.put(key.getKey(), key.getValue()));
        return cfg;
    }

    /**
     * Every flow built — delivery, worker, effective hash; no consumer yet — over one builder that carries the package's versions,
     * each added to {@code flows} as soon as it is built; returns every failure as one problem line, the flows that built in
     * {@code flows} all the same.
     */
    private List<String> build(String name, Optional<String> revision, String version, String archive, Package pkg, Path dir, Checked checked,
                               List<Flow> flows) throws IOException {
        Map<String, EndpointConfig> endpoints = new LinkedHashMap<>();
        checked.configs().forEach((id, cfg) -> endpoints.put(id, endpoint(id, checked.schemes().get(id), cfg)));
        FlowBuilder builder = builder(name, version, revision, endpoints);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, FlowDefinition> each : checked.flows().entrySet()) {
            String id = each.getKey();
            try {
                flows.add(flow(builder, name, version, revision, archive, new Recipe(each.getValue(), pkg.flowFile(id), Map.copyOf(endpoints)), dir));
            } catch (RuntimeException refused) {
                // a step's failure may name a package file by its absolute path: a problem line names it from the package root
                problems.add("flow '" + id + "' does not build: " + reason(refused).replace(dir + File.separator, ""));
            }
        }
        return problems;
    }

    /** A builder over {@code endpoints} that stamps every message with {@code package=<name>@<version>} and, when given, {@code revision}. */
    private FlowBuilder builder(String name, String version, Optional<String> revision, Map<String, EndpointConfig> endpoints) {
        Map<String, String> versions = new HashMap<>();
        versions.put("package", name + "@" + version);
        revision.ifPresent(r -> versions.put("revision", r));
        return new FlowBuilder(runtime, steps, languages, endpoints).recording(messages, meter).dlq(dlq).queue(queue).versions(versions);
    }

    /**
     * One flow built from {@code recipe}, its package unpacked in {@code dir}: its delivery, its worker (at-least-once), its
     * effective hash ({@link FlowHash}: the files those two builds read, its endpoints as its consumer and its producers get them);
     * its consumer comes later ({@link #consumers}). A flow that fails after its delivery was built stops and closes what it built
     * of itself before the failure leaves (FIX-30), unchecked ones included — its worker has run nothing yet, so its close runs at
     * once; a worker that does not build has closed its own producers.
     *
     * @throws IOException a file the build read cannot be hashed: the node's failure
     */
    private Flow flow(FlowBuilder builder, String name, String version, Optional<String> revision, String archive, Recipe recipe, Path dir) throws IOException {
        FlowDefinition def = recipe.def();
        Map<String, EndpointConfig> endpoints = recipe.endpoints();
        Set<Path> reads = ConcurrentHashMap.newKeySet();   // the stages keep the sink: thread-safe, each path once
        builder.reads(reads::add);
        Path baseDir = recipe.flowFile().getParent();
        Delivery delivery = builder.delivery(def, baseDir);
        FlowWorker worker = null;
        boolean built = false;
        try {
            worker = AT_LEAST_ONCE.equals(def.deliveryMode()) ? builder.worker(def, baseDir, config.nodeId()) : null;
            EndpointConfig from = endpoints.get(def.fromEndpoint());
            Map<String, Object> options = new HashMap<>(from.options());
            options.put("flow", def.id());
            options.put("maxBodyBytes", def.maxBodyBytes());
            EndpointConfig consumed = new EndpointConfig(from.name(), from.uri(), options);
            String hash = FlowHash.of(dir, recipe.flowFile(), reads, consumed, def.referencedEndpoints().stream().map(endpoints::get).toList());
            built = true;
            return new Flow(new DeployedFlow(def.id(), name, version, revision, def.version(), def.deliveryMode(), hash, DeployedFlow.State.RUNNING),
                    archive, recipe, delivery, worker, consumed, null);
        } finally {
            if (!built) {
                delivery.stop(Duration.ZERO);   // nothing was delivered yet: the stop completes at once, and the close after it
                delivery.close();
                if (worker != null) {
                    worker.stop();
                    worker.close();
                }
            }
        }
    }

    /**
     * Builds the consumer of each of {@code flows} (the added and changed ones) and returns their copies with it, in order; one the
     * component refuses is one line added to {@code problems}. Nothing is started: a consumer never started is never stopped.
     */
    private List<Flow> consumers(List<Flow> flows, List<String> problems) {
        List<Flow> with = new ArrayList<>(flows.size());
        for (Flow f : flows) {
            Function<Envelope, Future<Ack>> handler = f.worker() == null ? f.delivery() : f.worker().pollingAfterAccept(f.delivery());
            try {
                with.add(f.with(components.get(f.from().scheme()).consumer(f.from(), handler)));
            } catch (RuntimeException refused) {
                problems.add("flow '" + local(f.flow()) + "' does not build: " + reason(refused));
            }
        }
        return with;
    }

    /**
     * Phase A of the swap (D-063): every flow of {@code retiring} at once — stopped with the node's stop deadline, then closed
     * ({@link #stopAndClose}); a stop that fails, throws or does not complete in time is logged and counts as settled, and keeps
     * no other flow from its stop. Those whose id is in
     * {@code leaving} are asked for queue items once their worker drained ({@link #recheck}). Returns once every one has settled:
     * the barrier phase B waits behind. Never throws.
     */
    private void retire(List<Flow> retiring, Set<String> leaving) {
        List<Future<Void>> settled = new ArrayList<>(retiring.size());
        for (Flow f : retiring) {
            Future<Void> retired = stopAndClose(f, config.stopDeadline());
            if (leaving.contains(f.id())) recheck(f, retired);
            settled.add(retired.onFailure(failed -> LOG.log(System.Logger.Level.WARNING, "flow '" + f.id() + "' did not stop cleanly as the redeploy "
                    + "replaced or removed it; the redeploy goes on: " + failed, failed)).otherwiseEmpty());
        }
        Future.join(settled).await();   // never fails: each settled one is otherwiseEmpty
    }

    /**
     * Once {@code retired} settled, asks the worker of the at-least-once flow {@code f} — removed, or run otherwise by the new
     * version — to close, which completes once its items finished (it drained); then asks the queue whether items of the flow are
     * left. Items left, or a queue that cannot answer, are a WARNING naming the flow; nothing waits for it.
     */
    private void recheck(Flow f, Future<Void> retired) {
        retired.transform(stopped -> f.worker().close()).compose(drained -> queue.hasItems(f.id())).onComplete(left -> {
            if (left.failed()) {
                LOG.log(System.Logger.Level.WARNING, "flow '" + f.id() + "' is no longer run at-least-once and its old worker drained, but the work queue "
                        + "could not say whether items of it are left: " + left.cause(), left.cause());
            } else if (left.result()) {
                LOG.log(System.Logger.Level.WARNING, "flow '" + f.id() + "' is no longer run at-least-once and its old worker drained, but the work queue "
                        + "still holds items of it: they run only once a version runs the flow at-least-once again");
            }
        });
    }

    /**
     * Phase B: starts {@code flows} at once — each consumer, its start bounded by the node's stop deadline on the node's timer
     * ({@link #bounded}), then, once it started, its worker (as worker {@code nodeId}) — and returns once every start settled, each
     * flow with its outcome, in order: a consumer start that failed, threw or had not completed at the deadline is a failure, and
     * so is a worker start that threw (FIX-44: it runs inside the flow's start future, never in a listener). A flow whose
     * start is still pending then comes back without its consumer, which is stopped once that start completes (a late success is
     * followed by a stop): no stop of the flow meets a consumer still starting.
     */
    private List<Start> start(List<Flow> flows) {
        List<Future<Void>> calls = new ArrayList<>(flows.size());
        List<Future<Void>> started = new ArrayList<>(flows.size());
        for (Flow f : flows) {
            Future<Void> call = Future.future(done -> f.consumer().start().onComplete(done));
            calls.add(call);
            // the worker starts inside the flow's start future: a throw fails it, the flow's failed start (FIX-44), where a
            // listener's throw would be lost
            started.add(bounded(call, "its consumer's start").compose(v -> {
                if (f.worker() != null) f.worker().start();
                return Future.succeededFuture();
            }));
        }
        Future.join(started).otherwiseEmpty().await();
        List<Start> outcome = new ArrayList<>(flows.size());
        for (int i = 0; i < flows.size(); i++) {
            Flow f = flows.get(i);
            Future<Void> call = calls.get(i);
            if (call.isComplete()) {   // started, or failed with it: a stop of the flow stops its consumer
                outcome.add(new Start(f, started.get(i).cause()));
            } else {   // the deadline came first
                call.onComplete(late -> stopConsumer(f).onFailure(failed -> LOG.log(System.Logger.Level.WARNING, "flow '" + f.id()
                        + "': its consumer, stopped once its late start completed, did not stop: " + failed, failed)));
                outcome.add(new Start(f.with(null), started.get(i).cause()));
            }
        }
        return outcome;
    }

    /**
     * Stops the flow {@code f}: its worker first (no round starts while its consumer stops), then its consumer, if one was built,
     * and its delivery with {@code deadline} together — the consumer's stop bounded by the node's stop deadline from the moment the
     * delivery stopped ({@link #bounded}: the answers it waits for end by then). Completes when all three have settled, failed with
     * the first failure; a worker stop that throws is one, and does not keep the consumer and the delivery from stopping.
     */
    private Future<Void> stop(Flow f, Duration deadline) {
        Future<Void> worker = f.worker() == null ? Future.succeededFuture() : Future.future(stopped -> {
            f.worker().stop();
            stopped.complete();
        });
        Future<Void> consumer = stopConsumer(f);
        Future<Void> delivery = f.delivery().stop(deadline);
        return Future.join(worker, delivery, delivery.transform(stopped -> bounded(consumer, "flow '" + f.id() + "': its consumer's stop"))).mapEmpty();
    }

    /** Stops the consumer of {@code f}, if one was built; one that throws fails the future. */
    private static Future<Void> stopConsumer(Flow f) {
        return f.consumer() == null ? Future.succeededFuture() : Future.future(done -> f.consumer().stop().onComplete(done));
    }

    /** Stops {@code flows} at once ({@link #stop(Flow, Duration)}); completes when every one has settled, failed with the first failure in flow order. */
    private Future<Void> stop(List<Flow> flows, Duration deadline) {
        return Future.join(flows.stream().map(f -> stop(f, deadline)).toList()).mapEmpty();
    }

    /**
     * Closes the producers of {@code flows} (FIX-30): each delivery's once it stopped, awaited; each worker's asked, not awaited
     * ({@link Flow#close}). Never fails, a failed producer close is logged.
     */
    private static Future<Void> close(List<Flow> flows) {
        return Future.all(flows.stream().map(Flow::close).toList()).mapEmpty();
    }

    /**
     * Stops flows built and not to run on — {@code started} ones within the node's stop deadline, {@code others} at once (a zero
     * deadline, cancelling what is in flight) — and closes each once it stopped ({@link #stopAndClose}); every flow is stopped
     * whatever another's stop does, and one that does not stop cleanly is a WARNING naming it. Returns once every one has settled:
     * the first failure, in that order, or null — a refusal adds it as suppressed (FIX-32). Never throws.
     */
    private Throwable discard(List<Flow> started, List<Flow> others) {
        List<Future<Void>> settled = new ArrayList<>(started.size() + others.size());
        for (Flow f : started) settled.add(discard(f, config.stopDeadline()));
        for (Flow f : others) settled.add(discard(f, Duration.ZERO));
        Future<?> all = Future.join(settled);
        all.otherwiseEmpty().await();
        return all.cause();
    }

    private Future<Void> discard(Flow f, Duration deadline) {
        return stopAndClose(f, deadline)
                .onFailure(failed -> LOG.log(System.Logger.Level.WARNING, "flow '" + f.id() + "', built and not to run on, did not stop cleanly: " + failed, failed));
    }

    /**
     * Stops {@code f} ({@link #stop(Flow, Duration)}) and closes it once that stop settled ({@link Flow#close}); never throws: a
     * stop that throws (its delivery's stop may) fails the future instead, and nothing is closed then.
     */
    private Future<Void> stopAndClose(Flow f, Duration deadline) {
        return Future.future(done -> stop(f, deadline).eventually(f::close).onComplete(done));
    }

    /**
     * {@code call} bounded by the node's stop deadline on the node's timer: completes as {@code call} does, or fails with a
     * {@link TimeoutException} ("{@code what} did not complete within the stop deadline ...") when the deadline comes first.
     */
    private Future<Void> bounded(Future<Void> call, String what) {
        Promise<Void> bounded = Promise.promise();
        Duration deadline = config.stopDeadline();
        Timer.Cancellable bound = timer.schedule(deadline, () -> bounded.tryFail(new TimeoutException(what + " did not complete within the stop deadline " + deadline)));
        call.onComplete(done -> {
            bound.cancel();
            if (done.succeeded()) bounded.tryComplete();
            else bounded.tryFail(done.cause());
        });
        return bounded.future();
    }

    /**
     * Runs the cleanup step {@code step} of a refused deploy; whatever it throws, an {@link Error} included, is added to
     * {@code failure} as suppressed (FIX-32): the refusal stays what is thrown, and the steps after it run all the same.
     */
    private static void cleanUp(Throwable failure, Cleanup step) {
        try {
            step.run();
        } catch (Throwable failed) {
            failure.addSuppressed(failed);
        }
    }

    /**
     * Deletes every directory of {@code packageDir} ({@code packages/<name>/}) not named in {@code kept}; each one that cannot be
     * deleted, or a list that fails, is a WARNING and the deploy stands.
     */
    private static void prune(String name, Path packageDir, Set<String> kept) {
        quietly("package '" + name + "': its directories could not be listed for pruning", () -> {
            List<Path> children;
            try (Stream<Path> list = Files.list(packageDir)) {
                children = list.toList();
            }
            for (Path child : children) {
                if (kept.contains(child.getFileName().toString())) continue;
                quietly("package '" + name + "': the directory " + child.getFileName() + " (neither current, previous nor in use) could not be deleted",
                        () -> deleteTree(child));
            }
        });
    }

    /** Runs {@code step}; its failure is a WARNING beginning with {@code what}. */
    private static void quietly(String what, Cleanup step) {
        try {
            step.run();
        } catch (IOException | RuntimeException failed) {
            LOG.log(System.Logger.Level.WARNING, what + ": " + failed, failed);
        }
    }

    // --- helpers

    private static DeployRefusedException refused(Kind kind, String error) {
        return new DeployRefusedException(kind, error, List.of());
    }

    private static DeployRefusedException stopping() {
        return refused(Kind.STOPPING, "the node is closing: it takes no deploy");
    }

    private static DeployRefusedException invalid(String name, List<String> problems) {
        return new DeployRefusedException(Kind.INVALID, "package '" + name + "' is invalid: " + problems.size() + " problem(s)", problems);
    }

    /** The flow's id within its package: the qualified id without {@code <package>/}, as the package's problem lines name it. */
    private static String local(DeployedFlow flow) {
        return flow.id().substring(flow.pkg().length() + 1);
    }

    /** A problem line of a start that failed: {@code flow '<id>' <what>: <reason>}. */
    private static String problem(Start s, String what) {
        return "flow '" + s.flow().id() + "' " + what + ": " + reason(s.failure());
    }

    private static String reason(Throwable failure) {
        return Objects.toString(failure.getMessage(), failure.toString());
    }

    /** An endpoint as a component gets it: {@code <scheme>://<id>}, its options as plain maps and lists; a null option is an absent one. */
    private static EndpointConfig endpoint(String id, String scheme, JsonObject cfg) {
        Map<String, Object> options = map(cfg);
        options.values().removeIf(Objects::isNull);
        return new EndpointConfig(id, URI.create(scheme + "://" + id), options);
    }

    /** A document as core and the components read it: nested objects as maps, arrays as lists (FlowDefinition reads no JsonObject). */
    private static Map<String, Object> map(JsonObject document) {
        Map<String, Object> map = new LinkedHashMap<>();
        document.forEach(e -> map.put(e.getKey(), plain(e.getValue())));
        return map;
    }

    private static Object plain(Object value) {
        if (value instanceof JsonObject o) return map(o);
        if (value instanceof JsonArray a) return a.stream().map(Deployments::plain).toList();
        return value;
    }

    /** Every regular file below {@code dir}, by its {@code /}-separated path from it, with its SHA-256. */
    private static List<DeployedPackage.PackageFile> files(Path dir) throws IOException {
        List<DeployedPackage.PackageFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.add(new DeployedPackage.PackageFile(dir.relativize(p).toString().replace(File.separatorChar, '/'), FlowHash.sha256(p)));
            }
        }
        return files;
    }

    /**
     * Whether {@code dir} holds exactly {@code unpacked} ({@link #files}: path and SHA-256 alike); no directory (a kept one deleted
     * from under the node), or a file in it the node cannot read, is a mismatch.
     */
    private static boolean holds(Path dir, List<DeployedPackage.PackageFile> unpacked) {
        try {
            return Files.isDirectory(dir) && unpacked.equals(files(dir));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Deletes the directory {@code dir} when nothing is in it. */
    private static void deleteIfEmpty(Path dir) throws IOException {
        boolean empty;
        try (Stream<Path> left = Files.list(dir)) {
            empty = left.findAny().isEmpty();
        }
        if (empty) Files.delete(dir);
    }

    /** Deletes {@code root} with everything below it; nothing when it does not exist. */
    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
