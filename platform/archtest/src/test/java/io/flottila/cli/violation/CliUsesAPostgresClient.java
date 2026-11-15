package io.flottila.cli.violation;

/** Deliberate violation: cli may depend on runtime (D-079), yet only runtime may touch a store client itself. */
public final class CliUsesAPostgresClient {
    private CliUsesAPostgresClient() {}
    public static Class<?> client() { return io.vertx.pgclient.PgConnectOptions.class; }
}
