package io.flottila.core.flow;

import io.flottila.testkit.FakeRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16b (D-121 (b) B1–B3): what {@link FlowWorkerEventsTest} leaves out of {@link WorkEvents} and {@link LocalWorkSignals} —
 * payloads not of the {@code w1} form, the bound on the prefixes warned about, a window's end with one payload kept, and the close.
 * The sends are observed through the {@code send} a {@link WorkEvents} is given (the carrier's boundary), the wakes through a
 * {@link Workers} registry that holds no worker (a wake of no worker does nothing), the warnings through JUL.
 */
@Timeout(30)
class WorkEventsTest {

    final FakeRuntime rt = FakeRuntime.create();
    final List<List<String>> sent = new CopyOnWriteArrayList<>();
    final WorkEvents events = new WorkEvents("n-a", new Workers(), rt.timer(), sent::add);
    final Logger localLog = Logger.getLogger(LocalWorkSignals.class.getName());
    final List<String> warnings = new CopyOnWriteArrayList<>();
    final Handler handler = new Handler() {
        @Override public void publish(LogRecord r) {
            if (r.getLevel() == Level.WARNING) warnings.add(r.getMessage());
        }
        @Override public void flush() { }
        @Override public void close() { }
    };

    @BeforeEach
    void listen() { localLog.addHandler(handler); }

    @AfterEach
    void unlisten() { localLog.removeHandler(handler); }

    @Test
    void aPayloadNotOfTheW1FormIsWarnedOncePerDistinctPrefix() {
        LocalWorkSignals.Bus bus = new LocalWorkSignals.Bus();
        new LocalWorkSignals("n-a", new Workers(), bus, rt.timer());
        bus.publish(List.of("hello there", "hello again", "w9 n-x n-a orders", "w1 n-x n-a", "w1 n-x n-a orders extra", "lonely", "lonely"));
        assertEquals(4, warnings.size(), "hello, w9, w1 (three fields, then five), lonely: once each: " + warnings);
        for (String prefix : List.of("prefix hello)", "prefix w9)", "prefix w1)", "prefix lonely)")) {
            assertEquals(1, warnings.stream().filter(w -> w.contains(prefix)).count(), prefix + " named once: " + warnings);
        }
        bus.publish(List.of("w1 n-x n-a orders", "w1 n-a * *"));
        assertEquals(4, warnings.size(), "well-formed payloads warn nothing: " + warnings);
    }

    @Test
    void theWarnedPrefixesAreBounded() {
        List<Optional<String>> answers = new ArrayList<>();
        for (int i = 1; i <= WorkEvents.WARNED_CAP + 1; i++) answers.add(events.receive("p" + i + " x"));
        for (int i = 0; i < WorkEvents.WARNED_CAP; i++) assertEquals(Optional.of("p" + (i + 1)), answers.get(i), "the first prefixes are answered");
        assertEquals(Optional.empty(), answers.get(WorkEvents.WARNED_CAP), "one past the bound is not");
        assertEquals(Optional.empty(), events.receive("p1 again"), "nor a prefix answered before");
    }

    @Test
    void aWindowEndingWithOnePayloadKeptSendsItAndOpensTheNextWindow() {
        events.announce("n-b", "orders");
        events.announce("n-c", "orders");
        assertEquals(List.of(List.of("w1 n-a n-b orders")), sent, "the leading edge only");
        rt.clock().advance(WorkEvents.WINDOW);
        assertEquals(List.of(List.of("w1 n-a n-b orders"), List.of("w1 n-a n-c orders")), sent, "the one kept, at the window's end");

        events.announce(null, null);
        assertEquals(2, sent.size(), "the window's end opened the next window: kept, not sent at once");
        rt.clock().advance(WorkEvents.WINDOW);
        assertEquals(List.of("w1 n-a * *"), sent.getLast(), "null target and flow: * *");
        rt.clock().advance(WorkEvents.WINDOW);
        assertEquals(3, sent.size(), "a window with nothing kept sends nothing and closes");
        events.announce("n-d", "invoices");
        assertEquals(List.of("w1 n-a n-d invoices"), sent.getLast(), "a leading edge again");
    }

    @Test
    void aClosedEventsSendsNothingMore() {
        events.announce("n-b", "orders");
        events.announce("n-c", "orders");
        int pending = rt.timer().pending();
        events.close();
        assertEquals(pending - 1, rt.timer().pending(), "the open window's end is cancelled");
        rt.clock().advance(Duration.ofSeconds(1));
        events.announce("n-d", "orders");
        rt.clock().advance(Duration.ofSeconds(1));
        assertEquals(List.of(List.of("w1 n-a n-b orders")), sent, "what the window kept is dropped and a later announce is not sent");
        events.close();
        assertTrue(sent.size() == 1, "a second close has nothing to do");
    }
    /**
     * Review r1 V5: a window's end already firing as the close runs — its task taken by a real timer, so the cancel comes too late —
     * sends nothing and arms no next window. The timer here hands every task to the case, whose cancel answers false (already
     * fired): the case fires the window's end after the close.
     */
    @Test
    void aWindowEndFiringAfterTheCloseSendsNothingAndArmsNoWindow() {
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        io.flottila.api.Timer firing = (delay, task) -> {
            tasks.add(task);
            return () -> false;   // the task is already on its way: too late to cancel
        };
        WorkEvents racing = new WorkEvents("n-a", new Workers(), firing, sent::add);
        racing.announce("n-b", "orders");
        racing.announce("n-c", "orders");   // kept for the window's end
        assertEquals(1, tasks.size(), "fixture: the leading edge opened one window");
        racing.close();
        tasks.getFirst().run();   // the window's end, firing after the close
        assertEquals(List.of(List.of("w1 n-a n-b orders")), sent, "the leading edge only: nothing sent at the end of a closed window");
        assertEquals(1, tasks.size(), "no next window armed after the close");
    }
}
