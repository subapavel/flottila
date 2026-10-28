package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The polling contract is only worth its name if every case fails for the consumer that breaks exactly that property
 * (D-076). One in-memory polling component, correct by default, broken one way per variant, run through the JUnit engine;
 * each variant's exact set of failing cases is pinned, and the check that caught it where a later check could mask it.
 */
class PollingConsumerContractSelfTest {

    static final Set<String> CASES = Set.of("schemeMatchesTheEndpointUri", "configSchemaIsAJsonObjectSchema",
            "aDroppedFileIsDeliveredIntactAndRemovedAfterAccepted", "theFileStaysWhileTheAnswerIsPending",
            "aRetryableNackKeepsTheFileAndTheNextRoundDeliversItAgain", "aRetryableNackEndsTheRound", "aHandlerThatThrowsOrAnswersNullKeepsTheFile",
            "aFinalNackIsNotDeliveredAgain", "filesAreDeliveredOldestFirst", "aReplyFinishesTheFileAsAcceptedDoes",
            "aBodyAboveTheTreeLimitStreamsThroughIntact", "eachDeliveryRunsOnAVirtualThreadOfItsOwn",
            "stopWaitsForTheDeliveryInFlightAndCompletesItsRemoval", "aStoppedConsumerDeliversNothing", "healthIsUpWhileRunningAndDownAfterStop");

    static final String DROPPED = "aDroppedFileIsDeliveredIntactAndRemovedAfterAccepted";
    static final String PENDING = "theFileStaysWhileTheAnswerIsPending";
    static final String NACK_KEEPS = "aRetryableNackKeepsTheFileAndTheNextRoundDeliversItAgain";
    static final String NACK_ENDS = "aRetryableNackEndsTheRound";
    static final String BROKEN_HANDLER = "aHandlerThatThrowsOrAnswersNullKeepsTheFile";
    static final String FINAL_NACK = "aFinalNackIsNotDeliveredAgain";
    static final String OLDEST = "filesAreDeliveredOldestFirst";
    static final String REPLY = "aReplyFinishesTheFileAsAcceptedDoes";
    static final String BIG = "aBodyAboveTheTreeLimitStreamsThroughIntact";
    static final String OWN_THREAD = "eachDeliveryRunsOnAVirtualThreadOfItsOwn";
    static final String STOP_WAITS = "stopWaitsForTheDeliveryInFlightAndCompletesItsRemoval";
    static final String STOPPED = "aStoppedConsumerDeliversNothing";
    static final String HEALTH = "healthIsUpWhileRunningAndDownAfterStop";

    /**
     * A polling component over an in-memory directory, its own fake clock and timer and a BlobStore with a 4 KiB tree limit.
     * The correct one: {@code start()} schedules round 0; the timer only fires a round, which runs on a virtual thread; a
     * round takes the files by (drop order, name), one at a time, writes each body by streaming, calls the handler on a
     * virtual thread of its own and waits for the answer; Accepted or Reply removes the file; a retryable Nack or a failure
     * keeps it and ends the round; a final Nack keeps it and skips it from then on; the next round is scheduled when a
     * round ends. {@code stop()} cancels the next round, takes no new file and completes when the round in flight is over.
     * Each flag breaks one of these.
     */
    static final class Variant implements Component {
        static final Duration INTERVAL = Duration.ofSeconds(10);

        record File(String name, long mtime, Buffer bytes) {}

        final FakeClock clock = FakeClock.at(FakeRuntime.T0);
        final FakeTimer timer = new FakeTimer(clock);
        final InMemoryBlobStore blobs = new InMemoryBlobStore(Hasher.sha256(), 4096);
        private final Map<String, File> dir = new ConcurrentHashMap<>();
        private final AtomicLong mtimes = new AtomicLong();
        private final AtomicLong ids = new AtomicLong();
        private final Set<Poller> running = ConcurrentHashMap.newKeySet();

