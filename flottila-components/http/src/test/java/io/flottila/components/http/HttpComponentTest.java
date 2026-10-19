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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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
}
