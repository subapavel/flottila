package io.flottila.testkit.violation;

/** Deliberate violation: testkit depends on runtime (testkit depends on core). */
public final class TestkitDependsOnRuntime {
    private TestkitDependsOnRuntime() {}
    public static Object runtime() { return new io.flottila.runtime.violation.RuntimeFixture(); }
}
