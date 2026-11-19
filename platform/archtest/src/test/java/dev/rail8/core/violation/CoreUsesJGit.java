package dev.rail8.core.violation;

/** Deliberate violation: only runtime reads the package repositories, so only runtime may name JGit. */
public final class CoreUsesJGit {
    private CoreUsesJGit() {}
    public static Class<?> client() { return org.eclipse.jgit.lib.Repository.class; }
}
