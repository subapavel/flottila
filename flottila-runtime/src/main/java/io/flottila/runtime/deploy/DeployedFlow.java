package io.flottila.runtime.deploy;

import java.util.Objects;
import java.util.Optional;

/**
 * A flow that runs on this node (D-059, D-060 a): its qualified id {@code <package>/<flow>} — the id of its snapshots, DLQ rows and
 * queue items — and what it was deployed from.
 *
 * @param id the qualified id, {@code <package>/<flow>}
 * @param pkg the package's name
 * @param packageVersion the package's version
 * @param revision the deploy's revision, empty without one
 * @param flowVersion the flow's own version
 * @param delivery the delivery mode: {@code sync}, {@code at-least-once} or {@code at-most-once}
 * @since 0.1
 */
public record DeployedFlow(String id, String pkg, String packageVersion, Optional<String> revision, String flowVersion, String delivery) {
    public DeployedFlow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(revision, "revision");
    }
}
