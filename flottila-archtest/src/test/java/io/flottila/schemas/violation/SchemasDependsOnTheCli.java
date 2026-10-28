package io.flottila.schemas.violation;

/**
 * Deliberate violation in a module no other rule names (schemas): NOTHING_DEPENDS_ON_CLI binds every class outside
 * io.flottila.cli, not a list of modules (D-079).
 */
public final class SchemasDependsOnTheCli {
    private SchemasDependsOnTheCli() {}
    public static String cli() { return io.flottila.cli.violation.CliFixture.command(); }
}
