package io.flottila.runtime.admin;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.BodyLostException;
import io.flottila.api.BlobStore;
import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Health;
import io.flottila.api.IdGenerator;
import io.flottila.core.admission.TargetLimit;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.flow.Replay;
import io.flottila.core.flow.ReplayRefusedException;
import io.flottila.core.flow.RowNotRemovedException;
import io.flottila.core.flow.WorkSignals;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.core.record.StepCpu;
import io.flottila.runtime.deploy.DeployRefusedException;
import io.flottila.runtime.deploy.DeployResult;
import io.flottila.runtime.deploy.DeployedFlow;
import io.flottila.runtime.deploy.DeployedPackage;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.UndeployResult;
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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The admin read API (D-057 a, GATE check 6) over the stores: a message's timeline with wall and CPU nanos per step, the
 * payload after step n, key search and the DLQ a page at a time ({@code {items, next|null}}), {@code /health}, and the probes
 * {@code /live} and {@code /ready} (D-123).
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
 * take, 500 for anything else — a store that failed, a recorded header no response may carry, a stored body whose stream
 * fails before the head is out (after it, the response is reset).
 *
 * <p>With a {@link Deployments} (D-059, D-060 b) it also deploys: {@code PUT /admin/packages/{name}?revision=} — a Content-Length
 * above {@code maxPackageBytes} is 413 before a byte of the body is read; otherwise {@code Expect: 100-continue} is answered with
 * {@code 100 Continue} and the body is streamed with back-pressure into a
 * temp file in {@code incomingDir()} and counted, 413 at byte max+1; the deploy runs on the route's virtual thread and the temp
 * file is deleted before the answer goes out, whatever it is: 200 {@code {package, version, revision|null, archive, flows, added,
 * swapped, removed}} (the last three sorted qualified flow ids: a redeploy swaps only the flows that changed, D-063), or a refusal
 * by its kind — 400, 403, 409 (another deploy runs on the node), 422 {@code {error, problems}}, 500 ({@code {error}}; a redeploy
 * whose start failed and rolled back: {@code {error, problems}}, D-063), 503. An answer given before
 * the request's body was read to its end closes the connection once written: the rest is never read. {@code GET /admin/flows} and
 * {@code GET /admin/packages} list what runs, sorted, not paged; a flow shows its effective {@code hash} and its {@code state}
 * (D-063), a package its {@code state} and its {@code problems} (D-070: FAILED when the restore could not install it, with no
 * flows, no files, no folders and no artifacts) and its tree (D-078): {@code folders:[{path, namespace|null, description|null}]}
 * and {@code artifacts:[{path, kind, id|null}]}, each sorted by path, its {@code content} digest (null when FAILED) and its
 * {@code dependencies:[{name, version, content, archive, direct}]}, the closure its deploy resolved (D-087).
 * {@code DELETE /admin/packages/{name}} undeploys (D-095) on the route's virtual thread: 200 {@code {name, version, flowsRemoved}}
 * (its flows' qualified ids, sorted), or a refusal by the deploy's status map — 400, 403, 409 (another deploy or undeploy runs on
 * the node, or another package's record pins it), 503 — and 404 for a package the node does not list; a record delete that fails
 * is 500. {@code GET /admin/targets} lists the node's limits per target system (D-099), sorted by target: 200
 * {@code {items:[{target, maxCalls, maxWaitMs, inFlight, waiting, refused}]}}, every target a built flow named so far; a limit of a
 * component that names no target is not listed. Without one these routes do not exist (404).
 *
 * <p>With a {@link Deployments} it also shows a deployed flow's CPU by step (D-094): {@code GET /admin/flows/{flow}/cpu} — the flow's
 * qualified id as one path segment, its slash percent-encoded — answers, with a {@link Sampling}, 200 {@code {flow, sampling:"on",
 * period, since, items:[{step, name, executions, samples, cpuNanos}], totals:{samples, attributed, pending, unattributed, platform,
 * lost, dropped}}}: the node's CPU-sampling aggregate since the sampler started, the items by CPU descending, the totals node-wide
 * ({@link StepCpu}); never a per-message figure. Once the sampler's stream has failed, {@code sampling:"failed"} with the rest as the
 * stream last fed it. Without one, 200 {@code {flow, sampling:"off", items:[]}}. A flow not deployed here is 404.
 *
 * <p>With a {@link WorkQueue} (D-067) it shows and releases held work items: {@code GET /admin/work/held?limit&after} pages every
 * flow's held rows in seq order ({@code {items:[{seq, messageId, flow, step, partitionKey, attempts, heldAt}], next|null}});
 * {@code POST /admin/work/{seq}/release} answers 200 {@code {seq}}, 404 for a seq unknown or not held, 400 for a seq that is
 * not a long. A release wakes the row's flow in the node's {@link Workers} (every flow when the queue cannot tell the row) and
 * announces it through the node's {@link WorkSignals} to the node whose journal holds its body ({@code *} when unknown; D-121 (b)
 * B4 (ii)); built without them, a release wakes and announces nothing. Each held row also carries {@code blocks}, the rows of its
 * key behind it (D-126). {@code GET /admin/work/lost?limit&after} pages the lost rows — their body given up by an accepted
 * {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} — the same way ({@code {items:[{seq, messageId, flow, partitionKey, lostAt, blocks}],
 * next|null}}); {@code POST /admin/work/{seq}/discard} deletes a lost row and writes one {@code audit} row in the same transaction,
 * answering 200 {@code {seq, auditId}}, 404 for a seq unknown or not lost, 400 for a seq that is not a long or an
 * {@code X-Flottila-Actor} over 128 characters; the actor is that header ({@code unknown} without one), self-declared under the
 * shared token. A discard wakes the row's flow and announces it to the node of its key's next row, as a release does. Without a
 * queue these routes do not exist (404).
 *
 * <p>With a {@link Deployments} and an {@link IdGenerator} (D-067, GATE check 7) it replays and retries:
 * {@code POST /admin/messages/{id}/replay?from=n} runs steps {@code n..end} of the message's flow as deployed now, and
 * {@code POST /admin/dlq/{id}/retry} the parked step, each as a new message whose id the generator mints, through the flow's
 * {@link Replay} on the route's virtual thread. 200 {@code {id, replayOf, from, outcome:"COMPLETE"}}; a run that failed is 502
 * with the same fields, {@code outcome} PARKED or FAILED, and {@code error}. Refusals, nothing run: 400 ({@code from} missing, not
 * an int, outside 1..steps) → 404 (no timeline, no DLQ row) → 409 (the flow does not run here; a retry of the id already runs on
 * this node) → 422 (step n−1 unrecorded or metadata-only, the parked step beyond the flow) → 409 (the input body removed by
 * retention, D-096) → 422 (a body above the flow's byte budget) → 429 with {@code retry-after: 1} (no permit of the flow's
 * admission now). A store that cannot say whether the input body exists is no refusal: 502 FAILED. A retry that went through but whose row could not be
 * removed is 500 naming the new message.
 *
 * <p>Every {@code /admin/**} request that carries an {@code Origin} header, or a {@code Sec-Fetch-Site} other than {@code none},
 * is 403 {@code {error}} before the token check and the route, token or no token (D-069): what a browser sends, never the CLI.
 *
 * @since 0.1
 */
public final class AdminServer {

    /** The page size when a request names no {@code limit}; the stores refuse more than {@link Page#MAX_LIMIT}. */
    public static final int DEFAULT_LIMIT = 50;

    private static final System.Logger LOG = System.getLogger(AdminServer.class.getName());

    /** The header an admin request declares its actor in, for the audit row (D-126 L4); and its length at most. */
    static final String ACTOR = "X-Flottila-Actor";
    static final int ACTOR_MAX = 128;
    private static final String JSON = "application/json";
    private static final String OCTETS = "application/octet-stream";
    private static final String BEARER = "Bearer ";
    /** What the router answers itself: no route, a method the route does not take, a request it cannot read, a handler that threw. */
    private static final Map<Integer, String> ROUTER_ERRORS = Map.of(400, "bad request", 404, "no such route", 405, "method not allowed", 500, "internal error");

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
                throw new IllegalArgumentException("the admin API on " + host + " is not loopback-only: it needs a token (FLOTTILA_ADMIN_TOKEN)");
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
     * only when there is one — the node's {@code notices}: facts an operator should act on that fail nothing (D-106: a
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
     * What {@code /ready} reports (D-123): four in-memory inputs, true is UP, each read on every request and never awaited — so
     * {@code /ready} needs no deadline.
     *
     * @param lifecycle the node finished its start and its close has not begun
     * @param fence the node lease's fence is open
     * @param journal the journal is UP: neither failed nor in outage (DEGRADED is DOWN here)
     * @param deployments no package or flow is listed FAILED
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
     * What {@code GET /admin/flows/{flow}/cpu} shows when the node runs a CPU sampler (D-094).
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

    private final Config config;
    private final MessageStore messages;
    private final Dlq dlq;
    private final BlobStore blobs;
    private final HealthProbe health;
    private final Optional<Deployments> deployments;
    private final Optional<WorkQueue> work;
    private final Optional<IdGenerator> ids;
    private final Optional<Sampling> sampling;
    private final Workers workers;
    private final WorkSignals signals;
    /** The ids whose DLQ retry runs on this node now: a second retry of one is 409 (node-local, D-067). */
    private final Set<String> retrying = ConcurrentHashMap.newKeySet();
    private volatile Supplier<Future<Void>> undeploy = Future::succeededFuture;

    /** The read API only: no deploy routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health) {
        this(config, messages, dlq, blobs, health, Optional.empty());
    }

    /** The read API and, with {@code deployments}, the deploy routes; no work routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments) {
        this(config, messages, dlq, blobs, health, deployments, Optional.empty(), Optional.empty(), Optional.empty(), new Workers(), WorkSignals.NONE);
    }

    /** The read API, with {@code deployments} the deploy routes, and the held-work routes over {@code work}; no replay routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work) {
        this(config, messages, dlq, blobs, health, deployments, work, new Workers(), WorkSignals.NONE);
    }

    /**
     * The read API, with {@code deployments} the deploy routes, and the held-work routes over {@code work}, a release waking its flow
     * in {@code workers} and announcing it through {@code signals} (D-121 (b) B4 (ii)); no replay routes.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.empty(), Optional.empty(), workers, signals);
    }

    /**
     * The read API, the held-work routes over {@code work} and, with {@code deployments}, the deploy routes and the replay and
     * retry routes, whose new messages take their ids from {@code ids}.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, IdGenerator ids) {
        this(config, messages, dlq, blobs, health, deployments, work, ids, Optional.empty());
    }

    /**
     * The read API, the held-work routes over {@code work} and, with {@code deployments}, the deploy, replay and retry routes and
     * the flows' CPU, sampled when {@code sampling} is there (D-094).
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, IdGenerator ids, Optional<Sampling> sampling) {
        this(config, messages, dlq, blobs, health, deployments, work, ids, sampling, new Workers(), WorkSignals.NONE);
    }

    /**
     * {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, IdGenerator, Optional)}, a release
     * waking its flow in {@code workers} and announcing it through {@code signals} (D-121 (b) B4 (ii)): the node's.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, IdGenerator ids, Optional<Sampling> sampling, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.of(ids), sampling, workers, signals);
    }

    private AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                        Optional<WorkQueue> work, Optional<IdGenerator> ids, Optional<Sampling> sampling, Workers workers, WorkSignals signals) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.health = Objects.requireNonNull(health, "health");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.work = work;
        this.ids = ids;
        this.sampling = Objects.requireNonNull(sampling, "sampling");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.signals = Objects.requireNonNull(signals, "signals");
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
            router.get("/live").handler(offload(c -> Json.of(200, new JsonObject().put("status", Health.Status.UP.name()))));   // reads nothing (D-123)
            router.get("/ready").handler(offload(AdminServer.this::ready));
            router.route("/admin/*").handler(AdminServer::browserGuard);   // before the token check (D-069)
            config.token().ifPresent(token -> router.route("/admin/*").handler(bearer(token)));
            router.get("/admin/messages").handler(offload(AdminServer.this::search));
            router.get("/admin/messages/:id").handler(offload(AdminServer.this::timeline));
            router.get("/admin/messages/:id/steps/:n/body").handler(offload(AdminServer.this::body));
            router.get("/admin/dlq").handler(offload(AdminServer.this::dlq));
            deployments.ifPresent(d -> {
                router.put("/admin/packages/:name").handler(upload(d));
                router.delete("/admin/packages/:name").handler(offload(c -> undeployed(c, d)));
                router.get("/admin/flows").handler(offload(c -> flows(d)));
                router.get("/admin/packages").handler(offload(c -> packages(d)));
                router.get("/admin/targets").handler(offload(c -> targets(d)));
                router.get("/admin/flows/:flow/cpu").handler(offload(c -> cpu(c, d)));
                ids.ifPresent(minted -> {
                    router.post("/admin/messages/:id/replay").handler(offload(c -> replay(c, d, minted)));
                    router.post("/admin/dlq/:id/retry").handler(offload(c -> retry(c, d, minted)));
                });
            });
            work.ifPresent(q -> {
                router.get("/admin/work/held").handler(offload(c -> held(c, q)));
                router.post("/admin/work/:seq/release").handler(offload(c -> release(c, q, workers, signals)));
                router.get("/admin/work/lost").handler(offload(c -> lost(c, q)));
                router.post("/admin/work/:seq/discard").handler(offload(c -> discard(c, q, workers, signals)));
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
            return ctx -> Thread.ofVirtual().name("flottila-admin").start(() -> {
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
                Thread.ofVirtual().name("flottila-admin").start(() -> {
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
     * Lets the request on unless a browser sent it (D-069): an {@code Origin} header, whatever its value, or a {@code Sec-Fetch-Site}
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
        Snapshot at = messages.timeline(id).await().stream().filter(s -> s.step() == n).findFirst().orElse(null);
        if (at == null) return Json.of(404, error("no step " + n + " recorded for message " + id));
        if (at.bodyKey().isEmpty()) return Json.of(404, error("step " + n + " of message " + id + " was recorded as metadata only: hash and size, no bytes"));
        // the head first: a recorded content-type no response may carry is refused here (IllegalArgumentException, a 500), before any stream is opened
        MultiMap head = HttpHeaders.headers().set("content-type", at.headers().getOrDefault("content-type", OCTETS)).set("content-length", Long.toString(at.bodySize()));
        String gone = "the bytes of step " + n + " of message " + id + " are no longer stored (removed by retention)";
        BlobKey key = at.bodyKey().get();
        // existence as Postgres names it, never a node's location index (CORE-4c2 r1): no object is retention's removal (D-096);
        // bytes that go after this check are gone too — an open that throws (a store that finds the key synchronously) or a
        // stream that fails before its first chunk (a store that opens lazily) answers the same 404
        boolean stored;
        try {
            stored = blobs.exists(key).await();
        } catch (BodyLostException lost) {   // given up by an accepted loss: stored once, never readable again (D-105)
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

    /** {@code key=name:value}, split at the first colon: a value may contain colons, a name may not. */
    private Reply search(RoutingContext ctx) {
        String key = Objects.requireNonNullElse(ctx.request().getParam("key"), "");
        int colon = key.indexOf(':');
        if (colon < 0) throw new Refused("key must be name:value");
        Page<String> page = refusable(() -> messages.findByKey(key.substring(0, colon), key.substring(colon + 1), after(ctx), limit(ctx)).await());
        return Json.of(200, page(page, id -> id));
    }

    private Reply dlq(RoutingContext ctx) {
        return Json.of(200, page(refusable(() -> dlq.list(after(ctx), limit(ctx)).await()), AdminServer::parked));
    }

    private static Reply held(RoutingContext ctx, WorkQueue work) {
        return Json.of(200, page(refusable(() -> work.held(after(ctx), limit(ctx)).await()), AdminServer::held));
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
     * 200 {@code {seq, auditId}} for a lost row, discarded and audited (D-126): its flow woken here and, when its key has a next row
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
     * The audit actor (D-126 L4): the request's {@value #ACTOR} header, stripped — self-declared under the shared token, never
     * authenticated — {@code unknown} when absent or blank; over {@link #ACTOR_MAX} characters a 400.
     */
    private static String actor(HttpServerRequest req) {
        String actor = Objects.requireNonNullElse(req.getHeader(ACTOR), "").strip();
        if (actor.length() > ACTOR_MAX) throw new Refused(ACTOR + " is at most " + ACTOR_MAX + " characters, was " + actor.length());
        return actor.isEmpty() ? "unknown" : actor;
    }

    /**
     * {@code POST /admin/messages/{id}/replay?from=n}: a {@code from} that is not an int of at least 1 is 400 before the message is
     * looked up; then 404 without a timeline, 409 when the message's flow does not run here; then the run ({@link #ran}).
     */
    private Reply replay(RoutingContext ctx, Deployments deployments, IdGenerator ids) {
        String id = ctx.pathParam("id");
        int from = from(ctx);
        List<Snapshot> timeline = messages.timeline(id).await();
        if (timeline.isEmpty()) return Json.of(404, error("no message " + id + " recorded"));
        String flow = timeline.getFirst().flow();
        Optional<Replay> replay = deployments.replay(flow);
        if (replay.isEmpty()) return notRunning(flow);
        String replayId = ids.next();
        return ran(replayId, id, from, () -> replay.get().from(id, from, replayId));
    }

    /**
     * {@code POST /admin/dlq/{id}/retry}: 404 without a DLQ row, 409 when its flow does not run here or a retry of the id runs on
     * this node already; then the run of the parked step ({@link #ran}). A retry that went through but whose row stays is 500.
     */
    private Reply retry(RoutingContext ctx, Deployments deployments, IdGenerator ids) {
        String id = ctx.pathParam("id");
        Optional<Parked> row = dlq.get(id).await();
        if (row.isEmpty()) return Json.of(404, error("no DLQ row for message " + id));
        Optional<Replay> replay = deployments.replay(row.get().flow());
        if (replay.isEmpty()) return notRunning(row.get().flow());
        if (!retrying.add(id)) return Json.of(409, error("a retry of message " + id + " runs on this node already"));
        try {
            String replayId = ids.next();
            return ran(replayId, id, row.get().step(), () -> replay.get().retry(id, replayId));
        } finally {
            retrying.remove(id);
        }
    }

    /** The {@code from} parameter: an int of at least 1, else (missing, not an int) a 400. */
    private static int from(RoutingContext ctx) {
        String raw = ctx.request().getParam("from");
        int from;
        try {
            from = Integer.parseInt(raw);
        } catch (NumberFormatException notAnInt) {   // missing (null) or not an int
            from = 0;
        }
        if (from < 1) throw new Refused("from must be a step number of at least 1, was " + (raw == null ? "missing" : "'" + raw + "'"));
        return from;
    }

    /**
     * The run {@code replayId} of {@code replayOf} from step {@code from}, on this virtual thread: 200 COMPLETE; a refusal by its
     * reason; a retry that went through but whose row stays, 500 naming {@code replayId}; else 502 PARKED (the run parked again)
     * or FAILED with the run's error — a checked one thrown sneakily by a step's await included. An Error is the caller's (500).
     */
    private static Reply ran(String replayId, String replayOf, int from, Supplier<Envelope> run) {
        JsonObject answer = new JsonObject().put("id", replayId).put("replayOf", replayOf).put("from", from);
        try {
            run.get();
            return Json.of(200, answer.put("outcome", "COMPLETE"));
        } catch (ReplayRefusedException refused) {
            return refusal(refused);
        } catch (RowNotRemovedException kept) {
            return Json.of(500, error(kept.getMessage()));
        } catch (Exception failed) {
            String outcome = failed instanceof ParkedException ? "PARKED" : "FAILED";
            return Json.of(502, answer.put("outcome", outcome).put("error", Objects.toString(failed.getMessage(), failed.toString())));
        }
    }

    /** A replay refusal by its reason: 400, 404, 409, 422, or 429 with {@code retry-after: 1}. */
    private static Reply refusal(ReplayRefusedException refused) {
        String why = refused.getMessage();
        return switch (refused.reason()) {
            case STEP_OUT_OF_RANGE -> Json.of(400, error("bad request: " + why));
            case UNKNOWN_MESSAGE, NO_DLQ_ROW -> Json.of(404, error(why));
            case CLOSED, REMOVED_BY_RETENTION, BODY_LOST -> Json.of(409, error(why));
            case NOT_RECORDED, METADATA_ONLY, STEP_BEYOND_FLOW, TOO_LARGE -> Json.of(422, error(why));
            case BUSY -> {
                Json busy = Json.of(429, error(why));
                yield res -> busy.send(res.putHeader("retry-after", "1"));
            }
        };
    }

    private static Reply notRunning(String flow) {
        return Json.of(409, error("flow " + flow + " does not run on this node"));
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

    private static Reply flows(Deployments deployments) {
        JsonArray items = new JsonArray();
        for (DeployedFlow f : deployments.flows()) {
            items.add(new JsonObject().put("id", f.id()).put("package", f.pkg()).put("packageVersion", f.packageVersion()).put("revision", f.revision().orElse(null))
                    .put("flowVersion", f.flowVersion()).put("delivery", f.delivery()).put("hash", f.hash()).put("state", f.state().name()));
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

    /** {@code GET /admin/targets}: the gauge of every target the deployed flows named, sorted by target (D-099). */
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
            JsonArray folders = new JsonArray();   // D-078: every folder below the root, null where its folder.yaml sets nothing
            p.folders().forEach(f -> folders.add(new JsonObject().put("path", f.path()).put("namespace", f.namespace().orElse(null))
                    .put("description", f.description().orElse(null))));
            JsonArray artifacts = new JsonArray();   // D-078: a test's id is null
            p.artifacts().forEach(a -> artifacts.add(new JsonObject().put("path", a.path()).put("kind", a.kind()).put("id", a.id().orElse(null))));
            JsonArray dependencies = new JsonArray();   // D-087: the closure, each marked direct or not
            p.dependencies().forEach(d -> dependencies.add(new JsonObject().put("name", d.name()).put("version", d.version()).put("content", d.content())
                    .put("archive", "sha256:" + d.archiveSha256()).put("direct", d.direct())));
            items.add(new JsonObject().put("name", p.name()).put("version", p.version()).put("revision", p.revision().orElse(null))
                    .put("archive", "sha256:" + p.archiveSha256()).put("deployedAt", p.deployedAt().toString()).put("flows", new JsonArray(p.flows())).put("files", files)
                    .put("folders", folders).put("artifacts", artifacts).put("state", p.state().name()).put("problems", new JsonArray(p.problems()))
                    .put("content", p.content()).put("dependencies", dependencies));
        }
        return Json.of(200, new JsonObject().put("items", items));
    }

    /** {@code DELETE /admin/packages/{name}}: the undeploy's result, or its refusal; a record delete that fails throws, a 500. */
    private static Reply undeployed(RoutingContext ctx, Deployments deployments) throws IOException {
        try {
            UndeployResult r = deployments.undeploy(ctx.pathParam("name"));
            return Json.of(200, new JsonObject().put("name", r.name()).put("version", r.version()).put("flowsRemoved", new JsonArray(r.flowsRemoved())));
        } catch (DeployRefusedException refused) {
            return refusal(refused);
        }
    }

    /** A refusal by its kind: 400, 403, 404, 409, 422 (with every problem), 500 (with every problem of a redeploy rolled back), 503. */
    private static Reply refusal(DeployRefusedException refused) {
        int status = switch (refused.kind()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case CONFLICT -> 409;
            case INVALID -> 422;
            case FAILED -> 500;
            case STOPPING -> 503;
            case NOT_FOUND -> 404;
        };
        JsonObject body = error(refused.error());
        if (!refused.problems().isEmpty()) body.put("problems", new JsonArray(refused.problems()));
        return Json.of(status, body);
    }

    private static Reply tooLarge(long max) {
        return Json.of(413, error("the package is above FLOTTILA_MAX_PACKAGE_BYTES " + max + " bytes"));
    }

    private static <T> JsonObject page(Page<T> page, Function<T, Object> item) {
        JsonArray items = new JsonArray();
        for (T t : page.items()) items.add(item.apply(t));
        return new JsonObject().put("items", items).put("next", page.next().orElse(null));
    }

    private static JsonObject step(Snapshot s) {
        return new JsonObject()
                .put("step", s.step())
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
                .put("versions", new JsonObject(new HashMap<>(s.versions())));
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
