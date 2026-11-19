package dev.rail8.components.direct;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobNotFoundException;
import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Fault;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.Key;
import dev.rail8.api.NackException;
import dev.rail8.api.Part;
import dev.rail8.api.Producer;
import dev.rail8.api.settings.Direction;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeIds;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call from one flow to another through the direct component: what the callee's flow receives, what the caller gets
 * back for each answer, the order of the refusals, the claim of an address and its release at a stop.
 */
@Timeout(30)
class DirectComponentTest {

    private static final Key<String> STAGE = new Key<>("stage");
    private static final Key<Object> DEPTH = new Key<>("rail8.depth");

    private final FakeRuntime rt = FakeRuntime.create();
    private final FakeIds calleeIds = new FakeIds("callee");
    private final DirectComponent direct = new DirectComponent(rt.blobs(), calleeIds, rt.timer());
    private final Producer call = direct.producer(out("/orders"));
    private final List<Consumer> running = new ArrayList<>();

    @AfterEach
    void stopAll() {
        for (Consumer c : running) c.stop().await();
    }

    static EndpointConfig in(String flow, String address, long maxBodyBytes) {
        return new EndpointConfig(flow + "-in", URI.create("direct:" + address),
                Map.of("address", address, "flow", flow, "maxBodyBytes", maxBodyBytes));
    }

    static EndpointConfig out(String address) {
        return new EndpointConfig("call", URI.create("direct:" + address), Map.of("address", address));
    }

    /** A started consumer of {@code /orders} for the flow {@code flow}, taking bodies of up to 1 KiB; stopped after the case. */
    private Consumer serve(String flow, Function<Envelope, Future<Ack>> handler) {
        return serve(direct, in(flow, "/orders", 1024), handler);
    }

    private Consumer serve(DirectComponent component, EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        Consumer c = component.consumer(cfg, handler);
        c.start().await();
        running.add(c);
        return c;
    }

    /** The calling flow's message after its first step: a header, a prop and a key of its own. */
    private Envelope caller(String body) {
        return rt.ingress("shop", Headers.empty().with("content-type", "text/plain"), Buffer.buffer(body))
                .advance().with(STAGE, "priced").withKey("order", "42");
    }

    private static Throwable failure(Future<?> f) {
        f.otherwiseEmpty().await();
        assertTrue(f.failed(), "the send fails: " + f);
        return f.cause();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the handler was invoked");
    }

    // --- the address

    @Test
    void theAddressIsTheSameAnswerForTheInAndTheOutEndpoint() {
        assertEquals(Optional.of("/orders"), direct.internalAddress(in("fulfil", "/orders", 1024)));
        assertEquals(Optional.of("/orders"), direct.internalAddress(out("/orders")));
        assertEquals(Optional.of("/orders/eu-1"), direct.internalAddress(out("/orders/eu-1")));
    }

    @Test
    void anEndpointWithoutAWellFormedAddressIsRefusedWhereverItIsUsed() {
        EndpointConfig none = new EndpointConfig("call", URI.create("direct:/orders"), Map.of());
        EndpointConfig trailing = out("/orders/");
        for (EndpointConfig bad : List.of(none, trailing)) {
            assertThrows(IllegalArgumentException.class, () -> direct.internalAddress(bad), "internalAddress of " + bad.options());
            assertThrows(IllegalArgumentException.class, () -> direct.producer(bad), "producer of " + bad.options());
            Map<String, Object> options = new java.util.HashMap<>(bad.options());
            options.put("maxBodyBytes", 1024L);
            EndpointConfig asIn = new EndpointConfig("in", bad.uri(), options);
            assertThrows(IllegalArgumentException.class, () -> direct.consumer(asIn, a -> Future.succeededFuture(new Ack.Accepted(a.id()))),
                    "consumer of " + options);
        }
    }

