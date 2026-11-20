package dev.rail8.runtime.queue;

import java.util.List;

/**
 * A write of messages refused because the pool no longer runs their flow at-least-once: no record runs it so, no mark retires it,
 * and this node does not run it ahead of a record. Nothing of the write is kept; a sender may try again, and finds the flow gone.
 *
 * @since 0.1
 */
public final class NotAdmittedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The qualified ids of the flows refused, sorted. */
    private final transient List<String> flows;

    public NotAdmittedException(List<String> flows) {
        super("the pool no longer runs " + (flows.size() == 1 ? "flow " + flows.getFirst() : "flows " + flows) + " at-least-once: no message for it is kept");
        this.flows = List.copyOf(flows);
    }

    /** The qualified ids of the flows refused, sorted. */
    public List<String> flows() {
        return flows;
    }
}
