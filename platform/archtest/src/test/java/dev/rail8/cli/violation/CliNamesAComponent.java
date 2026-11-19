package dev.rail8.cli.violation;

/** Deliberate violation: a cli class names a class of the http module other than the http component itself. */
public final class CliNamesAComponent {
    private CliNamesAComponent() {}
    public static Class<?> module() { return dev.rail8.components.http.HttpPlugin.class; }
}
