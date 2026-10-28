package io.flottila.cli.violation;

/**
 * Allowed since F-16 / D-079: cli is the outermost module and may depend on every module (here runtime) and on itself; once a
 * deliberate violation of the retired CLI_DEPENDS_ON_API_AND_TESTKIT.
 */
public final class CliDependsOnRuntime {
    private CliDependsOnRuntime() {}
    public static Object runtime() { return new io.flottila.runtime.violation.RuntimeFixture(); }
    public static Object itself() { return CliFixture.command(); }
}
