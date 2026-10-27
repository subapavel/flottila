package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Health;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.flottila.api.Props;
import io.flottila.api.Views;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * The contract suite is only worth its name if every case fails for the component that breaks exactly that property.
 * One broken variant per property, run through the JUnit engine, asserting the exact set of failing cases.
 */
class ComponentContractSelfTest {

    static final EndpointConfig LOOP = new EndpointConfig("loop", URI.create("loopback://x"), Map.of());

    /**
     * A loopback component with one thing wrong, chosen by the variant. The correct one's {@code stop()} unregisters the
     * handler at once and completes when the handler answers still outstanding have completed (D-056); a Nack fails the
     * send with a {@link NackException} carrying the reason and the retryable flag; each delivery calls the handler on a
     * virtual thread of its own (D-057).
     */
    static final class Variant implements Component {
        final String scheme;
        final String schema;
        final boolean handlerSurvivesStop;
        final boolean healthAlwaysUp;
        final boolean ackBeforeHandler;
        final boolean nackIsSuccess;
        final UnaryOperator<Envelope> deliveredAs;
        final UnaryOperator<Envelope> repliedAs;
        private record Running(Function<Envelope, Future<Ack>> handler, ConsumerDrain drain) {}
        private volatile Running handler;
        /** Broken stop: completes at once and fails every send still waiting for its answer (the answer is dropped). */
        boolean stopDropsPendingAnswers;
        /** How a Nack fails the send; the correct variant reports a {@link NackException} with reason and flag. */
        Function<Ack.Nack, Throwable> nackAs = n -> new NackException(n.reason(), n.retryable());
        /** Where the handler runs; {@code null} (the correct variant) is a virtual thread of its own per delivery. */
        Executor handlerThread;
        /** Broken: the handler runs on the sender's thread, inside {@code send()}. */
        boolean handlerOnTheSendersThread;
        /** Broken: a handler that throws or answers null leaves the answer pending (its own thread dies with the exception). */
        boolean handlerFailureLost;
        /**
         * Broken (D-066): the sender's side re-stores each reply here — under the plain next-step key, (id, step + 1, body),
         * whatever the attempt (the reply writer before run-scoped keys), or under the envelope's next key but without the
         * sender's props ({@link #restoredWithoutProps}); {@code null} (the correct variant) returns the reply as is.
         */
        InMemoryBlobStore restoredReplies;
        /** With {@link #restoredReplies}: keyed by {@link Envelope#nextKey()}, the attempt kept, but the sender's props dropped. */
        boolean restoredWithoutProps;
        private final Set<Promise<Envelope>> waiting = ConcurrentHashMap.newKeySet();

        Variant(String scheme, String schema, boolean handlerSurvivesStop, boolean healthAlwaysUp, boolean ackBeforeHandler, boolean nackIsSuccess,
                UnaryOperator<Envelope> deliveredAs, UnaryOperator<Envelope> repliedAs) {
            this.scheme = scheme; this.schema = schema; this.handlerSurvivesStop = handlerSurvivesStop; this.healthAlwaysUp = healthAlwaysUp;
            this.ackBeforeHandler = ackBeforeHandler; this.nackIsSuccess = nackIsSuccess; this.deliveredAs = deliveredAs; this.repliedAs = repliedAs;
        }

        static Variant correct() { return new Variant("loopback", "{\"type\":\"object\"}", false, false, false, false, e -> e, e -> e); }

        /** {@code stop()} completes at once and drops the answers still pending: their sends fail. */
        static Variant stopDropsPendingAnswers() { Variant v = correct(); v.stopDropsPendingAnswers = true; return v; }

        /** A Nack reaches the sender with {@code nackAs}. */
        static Variant nackReportedAs(Function<Ack.Nack, Throwable> nackAs) { Variant v = correct(); v.nackAs = nackAs; return v; }

        /** The handler runs inside {@code send()}, on the sender's thread (the mock and RecordingComponent before D-057). */
        static Variant handlerOnTheSendersThread() { Variant v = correct(); v.handlerOnTheSendersThread = true; return v; }

