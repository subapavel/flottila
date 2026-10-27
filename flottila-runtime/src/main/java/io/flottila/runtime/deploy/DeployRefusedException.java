package io.flottila.runtime.deploy;

import java.util.List;
import java.util.Objects;

/**
 * Why {@link Deployments#deploy} refused a package (D-059): its {@link Kind} — the admin API answers each with its own status —
 * the error line, and for {@link Kind#INVALID} every problem found, one line each, none with an absolute path; for a redeploy that
 * did not start and rolled back ({@link Kind#FAILED}, D-063) every flow that did not start, build or start again.
 *
 * @since 0.1
 */
public final class DeployRefusedException extends Exception {

    /** What kind of refusal; the admin API's status in brackets. */
    public enum Kind {
        /** A bad name or revision, or an archive {@code PackageArchive} refuses (400). */
        BAD_REQUEST,
        /** The package does not pass validation: every problem in {@link #problems()} (422). */
        INVALID,
        /** Another deploy runs on this node: one at a time per node, whatever the package (409). */
        CONFLICT,
        /** The node is closing (503). */
        STOPPING,
        /**
         * A flow did not start (500): every flow the deploy built was stopped again; on a redeploy the flows it replaced or removed
         * run again from the directories they ran from, or are listed FAILED (D-063).
         */
        FAILED,
        /** A deployed environment: manual deploy is non-production only (403). */
        FORBIDDEN
    }

    private final Kind kind;
    private final String error;
    private final List<String> problems;

    /** No stack trace is kept; suppression is on: a cleanup that fails after the refusal is added to it, never in its place (FIX-32). */
    public DeployRefusedException(Kind kind, String error, List<String> problems) {
        super(kind + ": " + error + (problems.isEmpty() ? "" : " " + problems), null, true, false);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.error = Objects.requireNonNull(error, "error");
        this.problems = List.copyOf(problems);
    }

    public Kind kind() { return kind; }

    /** The error line. */
    public String error() { return error; }

    /** Every problem of an {@link Kind#INVALID} package, or of a {@link Kind#FAILED} redeploy, one line each; otherwise empty. */
    public List<String> problems() { return problems; }
}
