package io.flottila.cli.violation;

/** Plain cli-package class the NOTHING_DEPENDS_ON_CLI violations point at (D-079). */
public final class CliFixture {
    private CliFixture() {}
    public static String command() { return "validate"; }
}
