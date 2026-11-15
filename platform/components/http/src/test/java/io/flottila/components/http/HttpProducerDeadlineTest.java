package io.flottila.components.http;

import io.flottila.api.Deadline;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.flottila.components.http.HttpComponentTest.soon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15b (D-113 draft), beside {@link HttpComponentTest}: the http producer's own bounds. A {@code timeoutMs} expiry is the same
 * retryable {@link NackException} as a cut send, for a plain send and a bounded one; a bounded send with no time left sends no
 * request and is not counted in {@code sends()}; a send cut while it still waits for a pooled connection is reset unsent when
 * the connection comes — the partner never sees it; a re-read that fires after its send settled (its cancel came too late)
 * arms no other; the three-argument component cuts on Vert.x timers.
 */
@Timeout(60)
class HttpProducerDeadlineTest {

    static Vertx vertx;
    final FakeRuntime rt = FakeRuntime.create();
    /** The producers' cuts run on the case's fake timer: nothing is cut unless the case moves the fake clock. */
    final HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids(), rt.timer());
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() { vertx = Vertx.vertx(); }

    @AfterAll
    static void stop() { vertx.close().await(); }

    @AfterEach
    void closeServers() { servers.forEach(s -> s.close().await()); }

    /** A partner that records each request's {@code x-tag} and answers it only when the case ends its response. */
    final List<String> tags = new CopyOnWriteArrayList<>();
    final Map<String, HttpServerResponse> open = new java.util.concurrent.ConcurrentHashMap<>();

    /** The url of a fresh partner as above. */
    String partner() {
        HttpServer s = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            String tag = req.getHeader("x-tag");
            tags.add(tag);
            open.put(tag, req.response());
        })).listen(0, "127.0.0.1").await();
        servers.add(s);
        return "http://127.0.0.1:" + s.actualPort() + "/in";
    }

    /** A producer of {@link #http} to a fresh partner, {@code extra} added to its options. */
    HttpComponent.HttpProducer producer(Map<String, Object> extra) {
        String url = partner();
        Map<String, Object> options = new java.util.HashMap<>(Map.of("url", url, "maxBodyBytes", 4096));
        options.putAll(extra);
        return (HttpComponent.HttpProducer) http.producer(new EndpointConfig("partner", URI.create(url), options));
    }

    Envelope tagged(String tag) {
        return rt.ingress("f", Headers.empty().with("x-tag", tag), Buffer.buffer(tag));
    }

    static Throwable failureOf(Future<?> f) throws Exception {
        f.otherwiseEmpty().await(10, TimeUnit.SECONDS);
        assertTrue(f.failed(), "expected a failure: " + f);
        return f.cause();
    }

    @Test
    void aTimeoutMsExpiryIsARetryableNackForAPlainAndABoundedSend() throws Exception {
        HttpComponent.HttpProducer p = producer(Map.of("timeoutMs", 200));
        try {
            NackException plain = assertInstanceOf(NackException.class, failureOf(p.send(tagged("plain"))), "the partner never answers");
            assertTrue(plain.retryable(), "retryable, as a cut is: " + plain);
            NackException bounded = assertInstanceOf(NackException.class, failureOf(p.send(tagged("bounded"), () -> Duration.ofMinutes(1))),
                    "a bounded send with a minute left still times out after timeoutMs");
            assertTrue(bounded.retryable(), bounded.toString());
            assertEquals(List.of("plain", "bounded"), tags, "both reached the partner");
        } finally {
            p.close().await();
        }
    }

    @Test
    void aBoundedSendWithNoTimeLeftSendsNoRequestAndIsNotCounted() throws Exception {
        HttpComponent.HttpProducer p = producer(Map.of());
        try {
            for (Duration left : List.of(Duration.ZERO, Duration.ofMillis(-5))) {
                NackException refused = assertInstanceOf(NackException.class, failureOf(p.send(tagged("late"), () -> left)), left + " left");
                assertTrue(refused.retryable(), refused.toString());
            }
            assertEquals(0, p.sends(), "no request, not counted");
            Future<Envelope> inTime = p.send(tagged("in-time"), () -> Duration.ofMinutes(1));
            assertTrue(soon(() -> open.containsKey("in-time")), "witness: with time left the request goes out: " + tags);
            open.get("in-time").setStatusCode(202).end();
            inTime.await(10, TimeUnit.SECONDS);
            assertEquals(1, p.sends());
            assertEquals(List.of("in-time"), tags, "the refused sends never reached the partner");
        } finally {
            p.close().await();
        }
    }

    @Test
    void aSendCutWhileItWaitsForAConnectionIsResetUnsent() throws Exception {
        HttpComponent.HttpProducer p = producer(Map.of("poolSize", 1));
        try {
            Future<Envelope> first = p.send(tagged("first"));
            assertTrue(soon(() -> open.containsKey("first")), "fixture: the first send holds the pool's one connection: " + tags);
            AtomicReference<Instant> until = new AtomicReference<>(rt.clock().instant().plusSeconds(5));
            Deadline d = () -> Duration.between(rt.clock().instant(), until.get());
            Future<Envelope> waiting = p.send(tagged("cut"), d);   // waits for the connection
            rt.clock().advance(Duration.ofSeconds(5));
            NackException cut = assertInstanceOf(NackException.class, failureOf(waiting), "cut at its deadline while it waited");
            assertTrue(cut.retryable(), cut.toString());

            open.get("first").setStatusCode(202).end();   // the connection frees: the cut request gets it, and is reset unsent
            first.await(10, TimeUnit.SECONDS);
            Future<Envelope> third = p.send(tagged("third"));
            assertTrue(soon(() -> open.containsKey("third")), "the next send goes out: " + tags);
            open.get("third").setStatusCode(202).end();
            third.await(10, TimeUnit.SECONDS);
            assertEquals(List.of("first", "third"), tags, "the cut send never reached the partner");
        } finally {
            p.close().await();
        }
    }

    @Test
    void aTimerThatRefusesTheCutFailsTheSendAndSendsNothing() throws Exception {
        IllegalStateException refused = new IllegalStateException("timer shut down");
        io.flottila.api.Timer shutDown = (delay, task) -> { throw refused; };
        HttpComponent timed = new HttpComponent(vertx, rt.blobs(), rt.ids(), shutDown);
        String url = partner();
        HttpComponent.HttpProducer p = (HttpComponent.HttpProducer) timed.producer(new EndpointConfig("partner", URI.create(url), Map.of("url", url, "maxBodyBytes", 4096)));
        try {
            Future<Envelope> sent = p.send(tagged("unarmed"), () -> Duration.ofMinutes(1));   // a refusal is a failed future, never a throw
            assertSame(refused, failureOf(sent), "the timer's own failure, as it threw");
            assertEquals(0, p.sends(), "no request, not counted");
            Future<Envelope> plain = p.send(tagged("witness"));   // an unbounded send arms no cut
            assertTrue(soon(() -> open.containsKey("witness")), "witness: the partner is reachable: " + tags);
            open.get("witness").setStatusCode(202).end();
            plain.await(10, TimeUnit.SECONDS);
            assertEquals(List.of("witness"), tags, "the refused send never reached the partner");
        } finally {
            p.close().await();
        }
    }

    @Test
    void aReReadThatFiresAfterItsSendSettledArmsNoOther() throws Exception {
        java.util.concurrent.atomic.AtomicInteger schedules = new java.util.concurrent.atomic.AtomicInteger();
        io.flottila.api.Timer lateCancels = (delay, task) -> {   // every cancel comes too late: the task still fires
            schedules.incrementAndGet();
            rt.timer().schedule(delay, task);
            return () -> false;
        };
        HttpComponent timed = new HttpComponent(vertx, rt.blobs(), rt.ids(), lateCancels);
        HttpServer s = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> req.response().setStatusCode(202).end()))
                .listen(0, "127.0.0.1").await();
        servers.add(s);
        String url = "http://127.0.0.1:" + s.actualPort() + "/in";
        io.flottila.api.Producer p = timed.producer(new EndpointConfig("partner", URI.create(url), Map.of("url", url, "maxBodyBytes", 4096)));
        try {
            AtomicReference<Instant> until = new AtomicReference<>(rt.clock().instant().plusSeconds(5));
            p.send(tagged("answered"), () -> Duration.between(rt.clock().instant(), until.get())).await(10, TimeUnit.SECONDS);
            assertEquals(1, schedules.get(), "fixture: one cut armed for the send");
            until.set(rt.clock().instant().plusSeconds(10));   // the deadline still open when the stale re-read fires
            rt.clock().advance(Duration.ofSeconds(5));
            assertEquals(1, schedules.get(), "a re-read for a settled send stops there: it arms no other, though time is left");
        } finally {
            p.close().await();
        }
    }

    @Test
    void theThreeArgumentComponentCutsOnVertxTimers() throws Exception {
        HttpComponent onVertx = new HttpComponent(vertx, rt.blobs(), rt.ids());
        HttpServer s = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> tags.add("silent")))   // never answers
                .listen(0, "127.0.0.1").await();
        servers.add(s);
        String url = "http://127.0.0.1:" + s.actualPort() + "/in";
        io.flottila.api.Producer p = onVertx.producer(new EndpointConfig("partner", URI.create(url), Map.of("url", url, "maxBodyBytes", 4096)));
        try {
            Instant until = Instant.now().plusMillis(150);   // the real clock: Vert.x timers run on it
            Throwable failure = failureOf(p.send(tagged("silent"), () -> Duration.between(Instant.now(), until)));
            NackException cut = assertInstanceOf(NackException.class, failure, "cut at its deadline, the partner silent");
            assertTrue(cut.retryable() && cut.reason().contains("was cut"), "the cut, not the 30 s timeoutMs: " + cut);
        } finally {
            p.close().await();
        }
    }
}
