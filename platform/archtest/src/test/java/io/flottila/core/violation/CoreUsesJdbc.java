package io.flottila.core.violation;

/** Deliberate violation: JDBC is a JDK-only route to Postgres; api and core may not use java.sql. */
public final class CoreUsesJdbc {
    private CoreUsesJdbc() {}
    public static Class<?> jdbc() { return java.sql.DriverManager.class; }
}