    @Test
    void anInEndpointNeedsAPositiveBodyLimit() {
        for (Map<String, Object> options : List.<Map<String, Object>>of(Map.of("address", "/orders"), Map.of("address", "/orders", "maxBodyBytes", 0L))) {
            EndpointConfig cfg = new EndpointConfig("in", URI.create("direct:/orders"), options);
            assertThrows(IllegalArgumentException.class, () -> direct.consumer(cfg, a -> Future.succeededFuture(new Ack.Accepted(a.id()))),
                    "consumer of " + options);
        }
    }

    @Test
    void theSettingsTakeAPathOfAtMost256CharactersAndNothingElse() {
        for (String good : List.of("/orders", "/orders/eu-1", "/a.b_c~d", "/" + "a".repeat(255))) {
            assertEquals(List.of(), direct.settings().check(Map.of("address", good), Direction.OUT), good);
            assertEquals(List.of(), direct.settings().check(Map.of("address", good, "flow", "fulfil", "maxBodyBytes", 1024L), Direction.IN), good);
        }
        for (String bad : List.of("/", "orders", "//orders", "/orders/", "/orders//eu", "/or ders", "/" + "a".repeat(256))) {
            assertNotEquals(List.of(), direct.settings().check(Map.of("address", bad), Direction.OUT), bad);
        }
        assertNotEquals(List.of(), direct.settings().check(Map.of(), Direction.OUT), "an out endpoint names its address");
        assertNotEquals(List.of(), direct.settings().check(Map.of("flow", "fulfil", "maxBodyBytes", 1024L), Direction.IN), "an in endpoint names its address");
    }

    // --- the callee's three ways of answering

    @Test
    void aSyncCalleesReplyComesBackAsTheCallersNextStepWithTheBodyCopiedUnderTheCallersNextKey() {
        FakeRuntime callee = FakeRuntime.create();
        serve("fulfil", a -> {
            Body confirmed = callee.store(a, Buffer.buffer("{\"confirmed\":true}"));
            Envelope answer = Envelope.ingress(a.id(), a.flow(), Headers.empty().with("content-type", "application/json").with("x-status", "done"), confirmed)
                    .withAttachment(new Part("receipt", "text/plain", confirmed));
            return Future.succeededFuture(new Ack.Reply(answer));
        });
        Envelope sent = caller("order").withHeader("x-partner", "acme")
                .withAttachment(new Part("invoice", "text/plain", caller("invoice").body()));

        Envelope reply = call.send(sent).await();

        assertEquals(sent.id(), reply.id(), "the caller's message goes on");
        assertEquals(sent.flow(), reply.flow());
        assertEquals(sent.step() + 1, reply.step());
        assertEquals(sent.props(), reply.props(), "the caller's props");
        assertEquals(sent.keys().with("rail8.ref", sent.id()), reply.keys(), "the caller's keys, plus the reference of the chain");
        assertEquals(Map.of("content-type", "application/json", "x-status", "done"), reply.headers().asMap(),
                "the reply's headers replace the caller's");
        assertEquals(sent.nextKey(), reply.body().key(), "the reply's body under the caller's next key");
        assertSame(rt.blobs(), reply.body().store(), "copied into the component's store");
        assertEquals("{\"confirmed\":true}", reply.body().whole().toString());
        assertEquals(sent.attachments(), reply.attachments(), "the caller's attachments, not the reply's");
    }

