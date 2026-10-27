package io.flottila.components.mock;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behaviours from the demo's {@code invoice-sync.test.yaml}: fixture replies, delays on the fake timer, failures with the schema's shapes. */
class MockComponentTest {

    static final Path DEMO_FLOWS = Path.of("../../packages/demo/flows");
    final FakeRuntime rt = FakeRuntime.create();
    final MockComponent http = new MockComponent("http", rt.blobs(), rt.timer());
    final EndpointConfig domestic = new EndpointConfig("partner-domestic", URI.create("http://localhost:8081/echo"), Map.of());
    final EndpointConfig foreign = new EndpointConfig("partner-foreign", URI.create("http://localhost:8081/echo-foreign"), Map.of());

    Envelope invoice() { return rt.ingress("invoice-sync", Headers.empty().with("x-request-id", "r1"), Buffer.buffer("<Invoice/>")); }

    @Test
    void repliesFromTheFixtureAfterTheDelayAndRecordsPerEndpoint() throws Exception {
        http.bindAll(Map.of("partner-domestic", Map.of("reply", "../fixtures/partner-ack.xml", "delay", "20ms"),
                "partner-foreign", Map.of("reply", "../fixtures/partner-ack.xml")), DEMO_FLOWS);
        Producer p = http.producer(domestic);
        Envelope in = invoice();
        Future<Envelope> f = p.send(in);
        assertFalse(f.isComplete(), "20 ms on the fake timer have not passed");
        assertEquals(1, rt.timer().pending());
        rt.clock().advance(Duration.ofMillis(20));
        Envelope out = f.await();
        assertEquals(Files.readString(DEMO_FLOWS.resolve("../fixtures/partner-ack.xml")), out.body().whole().toString(), "the fixture is the reply body");
        assertEquals(1, out.step(), "the next step's body");
        assertEquals("application/xml", out.header(Envelope.H_CONTENT_TYPE).orElseThrow());
        assertEquals("r1", out.header("x-request-id").orElseThrow(), "headers carried over");
        assertEquals(List.of(in), http.sentTo("partner-domestic"));
        assertEquals(List.of(), http.sentTo("partner-foreign"));
        Envelope now = http.producer(foreign).send(invoice()).await();
        assertTrue(now.body().whole().toString().contains("Ack"), "no delay: answered at once");
        assertEquals(2, http.sent().size());
        assertEquals("http", http.scheme());
        assertEquals("object", new io.vertx.core.json.JsonObject(http.configSchema()).getString("type"));
    }

    @Test
    void aConsumerAnswersItsOwnEndpointOnlyTheOthersKeepTheirBehaviour() {
        http.bind("partner-domestic", Behaviour.from(Map.of("reply", "../fixtures/partner-ack.xml"), DEMO_FLOWS));
        EndpointConfig in = new EndpointConfig("http-in", URI.create("http://0.0.0.0:8080/in"), Map.of());
        List<Envelope> ingress = new java.util.ArrayList<>();
        var consumer = http.consumer(in, e -> { ingress.add(e); return Future.succeededFuture(new io.flottila.api.Ack.Reply(e.withHeader("x-from", "ingress"))); });
        consumer.start().await();
        Envelope toPartner = http.producer(domestic).send(invoice()).await();
        assertTrue(toPartner.body().whole().toString().contains("Ack"), "the partner send answers from the fixture, not from the flow's own ingress handler");
        assertEquals(List.of(), ingress, "the ingress consumer never saw the partner send");
        assertEquals("ingress", http.producer(in).send(invoice()).await().header("x-from").orElseThrow(), "a send to the consumer's own endpoint reaches it");
        consumer.stop().await();
        assertEquals(io.flottila.api.Health.Status.DOWN, http.health().await().status());
    }

