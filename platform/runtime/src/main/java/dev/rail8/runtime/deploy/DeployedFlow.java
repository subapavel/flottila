package dev.rail8.runtime.deploy;

import java.util.Objects;
import java.util.Optional;

/**
 * A flow that runs on this node: its qualified id {@code <package>/<flow>} — the id of its snapshots, DLQ rows and
 * queue items — and what it was deployed from.
 *
 * @param id the qualified id, {@code <package>/<flow>}
 * @param pkg the package's name
 * @param packageVersion the package's version
 * @param commit the commit its package was deployed from when the flow was built: a flow a redeploy kept, its files unchanged,
 *               keeps the commit it was built from; empty for a deploy with no origin
 * @param flowVersion the flow's own version
 * @param delivery the delivery mode: {@code sync}, {@code at-least-once} or {@code at-most-once}
 * @param hash the flow's effective hash, {@code sha256:} and 64 lower-case hex digits: its yaml, the package files its
 *             build read and its endpoints' config in this environment
 * @param state whether the flow runs
 * @since 0.1
 */
public record DeployedFlow(String id, String pkg, String packageVersion, Optional<String> commit, String flowVersion, String delivery,
                           String hash, State state) {

    /**
     * {@link #RUNNING}: started and serving; {@link #RESTARTING}: started, but its consumer takes no message — it did not start
     * or stopped working, and the node starts a fresh one after a delay; never stored, read live from the consumer;
     * {@link #FAILED}: a redeploy could not build it or start its worker again, or the restore at the node's start could not
     * start its worker.
     */
    public enum State { RUNNING, RESTARTING, FAILED }

    public DeployedFlow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(state, "state");
    }
}
