package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Expressions;
import dev.rail8.api.Health;
import dev.rail8.api.Runtime;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.Timer;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Settings;
import dev.rail8.core.admission.TargetLimit;
import dev.rail8.core.admission.Targets;
import dev.rail8.core.cache.InMemoryCache;
import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.flow.Delivery;
import dev.rail8.core.flow.FlowBuilder;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.flow.FlowWorker;
import dev.rail8.core.flow.Intake;
import dev.rail8.core.flow.Runs;
import dev.rail8.core.flow.SequentialIntake;
import dev.rail8.core.flow.WorkSignals;
import dev.rail8.core.flow.WorkerPolicy;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.MessageStore;
import dev.rail8.core.scope.MessageContext;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.git.Commit;
import dev.rail8.runtime.git.GitCache;
import dev.rail8.runtime.git.GitFailure;
import dev.rail8.runtime.git.PackageRepositories;
import dev.rail8.runtime.lease.InMemoryPollerLeases;
import dev.rail8.runtime.lease.PollerLeases;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.pg.PgTx;
import dev.rail8.schemas.Package;
import dev.rail8.schemas.Schemas;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The packages deployed on this node and their running flows. The listing lives in memory; each deploy from git writes its
 * package's {@link DeploymentRecord} (a deploy with no origin writes none), and {@link #restore} installs what those name at the
 * node's start. A deploy of a package that runs already is a redeploy: it swaps only the flows whose effective hash changed; a
 * deploy of a package listed {@link DeployedPackage.State#FAILED} (the restore could not install it) is a first deploy.
 *
 * <p>{@link #deploy(String, String, String)} blocks: call it on a virtual thread. Its order: a bad repository name or commit, or
 * a ref in a deployed environment ({@link Kind#BAD_REQUEST}); a deployed environment ({@link Kind#FORBIDDEN}: manual deploy is
 * non-production only), or a node without {@code RAIL8_GIT_BASE} ({@link Kind#FORBIDDEN}); a closing node
 * ({@link Kind#STOPPING}); another deploy holding the node's lock ({@link Kind#CONFLICT}: one deploy at a time per node, whatever
 * the package) — all of them before anything is fetched. Then the commit is read through the node's {@link GitCache}
 * ({@code <dataDir>/git}), fetched when the cache lacks it within {@code RAIL8_GIT_TIMEOUT} and, when {@code RAIL8_GIT_REF} is
 * set, proved reachable from that ref: a commit or ref not found, not reachable or with an incomplete history is
 * {@link Kind#INVALID}, any other failure of git (credentials refused, timed out, the cache full, the transport) is
 * {@link Kind#FAILED}, each the failure's one line. The package's folder in the commit is written into a folder of
 * {@code <dataDir>/.incoming} (a tree the seam refuses is {@link Kind#INVALID}), and its {@code package.yaml}'s name is checked
 * against the package-name pattern ({@link Kind#INVALID}) before it names a directory; a package of that name recorded from
 * another repository is {@link Kind#CONFLICT}. The folder is moved atomically to
 * {@code <dataDir>/packages/<name>/<content hex>/}, the hex of its content digest ({@code Package.content}; a directory an
 * earlier node left there is replaced), unless that directory is one this node keeps for the package (below) and holds the
 * files recorded when it was written, path and SHA-256 alike: then it is used as it is and the folder is deleted. A kept
 * directory whose files differ (one written into it, changed, deleted, or one the node cannot read) is not trusted: the folder
 * replaces it, and it stays kept whatever happens next. Then the package is checked whole — {@code Package.problems()}, the
 * manifest's name, its dependencies ({@link #dependencies}), a component for every scheme, every endpoint's config (its environment's keys laid over it) against
 * its component's settings description ({@code Component.settings()}, or its {@code configSchema()} when it describes
 * nothing), no in endpoint setting {@code flow} or {@code maxBodyBytes}, one flow per in endpoint,
 * no flow from an out endpoint, every flow definition, no {@code delivery.queue} on a flow that is not at-least-once and none of
 * {@code kafka}, the optional add-on not in this build, no at-least-once flow above {@code RAIL8_BLOB_DURABLE_MAX}, no
 * in endpoint feeding a flow whose consumer would listen where another of the package, the node's admin API, its peer link or a running flow of
 * another package listens (one listener per host:port on a node, {@code Component.listensOn}, {@link #listeners}; the node's
 * HTTP port is one of them), no in endpoint feeding a flow at a path of the node's HTTP port that another flow of the package or a
 * running flow of another package takes requests at (its component's prefix and its {@code address}, {@code Component.httpPrefix},
 * {@link #paths}), no flow
 * serving a node-local address another of the package or a running flow of another package serves and no cycle of calls between
 * flows ({@code Component.internalAddress}, {@link #links}, {@link Calls#check}), and
 * no at-least-once flow the package runs that the new version removes or makes non-at-least-once while the work queue holds
 * items of it (queued, leased, held or stopped: they would never run; a flow whose new yaml does not read is not asked, its own problems
 * are listed; a queue that cannot answer is the node's failure, and only when every question that failed was one the node's Postgres
 * pool refused a connection ({@link PgTx#refusedByThePool}) {@link Kind#BUSY}, retryable — unless the package has
 * problems: then it is their 422) — and
 * every flow built: its delivery, its worker (at-least-once)
 * and its effective hash ({@link DeployedFlow#hash()}). Each flow is then added (an id the package does not run, or one it lists
 * {@link DeployedFlow.State#FAILED}: nothing of that one runs), unchanged (the hash of the flow running: that flow runs on
 * untouched, with the stamps it was built with, and the new build is stopped with a zero deadline and closed), changed, or removed
 * (the package lists it, the new version has no such flow; a FAILED one has nothing to stop); a consumer is built for each added
 * and changed flow. Every problem is one line of one {@link Kind#INVALID}, and nothing running stops and nothing starts before
 * every check passed (a file the hash cannot read is the node's {@link IOException}). {@link #check} ends here: it answers what
 * the deploy would do, stops and closes what it built, deletes the directories it wrote and changes nothing else; the commits it
 * fetched stay in the cache.
 *
 * <p>Then the swap. Phase A stops every changed and removed flow at once ({@link #stop(Flow, Duration)}: its
 * worker first, then its delivery within the node's stop deadline and its consumer together, that consumer's stop bounded by the
 * stop deadline once the delivery stopped: ≤ 2× the stop deadline), then closes its delivery's producers and asks its worker
 * to close its own, never awaited; a stop that fails or does not complete is logged at WARNING and the swap goes on. Phase B starts every
 * added and changed flow at once — its consumer, whose start is bounded by the node's stop deadline on the node's timer, then,
 * once that start succeeded, its worker, as worker {@code nodeId} — only once
 * every flow of phase A has settled: a new flow on the in endpoint of an old one never meets the old consumer there. A removed
 * at-least-once flow, or one the new version runs otherwise, is asked for queue items again once its old worker drained: items
 * left are a WARNING naming it, never a delay of the answer. Then the deploy saves its {@link DeploymentRecord} (name, version,
 * repository, commit, content digest, the package's {@code deployedAt} and the pins of its dependencies) and waits for it: the
 * commit point. A save that fails
 * refuses a first deploy ({@link Kind#FAILED} "the deploy record was not written: &lt;cause&gt;", the cleanup below) and rolls a
 * redeploy back as a failed start does (below), with that line as its first problem; when the node's Postgres pool refused the
 * save a connection ({@link PgTx#refusedByThePool}) the refusal is {@link Kind#BUSY} instead, retryable. Only once the record is written is the
 * listing replaced, and {@code packages/<name>/} is pruned to the current directory, the previous one (the last other content the
 * package ran: a deploy of the running content keeps it), every one a running flow was built from — the directories this node
 * keeps — and every one a record pins (a
 * package's record, FAILED ones included, or during a restore any row read); one that cannot be deleted is a WARNING. Each call of
 * the package's flows that no flow on the node serves, and each call of another package's flow to an address the replaced
 * version served and no flow serves now, is then a WARNING, never a refusal.
 *
 * <p>A deploy is atomic to readers: while it runs, {@link #flows()} and {@link #packages()} answer what they
 * answered before it, phase A and B and a rollback included; the listing changes once, when the deploy returns (the new version)
 * or throws (the version before; each flow phase A stopped that does not run again then is listed {@link DeployedFlow.State#FAILED},
 * with nothing of it running).
 *
 * <p>A flow that does not start — its consumer's start fails, throws, or has not completed at the deadline (then it is stopped
 * once that start completes), or its worker's start throws — is {@link Kind#FAILED}. On a first deploy that is the
 * refusal "flow '&lt;id&gt;' did not start: ..." and the cleanup below. On a redeploy the deploy rolls back ({@link #rollBack}): every flow of the new version stops — one that started
 * within the stop deadline, the others at once — and closes (one whose start was still pending at the deadline stops only once
 * that start completes, maybe after the old flow started again); then every changed and removed flow is built again from
 * the directory it was built from, with its stamps, and started through the same phase B; one that does not build again, or whose
 * worker does not start again, is {@link DeployedFlow.State#FAILED}: listed, with nothing of it running; one whose consumer does
 * not start again runs, its consumer tried again (below). The refusal (500) is "version X did not start; Y runs
 * again" ("was not recorded" for a failed save, 503 when the pool refused it; "; flows [..] FAILED" when the package lists FAILED flows) with a problem line
 * per flow that did not start, build or start again (for a consumer tried again: "...; its consumer is tried again"); the
 * package's listing and record are unchanged. An Error a component or a step factory throws in the rollback breaks it off:
 * what it built is stopped as below, every flow phase A stopped is listed FAILED, and the Error is a problem line and is added to
 * the refusal as suppressed.
 *
 * <p>Every deploy that held the lock, refused or not ({@link #check} excepted), ends with one audit row
 * ({@link DeploymentStore#audit}): the actor, the repository, the commit (its id once read, else as the deploy named it) and the
 * outcome; a row that is not written is a WARNING and changes nothing of the deploy.
 *
 * <p>Whatever refuses the deploy after the write began stops every flow it built and did not close yet (zero deadline), then
 * closes their deliveries' producers and asks their workers to close theirs, a worker's once the items it still runs finished
 * — a flow that failed half way does the same for what it built of itself at once — and deletes the directories it
 * wrote, each {@code packages/<name>/} too when nothing else is in it: nothing of it is started again, is listed or stays on disk, and
 * no producer stays open past the last item that sends. Every flow is stopped whatever another's stop does; a cleanup step that
 * fails does not replace the refusal: its failure is added to it as suppressed, and the steps after it run all the same.
 *
 * <p>{@link #restore} runs at the node's start, before the node's admin port opens, under the deploy lock: each record's commit
 * is read from the git cache, fetched only when the cache lacks it and never checked for reachability, written into
 * {@code .incoming}, its content checked against the record's, and installed in restore mode — the record's repository, commit
 * and {@code deployedAt}; no record, no audit, no deployed-environment refusal; a flow whose consumer does not start runs, its
 * consumer tried again (below), one whose worker's start throws is FAILED, and the others run; each pin of the record is read
 * from its own repository and commit the same way — and a package refused whole is listed FAILED with its problems (one whose
 * consumer would listen where a package restored before it does, or that serves an address one of those serves or closes a
 * cycle of calls with them, included). {@link #health} is DEGRADED while anything is listed FAILED or RESTARTING, never DOWN.
 *
 * <p>Every consumer runs in a {@link RestartingConsumer}. Once its flow started, a consumer that breaks
 * ({@code Consumer.broken}) — or, at a restore or a rollback's start again, one that did not start — is stopped, and a fresh
 * one of the same endpoint is built and started after a delay that grows with each failure in a row (1 s, doubling, at most
 * 30 s; a run of 30 s starts the count again), with no limit on the attempts, until the flow stops: no operator is needed, and
 * nothing else stops. Meanwhile the flow is listed {@link DeployedFlow.State#RESTARTING}, read live and never stored, and
 * {@link #restarting} gives its failures, its next attempt and the last cause. A fresh consumer starts only once the one before
 * it stopped: a polling one without a bound, any other within the node's stop deadline.
 *
 * <p>{@link #undeploy} blocks too. Its order: a bad name ({@link Kind#BAD_REQUEST}); a deployed environment
 * ({@link Kind#FORBIDDEN}: manual undeploy is non-production only); the node's lock held by a deploy, an undeploy or a restore
 * ({@link Kind#CONFLICT}, or {@link Kind#STOPPING} while closing); a closing node ({@link Kind#STOPPING}); a package the node does
 * not list ({@link Kind#NOT_FOUND}); a running
 * at-least-once flow of it whose items the work queue holds ({@link Kind#CONFLICT}, a line per such flow: they would never run;
 * a queue that cannot answer is the node's {@link IOException}, and only when every question that failed was one the node's Postgres
 * pool refused a connection ({@link PgTx#refusedByThePool}) and no flow holds items {@link Kind#BUSY}, retryable). Then, under the lock, its record is deleted: the commit point. A delete
 * that fails is the node's {@link IOException}, one the pool refused {@link Kind#BUSY}; either changes nothing else.
 * Then every flow of it that runs stops and closes as phase A stops a flow (a stop that fails is a WARNING and counts as settled;
 * an at-least-once one is asked for queue items again once its worker drained, items that arrived meanwhile a WARNING),
 * the listing drops it, and {@code packages/<name>/} is pruned of every directory no remaining record pins and no remaining flow
 * reads (a failure is a WARNING, the undeploy stands). A package that pins it runs on: its pin's directory stays while a record
 * pins it, and a restore reads it from the pin's own commit. Deploying a commit of it again is the rollback. A restore after it
 * does not install it: its record is gone.
 *
 * <p>A flow runs as {@code <package>/<flow>}: its snapshots, DLQ rows and queue items carry that id, and so does
 * the consumer option {@code flow}; the deploy also sets {@code maxBodyBytes}, the flow's limit. Every message carries the
 * versions {@code package=<name>@<version>} and, for a deploy from git, {@code commit}: the commit its flow was built from.
 *
 * <p>A consumer whose component says it polls ({@code Component.polls}, asked in the same try as the consumer's build: a throw
 * is that flow's problem line) runs under the endpoint's poller lease ({@link LeasedConsumer}): the key
 * {@code <package>/<flow>/<endpoint uri as the component gets it>}, the owner {@code <nodeId>#<n>} (n counted here: every leased
 * consumer its own owner), the ttl and renew period of {@code RAIL8_POLLER_LEASE_*}. Its start completes at once and its
 * consumer polls only once the lease is held, so a consumer whose start fails after the deploy is never the deploy's
 * {@link Kind#FAILED}: the leased consumer breaks, and a fresh one, with an owner of its own, is tried again as above. Its stop
 * — phase A, a rollback, {@link #close}, a restart — stops the consumer, then releases the lease: the flow that replaces it, or
 * another node, takes the lease at its first tick, not a ttl later.
 *
 * <p>{@link #close} refuses deploys from then on, waits for a running one, then stops every
 * flow that runs at once: its worker first, then its delivery (within the node's stop deadline) and its consumer (bounded by the
 * stop deadline once the delivery stopped: ≤ 2× the stop deadline) together; once all have stopped it
 * closes every delivery's producers (a failed close is logged), then waits for every worker's items still running to
 * settle in the work queue and its producers to close — within the stop deadline counted from the start of the close:
 * a flow whose worker has not closed by then is a WARNING naming its count of unsettled items, whose work rows stay claimed
 * and run again once their lease ran out — then returns and rethrows the first stop failure, in flow order.
 * The node lease and each item's lease are renewed while the close waits: the node closes its lease and stores after it.
 * A redeploy or an undeploy never waits for a worker: its items run to their end through the worker's open
 * producers, renewing their lease, and the worker closes its producers once the last of them finished.
 *
 * <p>The {@link Runtime} only goes into each deploy's {@code new FlowBuilder}: nothing here reads it, and no flow or lambda
 * captures it (CLAUDE.md); the components, the clock and the timer come in on their own.
 *
 * @since 0.1
 */
public final class Deployments {

    private static final System.Logger LOG = System.getLogger(Deployments.class.getName());
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    /** The most packages one deploy's dependency closure holds. */
    static final int MAX_CLOSURE = 100;
    private static final String AT_LEAST_ONCE = "at-least-once";
    /** The consumer options the deploy sets on every in endpoint: a package that sets one itself is refused. */
    private static final List<String> RESERVED = List.of("flow", "maxBodyBytes");

    private final NodeConfig config;
    private final Runtime runtime;
    private final Map<String, Component> components;
    /** The HTTP prefix of each scheme whose component declares one ({@code Component.httpPrefix}), read once. */
    private final Map<String, String> prefixes;
    private final Clock clock;
    private final Timer timer;
    private final List<StepFactory> steps;
    private final List<Expressions> languages;
    private final MessageStore messages;
    private final Dlq dlq;
    private final WorkQueue queue;
    private final Intake intake;
    private final Meter meter;
    private final DeploymentStore records;
    /** The node's git cache over {@code RAIL8_GIT_*}; empty without {@code RAIL8_GIT_BASE}: no deploy from git, every record FAILED. */
    private final Optional<GitCache> git;
    private final PollerLeases leases;
    private final Targets targets;
    private final Runs runs;   // every run of every flow deployed here, on the node's clock and timer
    private final InMemoryCache caches;   // the cache of custom code, one for the node, kept across redeploys
    private final BooleanSupplier fence;
    private final Workers workers;
    private final WorkSignals signals;
    /** Numbers the owners of the leased consumers built here: {@code <nodeId>#<n>}. */
    private final AtomicLong owners = new AtomicLong();
    /** One deploy, undeploy or restore at a time; {@link #close} takes it to wait for the one running. */
    private final ReentrantLock deploying = new ReentrantLock();
    /**
     * By name, so {@link #packages()} is sorted; written under {@link #deploying} — by a deploy once, as it returns or throws, by an
     * undeploy once its record is deleted — read by anyone.
     */
    private final Map<String, Deployed> deployed = new ConcurrentSkipListMap<>();
    private volatile boolean closing;
    /** The rows a {@link #restore} installs, while it runs (under {@link #deploying}): what they pin is never pruned. */
    private List<DeploymentRecord> restoringRows = List.of();
    /**
     * Why a fetch of the running {@link #restore} could not reach the git host — the transport failed, or it timed out — once one
     * did (under {@link #deploying}): the restore fetches nothing more. Null otherwise.
     */
    private GitFailure.Reason unreachable;

    /** Deployments whose records live in memory: dev, tests and the command line. */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter) {
        this(config, runtime, components, clock, timer, steps, languages, messages, dlq, queue, meter, new InMemoryDeploymentStore());
    }

    /**
     * Deployments whose at-least-once flows save through a {@link SequentialIntake} over {@code messages} and {@code queue}, handing
     * each item off to the flow's started worker, leased to the node {@code config.nodeId()}: tests and tools; the node
     * passes its Postgres intake.
     */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter, DeploymentStore records) {
        this(config, runtime, components, clock, timer, steps, languages, messages, dlq, queue, meter, records, new Workers());
    }

    private Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                        List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter, DeploymentStore records,
                        Workers workers) {
        this(config, runtime, components, clock, timer, steps, languages, messages, dlq, queue, meter, records,
                new SequentialIntake(messages, queue, workers, config.nodeId()), new InMemoryPollerLeases(clock),
                new Targets(config.targets().defaults(), config.targets().overrides(), timer), () -> true, workers);
    }

    /** Deployments whose poller leases are held in this process's memory, on {@code clock}: one node alone. */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter, DeploymentStore records,
                       Intake intake) {
        this(config, runtime, components, clock, timer, steps, languages, messages, dlq, queue, meter, records, intake,
                new InMemoryPollerLeases(clock));
    }

    /**
     * Deployments whose flows send under limits of their own, built from {@code config}'s {@link NodeConfig#targets()} on
     * {@code timer}: tests and tools; the node passes the one {@link Targets} it built.
     */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter, DeploymentStore records,
                       Intake intake, PollerLeases leases) {
        this(config, runtime, components, clock, timer, steps, languages, messages, dlq, queue, meter, records, intake, leases,
                new Targets(config.targets().defaults(), config.targets().overrides(), timer));
    }

    /**
     * Deployments whose workers are never fenced: tests and tools; the node passes its lease's fence.
     */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter, DeploymentStore records,
                       Intake intake, PollerLeases leases, Targets targets) {
        this(config, runtime, components, clock, timer, steps, languages, messages, dlq, queue, meter, records, intake, leases, targets,
                () -> true);
    }

    /**
     * @param runtime handed to each deploy's {@code new FlowBuilder} and read nowhere else
     * @param components the node's components by scheme: the consumers, and the settings the endpoints are checked against
     * @param clock {@code deployedAt}, and the validity of a leased consumer's lease
     * @param timer the node's (the one the runtime carries): it bounds a consumer's start and stop by the node's stop deadline, and
     *              ticks every leased consumer
     * @param records where a deploy writes its package's record, the deploy's commit point, and its audit row
     * @param intake the durable save of every at-least-once flow's delivery: body, ingress record and item, over the
     *               same stores as {@code messages} and {@code queue}
     * @param leases where the consumer of a polling endpoint holds its lease
     * @param targets the node's limits per target system: every flow built here — deployed, swapped in by a redeploy,
     *                rebuilt by a rollback, restored — sends under them, so flows naming one target share its limit across packages
     *                and versions; {@link #targets()} lists their gauges
     * @param fence the node's fence ({@code NodeLease}): every at-least-once flow's worker built here sends, completes,
     *              takes the next item of a key and holds only while it is open ({@link FlowBuilder#fence})
     */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter, DeploymentStore records,
                       Intake intake, PollerLeases leases, Targets targets, BooleanSupplier fence) {
        this(config, runtime, components, clock, timer, steps, languages, messages, dlq, queue, meter, records, intake, leases, targets, fence,
                new Workers());
    }

    /**
     * Deployments whose at-least-once workers register, while started, in {@code workers} — the node's, the one {@code intake}
     * reserves its hand-off slots in; the other constructors give the deployments a registry of their own.
     */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter, DeploymentStore records,
                       Intake intake, PollerLeases leases, Targets targets, BooleanSupplier fence, Workers workers) {
        this(config, runtime, components, clock, timer, steps, languages, messages, dlq, queue, meter, records, intake, leases, targets, fence,
                workers, WorkSignals.NONE);
    }

    /**
     * Deployments whose at-least-once workers register in {@code workers} and announce through {@code signals} — the node's work
     * events: a keyed completion whose key's next row lies on another node tells that node.
     */
    public Deployments(NodeConfig config, Runtime runtime, Map<String, Component> components, Clock clock, Timer timer, List<StepFactory> steps,
                       List<Expressions> languages, MessageStore messages, Dlq dlq, WorkQueue queue, Meter meter, DeploymentStore records,
                       Intake intake, PollerLeases leases, Targets targets, BooleanSupplier fence, Workers workers,
                       WorkSignals signals) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.config = Objects.requireNonNull(config, "config");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.components = Map.copyOf(components);
        this.prefixes = SharedHttpPort.prefixes(List.copyOf(this.components.values()));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.steps = List.copyOf(steps);
        this.languages = List.copyOf(languages);
        this.messages = Objects.requireNonNull(messages, "messages");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.intake = Objects.requireNonNull(intake, "intake");
        this.meter = Objects.requireNonNull(meter, "meter");
        this.records = Objects.requireNonNull(records, "records");
        this.git = config.git().map(settings -> new GitCache(settings, config.dataDir().resolve("git"), config.maxPackageBytes(), config.deployed(),
                timer, this::named));
        this.leases = Objects.requireNonNull(leases, "leases");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.fence = Objects.requireNonNull(fence, "fence");
        this.runs = new Runs(clock, timer, WorkerPolicy.DEFAULT.lease());
        this.caches = new InMemoryCache(clock, InMemoryCache.MAX_ENTRIES, InMemoryCache.MAX_BYTES);
    }

    /**
     * Stops every run of {@code messageId} going on this node, in any flow deployed here, with {@code why}: an operator's cancel
     * taken here. True when one was running.
     */
    public boolean stop(String messageId, Runs.Why why) {
        return runs.stop(messageId, why);
    }

    /**
     * One flow as the deploy built it: {@code dir} names the directory it was built from ({@code packages/<name>/<dir>}, the hex of
     * its package's content digest) and
     * {@code recipe} what its build took, so a failed redeploy can build it again; {@code from} is its in endpoint as its
     * consumer gets it; {@code worker} is null unless the flow is at-least-once, and {@code consumer} is null until one is built for
     * it ({@link #with}). A {@link DeployedFlow.State#FAILED} flow ({@link #failed}) has no delivery, worker or consumer.
     */
    private record Flow(DeployedFlow flow, String dir, Recipe recipe, Delivery delivery, FlowWorker worker, EndpointConfig from,
                        RestartingConsumer consumer) {
        String id() {
            return flow.id();
        }

        /** Whether it runs: a FAILED flow has nothing to stop or close. */
        boolean runs() {
            return flow.state() == DeployedFlow.State.RUNNING;
        }

        Flow with(RestartingConsumer built) {
            return new Flow(flow, dir, recipe, delivery, worker, from, built);
        }

        /** Its record as listed now: {@link DeployedFlow.State#RESTARTING} while its consumer waits for its next attempt. */
        DeployedFlow listed() {
            if (consumer == null || consumer.waiting().isEmpty()) return flow;
            DeployedFlow f = flow;
            return new DeployedFlow(f.id(), f.pkg(), f.packageVersion(), f.commit(), f.flowVersion(), f.delivery(), f.hash(), DeployedFlow.State.RESTARTING);
        }

        /** This flow as a failed redeploy lists it when it cannot run it again: its record, FAILED; nothing of it built. */
        Flow failed() {
            DeployedFlow f = flow;
            return new Flow(new DeployedFlow(f.id(), f.pkg(), f.packageVersion(), f.commit(), f.flowVersion(), f.delivery(), f.hash(), DeployedFlow.State.FAILED),
                    dir, recipe, null, null, from, null);
        }

        /**
         * Asks its stopped worker to close its producers, never awaited here — that close waits for the items still in flight;
         * only the node's shutdown waits for them ({@link Deployments#drain}) — and closes its delivery's producers once
         * the delivery stopped; completes with the delivery's close, never fails.
         */
        Future<Void> close() {
            if (worker != null) worker.close();
            return delivery.close();
        }
    }

    /**
     * What a flow's build took besides the builder's stamps: its definition, its yaml, the package's endpoints as built — their
     * {@code secret://} references resolved — and as {@code written}, references kept (what its effective hash covers),
     * and where its consumer listens as its check asked the component ({@link #listeners}; empty when it accepts no connections):
     * the address it holds against another package's deploy while it runs; the path of the node's HTTP port it takes requests at
     * ({@link #paths}; empty when its component declares no prefix), held the same way; and the node-local addresses it serves and
     * calls as its check asked the components ({@link #links}), which it holds the same way. A flow a redeploy keeps by its hash keeps
     * these answers as they were, though a {@code secret://} value they came from may have changed since.
     */
    private record Recipe(FlowDefinition def, Path flowFile, Map<String, EndpointConfig> endpoints, Map<String, EndpointConfig> written,
                          Optional<InetSocketAddress> listensOn, Optional<String> httpPath, Calls.Links calls, Visible dependencies) {}

    /**
     * The package's direct dependencies as its steps read them: each name to the directory of the version its lock
     * resolved to, and to that version.
     */
    private record Visible(Map<String, Path> dirs, Map<String, String> versions) {}

    /**
     * The dependencies a check resolved: the direct ones {@code visible} to the steps, the closure as the record {@code pins}
     * it, in the order resolved, and as the package lists it ({@code listed}, each marked direct or not).
     */
    private record Resolved(Visible visible, List<DeploymentRecord.Pin> pins, List<DeployedPackage.Dependency> listed) {}

    /**
     * A package deployed: its listing, its flows, the directories this node keeps for it — current, previous and in use —
     * by name, each with the files written into it (path and SHA-256, as {@link DeployedPackage#files()}), {@code previous},
     * the directory of the last other content the package ran (null when there was none), and the pins of its record.
     */
    private record Deployed(DeployedPackage pkg, List<Flow> flows, Map<String, List<DeployedPackage.PackageFile>> kept, String previous,
                            List<DeploymentRecord.Pin> pins) {}

    /**
     * A flow phase B started ({@code failure} null) or did not ({@link #start}); {@code retried} is its consumer's failed start
     * when that consumer is tried again, else null.
     */
    private record Start(Flow flow, Throwable failure, Throwable retried) {}

    /**
     * What the checks hand the build: each valid endpoint's scheme and config (environment laid over) as written and with its
     * {@code secret://} references resolved ({@code resolved}), each valid flow's definition, the qualified ids of the
     * at-least-once flows running that the new version does not run at-least-once, where each flow's consumer listens, by
     * the flow's local id (a flow whose consumer accepts no connections is not in it), the path of the node's HTTP port each flow
     * takes requests at, by its local id (a flow whose component declares no prefix is not in it), the node-local addresses each
     * flow serves and calls, by its local id, and the package's dependencies as resolved.
     */
    private record Checked(Map<String, String> schemes, Map<String, JsonObject> configs, Map<String, JsonObject> resolved,
                           Map<String, FlowDefinition> flows, Set<String> leaving, Map<String, InetSocketAddress> listening,
                           Map<String, String> paths, Map<String, Calls.Links> calls, Resolved dependencies) {}

    /** Where {@code holder} — a flow and its in endpoint, or the node's admin API — listens. */
    private record Listener(String holder, InetSocketAddress at) {}

    /** The repository and the commit a package's folder was written from. */
    private record Origin(String repository, String commit) {}

    /** A version an off-node validate holds: its manifest's name and version, its content digest, and its folder. */
    private record Held(String name, String version, String content, Path dir) {}

    /** Reads the commit a record or a pin names: a deploy's checks it is reachable, a restore's reads the cache first. */
    @FunctionalInterface
    private interface Commits {
        Commit read(String repository, String commit) throws GitFailure;
    }

    /** A cleanup or housekeeping step that may fail. */
    @FunctionalInterface
    private interface Cleanup {
        void run() throws IOException;
    }

    /** {@code <dataDir>/.incoming}: commits and copies are written here, on the file system of the packages, so the move is atomic. */
    public Path incomingDir() {
        return config.dataDir().resolve(".incoming");
    }

    /** The node's environment, {@code RAIL8_ENV}: every endpoint is deployed with its {@code environments.<env>.config} overlay. */
    public String environment() {
        return config.env();
    }

    /** The origin senders call the node's HTTP port at, {@code RAIL8_HTTP_ORIGIN}, when the node is given one. */
    public Optional<String> httpOrigin() {
        return config.http().origin();
    }

    /** The HTTP prefix of the component of {@code scheme} ({@code Component.httpPrefix}), when it declares one. */
    public Optional<String> httpPrefix(String scheme) {
        return Optional.ofNullable(prefixes.get(scheme));
    }

    /** The settings description of every component the node serves, by scheme in name order. */
    public SortedMap<String, Settings> settings() {
        SortedMap<String, Settings> all = new TreeMap<>();
        components.forEach((scheme, component) -> all.put(scheme, component.settings()));
        return Collections.unmodifiableSortedMap(all);
    }

    /** The {@code configSchema()} of every step type that a module or plugin of the node builds, by type in name order. */
    public SortedMap<String, String> stepSchemas() {
        SortedMap<String, String> all = new TreeMap<>();
        steps.forEach(factory -> all.put(factory.type(), factory.configSchema()));
        return Collections.unmodifiableSortedMap(all);
    }

    /** The gauge of every target the flows built here named so far, sorted by target; never an unshared limit. */
    public List<TargetLimit.Gauge> targets() {
        return targets.gauges();
    }

    /**
     * Every flow of every package deployed, RUNNING, RESTARTING or FAILED, sorted by its qualified id; during a deploy as before
     * it, each RESTARTING while its consumer waits for its next attempt.
     */
    public List<DeployedFlow> flows() {
        return deployed.values().stream().flatMap(d -> d.flows().stream()).map(Flow::listed).sorted(Comparator.comparing(DeployedFlow::id)).toList();
    }

    /**
     * A flow whose consumer waits for its next attempt, listed {@link DeployedFlow.State#RESTARTING}.
     *
     * @param failures the failures in a row so far
     * @param next when the next attempt is due
     * @param cause the last failure: its start's, or its break's
     */
    public record Restarting(int failures, Instant next, String cause) {}

    /** The flows whose consumer waits for its next attempt now, by qualified id, sorted; during a deploy as before it. */
    public SortedMap<String, Restarting> restarting() {
        SortedMap<String, Restarting> all = new TreeMap<>();
        deployed.values().stream().flatMap(d -> d.flows().stream()).filter(f -> f.consumer() != null).forEach(f -> f.consumer().waiting()
                .ifPresent(w -> all.put(f.id(), new Restarting(w.failures(), w.next(), reason(w.cause())))));
        return Collections.unmodifiableSortedMap(all);
    }

    /**
     * The deliveries in flight now, summed over every flow that runs ({@link Delivery#inFlight}): an at-most-once run counts until
     * it ended and its trail closed, though it answered Accepted at once. During a deploy, those of the flows listed before it.
     */
    public int inFlight() {
        return deployed.values().stream().flatMap(d -> d.flows().stream()).filter(Flow::runs).mapToInt(f -> f.delivery().inFlight()).sum();
    }

    /** Every package deployed, sorted by name; during a deploy as before it. */
    public List<DeployedPackage> packages() {
        return deployed.values().stream().map(Deployed::pkg).toList();
    }

    /**
     * The definition of the flow {@code flowId} ({@code <package>/<flow>}) deployed here now, {@link DeployedFlow.State#RUNNING} or
     * FAILED — what an admin restart reads its delivery mode and its {@code partitionBy} from; empty for a flow not
     * deployed here. During a deploy, the version listed before it.
     */
    public Optional<FlowDefinition> definition(String flowId) {
        return deployed.values().stream().flatMap(d -> d.flows().stream()).filter(f -> f.id().equals(flowId)).findFirst().map(f -> f.recipe().def());
    }

    /**
     * Deploys the package at {@code commitOrRef} of {@code repository}, or redeploys it when the package runs already; see the class
     * comment for the order. In {@code dev} and {@code ci} the commit may be a ref ({@code HEAD}, a branch or tag name, or a full name
     * under {@code refs/heads/} or {@code refs/tags/}), resolved in the repository at the fetch. Blocking: on a virtual thread.
     *
     * @param actor who deploys, as the audit row names them
     * @return the package deployed and the flows added, swapped and removed
     * @throws DeployRefusedException what the admin API answers with its own status
     * @throws IOException the data directory or the git cache cannot be written: the node's failure, not the package's
     */
    public DeployResult deploy(String repository, String commitOrRef, String actor) throws DeployRefusedException, IOException {
        return fromGit(repository, commitOrRef, Objects.requireNonNull(actor, "actor"));
    }

    /**
     * What {@link #deploy(String, String, String)} of {@code commitOrRef} of {@code repository} would do, with every problem: the
     * same refusals, fetch, write, dependencies and checks, then the flows it built are stopped and closed and the directories it
     * wrote deleted. Nothing that runs changes, no record and no audit row is written; the commits fetched stay in the git cache.
     * Blocking: on a virtual thread.
     *
     * @return the package as the deploy would list it, and the flows it would add, swap and remove
     * @throws DeployRefusedException as {@link #deploy(String, String, String)}
     * @throws IOException as {@link #deploy(String, String, String)}
     */
    public DeployResult check(String repository, String commitOrRef) throws DeployRefusedException, IOException {
        return fromGit(repository, commitOrRef, null);
    }

    /** A deploy from git ({@code actor} given) or its check ({@code actor} null). */
    private DeployResult fromGit(String repository, String commitOrRef, String actor) throws DeployRefusedException, IOException {
        try {
            PackageRepositories.checkNames(repository, commitOrRef, config.deployed());
        } catch (IllegalArgumentException refused) {
            throw refused(Kind.BAD_REQUEST, refused.getMessage());
        }
        if (config.deployed()) throw refused(Kind.FORBIDDEN, "manual deploy is non-production only (ARCHITECTURE §9)");
        GitCache cache = git.orElseThrow(() -> refused(Kind.FORBIDDEN, "the node has no RAIL8_GIT_BASE: it deploys nothing"));
        // closing is read where the lock decides: a close that holds the lock (stopping) or took it before this deploy (closed)
        // has set it first, so no deploy starts a flow after close stopped them all
        if (!deploying.tryLock()) throw busy();
        try {
            if (closing) throw stopping();
            String[] read = {commitOrRef};   // the commit's id once it is read: what the audit row names
            try (PackageRepositories repos = cache.open()) {
                DeployResult result = fromCommit(repos, repository, commitOrRef, read, actor == null);
                if (actor != null) audit(actor, repository, read[0], new JsonObject().put("outcome", "deployed").put("package", result.pkg().name())
                        .put("version", result.pkg().version()));
                return result;
            } catch (DeployRefusedException refused) {
                if (actor != null) audit(actor, repository, read[0], new JsonObject().put("outcome", "refused").put("error", refused.error()));
                throw refused;
            } catch (IOException | RuntimeException failed) {
                if (actor != null) audit(actor, repository, read[0], new JsonObject().put("outcome", "failed").put("error", reason(failed)));
                throw failed;
            }
        } finally {
            deploying.unlock();
        }
    }

    /**
     * Reads {@code commitOrRef} of {@code repository} through {@code repos}, writes its package folder into {@code .incoming}, checks
     * its manifest's name and the repository the package is recorded from, then installs it ({@link #install}) with its pins read
     * through {@code repos} too; {@code read[0]} is set to the commit's id once it is read.
     */
    private DeployResult fromCommit(PackageRepositories repos, String repository, String commitOrRef, String[] read, boolean check)
            throws DeployRefusedException, IOException {
        Commit commit;
        try {
            commit = repos.forDeploy(repository, commitOrRef);
        } catch (GitFailure failed) {
            throw refused(failed, "");
        }
        read[0] = commit.id();
        Path folder = checkout(commit, "");
        return installed(folder, () -> {
            String name = named(folder, commit);
            Deployed listed = deployed.get(name);
            Optional<String> recorded = listed == null ? Optional.empty() : listed.pkg().repository();
            if (recorded.isPresent() && !recorded.get().equals(repository)) {
                throw refused(Kind.CONFLICT, "package '" + name + "' is deployed from the repository '" + recorded.get() + "', not '" + repository
                        + "': a package comes from one repository; undeploy it first to deploy it from another");
            }
            return install(name, Optional.of(new Origin(repository, commit.id())), folder, hex(Package.content(folder)), repos::forDeploy, repos, null, check);
        });
    }

    /**
     * The name {@code package.yaml} of the folder written from {@code commit} gives the package, once it is a package's name: a lower-case
     * letter, then at most 63 lower-case letters, digits and dashes. Checked before the name names a directory; a manifest that does
     * not read, or a name that is not one, is {@link Kind#INVALID}.
     */
    private static String named(Path folder, Commit commit) throws DeployRefusedException, IOException {
        Object name;
        try {
            name = Package.load(folder).manifest().getValue("name");
        } catch (IllegalArgumentException unreadable) {
            throw new DeployRefusedException(Kind.INVALID, "the package of " + commit.repository() + " at " + commit.id() + " cannot be read",
                    List.of(unreadable.getMessage()));
        }
        if (!(name instanceof String n) || !NAME.matcher(n).matches()) {
            throw refused(Kind.INVALID, "the package.yaml of " + commit.repository() + " at " + commit.id() + " names the package '" + name
                    + "': a package name is a lower-case letter, then at most 63 lower-case letters, digits and dashes");
        }
        return n;
    }

    /**
     * The package folder of {@code commit} written into a fresh folder of {@code .incoming}, the caller's to move or delete; a tree the
     * seam refuses is {@link Kind#INVALID} and a cache that lacks one of its objects the refusal of its {@link GitFailure}, each after
     * {@code of} ({@code ""}, or the dependency it is); whatever stops the write deletes what it wrote.
     */
    private Path checkout(Commit commit, String of) throws DeployRefusedException, IOException {
        return written(Files.createTempDirectory(Files.createDirectories(incomingDir()), "commit-"), into -> {
            try {
                commit.write(into);
            } catch (GitFailure failed) {
                throw refused(failed, of);
            } catch (IllegalArgumentException refused) {
                throw refused(Kind.INVALID, of + commit.repository() + " at " + commit.id() + " is refused: " + refused.getMessage());
            } catch (UncheckedIOException disk) {
                throw disk.getCause();
            }
        });
    }

    /**
     * The refusal of a git failure, its one line after {@code of}: a commit or ref not found, not reachable from the pool's ref or
     * with an incomplete history is the deploy's {@link Kind#INVALID}; credentials refused, a timeout, a full cache or a failed
     * transport are the node's {@link Kind#FAILED}.
     */
    private static DeployRefusedException refused(GitFailure failed, String of) {
        Kind kind = switch (failed.reason()) {
            case NOT_FOUND, NOT_REACHABLE, HISTORY_INCOMPLETE -> Kind.INVALID;
            case CREDENTIALS_REFUSED, TIMED_OUT, CACHE_FULL, TRANSPORT_FAILED -> Kind.FAILED;
        };
        return refused(kind, of + failed.getMessage());
    }

    /** Writes the audit row of a deploy; one that is not written is a WARNING. */
    private void audit(String actor, String repository, String commit, JsonObject outcome) {
        JsonObject detail = new JsonObject().put("repository", repository).put("commit", commit).mergeIn(outcome);
        Future<Void> written = Future.future(done -> records.audit(actor, repository, detail).onComplete(done));
        written.otherwiseEmpty().await();
        if (written.failed()) LOG.log(System.Logger.Level.WARNING, "the audit row of a deploy of " + repository + " at " + commit + " was not written: "
                + reason(written.cause()), written.cause());
    }

    /**
     * Deploys the package folder {@code folder} as {@code name} with no origin: {@code rail8 test}'s deploy, the only one that uses
     * it. The deploy works on a copy of the folder, made once the lock is held, and never writes into {@code folder}: an edit of it
     * while the deploy or its flows run changes nothing of them. No record and no audit row is written, so a restore never installs
     * it, and the package lists no repository and no commit. Its dependencies are read only from the directories the node has
     * with the content their lock pins: nothing is fetched. Otherwise its order is the one of
     * {@link #deploy(String, String, String)}: a bad name ({@link Kind#BAD_REQUEST}); a deployed environment ({@link Kind#FORBIDDEN}:
     * a deploy with no origin is dev and ci only); a closing node ({@link Kind#STOPPING}); another deploy holding the lock
     * ({@link Kind#CONFLICT}); then the copy, in which anything but a regular file or a directory is {@link Kind#BAD_REQUEST}; then
     * the checks, the build and the swap. Blocking: on a virtual thread.
     *
     * @return the package deployed and the flows added, swapped and removed
     * @throws DeployRefusedException as {@link #deploy(String, String, String)}
     * @throws IOException {@code folder} cannot be read or the data directory written: the caller's or the node's failure
     */
    public DeployResult deploy(String name, Path folder) throws DeployRefusedException, IOException {
        Objects.requireNonNull(folder, "folder");
        if (!NAME.matcher(name).matches()) throw badName(name);
        if (config.deployed()) throw refused(Kind.FORBIDDEN, "a deploy with no origin is dev and ci only: it writes no record");
        if (!deploying.tryLock()) throw busy();
        try {
            if (closing) throw stopping();
            Path copy = copied(folder);
            return installed(copy, () -> install(name, Optional.empty(), copy, hex(Package.content(copy)), null, null, null, false));
        } finally {
            deploying.unlock();
        }
    }

    /** One install of a deploy, as {@link #installed} runs it. */
    @FunctionalInterface
    private interface Install {
        DeployResult run() throws DeployRefusedException, IOException;
    }

    /**
     * Runs {@code install}, then deletes {@code folder}, the deploy's folder of {@code .incoming}: by then the install moved it into
     * {@code packages/}, unless the deploy was refused or a kept directory served. A delete that fails is added to a refusal as
     * suppressed, and after a deploy that took effect it is a WARNING: the deploy stands.
     */
    private static DeployResult installed(Path folder, Install install) throws DeployRefusedException, IOException {
        DeployResult result;
        try {
            result = install.run();
        } catch (Throwable failure) {
            cleanUp(failure, () -> deleteTree(folder));
            throw failure;
        }
        quietly("the folder " + folder.getFileName() + " of .incoming could not be deleted", () -> deleteTree(folder));
        return result;
    }

    /**
     * Undeploys the package {@code name}; see the class comment for the order. Blocking: on a virtual thread.
     *
     * @return the package undeployed, the version it was listed with and its flows, by qualified id
     * @throws DeployRefusedException what the admin API answers with its own status
     * @throws IOException the package's record could not be deleted, or the work queue could not answer: the node's failure, and
     *         nothing else changed
     */
    public UndeployResult undeploy(String name) throws DeployRefusedException, IOException {
        if (!NAME.matcher(name).matches()) throw badName(name);
        if (config.deployed()) throw refused(Kind.FORBIDDEN, "manual undeploy is non-production only (ARCHITECTURE §9)");
        if (!deploying.tryLock()) throw busy();   // closing read where the lock decides, as a deploy reads it
        try {
            if (closing) throw stopping();
            Deployed listed = deployed.get(name);
            if (listed == null) throw refused(Kind.NOT_FOUND, "no package '" + name + "' is deployed");
            String version = listed.pkg().version();
            List<Flow> running = listed.flows().stream().filter(Flow::runs).toList();
            Set<String> leaving = queued(running);
            // the commit point: the record is deleted before anything stops; a delete that fails changes nothing else
            Future<Void> deleted = Future.future(done -> records.delete(name).onComplete(done));
            deleted.otherwiseEmpty().await();
            if (deleted.failed()) {
                String undeleted = "the deploy record of package '" + name + "' was not deleted: " + reason(deleted.cause());
                if (PgTx.refusedByThePool(deleted.cause())) throw refused(Kind.BUSY, undeleted);   // retryable, nothing changed
                throw new IOException(undeleted, deleted.cause());
            }
            try {
                retire(running, leaving, "its package was undeployed; the undeploy goes on");
            } finally {
                deployed.remove(name);   // the record is gone: whatever the stop did, the package is no longer listed
            }
            Path packageDir = config.dataDir().resolve("packages").resolve(name);
            if (Files.isDirectory(packageDir)) {   // a package listed FAILED by a restore on a fresh data directory has none
                Set<String> keep = new HashSet<>(pinned(name));   // what a remaining record pins or a remaining flow reads
                keep.addAll(read(name));
                prune(name, packageDir, keep);
            }
            return new UndeployResult(name, version, listed.flows().stream().map(Flow::id).toList());
        } finally {
            deploying.unlock();
        }
    }

    /**
     * The dry run of a deploy of the package directory {@code dir} as {@code name}: the document checks
     * ({@code Package.problems()} and the package-level ones), each endpoint's config in this node's environment against its
     * component's schema, and the dry build of every flow with this node's steps — against the flows the node runs — then stop
     * and close whatever was built. Nothing is fetched, written, locked, recorded, enqueued or started, no consumer is built, and the
     * listing stays as it was; a dependency is read only from a directory the node has with the content its lock pins. Blocking: on
     * a virtual thread.
     *
     * <p>It reports what the load, the check and the build refuse. It does not reach what only {@link #deploy(String, String, String)}
     * and its install check: the package name's pattern, the non-production-only rule, the fetch and the write of the commit with
     * their limits, the dependencies a deploy would fetch, the consumer of each flow (its component may still refuse it), the start
     * of each flow and the write of its record. An empty list is no promise that a deploy succeeds.
     *
     * @return the problems the load, the check and the build find, each path in it from the package root; empty when they find none
     * @throws IOException a file the build read cannot be hashed, or the work queue could not answer (the node's Postgres pool
     *                     refused the question included): the node's failure
     */
    public List<String> validate(String name, Path dir) throws IOException {
        return validate(name, dir, List.of());
    }

    /**
     * Off-node validation: {@link #validate(String, Path)} of {@code dir} as {@code name} with the package's dependency
     * closure handed over as package folders, {@code held}. For this call only, each held folder counts as a version this node has —
     * its name and version from its {@code package.yaml}, its content {@code Package.content} of the folder — in the resolution
     * ({@link #dependencies}: the missing version, the content, the cycle, the {@code <dep>:<path>} file checks run as for a deployed
     * version) and in the build, which reads a {@code <dep>:<path>} from the folder. In the call checks each held package other
     * than {@code name} stands in for the version of it the node runs ({@link #others}): its flows' endpoints are asked which
     * node-local addresses they serve and call, as a deploy of it would ask them, and what of it does not check is left out
     * silently. Nothing of a held folder is deployed, written, recorded, built, started or listed, in any environment (no
     * {@link Kind#FORBIDDEN}: nothing is deployed), and after the call nothing of it is visible. Blocking: on a virtual thread.
     *
     * @param held the closure's package folders, each a package that loads with a name and a version
     * @return the problems, as {@link #validate(String, Path)} returns them
     * @throws IOException a held folder or a file of it cannot be read, a file the build read cannot be hashed, or the work queue
     *                     could not answer (the node's Postgres pool refused the question included): the node's failure
     * @throws IllegalArgumentException a file of a held folder does not parse ({@code Package.load})
     */
    public List<String> validate(String name, Path dir, List<Path> held) throws IOException {
        List<Held> versions = new ArrayList<>();
        for (Path folder : held) {
            JsonObject manifest = Package.load(folder).manifest();
            versions.add(new Held(manifest.getString("name"), manifest.getString("version"), Package.content(folder), folder));
        }
        Deployed listed = deployed.get(name);
        List<Flow> before = listed == null ? List.of() : listed.flows();   // a package listed FAILED lists no flows (restore)
        List<Flow> built = new ArrayList<>();
        try {
            Package pkg = load(name, dir);
            Checked checked = check(name, pkg, dir, before, versions, null, new ArrayList<>());
            return build(name, Optional.empty(), pkg.manifest().getString("version"), "validate", pkg, dir, checked, built);
        } catch (DeployRefusedException refused) {
            if (refused.kind() == Kind.BUSY) throw new IOException(refused.error(), refused);   // a pool refusal lists no problem: the node's failure
            return refused.problems();
        } finally {
            Future.join(built.stream().map(Deployments::dropBuilt).toList()).await();   // each never fails
        }
    }

    /**
     * Stops at once and closes a flow {@link #validate} built — never started, no consumer — as {@link #flow} stops its own when
     * its build fails: its worker's stop, its delivery's zero-deadline stop, then its close ({@link Flow#close}). Unlike
     * {@link #discard} it schedules nothing on the node's timer: there is no consumer stop to bound. Never fails: a flow that
     * does not stop cleanly is a WARNING.
     */
    private static Future<Void> dropBuilt(Flow f) {
        return Future.<Void>future(done -> {
            if (f.worker() != null) f.worker().stop();
            f.delivery().stop(Duration.ZERO).eventually(f::close).onComplete(done);
        }).onFailure(failed -> LOG.log(System.Logger.Level.WARNING, "flow '" + f.id() + "', built by a validate, did not stop cleanly: " + failed, failed))
                .otherwiseEmpty();
    }

    /**
     * Refuses deploys from now on, waits for a running one, then stops every flow at once and closes their producers (see the
     * class comment) and returns once all have stopped and every delivery's producers closed, and every worker closed — its
     * items settled — or the stop deadline, counted from now, passed ({@link #drain}); the first stop failure, in flow order, is
     * rethrown then. Blocking: on a virtual thread.
     */
    public void close() {
        closing = true;
        deploying.lock();
        try {
            List<Flow> running = deployed.values().stream().flatMap(d -> d.flows().stream()).filter(Flow::runs).toList();
            deployed.clear();
            Future<Void> stopped = stop(running, config.stopDeadline());   // every worker stopped first: no claim from here on
            Future<Void> drained = drain(running, stopped);                 // its bound runs from here, the start of the close
            stopped.eventually(() -> close(running)).eventually(() -> drained).await();
        } finally {
            deploying.unlock();
        }
    }

    /**
     * Installs, at the node's start, every package the pool's records name, one at a time in name order, under the deploy
     * lock — a deploy meanwhile is {@link Kind#CONFLICT} — and returns once each one is installed or listed FAILED. Each record's
     * commit is read from the git cache, and fetched only when the cache lacks it, with no check of reachability: a warm cache
     * restores with git unreachable. Its package folder is written into a folder of {@link #incomingDir()}, its content checked
     * against the record's, and installed in restore mode ({@link #install}): no deployed-environment refusal, no record and no audit
     * row written; repository, commit and {@code deployedAt} are the record's; each pin is read from its own repository and commit
     * the same way and checked against the pin; a flow that does not start is listed {@link DeployedFlow.State#FAILED} and the
     * others run. A package refused whole — a commit that cannot be read (the node has no {@code RAIL8_GIT_BASE}, or git fails), a
     * content other than its record's (a node configured with another base or path), a tree the seam refuses, problems — is listed
     * {@link DeployedPackage.State#FAILED} with its record's version, repository, commit and {@code deployedAt}, its problems and no
     * flows or files, with an ERROR log naming it; the next one is restored all the same — a package that serves an address a
     * package restored before it serves, or whose calls close a cycle with those, among them. Once a fetch fails because the git
     * host could not be reached (the transport failed) or timed out, the restore fetches nothing more: every commit the cache lacks
     * fails at once with that reason, so the admin port is not held back for a timeout per record. Once every record is in, each
     * call of a running flow that no running flow serves is a WARNING. Blocking: on a virtual thread.
     *
     * @throws IOException the data directory cannot be written or a folder written into it read: the node's failure, not a package's
     *                     (the packages restored so far run on until {@link #close})
     * @throws IllegalStateException the node lists a package already (a deploy or a restore installed one) or {@link #close} began:
     *                               a restore runs at the node's start, before anything else
     */
    public void restore() throws IOException {
        deploying.lock();
        try {
            if (closing || !deployed.isEmpty()) throw new IllegalStateException("a restore runs at the node's start, before anything is deployed and before close");
            List<DeploymentRecord> rows = records.list().await();   // rows that cannot be read: the node does not start
            Files.createDirectories(incomingDir());
            restoringRows = rows;
            for (DeploymentRecord row : rows) restore(row);
            warnUnserved((f, a) -> true);   // once every package is in: a callee restored after its caller is no false warning
        } finally {
            restoringRows = List.of();
            unreachable = null;
            deploying.unlock();
        }
    }

    /**
     * The {@code deployments} check of the node's {@code /health}: DEGRADED while a package or a flow is listed FAILED or a
     * flow's consumer waits for its next attempt, UP otherwise. It is never DOWN: what one package cannot run is that
     * package's problem, and the node and its other packages keep running. The detail names each FAILED package and flow,
     * then each RESTARTING flow with its failures in a row and when the next attempt is due, never the cause; the wire
     * shows the status only.
     */
    public Health health() {
        List<String> failed = new ArrayList<>();
        packages().stream().filter(p -> p.state() == DeployedPackage.State.FAILED).forEach(p -> failed.add("package " + p.name()));
        flows().stream().filter(f -> f.state() == DeployedFlow.State.FAILED).forEach(f -> failed.add("flow " + f.id()));
        List<String> waiting = restarting().entrySet().stream()
                .map(e -> "flow " + e.getKey() + " (failure " + e.getValue().failures() + ", next attempt at " + e.getValue().next() + ")").toList();
        List<String> detail = new ArrayList<>();
        if (!failed.isEmpty()) detail.add("FAILED: " + String.join(", ", failed));
        if (!waiting.isEmpty()) detail.add("RESTARTING: " + String.join(", ", waiting));
        return detail.isEmpty() ? Health.up() : Health.degraded(String.join("; ", detail));
    }

    /** One record of {@link #restore()}: installed, or listed FAILED when refused; only an IOException of the data directory throws. */
    private void restore(DeploymentRecord row) throws IOException {
        Path folder = null;
        try {
            GitCache cache = git.orElseThrow(() -> refused(Kind.FAILED, "the node has no RAIL8_GIT_BASE: it reads no commit"));
            try (PackageRepositories repos = cache.open()) {
                Commits commits = (repository, commit) -> restored(cache, repos, repository, commit);
                Commit commit;
                try {
                    commit = commits.read(row.repository(), row.commit());
                } catch (GitFailure failed) {
                    throw refused(Kind.FAILED, failed.getMessage());
                }
                folder = checkout(commit, "");
                String content = Package.content(folder);
                if (!content.equals(row.content())) {   // other files under the record's commit: nothing of them runs
                    throw refused(Kind.FAILED, row.repository() + " at " + row.commit() + " holds the content " + content + ", its record " + row.content()
                            + ": the node reads another RAIL8_GIT_BASE or RAIL8_GIT_PATH than the deploy did");
                }
                install(row.name(), Optional.of(new Origin(row.repository(), row.commit())), folder, hex(content), commits, repos, row, false);
            }
        } catch (DeployRefusedException refused) {
            List<String> problems = Stream.concat(Stream.of(refused.error()), refused.problems().stream()).toList();
            LOG.log(System.Logger.Level.ERROR, "package '" + row.name() + "' (" + row.repository() + " at " + row.commit() + ") could not be restored; it is listed "
                    + "FAILED until a deploy of it: " + problems);
            deployed.put(row.name(), new Deployed(new DeployedPackage(row.name(), row.version(), Optional.of(row.repository()), Optional.of(row.commit()), List.of(),
                    row.deployedAt(), List.of(), List.of(), List.of(), DeployedPackage.State.FAILED, problems, null, List.of()), List.of(), Map.of(), null,
                    row.dependencies()));
        } finally {
            Path left = folder;   // moved into packages/ by now, unless the package was refused or a kept directory served
            if (left != null) quietly("the folder " + left.getFileName() + " of .incoming could not be deleted", () -> deleteTree(left));
        }
    }

    /**
     * A commit a restore reads: from the cache, fetched through {@code repos} when the cache lacks it — unless a fetch of this
     * restore could not reach the git host already ({@link #unreachable}): then one the cache lacks fails at once with that reason.
     * A fetch that fails because the transport failed or timed out sets it.
     */
    private Commit restored(GitCache cache, PackageRepositories repos, String repository, String commit) throws GitFailure {
        if (unreachable != null) {
            try (PackageRepositories cached = cache.cached(unreachable)) {
                return cached.recorded(repository, commit);
            }
        }
        try {
            return repos.recorded(repository, commit);
        } catch (GitFailure failed) {
            if (failed.reason() == GitFailure.Reason.TRANSPORT_FAILED || failed.reason() == GitFailure.Reason.TIMED_OUT) unreachable = failed.reason();
            throw failed;
        }
    }

    // --- one deploy, under the lock

    /**
     * Moves the folder in place (or reuses a kept directory that holds what was written into it), resolves the dependencies,
     * checks, builds, classifies, swaps, saves the record, then replaces the package's listing and prunes — or, for a check, stops
     * what it built and deletes what it wrote once it classified — or, when phase B does not start every flow of a
     * redeploy or its record is not written, rolls back; whatever refuses it
     * stops what it built and did not close yet and deletes what it wrote, unchecked failures included. It writes {@code deployed}
     * once: the new version as it returns, or — thrown once phase A stopped flows — the version before with each of those listed
     * FAILED unless the rollback runs it again ({@link #afterFailure}); {@code deployed.get(name)} is {@code running} until then.
     * A package listed {@link DeployedPackage.State#FAILED} runs nothing: a deploy over it is a first deploy.
     *
     * <p>{@code folder} holds the package's files in {@code .incoming}: the install moves it under {@code packages/<name>/<hex>/},
     * {@code hex} the hex of its content digest, or leaves it to the caller to delete. {@code origin} is the repository and commit the
     * folder was written from, named by the record and the listing; empty for a deploy with no origin, which writes no record.
     * {@code commits} reads the commits the dependencies' pins name: a deploy's checks reachability, a restore's reads the cache
     * first; null for a deploy with no origin, which fetches nothing. {@code fetching} is the git session {@code commits} reads
     * through, closed once the pins are read so its deadline bounds the fetches only; null when nothing is fetched. {@code restoring} is null for a deploy; for a restore
     * ({@link #restore}) it is the package's record, and the install runs in restore mode: no record is written,
     * {@code deployedAt} is the record's, and a flow that does not start is stopped, closed and listed
     * {@link DeployedFlow.State#FAILED} while the others run — the package is installed all the same.
     */
    private DeployResult install(String name, Optional<Origin> origin, Path folder, String hex, Commits commits, PackageRepositories fetching,
                                 DeploymentRecord restoring, boolean check) throws DeployRefusedException, IOException {
        Deployed listed = deployed.get(name);
        Deployed running = listed == null || listed.pkg().state() == DeployedPackage.State.FAILED ? null : listed;   // a FAILED package runs nothing
        List<Flow> before = running == null ? List.of() : running.flows();
        List<DeployedPackage.PackageFile> writtenInto = running == null ? null : running.kept().get(hex);   // null: no kept directory
        Path packageDir = config.dataDir().resolve("packages").resolve(name);
        Path dir = packageDir.resolve(hex);
        Files.createDirectories(packageDir);
        List<Path> written = new ArrayList<>();   // what this deploy wrote: a refusal deletes it
        List<Flow> built = new ArrayList<>();     // every flow built and neither run nor closed yet: a refusal stops and closes them
        List<Flow> stopped = List.of();           // the running flows phase A stopped: a refusal lists each FAILED unless it runs again
        List<Flow> again = new ArrayList<>();     // of those, the ones the rollback runs again, once its whole restart settled
        Deployed next;
        DeployResult result;
        try {
            if (writtenInto == null || !holds(dir, writtenInto)) {   // a kept directory is reused only as it was written
                // a kept directory written afresh stays whatever happens: it runs or ran; so does a pinned one, a record pins it,
                // and one a listed flow reads as a dependency
                if (writtenInto == null && !pinned(name).contains(hex) && !read(name).contains(hex)) written.add(dir);
                deleteTree(dir);   // what an earlier node left there, or a kept directory's changed files, is replaced, never merged
                Files.move(folder, dir, StandardCopyOption.ATOMIC_MOVE);
            }
            Package pkg = load(name, dir);
            Checked checked = check(name, pkg, dir, before, List.of(), commits, written);
            if (fetching != null) fetching.close();   // every pin is read: the fetches' deadline ends before a flow starts
            String version = pkg.manifest().getString("version");
            Optional<String> commit = origin.map(Origin::commit);
            List<String> problems = build(name, commit, version, hex, pkg, dir, checked, built);

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
            Instant deployedAt = restoring == null ? clock.instant() : restoring.deployedAt();
            DeployedPackage deployedPackage = new DeployedPackage(name, version, origin.map(Origin::repository), commit, now.stream().map(Flow::id).toList(),
                    deployedAt, files(dir), pkg.folders(), pkg.artifacts(), DeployedPackage.State.RUNNING, List.of(), Package.content(dir),
                    checked.dependencies().listed());
            List<DeploymentRecord.Pin> pins = checked.dependencies().pins();
            if (check) {   // a check ends here: what it built stops and closes, what it wrote goes, and nothing that runs changed
                Throwable unstopped = discard(List.of(), built);
                if (unstopped != null) LOG.log(System.Logger.Level.WARNING, "package '" + name + "': a flow its check built did not stop cleanly: " + unstopped, unstopped);
                built.clear();
                for (Path p : written) quietly("package '" + name + "': the directory " + p.getFileName() + " its check wrote could not be deleted", () -> deleteWritten(p));
                quietly("package '" + name + "': its directory could not be deleted after its check", () -> deleteIfEmpty(packageDir));
                return new DeployResult(deployedPackage, added, swapped, List.copyOf(gone.keySet()));
            }
            retire(retiring, checked.leaving(), "the redeploy replaced or removed it; the redeploy goes on");   // phase A, settled when it returns; it never throws
            stopped = retiring;
            built.clear();
            built.addAll(starting);   // phase B calls every start: from here on a refusal stops their consumers too
            List<Start> started = start(starting, restoring != null);   // a restore tries a consumer that did not start again
            List<Start> failed = started.stream().filter(s -> s.failure() != null).toList();
            if (!failed.isEmpty()) {
                if (restoring == null && running == null) throw refused(Kind.FAILED, problem(failed.getFirst(), "did not start"));   // a first deploy: the cleanup stops them all
                if (restoring == null) throw rollBack(Kind.FAILED, version, "did not start", List.of(), running, retiring, started, packageDir, built, again);
                unstarted(name, failed, now);   // a restore: those are listed FAILED, the others run
            }
            if (restoring == null && origin.isPresent()) {   // a restore installs the record it read; a deploy with no origin has none to write
                // the commit point: the package's record, written once every flow started; the listing and the pruning follow it
                DeploymentRecord record = new DeploymentRecord(name, version, origin.get().repository(), origin.get().commit(), deployedPackage.content(),
                        deployedAt, pins);
                Future<Void> saved = Future.future(done -> records.save(record).onComplete(done));
                saved.otherwiseEmpty().await();
                if (saved.failed()) {
                    String unrecorded = "the deploy record was not written: " + reason(saved.cause());
                    Kind unwritten = PgTx.refusedByThePool(saved.cause()) ? Kind.BUSY : Kind.FAILED;   // a pool refusal is retryable
                    if (running == null) throw refused(unwritten, unrecorded);   // a first deploy: the cleanup stops the flows that started
                    throw rollBack(unwritten, version, "was not recorded", List.of(unrecorded), running, retiring, started, packageDir, built, again);
                }
            }
            String previous = null;   // the directory of the last other content the package ran: none on a first deploy
            if (running != null) {
                String current = hex(running.pkg().content());
                previous = hex.equals(current) ? running.previous() : current;
            }
            Map<String, List<DeployedPackage.PackageFile>> kept = new HashMap<>();
            kept.put(hex, deployedPackage.files());
            if (previous != null) kept.put(previous, running.kept().get(previous));
            now.forEach(f -> kept.computeIfAbsent(f.dir(), d -> running.kept().get(d)));   // in use: an old flow's was kept with it
            next = new Deployed(deployedPackage, List.copyOf(now), Map.copyOf(kept), previous, pins);
            result = new DeployResult(deployedPackage, added, swapped, List.copyOf(gone.keySet()));
        } catch (Throwable failure) {
            if (!stopped.isEmpty()) deployed.put(name, afterFailure(running, stopped, again));   // the one write of a refused deploy
            cleanUp(failure, () -> {
                Throwable unstopped = discard(List.of(), built);
                if (unstopped != null) failure.addSuppressed(unstopped);
            });
            for (Path p : written) cleanUp(failure, () -> deleteWritten(p));
            cleanUp(failure, () -> deleteIfEmpty(packageDir));   // packages/<name>/ goes with a refused first deploy; an earlier node's leftovers stay
            throw failure;
        }
        // the new flows run: from here on nothing undoes the deploy
        deployed.put(name, next);
        if (restoring == null) {   // its own calls no flow serves, and others' calls to an address the replaced version served
            Set<Calls.Address> left = new HashSet<>();
            before.stream().filter(Flow::runs).forEach(f -> f.recipe().calls().serves().ifPresent(left::add));
            warnUnserved((f, a) -> f.flow().pkg().equals(name) || left.contains(a));
        }
        Set<String> keep = new HashSet<>(next.kept().keySet());
        keep.addAll(pinned(name));
        keep.addAll(read(name));
        prune(name, packageDir, keep);
        return result;
    }

    /**
     * The flows of a restore that did not start ({@code failed}: a worker's start threw; a consumer that did not start is tried
     * again instead): each is stopped at once and closed ({@link #discard}) and replaced in {@code now} by itself
     * {@link DeployedFlow.State#FAILED} ({@link Flow#failed}: nothing of it runs), with an ERROR log naming it.
     */
    private void unstarted(String name, List<Start> failed, List<Flow> now) {
        Set<String> ids = new HashSet<>();
        for (Start s : failed) {
            ids.add(s.flow().id());
            LOG.log(System.Logger.Level.ERROR, "package '" + name + "' is restored, but " + problem(s, "did not start") + "; it is listed FAILED until a "
                    + "deploy of the package starts it");
        }
        discard(List.of(), failed.stream().map(Start::flow).toList());   // never throws; a flow that does not stop cleanly is a WARNING
        now.replaceAll(f -> ids.contains(f.id()) ? f.failed() : f);
    }

    /**
     * Rolls back a redeploy whose phase B did not start every flow, or whose record was not written once it did,
     * and returns the refusal to throw; it writes no listing and no record:
     * {@code deployed} lists {@code running} throughout, and {@link #install} writes what the refusal leaves. First every flow of
     * the new version ({@code phaseB}) stops — one that started within the node's stop deadline, the others at once — and closes
     * (its delivery's producers awaited; a consumer whose start was still pending at the deadline stops only once that
     * start completes); only then is each flow phase A stopped ({@code retired}: changed or removed) built again, from the
     * directory it was built from and with its stamps, and started through phase B ({@link #start}), a consumer that does not start
     * again tried again. One that does not build again, or whose worker does not start again, is stopped and closed in turn. Once
     * the whole restart settled, the ones that started again (a consumer tried again included) are added to
     * {@code again} — the one object of each, so a later stop stops each consumer once — and {@link #afterFailure} lists every
     * other retired flow {@link DeployedFlow.State#FAILED}. An unchecked failure that breaks the rollback off (an Error a component
     * or a step factory throws) leaves {@code again} empty — every retired flow FAILED — and what {@code built} holds to the
     * cleanup of {@link #install}, which stops it; it is a problem line and added as suppressed. The refusal is of {@code kind}:
     * "version {@code version} &lt;why&gt;; &lt;running version&gt; runs again", with "; flows [..] FAILED" naming every flow
     * the package lists FAILED after it (a retired one {@link #afterFailure} lists FAILED, or one FAILED before the deploy), the
     * {@code causes} first among its problems, then a problem line per flow that did not start, build or start again, and each
     * stop failure added as suppressed. {@code built}
     * holds what a throw would leave to stop wherever one can happen: discard never throws, so a list it stopped is cleared before
     * anything that may throw runs.
     */
    private DeployRefusedException rollBack(Kind kind, String version, String why, List<String> causes, Deployed running, List<Flow> retired, List<Start> phaseB,
                                            Path packageDir, List<Flow> built, List<Flow> again) {
        List<String> problems = new ArrayList<>(causes);
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
            // start was still pending at the deadline: that one stops only once its start completes, maybe after
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
            List<Start> restarted = start(starting, true);   // a consumer that does not start again is tried again
            built.clear();
            List<Flow> back = new ArrayList<>();   // the retired flows that started again
            List<Flow> failedAgain = new ArrayList<>();
            for (Start s : restarted) {
                built.add(s.flow());
                if (s.failure() == null) {
                    back.add(s.flow());
                    if (s.retried() != null) problems.add("flow '" + s.flow().id() + "' did not start again: " + reason(s.retried()) + "; its consumer is tried again");
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
        DeployRefusedException refusal = new DeployRefusedException(kind, "version " + version + " " + why + "; " + running.pkg().version() + " runs again"
                + (failed.isEmpty() ? "" : "; flows " + failed + " FAILED"), problems);
        unstopped.stream().filter(Objects::nonNull).forEach(refusal::addSuppressed);
        return refusal;
    }

    /**
     * The flow {@code ran} built again — delivery, worker, effective hash; no consumer yet — from the directory it was built from,
     * as its build took it ({@link Recipe}) and with its stamps; null when it does not build, one problem line added, its paths
     * from the package root and its dependencies' ({@link #fromRoot}).
     */
    private Flow rebuild(Flow ran, Path packageDir, List<String> problems) {
        DeployedFlow f = ran.flow();
        Path dir = packageDir.resolve(ran.dir());
        try {
            return flow(builder(f.pkg(), f.packageVersion(), f.commit(), ran.recipe().endpoints()), f.pkg(), f.packageVersion(), f.commit(), ran.dir(),
                    ran.recipe(), dir);
        } catch (IOException | RuntimeException refused) {
            problems.add("flow '" + local(f) + "' does not build again: " + fromRoot(reason(refused), dir, ran.recipe().dependencies()));
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
        return new Deployed(d.pkg(), d.flows().stream().map(f -> byId.getOrDefault(f.id(), f)).toList(), d.kept(), d.previous(), d.pins());
    }

    /**
     * A copy of the package folder {@code folder} in a fresh folder of {@code .incoming}, walked without following links: its
     * directories and regular files; anything else (a symbolic link, a device) is {@link Kind#BAD_REQUEST}. The copy is the
     * caller's to move or delete. Whatever stops the copy deletes what it wrote.
     */
    private Path copied(Path folder) throws DeployRefusedException, IOException {
        Path from = folder.toAbsolutePath().normalize();
        return written(Files.createTempDirectory(Files.createDirectories(incomingDir()), "copy-"), into -> {
            List<Path> paths;
            try (Stream<Path> walk = Files.walk(from)) {
                paths = walk.sorted().toList();
            }
            for (Path p : paths) {
                Path target = into.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(p, target, LinkOption.NOFOLLOW_LINKS);
                } else {
                    throw refused(Kind.BAD_REQUEST, "the package folder is refused: '" + from.relativize(p).toString().replace(File.separatorChar, '/')
                            + "' is neither a regular file nor a directory");
                }
            }
        });
    }

    /** What one writing step writes into an empty folder. */
    @FunctionalInterface
    private interface Writing {
        void into(Path dir) throws DeployRefusedException, IOException;
    }

    /** {@code dir} once {@code writing} filled it; a writing that throws deletes {@code dir} first. */
    private static Path written(Path dir, Writing writing) throws DeployRefusedException, IOException {
        try {
            writing.into(dir);
            return dir;
        } catch (Throwable failure) {
            cleanUp(failure, () -> deleteTree(dir));
            throw failure;
        }
    }

    /** The package as written; a file that does not parse stops the load, and the 422 names it (package-relative). */
    private static Package load(String name, Path dir) throws DeployRefusedException, IOException {
        try {
            return Package.load(dir);
        } catch (IllegalArgumentException unreadable) {
            throw new DeployRefusedException(Kind.INVALID, "package '" + name + "' cannot be read", List.of(unreadable.getMessage()));
        }
    }

    /**
     * What is wrong with {@code cfg}, an endpoint's config as written, for an endpoint of {@code direction} ({@code in} or
     * {@code out}) served by {@code component}: each {@code '<pointer>': <message>}. A component that describes its settings
     * is judged by that description only; one that describes nothing by its {@code configSchema()}.
     */
    private static List<String> refusedBy(Component component, JsonObject cfg, String direction) {
        Settings settings = component.settings();
        List<String> refused = new ArrayList<>();
        if (settings.described()) {
            for (Settings.Problem p : settings.check(cfg.getMap(), "in".equals(direction) ? Direction.IN : Direction.OUT)) {
                refused.add("'" + p.path() + "': " + p.message());
            }
        } else {
            for (Schemas.Problem p : Schemas.check(component.configSchema(), cfg).problems()) refused.add("'" + p.path() + "': " + p.message());
        }
        return refused;
    }

    /**
     * Every check that needs no component built; all problems at once, as one {@link Kind#INVALID}. Each endpoint's
     * {@code secret://} references are resolved after its schema check, which sees them as written ({@link #resolved}). Of the flows {@code running}
     * (the package's, empty on a first deploy), every at-least-once one the new version does not run at-least-once — removed, or
     * another delivery mode — is asked whether the work queue holds items of it (blocking): if so, those items would never run.
     * One the new version names with a yaml that does not read is not asked: its problems are listed already. A question the queue
     * fails is the node's {@link IOException} (its cause the queue's failure, the last such flow's) once every one was asked; only
     * when every question that failed was one the node's Postgres pool refused a connection ({@link PgTx#refusedByThePool}) is it
     * {@link Kind#BUSY} with the last such line instead (a real failure outranks a refusal in any flow order) —
     * unless there are problems: then they are the answer. Against the flows the node runs, each flow's consumer is asked where it
     * listens ({@link #listeners}), and each flow which node-local addresses it serves and calls ({@link #links}): one flow per address
     * and no cycle of calls ({@link Calls#check}, against {@link #others}). {@code held} holds the versions an off-node validate
     * holds, empty otherwise; {@code commits} and {@code written} are as {@link #dependencies} takes them.
     */
    private Checked check(String name, Package pkg, Path dir, List<Flow> running, List<Held> held, Commits commits, List<Path> written)
            throws DeployRefusedException, IOException {
        List<String> problems = new ArrayList<>(pkg.problems());
        JsonObject manifest = pkg.manifest();
        if (!name.equals(manifest.getValue("name"))) problems.add("package.yaml names the package '" + manifest.getValue("name") + "', the deploy '" + name + "'");
        Resolved dependencies = dependencies(name, pkg, dir, held, commits, written, problems);
        Map<String, String> schemes = new LinkedHashMap<>();
        Map<String, JsonObject> configs = new LinkedHashMap<>();
        Map<String, JsonObject> resolved = new LinkedHashMap<>();   // as the components get them: every secret:// reference resolved
        Set<String> outs = new HashSet<>();
        Set<String> ins = new HashSet<>();   // the in endpoints whose component takes their config: the ones asked where they listen
        Map<String, EndpointConfig> callable = new HashMap<>();   // the out endpoints whose component takes their config, references resolved
        pkg.endpoints().forEach((id, endpoint) -> {
            if (!Schemas.ENDPOINT.validate(endpoint).valid()) return;   // what its schema says is listed already; nothing here relies on its shape
            String scheme = endpoint.getString("scheme");
            JsonObject cfg = overlaid(endpoint);
            schemes.put(id, scheme);
            configs.put(id, cfg);
            Component component = components.get(scheme);
            boolean valid = false;
            if (component == null) {
                problems.add("endpoint '" + id + "': no component on this node serves the scheme '" + scheme + "'");
            } else {
                List<String> refused = refusedBy(component, cfg, endpoint.getString("direction"));
                for (String p : refused) problems.add("endpoint '" + id + "' config (environment " + config.env() + ") at " + p);
                valid = refused.isEmpty();
            }
            JsonObject values = resolved(id, cfg, problems);   // null: a reference did not resolve, its problem is listed
            if (values != null) resolved.put(id, values);
            if ("in".equals(endpoint.getString("direction"))) {
                for (String key : RESERVED) {
                    if (cfg.containsKey(key)) problems.add("endpoint '" + id + "' sets '" + key + "': the deploy sets it on every in endpoint (the flow's qualified id, its maxBodyBytes)");
                }
                if (valid && values != null) ins.add(id);
            } else {
                outs.add(id);   // the schema allows in and out only
                if (valid && values != null) callable.put(id, endpoint(id, scheme, values));
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
            problems.addAll(optionProblems(id, def.steps()));
            feeds.computeIfAbsent(def.fromEndpoint(), endpoint -> new ArrayList<>()).add(id);
            if (outs.contains(def.fromEndpoint())) {
                problems.add("flow '" + id + "' consumes from endpoint '" + def.fromEndpoint() + "', whose direction is out: a flow's from is an in endpoint");
            }
            String queued = flow.getJsonObject("delivery").getString("queue");   // absent or postgres: the node's work queue
            if (queued != null && !AT_LEAST_ONCE.equals(def.deliveryMode())) {
                problems.add("flow '" + id + "' is " + def.deliveryMode() + " and sets delivery.queue " + queued + ": only an at-least-once flow has a work queue");
            } else if ("kafka".equals(queued)) {
                problems.add("flow '" + id + "' sets delivery.queue kafka: Kafka is an optional add-on, not in this build; omit it or use postgres");
            }
            long durableMax = config.blobs().durableMax();
            if (AT_LEAST_ONCE.equals(def.deliveryMode()) && def.maxBodyBytes() > durableMax) {
                problems.add("flow '" + id + "' is at-least-once with limits.maxBodyBytes " + def.maxBodyBytes() + " above RAIL8_BLOB_DURABLE_MAX " + durableMax + " (D-058)");
            }
        });
        Map<String, EndpointConfig> asked = new TreeMap<>();   // by the local id of the flow each feeds: a pair's line goes on the later one
        feeds.forEach((endpoint, fed) -> {
            if (fed.size() > 1) problems.add("endpoint '" + endpoint + "' is the from endpoint of the flows " + fed + ": an in endpoint feeds one flow");
            if (ins.contains(endpoint)) asked.put(fed.getFirst(), endpoint(endpoint, schemes.get(endpoint), resolved.get(endpoint)));
        });
        Map<String, InetSocketAddress> listening = listeners(name, asked, problems);
        Map<String, String> paths = paths(name, asked, configs, problems);
        Map<String, Calls.Links> calls = links(flows, asked, callable, problems);
        List<Calls.Node> fresh = new ArrayList<>();
        calls.forEach((id, links) -> fresh.add(new Calls.Node(id, flows.get(id).fromEndpoint(), links)));
        Calls.check(fresh, others(name, held), problems);
        Set<String> stayQueued = new HashSet<>();   // the qualified ids the new version runs at-least-once
        flows.values().forEach(def -> {
            if (AT_LEAST_ONCE.equals(def.deliveryMode())) stayQueued.add(def.id());
        });
        Set<String> unread = new HashSet<>(pkg.flows().keySet());   // the flows the new version names whose yaml did not read: their problems say it all
        unread.removeAll(flows.keySet());
        Set<String> leaving = new HashSet<>();
        IOException unanswered = null;   // a question the queue failed for real: the node's failure, unless the package is refused anyway
        String pooled = null;   // a question the node's Postgres pool refused: BUSY, unless a real failure or a problem outranks it
        for (Flow f : running) {
            if (!AT_LEAST_ONCE.equals(f.flow().delivery()) || stayQueued.contains(f.id()) || unread.contains(local(f.flow()))) continue;
            leaving.add(f.id());
            Future<Boolean> items = queue.hasItems(f.id());
            if (Boolean.TRUE.equals(items.otherwiseEmpty().await())) {
                problems.add("flow '" + local(f.flow()) + "' runs at-least-once and the work queue holds items of it (queued, leased, held or stopped): this version "
                        + "does not run it at-least-once, so they would never run; deploy it once they are done");
            } else if (items.failed()) {
                String line = "the work queue could not say whether it holds items of flow '" + f.id() + "': " + reason(items.cause());
                if (PgTx.refusedByThePool(items.cause())) pooled = line;
                else unanswered = new IOException(line, items.cause());
            }
        }
        if (!problems.isEmpty()) throw invalid(name, problems);
        if (unanswered != null) throw unanswered;
        if (pooled != null) throw refused(Kind.BUSY, pooled);   // every failed question was the pool's: retryable, nothing changed
        return new Checked(schemes, configs, resolved, flows, Set.copyOf(leaving), Map.copyOf(listening), Map.copyOf(paths), Map.copyOf(calls),
                dependencies);
    }

    /**
     * The package's dependencies resolved, every problem one line added to {@code problems} (a line {@code pkg}'s own problems hold
     * already is not added again). Each entry of its {@code package.lock} is resolved to a directory that holds the content the entry
     * pins ({@link #located}), and so is each entry of that version's own lock, to the closure, which holds at most
     * {@value #MAX_CLOSURE} packages; a name met again on the way is a cycle ({@code dependency cycle: a -> b -> a}). Then each
     * step's {@code stylesheet} or {@code schema} naming a resolved direct dependency, {@code <dep>:<path>}, must be a file of it.
     * {@code held} are the versions an off-node validate holds; {@code commits} reads the commits the pins name, null when nothing is
     * fetched; every directory this resolution writes that the node did not keep goes on {@code written}.
     *
     * @throws IOException a directory of the node cannot be read or written
     */
    private Resolved dependencies(String name, Package pkg, Path dir, List<Held> held, Commits commits, List<Path> written, List<String> problems)
            throws IOException {
        Set<String> lines = new LinkedHashSet<>();
        Map<DeploymentRecord.Pin, Path> closure = new LinkedHashMap<>();   // each pin to its directory, in the order resolved
        Set<DeploymentRecord.Pin> direct = new HashSet<>();
        Map<String, Path> dirs = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        List<String> path = new ArrayList<>(List.of(name));
        resolve(pkg.lock(), path, held, commits, written, closure, direct, lines);
        for (DeploymentRecord.Pin p : direct) {
            dirs.put(p.name(), closure.get(p));
            versions.put(p.name(), p.version());
        }
        pkg.flows().forEach((id, flow) -> {
            if (!Schemas.FLOW.validate(flow).valid()) return;   // its schema problems are listed
            for (Package.WrittenStep step : Package.writtenSteps(flow)) {
                JsonObject entry = step.entry();
                for (String key : List.of("stylesheet", "schema")) {
                    if (!(entry.getValue(key) instanceof String reference)) continue;
                    Optional<String> dep = Package.dependencyOf(reference);
                    Path root = dep.map(dirs::get).orElse(null);   // null: a path from the package root, or a dependency not resolved
                    if (root != null && !Files.isRegularFile(root.resolve(reference.substring(dep.get().length() + 1)).normalize())) {
                        lines.add(dir.relativize(pkg.flowFile(id)).toString().replace(File.separatorChar, '/') + ": flow '" + id + "' "
                                + step.label() + " " + key + " '" + reference + "' does not exist in " + dep.get() + "@" + versions.get(dep.get()));
                    }
                }
            }
        });
        lines.removeAll(problems);   // a package depending on itself: its own problems name the cycle
        problems.addAll(lines);
        List<DeployedPackage.Dependency> listed = new ArrayList<>();
        closure.keySet().forEach(p -> listed.add(new DeployedPackage.Dependency(p.name(), p.version(), p.content(), p.repository(), p.commit(), direct.contains(p))));
        return new Resolved(new Visible(Map.copyOf(dirs), Map.copyOf(versions)), List.copyOf(closure.keySet()), List.copyOf(listed));
    }

    /**
     * Resolves each of {@code entries} — the lock of the last package on {@code path} — to its directory ({@link #located}), adds its
     * pin to {@code closure} ({@code direct} when {@code path} holds the deployed package alone), then follows that version's own lock
     * with its name on {@code path}; a name {@code path} holds already is a cycle, not followed, and a closure that would pass
     * {@value #MAX_CLOSURE} packages stops. Every problem is a line of {@code lines}.
     */
    private void resolve(List<Package.Locked> entries, List<String> path, List<Held> held, Commits commits, List<Path> written,
                         Map<DeploymentRecord.Pin, Path> closure, Set<DeploymentRecord.Pin> direct, Set<String> lines) throws IOException {
        for (Package.Locked entry : entries) {
            if (path.contains(entry.name())) {
                lines.add("dependency cycle: " + String.join(" -> ", path) + " -> " + entry.name());
                continue;
            }
            DeploymentRecord.Pin pin = new DeploymentRecord.Pin(entry.name(), entry.version(), entry.content(), entry.repository(), entry.commit());
            if (!closure.containsKey(pin) && closure.size() >= MAX_CLOSURE) {
                lines.add("the dependency closure holds more than " + MAX_CLOSURE + " packages");
                return;
            }
            Path at = located(pin, held, commits, written, lines);
            if (at == null) continue;
            Package dependency;
            try {
                dependency = Package.load(at);
            } catch (IllegalArgumentException unreadable) {
                lines.add("dependency " + pin.name() + "@" + pin.version() + " cannot be read: " + unreadable.getMessage());
                continue;
            }
            closure.put(pin, at);
            if (path.size() == 1) direct.add(pin);
            path.add(entry.name());
            resolve(dependency.lock(), path, held, commits, written, closure, direct, lines);
            path.removeLast();
        }
    }

    /**
     * The directory the version {@code pin} names is read from, or null with a line added to {@code lines}: a folder {@code held}
     * with its name, version and content; else {@code packages/<name>/<content hex>/} when it holds that content (a directory the
     * node keeps is used as it is); else, when {@code commits} is given, the pin's commit read through it and written into
     * {@code .incoming}, its manifest's name and version and its content checked against the pin, then moved there — on
     * {@code written} unless the node keeps that directory. Without {@code commits} nothing is fetched.
     */
    private Path located(DeploymentRecord.Pin pin, List<Held> held, Commits commits, List<Path> written, Set<String> lines) throws IOException {
        for (Held h : held) {
            if (h.name().equals(pin.name()) && h.version().equals(pin.version()) && h.content().equals(pin.content())) return h.dir();
        }
        Path dir = pinDir(pin);
        if (holds(dir, pin.content())) return dir;
        String dependency = "dependency " + pin.name() + "@" + pin.version();
        if (commits == null) {
            lines.add(unheld(pin, held));
            return null;
        }
        Path folder = null;
        try {
            folder = checkout(commits.read(pin.repository(), pin.commit()), dependency + ": ");
            String at = pin.repository() + " at " + pin.commit();
            JsonObject manifest = Package.load(folder).manifest();
            if (!pin.name().equals(manifest.getValue("name")) || !pin.version().equals(manifest.getValue("version"))) {
                lines.add(dependency + ": " + at + " holds " + manifest.getValue("name") + "@" + manifest.getValue("version"));
                return null;
            }
            String content = Package.content(folder);
            if (!content.equals(pin.content())) {
                lines.add(dependency + ": " + at + " has content " + content + ", package.lock pins " + pin.content());
                return null;
            }
            if (!keeps(pin.name(), hex(pin.content()))) written.add(dir);
            deleteTree(dir);   // what an earlier node left there, or a kept directory's changed files, is replaced, never merged
            Files.createDirectories(dir.getParent());
            Files.move(folder, dir, StandardCopyOption.ATOMIC_MOVE);
            return dir;
        } catch (GitFailure failed) {
            lines.add(dependency + ": " + failed.getMessage());
            return null;
        } catch (DeployRefusedException refused) {
            lines.add(refused.error());
            return null;
        } catch (IllegalArgumentException unreadable) {
            lines.add(dependency + " cannot be read: " + unreadable.getMessage());
            return null;
        } finally {
            Path left = folder;   // moved by now, unless it was refused
            if (left != null) deleteTree(left);
        }
    }

    /**
     * The line of a dependency that nothing on the node holds with the content its pin names, for a resolution that fetches
     * nothing: the contents the node and {@code held} have of its version, or the versions they have of it. A package listed FAILED
     * has none: its directory may not be on disk, nor what its record pins.
     */
    private String unheld(DeploymentRecord.Pin pin, List<Held> held) {
        Map<String, Set<String>> contents = new TreeMap<>();   // each version of the dependency to its contents
        held.stream().filter(h -> h.name().equals(pin.name())).forEach(h -> contents.computeIfAbsent(h.version(), v -> new TreeSet<>()).add(h.content()));
        for (Deployed d : deployed.values()) {
            if (d.pkg().state() == DeployedPackage.State.FAILED) continue;
            if (d.pkg().name().equals(pin.name())) contents.computeIfAbsent(d.pkg().version(), v -> new TreeSet<>()).add(d.pkg().content());
            d.pins().stream().filter(p -> p.name().equals(pin.name())).forEach(p -> contents.computeIfAbsent(p.version(), v -> new TreeSet<>()).add(p.content()));
        }
        String dependency = "dependency " + pin.name() + "@" + pin.version();
        Set<String> found = contents.get(pin.version());
        if (found != null) return dependency + " has content " + String.join(", ", found) + " on this node, package.lock pins " + pin.content();
        return dependency + " is not deployed on this node (" + (contents.isEmpty() ? "no package " + pin.name() : "it has " + pin.name() + " "
                + String.join(", ", contents.keySet())) + ")";
    }

    /** The content hexes of {@code name} that a record pins — the listing's, FAILED packages included, and a restore's rows'. */
    private Set<String> pinned(String name) {
        Set<String> pinned = new HashSet<>();
        Stream.concat(deployed.values().stream().flatMap(d -> d.pins().stream()), restoringRows.stream().flatMap(r -> r.dependencies().stream()))
                .filter(p -> p.name().equals(name)).forEach(p -> pinned.add(hex(p.content())));
        return pinned;
    }

    /**
     * The content hexes of {@code name} whose directories a listed flow's recipe reads as a dependency: a flow kept by
     * hash across a lock bump reads the directory it was built from, and a rollback builds it from there again.
     */
    private Set<String> read(String name) {
        Set<String> read = new HashSet<>();
        for (Deployed d : deployed.values()) {
            for (Flow f : d.flows()) {
                Path dir = f.recipe().dependencies().dirs().get(name);
                if (dir != null) read.add(dir.getFileName().toString());
            }
        }
        return read;
    }

    /** Whether the node keeps {@code packages/<name>/<hex>/}: the package's own (current, previous, in use), pinned or read. */
    private boolean keeps(String name, String hex) {
        Deployed listed = deployed.get(name);
        return listed != null && listed.kept().containsKey(hex) || pinned(name).contains(hex) || read(name).contains(hex);
    }

    /**
     * The repositories a record names, its own or a pin's — the listing's, FAILED packages included, and a restore's rows' — which
     * the git cache keeps when it is full.
     */
    private Set<String> named() {
        Set<String> named = new HashSet<>();
        for (Deployed d : deployed.values()) {
            d.pkg().repository().ifPresent(named::add);
            d.pins().forEach(p -> named.add(p.repository()));
        }
        for (DeploymentRecord r : restoringRows) {
            named.add(r.repository());
            r.dependencies().forEach(p -> named.add(p.repository()));
        }
        return named;
    }

    /** {@code packages/<name>/<content hex>/} of a pin: where its version is written. */
    private Path pinDir(DeploymentRecord.Pin pin) {
        return config.dataDir().resolve("packages").resolve(pin.name()).resolve(hex(pin.content()));
    }

    /**
     * One listener per host:port on a node. Asks the component of each of {@code asked} — an in endpoint whose config its
     * schema takes, this node's environment laid over it and every {@code secret://} reference resolved (one that does
     * not resolve is not asked), by the local id of the flow it feeds — where its consumer would listen,
     * and adds a line to {@code problems} for every address that overlaps ({@link Listeners#overlap}) the address of a flow before
     * it in id order (once per pair, on the later flow), the node's admin API ({@code RAIL8_ADMIN_HOST/PORT}), its HTTP port
     * ({@code RAIL8_HTTP_HOST/PORT}), its peer link ({@code RAIL8_PEER_BIND/PORT}: Vert.x lets a second server of the node join a port the node already listens on, and then
     * hands its connections to either server, so the port must be refused here), or a running
     * flow of another package, as that flow's check answered it. A FAILED flow holds no port, nor does a FAILED package (it
     * lists no flows), nor the version of {@code name} this deploy replaces. A component that throws is one line naming the
     * endpoint and its scheme. Called under the deploy lock: the flows it compares with change only when a deploy returns.
     *
     * @return the address each flow's consumer listens on, by its local id
     */
    private Map<String, InetSocketAddress> listeners(String name, Map<String, EndpointConfig> asked, List<String> problems) {
        List<Listener> taken = new ArrayList<>();   // the new version's flows asked so far
        List<Listener> held = new ArrayList<>();    // the admin API, the HTTP port, the peer link, then every other package's running flows
        held.add(new Listener("the node's admin API (RAIL8_ADMIN_HOST/PORT)", InetSocketAddress.createUnresolved(config.admin().host(), config.admin().port())));
        held.add(new Listener("the node's HTTP port (RAIL8_HTTP_HOST/PORT)", InetSocketAddress.createUnresolved(config.http().host(), config.http().port())));
        held.add(new Listener("the node's peer link (RAIL8_PEER_BIND/PORT)", InetSocketAddress.createUnresolved(config.peer().bind(), config.peer().port())));
        for (Deployed d : deployed.values()) {
            if (d.pkg().name().equals(name)) continue;   // the version this deploy replaces
            for (Flow f : d.flows()) {
                if (f.runs()) f.recipe().listensOn().ifPresent(at -> held.add(new Listener(listener(f.id(), f.from().name()), at)));
            }
        }
        Map<String, InetSocketAddress> listening = new LinkedHashMap<>();
        for (Map.Entry<String, EndpointConfig> each : asked.entrySet()) {
            EndpointConfig endpoint = each.getValue();
            Optional<InetSocketAddress> answer;
            try {
                answer = Objects.requireNonNull(components.get(endpoint.scheme()).listensOn(endpoint), "listensOn answered null");
            } catch (RuntimeException thrown) {
                problems.add("endpoint '" + endpoint.name() + "' (scheme '" + endpoint.scheme() + "'): its component cannot say where its consumer listens: "
                        + reason(thrown));
                continue;
            }
            if (answer.isEmpty()) continue;
            Listener at = new Listener(listener(each.getKey(), endpoint.name()), answer.get());
            Stream.concat(taken.stream(), held.stream()).filter(other -> Listeners.overlap(at.at(), other.at())).forEach(other -> problems.add(at.holder()
                    + " listens on " + Listeners.shown(at.at()) + ", which overlaps " + Listeners.shown(other.at()) + " of " + other.holder()
                    + ": one listener per port on a node (D-072)"));
            taken.add(at);
            listening.put(each.getKey(), at.at());
        }
        return listening;
    }

    /**
     * One flow per path on the node's HTTP port. For each of {@code asked} — as {@link #listeners} takes them — whose component
     * declares a prefix, the path its consumer would mount (the prefix, then its {@code address}, {@link SharedHttpPort#path}), and a
     * line in {@code problems} for an address {@code written} (the endpoint's config before its references were resolved) as a
     * secret reference, one that is not a path, and every path equal to that of a flow before it in id order (once per pair, on the later flow) or of a
     * running flow of another package, as that flow's check answered it. A FAILED flow holds no path, nor does a FAILED package, nor
     * the version of {@code name} this deploy replaces: its paths are free for the new version, whose consumers mount them as the
     * old ones unmount. Called under the deploy lock: the flows it compares with change only when a deploy returns.
     *
     * @return the path each flow's consumer takes requests at, by its local id
     */
    private Map<String, String> paths(String name, Map<String, EndpointConfig> asked, Map<String, JsonObject> written, List<String> problems) {
        Map<String, String> holders = new HashMap<>();   // by path: who takes requests there, every other package's running flows first
        for (Deployed d : deployed.values()) {
            if (d.pkg().name().equals(name)) continue;   // the version this deploy replaces
            for (Flow f : d.flows()) {
                if (f.runs()) f.recipe().httpPath().ifPresent(path -> holders.putIfAbsent(path, listener(f.id(), f.from().name())));
            }
        }
        Map<String, String> paths = new LinkedHashMap<>();
        for (Map.Entry<String, EndpointConfig> each : asked.entrySet()) {
            EndpointConfig endpoint = each.getValue();
            String prefix = prefixes.get(endpoint.scheme());
            if (prefix == null) continue;
            Object address = endpoint.options().get(HttpIngress.ADDRESS.name());
            if (!Objects.equals(address, written.get(endpoint.name()).getValue(HttpIngress.ADDRESS.name()))) {
                problems.add("endpoint '" + endpoint.name() + "' (scheme '" + endpoint.scheme() + "'): its address is written as a secret reference; "
                        + "an address is the path senders call, written as it is");
                continue;
            }
            String path;
            try {
                path = SharedHttpPort.path(prefix, address);
            } catch (IllegalArgumentException bad) {
                problems.add("endpoint '" + endpoint.name() + "' (scheme '" + endpoint.scheme() + "'): " + bad.getMessage());
                continue;
            }
            String holder = listener(each.getKey(), endpoint.name());
            String other = holders.putIfAbsent(path, holder);
            if (other != null) {
                problems.add(holder + " takes requests at " + path + " on the node's HTTP port, where " + other + " takes them: one flow per path");
                continue;
            }
            paths.put(each.getKey(), path);
        }
        return paths;
    }

    /**
     * Whether a running flow of a package deployed here takes requests at {@code path} of the node's HTTP port, as its check
     * answered it, whether or not its consumer has mounted it at the moment (a redeploy swaps it, it restarts, it stops). A
     * FAILED flow does not. Reads the listing as it is, without the deploy lock: called on an event loop for every request no mount
     * takes, it never blocks.
     */
    public boolean serves(String path) {
        for (Deployed d : deployed.values()) {
            for (Flow f : d.flows()) {
                if (f.runs() && f.recipe().httpPath().filter(path::equals).isPresent()) return true;
            }
        }
        return false;
    }

    /** A flow as a listener line names it: {@code flow '<id>' (endpoint '<endpoint>')}. */
    private static String listener(String flow, String endpoint) {
        return "flow '" + flow + "' (endpoint '" + endpoint + "')";
    }

    /**
     * The node-local addresses of each flow of {@code flows}, by its local id, in id order: the one its in endpoint of {@code asked}
     * serves and those of the endpoints of {@code callable} its steps send to ({@link FlowDefinition#referencedEndpoints}: processes
     * expanded, every {@code byProp} choice), as their components answer ({@link Component#internalAddress}). Each endpoint is asked
     * once; an empty answer names no address. A component that throws or answers null is one line in {@code problems} naming the
     * endpoint and its scheme, and the endpoint names no address.
     */
    private Map<String, Calls.Links> links(Map<String, FlowDefinition> flows, Map<String, EndpointConfig> asked, Map<String, EndpointConfig> callable,
                                           List<String> problems) {
        Map<String, Optional<Calls.Address>> answers = new HashMap<>();   // by endpoint name
        Function<EndpointConfig, Optional<Calls.Address>> ask = e -> answers.computeIfAbsent(e.name(), n -> address(e, problems));
        Map<String, Calls.Links> links = new TreeMap<>();
        flows.forEach((id, def) -> {
            Optional<Calls.Address> serves = Optional.ofNullable(asked.get(id)).flatMap(ask);
            List<Calls.Address> calls = def.referencedEndpoints().stream().map(callable::get).filter(Objects::nonNull).map(ask).flatMap(Optional::stream).toList();
            links.put(id, new Calls.Links(serves, calls));
        });
        return links;
    }

    /** The node-local address {@code endpoint} names as its component answers, or none; a throw or a null is a line in {@code problems}. */
    private Optional<Calls.Address> address(EndpointConfig endpoint, List<String> problems) {
        try {
            Optional<String> answer = Objects.requireNonNull(components.get(endpoint.scheme()).internalAddress(endpoint), "internalAddress answered null");
            return answer.map(value -> new Calls.Address(endpoint.scheme(), value));
        } catch (RuntimeException thrown) {
            problems.add("endpoint '" + endpoint.name() + "' (scheme '" + endpoint.scheme() + "'): its component cannot say which node-local address it serves "
                    + "or calls: " + reason(thrown));
            return Optional.empty();
        }
    }

    /**
     * The flows a check of {@code name} meets on the node: every running flow of another package, as its own check answered, by its
     * qualified id — a FAILED flow serves and calls nothing, nor does a FAILED package (it lists no flows), nor the version of
     * {@code name} the deploy replaces — but for a package an off-node validate holds, the flows of the held version instead
     * ({@link #heldNodes}).
     */
    private List<Calls.Node> others(String name, List<Held> held) throws IOException {
        Set<String> standIns = new HashSet<>();
        held.forEach(h -> standIns.add(h.name()));
        List<Calls.Node> others = new ArrayList<>();
        for (Deployed d : deployed.values()) {
            if (d.pkg().name().equals(name) || standIns.contains(d.pkg().name())) continue;
            d.flows().stream().filter(Flow::runs).map(Deployments::node).forEach(others::add);
        }
        for (Held h : held) {
            if (!h.name().equals(name)) others.addAll(heldNodes(h.name(), h.dir()));
        }
        return others;
    }

    /**
     * The flows of the package {@code pkgName} in the held folder {@code dir}, by their qualified ids, with the node-local addresses a
     * deploy of it would ask their components for ({@link #links}): of its endpoints whose config the schema and the component take,
     * this node's environment laid over it and every reference resolved, and of its flows that read. What does not check is left out
     * silently, and so is a component that cannot answer: those are problems of that package, not of the one validated.
     */
    private List<Calls.Node> heldNodes(String pkgName, Path dir) throws IOException {
        Package pkg = Package.load(dir);
        List<String> dropped = new ArrayList<>();
        Map<String, EndpointConfig> ins = new HashMap<>();
        Map<String, EndpointConfig> outs = new HashMap<>();
        pkg.endpoints().forEach((id, endpoint) -> {
            if (!Schemas.ENDPOINT.validate(endpoint).valid()) return;
            String scheme = endpoint.getString("scheme");
            Component component = components.get(scheme);
            JsonObject cfg = overlaid(endpoint);
            if (component == null || !refusedBy(component, cfg, endpoint.getString("direction")).isEmpty()) return;
            JsonObject values = resolved(id, cfg, dropped);
            if (values != null) ("in".equals(endpoint.getString("direction")) ? ins : outs).put(id, endpoint(id, scheme, values));
        });
        Map<String, FlowDefinition> flows = new HashMap<>();
        Map<String, EndpointConfig> asked = new HashMap<>();
        pkg.flows().forEach((id, flow) -> {
            if (!Schemas.FLOW.validate(flow).valid()) return;
            FlowDefinition def;
            try {
                def = FlowDefinition.from(map(flow.put("id", pkgName + "/" + id)));
            } catch (RuntimeException unread) {   // its line is its own package's
                return;
            }
            flows.put(id, def);
            if (ins.containsKey(def.fromEndpoint())) asked.put(id, ins.get(def.fromEndpoint()));
        });
        List<Calls.Node> nodes = new ArrayList<>();
        links(flows, asked, outs, dropped).forEach((id, links) -> nodes.add(new Calls.Node(pkgName + "/" + id, flows.get(id).fromEndpoint(), links)));
        return nodes;
    }

    /** A flow on the node as the call checks see it: its qualified id, its in endpoint, the addresses its check answered. */
    private static Calls.Node node(Flow f) {
        return new Calls.Node(f.id(), f.from().name(), f.recipe().calls());
    }

    /**
     * A WARNING for each call of a running flow, in package and flow order, that {@code which} picks and that no running flow on the
     * node serves: {@code flow '<qualified id>' calls <scheme> <address>, which no flow on this node serves}. Never a refusal.
     */
    private void warnUnserved(BiPredicate<Flow, Calls.Address> which) {
        List<Flow> running = deployed.values().stream().flatMap(d -> d.flows().stream()).filter(Flow::runs).toList();
        Set<Calls.Address> unserved = Calls.unserved(running.stream().map(Deployments::node).toList());
        for (Flow f : running) {
            for (Calls.Address a : f.recipe().calls().calls()) {
                if (unserved.contains(a) && which.test(f, a)) LOG.log(System.Logger.Level.WARNING, "flow '" + f.id() + "' calls " + a + ", which no flow on this node serves");
            }
        }
    }

    /**
     * The options of each step whose type a step factory on the node builds — {@link StepConfig#config()} without {@code recording},
     * i.e. the step without {@code type}, {@code name}, {@code retryable} and {@code recording} — judged by that factory's
     * {@code configSchema()}: a line per problem, {@code flow '<flow>' step <n> '<name>' (type '<type>') option at '<path>':
     * <message>}, n and name the step's number and name in the run (a step of a local process under {@code <call>/<step>}), and
     * one line for the step when the schema cannot be used (not JSON, no object, not a schema).
     * A type no factory builds is not judged: a built-in one is typed by the flow schema, an unknown one does not build.
     */
    private List<String> optionProblems(String flowId, List<StepConfig> defs) {
        List<String> problems = new ArrayList<>();
        for (StepConfig step : defs) {
            Optional<StepFactory> factory = steps.stream().filter(f -> f.type().equals(step.type())).findFirst();
            if (factory.isEmpty()) continue;
            JsonObject options = (JsonObject) json(step.config());
            options.remove("recording");   // the platform's key (the recorder reads it), never the factory's option
            String at = "flow '" + flowId + "' step " + step.index() + " '" + step.name() + "' (type '" + step.type() + "')";
            try {
                for (Schemas.Problem p : Schemas.check(factory.get().configSchema(), options).problems()) {
                    problems.add(at + " option at '" + p.path() + "': " + p.message());
                }
            } catch (RuntimeException unusable) {
                problems.add(at + ": the configSchema() of its step factory is not a usable JSON Schema: " + reason(unusable));
            }
        }
        return problems;
    }

    /** The endpoint's {@code config} with {@code environments.<env>.config} laid over it: a key the environment names is replaced whole, never merged. */
    private JsonObject overlaid(JsonObject endpoint) {
        JsonObject cfg = endpoint.getJsonObject("config");
        JsonObject environment = endpoint.getJsonObject("environments", new JsonObject()).getJsonObject(config.env(), new JsonObject());
        environment.getJsonObject("config", new JsonObject()).forEach(key -> cfg.put(key.getKey(), key.getValue()));
        return cfg;
    }

    /**
     * A copy of the endpoint config {@code cfg} with every string value that starts with {@code secret://}, at any depth of its
     * objects and arrays, replaced by the node's value of that reference ({@link NodeConfig#secrets()}); a string that
     * merely contains one is kept as written. Null, with a line added to {@code problems} per reference, when one is malformed
     * ({@code endpoint '<id>': } and the refusal naming it) or not set ({@code endpoint '<id>': secret '<reference>' is not set
     * (<variable>)}): no line carries a value.
     */
    private JsonObject resolved(String id, JsonObject cfg, List<String> problems) {
        int before = problems.size();
        JsonObject values = (JsonObject) resolved(id, (Object) cfg, problems);
        return problems.size() == before ? values : null;
    }

    private Object resolved(String id, Object value, List<String> problems) {
        if (value instanceof JsonObject object) {
            JsonObject copy = new JsonObject();
            object.forEach(e -> copy.put(e.getKey(), resolved(id, e.getValue(), problems)));
            return copy;
        }
        if (value instanceof JsonArray array) {
            JsonArray copy = new JsonArray();
            array.forEach(item -> copy.add(resolved(id, item, problems)));
            return copy;
        }
        if (!(value instanceof String reference) || !reference.startsWith(Secrets.SCHEME)) return value;
        try {
            Optional<String> secret = config.secrets().resolve(reference);
            if (secret.isEmpty()) problems.add("endpoint '" + id + "': secret '" + reference + "' is not set (" + Secrets.variable(reference) + ")");
            return secret.orElse(null);
        } catch (IllegalArgumentException malformed) {
            problems.add("endpoint '" + id + "': " + malformed.getMessage());
            return null;
        }
    }

    /**
     * Every flow built — delivery, worker, effective hash; no consumer yet — over one builder that carries the package's versions,
     * each added to {@code flows} as soon as it is built; returns every failure as one problem line, the flows that built in
     * {@code flows} all the same.
     */
    private List<String> build(String name, Optional<String> commit, String version, String hex, Package pkg, Path dir, Checked checked,
                               List<Flow> flows) throws IOException {
        Map<String, EndpointConfig> endpoints = new LinkedHashMap<>();   // as the components get them, references resolved
        Map<String, EndpointConfig> written = new LinkedHashMap<>();     // as the package writes them: what the hash covers
        checked.configs().forEach((id, cfg) -> {
            endpoints.put(id, endpoint(id, checked.schemes().get(id), checked.resolved().get(id)));
            written.put(id, endpoint(id, checked.schemes().get(id), cfg));
        });
        FlowBuilder builder = builder(name, version, commit, endpoints);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, FlowDefinition> each : checked.flows().entrySet()) {
            String id = each.getKey();
            try {
                flows.add(flow(builder, name, version, commit, hex,
                        new Recipe(each.getValue(), pkg.flowFile(id), Map.copyOf(endpoints), Map.copyOf(written),
                                Optional.ofNullable(checked.listening().get(id)), Optional.ofNullable(checked.paths().get(id)), checked.calls().get(id),
                                checked.dependencies().visible()), dir));
            } catch (RuntimeException refused) {
                problems.add("flow '" + id + "' does not build: " + fromRoot(reason(refused), dir, checked.dependencies().visible()));
            }
        }
        return problems;
    }

    /**
     * A builder over {@code endpoints} that stamps every message with {@code package=<name>@<version>} and, when given, {@code commit};
     * it marks the steps with StepEvents only when the node samples CPU time, and its {@code to}
     * steps send under the node's {@link Targets}; its at-least-once workers retry under the node's default schedule
     * ({@code RAIL8_RETRIES_*}) when a flow names no {@code delivery.retries}. Its steps' secrets are the node's
     * ({@link NodeConfig#secrets()}), and the steps of one type share the node's cache with that type's steps in the package's
     * other flows and versions.
     */
    private FlowBuilder builder(String name, String version, Optional<String> commit, Map<String, EndpointConfig> endpoints) {
        Map<String, String> versions = new HashMap<>();
        versions.put("package", name + "@" + version);
        commit.ifPresent(c -> versions.put("commit", c));
        return new FlowBuilder(runtime, steps, languages, endpoints).recording(messages, meter).dlq(dlq).queue(queue).intake(intake).versions(versions)
                .stepEvents(config.cpuSampling().isPresent()).targets(targets).fence(fence).workers(workers).signals(signals).retries(config.retries())
                .runs(runs).secrets(this::secret).caches(stepType -> caches.scope(MessageContext.DEFAULT_TENANT, name, stepType));
    }

    /**
     * The node's value of {@code reference}.
     *
     * @throws IllegalArgumentException naming the reference when it is malformed, and its variable when that is not set
     */
    private String secret(String reference) {
        return config.secrets().resolve(reference)
                .orElseThrow(() -> new IllegalArgumentException("secret '" + reference + "' is not set (" + Secrets.variable(reference) + ")"));
    }

    /**
     * One flow built from {@code recipe}, its package in {@code dir} (whose name is {@code hex}): its delivery, its worker
     * (at-least-once), its effective hash ({@link FlowHash}: the
     * files those builds read, its endpoints as its consumer and its producers get them); its consumer comes later
     * ({@link #consumers}). A flow that fails after
     * its delivery was built stops and closes what it built of itself before the failure leaves, unchecked ones included —
     * its worker has run nothing yet, so its close runs at once; a worker that does not build has closed its own producers.
     *
     * @throws IOException a file the build read cannot be hashed: the node's failure
     */
    private Flow flow(FlowBuilder builder, String name, String version, Optional<String> commit, String hex, Recipe recipe, Path dir) throws IOException {
        FlowDefinition def = recipe.def();
        Map<String, EndpointConfig> endpoints = recipe.endpoints();
        Set<Path> reads = ConcurrentHashMap.newKeySet();   // the stages keep the sink: thread-safe, each path once
        builder.reads(reads::add).dependencies(recipe.dependencies().dirs());
        Path baseDir = dir;   // a step's file reference is a path from the package root, wherever the flow file lies
        EndpointConfig consumed = consumed(endpoints.get(def.fromEndpoint()), def);
        Delivery delivery = builder.delivery(def, baseDir);
        FlowWorker worker = null;
        boolean built = false;
        try {
            worker = AT_LEAST_ONCE.equals(def.deliveryMode()) ? builder.worker(def, baseDir, config.nodeId()) : null;
            Map<String, EndpointConfig> written = recipe.written();   // the hash covers the references, never a value
            String hash = FlowHash.of(dir, recipe.dependencies().dirs(), recipe.flowFile(), reads, consumed(written.get(def.fromEndpoint()), def),
                    def.referencedEndpoints().stream().map(written::get).toList());
            built = true;
            return new Flow(new DeployedFlow(def.id(), name, version, commit, def.version(), def.deliveryMode(), hash, DeployedFlow.State.RUNNING),
                    hex, recipe, delivery, worker, consumed, null);
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

    /** The in endpoint {@code from} as the consumer of {@code def} gets it: with the flow's qualified id and its maxBodyBytes. */
    private static EndpointConfig consumed(EndpointConfig from, FlowDefinition def) {
        Map<String, Object> options = new HashMap<>(from.options());
        options.put("flow", def.id());
        options.put("maxBodyBytes", def.maxBodyBytes());
        return new EndpointConfig(from.name(), from.uri(), options);
    }

    /**
     * Builds the consumer of each of {@code flows} (the added and changed ones) and returns their copies with it, in order; one the
     * component refuses, or whose {@code polls} throws, is one line added to {@code problems}. Each consumer runs in its
     * {@link RestartingConsumer}, which builds every later one from the same endpoint and handler, its start and its wait for the
     * stop of the one before bounded by the node's stop deadline; the consumer of a polling endpoint, each later one included, is
     * wrapped in a {@link LeasedConsumer} of its own first. Nothing is started: a consumer never started is never stopped.
     */
    private List<Flow> consumers(List<Flow> flows, List<String> problems) {
        List<Flow> with = new ArrayList<>(flows.size());
        for (Flow f : flows) {
            Function<Envelope, Future<Ack>> handler = f.delivery();   // an at-least-once save hands its item off to the flow's worker
            EndpointConfig from = f.from();
            Component component = components.get(from.scheme());
            try {
                boolean polls = component.polls(from);
                Consumer consumer = component.consumer(from, handler);
                Supplier<Consumer> build = () -> component.consumer(from, handler);
                String key = f.id() + "/" + from.uri();
                Consumer first = polls ? leased(key, consumer, build) : consumer;
                Supplier<Consumer> fresh = polls ? () -> leased(key, build.get(), build) : build;
                with.add(f.with(new RestartingConsumer(f.id(), first, fresh, polls, config.stopDeadline(), timer, clock)));
            } catch (RuntimeException refused) {
                problems.add("flow '" + local(f.flow()) + "' does not build: " + reason(refused));
            }
        }
        return with;
    }

    /**
     * {@code first} under the lease {@code key} ({@code <package>/<flow>/<endpoint uri>}), owned by {@code <nodeId>#<n>}, on the
     * node's timer and clock with the ttl and renew period of its config; {@code fresh} builds each consumer after the first.
     */
    private LeasedConsumer leased(String key, Consumer first, Supplier<Consumer> fresh) {
        NodeConfig.Leases lease = config.leases();
        return new LeasedConsumer(leases, key, config.nodeId() + "#" + owners.incrementAndGet(), lease.ttl(), lease.renew(), timer, clock, first, fresh);
    }

    /**
     * Phase A of the swap, and the stop of an undeploy: every flow of {@code retiring} at once — stopped with the node's stop deadline, then closed
     * ({@link #stopAndClose}); a stop that fails, throws or does not complete in time is logged ({@code why} it stopped) and counts as settled, and keeps
     * no other flow from its stop. Those whose id is in
     * {@code leaving} are asked for queue items once their worker drained ({@link #recheck}). Returns once every one has settled:
     * the barrier phase B waits behind. Never throws.
     */
    private void retire(List<Flow> retiring, Set<String> leaving, String why) {
        List<Future<Void>> settled = new ArrayList<>(retiring.size());
        for (Flow f : retiring) {
            Future<Void> retired = stopAndClose(f, config.stopDeadline());
            if (leaving.contains(f.id())) recheck(f, retired);
            settled.add(retired.onFailure(failed -> LOG.log(System.Logger.Level.WARNING, "flow '" + f.id() + "' did not stop cleanly as " + why + ": "
                    + failed, failed)).otherwiseEmpty());
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
     * ({@link RestartingConsumer#start}), then, once it started, its worker (as worker {@code nodeId}) — and returns once every
     * start settled, each flow with its outcome, in order: a consumer start that failed, threw or had not completed at the deadline
     * is a failure, and so is a worker start that threw (it runs inside the flow's start future, never in a listener). With
     * {@code retry} a consumer start that failed is no failure of the flow: its consumer is tried again
     * ({@link RestartingConsumer#retry}; the flow is listed RESTARTING meanwhile) and its worker starts all the same. A consumer
     * whose start had not completed at the deadline is stopped once that start completes: no stop meets a consumer still starting.
     */
    private List<Start> start(List<Flow> flows, boolean retry) {
        List<Future<Void>> calls = new ArrayList<>(flows.size());
        List<Future<Void>> started = new ArrayList<>(flows.size());
        for (Flow f : flows) {
            Future<Void> call = Future.future(done -> f.consumer().start().onComplete(done));
            calls.add(call);
            // the worker starts inside the flow's start future: a throw fails it, the flow's failed start, where a
            // listener's throw would be lost
            started.add(call.transform(consumer -> {
                if (consumer.failed() && !retry) return Future.failedFuture(consumer.cause());
                if (consumer.failed()) f.consumer().retry(consumer.cause());
                if (f.worker() != null) f.worker().start();
                return Future.succeededFuture();
            }));
        }
        Future.join(started).otherwiseEmpty().await();
        List<Start> outcome = new ArrayList<>(flows.size());
        for (int i = 0; i < flows.size(); i++) {
            Future<Void> call = calls.get(i);
            outcome.add(new Start(flows.get(i), started.get(i).cause(), retry && call.failed() ? call.cause() : null));
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
     * Closes the producers of {@code flows}: each delivery's once it stopped, awaited; each worker's asked, not awaited
     * ({@link Flow#close}; the node's shutdown waits for it in {@link #drain}). Never fails, a failed producer close is logged.
     */
    private static Future<Void> close(List<Flow> flows) {
        return Future.all(flows.stream().map(Flow::close).toList()).mapEmpty();
    }

    /**
     * The node's shutdown wait for the workers of {@code flows}: once {@code stopped} settled, each worker's close —
     * complete once its items settled in the work queue and its producers closed ({@link FlowWorker#close}) — bounded by the
     * node's stop deadline from now ({@link #bounded}), every flow on a bound of its own. Completes once every one closed or
     * passed its bound: one WARNING per flow whose worker had not closed by then, naming its count of unsettled items — their
     * work rows stay claimed, never settled or deleted here, and run again once their lease ran out. Never fails: a worker's
     * close never fails, and never throws once its {@link FlowWorker#stop} ran — {@link #stop(Flow, Duration)} calls it before
     * this, and it sets its flag before anything that could throw — so a bound fails only with its deadline.
     */
    private Future<Void> drain(List<Flow> flows, Future<Void> stopped) {
        return Future.join(flows.stream().filter(f -> f.worker() != null).map(f -> drain(f, stopped)).toList()).mapEmpty();
    }

    private Future<Void> drain(Flow f, Future<Void> stopped) {
        return bounded(stopped.transform(s -> f.worker().close()), "flow '" + f.id() + "': its worker's items in flight")
                .onFailure(late -> LOG.log(System.Logger.Level.WARNING, "flow '" + f.id() + "' closes with " + f.worker().unsettled() + " unsettled item(s) as "
                        + "the node shuts down: their work rows stay claimed and run again once their lease ran out: " + late, late))
                .otherwiseEmpty();
    }

    /**
     * Stops flows built and not to run on — {@code started} ones within the node's stop deadline, {@code others} at once (a zero
     * deadline, cancelling what is in flight) — and closes each once it stopped ({@link #stopAndClose}); every flow is stopped
     * whatever another's stop does, and one that does not stop cleanly is a WARNING naming it. Returns once every one has settled:
     * the first failure, in that order, or null — a refusal adds it as suppressed. Never throws.
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
     * {@code failure} as suppressed: the refusal stays what is thrown, and the steps after it run all the same.
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
                quietly("package '" + name + "': the directory " + child.getFileName() + " (neither current, previous, in use nor pinned) could not be deleted",
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

    private static DeployRefusedException badName(String name) {
        return refused(Kind.BAD_REQUEST, "the package name '" + name + "' must be a lower-case letter, then at most 63 lower-case letters, digits and dashes");
    }

    private static DeployRefusedException stopping() {
        return refused(Kind.STOPPING, "the node is closing: it takes no deploy or undeploy");
    }

    /** The refusal of a deploy or undeploy that finds the node's lock held: {@link #stopping} while closing, else {@link Kind#CONFLICT}. */
    private DeployRefusedException busy() {
        return closing ? stopping() : refused(Kind.CONFLICT, "another deploy or undeploy is running: try again once it is done");
    }

    /**
     * The qualified ids of the at-least-once flows of {@code running}, once the work queue said it holds no item of any:
     * items of one refuse the undeploy ({@link Kind#CONFLICT}, a line per such flow by its local id, sorted as the flows are), since they would
     * never run; a question the queue fails is the node's {@link IOException} at once, naming the flow and the cause, whether or not
     * the pool refused an earlier one. A question the node's Postgres pool refused a connection ({@link PgTx#refusedByThePool}) does
     * not stop the asking: once every flow was asked without a real failure, items of any flow are still the CONFLICT, and only when
     * no flow holds items is it {@link Kind#BUSY} with the last such line (a real failure, then a flow known to hold
     * items, outranks a refusal in any flow order).
     */
    private Set<String> queued(List<Flow> running) throws DeployRefusedException, IOException {
        Set<String> queued = new HashSet<>();
        List<String> held = new ArrayList<>();
        String pooled = null;   // a question the node's Postgres pool refused: BUSY, unless a question fails for real or a flow holds items
        for (Flow f : running) {
            if (!AT_LEAST_ONCE.equals(f.flow().delivery())) continue;
            queued.add(f.id());
            Future<Boolean> items = queue.hasItems(f.id());
            if (Boolean.TRUE.equals(items.otherwiseEmpty().await())) {
                held.add("flow '" + local(f.flow()) + "' runs at-least-once and the work queue holds items of it (queued, leased, held or stopped): undeploy it once "
                        + "they are done");
            } else if (items.failed()) {
                String unanswered = "the work queue could not say whether it holds items of flow '" + f.id() + "': " + reason(items.cause());
                if (!PgTx.refusedByThePool(items.cause())) throw new IOException(unanswered, items.cause());
                pooled = unanswered;   // the later flows are still asked: a real failure of one outranks it
            }
        }
        if (!held.isEmpty()) throw refused(Kind.CONFLICT, String.join("\n", held));   // known items outrank a refusal
        if (pooled != null) throw refused(Kind.BUSY, pooled);   // every failed question was the pool's, no flow holds items: retryable
        return queued;
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

    /**
     * {@code message} with every path below the package directory {@code dir} named from the package root, and {@code dir} itself
     * as {@code .}: a step's failure may name a package file, or the directory a reference leaves, by its absolute
     * path — absolute and normalised, as StepResources prints it, whatever form the data directory was given in; no problem line does.
     * A path below a dependency's directory is named {@code <dep>:<path>}, the directory itself {@code <dep>@<version>}.
     */
    private static String fromRoot(String message, Path dir, Visible dependencies) {
        String root = dir.toAbsolutePath().normalize().toString();
        String shown = message.replace(root + File.separator, "").replace(root, ".");
        for (Map.Entry<String, Path> d : dependencies.dirs().entrySet()) {
            String at = d.getValue().toAbsolutePath().normalize().toString();
            shown = shown.replace(at + File.separator, d.getKey() + ":").replace(at, d.getKey() + "@" + dependencies.versions().get(d.getKey()));
        }
        return shown;
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

    /** {@code value} with its maps and lists as JSON objects and arrays, at any depth: the inverse of {@link #plain}. */
    private static Object json(Object value) {
        if (value instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            m.forEach((k, v) -> o.put(String.valueOf(k), json(v)));
            return o;
        }
        if (value instanceof List<?> l) {
            JsonArray a = new JsonArray();
            l.forEach(v -> a.add(json(v)));
            return a;
        }
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

    /** Whether {@code dir} holds the content {@code content} ({@link Package#content}); no directory, or one unread, does not. */
    private static boolean holds(Path dir, String content) {
        try {
            return Files.isDirectory(dir) && Package.content(dir).equals(content);
        } catch (IOException | UncheckedIOException unreadable) {
            return false;
        }
    }

    /** The hex of a content digest, {@code sha256:<hex>}: the name of the directory its package is written into. */
    static String hex(String content) {
        return content.substring(content.indexOf(':') + 1);
    }

    /** Deletes {@code packages/<name>/<hex>/} with everything below it, then {@code packages/<name>/} when nothing is left in it. */
    private static void deleteWritten(Path dir) throws IOException {
        deleteTree(dir);
        deleteIfEmpty(dir.getParent());
    }

    /** Deletes the directory {@code dir} when nothing is in it; nothing when it does not exist. */
    private static void deleteIfEmpty(Path dir) throws IOException {
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) return;
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
