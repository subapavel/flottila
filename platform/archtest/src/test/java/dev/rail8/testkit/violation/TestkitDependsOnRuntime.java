package dev.rail8.testkit.violation;

/** Deliberate violation: testkit depends on runtime (testkit depends on core). */
public final class TestkitDependsOnRuntime {
    private TestkitDependsOnRuntime() {}
    public static Object runtime() { return new dev.rail8.runtime.violation.RuntimeFixture(); }
}
