package io.flottila.api.violation;

/** Deliberate violation: api depends on core. */
public final class ApiDependsOnCore {
    private ApiDependsOnCore() {}
    public static Object core() { return new io.flottila.core.violation.CoreFixture(); }
}
