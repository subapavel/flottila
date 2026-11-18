package dev.rail8.examples.webhook;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.BodyTooLargeException;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Deadline;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.IdGenerator;
import dev.rail8.api.NackException;
import dev.rail8.api.Producer;
import dev.rail8.api.Timer;
import dev.rail8.api.Views;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The {@code webhook} component of the sample plugin: a consumer that takes HTTP POSTs on a path and a producer that POSTs to
 * a URL, over the Vert.x HTTP server and client.
 *
 * <p><b>Consumer</b> (options {@code host}, default 127.0.0.1; {@code port}, default 0, any free port, see
 * {@link WebhookConsumer#port()}; {@code path}, default {@code /}; and {@code flow} and {@code maxBodyBytes}, which the node sets).
 * A POST to the path is streamed into the blob store, refused with 413 above {@code maxBodyBytes}, and handed to the flow's
 * handler as a new message, on a virtual thread of its own. The handler's {@link Ack} is the answer: {@code Accepted} 202 with
 * the message id in {@code x-message-id}, {@code Reply} 200 with the reply's headers and body, a retryable {@code Nack} 503 and
 * a final one 400, both with the reason as the body. A handler that fails is 500 with its message. Another path is 404, another
 * method 405. Once {@link Consumer#stop()} began, a request is 503 and the handler is not called.
 *
 * <p><b>Producer</b> (options {@code url}, required; {@code maxBodyBytes}, required, the most a reply may hold;
 * {@code timeoutMs}, default 30 000). A send POSTs the envelope's body, streamed from a fresh {@code open()}, with its headers
 * and its message id as {@code x-message-id}. A 2xx response is the reply: its headers, and its body stored as the next step's.
 * Any other status fails the send with a {@link NackException} whose reason holds the first {@value #REASON_BYTES} bytes of the
 * response body: retryable for 429 and 5xx, final otherwise. A send that times out fails retryable, and so does a bounded send
 * still unanswered when its {@link Deadline} runs out: the request is reset.
 */
public final class WebhookComponent implements Component {

    /** The scheme this component serves. */
    public static final String SCHEME = "webhook";

    static final String CONFIG_SCHEMA = """
            {"type":"object","properties":{
              "host":{"type":"string"},"port":{"type":"integer","minimum":0,"maximum":65535},"path":{"type":"string"},
              "url":{"type":"string"},"maxBodyBytes":{"type":"integer","minimum":1},"timeoutMs":{"type":"integer","minimum":1},
              "flow":{"type":"string"}}}""";

    /** Headers that describe one connection, never copied from a message to a request or a response, or back. */
    private static final Set<String> HOP_BY_HOP =
            Set.of("content-length", "transfer-encoding", "connection", "host", "keep-alive", "upgrade", "expect", "te", "trailer");
    /** How long a stopping consumer lets the requests in flight answer before it closes their connections. */
    private static final Duration SHUTDOWN = Duration.ofSeconds(30);
    /** How much of a refusal's response body the send's failure names. */
    static final int REASON_BYTES = 512;

    private final Vertx vertx;
    private final BlobStore blobs;
    private final IdGenerator ids;
    private final Timer timer;
    private final Set<WebhookConsumer> running = ConcurrentHashMap.newKeySet();

    /** A component on {@code vertx}; it stores bodies in {@code blobs}, names messages from {@code ids}, cuts sends on {@code timer}. */
    public WebhookComponent(Vertx vertx, BlobStore blobs, IdGenerator ids, Timer timer) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    @Override public String scheme() { return SCHEME; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        return new WebhookConsumer(cfg, handler);
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        return new WebhookProducer(cfg);
    }

    /** Up while a consumer listens. */
    @Override
    public Future<Health> health() {
        return Future.succeededFuture(running.isEmpty() ? Health.down("no consumer listening") : Health.up());
    }

    /** The host and port a consumer of {@code cfg} binds, so the node refuses two consumers on one. */
    @Override
    public Optional<InetSocketAddress> listensOn(EndpointConfig cfg) {
        return Optional.of(InetSocketAddress.createUnresolved(text(cfg, "host", "127.0.0.1"), (int) number(cfg, "port", 0L)));
    }

    /**
     * The partner a producer of {@code cfg} calls, {@code http://host:port} of its {@code url}, so that the node limits the sends
     * to one partner together; empty when the endpoint has no url or the url names no host.
     */
    @Override
    public Optional<String> target(EndpointConfig cfg) {
        if (!(cfg.options().get("url") instanceof String url)) return Optional.empty();
        URI uri = URI.create(url);
        if (uri.getHost() == null || uri.getScheme() == null) return Optional.empty();
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        return Optional.of(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port);
    }

    /** The option {@code key} as text; {@code fallback} when it is absent, refused when it is absent and {@code fallback} is null. */
    private static String text(EndpointConfig cfg, String key, String fallback) {
        Object value = cfg.options().get(key);
        if (value == null && fallback == null) throw new IllegalArgumentException("endpoint '" + cfg.name() + "': option '" + key + "' is required");
        return value == null ? fallback : String.valueOf(value);
    }

    /** The option {@code key} as a number; {@code fallback} when it is absent, refused when it is absent and {@code fallback} is null. */
    private static long number(EndpointConfig cfg, String key, Long fallback) {
        Object value = cfg.options().get(key);
        if (value instanceof Number n) return n.longValue();
        if (value == null && fallback != null) return fallback;
        throw new IllegalArgumentException("endpoint '" + cfg.name() + "': option '" + key + "' must be a number, was " + value);
    }

    /** {@code raw} as a message's headers: names in lower case, the hop-by-hop ones left out. */
    private static Headers headers(MultiMap raw) {
        Map<String, String> kept = new HashMap<>();
        raw.forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(lower)) kept.put(lower, value);
        });
        return new Headers(kept);
    }

    /** A consumer: an HTTP server on its host and port. */
    public final class WebhookConsumer implements Consumer {
        private final Function<Envelope, Future<Ack>> handler;
        private final String host;
        private final int port;
        private final String path;
        private final String flow;
        private final long maxBodyBytes;
        private volatile HttpServer server;

        WebhookConsumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            this.host = text(cfg, "host", "127.0.0.1");
            this.port = (int) number(cfg, "port", 0L);
            this.path = text(cfg, "path", "/");
            this.flow = text(cfg, "flow", cfg.name());
            this.maxBodyBytes = number(cfg, "maxBodyBytes", null);
        }

        /** The port the server listens on, the one it was given when the option {@code port} is 0; -1 while it is not started. */
        public int port() {
            HttpServer s = server;
            return s == null ? -1 : s.actualPort();
        }

        @Override
        public Future<Void> start() {
            HttpServer s = vertx.createHttpServer(new HttpServerOptions().setHost(host).setPort(port)).requestHandler(this::handle);
            return s.listen().map(listening -> {
                server = listening;
                running.add(this);
                return null;
            });
        }

        /**
         * Takes no new message from now on, and completes once the requests in flight were answered (or after
         * {@code 30 s}, when their connections are closed).
         */
        @Override
        public Future<Void> stop() {
            HttpServer s = server;
            server = null;
            running.remove(this);
            return s == null ? Future.succeededFuture() : s.shutdown(SHUTDOWN);
        }

        private void handle(HttpServerRequest req) {
            HttpServerResponse res = req.response();
            if (!path.equals(req.path())) {
                res.setStatusCode(404).end();
                return;
            }
            if (req.method() != HttpMethod.POST) {
                res.setStatusCode(405).putHeader("allow", "POST").end();
                return;
            }
            String id = ids.next();
            Headers headers = headers(req.headers());
            Context context = vertx.getOrCreateContext();
            req.pause();   // nothing flows until the blob store reads the body
            blobs.write(BlobKey.body(id, 0), req, maxBodyBytes).onComplete(written -> {
                if (written.failed()) {
                    reason(res, written.cause() instanceof BodyTooLargeException ? 413 : 500, String.valueOf(written.cause().getMessage()));
                    return;
                }
                if (!running.contains(this)) {
                    reason(res, 503, "the consumer of flow " + flow + " is stopping");
                    return;
                }
                Envelope in = Envelope.ingress(id, flow, headers, written.result());
                // The flow runs on a virtual thread of its own, never on the server's event loop. Future.future catches a
                // handler that throws or answers null: the request is answered 500.
                Thread.ofVirtual().name("webhook-" + flow).start(() -> Future.<Ack>future(answer -> handler.apply(in).onComplete(answer))
                        .onComplete(done -> context.runOnContext(v -> answer(res, done))));
            });
        }

        private void answer(HttpServerResponse res, AsyncResult<Ack> done) {
            if (done.failed()) {
                reason(res, 500, String.valueOf(done.cause().getMessage()));
                return;
            }
            switch (done.result()) {
                case Ack.Accepted accepted -> res.setStatusCode(202).putHeader(Envelope.H_MESSAGE_ID, accepted.messageId()).end();
                case Ack.Nack nack -> reason(res, nack.retryable() ? 503 : 400, nack.reason());
                case Ack.Reply reply -> {
                    Envelope out = reply.reply();
                    res.setStatusCode(200);
                    out.headers().asMap().forEach((name, value) -> {
                        if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) res.putHeader(name, value);
                    });
                    res.putHeader("content-length", String.valueOf(out.body().size()));
                    out.body().open().pipeTo(res);
                }
            }
        }

        private static void reason(HttpServerResponse res, int status, String reason) {
            res.setStatusCode(status).putHeader("content-type", "text/plain; charset=utf-8").end(reason);
        }
    }

    /** A producer: an HTTP client with a connection pool of its own. */
    public final class WebhookProducer implements Producer {
        private final String name;
        private final String url;
        private final long maxReplyBytes;
        private final long timeoutMs;
        private final HttpClientAgent client;

        WebhookProducer(EndpointConfig cfg) {
            this.name = cfg.name();
            this.url = text(cfg, "url", null);
            this.maxReplyBytes = number(cfg, "maxBodyBytes", null);
            this.timeoutMs = number(cfg, "timeoutMs", 30_000L);
            this.client = vertx.createHttpClient();
        }

        @Override
        public Future<Envelope> send(Envelope e) {
            return post(e, null);
        }

        /**
         * {@link #send(Envelope)}, cut when {@code d} runs out: with no time left nothing is sent; else a timer at what is left
         * reads {@code d} again, waits on while time is left (the deadline was renewed) and otherwise fails the send and resets
         * its request.
         */
        @Override
        public Future<Envelope> send(Envelope e, Deadline d) {
            Duration left = d.remaining();
            if (!left.isPositive()) return Future.failedFuture(new NackException("deadline passed: message " + e.id() + " not sent to " + name, true));
            Cut cut = new Cut(d);
            cut.arm(left);
            post(e, cut).onComplete(done -> {
                cut.armed.cancel();
                if (done.succeeded()) cut.out.tryComplete(done.result());
                else cut.out.tryFail(done.cause());
            });
            return cut.out.future();
        }

        private Future<Envelope> post(Envelope e, Cut cut) {
            Envelope out = e.withOutboundId();
            RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI(url)
                    .setConnectTimeout(timeoutMs).setIdleTimeout(timeoutMs);
            return client.request(options).compose(req -> {
                if (cut != null && !cut.keep(req)) return Future.<HttpClientResponse>failedFuture(cut.failure());
                out.headers().asMap().forEach((header, value) -> {
                    if (!HOP_BY_HOP.contains(header.toLowerCase(Locale.ROOT))) req.putHeader(header, value);
                });
                req.putHeader("content-length", String.valueOf(e.body().size()));
                // Paused as it arrives, so that no byte of the response is lost before reply() reads it.
                req.response().onSuccess(HttpClientResponse::pause);
                return req.send(e.body().open());
            }).compose(res -> reply(e, res)).recover(failure -> Future.failedFuture(failure instanceof TimeoutException
                    ? new NackException("the request to " + name + " timed out: " + failure.getMessage(), true)
                    : failure));
        }

        private Future<Envelope> reply(Envelope e, HttpClientResponse res) {
            int status = res.statusCode();
            if (status / 100 == 2) {
                Headers headers = headers(res.headers());
                return blobs.write(e.nextKey(), res, maxReplyBytes)
                        .map(body -> new Envelope(e.id(), e.flow(), e.step() + 1, headers, e.props(), e.keys(), body, e.attachments(), new Views()));
            }
            boolean retryable = status == 429 || status / 100 == 5;
            return head(res).compose(reason -> Future.failedFuture(new NackException("HTTP " + status + " from " + name + ": " + reason, retryable)));
        }

        /** The first {@value #REASON_BYTES} bytes of the response body as text; the rest is read and dropped. */
        private static Future<String> head(HttpClientResponse res) {
            Promise<String> done = Promise.promise();
            Buffer kept = Buffer.buffer();
            res.exceptionHandler(failure -> done.tryComplete(kept.toString()));
            res.endHandler(v -> done.tryComplete(kept.toString()));
            res.handler(chunk -> {
                int room = REASON_BYTES - kept.length();
                if (room > 0) kept.appendBuffer(chunk, 0, Math.min(room, chunk.length()));
            });
            res.resume();
            return done.future();
        }

        @Override
        public Future<Void> close() {
            return client.close();
        }

        /** The cut of one bounded send: its outcome, the timer armed for it, and its request once there is one. */
        private final class Cut {
            private final Deadline deadline;
            private final Promise<Envelope> out = Promise.promise();
            private volatile Timer.Cancellable armed;
            private volatile HttpClientRequest request;

            Cut(Deadline deadline) {
                this.deadline = deadline;
            }

            void arm(Duration left) {
                armed = timer.schedule(left, this::fire);
            }

            private void fire() {
                if (out.future().isComplete()) return;
                Duration left = deadline.remaining();
                if (left.isPositive()) {
                    arm(left);
                    return;
                }
                if (out.tryFail(failure())) {
                    HttpClientRequest sent = request;
                    if (sent != null) sent.reset();
                }
            }

            /** Keeps {@code req} for a later cut to reset; false, and {@code req} reset, when the send was cut already. */
            boolean keep(HttpClientRequest req) {
                request = req;
                if (!out.future().isComplete()) return true;
                req.reset();
                return false;
            }

            NackException failure() {
                return new NackException("deadline passed: the request to " + name + " was cut", true);
            }
        }
    }
}