        /** The handler runs on {@code where} instead of a virtual thread of its own. */
        static Variant handlerOn(Executor where) { Variant v = correct(); v.handlerThread = where; return v; }

        /** The handler runs on a virtual thread of its own, but what it throws — a null answer included — never reaches the answer. */
        static Variant handlerFailureLost() { Variant v = correct(); v.handlerFailureLost = true; return v; }

        /** Every reply re-stored under the plain next-step key of the envelope sent, the reply's headers kept. */
        static Variant replyStoredUnderThePlainKey() { Variant v = correct(); v.restoredReplies = new InMemoryBlobStore(io.flottila.api.Hasher.sha256(), 1 << 20); return v; }

        /** Every reply re-stored under the next key of the envelope sent — its attempt's — but carrying none of the sender's props. */
        static Variant replyStoredWithoutTheProps() { Variant v = replyStoredUnderThePlainKey(); v.restoredWithoutProps = true; return v; }

        /** The handler sees the envelope without its props — the attempt included — and its reply comes back as it keyed it. */
        static Variant propsLost() {
            return new Variant("loopback", "{\"type\":\"object\"}", false, false, false, false,
                    e -> new Envelope(e.id(), e.flow(), e.step(), e.headers(), Props.empty(), e.keys(), e.body(), e.attachments(), e.views()), e -> e);
        }

        /** The reply's bytes stored for {@code sent}'s next step, the reply's headers kept: what the sender gets back. */
        private Future<Envelope> restored(Envelope sent, Envelope reply) {
            BlobKey key = restoredWithoutProps ? sent.nextKey() : BlobKey.body(sent.id(), sent.step() + 1);
            Props props = restoredWithoutProps ? Props.empty() : sent.props();
            return restoredReplies.write(key, reply.body().whole(), 1 << 20)
                    .map(body -> new Envelope(sent.id(), sent.flow(), sent.step() + 1, reply.headers(), props, sent.keys(), body, Map.of(), new Views()));
        }

        /** Calls the handler the way the variant does; the drain counts the answer before the handler runs. */
        private Future<Ack> dispatch(Running at, Envelope e) {
            if (handlerOnTheSendersThread) return at.drain().track(Future.future(p -> at.handler().apply(e).onComplete(p)));   // a throw fails the answer
            if (handlerFailureLost) {
                Promise<Ack> answer = Promise.promise();
                at.drain().track(answer.future());
                Thread.ofVirtual().name("variant-handler-failure-lost").start(() -> at.handler().apply(e).onComplete(answer));
                return answer.future();
            }
            if (handlerThread == null) return at.drain().deliver(at.handler(), e);
            Promise<Ack> answer = Promise.promise();
            at.drain().track(answer.future());
            handlerThread.execute(() -> {
                try {
                    at.handler().apply(e).onComplete(answer);
                } catch (Throwable failed) {
                    answer.tryFail(failed);
                }
            });
            return answer.future();
        }

