package io.flottila.runtime.admin;

import io.flottila.api.BlobStore;
import io.flottila.api.Hash;
import io.flottila.api.Health;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.deploy.DeployRefusedException;
import io.flottila.runtime.deploy.DeployResult;
import io.flottila.runtime.deploy.DeployedFlow;
import io.flottila.runtime.deploy.DeployedPackage;
import io.flottila.runtime.deploy.Deployments;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The admin read API (D-057 a, GATE check 6) over the stores: a message's timeline with wall and CPU nanos per step, the
 * payload after step n, key search and the DLQ a page at a time ({@code {items, next|null}}), and {@code /health}.
 *
 * <p>A verticle owns the socket and routes on its event loop; every route body runs on a fresh virtual thread — it awaits
 * the stores, encodes the answer and checks its headers there — and the answer is written back on the verticle's context.
 * {@code /admin/**} needs {@code Authorization: Bearer <token>} when a token is configured (the scheme in any case, the
 * token compared with {@link MessageDigest#isEqual}); without one it is open, which only a loopback bind allows.
 * {@code /health} is open and shows status only, never a check's detail. Every error body is {@code {error}}: 400 for a
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
 * (D-063). Without one these routes do not exist (404).
 *
 * @since 0.1
 */
public final class AdminServer {

    /** The page size when a request names no {@code limit}; the stores refuse more than {@link Page#MAX_LIMIT}. */
    public static final int DEFAULT_LIMIT = 50;

    private static final System.Logger LOG = System.getLogger(AdminServer.class.getName());
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
     * What {@code /health} reports: each check by name (a check that fails is DOWN) and the snapshot writes that failed.
     *
     * @param checks the checks, by name
     * @param snapshotWriteFailures read on every request
     */
    public record HealthProbe(Map<String, Supplier<Future<Health>>> checks, LongSupplier snapshotWriteFailures) {
        public HealthProbe {
            checks = Map.copyOf(checks);
            Objects.requireNonNull(snapshotWriteFailures, "snapshotWriteFailures");
        }
    }

    private final Config config;
    private final MessageStore messages;
    private final Dlq dlq;
    private final BlobStore blobs;
    private final HealthProbe health;
    private final Optional<Deployments> deployments;
    private volatile Supplier<Future<Void>> undeploy = Future::succeededFuture;

    /** The read API only: no deploy routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health) {
        this(config, messages, dlq, blobs, health, Optional.empty());
    }

    /** The read API and, with {@code deployments}, the deploy routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.health = Objects.requireNonNull(health, "health");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
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
            config.token().ifPresent(token -> router.route("/admin/*").handler(bearer(token)));
            router.get("/admin/messages").handler(offload(AdminServer.this::search));
            router.get("/admin/messages/:id").handler(offload(AdminServer.this::timeline));
            router.get("/admin/messages/:id/steps/:n/body").handler(offload(AdminServer.this::body));
            router.get("/admin/dlq").handler(offload(AdminServer.this::dlq));
            deployments.ifPresent(d -> {
                router.put("/admin/packages/:name").handler(upload(d));
                router.get("/admin/flows").handler(offload(c -> flows(d)));
                router.get("/admin/packages").handler(offload(c -> packages(d)));
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
        return Json.of(up ? 200 : 503, new JsonObject().put("status", (up ? Health.Status.UP : Health.Status.DOWN).name()).put("checks", checks)
                .put("snapshotWriteFailures", health.snapshotWriteFailures().getAsLong()));
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
        String gone = "the bytes of step " + n + " of message " + id + " are no longer stored";
        ReadStream<Buffer> bytes;
        try {
            bytes = blobs.open(at.bodyKey().get());
        } catch (IllegalArgumentException missing) {   // the store holds no object under the key: retention took the bytes
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

    private static Reply packages(Deployments deployments) {
        JsonArray items = new JsonArray();
        for (DeployedPackage p : deployments.packages()) {
            JsonArray files = new JsonArray();
            for (DeployedPackage.PackageFile f : p.files()) files.add(new JsonObject().put("path", f.path()).put("sha256", f.sha256()));
            items.add(new JsonObject().put("name", p.name()).put("version", p.version()).put("revision", p.revision().orElse(null))
                    .put("archive", "sha256:" + p.archiveSha256()).put("deployedAt", p.deployedAt().toString()).put("flows", new JsonArray(p.flows())).put("files", files));
        }
        return Json.of(200, new JsonObject().put("items", items));
    }

    /** A refusal by its kind: 400, 403, 409, 422 (with every problem), 500 (with every problem of a redeploy rolled back), 503. */
    private static Reply refusal(DeployRefusedException refused) {
        int status = switch (refused.kind()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case CONFLICT -> 409;
            case INVALID -> 422;
            case FAILED -> 500;
            case STOPPING -> 503;
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
     * with an {@link IllegalArgumentException}, the store holding nothing under the key (a durable tier opens lazily and finds
     * no row only when read), the 404 {@code gone} — after it a reset, whatever the failure.
     */
    private record Bytes(MultiMap head, ReadStream<Buffer> bytes, String gone) implements Reply {
        @Override
        public Future<?> send(HttpServerResponse res) {
            res.setStatusCode(200).headers().setAll(head);
            return bytes.pipe().endOnFailure(false).to(res).onFailure(failed -> {
                if (failed instanceof IllegalArgumentException && !res.headWritten()) {
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