        String scheme = "polling";
        String schema = "{\"type\":\"object\"}";
        /** Broken: health reports UP whether or not a consumer runs. */
        boolean healthAlwaysUp;
        /** Broken: files taken in name order, not oldest first. */
        boolean byName;
        /** Broken: the file is removed right after the handler was called, before its answer is awaited. */
        boolean removedBeforeTheAnswer;
        /** Broken: the file is removed after any answer, a Nack and a failure included. */
        boolean removedOnAnyAnswer;
        /** Broken: a retryable Nack keeps the file but the round goes on to the next one. */
        boolean retryableNackGoesOn;
        /** Broken: a handler that throws, answers null or fails keeps the file but the round goes on. */
        boolean failureGoesOn;
        /** Broken: a finally refused file is delivered again by the next round (the refused set is never read). */
        boolean refusedDeliveredAgain;
        /** Broken: the handler runs on the round's own thread. */
        boolean handlerOnTheRoundsThread;
        /** Broken: the round runs on the thread the timer fires it on. */
        boolean roundOnTheTimersThread;
        /** Broken: stop() completes at once, whatever is in flight. */
        boolean stopAtOnce;
        /** Broken: stop() completes once the answer in flight is in, before that file's removal (which waits for the stop). */
        boolean stopBeforeTheRemoval;
        /** Broken: stop() leaves the rounds running: the timer is not cancelled and the rounds deliver on. */
        boolean stopLeavesTheRounds;
        /** Broken: once stop() began, the round in flight still takes its next file. */
        boolean stopTakesTheNextFile;
        /** Broken: a Reply is taken for a retryable Nack: the file stays and the round ends. */
        boolean replyKeepsTheFile;
        /** What the handler sees of a file's bytes; the correct one sees them all. */
        UnaryOperator<Buffer> delivered = b -> b;

        static Supplier<Variant> broken(java.util.function.Consumer<Variant> defect) {
            return () -> {
                Variant v = new Variant();
                defect.accept(v);
                return v;
            };
        }

        void drop(String name, Buffer bytes) { dir.put(name, new File(name, mtimes.incrementAndGet(), bytes.copy())); }

        /** With {@link #stopBeforeTheRemoval}: counted down by the first look at the directory once a stop has completed. */
        private final CountDownLatch lookedAfterTheStop = new CountDownLatch(1);
        private volatile Poller stoppedOne;

        Set<String> remaining() {
            Set<String> now = Set.copyOf(dir.keySet());
            Poller p = stoppedOne;
            if (p != null && p.stopped.future().isComplete()) lookedAfterTheStop.countDown();
            return now;
        }

        @Override public String scheme() { return scheme; }

        @Override public String configSchema() { return schema; }

        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { return new Poller(handler); }

        @Override public Producer producer(EndpointConfig cfg) { throw new IllegalArgumentException("a poller has no producer"); }

        @Override public Future<Health> health() {
            return Future.succeededFuture(healthAlwaysUp || !running.isEmpty() ? Health.up() : Health.down("no consumer polls"));
        }

        final class Poller implements Consumer {
            private final Function<Envelope, Future<Ack>> handler;
            private final Set<String> refused = ConcurrentHashMap.newKeySet();
            private final Promise<Void> stopped = Promise.promise();
            private Timer.Cancellable next;        // guarded by this
            private boolean stopping;              // guarded by this
            private Promise<Void> round;           // the round in flight, guarded by this
            private volatile Future<Ack> answerInFlight;

            Poller(Function<Envelope, Future<Ack>> handler) { this.handler = handler; }

            @Override public Future<Void> start() {
                running.add(this);
                schedule(Duration.ZERO);
                return Future.succeededFuture();
            }

            private synchronized void schedule(Duration delay) {
                if (!stopping) next = timer.schedule(delay, this::fire);
            }

            private void fire() {
                if (roundOnTheTimersThread) round();
                else Thread.ofVirtual().name("variant-round").start(this::round);
            }

            private synchronized boolean stopping() { return stopping; }