        @Override public String scheme() { return scheme; }
        @Override public String configSchema() { return schema; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> h) {
            Running mine = new Running(h, new ConsumerDrain());
            return new Consumer() {
                @Override public Future<Void> start() { handler = mine; return Future.succeededFuture(); }
                @Override public Future<Void> stop() {
                    if (!handlerSurvivesStop) handler = null;
                    if (!stopDropsPendingAnswers) return mine.drain().stop();
                    waiting.forEach(p -> p.tryFail(new IllegalStateException("consumer stopped: answer dropped")));
                    return Future.succeededFuture();
                }
            };
        }
        @Override public Producer producer(EndpointConfig cfg) {
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e) {
                    Running at = handler;
                    if (at == null) return Future.failedFuture(new IllegalStateException("no consumer running"));
                    Future<Ack> ack = dispatch(at, deliveredAs.apply(e));
                    if (ackBeforeHandler) return Future.succeededFuture(e);
                    Promise<Envelope> answer = Promise.promise();
                    waiting.add(answer);
                    answer.future().onComplete(done -> waiting.remove(answer));
                    ack.<Envelope>compose(a -> switch (a) {
                        case Ack.Reply r -> restoredReplies == null ? Future.succeededFuture(repliedAs.apply(r.reply()))
                                : restored(e, repliedAs.apply(r.reply()));
                        case Ack.Accepted ok -> Future.succeededFuture(e);
                        case Ack.Nack n -> nackIsSuccess ? Future.succeededFuture(e) : Future.failedFuture(nackAs.apply(n));
                    }).onComplete(done -> { if (done.succeeded()) answer.tryComplete(done.result()); else answer.tryFail(done.cause()); });
                    return answer.future();
                }
                @Override public Future<Void> close() { return Future.succeededFuture(); }
            };
        }
        @Override public Future<Health> health() { return Future.succeededFuture(healthAlwaysUp || handler != null ? Health.up() : Health.down("no consumer")); }
    }

    /** The engine instantiates the suite itself; the variant under test is handed over through this slot. */
    static volatile Component current = Variant.correct();

    static class VariantContractTest extends ComponentContract {
        @Override protected Component component() { return current; }
        @Override protected EndpointConfig endpoint() { return LOOP; }
    }

    /** The same suite with a second of patience: a handler that waits in vain for the other delivery gives up sooner. */
    static class ImpatientVariantContractTest extends VariantContractTest {
        @Override protected Duration patience() { return Duration.ofSeconds(1); }
    }

    static final EndpointConfig EPHEMERAL = new EndpointConfig("in", URI.create("ephemeral://in"), Map.of());   // no address before start

    /** An address assigned at start, but the suite never asks the started consumer for it: every producer targets {@link #endpoint()}. */
    static class EphemeralWithoutOverrideContractTest extends ComponentContract {
        @Override protected Component component() { return new EphemeralAddressComponent(); }
        @Override protected EndpointConfig endpoint() { return EPHEMERAL; }
        @Override protected Duration patience() { return Duration.ofMillis(200); }   // a handler never invoked is waited out
    }

    /** The producer reaches the started consumer's address, but {@link Consumer#stop()} leaves the handler registered there. */
    static class EphemeralStopKeepsTheAddressContractTest extends ComponentContract {
        @Override protected Component component() { return new EphemeralAddressComponent(true); }
        @Override protected EndpointConfig endpoint() { return EPHEMERAL; }
        @Override protected EndpointConfig producerEndpoint(Consumer started) {
            return new EndpointConfig("in", URI.create("ephemeral://in"), Map.of("address", ((EphemeralAddressComponent.EphemeralConsumer) started).address()));
        }
    }

    static Set<String> failing(Component variant) {
        current = variant;
        return failing(VariantContractTest.class);
    }

    static Set<String> failingImpatiently(Component variant) {
        current = variant;
        return failing(ImpatientVariantContractTest.class);
    }

    static Set<String> failing(Class<? extends ComponentContract> suite) {
        Events events = EngineTestKit.engine("junit-jupiter").selectors(selectClass(suite)).execute().testEvents();
        assertEquals(0, events.skipped().count(), "nothing skipped");
        Set<String> failed = new TreeSet<>();
        events.failed().stream().forEach(e -> failed.add(e.getTestDescriptor().getDisplayName().replace("()", "")));
        return failed;
    }

    @Test
    void theCorrectLoopbackPassesEveryCase() {
        assertEquals(Set.of(), failing(Variant.correct()));
    }

    @Test
    void eachBrokenPropertyFailsExactlyItsCases() {
        Variant ok = Variant.correct();
        assertEquals(Set.of("schemeMatchesTheEndpointUri"), failing(new Variant("other", ok.schema, false, false, false, false, e -> e, e -> e)), "wrong scheme");
        assertEquals(Set.of("schemeMatchesTheEndpointUri"), failing(new Variant(" ", ok.schema, false, false, false, false, e -> e, e -> e)), "blank scheme");
        assertEquals(Set.of("configSchemaIsAJsonObjectSchema"), failing(new Variant("loopback", "{\"type\":\"string\"}", false, false, false, false, e -> e, e -> e)), "schema not an object schema");
        assertEquals(Set.of("configSchemaIsAJsonObjectSchema"), failing(new Variant("loopback", "not json", false, false, false, false, e -> e, e -> e)), "schema not JSON");
        assertEquals(Set.of("aStoppedConsumerReceivesNothing", "healthIsUpWhileAConsumerRunsAndDownAfterItStopped", "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender",
                        "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(new Variant("loopback", ok.schema, true, false, false, false, e -> e, e -> e)), "the handler survives stop: delivers after stop and reports up");
        assertEquals(Set.of("healthIsUpWhileAConsumerRunsAndDownAfterItStopped"), failing(new Variant("loopback", ok.schema, false, true, false, false, e -> e, e -> e)), "health always up");
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "theSendCompletesOnlyAfterTheHandlerAcknowledged",
                        "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender"),
                failing(new Variant("loopback", ok.schema, false, false, false, false, e -> e,
                        e -> e.withHeader("x-seen", "dropped").withHeader("x-acked", "dropped").withHeader("x-answer", "dropped"))), "reply dropped (headers replaced)");
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "theSendCompletesOnlyAfterTheHandlerAcknowledged", "aNackFromTheHandlerFailsTheSendWithItsReason",
                        "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender", "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable", THROWS, NULL_ANSWER, ATTEMPT_REPLY),
                failing(new Variant("loopback", ok.schema, false, false, true, false, e -> e, e -> e)), "acknowledged before the handler answered (the envelope sent comes back)");
        assertEquals(Set.of(), failing(new Variant("loopback", ok.schema, false, false, false, false, e -> e.withId("receiver-minted"), e -> e)),
                "a receiver that mints its own id (every real transport does) passes: the id is not what crosses");
        assertEquals(Set.of("aNackFromTheHandlerFailsTheSendWithItsReason", "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(new Variant("loopback", ok.schema, false, false, false, true, e -> e, e -> e)), "a NACK treated as success");
        Body truncated = Body.of(FakeRuntime.create().blobs(), io.flottila.api.BlobKey.body("t", 0), new Hash("sha256-64", 0), 0);
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "aBodyAboveTheTreeLimitStreamsThroughIntact", "aSecondSendOfTheSameEnvelopeDeliversTheSameBodyAgain"),
                failing(new Variant("loopback", ok.schema, false, false, false, false, e -> e.next(truncated), e -> e)), "the body does not arrive intact");
        current = Variant.correct();
    }

    @Test
    void aStopThatDropsTheAnswersInFlightFailsBothStopCases() {
        assertEquals(Set.of("stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender", "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(Variant.stopDropsPendingAnswers()), "stop completes at once and the pending sends fail: neither waits for the delivery in flight");
        current = Variant.correct();
    }

    @Test
    void aCancelThatReachesTheSenderAsAnythingButARetryableNackExceptionWithItsReasonFailsTheCancelCase() {
        assertEquals(Set.of("aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(Variant.nackReportedAs(n -> new NackException(n.reason(), false))), "the retryable flag is lost on the way");
        assertEquals(Set.of("aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(Variant.nackReportedAs(n -> new IllegalStateException("nack: " + n.reason()))), "not reported as a NackException");
        assertEquals(Set.of("aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(Variant.nackReportedAs(n -> new NackException("nack: " + n.reason().replace("cancelled", "canceled"), n.retryable()))),
                "the reason does not travel intact (the existing NACK case only looks for its own marker)");
        current = Variant.correct();
    }

    static final String OWN_THREAD = "theHandlerRunsOnAVirtualThreadOfItsOwnPerDelivery";
    static final String THROWS = "aHandlerThatThrowsFailsTheSendAndStopStillCompletes";
    static final String NULL_ANSWER = "aHandlerThatAnswersNullFailsTheSendAndStopStillCompletes";
    static final String ATTEMPT_REPLY = "aReplyOfASecondAttemptIsKeyedByItsAttempt";

    @Test
    void aReplyStoredUnderThePlainKeyWhateverTheAttemptFailsOnlyTheAttemptCase() {
        assertEquals(Set.of(ATTEMPT_REPLY), failing(Variant.replyStoredUnderThePlainKey()),
                "every attempt's reply under (id, step + 1, body): the second is not keyed by its attempt and overwrites the first's bytes");
        current = Variant.correct();
    }

    @Test
    void aReplyKeyedByItsAttemptThatDropsTheSendersPropsFailsOnlyTheAttemptCase() {
        assertEquals(Set.of(ATTEMPT_REPLY), failing(Variant.replyStoredWithoutTheProps()),
                "the keys and the bytes are right, but the reply does not carry the attempt on: the steps after it would write under plain keys");
        current = Variant.correct();
    }

    @Test
    void aDeliveryThatLosesThePropsOfAReplyReturnedAsItIsFailsOnlyTheAttemptCase() {
        assertEquals(Set.of(ATTEMPT_REPLY), failing(Variant.propsLost()),
                "the handler never sees the attempt and the reply comes back as the handler keyed it: the plain key for both attempts");
        current = Variant.correct();
    }

    @Test
    void aHandlerCalledOnTheSendersThreadFailsOnlyTheOwnThreadCase() {
        assertEquals(Set.of(OWN_THREAD), failing(Variant.handlerOnTheSendersThread()),
                "the handler runs inside send(): virtual (the senders are), one per delivery, but the sender's own thread");
        current = Variant.correct();
    }

    @Test
    void aHandlerCalledOnAPlatformThreadPerDeliveryFailsOnlyTheOwnThreadCase() {
        assertEquals(Set.of(OWN_THREAD), failing(Variant.handlerOn(r -> Thread.ofPlatform().daemon().name("variant-platform-handler").start(r))),
                "a thread of its own per delivery, but a platform thread");
        current = Variant.correct();
    }

    @Test
    void handlersSharingOneVirtualThreadFailOnlyTheOwnThreadCase() {
        try (ExecutorService one = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("variant-shared-handler").factory())) {
            assertEquals(Set.of(OWN_THREAD), failingImpatiently(Variant.handlerOn(one)),
                    "virtual and never the sender's, but one thread for every delivery: the second waits until the first has answered");
        }
        current = Variant.correct();
    }

    @Test
    void aHandlerFailureThatNeverReachesTheAnswerFailsExactlyTheBrokenHandlerCases() {
        assertEquals(Set.of(THROWS, NULL_ANSWER), failingImpatiently(Variant.handlerFailureLost()),
                "a thrown exception or a null answer kills the handler's thread and leaves the send and the stop pending");
        current = Variant.correct();
    }

    @Test
    void anAddressAssignedAtStartIsReachedOnlyThroughTheStartedConsumersProducerEndpoint() {
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "theSendCompletesOnlyAfterTheHandlerAcknowledged",
                        "aNackFromTheHandlerFailsTheSendWithItsReason", "aBodyAboveTheTreeLimitStreamsThroughIntact", "aSecondSendOfTheSameEnvelopeDeliversTheSameBodyAgain",
                        "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender", "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable", OWN_THREAD,
                        THROWS, NULL_ANSWER, ATTEMPT_REPLY),
                failing(EphemeralWithoutOverrideContractTest.class),
                "without producerEndpoint the producer has no address: every case that needs a delivery fails, the component delivers only to the address named");
    }

    @Test
    void aStoppedConsumerWhoseAddressStaysRegisteredFailsTheStoppedHealthAndBothStopCases() {
        assertEquals(Set.of("aStoppedConsumerReceivesNothing", "healthIsUpWhileAConsumerRunsAndDownAfterItStopped", "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender",
                        "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(EphemeralStopKeepsTheAddressContractTest.class),
                "stop() leaves the address registered: the producer built from where the consumer was still delivers, also while a delivery is in flight; health stays up");
    }
}
