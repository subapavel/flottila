package io.flottila.components.http;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Fault;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.IdGenerator;
import io.flottila.api.Key;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The {@code http} component over the Vert.x server and client (ARCHITECTURE §4 delivery modes, §7 Component SPI):
 * the one place that opens an HTTP socket.
 *
 * <p><b>Consumer</b> (endpoint options {@code host}, {@code port} — 0 for an ephemeral one, see {@link HttpConsumer#port()} —,
 * {@code path}, {@code flow}, {@code maxBodyBytes}, {@code shutdownTimeoutMs} — default 30 000, positive): the server verticle owns the socket and never runs a pipeline. A request
 * at the path is streamed into the {@link BlobStore} under {@code maxBodyBytes} (413 above it), becomes the ingress envelope
 * (new id, the request headers, {@link #METHOD} / {@link #PATH} props) and is handed to the handler on its own virtual thread;
 * the answer follows the {@link Ack}: {@code Reply} → 200 with the reply's headers and its body streamed, {@code Accepted} →
 * 202 with {@code x-message-id}, a retryable {@code Nack} → 503 + {@code Retry-After}, a final one → 400. A failed handler (a
 * sync flow's error reaches the caller) is answered by the failure's {@link Fault.Kind} (D-049): {@code MALFORMED} 400,
 * {@code INVALID} 422, {@code DOWNSTREAM} 503 + {@code Retry-After} when retryable ({@link NackException},
 * {@link HttpStatusException}) else 502, {@code INTERNAL} and any failure that is no {@link Fault} 500 — the body is the
 * failure's message as {@code text/plain}. Other paths are 404, other methods 405.
 *
 * <p><b>Producer</b> (options {@code url}, {@code maxBodyBytes} for the reply, optional {@code timeoutMs}, {@code poolSize}):
 * one client per endpoint with its connection pool; POSTs the envelope's body — streamed from {@link Body#open()}, so a retry
 * re-opens it — with the envelope's headers (hop-by-hop ones aside); a 2xx response is the reply envelope (its body the next
 * step's, stored under {@link Envelope#nextKey()} of the envelope sent — the run's own key from its second attempt on, D-066 —
 * {@link #STATUS} prop, the response headers), 503 and 429 fail with a retryable {@link NackException}, any other
 * non-2xx with {@link HttpStatusException} carrying the first {@value #ERROR_BODY_BYTES} bytes of the response (the rest drained). Limits are never defaulted: both {@code maxBodyBytes} are required.
 *
 * @since 0.1
 */
public final class HttpComponent implements Component {

    /** The request's method on an ingress envelope. */
    public static final Key<String> METHOD = new Key<>("http.method");
    /** The request's path on an ingress envelope. */
    public static final Key<String> PATH = new Key<>("http.path");
    /** The response status on a reply envelope. */
    public static final Key<Integer> STATUS = new Key<>("http.status");
    /** The header carrying the message id on a 202. */
    public static final String H_MESSAGE_ID = "x-message-id";

    static final String CONFIG_SCHEMA = """
            {"type":"object","properties":{"host":{"type":"string"},"port":{"type":"integer","minimum":0,"maximum":65535},"path":{"type":"string"},
             "flow":{"type":"string"},"url":{"type":"string"},"maxBodyBytes":{"type":"integer","minimum":1},"timeoutMs":{"type":"integer","minimum":1},
             "poolSize":{"type":"integer","minimum":1},"shutdownTimeoutMs":{"type":"integer","minimum":1}}}""";
    private static final Set<String> HOP_BY_HOP = Set.of("content-length", "transfer-encoding", "connection", "host", "keep-alive", "upgrade", "expect", "te", "trailer", "proxy-authorization", "proxy-authenticate");
    /** The consumer's {@code shutdownTimeoutMs} when the endpoint sets none: how long a stop waits for the requests in flight. */
    static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 30_000;
    /** How much of a non-2xx response body travels in the failure's message; the rest is read and dropped. */
    static final int ERROR_BODY_BYTES = 512;

    private final Vertx vertx;
    private final BlobStore blobs;
    private final IdGenerator ids;
    private final Map<HttpConsumer, Boolean> running = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong handled = new java.util.concurrent.atomic.AtomicLong();   // numbers the handler threads

    public HttpComponent(Vertx vertx, BlobStore blobs, IdGenerator ids) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override public String scheme() { return "http"; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        return new HttpConsumer(cfg, handler);
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        return new HttpProducer(cfg);
    }

    @Override
    public Future<Health> health() {
        return Future.succeededFuture(running.isEmpty() ? Health.down("no consumer listening") : Health.up());
    }

    /** Where a consumer of {@code cfg} binds ({@link #bindsTo}): every http consumer accepts connections (D-072). */
    @Override
    public Optional<InetSocketAddress> listensOn(EndpointConfig cfg) {
        return Optional.of(bindsTo(cfg));
    }

    // --- options

    /**
     * The address a consumer of {@code cfg} binds, unresolved: the {@code host} option (127.0.0.1 when absent) and the {@code port}
     * option, else the URI's port, else 0 (any free port). {@link HttpConsumer} binds exactly this, so {@link #listensOn} answers it.
     */
    private static InetSocketAddress bindsTo(EndpointConfig cfg) {
        return InetSocketAddress.createUnresolved(string(cfg, "host", "127.0.0.1"), intOption(cfg, "port", Math.max(cfg.uri().getPort(), 0)));
    }

    static long requiredLong(EndpointConfig cfg, String key) {
        Object v = cfg.options().get(key);
        if (!(v instanceof Number n) || n.longValue() <= 0) {
            throw new IllegalArgumentException("endpoint '" + cfg.name() + "': option '" + key + "' must be a positive integer (never unlimited), was " + v);
        }
        return n.longValue();
    }

    /** {@code key} as a positive integer, {@code fallback} when absent; present and not positive is refused as by {@link #requiredLong}. */
    static long positiveLong(EndpointConfig cfg, String key, long fallback) {
        return cfg.options().get(key) == null ? fallback : requiredLong(cfg, key);
    }

    static String string(EndpointConfig cfg, String key, String fallback) {
        Object v = cfg.options().get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    static int intOption(EndpointConfig cfg, String key, int fallback) {
        Object v = cfg.options().get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    /** The request's headers as the envelope's, names lower-cased; hop-by-hop ones left out. */
    static Headers headers(io.vertx.core.MultiMap raw) {
        Map<String, String> m = new HashMap<>();
        raw.forEach((k, v) -> { String name = k.toLowerCase(Locale.ROOT); if (!HOP_BY_HOP.contains(name)) m.put(name, v); });
        return new Headers(m);
    }

    // --- consumer

    /** @since 0.1 */
    public final class HttpConsumer implements Consumer {
        private final EndpointConfig cfg;
        private final Function<Envelope, Future<Ack>> handler;
        private final String host;
        private final int configuredPort;
        private final String path;
        private final String flow;
        private final long maxBodyBytes;
        private final Duration shutdownTimeout;
        private volatile HttpServer server;

        HttpConsumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
            this.cfg = Objects.requireNonNull(cfg, "cfg");
            this.handler = Objects.requireNonNull(handler, "handler");
            InetSocketAddress at = bindsTo(cfg);
            this.host = at.getHostString();
            this.configuredPort = at.getPort();
            this.path = string(cfg, "path", cfg.uri().getPath() == null || cfg.uri().getPath().isEmpty() ? "/" : cfg.uri().getPath());
            this.flow = string(cfg, "flow", cfg.name());
            this.maxBodyBytes = requiredLong(cfg, "maxBodyBytes");
            this.shutdownTimeout = Duration.ofMillis(positiveLong(cfg, "shutdownTimeoutMs", DEFAULT_SHUTDOWN_TIMEOUT_MS));
        }

        /** How long {@link #stop()} lets the requests in flight answer before the server force-closes: {@code shutdownTimeoutMs}. */
        Duration shutdownTimeout() {
            return shutdownTimeout;
        }

        /** The port the server listens on (the actual one when configured 0); -1 before {@link #start()}. */
        public int port() {
            HttpServer s = server;
            return s == null ? -1 : s.actualPort();
        }

        @Override
        public Future<Void> start() {
            HttpServer s = vertx.createHttpServer(new HttpServerOptions().setHost(host).setPort(configuredPort)).requestHandler(this::handle);
            return s.listen().map(listening -> { server = listening; running.put(this, Boolean.TRUE); return null; });
        }

        /**
         * Stops (D-056): health DOWN at once; a request whose body completes from now on is answered 503 + {@code retry-after}
         * without calling the handler; the server stops listening and its connections close once their requests are answered
         * — the deliveries in flight answer their senders — or, the transport's backstop, after {@code shutdownTimeoutMs}.
         */
        @Override
        public Future<Void> stop() {
            HttpServer s = server;
            server = null;
            running.remove(this);   // health DOWN, and a request whose body completes from now on is refused
            return s == null ? Future.succeededFuture() : s.shutdown(shutdownTimeout);
        }

        private void handle(HttpServerRequest req) {
            HttpServerResponse res = req.response();
            if (!path.equals(req.path())) { res.setStatusCode(404).end("no endpoint at " + req.path()); return; }
            if (req.method() != HttpMethod.POST && req.method() != HttpMethod.PUT) { res.setStatusCode(405).putHeader("allow", "POST, PUT").end(); return; }
            String declared = req.getHeader("content-length");
            if (declared != null && Long.parseLong(declared) > maxBodyBytes) { res.setStatusCode(413).end("body above " + maxBodyBytes + " bytes"); return; }
            String id = ids.next();
            Context ctx = vertx.getOrCreateContext();
            Headers headers = headers(req.headers());
            String method = req.method().name();
            req.pause();   // nothing flows until the store attached its handlers (a store that attaches later resumes)
            blobs.write(BlobKey.body(id, 0), req, maxBodyBytes).onComplete(written -> {
                if (written.failed()) {
                    if (written.cause() instanceof BodyTooLargeException) res.setStatusCode(413).end("body above " + maxBodyBytes + " bytes");
                    else res.setStatusCode(500).end(String.valueOf(written.cause().getMessage()));
                    return;
                }
                if (!running.containsKey(this)) {   // stopping (or not yet started): never handed to the handler
                    res.setStatusCode(503).putHeader("retry-after", "1").end("consumer of flow " + flow + " is stopping");
                    return;
                }
                Envelope in = Envelope.ingress(id, flow, headers, written.result()).with(METHOD, method).with(PATH, path);
                // the handler runs the flow on the message's own virtual thread; the socket's event loop never does
                // a handler that throws (a checked one too, sneakily: an interrupted await) or answers null fails the answer — Future.future
                // catches either — and the request gets a 500, never a hang
                Thread.ofVirtual().name("flottila-http-" + handled.incrementAndGet()).start(() -> Future.<Ack>future(p -> handler.apply(in).onComplete(p))
                        .onComplete(done -> ctx.runOnContext(v -> answer(res, done.succeeded() ? done.result() : null, done.cause()))));
            });
        }

        private void answer(HttpServerResponse res, Ack ack, Throwable failure) {
            if (failure != null) {
                // a downstream failure says itself whether the sender may try again
                boolean retryable = failure instanceof NackException nack ? nack.retryable() : failure instanceof HttpStatusException partner && partner.retryable();
                int status = !(failure instanceof Fault fault) ? 500 : switch (fault.kind()) {
                    case MALFORMED -> 400;
                    case INVALID -> 422;
                    case DOWNSTREAM -> retryable ? 503 : 502;
                    case INTERNAL -> 500;
                };
                if (status == 503) res.putHeader("retry-after", "1");
                res.setStatusCode(status).putHeader("content-type", "text/plain").end(String.valueOf(failure.getMessage()));
                return;
            }
            switch (ack) {
                case Ack.Accepted a -> res.setStatusCode(202).putHeader(H_MESSAGE_ID, a.messageId()).end();
                case Ack.Nack n -> {
                    if (n.retryable()) res.setStatusCode(503).putHeader("retry-after", "1").end(n.reason());
                    else res.setStatusCode(400).end(n.reason());
                }
                case Ack.Reply r -> {
                    Envelope reply = r.reply();
                    res.setStatusCode(reply.get(STATUS).orElse(200));
                    reply.headers().asMap().forEach((k, v) -> { if (!HOP_BY_HOP.contains(k)) res.putHeader(k, v); });
                    res.putHeader("content-length", String.valueOf(reply.body().size()));
                    reply.body().open().pipeTo(res);
                }
            }
        }
    }

    // --- producer

    /** @since 0.1 */
    public final class HttpProducer implements Producer {
        private final EndpointConfig cfg;
        private final String url;
        private final long maxReplyBytes;
        private final long timeoutMs;
        private final HttpClientAgent client;
        private final AtomicInteger sends = new AtomicInteger();

        HttpProducer(EndpointConfig cfg) {
            this.cfg = Objects.requireNonNull(cfg, "cfg");
            this.url = string(cfg, "url", cfg.uri().toString());
            this.maxReplyBytes = requiredLong(cfg, "maxBodyBytes");
            this.timeoutMs = intOption(cfg, "timeoutMs", 30_000);
            this.client = vertx.createHttpClient(new HttpClientOptions(), new PoolOptions().setHttp1MaxSize(intOption(cfg, "poolSize", 8)));
        }

        /** Sends so far, each one a fresh request with the body re-opened. */
        public int sends() { return sends.get(); }

        @Override
        public Future<Envelope> send(Envelope e) {
            Objects.requireNonNull(e, "envelope");
            sends.incrementAndGet();
            RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI(url).setTimeout(timeoutMs);
            return client.request(options).compose(req -> {
                e.headers().asMap().forEach((k, v) -> { if (!HOP_BY_HOP.contains(k)) req.putHeader(k, v); });
                req.putHeader("content-length", String.valueOf(e.body().size()));
                // Registered before the send, so it runs in the event-loop callback that delivers the response: nothing flows
                // until reply() attached its handlers (the store resumes a 2xx reply, head() any other), which runs a deferred
                // task later (FIX-21: a small reply could end unheard in between, "Response already ended", and a retry sent twice).
                req.response().onSuccess(HttpClientResponse::pause);
                return req.send(e.body().open());
            }).compose(res -> reply(e, res));
        }

        private Future<Envelope> reply(Envelope e, HttpClientResponse res) {
            int status = res.statusCode();
            if (status / 100 == 2) {
                Headers headers = headers(res.headers());
                return blobs.write(e.nextKey(), res, maxReplyBytes)
                        .map(body -> new Envelope(e.id(), e.flow(), e.step() + 1, headers, e.props().with(STATUS, status), e.keys(), body, e.attachments(), new io.flottila.api.Views()));
            }
            return head(res).compose(reason -> {
                if (status == 503 || status == 429) return Future.failedFuture(new NackException("HTTP " + status + " from " + cfg.name() + ": " + reason, true));
                return Future.failedFuture(new HttpStatusException(status, reason));
            });
        }

        /** The first {@link #ERROR_BODY_BYTES} of the response body as text; the rest is drained, never buffered (a partner's error page is not a heap allocation). */
        private static Future<String> head(HttpClientResponse res) {
            io.vertx.core.Promise<String> done = io.vertx.core.Promise.promise();
            Buffer kept = Buffer.buffer();
            res.exceptionHandler(x -> done.tryComplete(kept.toString()));
            res.endHandler(v -> done.tryComplete(kept.toString()));
            res.handler(chunk -> {
                int room = ERROR_BODY_BYTES - kept.length();
                if (room > 0) kept.appendBuffer(chunk, 0, Math.min(room, chunk.length()));
            });
            res.resume();
            return done.future();
        }

        @Override
        public Future<Void> close() { return client.close(); }
    }
}
