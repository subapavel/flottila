package io.flottila.core.flow;

import io.flottila.api.Timer;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What every {@link WorkSignals} of a node does with its events (D-121 (b) B1–B3), whatever carries them: the payloads it sends
 * and how they are paced, and what a payload received wakes. A payload is {@code w1 <sender> <target|*> <flow|*>}, one blank
 * between the fields ({@code *}: every node but the sender, every flow).
 *
 * <p>Sending (B3): the first announce of a {@value #WINDOW_MILLIS} ms window is sent at once (the leading edge) and opens the
 * window on the timer; the announces later in the window are kept, each distinct payload once, and sent together as one send at
 * its end — more than {@value #FOLD_CAP} of them as the one payload {@code w1 <sender> * *} — which opens the next window; a window
 * that ends with nothing kept closes, and the next announce is a leading edge again. So at most one send leaves per window after
 * the first. What a send does with a failure is the sender's: it is never sent again.
 *
 * <p>Receiving (B1): a payload of this node's own sender wakes nothing (Postgres delivers a node's NOTIFY to its own LISTEN), nor
 * one for another node; one for this node or {@code *} wakes the flow's started worker ({@link Workers#wake}), flow {@code *} every
 * started worker ({@link Workers#wakeAll}). A payload not of the {@code w1} form wakes nothing; {@link #receive} answers its prefix
 * — the text before its first blank — the first time that prefix is seen, for one WARNING per distinct prefix, at most
 * {@value #WARNED_CAP} prefixes in all (later ones are not answered).
 *
 * @since 0.1
 */
public final class WorkEvents {

    /** The first field of every payload: its form. */
    public static final String FORM = "w1";
    /** Every node but the sender, every flow. */
    public static final String ALL = "*";
    /** The window's length in milliseconds (B7: a constant, no env key until P2-16c). */
    public static final long WINDOW_MILLIS = 50;
    /** The window a leading edge opens. */
    public static final Duration WINDOW = Duration.ofMillis(WINDOW_MILLIS);
    /** The most distinct payloads one window's end sends as they are; more fold to {@code w1 <sender> * *}. */
    public static final int FOLD_CAP = 64;
    /** The most distinct prefixes {@link #receive} answers for a warning. */
    public static final int WARNED_CAP = 64;

    private final String node;
    private final Workers workers;
    private final Timer timer;
    private final Consumer<List<String>> send;

    private final Object lock = new Object();
    private final Set<String> kept = new LinkedHashSet<>();   // guarded by lock: the window's later payloads, each once
    private final Set<String> warned = new HashSet<>();       // guarded by lock: the prefixes answered for a warning
    private Timer.Cancellable window;                         // guarded by lock: the open window's end, null: none open
    private boolean closed;                                   // guarded by lock

    /**
     * The events of node {@code node}: what it receives wakes {@code workers}; what it announces goes to {@code send} — one call per
     * send, its payloads in announce order — paced by windows on {@code timer}.
     */
    public WorkEvents(String node, Workers workers, Timer timer, Consumer<List<String>> send) {
        this.node = Objects.requireNonNull(node, "node");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.send = Objects.requireNonNull(send, "send");
    }

    /** The payload {@code sender} sends for {@code targetNode} (null: {@code *}) and {@code flow} (null: {@code *}). */
    public static String payload(String sender, String targetNode, String flow) {
        return FORM + " " + sender + " " + (targetNode == null ? ALL : targetNode) + " " + (flow == null ? ALL : flow);
    }

    /** {@link WorkSignals#announce}: sent at once as a leading edge, else kept for the open window's end; nothing after {@link #close}. */
    public void announce(String targetNode, String flow) {
        String payload = payload(node, targetNode, flow);
        synchronized (lock) {
            if (closed) return;
            if (window != null) {
                kept.add(payload);
                return;
            }
            window = timer.schedule(WINDOW, this::windowEnded);
        }
        send.accept(List.of(payload));
    }

    /** The open window ended: what it kept is one send and opens the next window; nothing kept, it closes. */
    private void windowEnded() {
        List<String> batch;
        synchronized (lock) {
            if (kept.isEmpty()) {
                window = null;
                return;
            }
            batch = kept.size() > FOLD_CAP ? List.of(payload(node, null, null)) : List.copyOf(kept);
            kept.clear();
            window = timer.schedule(WINDOW, this::windowEnded);
        }
        send.accept(batch);
    }

    /** Sends nothing from now on: what the open window kept is dropped and its end cancelled. */
    public void close() {
        synchronized (lock) {
            closed = true;
            kept.clear();
            if (window != null) window.cancel();
            window = null;
        }
    }

    /**
     * Wakes what {@code payload} names here, if anything; answers the payload's prefix when it is not of the {@code w1} form and its
     * prefix was not answered before (at most {@value #WARNED_CAP} in all), else empty.
     */
    public Optional<String> receive(String payload) {
        String[] f = payload.split(" ", -1);
        if (f.length != 4 || !FORM.equals(f[0])) return unknown(payload);
        if (f[1].equals(node) || !(f[2].equals(ALL) || f[2].equals(node))) return Optional.empty();   // its own, or another node's
        if (f[3].equals(ALL)) workers.wakeAll();
        else workers.wake(f[3]);
        return Optional.empty();
    }

    private Optional<String> unknown(String payload) {
        int blank = payload.indexOf(' ');
        String prefix = blank < 0 ? payload : payload.substring(0, blank);
        synchronized (lock) {
            if (warned.size() >= WARNED_CAP || !warned.add(prefix)) return Optional.empty();
        }
        return Optional.of(prefix);
    }
}
