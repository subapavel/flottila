package dev.rail8.runtime.git;

import java.util.Objects;

/**
 * A git failure, as its one formatter says it: the repository by its name, the commit or ref, and one {@link Reason} of a fixed list,
 * such as {@code orders at 3f2a…: not reachable from the pool's ref}. It never carries the transport's own message, a cause, a
 * suppressed exception or a stack trace, so an answer, a log line, an audit row or the listing of a failed package that shows it
 * shows nothing else of the transport, its URIs or its credentials.
 *
 * @since 0.1
 */
public final class GitFailure extends Exception {

    /** Why git failed: the formatter's fixed list. */
    public enum Reason {
        /** The repository, the commit or the ref is not there. */
        NOT_FOUND("not found"),
        /** The commit is not reachable from the pool's ref ({@code RAIL8_GIT_REF}). */
        NOT_REACHABLE("not reachable from the pool's ref"),
        /** The commit's history is not whole: the source, or the cache, lacks part of it. */
        HISTORY_INCOMPLETE("history incomplete"),
        /** The git server refused the credentials, or asked for some and was given none. */
        CREDENTIALS_REFUSED("credentials refused"),
        /** The fetches took longer than {@code RAIL8_GIT_TIMEOUT}. */
        TIMED_OUT("timed out (RAIL8_GIT_TIMEOUT)"),
        /** The fetch would pass {@code RAIL8_GIT_MAX_BYTES}, after the cached repositories nothing names were deleted. */
        CACHE_FULL("the git cache is full (RAIL8_GIT_MAX_BYTES)"),
        /** Anything else: the server cannot be reached, a refused redirect, a broken answer. */
        TRANSPORT_FAILED("the transport failed");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        /** The reason as the formatter writes it. */
        @Override
        public String toString() {
            return text;
        }
    }

    private final Reason reason;
    private final String repository;
    private final String target;

    /**
     * @param reason why git failed
     * @param repository the repository's name, one the name rule accepted
     * @param target the commit's id or the ref, one the commit rule accepted
     */
    public GitFailure(Reason reason, String repository, String target) {
        super(repository + " at " + target + ": " + reason, null, false, false);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.target = Objects.requireNonNull(target, "target");
    }

    public Reason reason() { return reason; }

    /** The repository's name. */
    public String repository() { return repository; }

    /** The commit's id or the ref the failure is about. */
    public String target() { return target; }
}
