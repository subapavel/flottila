package io.flottila.core.violation;

/** Deliberate violation: core depends on runtime. */
public final class CoreDependsOnRuntime {
    private CoreDependsOnRuntime() {}
    public static Object runtime() { return new io.flottila.runtime.violation.RuntimeFixture(); }
}