    @Test
    void anAtLeastOnceCalleeGetsANewMessageOverTheSameBodyAndTheCallerGetsItsOwnMessageBack() {
        List<Envelope> intake = new CopyOnWriteArrayList<>();   // the callee's flow takes the message in, then accepts it
        serve("fulfil", a -> {
            intake.add(a);
            return Future.succeededFuture(new Ack.Accepted(a.id()));
        });
        Envelope sent = caller("order").withHeader("X-Message-Id", "forwarded-from-upstream");

        Envelope accepted = call.send(sent).await();

        assertEquals(1, intake.size());
        Envelope arrival = intake.get(0);
        assertEquals("callee-1", arrival.id(), "a new id");
        assertEquals("fulfil", arrival.flow(), "the callee's flow");
        assertEquals(0, arrival.step(), "as it arrived");
        assertEquals(Map.of("content-type", "text/plain", "x-message-id", sent.id()), arrival.headers().asMap(),
                "the caller's headers, the message id the caller's");
        assertSame(sent.body(), arrival.body(), "the same body, not a copy");
        assertEquals(Map.of("rail8.ref", sent.id()), arrival.keys().asMap(), "the reference of the chain: the caller's id");
        assertEquals(Map.of("rail8.depth", 1), arrival.props().asMap(), "one call deep");
        assertEquals(Map.of(), arrival.attachments());

        assertEquals(sent.id(), accepted.id(), "the message as sent");
        assertEquals(sent.step(), accepted.step());
        assertSame(sent.body(), accepted.body());
        assertEquals(sent.headers(), accepted.headers());
        assertEquals(sent.props(), accepted.props());
        assertEquals(sent.keys().with("rail8.ref", sent.id()), accepted.keys(), "plus the reference of the chain");
    }

    @Test
    void anAtMostOnceCalleeAnswersBeforeItRunsAndWhatItsRunDoesLaterNeverReachesTheCaller() {
        Promise<Void> run = Promise.promise();   // the callee's run, which goes on after its answer
        List<String> ran = new CopyOnWriteArrayList<>();
        Consumer callee = serve("fulfil", a -> {
            run.future().onComplete(done -> ran.add(a.id() + (done.failed() ? " failed" : " done")));
            return Future.succeededFuture(new Ack.Accepted(a.id()));
        });
        Envelope sent = caller("order");

        Future<Envelope> accepted = call.send(sent);

        assertEquals(sent.id(), accepted.await().id(), "accepted while the callee's run is still to come");
        callee.stop().await();   // a stop drains the answers, not the runs after them
        running.remove(callee);
        run.fail(new IllegalStateException("the run failed after the answer"));
        assertEquals(List.of("callee-1 failed"), ran);
        assertTrue(accepted.succeeded(), "the caller keeps its answer: " + accepted);
    }

    @Test
    void aCallerThatIsItselfACalleeHandsItsReferenceOnAndGoesOneCallDeeper() {
        List<Envelope> intake = new CopyOnWriteArrayList<>();
        serve("fulfil", a -> {
            intake.add(a);
            return Future.succeededFuture(new Ack.Accepted(a.id()));
        });
        Envelope sent = caller("order").withKey("rail8.ref", "root-7").with(DEPTH, 3);

        Envelope accepted = call.send(sent).await();

        assertEquals("root-7", intake.get(0).key("rail8.ref").orElseThrow(), "the chain keeps the reference of its first message");
        assertEquals(Map.of("rail8.depth", 4), intake.get(0).props().asMap());
        assertEquals("root-7", accepted.key("rail8.ref").orElseThrow());
    }

    @Test
    void aNackOfTheCalleeFailsTheSendWithItsReasonAndItsRetryable() {
        AtomicInteger asked = new AtomicInteger();
        serve("fulfil", a -> Future.succeededFuture(asked.incrementAndGet() == 1 ? new Ack.Nack("busy", true) : new Ack.Nack("refused", false)));

        NackException busy = assertInstanceOf(NackException.class, failure(call.send(caller("one"))));
        NackException refused = assertInstanceOf(NackException.class, failure(call.send(caller("two"))));

        assertEquals("busy", busy.reason());
        assertTrue(busy.retryable());
        assertEquals("refused", refused.reason());
        assertFalse(refused.retryable());
    }

    // --- a failed answer: the callee's failure, named by the callee's message id

    /** A fault of the kind {@code kind}, as a step reports one. */
    static final class KindFault extends RuntimeException implements Fault {
        private final Kind kind;

        KindFault(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        @Override public Kind kind() { return kind; }
    }

    /** The send's failure when the callee's flow fails with {@code thrown}, and the callee's message id. */
    private record Failed(Throwable failure, String calleeId) {}

