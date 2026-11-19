package dev.rail8.runtime.admin;

import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobNotFoundException;
import dev.rail8.api.BodyLostException;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Envelope;
import dev.rail8.api.Hash;
import dev.rail8.api.Health;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.core.admission.TargetLimit;
import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.flow.Runs;
import dev.rail8.core.flow.WorkSignals;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.MessageStore;
import dev.rail8.core.record.Page;
import dev.rail8.core.record.Snapshot;
import dev.rail8.core.record.StepCpu;
import dev.rail8.runtime.deploy.DeployRefusedException;
import dev.rail8.runtime.deploy.DeployResult;
import dev.rail8.runtime.deploy.DeployedFlow;
import dev.rail8.runtime.deploy.DeployedPackage;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.UndeployResult;
import dev.rail8.runtime.pg.PgTx;
import dev.rail8.runtime.store.PgMessageList;
import dev.rail8.runtime.store.StepLogJson;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The admin read API over the stores: a message's timeline with wall and CPU nanos per step (the rows of
 * every attempt by attempt, then step, each naming its {@code attempt} and showing the lines the step's code logged as its
 * {@code log}, {@link StepLogJson}), the payload after step n (the latest attempt's,
 * or {@code ?attempt=k}'s), the search for messages and the DLQ a page at a time ({@code {items, next|null}}), {@code /health},
 * and the probes {@code /live} and {@code /ready}. {@code GET /admin/messages} finds message ids by business key
 * ({@code ?key=name:value}, {@link MessageStore#findByKey}) or by a step they passed ({@code ?flow=<flow id>&step=<step name>},
 * {@link MessageStore#findByStep}), with {@code limit} and {@code after}; it is 400 for neither, for a key with a flow or step,
 * and for a flow without a step or a step without a flow. With {@code from} or {@code to} it is instead the list of recent
 * messages ({@link PgMessageList}): {@code from} and {@code to} are ISO-8601 instants, both required, and {@code flow},
 * {@code status} (completed, failed, running or cancelled), {@code key=name:value} and {@code step} narrow it; its items are
 * {@code {id, flow, status, startedAt, durationNanos, keys}}, and it is 400 for a range the list refuses, for an instant or a
 * status that does not parse, and on a server built without the list. {@code GET /admin/message-counts?from=&to=} counts the
 * messages of that list in each status ({@link PgMessageList#counts}), of {@code flow} when it is given: an object
 * {@code {completed, failed, running, cancelled}}, 400 for the same refusals as the list.
 *
 * <p>A verticle owns the socket and routes on its event loop; every route body runs on a fresh virtual thread — it awaits
 * the stores, encodes the answer and checks its headers there — and the answer is written back on the verticle's context.
 * {@code /admin/**} needs {@code Authorization: Bearer <token>} when a token is configured (the scheme in any case, the
 * token compared with {@link MessageDigest#isEqual}); without one it is open, which only a loopback bind allows.
 * {@code /health} is open and shows status only, never a check's detail. {@code /live} and {@code /ready} are open too:
 * {@code /live} answers 200 {@code {"status":"UP"}} while the server runs and reads nothing; {@code /ready} reads its
 * {@link Readiness} inputs on the request, awaits nothing, and answers 200 {@code {"status":"UP","checks":{..}}} when every
 * one is UP, else 503 {@code {"status":"DOWN","checks":{..}}}. Every error body is {@code {error}}: 400 for a
 * parameter that does not parse or a limit or cursor a page store refuses, 401 without the token, 404 for what is not
 * there (a message, a step, a body recorded as metadata only, bytes no longer stored), 405 for a method a route does not
 * take, 503 for a request the node's Postgres pool refused a connection (its wait elapsed, its wait queue full, or the
 * pool closed by a stopping node: retryable), whether a statement on the pool was refused or a transaction's
 * acquire ({@link dev.rail8.runtime.pg.PoolRefusedException}): the reads, the work queue's discard, the DLQ
 * {@code /retry}'s read and act and {@code /cancel}'s act with a fixed text as {@code error}, a deploy's work-queue question or record write and an
 * undeploy's work-queue question or record delete with the refusal's own line (below); 500 for anything else: a store that
 * failed, a recorded header no response may carry, a stored body whose stream fails before the head is out (after it, the
 * response is reset).
 *
 * <p>With a {@link Deployments} it also deploys: {@code PUT /admin/packages/{name}?revision=} — a Content-Length
 * above {@code maxPackageBytes} is 413 before a byte of the body is read; otherwise {@code Expect: 100-continue} is answered with
 * {@code 100 Continue} and the body is streamed with back-pressure into a
 * temp file in {@code incomingDir()} and counted, 413 at byte max+1; the deploy runs on the route's virtual thread and the temp
 * file is deleted before the answer goes out, whatever it is: 200 {@code {package, version, revision|null, archive, flows, added,
 * swapped, removed}} (the last three sorted qualified flow ids: a redeploy swaps only the flows that changed), or a refusal
 * by its kind — 400, 403, 409 (another deploy runs on the node), 422 {@code {error, problems}}, 500 ({@code {error}}; a redeploy
 * whose start failed and rolled back: {@code {error, problems}}), 503 (the node stops; or the node's Postgres pool refused
 * the deploy record's write: the same body as the 500 of a record not written, or its work-queue question: {@code {error}}
 * naming the flow and the pool's refusal; only when every failed question was the pool's — a real failure of another
 * flow's question is 500). An answer given before
 * the request's body was read to its end closes the connection once written: the rest is never read. {@code GET /admin/flows} and
 * {@code GET /admin/packages} list what runs, sorted, not paged; a flow shows its effective {@code hash} and its {@code state}
 * ({@code RUNNING}, {@code RESTARTING} or {@code FAILED}; a RESTARTING one also {@code restart: {failures, next, cause}}), a package its {@code state} and its {@code problems} (FAILED when the restore could not install it, with no
 * flows, no files, no folders and no artifacts) and its tree: {@code folders:[{path, namespace|null, description|null}]}
 * and {@code artifacts:[{path, kind, id|null}]}, each sorted by path, its {@code content} digest (null when FAILED) and its
 * {@code dependencies:[{name, version, content, archive, direct}]}, the closure its deploy resolved.
 * {@code DELETE /admin/packages/{name}} undeploys on the route's virtual thread: 200 {@code {name, version, flowsRemoved}}
 * (its flows' qualified ids, sorted), or a refusal by the deploy's status map — 400, 403, 409 (another deploy or undeploy runs on
 * the node, or another package's record pins it, or the work queue holds items of a flow of it), 503 (the node stops; or the
 * node's Postgres pool refused the work queue's question or the record delete: nothing changed, retryable; a refused
 * question only when no other question failed otherwise and no flow holds items — those are 500 and 409) — and 404 for a package the node does not list; a record
 * delete or a queue question that fails otherwise is 500. {@code GET /admin/targets} lists the node's limits per target system, sorted by target: 200
 * {@code {items:[{target, maxCalls, maxWaitMs, inFlight, waiting, refused}]}}, every target a built flow named so far; a limit of a
 * component that names no target is not listed. {@code GET /admin/components} lists every component the node serves, sorted by
 * scheme: 200 {@code {environment, httpOrigin, items:[{scheme, httpPrefix, settings}]}}, {@code environment} the node's
 * {@code RAIL8_ENV}, {@code httpOrigin} the origin senders call its HTTP port at ({@code RAIL8_HTTP_ORIGIN}, null when not set),
 * {@code httpPrefix} the path the component's in endpoints are served under on that port (null when it takes no requests there),
 * and {@code settings} the component's settings description as the UI draws its form from it. {@code GET /admin/steps} lists every step type a module or plugin of the node builds, sorted by type: 200
 * {@code {items:[{type, configSchema}]}}, {@code configSchema} the JSON Schema of the step's options, or null when the factory's
 * is no JSON object; the built-in steps are not listed. Without one these routes do not exist (404).
 *
 * <p>With a {@link Deployments} it also shows a deployed flow's CPU by step: {@code GET /admin/flows/{flow}/cpu} — the flow's
 * qualified id as one path segment, its slash percent-encoded — answers, with a {@link Sampling}, 200 {@code {flow, sampling:"on",
 * period, since, items:[{step, name, executions, samples, cpuNanos}], totals:{samples, attributed, pending, unattributed, platform,
 * lost, dropped}}}: the node's CPU-sampling aggregate since the sampler started, the items by CPU descending, the totals node-wide
 * ({@link StepCpu}); never a per-message figure. Once the sampler's stream has failed, {@code sampling:"failed"} with the rest as the
 * stream last fed it. Without one, 200 {@code {flow, sampling:"off", items:[]}}. A flow not deployed here is 404.
 *
 * <p>With a {@link WorkQueue} it shows and releases held work items: {@code GET /admin/work/held?limit&after} pages every
 * flow's held rows in seq order ({@code {items:[{seq, messageId, flow, step, partitionKey, attempts, heldAt}], next|null}});
 * {@code POST /admin/work/{seq}/release} answers 200 {@code {seq}}, 404 for a seq unknown or not held, 400 for a seq that is
 * not a long. A release wakes the row's flow in the node's {@link Workers} (every flow when the queue cannot tell the row) and
 * announces it through the node's {@link WorkSignals} to the node whose journal holds its body ({@code *} when unknown);
 * built without them, a release wakes and announces nothing. Each held row also carries {@code blocks}, the rows of its
 * key behind it. A row that stops its key — a message of an ordered flow that failed for good, parked, the rows of its key
 * waiting behind it until an operator retries or cancels it — is not among the held rows: {@code GET /admin/work/stopped?limit&after}
 * pages those the same way ({@code {items:[{seq, messageId, flow, step, partitionKey, attempts, stoppedAt, blocks, error|null}],
 * next|null}}, {@code error} the parked failure's text), and {@code /release} does not release one (404). {@code GET /admin/work/lost?limit&after} pages the lost rows — their body given up by an accepted
 * {@code RAIL8_JOURNAL_ACCEPT_LOSS} — the same way ({@code {items:[{seq, messageId, flow, partitionKey, lostAt, blocks}],
 * next|null}}); {@code POST /admin/work/{seq}/discard} deletes a lost row and writes one {@code audit} row in the same transaction,
 * answering 200 {@code {seq, auditId}}, 404 for a seq unknown or not lost, 400 for a seq that is not a long or an
 * {@code X-Rail8-Actor} over 128 characters; the actor is that header ({@code unknown} without one), self-declared under the
 * shared token. A discard wakes the row's flow and announces it to the node of its key's next row, as a release does. Without a
 * queue these routes do not exist (404).
 *
 * <p>With a {@link FailedMessages}, the operator acts on a parked message, each act audited with the actor as {@code /discard}
 * reads it. With a {@link Deployments} too, {@code POST /admin/dlq/{id}/retry} runs a message its flow's worker parked once more
 * under its own id (a message keeps one id), as attempt k + 1 of the row's own bound: in place when it stops its key, else at
 * the tail of its key; 202 {@code {id, attempt}}, 404 (no DLQ row), 409 (its flow not deployed here, a work row of the message not
 * settled yet or its body lost, its ingress body no longer kept), 422 saying why (a flow that is not at-least-once, a row its
 * worker did not park, or a keyed flow's row parked before its key was kept). {@code POST /admin/dlq/{id}/cancel} ends a parked
 * message on any node, deployed or not, and so frees its key: 200 {@code {id, auditId, cancelledAt}}, 404 (no DLQ row), 409 (a
 * work row of the message not settled yet), 400 (a body other than none or {@code {"reason"}}), 413 (a body above
 * {@link #CANCEL_BODY_MAX} bytes). {@code POST /admin/messages/{id}/cancel} ends an at-least-once message wherever it is, with the
 * same body and answers: a parked one as the DLQ cancel does (200, {@code state: "ended"}); one queued, waiting or running has its
 * work row marked and its run stopped (202 {@code {id, auditId, cancelledAt, state: "stopping", node}}); 404 for a message with
 * neither, sync and at-most-once runs among them. There is no {@code /restart} route (404).
 *
 * <p>With {@link Modules}, each admin service a module brings is a route, {@code <method> /admin/modules/<plugin>/<path>}, that
 * runs the service's calls on virtual threads of their own, at most 4 of one plugin at once ({@link ModuleCalls}); its own
 * answers are problems ({@code application/problem+json}), not {@code {error}}.
 *
 * <p>Every {@code /admin/**} request that carries an {@code Origin} header, or a {@code Sec-Fetch-Site} other than {@code none},
 * is 403 {@code {error}} before the token check and the route, token or no token: what a browser sends, never the CLI.
 *
 * @since 0.1
 */
public final class AdminServer {

    /** The page size when a request names no {@code limit}; the stores refuse more than {@link Page#MAX_LIMIT}. */
    public static final int DEFAULT_LIMIT = 50;

    private static final System.Logger LOG = System.getLogger(AdminServer.class.getName());

    /** The header an admin request declares its actor in, for the audit row; and its length at most. */
    static final String ACTOR = "X-Rail8-Actor";
    static final int ACTOR_MAX = 128;
    /** The largest body {@code POST /admin/dlq/{id}/cancel} reads, in bytes: a larger one is 413. */
    static final int CANCEL_BODY_MAX = 8 * 1024;
    private static final String JSON = "application/json";
    private static final String OCTETS = "application/octet-stream";
    private static final String BEARER = "Bearer ";
    /** What the router answers itself: no route, a method the route does not take, a request it cannot read, a handler that threw. */
    private static final Map<Integer, String> ROUTER_ERRORS = Map.of(400, "bad request", 404, "no such route", 405, "method not allowed",
            413, "the request body is above " + CANCEL_BODY_MAX + " bytes", 500, "internal error");

    /**
     * Where the API listens and the token {@code /admin/**} needs. A blank token is no token; a host that is not a loopback
     * address literal (a host name included: where it resolves is not known here) without a token is refused.
     *
     * @param host the bind address
     * @param port the port; 0 binds any free one
     * @param token the bearer token, empty for none
     */
    public record Config(String host, int port, Optional<String> token) {
        public Config {
            Objects.requireNonNull(host, "host");
            token = token.filter(t -> !t.isBlank());
            if (token.isEmpty() && !loopback(host)) {
                throw new IllegalArgumentException("the admin API on " + host + " is not loopback-only: it needs a token (RAIL8_ADMIN_TOKEN)");
            }
        }

        /** The token redacted: a log line never carries it. */
        @Override
        public String toString() {
            return "Config[host=" + host + ", port=" + port + ", token=" + token.map(t -> "***") + "]";
        }

        private static boolean loopback(String host) {
            try {
                return InetAddress.ofLiteral(host).isLoopbackAddress();
            } catch (IllegalArgumentException aHostName) {
                return false;
            }
        }
    }

    /**
     * What {@code /health} reports: each check by name (a check that fails is DOWN), the snapshot writes that failed, and —
     * only when there is one — the node's {@code notices}: facts an operator should act on that fail nothing (a
     * generated master key), never a key or a check's detail.
     *
     * @param checks the checks, by name
     * @param snapshotWriteFailures read on every request
     * @param notices shown as the answer's {@code notices} array when not empty
     * @param readiness what {@code /ready} reports
     */
    public record HealthProbe(Map<String, Supplier<Future<Health>>> checks, LongSupplier snapshotWriteFailures, List<String> notices, Readiness readiness) {
        public HealthProbe {
            checks = Map.copyOf(checks);
            Objects.requireNonNull(snapshotWriteFailures, "snapshotWriteFailures");
            notices = List.copyOf(notices);
            Objects.requireNonNull(readiness, "readiness");
        }

        /** No readiness inputs: {@code /ready} is UP on every check while the server answers ({@link Readiness#ALWAYS}). */
        public HealthProbe(Map<String, Supplier<Future<Health>>> checks, LongSupplier snapshotWriteFailures, List<String> notices) {
            this(checks, snapshotWriteFailures, notices, Readiness.ALWAYS);
        }

        /** No notices, no readiness inputs ({@link Readiness#ALWAYS}). */
        public HealthProbe(Map<String, Supplier<Future<Health>>> checks, LongSupplier snapshotWriteFailures) {
            this(checks, snapshotWriteFailures, List.of());
        }
    }

    /**
     * What {@code /ready} reports: four in-memory inputs, true is UP, each read on every request and never awaited — so
     * {@code /ready} needs no deadline.
     *
     * @param lifecycle the node finished its start and its close has not begun
     * @param fence the node lease's fence is open
     * @param journal the journal is UP: neither failed nor in outage (DEGRADED is DOWN here)
     * @param deployments the deployments check is not DOWN: a package or a flow listed FAILED or RESTARTING keeps the node ready
     */
    public record Readiness(BooleanSupplier lifecycle, BooleanSupplier fence, BooleanSupplier journal, BooleanSupplier deployments) {
        /** Every input UP: what a server built without readiness inputs reports. */
        public static final Readiness ALWAYS = new Readiness(() -> true, () -> true, () -> true, () -> true);

        public Readiness {
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(fence, "fence");
            Objects.requireNonNull(journal, "journal");
            Objects.requireNonNull(deployments, "deployments");
        }
    }

    /**
     * What {@code GET /admin/flows/{flow}/cpu} shows when the node runs a CPU sampler.
     *
     * @param period the sampling period
     * @param since when the sampler started: the aggregate is cumulative since then
     * @param cpu the sampler's aggregate
     * @param failed whether the sampler's stream has failed, read at every request
     */
    public record Sampling(Duration period, Instant since, StepCpu cpu, BooleanSupplier failed) {
        public Sampling {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(since, "since");
            Objects.requireNonNull(cpu, "cpu");
            Objects.requireNonNull(failed, "failed");
        }
    }

    /**
     * The services of the admin API that the node's modules bring, and the clock each call of one is given.
     *
     * @param services each module's services, by the module's name
     * @param clock    the node's clock
     */
    public record Modules(Map<String, List<AdminService>> services, Clock clock) {
        /** No services. */
        public static final Modules NONE = new Modules(Map.of(), Clock.systemUTC());

        public Modules {
            services = services.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
            Objects.requireNonNull(clock, "clock");
        }
    }

    private final Config config;
    private final MessageStore messages;
    private final Dlq dlq;
    private final BlobStore blobs;
    private final HealthProbe health;
    private final Optional<Deployments> deployments;
    private final Optional<WorkQueue> work;
    private final Optional<FailedMessages> failed;
    private final Optional<Sampling> sampling;
    private final Optional<PgMessageList> list;
    private final Workers workers;
    private final WorkSignals signals;
    private final Modules modules;
    private volatile Supplier<Future<Void>> undeploy = Future::succeededFuture;

    /** The read API only: no deploy routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health) {
        this(config, messages, dlq, blobs, health, Optional.empty());
    }

    /** The read API and the services of {@code modules}: no deploy routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Modules modules) {
        this(config, messages, dlq, blobs, health, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), new Workers(),
                WorkSignals.NONE, modules);
    }

    /** The read API and, with {@code deployments}, the deploy routes; no work routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments) {
        this(config, messages, dlq, blobs, health, deployments, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), new Workers(), WorkSignals.NONE);
    }

    /** The read API, with {@code deployments} the deploy routes, and the held-work routes over {@code work}; no operator's acts. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work) {
        this(config, messages, dlq, blobs, health, deployments, work, new Workers(), WorkSignals.NONE);
    }

    /**
     * The read API, with {@code deployments} the deploy routes, and the held-work routes over {@code work}, a release waking its
     * flow in {@code workers} and announcing it through {@code signals}; no operator's acts.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.empty(), Optional.empty(), Optional.empty(), workers, signals);
    }

    /**
     * The read API, the held-work routes over {@code work} and, with {@code deployments}, the deploy routes and the flows' CPU,
     * sampled when {@code sampling} is there; no operator's acts.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, Optional<Sampling> sampling) {
        this(config, messages, dlq, blobs, health, deployments, work, sampling, new Workers(), WorkSignals.NONE);
    }

    /**
     * {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, Optional)}, a release waking its
     * flow in {@code workers} and announcing it through {@code signals}; no operator's acts.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, Optional<Sampling> sampling, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.empty(), sampling, Optional.empty(), workers, signals);
    }

    /**
     * The node's server: {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, Optional, Workers,
     * WorkSignals)} and the operator's acts on a parked message over {@code failed} — the cancel, and with {@code deployments} the
     * retry — each waking its flow in {@code workers} and announcing it through {@code signals}.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, FailedMessages failed, Optional<Sampling> sampling, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.of(failed), sampling, Optional.empty(), workers, signals);
    }

    /**
     * {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, FailedMessages, Optional, Workers,
     * WorkSignals)} and the list of recent messages over {@code list}.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, FailedMessages failed, Optional<Sampling> sampling, PgMessageList list, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, work, failed, sampling, list, workers, signals, Modules.NONE);
    }

    /**
     * The node's server with its modules' services:
     * {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, FailedMessages, Optional, PgMessageList,
     * Workers, WorkSignals)} and the services of {@code modules}.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, FailedMessages failed, Optional<Sampling> sampling, PgMessageList list, Workers workers, WorkSignals signals,
                       Modules modules) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.of(failed), sampling, Optional.of(list), workers, signals, modules);
    }

    private AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                        Optional<WorkQueue> work, Optional<FailedMessages> failed, Optional<Sampling> sampling, Optional<PgMessageList> list,
                        Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, work, failed, sampling, list, workers, signals, Modules.NONE);
    }

    private AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                        Optional<WorkQueue> work, Optional<FailedMessages> failed, Optional<Sampling> sampling, Optional<PgMessageList> list,
                        Workers workers, WorkSignals signals, Modules modules) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.health = Objects.requireNonNull(health, "health");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.work = work;
        this.failed = failed;
        this.sampling = Objects.requireNonNull(sampling, "sampling");
        this.list = list;
        this.workers = Objects.requireNonNull(workers, "workers");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.modules = Objects.requireNonNull(modules, "modules");
    }

    /** Deploys the listening verticle; completes with the bound port (the actual one when configured 0). */
    public Future<Integer> start(Vertx vertx) {
        Listener listener = new Listener();
        return vertx.deployVerticle(listener).map(id -> {
            undeploy = () -> vertx.undeploy(id);
            return listener.server.actualPort();
        });
    }

    /** Undeploys the verticle, which closes the socket; a second stop, or one before start, has nothing to do. */
    public Future<Void> stop() {
        Supplier<Future<Void>> running = undeploy;
        undeploy = Future::succeededFuture;
        return running.get();
    }

    /** The verticle: the socket, the router and the context every answer is written on. */
    private final class Listener extends VerticleBase {

        volatile HttpServer server;

        @Override
        public Future<?> start() {
            Router router = Router.router(vertx);
            router.get("/health").handler(offload(AdminServer.this::health));
            router.get("/live").handler(offload(c -> Json.of(200, new JsonObject().put("status", Health.Status.UP.name()))));   // reads nothing
            router.get("/ready").handler(offload(AdminServer.this::ready));
            router.route("/admin/*").handler(AdminServer::browserGuard);   // before the token check
            config.token().ifPresent(token -> router.route("/admin/*").handler(bearer(token)));
            new ModuleCalls(modules, context).mount(router);
            router.get("/admin/messages").handler(offload(AdminServer.this::search));
            router.get("/admin/message-counts").handler(offload(AdminServer.this::counts));
            router.get("/admin/messages/:id").handler(offload(AdminServer.this::timeline));
            router.get("/admin/messages/:id/steps/:n/body").handler(offload(AdminServer.this::body));
            router.get("/admin/dlq").handler(offload(AdminServer.this::dlq));
            deployments.ifPresent(d -> {
                router.put("/admin/packages/:name").handler(upload(d));
                router.delete("/admin/packages/:name").handler(offload(c -> undeployed(c, d)));
                router.get("/admin/flows").handler(offload(c -> flows(d)));
                router.get("/admin/packages").handler(offload(c -> packages(d)));
                router.get("/admin/targets").handler(offload(c -> targets(d)));
                router.get("/admin/components").handler(offload(c -> components(d)));
                router.get("/admin/steps").handler(offload(c -> steps(d)));
                router.get("/admin/flows/:flow/cpu").handler(offload(c -> cpu(c, d)));
            });
            work.ifPresent(q -> {
                router.get("/admin/work/held").handler(offload(c -> held(c, q)));
                router.get("/admin/work/stopped").handler(offload(c -> stopped(c, q)));
                router.post("/admin/work/:seq/release").handler(offload(c -> release(c, q, workers, signals)));
                router.get("/admin/work/lost").handler(offload(c -> lost(c, q)));
                router.post("/admin/work/:seq/discard").handler(offload(c -> discard(c, q, workers, signals)));
            });
            failed.ifPresent(f -> {
                deployments.ifPresent(d -> router.post("/admin/dlq/:id/retry").handler(offload(c -> retry(c, d, f))));
                // the only route that reads a body into memory: bounded, nothing written to disk
                router.post("/admin/dlq/:id/cancel").handler(BodyHandler.create(false).setBodyLimit(CANCEL_BODY_MAX)).handler(offload(c -> cancel(c, f)));
                router.post("/admin/messages/:id/cancel").handler(BodyHandler.create(false).setBodyLimit(CANCEL_BODY_MAX)).handler(offload(c -> end(c, f)));
            });
            ROUTER_ERRORS.forEach((status, reason) -> router.errorHandler(status, ctx -> answer(ctx.response(), Json.of(status, error(reason)))));
            return vertx.createHttpServer(new HttpServerOptions().setHost(config.host()).setPort(config.port())).requestHandler(router).listen()
                    .map(listening -> server = listening);
        }

        @Override
        public Future<?> stop() {
            return server.close();
        }

        /**
         * The route body on a fresh virtual thread — never on the event loop — where the answer is also encoded and its headers
         * checked; it is written back on this verticle's context.
         */
        private Handler<RoutingContext> offload(Route body) {
            return ctx -> Thread.ofVirtual().name("rail8-admin").start(() -> {
                Reply reply = run(body, ctx);
                context.runOnContext(v -> answer(ctx.response(), reply));
            });
        }

        /**
         * {@code PUT /admin/packages/{name}} on the event loop: a declared length above the limit is answered at once (Netty refused
         * a Content-Length that is not a non-negative number before routing; were one to get here, the router answers 500); else
         * the request is paused before a byte of it is read, {@code Expect: 100-continue} is answered, and the route body runs on a
         * virtual thread.
         */
        private Handler<RoutingContext> upload(Deployments deployments) {
            return ctx -> {
                HttpServerRequest req = ctx.request();
                String declared = req.getHeader(HttpHeaders.CONTENT_LENGTH);
                if (declared != null && Long.parseLong(declared) > deployments.maxPackageBytes()) {
                    answerAndClose(ctx, tooLarge(deployments.maxPackageBytes()));
                    return;
                }
                req.pause();
                Upload upload = new Upload(req, deployments.maxPackageBytes());
                // a client that waits for 100 Continue before its body gets it now; one above the limit got its 413 instead
                if (HttpHeaders.CONTINUE.toString().equalsIgnoreCase(req.getHeader(HttpHeaders.EXPECT))) ctx.response().writeContinue();
                Thread.ofVirtual().name("rail8-admin").start(() -> {
                    Reply reply = run(c -> deploy(c, deployments, upload), ctx);
                    context.runOnContext(v -> answerAndClose(ctx, reply));
                });
            };
        }

        /** The body into a temp file in {@code incomingDir()}, then the deploy; the temp file is deleted before the answer, whatever it is. */
        private Reply deploy(RoutingContext ctx, Deployments deployments, Upload body) throws IOException {
            long max = deployments.maxPackageBytes();
            Path incoming = deployments.incomingDir();
            Files.createDirectories(incoming);
            Path upload = Files.createTempFile(incoming, "upload-", ".zip");
            try {
                long received = Future.<Long>future(done -> context.runOnContext(v -> vertx.fileSystem().open(upload.toString(), new OpenOptions().setRead(false).setCreate(false))
                        .compose(body::into).onComplete(done))).await();
                if (received > max) return tooLarge(max);
                DeployResult r = deployments.deploy(ctx.pathParam("name"), Optional.ofNullable(ctx.request().getParam("revision")), upload);
                DeployedPackage p = r.pkg();
                return Json.of(200, new JsonObject().put("package", p.name()).put("version", p.version()).put("revision", p.revision().orElse(null))
                        .put("archive", "sha256:" + p.archiveSha256()).put("flows", new JsonArray(p.flows()))
                        .put("added", new JsonArray(r.added())).put("swapped", new JsonArray(r.swapped())).put("removed", new JsonArray(r.removed())));
            } catch (DeployRefusedException refused) {
                return refusal(refused);
            } finally {
                Files.deleteIfExists(upload);
            }
        }
    }

    /** A route body; it may throw anything — {@link #run} answers it. */
    @FunctionalInterface
    private interface Route {
        Reply apply(RoutingContext ctx) throws Exception;
    }

    private static Reply run(Route body, RoutingContext ctx) {
        try {
            return body.apply(ctx);
        } catch (Refused refused) {   // a bad limit, cursor, key or step number: only what the routes map to Refused
            return Json.of(400, error("bad request: " + refused.getMessage()));
        } catch (Throwable failed) {   // a store that failed, a header no response may carry, a checked one thrown sneakily by an await: a 500, never a hang
            if (PgTx.refusedByThePool(failed)) return busy();
            LOG.log(System.Logger.Level.WARNING, "admin request " + ctx.request().path() + " failed", failed);
            return Json.of(500, error("internal error"));
        }
    }

    /**
     * Writes the answer; when Vert.x refuses it, the request still ends — a 500 before the head is out, a reset after. Completes
     * once the answer is written (or refused).
     */
    private static Future<?> answer(HttpServerResponse res, Reply reply) {
        try {
            return reply.send(res);
        } catch (RuntimeException refused) {
            LOG.log(System.Logger.Level.WARNING, "writing an admin answer failed", refused);
            return fail(res);
        }
    }

    /** {@link #answer}, then, when the request's body was not read to its end, the connection closes: the rest of it is never read. */
    private static void answerAndClose(RoutingContext ctx, Reply reply) {
        HttpServerRequest req = ctx.request();
        answer(ctx.response(), reply).onComplete(written -> {
            if (!req.isEnded()) req.connection().close();
        });
    }

    /** Ends a response that cannot be answered as meant: a 500 {@code {error}} while the head is not out, otherwise a reset. */
    private static Future<?> fail(HttpServerResponse res) {
        if (res.headWritten()) return res.reset();
        return Json.of(500, error("internal error")).send(res);
    }

    /**
     * Lets the request on when it carries {@code Authorization: Bearer <token>} — the scheme in any case (RFC 7235), the token
     * compared with {@link MessageDigest#isEqual}; 401 otherwise.
     */
    private static Handler<RoutingContext> bearer(String token) {
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        return ctx -> {
            String presented = Objects.requireNonNullElse(ctx.request().getHeader("Authorization"), "");
            if (presented.regionMatches(true, 0, BEARER, 0, BEARER.length())
                    && MessageDigest.isEqual(presented.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8), expected)) {
                ctx.next();
            } else {
                answer(ctx.response().putHeader("www-authenticate", "Bearer"), Json.of(401, error("this route needs Authorization: Bearer <token>")));
            }
        };
    }

    /**
     * Lets the request on unless a browser sent it: an {@code Origin} header, whatever its value, or a {@code Sec-Fetch-Site}
     * other than {@code none} (a typed URL) is 403 {@code {error}}. The CLI and curl send neither.
     */
    private static void browserGuard(RoutingContext ctx) {
        HttpServerRequest req = ctx.request();
        String site = req.getHeader("Sec-Fetch-Site");
        if (req.getHeader(HttpHeaders.ORIGIN) == null && (site == null || site.equals("none"))) {
            ctx.next();
        } else {
            answer(ctx.response(), Json.of(403, error("the admin API does not answer a browser: a request with Origin or Sec-Fetch-Site other than none is refused")));
        }
    }

    /** A request the API refuses: a 400 naming the reason. Only a bad parameter becomes one, never a store's other failure. */
    private static final class Refused extends RuntimeException {
        Refused(String reason) {
            super(reason, null, false, false);
        }
    }

    /** {@code call}'s IllegalArgumentException — a parameter that does not parse, or a page store refusing a limit or cursor — as a 400. */
    private static <T> T refusable(Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException refused) {
            throw new Refused(refused.getMessage());
        }
    }

    // --- routes (on the request's virtual thread)

    private Reply health(RoutingContext ctx) {
        Map<String, Future<Health>> pending = new TreeMap<>();
        health.checks().forEach((name, check) -> pending.put(name, check.get().otherwise(Health.down(""))));
        JsonObject checks = new JsonObject();
        pending.forEach((name, answer) -> checks.put(name, answer.await().status().name()));
        boolean up = !checks.getMap().containsValue(Health.Status.DOWN.name());
        JsonObject answer = new JsonObject().put("status", (up ? Health.Status.UP : Health.Status.DOWN).name()).put("checks", checks)
                .put("snapshotWriteFailures", health.snapshotWriteFailures().getAsLong());
        if (!health.notices().isEmpty()) answer.put("notices", new JsonArray(health.notices()));
        return Json.of(up ? 200 : 503, answer);
    }

    /** {@code /ready}: each input read now, none awaited; 200 when all are UP, else 503, each named. */
    private Reply ready(RoutingContext ctx) {
        Readiness r = health.readiness();
        JsonObject checks = new JsonObject().put("lifecycle", status(r.lifecycle())).put("fence", status(r.fence()))
                .put("journal", status(r.journal())).put("deployments", status(r.deployments()));
        boolean up = !checks.getMap().containsValue(Health.Status.DOWN.name());
        return Json.of(up ? 200 : 503, new JsonObject().put("status", (up ? Health.Status.UP : Health.Status.DOWN).name()).put("checks", checks));
    }

    private static String status(BooleanSupplier input) {
        return (input.getAsBoolean() ? Health.Status.UP : Health.Status.DOWN).name();
    }

    private Reply timeline(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        List<Snapshot> timeline = messages.timeline(id).await();
        if (timeline.isEmpty()) return Json.of(404, error("no message " + id + " recorded"));
        JsonArray steps = new JsonArray();
        for (Snapshot s : timeline) steps.add(step(s));
        return Json.of(200, new JsonObject().put("id", id).put("flow", timeline.getFirst().flow()).put("steps", steps));
    }

    private Reply body(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        int n = refusable(() -> Integer.parseInt(ctx.pathParam("n")));
        OptionalInt attempt = attempt(ctx);
        // the timeline is in attempt order: the last row of step n is its latest attempt's
        Snapshot at = messages.timeline(id).await().stream().filter(s -> s.step() == n && (attempt.isEmpty() || s.attempt() == attempt.getAsInt()))
                .reduce((earlier, later) -> later).orElse(null);
        if (at == null) {
            return Json.of(404, error(attempt.isEmpty() ? "no step " + n + " recorded for message " + id
                    : "no step " + n + " of attempt " + attempt.getAsInt() + " recorded for message " + id));
        }
        if (at.bodyKey().isEmpty()) return Json.of(404, error("step " + n + " of message " + id + " was recorded as metadata only: hash and size, no bytes"));
        // the head first: a recorded content-type no response may carry is refused here (IllegalArgumentException, a 500), before any stream is opened
        MultiMap head = HttpHeaders.headers().set("content-type", at.headers().getOrDefault("content-type", OCTETS)).set("content-length", Long.toString(at.bodySize()));
        String gone = "the bytes of step " + n + " of message " + id + " are no longer stored (removed by retention)";
        BlobKey key = at.bodyKey().get();
        // existence as Postgres names it, never a node's location index: no object is retention's removal;
        // bytes that go after this check are gone too — an open that throws (a store that finds the key synchronously) or a
        // stream that fails before its first chunk (a store that opens lazily) answers the same 404
        boolean stored;
        try {
            stored = blobs.exists(key).await();
        } catch (BodyLostException lost) {   // given up by an accepted loss: stored once, never readable again
            return Json.of(410, error("the bytes of step " + n + " of message " + id + " are gone for good: " + lost.getMessage()));
        }
        if (!stored) return Json.of(404, error(gone));
        ReadStream<Buffer> bytes;
        try {
            bytes = blobs.open(key);
        } catch (BlobNotFoundException missing) {   // removed between the check and the open
            return Json.of(404, error(gone));
        }
        return new Bytes(head, bytes, gone);
    }

    /**
     * {@code key=name:value}, split at the first colon: a value may contain colons, a name may not; or {@code flow} and
     * {@code step} together, never with a key.
     */
    private Reply search(RoutingContext ctx) {
        if (ctx.request().getParam("from") != null || ctx.request().getParam("to") != null) return recent(ctx);
        String flow = ctx.request().getParam("flow");
        String step = ctx.request().getParam("step");
        if (flow != null || step != null) {
            if (ctx.request().getParam("key") != null) throw new Refused("search by key or by flow and step, not both");
            if (flow == null || flow.isEmpty() || step == null || step.isEmpty()) throw new Refused("a search by step names both flow and step");
            return Json.of(200, page(refusable(() -> messages.findByStep(flow, step, after(ctx), limit(ctx)).await()), id -> id));
        }
        String key = Objects.requireNonNullElse(ctx.request().getParam("key"), "");
        int colon = key.indexOf(':');
        if (colon < 0) throw new Refused("key must be name:value");
        Page<String> page = refusable(() -> messages.findByKey(key.substring(0, colon), key.substring(colon + 1), after(ctx), limit(ctx)).await());
        return Json.of(200, page(page, id -> id));
    }

    /** The list of recent messages: {@code from} and {@code to}, and the optional {@code flow}, {@code status}, {@code key} and {@code step}. */
    private Reply recent(RoutingContext ctx) {
        PgMessageList recent = list.orElseThrow(() -> new Refused("this server does not list messages by time"));
        Optional<PgMessageList.Status> status = given(ctx, "status").map(s -> switch (s) {
            case "completed" -> PgMessageList.Status.COMPLETED;
            case "failed" -> PgMessageList.Status.FAILED;
            case "running" -> PgMessageList.Status.RUNNING;
            case "cancelled" -> PgMessageList.Status.CANCELLED;
            default -> throw new Refused("status must be completed, failed, running or cancelled, was '" + s + "'");
        });
        Optional<PgMessageList.Key> key = given(ctx, "key").map(k -> {
            int colon = k.indexOf(':');
            if (colon < 0) throw new Refused("key must be name:value");
            return new PgMessageList.Key(k.substring(0, colon), k.substring(colon + 1));
        });
        PgMessageList.Query query = refusable(() -> new PgMessageList.Query(instant(ctx, "from"), instant(ctx, "to"), given(ctx, "flow"), status, key,
                given(ctx, "step")));
        return Json.of(200, page(refusable(() -> recent.list(query, after(ctx), limit(ctx)).await()), AdminServer::listed));
    }

    /** How many messages of the list of recent messages are in each status: {@code from} and {@code to}, and the optional {@code flow}. */
    private Reply counts(RoutingContext ctx) {
        PgMessageList recent = list.orElseThrow(() -> new Refused("this server does not count messages by time"));
        Map<PgMessageList.Status, Long> counts = refusable(() -> recent.counts(instant(ctx, "from"), instant(ctx, "to"), given(ctx, "flow")).await());
        JsonObject answer = new JsonObject();
        counts.forEach((status, n) -> answer.put(status.name().toLowerCase(Locale.ROOT), n));
        return Json.of(200, answer);
    }

    /** The parameter {@code name}, empty when it is missing or empty. */
    private static Optional<String> given(RoutingContext ctx, String name) {
        return Optional.ofNullable(ctx.request().getParam(name)).filter(value -> !value.isEmpty());
    }

    /** The ISO-8601 instant in parameter {@code name}; a missing one or one that does not parse is a 400. */
    private static Instant instant(RoutingContext ctx, String name) {
        String raw = ctx.request().getParam(name);
        if (raw == null) throw new Refused(name + " is required: an ISO-8601 instant");
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException notAnInstant) {
            throw new Refused(name + " must be an ISO-8601 instant, was '" + raw + "'");
        }
    }

    private Reply dlq(RoutingContext ctx) {
        return Json.of(200, page(refusable(() -> dlq.list(after(ctx), limit(ctx)).await()), AdminServer::parked));
    }

    private static Reply held(RoutingContext ctx, WorkQueue work) {
        return Json.of(200, page(refusable(() -> work.held(after(ctx), limit(ctx)).await()), AdminServer::held));
    }

    private static Reply stopped(RoutingContext ctx, WorkQueue work) {
        return Json.of(200, page(refusable(() -> work.stopped(after(ctx), limit(ctx)).await()), AdminServer::stopped));
    }

    /**
     * 200 {@code {seq}} for a held row, released: its flow woken here (every flow when untold) and announced to its body's node ({@code
     * *} when untold), the release committed; 404 for any other seq the queue does not release; a seq that is not a long never reaches it.
     */
    private static Reply release(RoutingContext ctx, WorkQueue work, Workers workers, WorkSignals signals) {
        long seq = refusable(() -> Long.parseLong(ctx.pathParam("seq")));
        Optional<WorkQueue.Released> released = work.releaseRow(seq).await();
        if (released.isEmpty()) return Json.of(404, error("no held work item " + seq));
        WorkQueue.Released row = released.get();
        row.flow().ifPresentOrElse(workers::wake, workers::wakeAll);
        announce(signals, row.bodyNode().orElse(null), row.flow().orElse(null), "released work item " + seq);
        return Json.of(200, new JsonObject().put("seq", seq));
    }

    /** Announces {@code flow} to {@code target} after a change committed: a throw is logged, the answer stands. */
    private static void announce(WorkSignals signals, String target, String flow, String what) {
        try {
            signals.announce(target, flow);
        } catch (RuntimeException | Error thrown) {   // the change committed: its answer stands, the body's node finds the row by reconciliation
            LOG.log(System.Logger.Level.WARNING, "announcing " + what + " failed: " + thrown, thrown);
        }
    }

    private static Reply lost(RoutingContext ctx, WorkQueue work) {
        return Json.of(200, page(refusable(() -> work.lost(after(ctx), limit(ctx)).await()), AdminServer::lost));
    }

    /**
     * 200 {@code {seq, auditId}} for a lost row, discarded and audited: its flow woken here and, when its key has a next row
     * whose node is known, that node announced, after the commit; 404 for any other seq (nothing deleted, nothing audited); a seq
     * that is not a long or an actor over {@link #ACTOR_MAX} characters is 400 before the queue is asked.
     */
    private static Reply discard(RoutingContext ctx, WorkQueue work, Workers workers, WorkSignals signals) {
        long seq = refusable(() -> Long.parseLong(ctx.pathParam("seq")));
        String actor = actor(ctx.request());
        Optional<WorkQueue.Discarded> discarded = work.discard(seq, actor, ctx.request().remoteAddress().hostAddress()).await();
        if (discarded.isEmpty()) return Json.of(404, error("no lost work item " + seq));
        WorkQueue.Discarded d = discarded.get();
        workers.wake(d.flow());
        d.nextNode().ifPresent(node -> announce(signals, node, d.flow(), "the key's next row after discarded work item " + seq));
        return Json.of(200, new JsonObject().put("seq", seq).put("auditId", d.auditId()));
    }

    /**
     * The audit actor: the request's {@value #ACTOR} header, stripped — self-declared under the shared token, never
     * authenticated — {@code unknown} when absent or blank; over {@link #ACTOR_MAX} characters a 400.
     */
    private static String actor(HttpServerRequest req) {
        String actor = Objects.requireNonNullElse(req.getHeader(ACTOR), "").strip();
        if (actor.length() > ACTOR_MAX) throw new Refused(ACTOR + " is at most " + ACTOR_MAX + " characters, was " + actor.length());
        return actor.isEmpty() ? "unknown" : actor;
    }

    /**
     * {@code POST /admin/dlq/{id}/retry}: the parked message runs once more under its own id, from step 1, as attempt k + 1 of its
     * own bound. An actor over {@link #ACTOR_MAX} characters is 400; 404 without a DLQ row; 409 when its flow is not deployed
     * here; 422 when its flow is not at-least-once, when its worker did not park it — a row at a step other than 1, one without an
     * {@code attempt} k of at least 1 — or when its flow is keyed ({@code partitionBy}) and the row kept no key (parked before V17);
     * nothing written for any of them; each 422 says why and names no other route. Then {@link FailedMessages#retry}, audited: a
     * message that stops its key runs in place, ahead of the rows behind it, any other at the tail of its key. The row gone
     * meanwhile is 404, a work row of the message there and not stopped, or a stopped row whose body is lost, 409 naming that work
     * item, an ingress body no longer kept 409; nothing changed, nothing woken or announced. 202 {@code {id, attempt: k + 1}} once
     * it committed: the flow woken here and announced to the node of the row's body (every node when the act names none). A body
     * sent to it is not read.
     */
    private Reply retry(RoutingContext ctx, Deployments deployments, FailedMessages failed) {
        String id = ctx.pathParam("id");
        FailedMessages.Actor by = by(ctx.request());
        Optional<Parked> found = dlq.get(id).await();
        if (found.isEmpty()) return Json.of(404, error("no DLQ row for message " + id));
        Parked row = found.get();
        String flow = row.flow();
        Optional<FlowDefinition> def = deployments.definition(flow);
        if (def.isEmpty()) return Json.of(409, error("flow " + flow + " is not deployed on this node; message " + id + " is not restarted"));
        if (!"at-least-once".equals(def.get().deliveryMode())) {
            return Json.of(422, error("message " + id + " of flow " + flow + " is not restarted: the flow is " + def.get().deliveryMode()
                    + ", only an at-least-once flow's worker runs a restart"));
        }
        Object attempt = row.props().asMap().get(Envelope.ATTEMPT.name());
        if (row.step() != 1 || !(attempt instanceof Integer k) || k < 1) {
            return Json.of(422, error("message " + id + " was not parked by its flow's worker (step " + row.step() + ", attempt " + attempt
                    + "): it cannot be restarted"));
        }
        if (def.get().partitionBy().isPresent() && row.partitionKey().isEmpty()) {
            return Json.of(422, error("message " + id + " of the keyed flow " + flow + " was parked before its key was kept"));
        }
        FailedMessages.Act act;
        try {
            act = failed.retry(id, flow, row.partitionKey().orElse(null), k, by).await();
        } catch (BlobNotFoundException | BodyLostException gone) {
            return Json.of(409, error("message " + id + " is not restarted: its ingress body is no longer kept (" + gone.getMessage() + ")"));
        }
        return switch (act) {
            case FailedMessages.Unsettled u -> Json.of(409, error("message " + id + " is not restarted: its work item " + u.seq()
                    + " is not settled yet, or its body is lost"));
            case FailedMessages.NotParked n -> Json.of(404, error("no DLQ row for message " + id + " any more"));
            case FailedMessages.Stopping s -> throw new IllegalStateException("a retry answered " + s);
            case FailedMessages.Done done -> {
                workers.wake(flow);
                announce(signals, done.node().orElse(null), flow, "restarted message " + id);
                yield Json.of(202, new JsonObject().put("id", id).put("attempt", k + 1));
            }
        };
    }

    /**
     * {@code POST /admin/dlq/{id}/cancel}: the parked message ends — its DLQ row deleted and, when it stops its key, its stopped
     * work row too, so the key's next row runs — and one audit row records it, in one transaction ({@link FailedMessages#cancel}).
     * It reads no deployed flow: every parked message can be cancelled. The body is the reason: none, or {@code {"reason": "..."}}
     * of 1 to {@link FailedMessages#REASON_MAX} characters after stripping and nothing else, else 400; above
     * {@link #CANCEL_BODY_MAX} bytes 413; an actor over {@link #ACTOR_MAX} characters 400 — each before anything is read. 404
     * without a DLQ row; 409 while a work row of the message is there and not stopped, naming it; nothing written. 200
     * {@code {id, auditId, cancelledAt}} once it committed: the flow woken here and announced to the node of its key's next row
     * when there is one.
     */
    private Reply cancel(RoutingContext ctx, FailedMessages failed) {
        String id = ctx.pathParam("id");
        FailedMessages.Actor by = by(ctx.request());
        Optional<String> reason = reason(ctx.request(), ctx.body());
        FailedMessages.Act act = failed.cancel(id, by, reason).await();
        return switch (act) {
            case FailedMessages.Unsettled u -> Json.of(409, error("message " + id + " is not cancelled: its work item " + u.seq() + " is not settled yet"));
            case FailedMessages.NotParked n -> Json.of(404, error("no DLQ row for message " + id));
            case FailedMessages.Stopping s -> throw new IllegalStateException("a DLQ cancel answered " + s);
            case FailedMessages.Done done -> Json.of(200, cancelled(id, done));
        };
    }

    /**
     * A parked message cancelled: its flow woken here and announced to the node of its key's next row when there is one. Answers
     * {@code {id, auditId, cancelledAt}}.
     */
    private JsonObject cancelled(String id, FailedMessages.Done done) {
        FailedMessages.Event e = done.event();
        workers.wake(e.flow());
        done.node().ifPresent(node -> announce(signals, node, e.flow(), "the key's next row after cancelled message " + id));
        return new JsonObject().put("id", id).put("auditId", e.id()).put("cancelledAt", e.at().toString());
    }

    /**
     * {@code POST /admin/messages/{id}/cancel}: the at-least-once message ends wherever it is ({@link FailedMessages#end}), with the
     * body, the actor and their 400 and 413 as {@code POST /admin/dlq/{id}/cancel}. A parked message is cancelled as there: 200
     * {@code {id, auditId, cancelledAt, state: "ended"}}. One not parked whose work row is queued, waiting or leased has the row
     * marked: its run stops, the one going on this node at once (with the cancel's actor and reason), one on another node at that
     * node's next lease renewal, one not running at its row's next claim, which a wake here and an announce to every node bring
     * on. 202 {@code {id, auditId, cancelledAt, state: "stopping", node}}, {@code node} the holder of the row's live lease, null
     * when none holds it; a repeat answers the first cancel. 409 naming the work item when it is held, stopped without a DLQ row,
     * lost, or not settled beside a DLQ row; 404 when the message has neither: sync and at-most-once runs are not cancelled.
     */
    private Reply end(RoutingContext ctx, FailedMessages failed) {
        String id = ctx.pathParam("id");
        FailedMessages.Actor by = by(ctx.request());
        Optional<String> reason = reason(ctx.request(), ctx.body());
        FailedMessages.Act act = failed.end(id, by, reason).await();
        return switch (act) {
            case FailedMessages.Unsettled u -> Json.of(409, error("message " + id + " is not cancelled: its work item " + u.seq()
                    + " is held, lost, or not settled yet"));
            case FailedMessages.NotParked n -> Json.of(404, error("no queued, running or parked at-least-once message " + id
                    + ": sync and at-most-once runs are not cancelled; their time limit ends them"));
            case FailedMessages.Done done -> Json.of(200, cancelled(id, done).put("state", "ended"));
            case FailedMessages.Stopping s -> {
                FailedMessages.Event e = s.event();
                deployments.ifPresent(d -> d.stop(id, new Runs.Cancelled(e.actor(), e.reason())));
                if (s.node().isEmpty()) {
                    workers.wake(e.flow());
                    announce(signals, null, e.flow(), "cancelled message " + id);
                }
                yield Json.of(202, new JsonObject().put("id", id).put("auditId", e.id()).put("cancelledAt", e.at().toString())
                        .put("state", "stopping").put("node", s.node().orElse(null)));
            }
        };
    }

    /**
     * A cancel's reason: no body is none; any other body is {@code {"reason": "<text>"}} with no other member, the text 1 to
     * {@link FailedMessages#REASON_MAX} characters after stripping, else a 400 — an empty object and a null reason among them.
     * Multipart form data is such a 400 too, never a cancel without a reason: the body handler parses it as a form and buffers
     * none of it, so it would read as no body.
     */
    private static Optional<String> reason(HttpServerRequest req, RequestBody body) {
        String shape = "the body is none or {\"reason\": \"<1 to " + FailedMessages.REASON_MAX + " characters>\"}";
        String type = req.getHeader(HttpHeaders.CONTENT_TYPE);
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) throw new Refused(shape + ": it is a form");
        if (body.isEmpty()) return Optional.empty();
        Object json;
        try {
            json = io.vertx.core.json.Json.decodeValue(body.buffer());
        } catch (DecodeException notJson) {
            throw new Refused(shape + ": it is not JSON");
        }
        if (!(json instanceof JsonObject o) || o.size() != 1 || !(o.getValue("reason") instanceof String text)) throw new Refused(shape);
        return refusable(() -> FailedMessages.reason(Optional.of(text)));
    }

    /** The actor of an act: {@link #actor} and the client's address. */
    private static FailedMessages.Actor by(HttpServerRequest req) {
        return new FailedMessages.Actor(actor(req), req.remoteAddress().hostAddress());
    }

    /** The {@code attempt} parameter: empty when missing, else an int of at least 1, else (not an int, below 1) a 400. */
    private static OptionalInt attempt(RoutingContext ctx) {
        String raw = ctx.request().getParam("attempt");
        if (raw == null) return OptionalInt.empty();
        int attempt;
        try {
            attempt = Integer.parseInt(raw);
        } catch (NumberFormatException notAnInt) {
            attempt = 0;
        }
        if (attempt < 1) throw new Refused("attempt must be an attempt number of at least 1, was '" + raw + "'");
        return OptionalInt.of(attempt);
    }

    private static Optional<String> after(RoutingContext ctx) {
        return Optional.ofNullable(ctx.request().getParam("after"));
    }

    /** The {@code limit} parameter, {@link #DEFAULT_LIMIT} without one; the store refuses one outside 1..500, a non-number is a NumberFormatException. */
    private static int limit(RoutingContext ctx) {
        String limit = ctx.request().getParam("limit");
        return limit == null ? DEFAULT_LIMIT : Integer.parseInt(limit);
    }

    // --- JSON

    /**
     * {@code GET /admin/flows}: every flow deployed here with its state; a flow whose consumer waits for its next attempt also
     * carries {@code restart}: its failures in a row, when the next attempt is due and the last failure's cause.
     */
    private static Reply flows(Deployments deployments) {
        JsonArray items = new JsonArray();
        Map<String, Deployments.Restarting> restarting = deployments.restarting();
        for (DeployedFlow f : deployments.flows()) {
            JsonObject item = new JsonObject().put("id", f.id()).put("package", f.pkg()).put("packageVersion", f.packageVersion()).put("revision", f.revision().orElse(null))
                    .put("flowVersion", f.flowVersion()).put("delivery", f.delivery()).put("hash", f.hash()).put("state", f.state().name());
            Deployments.Restarting r = restarting.get(f.id());
            if (r != null) item.put("restart", new JsonObject().put("failures", r.failures()).put("next", r.next().toString()).put("cause", r.cause()));
            items.add(item);
        }
        return Json.of(200, new JsonObject().put("items", items));
    }

    /**
     * {@code GET /admin/flows/{flow}/cpu}: 404 for a flow not deployed here; else the sampler's rows of the flow and its totals,
     * sampling on or, once its stream failed, failed; or {@code sampling:"off"} without a sampler.
     */
    private Reply cpu(RoutingContext ctx, Deployments deployments) {
        String flow = ctx.pathParam("flow");
        if (deployments.flows().stream().noneMatch(f -> f.id().equals(flow))) return Json.of(404, error("no flow " + flow + " deployed on this node"));
        JsonObject answer = new JsonObject().put("flow", flow);
        if (sampling.isEmpty()) return Json.of(200, answer.put("sampling", "off").put("items", new JsonArray()));
        StepCpu cpu = sampling.get().cpu();
        JsonArray items = new JsonArray();
        for (StepCpu.Row r : cpu.rows(flow)) {
            items.add(new JsonObject().put("step", r.step()).put("name", r.name()).put("executions", r.executions()).put("samples", r.samples())
                    .put("cpuNanos", r.cpuNanos()));
        }
        StepCpu.Totals t = cpu.totals();
        JsonObject totals = new JsonObject().put("samples", t.samples()).put("attributed", t.attributed()).put("pending", t.pending())
                .put("unattributed", t.unattributed()).put("platform", t.platform()).put("lost", t.lost()).put("dropped", t.dropped());
        return Json.of(200, answer.put("sampling", sampling.get().failed().getAsBoolean() ? "failed" : "on").put("period", sampling.get().period().toString()).put("since", sampling.get().since().toString())
                .put("items", items).put("totals", totals));
    }

    /**
     * {@code GET /admin/components}: the node's environment and advertised origin, then every component the node serves, sorted by
     * scheme, with its HTTP prefix and its settings description.
     */
    private static Reply components(Deployments deployments) {
        JsonArray items = new JsonArray();
        deployments.settings().forEach((scheme, settings) -> items.add(new JsonObject().put("scheme", scheme)
                .put("httpPrefix", deployments.httpPrefix(scheme).orElse(null)).put("settings", new JsonObject(settings.toJson()))));
        return Json.of(200, new JsonObject().put("environment", deployments.environment()).put("httpOrigin", deployments.httpOrigin().orElse(null))
                .put("items", items));
    }

    /**
     * {@code GET /admin/steps}: every step type a module or plugin of the node builds, sorted by type, with the JSON Schema its
     * step's options are checked against; {@code configSchema} is null when the factory's schema is no JSON object.
     */
    private static Reply steps(Deployments deployments) {
        JsonArray items = new JsonArray();
        deployments.stepSchemas().forEach((type, schema) -> items.add(new JsonObject().put("type", type).put("configSchema", schemaObject(schema))));
        return Json.of(200, new JsonObject().put("items", items));
    }

    /** {@code text} as a JSON object, or null when it is none. */
    private static JsonObject schemaObject(String text) {
        try {
            return new JsonObject(text);
        } catch (RuntimeException notAnObject) {
            return null;
        }
    }

    /** {@code GET /admin/targets}: the gauge of every target the deployed flows named, sorted by target. */
    private static Reply targets(Deployments deployments) {
        JsonArray items = new JsonArray();
        for (TargetLimit.Gauge g : deployments.targets()) {
            items.add(new JsonObject().put("target", g.target()).put("maxCalls", g.maxCalls()).put("maxWaitMs", g.maxWait().toMillis())
                    .put("inFlight", g.inFlight()).put("waiting", g.waiting()).put("refused", g.refused()));
        }
        return Json.of(200, new JsonObject().put("items", items));
    }

    private static Reply packages(Deployments deployments) {
        JsonArray items = new JsonArray();
        for (DeployedPackage p : deployments.packages()) {
            JsonArray files = new JsonArray();
            for (DeployedPackage.PackageFile f : p.files()) files.add(new JsonObject().put("path", f.path()).put("sha256", f.sha256()));
            JsonArray folders = new JsonArray();   // every folder below the root, null where its folder.yaml sets nothing
            p.folders().forEach(f -> folders.add(new JsonObject().put("path", f.path()).put("namespace", f.namespace().orElse(null))
                    .put("description", f.description().orElse(null))));
            JsonArray artifacts = new JsonArray();   // a test's id is null
            p.artifacts().forEach(a -> artifacts.add(new JsonObject().put("path", a.path()).put("kind", a.kind()).put("id", a.id().orElse(null))));
            JsonArray dependencies = new JsonArray();   // the closure, each marked direct or not
            p.dependencies().forEach(d -> dependencies.add(new JsonObject().put("name", d.name()).put("version", d.version()).put("content", d.content())
                    .put("archive", "sha256:" + d.archiveSha256()).put("direct", d.direct())));
            items.add(new JsonObject().put("name", p.name()).put("version", p.version()).put("revision", p.revision().orElse(null))
                    .put("archive", "sha256:" + p.archiveSha256()).put("deployedAt", p.deployedAt().toString()).put("flows", new JsonArray(p.flows())).put("files", files)
                    .put("folders", folders).put("artifacts", artifacts).put("state", p.state().name()).put("problems", new JsonArray(p.problems()))
                    .put("content", p.content()).put("dependencies", dependencies));
        }
        return Json.of(200, new JsonObject().put("items", items));
    }

    /**
     * {@code DELETE /admin/packages/{name}}: the undeploy's result, or its refusal (the pool's refusal of its queue question or its
     * record delete included: 503); a record delete or a queue question that fails otherwise throws, a 500.
     */
    private static Reply undeployed(RoutingContext ctx, Deployments deployments) throws IOException {
        try {
            UndeployResult r = deployments.undeploy(ctx.pathParam("name"));
            return Json.of(200, new JsonObject().put("name", r.name()).put("version", r.version()).put("flowsRemoved", new JsonArray(r.flowsRemoved())));
        } catch (DeployRefusedException refused) {
            return refusal(refused);
        }
    }

    /**
     * A refusal by its kind: 400, 403, 404, 409, 422 (with every problem), 500 (with every problem of a redeploy rolled back), 503
     * (the node stops; the pool refused the record's write, with every problem of a redeploy rolled back, a deploy's or an
     * undeploy's queue question, or an undeploy's record delete).
     */
    private static Reply refusal(DeployRefusedException refused) {
        int status = switch (refused.kind()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case CONFLICT -> 409;
            case INVALID -> 422;
            case FAILED -> 500;
            case STOPPING, BUSY -> 503;
            case NOT_FOUND -> 404;
        };
        JsonObject body = error(refused.error());
        if (!refused.problems().isEmpty()) body.put("problems", new JsonArray(refused.problems()));
        return Json.of(status, body);
    }

    /** 503 for a request the node's Postgres pool refused a connection: a fixed text, the client's message not shown. */
    private static Reply busy() {
        return Json.of(503, error("the database pool is busy; retry"));
    }

    private static Reply tooLarge(long max) {
        return Json.of(413, error("the package is above RAIL8_MAX_PACKAGE_BYTES " + max + " bytes"));
    }

    private static <T> JsonObject page(Page<T> page, Function<T, Object> item) {
        JsonArray items = new JsonArray();
        for (T t : page.items()) items.add(item.apply(t));
        return new JsonObject().put("items", items).put("next", page.next().orElse(null));
    }

    private static JsonObject step(Snapshot s) {
        return new JsonObject()
                .put("step", s.step())
                .put("attempt", s.attempt())
                .put("name", s.stepName())
                .put("outcome", s.outcome().name())
                .put("error", s.error().orElse(null))
                .put("startedAt", s.startedAt().toString())
                .put("wallNanos", s.wallNanos())
                .put("cpuNanos", s.cpuNanos() == Snapshot.CPU_UNKNOWN ? null : s.cpuNanos())
                .put("headers", new JsonObject(new HashMap<>(s.headers().asMap())))
                .put("props", props(s.props().asMap()))
                .put("keys", new JsonObject(new HashMap<>(s.keys().asMap())))
                .put("body", new JsonObject().put("hash", hash(s.bodyHash())).put("size", s.bodySize()).put("kept", s.bodyKey().isPresent()))
                .put("versions", new JsonObject(new HashMap<>(s.versions())))
                .put("log", StepLogJson.json(s.log()));
    }

    private static JsonObject listed(PgMessageList.Listed m) {
        return new JsonObject()
                .put("id", m.id())
                .put("flow", m.flow())
                .put("status", m.status().name().toLowerCase(Locale.ROOT))
                .put("startedAt", m.startedAt().toString())
                .put("durationNanos", m.durationNanos())
                .put("keys", new JsonObject(new HashMap<>(m.keys())));
    }

    private static JsonObject parked(Parked p) {
        return new JsonObject()
                .put("id", p.messageId())
                .put("flow", p.flow())
                .put("step", p.step())
                .put("name", p.stepName())
                .put("error", p.error())
                .put("parkedAt", p.parkedAt().toString())
                .put("headers", new JsonObject(new HashMap<>(p.headers().asMap())))
                .put("props", props(p.props().asMap()))
                .put("keys", new JsonObject(new HashMap<>(p.keys().asMap())))
                .put("body", new JsonObject().put("hash", hash(p.bodyHash())).put("size", p.bodySize()))
                .put("versions", new JsonObject(new HashMap<>(p.versions())));
    }

    private static JsonObject held(WorkQueue.Held h) {
        WorkQueue.Item i = h.item();
        return new JsonObject()
                .put("seq", i.seq())
                .put("messageId", i.messageId())
                .put("flow", i.flow())
                .put("step", i.step())
                .put("partitionKey", i.partitionKey())
                .put("attempts", i.attempts())
                .put("heldAt", h.heldAt().toString())
                .put("blocks", h.blocks());
    }

    private static JsonObject stopped(WorkQueue.Stopped s) {
        WorkQueue.Item i = s.item();
        return new JsonObject()
                .put("seq", i.seq())
                .put("messageId", i.messageId())
                .put("flow", i.flow())
                .put("step", i.step())
                .put("partitionKey", i.partitionKey())
                .put("attempts", i.attempts())
                .put("stoppedAt", s.stoppedAt().toString())
                .put("blocks", s.blocks())
                .put("error", s.error().orElse(null));
    }

    private static JsonObject lost(WorkQueue.Lost l) {
        WorkQueue.Item i = l.item();
        return new JsonObject()
                .put("seq", i.seq())
                .put("messageId", i.messageId())
                .put("flow", i.flow())
                .put("partitionKey", i.partitionKey())
                .put("lostAt", l.lostAt().toString())
                .put("blocks", l.blocks());
    }

    private static String hash(Hash h) {
        return h.algorithm() + ":" + h.hex();
    }

    /** Props as JSON: JSON-native values as they are, anything else by its string form (as the Postgres store keeps them). */
    private static JsonObject props(Map<String, Object> props) {
        JsonObject o = new JsonObject();
        props.forEach((k, v) -> o.put(k, v instanceof Number || v instanceof Boolean || v instanceof String ? v : String.valueOf(v)));
        return o;
    }

    private static JsonObject error(String message) {
        return new JsonObject().put("error", message);
    }

    // --- answers (on the verticle's context)

    private interface Reply {
        /** Sends the answer; completes once it is written. */
        Future<?> send(HttpServerResponse res);
    }

    /** A JSON answer, encoded where it is built (the route's virtual thread); its content-length overrides any set before. */
    private record Json(int status, Buffer body) implements Reply {
        static Json of(int status, JsonObject body) {
            return new Json(status, body.toBuffer());
        }

        @Override
        public Future<?> send(HttpServerResponse res) {
            return res.setStatusCode(status).putHeader("content-type", JSON).putHeader("content-length", Integer.toString(body.length())).end(body);
        }
    }

    /**
     * The stored bytes, streamed with back-pressure under the head checked on the virtual thread (content-type and length
     * from the snapshot). A stream that fails is never a complete answer: before the head is out a 500 — or, when it fails
     * with a {@link BlobNotFoundException}, the store holding nothing under the key (a durable tier opens lazily and finds
     * no row only when read), the 404 {@code gone} — after it a reset, whatever the failure.
     */
    private record Bytes(MultiMap head, ReadStream<Buffer> bytes, String gone) implements Reply {
        @Override
        public Future<?> send(HttpServerResponse res) {
            res.setStatusCode(200).headers().setAll(head);
            return bytes.pipe().endOnFailure(false).to(res).onFailure(failed -> {
                if (failed instanceof BlobNotFoundException && !res.headWritten()) {
                    Json.of(404, error(gone)).send(res);
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, "streaming a stored body failed", failed);
                fail(res);
            });
        }
    }

    /**
     * One upload, on the verticle's context: the request's body into a file, counted, one write in flight at a time — the request
     * is paused from each chunk until its write is done, so what arrives meanwhile waits in the request and then in the socket. It
     * finishes once: at the request's end, at byte {@code max}+1 (the request stays paused: the rest is never read), or at the
     * first failure of the request or a write. Finishing closes the file and completes with the bytes counted, or fails with that
     * first failure. It is made on the route, with the request paused, so a failure of the request (the client hangs up) before
     * the file is open is the upload's failure all the same; after a failure the rest of the body is dropped unread, so no write
     * reaches the file once it closes.
     */
    private static final class Upload {
        private final HttpServerRequest req;
        private final long max;
        private final Promise<Long> received = Promise.promise();
        private final Promise<Void> finished = Promise.promise();
        private AsyncFile file;   // on the verticle's context only, as count is
        private long count;

        /** On the route, the request paused: no chunk arrives before {@link #into} resumes it. */
        Upload(HttpServerRequest req, long max) {
            this.req = req;
            this.max = max;
            req.exceptionHandler(this::failed);
            req.endHandler(end -> finished.tryComplete());
            req.handler(this::chunk);
        }

        /** Pipes the body into {@code file}, open for writing; completes as the class comment says. */
        Future<Long> into(AsyncFile file) {
            this.file = file;
            finished.future().compose(v -> file.close()).onSuccess(closed -> received.tryComplete(count)).onFailure(received::tryFail);
            req.resume();
            return received.future();
        }

        private void chunk(Buffer chunk) {
            count += chunk.length();
            req.pause();
            if (count > max) finished.tryComplete();
            else file.write(chunk).onSuccess(written -> req.resume()).onFailure(this::failed);
        }

        private void failed(Throwable failure) {
            req.handler(null);   // a chunk still queued in the request, or released by the write in flight, is dropped
            received.tryFail(failure);
            finished.tryComplete();
        }
    }
}
