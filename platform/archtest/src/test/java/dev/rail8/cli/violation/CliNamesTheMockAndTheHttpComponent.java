package dev.rail8.cli.violation;

/** Allowed: the CLI names the mock it tests with and the http component, whose answer to a delivery it compares. */
public final class CliNamesTheMockAndTheHttpComponent {
    private CliNamesTheMockAndTheHttpComponent() {}
    public static Class<?> mock() { return dev.rail8.components.mock.MockComponent.class; }
    public static Class<?> http() { return dev.rail8.components.http.HttpComponent.class; }
}