    private Failed failedWith(Throwable thrown) {
        AtomicReference<String> id = new AtomicReference<>();
        DirectComponent fresh = new DirectComponent(rt.blobs(), calleeIds, rt.timer());
        serve(fresh, in("fulfil", "/orders", 1024), a -> {
            id.set(a.id());
            return Future.failedFuture(thrown);
        });
        Throwable failure = failure(fresh.producer(out("/orders")).send(caller("fails")));
        return new Failed(failure, id.get());
    }

    @Test
    void aFailedNackExceptionIsRebuiltWithTheCalleesMessageIdAndTheSameRetryable() {
        NackException thrown = new NackException("partner said no", false);
        Failed f = failedWith(thrown);

        NackException nack = assertInstanceOf(NackException.class, f.failure());
        assertFalse(nack.retryable(), "final as the callee's");
        assertTrue(nack.reason().contains(f.calleeId()) && nack.reason().contains("partner said no"), nack.reason());
        assertSame(thrown, nack.getCause(), "the callee's failure is the cause");
    }

    @Test
    void aNackExceptionBelowAWrapperDecidesTheSameWay() {
        RuntimeException thrown = new RuntimeException("step failed", new NackException("partner busy", true));
        Failed f = failedWith(thrown);

        NackException nack = assertInstanceOf(NackException.class, f.failure());
        assertTrue(nack.retryable(), "retryable as the callee's");
        assertTrue(nack.reason().contains(f.calleeId()) && nack.reason().contains("partner busy"), nack.reason());
        assertSame(thrown, nack.getCause());
    }

    @Test
    void anotherFaultKeepsItsKindAtTheTopWhereTheWorkerAndTheHttpStatusReadIt() {
        for (Fault.Kind kind : Fault.Kind.values()) {
            KindFault thrown = new KindFault(kind, "bad order " + kind);
            Failed f = failedWith(new RuntimeException("wrapped", thrown));

            Fault fault = assertInstanceOf(Fault.class, f.failure(), kind + ": a fault at the top");
            assertFalse(f.failure() instanceof NackException, kind + ": not a refusal of the partner");
            assertEquals(kind, fault.kind(), "the callee's kind");
            assertTrue(f.failure().toString().contains(f.calleeId()), "the failed row names the callee's message: " + f.failure());
            assertTrue(f.failure().toString().contains("bad order " + kind), "and what failed there: " + f.failure());
            assertSame(thrown, f.failure().getCause().getCause(), "the callee's failure is the cause");
        }
    }

    @Test
    void aFailureWithoutAFaultIsInternal() {
        IllegalStateException thrown = new IllegalStateException("boom");
        Failed f = failedWith(thrown);

        assertEquals(Fault.Kind.INTERNAL, assertInstanceOf(Fault.class, f.failure()).kind());
        assertTrue(f.failure().toString().contains(f.calleeId()) && f.failure().toString().contains("boom"), f.failure().toString());
        assertSame(thrown, f.failure().getCause());
    }

    @Test
    void aCauseChainThatLoopsIsWalkedOnce() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertEquals(Fault.Kind.INTERNAL, assertInstanceOf(Fault.class, failedWith(a).failure()).kind());
    }

    @Test
    void anErrorOrAnInterruptPassesThroughAsItIs() {
        LinkageError error = new LinkageError("callee broke");
        InterruptedException interrupted = new InterruptedException("stopping");

        assertSame(error, failedWith(error).failure());
        assertSame(interrupted, failedWith(interrupted).failure());
    }

    @Test
    void aHandlerThatThrowsIsAnInternalFailureNamingTheCalleesMessage() {
        serve("fulfil", a -> { throw new IllegalStateException("handler threw"); });

        Throwable failure = failure(call.send(caller("x")));

        assertEquals(Fault.Kind.INTERNAL, assertInstanceOf(Fault.class, failure).kind());
        assertTrue(failure.toString().contains("callee-1") && failure.toString().contains("handler threw"), failure.toString());
    }