    @Test
    void anUnboundEndpointEchoesAndAFailureIsANackRetryableAsHttpWouldBe() {
        Envelope in = invoice();
        assertEquals(in, http.producer(domestic).send(in).await(), "no binding: the request comes back");
        http.bind("partner-domestic", Behaviour.from(Map.of("fail", 503), DEMO_FLOWS));
        Future<Envelope> f = http.producer(domestic).send(in);
        f.otherwiseEmpty().await();
        assertTrue(f.cause() instanceof NackException n && n.retryable() && n.reason().contains("503"), String.valueOf(f.cause()));
        http.bind("partner-domestic", Behaviour.from(Map.of("fail", 400), DEMO_FLOWS));
        Future<Envelope> bad = http.producer(domestic).send(in);
        bad.otherwiseEmpty().await();
        assertTrue(bad.cause() instanceof NackException n && !n.retryable(), "4xx is final");
        http.bind("partner-domestic", Behaviour.from(Map.of("fail", 429), DEMO_FLOWS));
        Future<Envelope> throttled = http.producer(domestic).send(in);
        throttled.otherwiseEmpty().await();
        assertTrue(((NackException) throttled.cause()).retryable(), "429 is retryable");
        for (String fault : List.of("timeout", "reset", "malformed")) {
            http.bind("partner-domestic", Behaviour.from(Map.of("fail", fault), DEMO_FLOWS));
            Future<Envelope> x = http.producer(domestic).send(in);
            x.otherwiseEmpty().await();
            NackException n = (NackException) x.cause();
            assertEquals(!fault.equals("malformed"), n.retryable(), fault);
            assertTrue(n.reason().contains(fault), n.reason());
        }
    }

    @Test
    void afterAndEveryCountTheSendsOfOneEndpoint() {
        http.bind("partner-domestic", Behaviour.from(Map.of("fail", 503, "after", 3), DEMO_FLOWS));
        Producer p = http.producer(domestic);
        Envelope in = invoice();
        assertTrue(p.send(in).succeeded() && p.send(in).succeeded(), "sends 1 and 2 go through");
        assertTrue(p.send(in).failed() && p.send(in).failed(), "from the 3rd on, every send fails");
        http.bind("partner-foreign", Behaviour.from(Map.of("fail", "reset", "every", 2), DEMO_FLOWS));
        Producer q = http.producer(foreign);
        assertEquals(List.of(true, false, true, false, true), List.of(q.send(in).succeeded(), q.send(in).succeeded(), q.send(in).succeeded(), q.send(in).succeeded(), q.send(in).succeeded()),
                "every 2nd: 1 ok, 2 fails, 3 ok …");
        assertEquals(5, http.sentTo("partner-foreign").size(), "failed sends are recorded too");
        EndpointConfig third = new EndpointConfig("partner-third", URI.create("http://localhost:8081/third"), Map.of());
        http.bind("partner-third", Behaviour.from(Map.of("fail", 500, "after", 2, "every", 3), DEMO_FLOWS));
        Producer r = http.producer(third);
        assertEquals(List.of(true, true, true, false, true), List.of(r.send(in).succeeded(), r.send(in).succeeded(), r.send(in).succeeded(), r.send(in).succeeded(), r.send(in).succeeded()),
                "after 2 every 3: of the sends from the 2nd on, every 3rd fails — the 4th");
        assertTrue(r.send(in).succeeded() && r.send(in).failed(), "… and the 7th");
        assertTrue(http.producer(third).send(in).succeeded() && http.producer(third).send(in).succeeded() && http.producer(third).send(in).failed(),
                "the count is the endpoint's, across producers (a replay builds a new one): the 10th fails");
    }