            private void round() {
                Promise<Void> mine = Promise.promise();
                synchronized (this) {
                    if (stopping) return;
                    round = mine;
                }
                try {
                    Comparator<File> order = byName ? Comparator.comparing(File::name)
                            : Comparator.comparingLong(File::mtime).thenComparing(File::name);
                    for (File f : dir.values().stream().sorted(order).toList()) {
                        if (stopping() && !stopTakesTheNextFile) break;
                        String key = f.name() + "@" + f.mtime();
                        if (refused.contains(key) && !refusedDeliveredAgain) continue;
                        if (!take(f, key)) break;
                    }
                } finally {
                    synchronized (this) { round = null; }
                    mine.complete();
                    schedule(INTERVAL);
                }
            }

            /** Delivers {@code f}; whether the round goes on. */
            private boolean take(File f, String key) {
                String id = "v-" + ids.incrementAndGet();
                Body body = blobs.write(BlobKey.body(id, 0), new ChunkedReadStream(List.of(delivered.apply(f.bytes()))), Long.MAX_VALUE).await();
                Promise<Ack> promised = Promise.promise();
                // Published before the handler can run: a stop() issued once the case saw the handler called reads this answer,
                // never null nor the previous file's (the handler's thread may record the call before this thread runs on).
                answerInFlight = promised.future();
                deliver(Envelope.ingress(id, "contract", Headers.empty(), body), promised);
                Future<Ack> answer = promised.future();
                if (removedBeforeTheAnswer) dir.remove(f.name());
                Ack ack;
                try {
                    ack = answer.await();
                } catch (Throwable failed) {
                    ack = null;
                }
                if (removedOnAnyAnswer) dir.remove(f.name());
                return switch (ack) {
                    case Ack.Accepted a -> remove(f);
                    case Ack.Reply r when !replyKeepsTheFile -> remove(f);
                    case Ack.Reply r -> false;
                    case Ack.Nack n when !n.retryable() -> {
                        refused.add(key);
                        yield true;
                    }
                    case Ack.Nack n -> retryableNackGoesOn;
                    case null -> failureGoesOn;
                };
            }

