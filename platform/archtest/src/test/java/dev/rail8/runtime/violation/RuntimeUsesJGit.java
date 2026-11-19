package dev.rail8.runtime.violation;

/** A runtime-package class that names JGit, which runtime may. */
public final class RuntimeUsesJGit {
    private RuntimeUsesJGit() {}
    public static Class<?> client() { return org.eclipse.jgit.lib.Repository.class; }
}
