package dev.rail8.ingress.violation;

/** Deliberate violation: the shared HTTP port depends on runtime (it depends on api only). */
public final class IngressDependsOnRuntime {
    private IngressDependsOnRuntime() {}
    public static Object runtime() { return new dev.rail8.runtime.violation.RuntimeFixture(); }
}