            private boolean remove(File f) {
                if (stopBeforeTheRemoval && stopping()) {   // the stop has completed: the removal waits until the case looked
                    try {
                        if (!lookedAfterTheStop.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("nobody looked after the stop");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                dir.remove(f.name());
                return true;
            }

            /** The handler on a virtual thread of its own, its answer completing {@code answer}; a throw or a null answer fails it. */
            private void deliver(Envelope e, Promise<Ack> answer) {
                Runnable call = () -> {
                    try {
                        Future<Ack> f = handler.apply(e);
                        if (f == null) answer.tryFail(new IllegalStateException("the handler answered null"));
                        else f.onComplete(answer);
                    } catch (Throwable thrown) {
                        answer.tryFail(thrown);
                    }
                };
                if (handlerOnTheRoundsThread) call.run();
                else Thread.ofVirtual().name("variant-handler").start(call);
            }

            @Override public Future<Void> stop() {
                Promise<Void> inFlight;
                synchronized (this) {
                    running.remove(this);
                    if (stopLeavesTheRounds) return Future.succeededFuture();
                    stopping = true;
                    if (next != null) next.cancel();
                    inFlight = round;
                }
                if (stopAtOnce || inFlight == null) {
                    stopped.tryComplete();
                } else if (stopBeforeTheRemoval) {
                    stoppedOne = this;
                    answerInFlight.onComplete(done -> stopped.tryComplete());
                } else {
                    inFlight.future().onComplete(done -> stopped.tryComplete());
                }
                return stopped.future();
            }
        }
    }

    /** The engine instantiates the suite itself; the variant under test is handed over through this slot. */
    static volatile Supplier<Variant> current = Variant::new;

    static class VariantPollingContractTest extends PollingConsumerContract {
        private Variant variant;   // one per case: JUnit makes an instance per case

        private Variant variant() {
            if (variant == null) variant = current.get();
            return variant;
        }

        @Override protected Component component() { return variant(); }
        @Override protected EndpointConfig endpoint() { return new EndpointConfig("in", URI.create("polling://partner/inbox"), Map.of()); }
        @Override protected void drop(String name, ReadStream<Buffer> bytes) { variant().drop(name, bytesOf(bytes)); }
        @Override protected Set<String> remaining() { return variant().remaining(); }
        @Override protected void tick() { advanceToNextRound(variant().clock, variant().timer); }
        @Override protected Duration patience() { return Duration.ofSeconds(2); }
    }

    static Map<String, Throwable> failures(Supplier<Variant> variant) {
        current = variant;
        try {
            return ContractRun.failures(VariantPollingContractTest.class);
        } finally {
            current = Variant::new;
        }
    }

    static Set<String> failing(java.util.function.Consumer<Variant> defect) {
        return failures(Variant.broken(defect)).keySet();
    }

    @Test
    void theContractHasExactlyTheseCases() {
        Set<String> declared = new TreeSet<>();
        for (Method m : PollingConsumerContract.class.getDeclaredMethods()) if (m.isAnnotationPresent(Test.class)) declared.add(m.getName());
        assertEquals(new TreeSet<>(CASES), declared, "a case added or removed changes what the self-test pins");
    }

    @Test
    void theCorrectVariantPassesEveryCase() {
        assertEquals(Map.of(), failures(Variant::new));
    }

    @Test
    void aWrongSchemeOrSchemaFailsOnlyItsCase() {
        assertEquals(Set.of("schemeMatchesTheEndpointUri"), failing(v -> v.scheme = "other"));
        assertEquals(Set.of("configSchemaIsAJsonObjectSchema"), failing(v -> v.schema = "{\"type\":\"string\"}"));
    }

    @Test
    void healthUpWithoutAConsumerFailsOnlyTheHealthCase() {
        assertEquals(Set.of(HEALTH), failing(v -> v.healthAlwaysUp = true));
    }

    @Test
    void filesTakenInNameOrderFailOnlyTheOldestFirstCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.byName = true));
        assertEquals(Set.of(OLDEST), f.keySet());
        ContractRun.caughtBy(f, OLDEST, "oldest first, whatever the names or the listing order say");
    }

    @Test
    void aRemovalBeforeTheAnswerIsAwaitedFailsEveryCaseThatKeepsAFile() {
        assertEquals(Set.of(PENDING, NACK_KEEPS, NACK_ENDS, BROKEN_HANDLER, FINAL_NACK, STOP_WAITS, STOPPED),
                failing(v -> v.removedBeforeTheAnswer = true));
    }

    @Test
    void aRemovalOnAnyAnswerFailsEveryCaseWhoseAnswerKeepsTheFile() {
        assertEquals(Set.of(PENDING, NACK_KEEPS, NACK_ENDS, BROKEN_HANDLER, FINAL_NACK, STOPPED), failing(v -> v.removedOnAnyAnswer = true));
    }

