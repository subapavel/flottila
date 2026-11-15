package io.flottila.runtime.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

import java.util.Objects;

/**
 * Applies the schema migrations in {@code deploy/postgres} (packaged as {@code db/migration} on the
 * runtime classpath) with Flyway at runtime start (D-034). Flyway speaks JDBC, so this is the one
 * place the runtime uses the JDBC driver; every other access goes through the Vert.x client.
 *
 * @since 0.1
 */
public final class Migrations {

    /**
     * Outcome of a migration run.
     *
     * @param migrationsExecuted how many versions were applied by this run (0 = already current)
     * @param schemaVersion the schema version after the run
     * @since 0.1
     */
    public record Result(int migrationsExecuted, String schemaVersion) {}

    /** Classpath location of the packaged migrations. */
    public static final String LOCATION = "classpath:db/migration";

    private Migrations() {}

    /** Migrates the database at {@code jdbcUrl} to the latest packaged version; blocks the calling thread. */
    public static Result migrate(String jdbcUrl, String user, String password) {
        return migrate(jdbcUrl, user, password, LOCATION);
    }

    /** As {@link #migrate(String, String, String)} with an explicit Flyway location (tests). */
    public static Result migrate(String jdbcUrl, String user, String password, String location) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations(location)
                .validateMigrationNaming(true)
                .load();
        MigrateResult r = flyway.migrate();
        MigrationInfo current = flyway.info().current();
        if (current == null) throw new IllegalStateException("no migrations found under " + location + " — the runtime jar is incomplete");
        return new Result(r.migrationsExecuted, current.getVersion().getVersion());
    }
}
