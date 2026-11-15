package dev.rail8.api.violation;

/** Deliberate violation: api depends on core. */
public final class ApiDependsOnCore {
    private ApiDependsOnCore() {}
    public static Object core() { return new dev.rail8.core.violation.CoreFixture(); }
}
