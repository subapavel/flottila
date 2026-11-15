package io.rail8.testkit.violation;

/** Deliberate violation: testkit depends on runtime (testkit depends on core). */
public final class TestkitDependsOnRuntime {
    private TestkitDependsOnRuntime() {}
    public static Object runtime() { return new io.rail8.runtime.violation.RuntimeFixture(); }
}