    @Test
    void aReplyWhoseBodyCannotBeCopiedFailsTheSendWithTheStoresFailure() {
        Envelope sent = caller("order");
        Body gone = Body.of(rt.blobs(), BlobKey.body("gone", 0), sent.body().hash(), sent.body().size());
        serve("fulfil", a -> Future.succeededFuture(new Ack.Reply(a.next(gone))));

        assertInstanceOf(BlobNotFoundException.class, failure(call.send(sent)));
    }

    // --- the refusals, in their order

    @Test
    void theDepthIsCappedAt8Calls() {
        List<Envelope> intake = new CopyOnWriteArrayList<>();
        serve("fulfil", a -> {
            intake.add(a);
            return Future.succeededFuture(new Ack.Accepted(a.id()));
        });

        call.send(caller("seventh").with(DEPTH, 7)).await();
        call.send(caller("restored").with(DEPTH, 7L)).await();
        assertEquals(List.of(8, 8), intake.stream().map(a -> a.props().asMap().get("rail8.depth")).toList(), "the eighth call is made");

        NackException deep = assertInstanceOf(NackException.class, failure(call.send(caller("ninth").with(DEPTH, 8))));
        assertFalse(deep.retryable(), "a chain that deep never gets shallower");
        assertTrue(deep.reason().contains("8"), deep.reason());
        assertEquals(2, intake.size(), "the ninth call is not made");
    }

    @Test
    void aDepthThatIsNotAWholeNumberOfCallsIsAFinalNack() {
        AtomicInteger delivered = new AtomicInteger();
        serve("fulfil", a -> {
            delivered.incrementAndGet();
            return Future.succeededFuture(new Ack.Accepted(a.id()));
        });

        for (Object bad : List.of("3", 2.5, -1, Integer.MAX_VALUE, Long.MAX_VALUE, Double.NaN)) {
            NackException nack = assertInstanceOf(NackException.class, failure(call.send(caller("bad").with(DEPTH, bad))), String.valueOf(bad));
            assertFalse(nack.retryable(), bad + ": final");
        }
        assertEquals(0, delivered.get(), "none of them is called");
    }

    @Test
    void aCallToAnAddressNoFlowServesIsARetryableNack() {
        serve(direct, in("billing", "/billing", 1024), a -> Future.succeededFuture(new Ack.Accepted(a.id())));

        NackException nack = assertInstanceOf(NackException.class, failure(call.send(caller("order"))));

        assertTrue(nack.retryable(), "the callee's flow may be deployed later");
        assertTrue(nack.reason().contains("/orders"), nack.reason());
    }

    @Test
    void theDepthIsCheckedBeforeTheAddress() {
        assertFalse(assertInstanceOf(NackException.class, failure(call.send(caller("ninth").with(DEPTH, 8)))).retryable());
    }

    @Test
    void aBodyAboveTheCalleesLimitIsAFinalNackAndTheCalleeNeverSeesIt() {
        AtomicInteger delivered = new AtomicInteger();
        Consumer callee = serve(direct, in("fulfil", "/orders", 4), a -> {
            delivered.incrementAndGet();
            return Future.succeededFuture(new Ack.Accepted(a.id()));
        });

        call.send(caller("1234")).await();
        NackException nack = assertInstanceOf(NackException.class, failure(call.send(caller("12345"))));

        assertFalse(nack.retryable(), "the same body is as large next time");
        assertTrue(nack.reason().contains("4") && nack.reason().contains("fulfil"), nack.reason());
        assertEquals(1, delivered.get(), "only the body within the limit is delivered");
        assertTrue(callee.stop().succeeded(), "nothing is left in flight");
    }

    // --- claim, stop and swap

