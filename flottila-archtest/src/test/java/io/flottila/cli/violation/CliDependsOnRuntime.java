package io.flottila.cli.violation;

/** Deliberate violation: cli depends on runtime (cli depends on api and testkit). */
public final class CliDependsOnRuntime {
    private CliDependsOnRuntime() {}
    public static Object runtime() { return new io.flottila.runtime.violation.RuntimeFixture(); }
}
