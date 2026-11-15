package io.rail8.api.violation;

/** Deliberate violation: api depends on core. */
public final class ApiDependsOnCore {
    private ApiDependsOnCore() {}
    public static Object core() { return new io.rail8.core.violation.CoreFixture(); }
}