    @Test
    void aRoundThatGoesOnAfterARetryableNackFailsOnlyTheEndOfRoundCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.retryableNackGoesOn = true));
        assertEquals(Set.of(NACK_ENDS), f.keySet());
        ContractRun.caughtBy(f, NACK_ENDS, "the retryable Nack ended the first round");
    }

    @Test
    void aRoundThatGoesOnAfterAFailedAnswerFailsOnlyTheBrokenHandlerCase() {
        assertEquals(Set.of(BROKEN_HANDLER), failing(v -> v.failureGoesOn = true));
    }

    @Test
    void aRefusedFileDeliveredAgainFailsOnlyTheFinalNackCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.refusedDeliveredAgain = true));
        assertEquals(Set.of(FINAL_NACK), f.keySet());
        ContractRun.caughtBy(f, FINAL_NACK, "it was not delivered again");
        // the round may or may not have reached the younger file when the case looks: the second delivery is the refused one either way
        ContractRun.caughtBy(f, FINAL_NACK, "expected: <[refused, later]> but was: <[refused, refused");
    }

    @Test
    void aHandlerOnTheRoundsThreadFailsOnlyTheOwnThreadCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.handlerOnTheRoundsThread = true));
        assertEquals(Set.of(OWN_THREAD), f.keySet());
        ContractRun.caughtBy(f, OWN_THREAD, "not on one thread for the round");
    }

    @Test
    void aRoundOnTheTimersThreadFailsTheCasesThatHoldAnAnswer() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.roundOnTheTimersThread = true));
        assertEquals(Set.of(PENDING, STOP_WAITS), f.keySet());
        ContractRun.caughtBy(f, PENDING, "the timer's thread runs the round instead of firing it");
        ContractRun.caughtBy(f, STOP_WAITS, "the timer's thread runs the round instead of firing it");
    }

    @Test
    void aStopThatCompletesAtOnceFailsOnlyTheStopCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.stopAtOnce = true));
        assertEquals(Set.of(STOP_WAITS), f.keySet());
        ContractRun.caughtBy(f, STOP_WAITS, "stop waits for the delivery in flight");
    }

    @Test
    void aStopThatCompletesBeforeTheRemovalFailsOnlyTheStopCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.stopBeforeTheRemoval = true));
        assertEquals(Set.of(STOP_WAITS), f.keySet());
        ContractRun.caughtBy(f, STOP_WAITS, "stop completed only after the accepted file was removed");
    }

    @Test
    void aStopThatTakesTheNextFileFailsOnlyTheStopCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.stopTakesTheNextFile = true));
        assertEquals(Set.of(STOP_WAITS), f.keySet());
        ContractRun.caughtBy(f, STOP_WAITS, "took no new file once stop began");
    }

    @Test
    void aStopThatLeavesTheRoundsRunningFailsTheStoppedAndTheStopCases() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.stopLeavesTheRounds = true));
        assertEquals(Set.of(STOPPED, STOP_WAITS), f.keySet());
        ContractRun.caughtBy(f, STOP_WAITS, "stop waits for the delivery in flight");
    }

    @Test
    void aReplyThatKeepsTheFileFailsOnlyTheReplyCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.replyKeepsTheFile = true));
        assertEquals(Set.of(REPLY), f.keySet());
        ContractRun.caughtBy(f, REPLY, "a sync flow's Reply finishes the file as an Accepted does");
    }

    @Test
    void aLargeBodyCutAtTheTreeLimitFailsOnlyTheLargeBodyCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.delivered = b -> b.length() > 4096 ? b.slice(0, 4096).copy() : b));
        assertEquals(Set.of(BIG), f.keySet());
        ContractRun.caughtBy(f, BIG, "every byte arrived");
    }

    @Test
    void aLargeBodyWithOneByteAlteredFailsOnlyTheLargeBodyCaseAtTheByteCheck() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.delivered = b -> {
            if (b.length() <= 4096) return b;
            Buffer altered = b.copy();
            altered.setByte(150_000, (byte) (altered.getByte(150_000) + 1));
            return altered;
        }));
        assertEquals(Set.of(BIG), f.keySet());
        ContractRun.caughtBy(f, BIG, "byte 150000 differs");
    }

    @Test
    void aSmallBodyThatLosesItsLastByteFailsTheIntactCaseAtItsBodyCheck() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.delivered = b -> b.length() > 4096 ? b : b.slice(0, Math.max(0, b.length() - 1)).copy()));
        assertEquals(Set.of(DROPPED, PENDING, NACK_KEEPS, NACK_ENDS, BROKEN_HANDLER, FINAL_NACK, OLDEST, STOP_WAITS), f.keySet(),
                "every case that tells the files apart by their text");
        ContractRun.caughtBy(f, DROPPED, "the body arrives intact");
    }
}
