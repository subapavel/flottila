package dev.rail8.runtime.git;

import java.net.URI;

/**
 * The package repositories of the node pool, read through the node's cache. Every repository lives under one {@link #base()} and is
 * named by one path segment under it; the package lies at {@link #path()} in each. Blocking: called on a virtual thread.
 *
 * <p>An instance serves one deploy, or one package's restore: the fetches it makes take at most {@code RAIL8_GIT_TIMEOUT} together,
 * on the node's injected timer. A bad repository name, a commit that is neither 40 hex digits nor (in {@code dev} and {@code ci}) a
 * ref is refused with an {@link IllegalArgumentException} before anything is fetched; a failure of the node's own disk is an
 * {@link java.io.UncheckedIOException}, except while a fetch writes what it receives; every failure of a fetch, and of git, is a
 * {@link GitFailure}.
 *
 * @since 0.1
 */
public interface PackageRepositories {

    /** The base the repositories live under, without credentials, ending in {@code /}. */
    URI base();

    /** The package's folder in each repository, relative and without {@code ..}; empty for the repository's root. */
    String path();

    /**
     * Fetches and answers a commit a deploy names, for the package or a pin: a 40-hex id, or in dev and ci a ref ({@code HEAD}, a
     * branch or tag name, or a full name under {@code refs/heads/} or {@code refs/tags/}), resolved in the repository at the fetch;
     * proved reachable from the pool's ref when one is set.
     *
     * @param repository the repository's name under the base
     * @param commitOrRef the commit's id, or a ref
     * @return the commit, kept in the cache
     * @throws GitFailure when the commit cannot be fetched, is not reachable from the pool's ref, or its history is incomplete
     */
    Commit forDeploy(String repository, String commitOrRef) throws GitFailure;

    /**
     * Answers a commit a record or a pin names, from the cache, fetching only when the cache lacks it; no reachability check.
     *
     * @param repository the repository's name under the base
     * @param commit the commit's 40-hex id
     * @return the commit, kept in the cache
     * @throws GitFailure when the cache lacks the commit and it cannot be fetched, or its history is incomplete
     */
    Commit recorded(String repository, String commit) throws GitFailure;
}
