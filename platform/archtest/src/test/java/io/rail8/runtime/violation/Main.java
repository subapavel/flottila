package io.rail8.runtime.violation;

/** Deliberate violation: named Main, but not io.rail8.runtime.Main — the exemption is by full name, never the simple one. */
public final class Main {
    private Main() {}
    public static Class<?> component() { return io.rail8.components.http.HttpComponent.class; }
}
