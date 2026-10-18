package io.flottila.runtime.violation;

/** Plain runtime-package class other fixtures point at; runtime may use the Postgres client. */
public final class RuntimeFixture {
    public static Class<?> client() { return io.vertx.pgclient.PgConnectOptions.class; }
}
