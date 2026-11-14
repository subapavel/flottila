package io.flottila.core.flow;

import io.flottila.api.Timer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link WorkSignals} of nodes in one JVM (D-121 (b) B1, tests and single-JVM multi-node): every node joins one {@link Bus};
 * a send is one {@link Bus#publish} — what a {@code NOTIFY} is to every {@code LISTEN}ing node, the sender's own included — and
 * each joined node wakes its own {@link Workers} as {@link WorkEvents} says. Announces are paced by {@link WorkEvents}' windows on
 * the timer given. A payload not of the {@code w1} form is logged once per distinct prefix at WARNING.
 *
 * @since 0.1
 */
public final class LocalWorkSignals implements WorkSignals {

    private static final System.Logger LOG = System.getLogger(LocalWorkSignals.class.getName());

    /**
     * What carries the sends between the nodes of one JVM: every joined node receives every send.
     *
     * @since 0.1
     */
    public static final class Bus {

        private final List<LocalWorkSignals> joined = new CopyOnWriteArrayList<>();

        /** A bus no node joined yet. */
        public Bus() {
        }

        /** One send: each of {@code payloads}, in order, handed to every joined node — the sender included — before this returns. */
        public void publish(List<String> payloads) {
            for (String payload : payloads) for (LocalWorkSignals node : joined) node.received(payload);
        }
    }

    private final WorkEvents events;

    /** Node {@code node}'s signals, joined to {@code bus}: what it receives wakes {@code workers}; its windows run on {@code timer}. */
    public LocalWorkSignals(String node, Workers workers, Bus bus, Timer timer) {
        this.events = new WorkEvents(node, workers, timer, Objects.requireNonNull(bus, "bus")::publish);
        bus.joined.add(this);
    }

    @Override public void announce(String targetNode, String flow) {
        events.announce(targetNode, flow);
    }

    private void received(String payload) {
        events.receive(payload).ifPresent(prefix -> LOG.log(System.Logger.Level.WARNING,
                "a work event of an unknown form was ignored (prefix " + prefix + "): " + payload));
    }
}
