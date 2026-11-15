package io.rail8.core.flow;

import io.rail8.api.Envelope;
import io.rail8.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.rail8.core.flow.FlowCloseTest.texts;
import static io.rail8.core.flow.FlowCloseTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-141: a producer whose {@code close()} throws — whatever it throws, an {@link Error} and a sneaky checked exception
 * included — is the same failed close as a failed future: logged at WARNING, naming the flow and the producer, and no other
 * producer is stranded. The loop finishes, every producer is asked exactly once (counted on the fixture, not inferred from
 * a side effect), and the close completes successfully once every producer's close has settled.
 *
 * <p>Seam: {@link Producers#close()}, the one a {@link Delivery} and a {@link FlowWorker} call. Fixture: a hand-written
 * {@link Producer} that counts its closes and throws (through {@link ParkingTest#sneaky}, no proxy) or answers as told.
 * The FIX-30 cases ({@link FlowCloseTest}, {@link FlowCloseEdgeTest}) cover the RuntimeException and failed-future shapes
 * through the builder; this class is the only one that throws an {@link Error}.
 */
class ProducersCloseTest {

    static final String FLOW = "flow-141";
    static final String PRODUCERS_LOGGER = Producers.class.getName();

    /** What a producer's close throws. {@link #RUNTIME} is green before the fix (the regression guard); the others are the defect. */
    enum Thrown {
        ERROR, RUNTIME, CHECKED;

        Throwable make(String text) {
            return switch (this) {
                case ERROR -> new BrokenProducer(text);
                case RUNTIME -> new IllegalStateException(text);
                case CHECKED -> new IOException(text);
            };
        }
    }

    /** An Error that is not an OutOfMemoryError: one producer is broken, the JVM is not. */
    static final class BrokenProducer extends Error {
        BrokenProducer(String message) { super(message); }
    }

    /** A producer that counts its closes and answers as told: a throw, a given future, or a succeeded one. */
    static final class Counting implements Producer {
        final String name;
        final AtomicInteger closes = new AtomicInteger();
        private final Throwable thrown;
        private final Future<Void> answer;

        private Counting(String name, Throwable thrown, Future<Void> answer) {
            this.name = name;
            this.thrown = thrown;
            this.answer = answer;
        }

        static Counting clean(String name) { return new Counting(name, null, Future.succeededFuture()); }
        static Counting answering(String name, Future<Void> answer) { return new Counting(name, null, answer); }
        static Counting throwing(String name, Throwable thrown) { return new Counting(name, thrown, null); }

        @Override
        public Future<Envelope> send(Envelope e) {
            throw new AssertionError(name + ": a send is not part of a close");
        }

        @Override
        public Future<Void> close() {
            closes.incrementAndGet();
            if (thrown != null) throw ParkingTest.sneaky(thrown);
            return answer;
        }

        @Override
        public String toString() { return name; }
    }

    /** The outcome of one {@code Producers.close()}: the future, or what escaped the call. */
    record Closed(Future<Void> future, Throwable escaped) {}

    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger rail8 = Logger.getLogger("io.rail8");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    void listen() {
        capture.setLevel(Level.ALL);
        rail8.addHandler(capture);
    }

    @AfterEach
    void unlisten() {
        rail8.removeHandler(capture);
    }

    /** Closes; what escapes is returned, not swallowed, so a case asserts on the producers before it asserts on the escape. */
    static Closed close(Producer... producers) {
        try {
            return new Closed(new Producers(FLOW, List.of(producers)).close(), null);
        } catch (Throwable escaped) {
            return new Closed(null, escaped);
        }
    }

    List<LogRecord> warnings() {
        return logged.stream()
                .filter(r -> r.getLevel() == Level.WARNING && PRODUCERS_LOGGER.equals(r.getLoggerName()))
                .toList();
    }

    String dump() {
        return logged.stream().map(r -> r.getLoggerName() + " " + r.getLevel() + " " + texts(r)).toList().toString();
    }

    static void assertAskedOnce(String when, Counting... producers) {
        for (Counting p : producers) assertEquals(1, p.closes.get(), when + ": " + p.name + " is asked to close exactly once");
    }

    /**
     * Case 1, the whole defect: the MIDDLE producer's close throws, so the loop must go on to the THIRD. The third's call count is
     * what separates "the loop was abandoned" from "a warning is missing"; the escape is asserted last, so a skipped third is
     * the failure reported.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Thrown.class)
    void theLoopFinishesTheThirdProducerIsAskedWhenTheMiddleOnesCloseThrows(Thrown how) {
        Counting first = Counting.clean("first"), middle = Counting.throwing("middle", how.make("lid welded")), third = Counting.clean("third");

        Closed closed = close(first, middle, third);

        assertAskedOnce("after the middle one threw " + how + " (escaped: " + closed.escaped() + ")", first, middle, third);
        assertNull(closed.escaped(), "a throwing close does not escape the close");
    }

    /** The first and the last position too: a throw in the first stops nothing after it; a throw in the last strands nobody and still logs. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Thrown.class)
    void theLoopFinishesWhicheverProducerThrows(Thrown how) {
        Counting a = Counting.throwing("a", how.make("jam a")), b = Counting.clean("b"), c = Counting.clean("c");
        Closed throwsFirst = close(a, b, c);
        assertAskedOnce("first threw " + how + " (escaped: " + throwsFirst.escaped() + ")", a, b, c);
        assertNull(throwsFirst.escaped(), "the first one's throw does not escape");

        Counting x = Counting.clean("x"), y = Counting.clean("y"), z = Counting.throwing("z", how.make("jam z"));
        Closed throwsLast = close(x, y, z);
        assertAskedOnce("last threw " + how + " (escaped: " + throwsLast.escaped() + ")", x, y, z);
        assertNull(throwsLast.escaped(), "the last one's throw does not escape");
    }

    /** Case 2: the returned future completes, and completes successfully — it neither fails with the throwable nor hangs. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Thrown.class)
    void theCloseCompletesSuccessfullyWhenAProducersCloseThrows(Thrown how) throws Exception {
        Closed closed = close(Counting.clean("first"), Counting.throwing("middle", how.make("lid welded")), Counting.clean("third"));

        assertNull(closed.escaped(), "the throw does not escape the close");
        within(closed.future(), "the close");
        assertTrue(closed.future().succeeded(), "the close succeeds, whatever one producer did: " + closed.future().cause());
    }

    /**
     * Case 3: exactly one WARNING, from {@link Producers}, naming the flow and the producer whose close threw — not another
     * producer — and carrying the throwable's own text; so a change that logs twice, or not at all, or the wrong producer, fails.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Thrown.class)
    void aThrowingCloseIsLoggedOnceAtWarningNamingTheFlowAndTheProducer(Thrown how) {
        Closed closed = close(Counting.clean("first-producer"), Counting.throwing("middle-producer", how.make("lid welded")), Counting.clean("third-producer"));

        assertNull(closed.escaped(), "the throw does not escape the close");
        List<LogRecord> warnings = warnings();
        assertEquals(1, warnings.size(), "exactly one WARNING from Producers: " + dump());
        List<String> texts = texts(warnings.get(0));
        assertTrue(texts.get(0).contains(FLOW), "the WARNING names the flow: " + texts);
        assertTrue(texts.get(0).contains("middle-producer"), "the WARNING names the producer that failed: " + texts);
        assertFalse(texts.get(0).contains("first-producer") || texts.get(0).contains("third-producer"), "and no other producer: " + texts);
        assertTrue(texts.stream().skip(1).anyMatch(t -> t.contains("lid welded")), "the WARNING carries the throwable itself: " + texts);
    }

    /**
     * Case 4: a producer whose close threw takes nobody's result with it. The first producer's close is genuinely pending; the
     * second throws. The close must still wait for the first — complete only once the first's close answers — so the first's
     * result is in what the caller sees, and the third (after the thrower) is asked too.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Thrown.class)
    void aThrowingCloseKeepsTheEarlierProducersResultInTheClose(Thrown how) throws Exception {
        Promise<Void> firstClosing = Promise.promise();
        Counting first = Counting.answering("first", firstClosing.future()), second = Counting.throwing("second", how.make("lid welded")), third = Counting.clean("third");

        Closed closed = close(first, second, third);

        assertAskedOnce("after the second threw " + how + " (escaped: " + closed.escaped() + ")", first, second, third);
        assertNull(closed.escaped(), "the throw does not escape the close");
        assertFalse(closed.future().isComplete(), "the close waits for the first producer, whose close is still pending");
        firstClosing.complete();
        within(closed.future(), "the close once the first producer's close answered");
        assertTrue(closed.future().succeeded(), "the close succeeds: " + closed.future().cause());
    }

    /** The same, the other way round: the first's close FAILS its future and the second throws — two WARNINGs, one per producer, each naming its own. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Thrown.class)
    void aThrowingCloseAndAFailedFutureAreEachLoggedOnceAndEachProducerIsClosedOnce(Thrown how) throws Exception {
        Counting first = Counting.answering("first-producer", Future.failedFuture(new IllegalStateException("future failed")));
        Counting second = Counting.throwing("second-producer", how.make("lid welded"));
        Counting third = Counting.clean("third-producer");

        Closed closed = close(first, second, third);

        assertAskedOnce("after the second threw " + how + " (escaped: " + closed.escaped() + ")", first, second, third);
        assertNull(closed.escaped(), "the throw does not escape the close");
        within(closed.future(), "the close");
        assertTrue(closed.future().succeeded(), "the close succeeds: " + closed.future().cause());
        List<LogRecord> warnings = warnings();
        assertEquals(2, warnings.size(), "one WARNING per failed producer: " + dump());
        assertTrue(texts(warnings.get(0)).get(0).contains("first-producer"), "the failed future is named: " + texts(warnings.get(0)));
        assertTrue(texts(warnings.get(1)).get(0).contains("second-producer"), "the throw is named: " + texts(warnings.get(1)));
    }

    /** A second close is the first one: no producer is asked again, whatever one of them threw. */
    @Test
    void aSecondCloseAsksNoProducerAgainAfterAnErrorFromOne() throws Exception {
        Counting first = Counting.clean("first"), middle = Counting.throwing("middle", Thrown.ERROR.make("lid welded")), third = Counting.clean("third");
        Producers producers = new Producers(FLOW, List.of(first, middle, third));
        Future<Void> one = null;
        Throwable escaped = null;
        try {
            one = producers.close();
        } catch (Throwable thrown) {
            escaped = thrown;
        }
        assertAskedOnce("after the first close (escaped: " + escaped + ")", first, middle, third);
        assertNull(escaped, "the Error does not escape the first close");

        Future<Void> two = producers.close();

        assertEquals(one, two, "a second close is the first one");
        within(two, "the close");
        assertAskedOnce("after the second close", first, middle, third);
        assertEquals(1, warnings().size(), "still one WARNING: " + dump());
    }

    // --- FIX-145 (D-098): a sneaky InterruptedException out of a close() is swallowed; the flag must not be. Three cases:
    // an interrupt thrown by close() on the closing thread sets the flag again (D-098 (1)); a close that fails for its own
    // reason, no interrupt anywhere, leaves it clear; an InterruptedException arriving in the producer's failed future, in
    // general not the closing thread's own interrupt, leaves it clear too (D-098 (2)).

    /**
     * FIX-145 (D-098): a producer whose {@code close()} throws a sneaky {@link InterruptedException} is a cancelled close, and
     * the thread that ran it must not carry on as if it had not been interrupted: the interrupt flag is set again for the
     * caller (the cancelling caller still sees its interrupt, and the next blocking call on that thread still throws). The
     * WARNING behaviour is unchanged — the close still completes successfully — and the other producers are still closed,
     * each exactly once, counted on the fixture. The flag is cleared before the call, so no earlier case can make this pass,
     * and read with {@link Thread#interrupted()} (read and cleared) on the thread that ran the close, so it never leaks.
     * Red before the fix: {@code catch (Throwable)} (FIX-141) swallows the interrupt into a succeeded future, flag clear.
     */
    @Test
    void anInterruptFromAProducersCloseRestoresTheFlagAndStillClosesTheOthers() throws Exception {
        Counting first = Counting.clean("first-producer");
        Counting middle = Counting.throwing("middle-producer", new InterruptedException("close cancelled"));
        Counting third = Counting.clean("third-producer");
        assertFalse(Thread.interrupted(), "fixture: the flag is clear before the close (read and cleared)");

        Closed closed;
        boolean flagAfter;
        try {
            closed = close(first, middle, third);
        } finally {
            flagAfter = Thread.interrupted();   // read and cleared on the thread that ran the close, whatever it did
        }

        assertNull(closed.escaped(), "the interrupt does not escape the close");
        assertAskedOnce("after the middle one's close was interrupted", first, middle, third);
        within(closed.future(), "the close");
        assertTrue(closed.future().succeeded(), "the close still succeeds: " + closed.future().cause());
        assertEquals(1, warnings().size(), "still exactly one WARNING from Producers: " + dump());
        assertTrue(texts(warnings().get(0)).get(0).contains("middle-producer"), "the WARNING names the interrupted producer: " + texts(warnings().get(0)));
        assertTrue(flagAfter, "the interrupt flag is set again for the caller");
    }

    /**
     * FIX-145, the other side of the interrupt edge, and the witness that the flag is restored for an interrupt and not for
     * every failure: a close that fails for its own reason — a sneaky checked {@link IOException}, no interrupt anywhere —
     * leaves the interrupt flag clear. Green on arrival (the current code sets the flag for nothing at all); what it kills is
     * a fix that sets the flag unconditionally in the close loop, which would otherwise pass
     * {@link #anInterruptFromAProducersCloseRestoresTheFlagAndStillClosesTheOthers} untouched.
     */
    @Test
    void aCloseThatFailsWithoutAnInterruptLeavesTheFlagClear() throws Exception {
        Counting first = Counting.clean("first-producer");
        Counting middle = Counting.throwing("middle-producer", Thrown.CHECKED.make("partner socket reset"));
        Counting third = Counting.clean("third-producer");
        assertFalse(Thread.interrupted(), "fixture: the flag is clear before the close (read and cleared)");

        Closed closed;
        boolean flagAfter;
        try {
            closed = close(first, middle, third);
        } finally {
            flagAfter = Thread.interrupted();   // read and cleared on the thread that ran the close
        }

        assertNull(closed.escaped(), "the checked failure does not escape the close");
        assertAskedOnce("after the middle one's close threw a checked failure", first, middle, third);
        within(closed.future(), "the close");
        assertTrue(closed.future().succeeded(), "the close succeeds: " + closed.future().cause());
        assertFalse(flagAfter, "a close that failed without an interrupt leaves the flag clear");
    }

    /**
     * FIX-145, what pins where the guard sits (D-098 (2)): a producer that does not throw but returns a future already
     * <em>failed</em> with an {@link InterruptedException} was interrupted on its own thread — the closing thread was never
     * cancelled — so the close must leave that thread's interrupt flag CLEAR. This is the opposite of
     * {@link #anInterruptFromAProducersCloseRestoresTheFlagAndStillClosesTheOthers}, where {@code close()} threw the
     * interrupt <em>on the closing thread</em> and the flag it swallowed is the caller's own, so it must be set again.
     * Restoring it here would invent an interrupt nobody raised, and the continuation that observes such a failure runs on
     * whichever thread completed the future — in general not the one that asked for the close.
     *
     * <p>So the guard belongs in the synchronous {@code catch}, not in the {@code recover} lambda that is shared with
     * futures failing later: this case is the only one that separates the two placements (for a
     * {@link Future#failedFuture} the recover body runs inline on the caller's thread, which is why the two sibling cases
     * cannot tell them apart). The rest of the contract is unchanged — the close still succeeds, exactly one WARNING names
     * the failed producer, and every producer is asked to close exactly once.
     */
    @Test
    void aCloseWhoseFutureFailsWithAnInterruptedExceptionLeavesTheFlagClear() throws Exception {
        Counting first = Counting.clean("first-producer");
        Counting middle = Counting.answering("middle-producer",
                Future.failedFuture(new InterruptedException("cancelled on the producer's own thread")));
        Counting third = Counting.clean("third-producer");
        assertFalse(Thread.interrupted(), "fixture: the flag is clear before the close (read and cleared)");

        Closed closed;
        boolean flagAfter;
        try {
            closed = close(first, middle, third);
        } finally {
            flagAfter = Thread.interrupted();   // read and cleared on the thread that ran the close, whatever it did
        }

        assertNull(closed.escaped(), "the failed future does not escape the close");
        assertAskedOnce("after the middle one's close answered a failed future", first, middle, third);
        within(closed.future(), "the close");
        assertTrue(closed.future().succeeded(), "the close still succeeds: " + closed.future().cause());
        assertEquals(1, warnings().size(), "still exactly one WARNING from Producers: " + dump());
        assertTrue(texts(warnings().get(0)).get(0).contains("middle-producer"),
                "the WARNING names the producer whose close failed: " + texts(warnings().get(0)));
        assertFalse(flagAfter, "the producer was interrupted on its own thread: the closing thread's flag stays clear");
    }
}
