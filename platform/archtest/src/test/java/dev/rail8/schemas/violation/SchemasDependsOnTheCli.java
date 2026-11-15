package dev.rail8.schemas.violation;

/**
 * Deliberate violation in a module no other rule names (schemas): NOTHING_DEPENDS_ON_CLI binds every class outside
 * dev.rail8.cli, not a list of modules (D-079).
 */
public final class SchemasDependsOnTheCli {
    private SchemasDependsOnTheCli() {}
    public static String cli() { return dev.rail8.cli.violation.CliFixture.command(); }
}