    @Test
    void behaviourValidatesTheSchemaShapes() {
        Behaviour b = Behaviour.from(Map.of("reply", "../fixtures/partner-ack.xml", "delay", "2s", "fail", "timeout", "after", 2, "every", 3), Path.of("/flows"));
        assertEquals(Optional.of(Path.of("/fixtures/partner-ack.xml")), b.reply());
        assertEquals(Duration.ofSeconds(2), b.delay());
        assertEquals(Optional.of(Behaviour.Fault.TIMEOUT), b.fail());
        assertEquals(Behaviour.ECHO, Behaviour.from(Map.of(), Path.of("")));
        assertFalse(Behaviour.ECHO.failsOn(1));
        assertEquals(Duration.ofMinutes(3), Behaviour.parseDuration("3m"));
        assertEquals(Duration.ofHours(1), Behaviour.parseDuration("1h"));
        assertThrows(IllegalArgumentException.class, () -> Behaviour.from(Map.of("delay", "soon"), Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> Behaviour.from(Map.of("fail", "explode"), Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> Behaviour.from(Map.of("fail", 200), Path.of("")), "2xx is not a failure");
        assertThrows(IllegalArgumentException.class, () -> Behaviour.from(Map.of("fail", 600), Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> Behaviour.from(Map.of("after", 0), Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> Behaviour.from(Map.of("every", 0), Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> new Behaviour(Optional.empty(), Duration.ofMillis(-1), Optional.empty(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> http.bindAll(Map.of("x", "reply.xml"), Path.of("")), "a mocks entry is an object");
        assertEquals("application/json", MockComponent.contentType(Path.of("a.json")));
        assertEquals("text/plain", MockComponent.contentType(Path.of("a.txt")));
        assertEquals("application/octet-stream", MockComponent.contentType(Path.of("a.bin")));
        http.bind("partner-domestic", Behaviour.from(Map.of("reply", "nowhere.xml"), DEMO_FLOWS));
        Future<Envelope> missing = http.producer(domestic).send(invoice());
        missing.otherwiseEmpty().await();
        assertTrue(missing.cause().getMessage().contains("nowhere.xml"), "a missing fixture fails the send, named");
    }

    /**
     * D-066: the fixture reply is the next step's body of the envelope sent, under that envelope's next key — the plain key on
     * a first attempt, the part suffixed with the attempt from the second on — so each run's reply has a key of its own.
     */
    @Test
    void aFixtureReplyOfASecondAttemptIsKeyedByItsAttempt() {
        http.bind("partner-domestic", Behaviour.from(Map.of("reply", "../fixtures/partner-ack.xml"), DEMO_FLOWS));
        Producer p = http.producer(domestic);
        Envelope first = invoice().advance();
        Envelope one = p.send(first).await();
        Envelope two = p.send(first.with(Envelope.ATTEMPT, 2)).await();
        assertEquals(BlobKey.body(first.id(), 2), one.body().key(), "a first attempt: the next step's plain body key, as before");
        assertEquals(new BlobKey(first.id(), 2, "body~2"), two.body().key(), "the second attempt: its own key");
        assertTrue(rt.blobs().contains(one.body().key()) && rt.blobs().contains(two.body().key()), "both replies stored");
        assertEquals(one.body().hash(), two.body().hash(), "the same fixture: only the key tells the runs apart");
    }

    // --- stop (D-056: the mock's stop unregisters the handler and completes once every outstanding handler future has completed)

    @Test
    void stopCompletesOnlyOnceEveryOutstandingAnswerHasCompletedAFailedOneIncluded() throws Exception {
        List<Promise<Ack>> answers = new CopyOnWriteArrayList<>();
        var consumer = http.consumer(domestic, e -> { Promise<Ack> p = Promise.promise(); answers.add(p); return p.future(); });
        consumer.start().await();
        Producer p = http.producer(domestic);
        Future<Envelope> first = p.send(invoice());
        assertTrue(soon(() -> answers.size() == 1), "the first send reached the running consumer's handler");   // in order: answers.get(0) is first's
        Future<Envelope> second = p.send(invoice());
        assertTrue(soon(() -> answers.size() == 2), "both sends reached the running consumer's handler");

        Future<Void> stopped = consumer.stop();
        assertEquals(Health.Status.DOWN, http.health().await().status(), "no consumer runs once its stop began");
        assertFalse(stopped.isComplete(), "two answers are outstanding");
        Envelope late = invoice();
        assertEquals(late, p.send(late).await(), "a send after stop began gets the endpoint's behaviour (echo), not the handler");
        assertEquals(2, answers.size(), "the stopping consumer's handler saw nothing new");

        answers.get(0).fail(new IllegalStateException("the flow failed"));
        first.otherwiseEmpty().await(10, java.util.concurrent.TimeUnit.SECONDS);   // the handler's thread may pass the answer on after it returned
        assertTrue(first.failed(), "the failed answer reached its sender");
        assertFalse(stopped.isComplete(), "one answer is still outstanding");
        assertFalse(second.isComplete(), "its answer is still outstanding");
        answers.get(1).complete(new Ack.Reply(invoice().withHeader("x-answer", "last")));
        assertEquals("last", second.await().header("x-answer").orElse(null), "the last answer reached its sender");
        assertTrue(stopped.isComplete() && stopped.succeeded(), "stop completes with the last outstanding answer, a failed one counted as completed: " + stopped);
    }

    @Test
    void aSecondStopWithAnAnswerOutstandingDoesNotCompleteEarly() throws Exception {
        Promise<Ack> answer = Promise.promise();
        List<Envelope> handled = new CopyOnWriteArrayList<>();
        var consumer = http.consumer(domestic, e -> { handled.add(e); return answer.future(); });
        consumer.start().await();
        Envelope in = invoice();
        Future<Envelope> sent = http.producer(domestic).send(in);
        assertTrue(soon(() -> !handled.isEmpty()), "the send reached the running consumer's handler");
        assertEquals(List.of(in), handled, "the send reached the running consumer's handler");

        Future<Void> first = consumer.stop();
        Future<Void> again = consumer.stop();
        assertFalse(first.isComplete(), "one answer is outstanding");
        assertFalse(again.isComplete(), "a second stop is not an answer: the drain still waits for the outstanding one");
        answer.complete(new Ack.Accepted(in.id()));
        sent.otherwiseEmpty().await(10, java.util.concurrent.TimeUnit.SECONDS);   // the handler's thread may pass the answer on after it returned
        assertTrue(sent.succeeded(), "the answer reached its sender: " + sent);
        assertTrue(first.succeeded() && again.succeeded(), "both stops complete with the last answer: " + first + ", " + again);
    }

    /**
     * The answer is counted when the send hands it out, before the handler's thread runs (D-056 with D-057): a stop issued
     * right after the send, while that thread is still queued behind held carriers, waits for the answer all the same.
     */
    @Test
    void aStopBeforeTheHandlerWasEnteredStillWaitsForItsAnswer() throws Exception {
        Promise<Ack> answer = Promise.promise();
        List<Envelope> handled = new CopyOnWriteArrayList<>();
        var consumer = http.consumer(domestic, e -> { handled.add(e); return answer.future(); });
        consumer.start().await();
        Envelope in = invoice();
        Future<Envelope> sent;
        Future<Void> stopped;
        try (HeldCarriers held = HeldCarriers.hold()) {   // the handler's virtual thread is started by the send but cannot run
            sent = http.producer(domestic).send(in);
            stopped = consumer.stop();
            assertEquals(List.of(), handled, "the window is real: the handler has not been entered when stop is called");
            assertFalse(stopped.isComplete(), "the answer the send handed out is outstanding before its handler runs: stop waits for it");
        }
        assertTrue(soon(() -> handled.size() == 1), "the handler runs once the carriers are free");
        assertFalse(stopped.isComplete(), "stop still waits: the handler has not answered");
        answer.complete(new Ack.Accepted(in.id()));
        sent.otherwiseEmpty().await(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(sent.succeeded(), "the answer reached its sender: " + sent);
        stopped.otherwiseEmpty().await(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(stopped.succeeded(), "stop completes with the answer: " + stopped);
    }

    /** Spins (never sleeps) until {@code condition} holds, at most 10 s: each delivery calls the handler on a virtual thread of its own (D-057). */
    static boolean soon(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }
}
