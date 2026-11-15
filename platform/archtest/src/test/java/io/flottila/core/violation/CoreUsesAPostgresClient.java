package io.flottila.core.violation;

/** Deliberate violation: only runtime may touch the Postgres client. */
public final class CoreUsesAPostgresClient {
    private CoreUsesAPostgresClient() {}
    public static Class<?> client() { return io.vertx.pgclient.PgConnectOptions.class; }
}
