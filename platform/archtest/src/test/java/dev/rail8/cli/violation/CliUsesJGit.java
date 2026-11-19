package dev.rail8.cli.violation;

/** Deliberate violation: the CLI may depend on runtime, yet it reads a package repository through runtime's seam, never JGit itself. */
public final class CliUsesJGit {
    private CliUsesJGit() {}
    public static Class<?> client() { return org.eclipse.jgit.api.Git.class; }
}