    @Test
    void aSecondConsumerOfAnAddressFailsToStartAndTheFirstKeepsServing() {
        serve("fulfil", a -> Future.succeededFuture(new Ack.Reply(a.withHeader("x-by", "fulfil"))));
        Consumer second = direct.consumer(in("billing", "/orders", 1024), a -> Future.succeededFuture(new Ack.Reply(a.withHeader("x-by", "billing"))));

        Throwable refused = failure(second.start());

        assertInstanceOf(IllegalStateException.class, refused);
        assertTrue(refused.getMessage().contains("/orders") && refused.getMessage().contains("fulfil"), refused.getMessage());
        assertEquals("fulfil", call.send(caller("order")).await().header("x-by").orElseThrow());
        second.stop().await();
        assertEquals("fulfil", call.send(caller("order")).await().header("x-by").orElseThrow(), "the refused one's stop releases nothing");
    }

    @Test
    void aStoppedConsumerIsNeverStartedAgain() {
        Consumer once = direct.consumer(in("fulfil", "/orders", 1024), a -> Future.succeededFuture(new Ack.Accepted(a.id())));
        once.start().await();
        once.stop().await();

        assertInstanceOf(IllegalStateException.class, failure(once.start()));
        assertEquals(Health.Status.DOWN, direct.health().await().status(), "the address stays free");
    }

    @Test
    void aVersionSwapReleasesTheAddressAtTheOldStopAndTheOldAnswerStillReachesItsCaller() throws Exception {
        Promise<Ack> oldAnswer = Promise.promise();
        CountDownLatch oldEntered = new CountDownLatch(1);
        Consumer old = serve("fulfil", a -> {
            oldEntered.countDown();
            return oldAnswer.future();
        });
        Future<Envelope> inFlight = call.send(caller("first"));
        await(oldEntered);

        Future<Void> oldStopped = old.stop();
        assertFalse(oldStopped.isComplete(), "the old consumer drains the answer in flight");
        assertTrue(assertInstanceOf(NackException.class, failure(call.send(caller("between")))).retryable(),
                "between the versions no flow serves the address");
        Consumer fresh = serve("fulfil", a -> Future.succeededFuture(new Ack.Reply(a.withHeader("x-version", "2"))));

        assertEquals("2", call.send(caller("second")).await().header("x-version").orElseThrow(), "the new version serves the address");
        assertFalse(inFlight.isComplete(), "the old version's answer is still to come");
        oldAnswer.complete(new Ack.Reply(Envelope.ingress("old", "fulfil", Headers.empty().with("x-version", "1"), caller("reply").body())));
        assertEquals("1", inFlight.await().header("x-version").orElseThrow(), "the old version's answer reaches its caller");
        oldStopped.await();
        assertEquals(Health.Status.UP, direct.health().await().status(), "the new version still serves");

        running.remove(old);
        fresh.stop().await();
        running.remove(fresh);
        assertEquals(Health.Status.DOWN, direct.health().await().status());
    }

    @Test
    void aSendCutAtItsDeadlineDropsTheLateAnswerWithoutCopyingItAndTheStopWaitsForIt() throws Exception {
        FakeClock clock = FakeClock.at(FakeRuntime.T0);
        DirectComponent timed = new DirectComponent(rt.blobs(), calleeIds, new FakeTimer(clock));
        Promise<Ack> answer = Promise.promise();
        AtomicReference<Envelope> arrival = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        Consumer callee = timed.consumer(in("fulfil", "/orders", 1024), a -> {
            arrival.set(a);
            entered.countDown();
            return answer.future();
        });
        callee.start().await();
        Envelope sent = caller("late");

        Future<Envelope> cut = timed.producer(out("/orders")).send(sent, () -> Duration.between(clock.instant(), FakeRuntime.T0.plusSeconds(5)));
        await(entered);
        clock.advance(Duration.ofSeconds(5));

        assertTrue(assertInstanceOf(NackException.class, failure(cut)).retryable(), "cut: the caller may try again");
        Future<Void> stopped = callee.stop();
        assertFalse(stopped.isComplete(), "the late answer is still outstanding");
        FakeRuntime other = FakeRuntime.create();
        answer.complete(new Ack.Reply(arrival.get().next(other.store(arrival.get(), Buffer.buffer("too late")))));
        stopped.await();
        assertFalse(rt.blobs().contains(sent.nextKey()), "the late reply was not copied");
    }
}
