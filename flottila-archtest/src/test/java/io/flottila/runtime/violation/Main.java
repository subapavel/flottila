package io.flottila.runtime.violation;

/** Deliberate violation: named Main, but not io.flottila.runtime.Main — the exemption is by full name, never the simple one. */
public final class Main {
    private Main() {}
    public static Class<?> component() { return io.flottila.components.http.HttpComponent.class; }
}
