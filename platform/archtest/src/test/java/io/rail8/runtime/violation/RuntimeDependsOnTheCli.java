package io.rail8.runtime.violation;

/** Deliberate violation: nothing outside io.rail8.cli depends on it (D-079); the node least of all. */
public final class RuntimeDependsOnTheCli {
    private RuntimeDependsOnTheCli() {}
    public static String cli() { return io.rail8.cli.violation.CliFixture.command(); }
}
