package io.flottila.components.http;

import io.flottila.api.Ack;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTTP mapping (ARCHITECTURE §4): statuses per Ack and failure, limits never defaulted, the body streamed both ways. */
@Timeout(60)
class HttpComponentTest {

    static Vertx vertx;
    static HttpClientAgent raw;
    final FakeRuntime rt = FakeRuntime.create();
    final HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids());
    final List<Consumer> started = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
        raw = vertx.createHttpClient();
    }

    @AfterAll
    static void stop() {
        raw.close().await();
        vertx.close().await();
    }

    @AfterEach
    void down() {
        for (Consumer c : started) c.stop().await();
        for (HttpServer s : servers) s.close().await();
    }

    HttpComponent.HttpConsumer listen(Function<Envelope, Future<Ack>> handler, long maxBodyBytes) {
        EndpointConfig in = new EndpointConfig("http-in", URI.create("http://127.0.0.1:0/in"), Map.of("port", 0, "path", "/in", "flow", "invoice-sync", "maxBodyBytes", maxBodyBytes));
        HttpComponent.HttpConsumer c = (HttpComponent.HttpConsumer) http.consumer(in, handler);
        c.start().await();
        started.add(c);
        return c;
    }

    record Answer(int status, io.vertx.core.MultiMap headers, Buffer body) {}

    Answer post(int port, String path, Buffer body, Map<String, String> headers) {
        return raw.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + port + path)).compose(req -> {
            headers.forEach(req::putHeader);
            return req.send(body);
        }).compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.headers(), b))).await();
    }

    @Test
    void aReplyIsA200WithTheReplysHeadersAndBodyAndTheHandlerRunsOffTheEventLoop() {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        List<Boolean> onVirtual = new CopyOnWriteArrayList<>();
        HttpComponent.HttpConsumer c = listen(e -> {
            seen.add(e);
            onVirtual.add(Thread.currentThread().isVirtual());
            return Future.succeededFuture(new Ack.Reply(e.next(rt.store(e, Buffer.buffer("<Ack/>"))).withHeader("content-type", "application/xml").withHeader("x-flow", e.flow())));
        }, 4096);
        assertTrue(c.port() > 0, "an ephemeral port was taken");
        Answer a = post(c.port(), "/in", Buffer.buffer("<Invoice/>"), Map.of("Content-Type", "application/xml", "X-Request-Id", "r-1"));
        assertEquals(200, a.status());
        assertEquals("<Ack/>", a.body().toString());
        assertEquals("application/xml", a.headers().get("content-type"));
        assertEquals("invoice-sync", a.headers().get("x-flow"));
        assertEquals(1, seen.size());
        Envelope in = seen.get(0);
        assertEquals("invoice-sync", in.flow());
        assertEquals(0, in.step());
        assertEquals("<Invoice/>", in.body().whole().toString());
        assertEquals("r-1", in.headers().get("x-request-id"), "header names arrive lower-cased");
        assertEquals("application/xml", in.headers().get("content-type"));
        assertEquals(Optional.of("POST"), in.get(HttpComponent.METHOD));
        assertEquals(Optional.of("/in"), in.get(HttpComponent.PATH));
        assertEquals(List.of(true), onVirtual, "the handler runs on the message's virtual thread, never on the socket's event loop");
        assertEquals(Health.Status.UP, http.health().await().status());
    }

    @Test
    void acceptedIs202NacksAre503Or400AndAFailedHandlerIs500WithTheReason() {
        HttpComponent.HttpConsumer c = listen(e -> switch (e.headers().get("x-case")) {
            case "accept" -> Future.succeededFuture(new Ack.Accepted(e.id()));
            case "busy" -> Future.succeededFuture(new Ack.Nack("admission: 3/2 requests", true));
            case "bad" -> Future.succeededFuture(new Ack.Nack("no invoice id", false));
            case "throw" -> throw new IllegalStateException("validation failed at step 'validate'");
            default -> Future.failedFuture(new IllegalStateException("partner down"));
        }, 4096);
        Answer accepted = post(c.port(), "/in", Buffer.buffer("x"), Map.of("x-case", "accept"));
        assertEquals(202, accepted.status());
        assertTrue(accepted.headers().get(HttpComponent.H_MESSAGE_ID).startsWith("m-"), "the message id travels back: " + accepted.headers().get(HttpComponent.H_MESSAGE_ID));
        Answer busy = post(c.port(), "/in", Buffer.buffer("x"), Map.of("x-case", "busy"));
        assertEquals(503, busy.status());
        assertEquals("1", busy.headers().get("retry-after"));
        assertTrue(busy.body().toString().contains("admission"));
        Answer bad = post(c.port(), "/in", Buffer.buffer("x"), Map.of("x-case", "bad"));
        assertEquals(400, bad.status());
        assertEquals("no invoice id", bad.body().toString());
        Answer thrown = post(c.port(), "/in", Buffer.buffer("x"), Map.of("x-case", "throw"));
        assertEquals(500, thrown.status());
        assertTrue(thrown.body().toString().contains("validation failed"), thrown.body().toString());
        Answer failed = post(c.port(), "/in", Buffer.buffer("x"), Map.of("x-case", "fail"));
        assertEquals(500, failed.status());
        assertTrue(failed.body().toString().contains("partner down"));
    }

    /** The store as the http consumer sees it, counting the streaming writes it was asked for. */
    static final class CountingStore implements io.flottila.api.BlobStore {
        final io.flottila.api.BlobStore inner; final java.util.concurrent.atomic.AtomicInteger streamingWrites = new java.util.concurrent.atomic.AtomicInteger();
        CountingStore(io.flottila.api.BlobStore inner) { this.inner = inner; }
        @Override public long treeLimit() { return inner.treeLimit(); }
        @Override public Future<io.flottila.api.Body> write(io.flottila.api.BlobKey key, Buffer whole, long maxSize) { return inner.write(key, whole, maxSize); }
        @Override public Future<io.flottila.api.Body> write(io.flottila.api.BlobKey key, io.vertx.core.streams.ReadStream<Buffer> in, long maxSize) { streamingWrites.incrementAndGet(); return inner.write(key, in, maxSize); }
        @Override public io.vertx.core.streams.ReadStream<Buffer> open(io.flottila.api.BlobKey key) { return inner.open(key); }
        @Override public Future<Buffer> read(io.flottila.api.BlobKey key) { return inner.read(key); }
        @Override public Future<Void> persist(io.flottila.api.BlobKey key) { return inner.persist(key); }
    }

    @Test
    void theBodyLimitIs413WrongPathIs404WrongMethodIs405() {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        CountingStore store = new CountingStore(rt.blobs());
        HttpComponent counted = new HttpComponent(vertx, store, rt.ids());
        EndpointConfig in = new EndpointConfig("http-in", URI.create("http://127.0.0.1:0/in"), Map.of("port", 0, "path", "/in", "flow", "invoice-sync", "maxBodyBytes", 16L));
        HttpComponent.HttpConsumer c = (HttpComponent.HttpConsumer) counted.consumer(in, e -> { seen.add(e); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        c.start().await();
        started.add(c);
        Answer declared = post(c.port(), "/in", Buffer.buffer("x".repeat(17)), Map.of());
        assertEquals(413, declared.status(), "declared above the limit: refused before a byte is stored");
        assertEquals(0, store.streamingWrites.get(), "the store was never asked: the declared length is refused at the door");
        Answer chunked = raw.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + c.port() + "/in"))
                .compose(req -> { req.setChunked(true); return req.send(Buffer.buffer("y".repeat(40))); })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.headers(), b))).await();
        assertEquals(413, chunked.status(), "undeclared length: the store stops at the limit");
        assertEquals(1, store.streamingWrites.get(), "undeclared: the store is asked and refuses at the limit");
        assertEquals(202, post(c.port(), "/in", Buffer.buffer("x".repeat(16)), Map.of()).status(), "at the limit is fine");
        assertEquals(404, post(c.port(), "/elsewhere", Buffer.buffer("x"), Map.of()).status());
        Answer get = raw.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + c.port() + "/in"))
                .compose(req -> req.send()).compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.headers(), b))).await();
        assertEquals(405, get.status());
        assertEquals("POST, PUT", get.headers().get("allow"));
        assertEquals(1, seen.size(), "only the body within the limit reached the handler");
        assertFalse(rt.blobs().contains(io.flottila.api.BlobKey.body(seen.get(0).id(), 1)), "nothing else was stored under that message");
    }

    @Test
    void limitsAreNeverDefaultedAndAStoppedConsumerFreesThePort() {
        EndpointConfig noLimit = new EndpointConfig("in", URI.create("http://127.0.0.1:0/in"), Map.of("port", 0, "path", "/in"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> http.consumer(noLimit, e -> Future.succeededFuture(new Ack.Accepted(e.id())))).getMessage().contains("maxBodyBytes"));
        EndpointConfig noReplyLimit = new EndpointConfig("out", URI.create("http://127.0.0.1:1/x"), Map.of("url", "http://127.0.0.1:1/x"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> http.producer(noReplyLimit)).getMessage().contains("maxBodyBytes"));
        HttpComponent.HttpConsumer c = listen(e -> Future.succeededFuture(new Ack.Accepted(e.id())), 4096);
        int port = c.port();
        c.stop().await();
        started.remove(c);
        assertEquals(-1, c.port());
        assertEquals(Health.Status.DOWN, http.health().await().status());
        Future<Answer> after = raw.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + port + "/in")).compose(req -> req.send(Buffer.buffer("x")))
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.headers(), b)));
        after.otherwiseEmpty().await();
        assertTrue(after.failed(), "connection refused: the port is free again");
    }

    // --- P1-09g (D-072): where a consumer would listen, answered from the config alone

    /** A loopback port free right now: bound once and released. */
    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    static Optional<InetSocketAddress> where(String host, int port) {
        return Optional.of(InetSocketAddress.createUnresolved(host, port));
    }

    static EndpointConfig in(String uri, Map<String, Object> options) {
        return new EndpointConfig("http-in", URI.create(uri), options);
    }

    /**
     * For a port set by option and one set by the endpoint's URI, listensOn answers loopback on that port, unresolved, and the
     * consumer built from the same config, started, listens there: a POST to that address reaches it.
     */
    @Test
    void listensOnIsWhereTheStartedConsumerBinds() throws IOException {
        for (boolean byOption : List.of(true, false)) {
            int free = freePort();
            EndpointConfig cfg = byOption
                    ? in("http://http-in/in", Map.of("host", "127.0.0.1", "port", free, "path", "/in", "flow", "f", "maxBodyBytes", 4096L))
                    : in("http://127.0.0.1:" + free + "/in", Map.of("path", "/in", "flow", "f", "maxBodyBytes", 4096L));
            String how = byOption ? "the port option" : "the URI's port";

            Optional<InetSocketAddress> where = http.listensOn(cfg);

            assertEquals(where("127.0.0.1", free), where, how);
            assertTrue(where.get().isUnresolved(), how + ": unresolved, no DNS");
            HttpComponent.HttpConsumer c = (HttpComponent.HttpConsumer) http.consumer(cfg, e -> Future.succeededFuture(new Ack.Accepted(e.id())));
            c.start().await();
            started.add(c);
            assertEquals(where.get().getPort(), c.port(), how + ": the started consumer listens on the port listensOn answered");
            assertEquals(202, post(where.get().getPort(), "/in", Buffer.buffer("x"), Map.of()).status(), how + ": and is reached at " + where.get());
        }
    }

    @Test
    void listensOnDefaultsToLoopbackAndUriPortElseZero() throws IOException {
        assertEquals(where("127.0.0.1", 9000), http.listensOn(in("http://http-in/in", Map.of("port", 9000))), "no host option: loopback");
        assertEquals(where("0.0.0.0", 9000), http.listensOn(in("http://http-in/in", Map.of("host", "0.0.0.0", "port", 9000))), "the host as configured");
        assertEquals(where("127.0.0.1", 7000), http.listensOn(in("http://orders.example:7000/in", Map.of())),
                "no port option: the URI's port; the URI's host is not where a consumer binds");
        assertEquals(where("127.0.0.1", 9000), http.listensOn(in("http://http-in:7000/in", Map.of("port", 9000))), "the port option wins over the URI's");
        assertEquals(where("127.0.0.1", 0), http.listensOn(in("http://http-in:7000/in", Map.of("port", 0))), "port 0 as configured wins too: any free port");
        assertEquals(where("127.0.0.1", 0), http.listensOn(in("http://http-in", Map.of())), "neither: 0 (the URI a deploy builds has no port)");
        try (ServerSocket held = new ServerSocket(0, 0, InetAddress.ofLiteral("127.0.0.1"))) {
            assertEquals(where("127.0.0.1", held.getLocalPort()), http.listensOn(in("http://http-in/in", Map.of("port", held.getLocalPort()))),
                    "answered from the config alone: nothing is bound, so a port another socket holds is answered as it is");
        }
        assertEquals(Health.Status.DOWN, http.health().await().status(), "no consumer was started");
    }

    HttpServer partner(Function<io.vertx.core.http.HttpServerRequest, Void> handler) {
        HttpServer s = vertx.createHttpServer().requestHandler(handler::apply).listen(0, "127.0.0.1").await();
        servers.add(s);
        return s;
    }

    Producer producerTo(HttpServer partner, String path, Map<String, Object> extra) {
        Map<String, Object> options = new java.util.HashMap<>(Map.of("url", "http://127.0.0.1:" + partner.actualPort() + path, "maxBodyBytes", 4096));
        options.putAll(extra);
        return http.producer(new EndpointConfig("partner", URI.create("http://127.0.0.1:" + partner.actualPort() + path), options));
    }

    @Test
    void theProducerPostsTheBodyWithTheHeadersAndTheResponseIsTheReplyEnvelope() {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            seen.add(req.method().name() + " " + req.path() + " " + req.getHeader("x-request-id") + " " + req.getHeader("content-length") + " " + b);
            req.response().setStatusCode(201).putHeader("content-type", "application/xml").putHeader("x-partner", "p1").end("<PartnerAck/>");
        }); return null; });
        Producer p = producerTo(partner, "/invoices", Map.of());
        Envelope in = rt.ingress("invoice-sync", Headers.empty().with("x-request-id", "r-9").with("content-type", "application/xml"), Buffer.buffer("<Invoice/>")).advance();
        Envelope reply = p.send(in).await();
        assertEquals(List.of("POST /invoices r-9 10 <Invoice/>"), seen);
        assertEquals(2, reply.step(), "the reply is the next step's envelope");
        assertEquals(in.id(), reply.id());
        assertEquals("<PartnerAck/>", reply.body().whole().toString());
        assertEquals("application/xml", reply.headers().get("content-type"));
        assertEquals("p1", reply.headers().get("x-partner"));
        assertEquals(Optional.of(201), reply.get(HttpComponent.STATUS));
        assertEquals(2, reply.body().key().step());
        p.send(in).await();
        assertEquals(2, seen.size(), "a second send re-opens the body and posts it again");
        assertEquals(2, ((HttpComponent.HttpProducer) p).sends());
        p.close().await();
    }

    @Test
    void non2xxResponsesFailTheSendRetryableFor503And429FinalOtherwise() {
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            int status = Integer.parseInt(req.path().substring(1));
            req.response().setStatusCode(status).end(req.getHeader("x-long") != null ? "status " + status + " " + "e".repeat(100_000) : "status " + status + " body");
        }); return null; });
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        for (int status : List.of(503, 429)) {
            Producer p = producerTo(partner, "/" + status, Map.of());
            Future<Envelope> f = p.send(in);
            f.otherwiseEmpty().await();
            assertTrue(f.cause() instanceof NackException n && n.retryable() && n.reason().contains("status " + status), String.valueOf(f.cause()));
            p.close().await();
        }
        for (int status : List.of(400, 404, 500)) {
            Producer p = producerTo(partner, "/" + status, Map.of());
            Future<Envelope> f = p.send(in);
            f.otherwiseEmpty().await();
            assertTrue(f.cause() instanceof HttpStatusException x && x.status() == status && x.retryable() == (status >= 500) && x.getMessage().contains("status " + status + " body"), String.valueOf(f.cause()));
            p.close().await();
        }
        Producer p = producerTo(partner, "/502", Map.of());
        Future<Envelope> big = p.send(in.withHeader("x-long", "1"));
        big.otherwiseEmpty().await();
        assertTrue(big.cause() instanceof HttpStatusException x && x.status() == 502 && x.getMessage().startsWith("HTTP 502: status 502 eee"), String.valueOf(big.cause()));
        assertTrue(big.cause().getMessage().length() < HttpComponent.ERROR_BODY_BYTES + 32, "a 100 kB error page is cut at " + HttpComponent.ERROR_BODY_BYTES + " bytes, never buffered whole: " + big.cause().getMessage().length());
        p.close().await();
        HttpStatusException e = new HttpStatusException(502, "");
        assertEquals("HTTP 502", e.getMessage());
        assertTrue(e.retryable());
    }

    @Test
    void aReplyAboveTheLimitFailsAndASlowPartnerTimesOut() {
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            if (req.path().equals("/big")) req.response().end("z".repeat(5000));
            // "/never": no response at all
        }); return null; });
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        Producer toBig = producerTo(partner, "/big", Map.of());
        Future<Envelope> big = toBig.send(in);
        big.otherwiseEmpty().await();
        assertTrue(big.cause() instanceof io.flottila.api.BodyTooLargeException, "the reply is written under the endpoint's limit like any body: " + big.cause());
        toBig.close().await();
        Producer toNever = producerTo(partner, "/never", Map.of("timeoutMs", 200));
        Future<Envelope> slow = toNever.send(in);
        slow.otherwiseEmpty().await();
        assertTrue(slow.failed(), "timeoutMs bounds the wait: " + slow.cause());
        toNever.close().await();
    }

    /** How long {@link LateListeningStore} waits for a reply to end on its own before it listens anyway (a paused reply never does). */
    static final long LATE_LISTEN_MS = 1_000;

    /**
     * The store as the producer sees it, listening late — which the {@code BlobStore} contract allows: a stream "may be handed over
     * paused, the store resumes it once its handlers are attached". It attaches its handlers only once the reply has ended (its
     * {@code end()} future, which attaches nothing) or after {@link #LATE_LISTEN_MS}, and records whether the reply ended unheard.
     */
    static final class LateListeningStore implements io.flottila.api.BlobStore {
        final io.flottila.api.BlobStore inner;
        final AtomicInteger attached = new AtomicInteger();
        final AtomicBoolean endedUnheard = new AtomicBoolean();
        LateListeningStore(io.flottila.api.BlobStore inner) { this.inner = inner; }
        @Override public long treeLimit() { return inner.treeLimit(); }
        @Override public Future<io.flottila.api.Body> write(io.flottila.api.BlobKey key, Buffer whole, long maxSize) { return inner.write(key, whole, maxSize); }
        @Override public io.vertx.core.streams.ReadStream<Buffer> open(io.flottila.api.BlobKey key) { return inner.open(key); }
        @Override public Future<Buffer> read(io.flottila.api.BlobKey key) { return inner.read(key); }
        @Override public Future<Void> persist(io.flottila.api.BlobKey key) { return inner.persist(key); }
        @Override public Future<io.flottila.api.Body> write(io.flottila.api.BlobKey key, io.vertx.core.streams.ReadStream<Buffer> in, long maxSize) {
            HttpClientResponse reply = (HttpClientResponse) in;   // the producer hands its partner's response over
            io.vertx.core.Context ctx = Vertx.currentContext();
            io.vertx.core.Promise<io.flottila.api.Body> written = io.vertx.core.Promise.promise();
            AtomicBoolean listened = new AtomicBoolean();
            Runnable listen = () -> {
                attached.incrementAndGet();
                try {
                    inner.write(key, in, maxSize).onComplete(written);
                } catch (RuntimeException refused) {   // e.g. "Response already ended": the reply's bytes went by unheard
                    written.fail(refused);
                }
            };
            reply.end().onComplete(done -> {   // an end after the store listened is the normal end, not this
                if (listened.compareAndSet(false, true)) {
                    endedUnheard.set(true);
                    listen.run();
                }
            });
            ctx.owner().setTimer(LATE_LISTEN_MS, id -> ctx.runOnContext(v -> { if (listened.compareAndSet(false, true)) listen.run(); }));
            return written.future();
        }
    }

    /**
     * FIX-21: the producer hands its partner's reply to the store paused, like the consumer hands its request: a store that
     * listens late still gets the whole 1-byte reply, the reply never ends before anyone listened (else the producer is left with
     * "Response already ended" and its retryable step sends again), and the partner sees the send exactly once.
     */
    @Test
    void theReplyIsHandedToTheStorePausedSoALateListeningStoreGetsItWholeAndThePartnerSeesTheSendOnce() throws Exception {
        AtomicInteger partnerSaw = new AtomicInteger();
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            partnerSaw.incrementAndGet();
            req.response().putHeader("content-type", "text/plain").end("k");
        }); return null; });
        LateListeningStore store = new LateListeningStore(rt.blobs());
        HttpComponent late = new HttpComponent(vertx, store, rt.ids());
        String url = "http://127.0.0.1:" + partner.actualPort() + "/tiny";
        Producer p = late.producer(new EndpointConfig("partner", URI.create(url), Map.of("url", url, "maxBodyBytes", 16)));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        java.util.concurrent.CompletableFuture<Future<Envelope>> sent = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("message").start(() -> {   // as in a flow: the send starts on the message's virtual thread
            Future<Envelope> f = p.send(in);
            try {
                f.otherwiseEmpty().await(10, TimeUnit.SECONDS);
                sent.complete(f);
            } catch (TimeoutException stuck) {
                sent.completeExceptionally(stuck);
            }
        });
        Future<Envelope> f = sent.get(15, TimeUnit.SECONDS);
        assertEquals(1, partnerSaw.get(), "the partner saw the send exactly once");
        assertEquals(1, store.attached.get(), "the store listened once");
        assertFalse(store.endedUnheard.get(), "the reply ended before the store listened: its bytes went by unheard; the send: " + (f.failed() ? f.cause() : "succeeded"));
        assertTrue(f.succeeded(), "the send returns the partner's reply: " + f.cause());
        assertEquals("k", f.result().body().whole().toString());
        p.close().await(10, TimeUnit.SECONDS);
    }

    // ---- P2-09a (D-099): the target a producer calls ------------------------------------------------------------------

    static EndpointConfig out(String uri, Map<String, Object> options) {
        return new EndpointConfig("partner", URI.create(uri), options);
    }

    /**
     * The target of an http producer is {@code scheme://host:port} of its {@code url} (else of the endpoint URI): lower-cased,
     * the scheme's default port filled in (https keeps 443), never userinfo, path, query or fragment — two endpoints on one
     * partner's paths name one target. Answered from the config alone: a host nobody resolves is answered as written.
     */
    @Test
    void targetIsSchemeHostPortFromUrlWithoutUserinfoPathQuery() {
        assertEquals(Optional.of("https://partner.example.com:443"),
                http.target(out("http://partner/x", Map.of("url", "https://User:Secret@Partner.Example.COM/orders/v1?draft=true#top"))),
                "userinfo, path, query and fragment dropped, lower-cased, https default port");
        assertEquals(Optional.of("http://partner.example:80"), http.target(out("http://partner/x", Map.of("url", "http://partner.example/in"))), "http default port");
        assertEquals(Optional.of("http://partner.example:8080"), http.target(out("http://partner/x", Map.of("url", "http://partner.example:8080/in"))), "an explicit port");
        assertEquals(Optional.of("https://partner.example:8443"), http.target(out("http://partner/x", Map.of("url", "HTTPS://PARTNER.example:8443"))), "an upper-case scheme, no path");
        assertEquals(Optional.of("http://[::1]:9000"), http.target(out("http://partner/x", Map.of("url", "http://[::1]:9000/x"))), "an IPv6 literal keeps its brackets");
        assertEquals(Optional.of("http://orders.example:7000"), http.target(out("http://Orders.Example:7000/in?a=b", Map.of())), "no url option: the endpoint URI");
        assertEquals(Optional.of("https://orders.example:443"), http.target(out("https://orders.example/in", Map.of())), "no url option, https URI: 443");
        assertEquals(http.target(out("http://partner/x", Map.of("url", "http://partner.example:8080/a"))),
                http.target(out("http://partner/y", Map.of("url", "http://partner.example:8080/b/c?d=e"))), "two paths of one partner: one target");
        assertEquals(Optional.of("https://partner.invalid:443"), http.target(out("http://partner/x", Map.of("url", "https://partner.invalid/never"))),
                "a host that never resolves is answered as configured: no DNS");
    }

    /**
     * A host java.net.URI cannot read as a server name (an underscore: its authority is registry-based, getHost() is null) still
     * gets a key shared by every endpoint on that partner: from the raw authority, userinfo stripped, lower-cased, the scheme's
     * default port filled in (D-099, architect 2026-11-03).
     */
    @Test
    void aHostWithAnUnderscoreStillGetsASharedTarget() {
        assertEquals(Optional.of("https://my_host.example:443"), http.target(out("http://partner/x", Map.of("url", "https://User@My_Host.example:443/x"))),
                "userinfo stripped, lower-cased, the port as written");
        assertEquals(Optional.of("https://my_host.example:443"), http.target(out("http://partner/x", Map.of("url", "https://u:pw@MY_HOST.example/y?q=1"))),
                "the https default port filled in, path and query dropped");
        assertEquals(Optional.of("http://my_host.example:80"), http.target(out("http://partner/x", Map.of("url", "HTTP://My_Host.example/z"))),
                "the http default port, the scheme lower-cased");
        assertEquals(Optional.of("http://my_host.example:8080"), http.target(out("http://My_Host.example:8080/in", Map.of())), "no url option: the endpoint URI");
        assertEquals(http.target(out("http://partner/x", Map.of("url", "https://my_host.example/a"))),
                http.target(out("http://partner/y", Map.of("url", "https://My_Host.example:443/b#c"))), "two paths of one partner: one target");
    }

    /** An IPv6 literal without a port: the colons inside its brackets are not a port; the scheme's default port is filled in. */
    @Test
    void anIpv6LiteralWithoutAPortGetsTheDefaultPort() {
        assertEquals(Optional.of("http://[::1]:80"), http.target(out("http://partner/x", Map.of("url", "http://[::1]/x"))), "http, no port");
        assertEquals(Optional.of("https://[::1]:443"), http.target(out("http://partner/x", Map.of("url", "https://[::1]"))), "https, no port, no path");
        assertEquals(Optional.of("http://[fe80::1]:80"), http.target(out("http://[FE80::1]/in", Map.of())), "no url option: the endpoint URI, lower-cased");
    }

    /**
     * Vert.x 5.1.10 parses the producer's url with {@code java.net.URL} (RequestOptions.setAbsoluteURI, agent/work/P2-09a-vertx-absuri.txt),
     * which takes a query character {@code java.net.URI} refuses (an unencoded {@code |}) and a space: the producer sends to such a
     * url, so the endpoint still deploys — its target is still {@code scheme://host:port}, answered without a throw.
     */
    @Test
    void aUrlTheProducerAcceptsButURIRejectsStillDeploys() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> URI.create("http://127.0.0.1:1/in?x=a|b"), "fixture: java.net.URI refuses the url");
        assertEquals(Optional.of("http://partner.example:8080"), http.target(out("http://partner/x", Map.of("url", "http://User@Partner.Example:8080/a b?q=1 2|3"))),
                "a space and a | in path and query: userinfo stripped, lower-cased, the port as written");
        assertEquals(Optional.of("https://partner.example:443"), http.target(out("http://partner/x", Map.of("url", "https://partner.example/in?x=a|b"))), "the https default port");

        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(req -> {
            seen.add(req.uri());
            req.response().end("ok");
            return null;
        });
        String url = "http://127.0.0.1:" + partner.actualPort() + "/in?x=a|b";
        EndpointConfig cfg = new EndpointConfig("partner", URI.create("http://127.0.0.1:" + partner.actualPort() + "/in"), Map.of("url", url, "maxBodyBytes", 4096));
        assertEquals(Optional.of("http://127.0.0.1:" + partner.actualPort()), http.target(cfg), "the target of the url the producer sends to");
        Producer p = http.producer(cfg);
        try {
            java.util.concurrent.CompletableFuture<Future<Envelope>> sent = new java.util.concurrent.CompletableFuture<>();
            Thread.ofVirtual().name("message").start(() -> {
                Future<Envelope> f = p.send(rt.ingress("f", Headers.empty(), Buffer.buffer("x")));
                try {
                    f.otherwiseEmpty().await(10, TimeUnit.SECONDS);
                    sent.complete(f);
                } catch (TimeoutException stuck) {
                    sent.completeExceptionally(stuck);
                }
            });
            Future<Envelope> f = sent.get(15, TimeUnit.SECONDS);
            assertTrue(f.succeeded(), "the producer sends to the url java.net.URI refuses: " + f.cause());
            assertEquals(List.of("/in?x=a|b"), seen, "the partner saw the request as the url names it");
        } finally {
            p.close().await(10, TimeUnit.SECONDS);
        }
    }

    /** A url with an empty host (as the producer parses it) names no target: its sends get a limit of their own, never a key {@code http://:80}. */
    @Test
    void aUrlWithNoHostHasNoTarget() {
        assertEquals(Optional.empty(), http.target(out("http://partner/x", Map.of("url", "http:///x"))), "http:///x has no host");
    }

    /**
     * A url the producer cannot send to (a scheme other than http/https, no scheme, a port that is no number) refuses the build:
     * an IllegalArgumentException naming the endpoint and its {@code url} option — never the url itself, which may carry a
     * password, neither in its message nor anywhere down its cause chain.
     */
    @Test
    void aUrlTheProducerCannotSendToRefusesTheBuildNamingTheEndpointNotTheUrl() {
        for (String url : List.of("foo://x", "x:abc", "ftp://h/x", "https://u:Secret@h:abc/x")) {
            EndpointConfig cfg = new EndpointConfig("partner-orders", URI.create("http://partner/x"), Map.of("url", url));
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> http.target(cfg), url + ": refused");
            String message = String.valueOf(refused.getMessage());
            assertTrue(message.contains("partner-orders"), url + ": names the endpoint: " + message);
            assertTrue(message.contains("url"), url + ": names the option: " + message);
            assertFalse(message.contains(url), url + ": the url itself is not in the message: " + message);
            assertFalse(message.contains("Secret") || message.contains("u:"), url + ": no userinfo in the message: " + message);
            for (Throwable c = refused.getCause(); c != null; c = c.getCause()) {
                assertFalse(String.valueOf(c.getMessage()).contains("Secret"), url + ": no password down the cause chain: " + c);
            }
        }
    }

    /**
     * P2-09b (P2-09a r3 note): the refusal names what was read. Without a {@code url} option the target is read from the endpoint
     * URI, and a URI the producer cannot send to is refused as "the endpoint URI" — not as option 'url', which the endpoint does
     * not set; with a {@code url} option it is "option 'url'" and no endpoint URI. Neither message carries what was read.
     */
    @Test
    void aBadEndpointUriWithoutAUrlOptionIsNamedAsTheEndpointUri() {
        for (String uri : List.of("ftp://h/x", "https://u:Secret@h:abc/x")) {
            EndpointConfig cfg = new EndpointConfig("partner-orders", URI.create(uri), Map.of());
            String message = String.valueOf(assertThrows(IllegalArgumentException.class, () -> http.target(cfg), uri + ": refused").getMessage());
            assertTrue(message.contains("partner-orders"), uri + ": names the endpoint: " + message);
            assertTrue(message.contains("the endpoint URI"), uri + ": names the endpoint URI as what was read: " + message);
            assertFalse(message.contains("option 'url'"), uri + ": not the url option, which is not set: " + message);
            assertFalse(message.contains(uri) || message.contains("Secret"), uri + ": the URI itself is not in the message: " + message);
        }
        EndpointConfig withUrl = new EndpointConfig("partner-orders", URI.create("http://partner/x"), Map.of("url", "ftp://h/x"));
        String message = String.valueOf(assertThrows(IllegalArgumentException.class, () -> http.target(withUrl), "a bad url: refused").getMessage());
        assertTrue(message.contains("option 'url'"), "with a url option: names option 'url': " + message);
        assertFalse(message.contains("endpoint URI"), "with a url option: not the endpoint URI, which was not read: " + message);
    }
}
