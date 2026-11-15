package dev.rail8.testkit;

import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-229: once a send of the {@link RecordingComponent} is visible — to {@code awaitSent}, to {@code sent()} and
 * {@code sentTo()} — it has taken its hold, and holds are taken in the order the sends are recorded. On a CI runner the
 * old order (record and count the send for {@code awaitSent}, then {@code holds.pollFirst()}) let a test that armed its
 * next hold right after {@code awaitSent} returned have its holds swapped ({@code DeploymentsRedeployTest
 * .everyStartWaitsForEveryStop:558 c's held message: not within PT10S}).
 *
 * <p>The window is staged, not raced, through the package-private hook {@code afterSendVisible(Runnable)} of
 * {@code RecordingComponent} (in the manner of the streams' {@code beforeDrainExit}, FIX-12). It runs on the sending
 * thread, after the send is recorded and counted for {@code awaitSent} (visible to {@code sent()}, {@code sentTo()} and
 * {@code awaitSent}), before {@code send} returns; {@code null} (the default) = none. In the fixed order the send's hold
 * is already taken when the hook runs, so these cases stay meaningful after the fix: they hold the first sender in the
 * hook while the second side arms its hold and sends. The public surface alone cannot force the order: between counting
 * and taking a hold {@code send} calls no code of the caller (the reply function, a failure supplier and a consumer's
 * handler all run after the take) and the sender never parks there.
 */
class RecordingComponentHoldOrderTest {

    static final Duration BOUND = Duration.ofSeconds(10);

    final RecordingComponent http = new RecordingComponent("http");
    final EndpointConfig partner = new EndpointConfig("partner", URI.create("http://partner/in"), Map.of());
    final FakeRuntime rt = FakeRuntime.create();
    final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();   // every hold and gate: opened in @AfterEach
    final List<Thread> threads = new CopyOnWriteArrayList<>();
    final LinkedBlockingQueue<String> answered = new LinkedBlockingQueue<>();   // labels, in the order the sends were answered

    @AfterEach
    void openEverything() throws InterruptedException {
        latches.forEach(CountDownLatch::countDown);   // no held send or gated sender outlives its test
        for (Thread t : threads) t.join(BOUND);
    }

    CountDownLatch hold(int times) {
        CountDownLatch release = http.holdNext(times);
        latches.add(release);
        return release;
    }

    CountDownLatch gate() {
        CountDownLatch l = new CountDownLatch(1);
        latches.add(l);
        return l;
    }

    Envelope envelope(String label) {
        return rt.ingress("f", Headers.empty(), Buffer.buffer(label));
    }

    /** {@code sent}, its answer noted in {@link #answered} under {@code label} when it completes (on whatever thread). */
    Future<Envelope> noted(String label, Future<Envelope> sent) {
        sent.onComplete(done -> answered.add(label));
        return sent;
    }

    /** Runs {@code call} on a virtual thread of its own, bounded: a call that does not return fails with where it sits. */
    <T> T onOwnThread(String name, Supplier<T> call) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = Thread.ofVirtual().name(name).start(() -> {
            try {
                result.set(call.get());
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        threads.add(t);
        join(t, name + " returned");
        if (thrown.get() != null) throw new AssertionError(name + " threw", thrown.get());
        return result.get();
    }

    static void join(Thread t, String what) {
        try {
            if (!t.join(BOUND)) fail(what + ": not within " + BOUND + "; " + t.getName() + " sits at " + Arrays.toString(t.getStackTrace()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting until " + what, e);
        }
    }

    static void await(CountDownLatch l, String what, Thread where) {
        try {
            if (!l.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
                fail(what + ": not within " + BOUND + (where == null ? "" : "; " + where.getName() + " sits at " + Arrays.toString(where.getStackTrace())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting until " + what, e);
        }
    }

    String nextAnswered(String what) {
        try {
            String label = answered.poll(BOUND.toMillis(), TimeUnit.MILLISECONDS);
            if (label == null) fail(what + ": no send answered within " + BOUND);
            return label;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting until " + what, e);
        }
    }

    /**
     * The send of {@code e} on a virtual thread "sender-{@code label}", stopped in the hook until {@link #go()}: the hook
     * acts on that thread only (every other send passes it), opens {@code reached} and waits for {@code mayGo}.
     */
    final class Window {
        final String label;
        final CountDownLatch reached = gate();
        final CountDownLatch mayGo = gate();
        final List<String> hookThreads = new CopyOnWriteArrayList<>();
        final AtomicReference<Future<Envelope>> sent = new AtomicReference<>();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final Thread sender;

        Window(Producer p, String label, Envelope e) {
            this.label = label;
            this.sender = Thread.ofVirtual().name("sender-" + label).unstarted(() -> {
                try {
                    sent.set(noted(label, p.send(e)));
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            http.afterSendVisible(() -> {
                hookThreads.add(Thread.currentThread().getName());
                if (Thread.currentThread() != sender) return;
                reached.countDown();
                await(mayGo, "the test let " + label + "'s send go on", null);
            });
            threads.add(sender);
            sender.start();
        }

        /** Waits until the sender stands in the hook — its send recorded, counted, visible. */
        void awaitReached() {
            await(reached, "the hook ran on sender-" + label + " (it runs after the send is visible, before send returns)", sender);
        }

        /** Lets the sender leave the hook and waits until its send has returned; the send's future. */
        Future<Envelope> go() {
            mayGo.countDown();
            join(sender, label + "'s send returned after the hook");
            assertNull(thrown.get(), label + "'s send threw");
            assertTrue(hookThreads.contains(sender.getName()), "the hook ran on the sending thread: " + hookThreads);
            return sent.get();
        }
    }

    /** How the second side sees that the first send arrived before it arms its own hold. */
    enum Seen {
        /** {@code awaitSent(1)} called once the first send is visible: it returns at once. */
        AWAIT_SENT_CALLED_AFTER,
        /** {@code awaitSent(1)} already parked when the first send is made: the send's count wakes it (the CI shape). */
        AWAIT_SENT_ALREADY_WAITING,
        /** {@code sent()} and {@code sentTo(name)} already list it. */
        SENT_LISTS
    }

    // --- the CI shape: hold(); send a; awaitSent; hold(); send c; awaitSent — releasing the first latch answers a

    @ParameterizedTest
    @EnumSource(Seen.class)
    void theFirstLatchReleasesTheFirstSendWhenTheNextHoldIsArmedAsSoonAsTheFirstSendIsSeen(Seen seen) {
        Producer p = http.producer(partner);
        Envelope a = envelope("a");
        Envelope c = envelope("c");
        CountDownLatch aHold = hold(1);

        Thread waiter = null;
        AtomicReference<Throwable> waiterThrew = new AtomicReference<>();
        if (seen == Seen.AWAIT_SENT_ALREADY_WAITING) {
            waiter = Thread.ofVirtual().name("await-sent").start(() -> {
                try {
                    http.awaitSent(1, BOUND);
                } catch (Throwable t) {
                    waiterThrew.set(t);
                }
            });
            threads.add(waiter);
            Thread.State state;   // parked in its bounded latch wait before the send is made
            while ((state = waiter.getState()) != Thread.State.TIMED_WAITING && state != Thread.State.TERMINATED) Thread.onSpinWait();
            assertEquals(Thread.State.TIMED_WAITING, state, "nothing was sent: awaitSent(1) waits");
        }

        Window first = new Window(p, "a", a);
        first.awaitReached();
        switch (seen) {
            case AWAIT_SENT_CALLED_AFTER -> http.awaitSent(1, BOUND);
            case AWAIT_SENT_ALREADY_WAITING -> {
                join(waiter, "awaitSent(1), parked before a's send, returned");
                assertNull(waiterThrew.get(), "awaitSent(1) returned normally");
            }
            case SENT_LISTS -> {
                assertEquals(List.of(a), http.sent(), "a's send is visible in sent()");
                assertEquals(List.of(a), http.sentTo("partner"), "and in sentTo()");
            }
        }

        // the second side, while a's sender still stands in the hook: arm the next hold, send c, see it arrive
        CountDownLatch cHold = onOwnThread("arming-c", () -> hold(1));
        Future<Envelope> cSent = onOwnThread("sender-c", () -> noted("c", p.send(c)));
        onOwnThread("await-c", () -> { http.awaitSent(2, BOUND); return null; });

        Future<Envelope> aSent = first.go();
        assertEquals(List.of(a, c), http.sent(), "recorded in send order");
        assertFalse(aSent.isComplete(), "a is held at the partner");
        assertFalse(cSent.isComplete(), "c is held at the partner");

        aHold.countDown();
        String firstAnswered = nextAnswered("a's latch was counted down");
        assertEquals("a", firstAnswered, "the latch armed before a's send releases a; it released " + firstAnswered
                + ": c's send took a's hold while a's send was visible and had not taken one yet (the holds were swapped)");
        assertSame(a, aSent.result(), "a answered with the identity reply");
        assertFalse(cSent.isComplete(), "c still waits for its own latch");

        cHold.countDown();
        assertEquals("c", nextAnswered("c's latch was counted down"));
        assertSame(c, cSent.result());
    }

    // --- a hold armed after a send was recorded waits for the next send, never reaches back

    @Test
    void aHoldArmedWhileAnUnheldSendIsVisibleHoldsTheNextSendNotThatOne() {
        Producer p = http.producer(partner);
        Envelope a = envelope("a");
        Envelope b = envelope("b");

        Window first = new Window(p, "a", a);   // no hold armed when a is sent
        first.awaitReached();
        assertEquals(List.of(a), http.sent(), "a's send is visible");
        CountDownLatch late = onOwnThread("arming-late", () -> hold(1));

        Future<Envelope> aSent = first.go();
        assertTrue(aSent.isComplete(), "a found no hold when it was recorded: answered when its send returns, the hold armed after it "
                + "does not reach back — a's send took the late hold instead: " + aSent);
        assertSame(a, aSent.result());

        Future<Envelope> bSent = onOwnThread("sender-b", () -> noted("b", p.send(b)));
        assertFalse(bSent.isComplete(), "the late hold holds the next send");
        late.countDown();
        assertEquals("a", nextAnswered("a's answer noted"));
        assertEquals("b", nextAnswered("the late hold was released"));
        assertSame(b, bSent.result());
    }

    @Test
    void aHoldArmedAfterAHeldSendWasRecordedHoldsTheNextSend() {
        Producer p = http.producer(partner);
        CountDownLatch aHold = hold(1);
        Future<Envelope> aSent = noted("a", p.send(envelope("a")));
        http.awaitSent(1, BOUND);
        CountDownLatch bHold = hold(1);
        Future<Envelope> bSent = noted("b", p.send(envelope("b")));
        assertFalse(aSent.isComplete() || bSent.isComplete(), "both held");
        bHold.countDown();
        assertEquals("b", nextAnswered("b's latch was counted down"), "the hold armed after a was recorded is b's");
        assertFalse(aSent.isComplete(), "a still waits for its own latch");
        aHold.countDown();
        assertEquals("a", nextAnswered("a's latch was counted down"));
    }

    // --- holdNext(n > 1) and a send with no hold keep their behaviour

    @Test
    void holdNextOfTwoHoldsTheNextTwoSendsOnOneLatchAndNotTheThird() {
        Producer p = http.producer(partner);
        CountDownLatch both = hold(2);
        Future<Envelope> aSent = noted("a", p.send(envelope("a")));
        Future<Envelope> bSent = noted("b", p.send(envelope("b")));
        Envelope c = envelope("c");
        Future<Envelope> cSent = p.send(c);
        assertTrue(cSent.isComplete(), "holdNext(2) holds two sends: the third is answered at once");
        assertSame(c, cSent.result());
        assertFalse(aSent.isComplete() || bSent.isComplete(), "the first two are held");
        both.countDown();
        List<String> two = new ArrayList<>(List.of(nextAnswered("the shared latch released"), nextAnswered("the shared latch released")));
        two.sort(null);
        assertEquals(List.of("a", "b"), two, "one count-down releases both");
    }

    // --- holds are taken in the order the sends are recorded, under concurrent senders

    /**
     * Many concurrent senders, one latch per send armed in order: releasing latch i answers the i-th recorded send. A
     * probabilistic witness for "record and take as one step" (the staged cases above pin the old order deterministically);
     * against a fixed implementation it is deterministic.
     */
    @Test
    void theIthLatchReleasesTheIthRecordedSendUnderConcurrentSenders() throws InterruptedException {
        int senders = 8;
        int each = 25;
        Producer p = http.producer(partner);
        List<CountDownLatch> inOrder = new ArrayList<>();
        for (int i = 0; i < senders * each; i++) inOrder.add(hold(1));
        Map<Envelope, String> labels = new ConcurrentHashMap<>();
        List<List<Envelope>> batches = new ArrayList<>();
        for (int s = 0; s < senders; s++) {
            List<Envelope> batch = new ArrayList<>();
            for (int k = 0; k < each; k++) {
                String label = s + "-" + k;
                Envelope e = envelope(label);
                labels.put(e, label);
                batch.add(e);
            }
            batches.add(batch);
        }
        CountDownLatch start = gate();
        List<Thread> running = new ArrayList<>();
        for (int s = 0; s < senders; s++) {
            List<Envelope> batch = batches.get(s);
            Thread t = Thread.ofVirtual().name("sender-" + s).start(() -> {
                await(start, "the start", null);
                for (Envelope e : batch) noted(labels.get(e), p.send(e));
            });
            threads.add(t);
            running.add(t);
        }
        start.countDown();
        for (Thread t : running) join(t, t.getName() + " sent its batch");

        List<Envelope> recorded = http.sent();
        assertEquals(senders * each, recorded.size(), "every send recorded");
        assertTrue(answered.isEmpty(), "every send held: " + answered);
        for (int i = 0; i < recorded.size(); i++) {
            inOrder.get(i).countDown();
            String expected = labels.get(recorded.get(i));
            assertEquals(expected, nextAnswered("latch " + i + " was counted down"),
                    "latch " + i + " (armed i-th) releases the i-th recorded send");
        }
    }
}
