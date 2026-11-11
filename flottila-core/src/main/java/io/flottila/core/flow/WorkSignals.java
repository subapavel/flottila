package io.flottila.core.flow;

/**
 * Work events between the nodes of one pool (D-121 (b), P2-16b): tells a node that rows of a flow wait there for a claim it would
 * otherwise make only at its next reconciliation ({@link WorkerPolicy#pollInterval()}). Called only after the commit that made the
 * rows claimable is acknowledged, never inside a transaction. The node told wakes the flow's started worker ({@link Workers#wake});
 * a node never acts on what it sent itself. An event is a hint: a lost one costs one reconciliation, never a message.
 *
 * <p>Two implementations: {@link LocalWorkSignals} (the nodes of one JVM) and the runtime's {@code PgWorkSignals} (Postgres
 * {@code LISTEN/NOTIFY}). Both send the first announce of a window at once and fold the later ones of the window into one send at
 * its end ({@link WorkEvents}).
 *
 * @since 0.1
 */
@FunctionalInterface
public interface WorkSignals {

    /** Signals that tell nobody: a builder without a call to {@link FlowBuilder#signals}, a node of one. */
    WorkSignals NONE = (targetNode, flow) -> { };

    /**
     * Tells {@code targetNode} (null: every node but this one) that rows of {@code flow} (null: of every flow) wait there for a
     * claim. Never blocks and never throws for a send that fails: that send is logged and dropped.
     */
    void announce(String targetNode, String flow);
}
