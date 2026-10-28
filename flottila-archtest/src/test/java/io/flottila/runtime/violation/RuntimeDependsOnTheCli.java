package io.flottila.runtime.violation;

/** Deliberate violation: nothing outside io.flottila.cli depends on it (D-079); the node least of all. */
public final class RuntimeDependsOnTheCli {
    private RuntimeDependsOnTheCli() {}
    public static String cli() { return io.flottila.cli.violation.CliFixture.command(); }
}
