package io.flottila.runtime.deploy;

import java.util.List;
import java.util.Objects;

/**
 * Why {@link Deployments#deploy} refused a package (D-059): its {@link Kind} — the admin API answers each with its own status —
 * the error line, and for {@link Kind#INVALID} every problem found, one line each, none with an absolute path.
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
        /** Another deploy runs, or the package is deployed already (409). */
        CONFLICT,
        /** The node is closing (503). */
        STOPPING,
        /** A flow did not start; every flow of the package was stopped again (500). */
        FAILED,
        /** A deployed environment: manual deploy is non-production only (403). */
        FORBIDDEN
    }

    private final Kind kind;
    private final String error;
    private final List<String> problems;

    public DeployRefusedException(Kind kind, String error, List<String> problems) {
        super(kind + ": " + error + (problems.isEmpty() ? "" : " " + problems), null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.error = Objects.requireNonNull(error, "error");
        this.problems = List.copyOf(problems);
    }

    public Kind kind() { return kind; }

    /** The error line. */
    public String error() { return error; }

    /** Every problem of an {@link Kind#INVALID} package, one line each; empty for every other kind. */
    public List<String> problems() { return problems; }
}
