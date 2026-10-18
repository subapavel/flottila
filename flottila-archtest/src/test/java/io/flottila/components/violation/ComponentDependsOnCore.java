package io.flottila.components.violation;

/** Deliberate violation: a component depends on core (components depend on api only). */
public final class ComponentDependsOnCore {
    private ComponentDependsOnCore() {}
    public static Object core() { return new io.flottila.core.violation.CoreFixture(); }
}
