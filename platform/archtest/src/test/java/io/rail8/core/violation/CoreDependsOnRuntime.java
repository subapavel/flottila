package io.rail8.core.violation;

/** Deliberate violation: core depends on runtime. */
public final class CoreDependsOnRuntime {
    private CoreDependsOnRuntime() {}
    public static Object runtime() { return new io.rail8.runtime.violation.RuntimeFixture(); }
}
