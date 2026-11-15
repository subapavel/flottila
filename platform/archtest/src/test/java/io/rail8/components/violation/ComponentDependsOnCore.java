package io.rail8.components.violation;

/** Deliberate violation: a component depends on core (components depend on api only). */
public final class ComponentDependsOnCore {
    private ComponentDependsOnCore() {}
    public static Object core() { return new io.rail8.core.violation.CoreFixture(); }
}
