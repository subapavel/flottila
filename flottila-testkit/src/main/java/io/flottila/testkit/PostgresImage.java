package io.flottila.testkit;

/**
 * The one source of the Postgres image every Testcontainers test runs on (D-129). {@link #NAME} is the pinned default, the
 * newest 18 patch tag (pulled, 2026-11-11); the compose stacks run the same tag. The system property {@value #PROPERTY}
 * names another full image for one run (the CI run on the supported floor, 17).
 *
 * @since 0.1
 */
public final class PostgresImage {

    /** The pinned default image. */
    public static final String NAME = "postgres:18.6";

    /** The system property that overrides {@link #NAME} for one run. */
    public static final String PROPERTY = "flottila.it.postgres";

    private PostgresImage() {
    }

    /**
     * The image of this run: the property when it is set, {@link #NAME} otherwise. Read at every call.
     *
     * @throws IllegalStateException if the property is set but blank
     */
    public static String name() {
        String override = System.getProperty(PROPERTY);
        if (override == null) return NAME;
        if (override.isBlank()) {
            throw new IllegalStateException("the system property " + PROPERTY + " is blank: name a full image or leave it unset");
        }
        return override.strip();
    }
}
