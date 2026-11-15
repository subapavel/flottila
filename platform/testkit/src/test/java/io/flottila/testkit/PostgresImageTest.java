package io.flottila.testkit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-169 (D-129): the one source of the Postgres image every Testcontainers test runs on. The pinned default is a
 * patch tag of Postgres 18; the system property {@code flottila.it.postgres} names another full image for one run (the
 * CI floor run on 17). The property may be set by the build that runs this class, so every case saves and restores it.
 */
class PostgresImageTest {

    private static final String PROPERTY = "flottila.it.postgres"; // the spec's name, not the class's constant

    private String saved;

    @BeforeEach
    void saveProperty() {
        saved = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (saved == null) System.clearProperty(PROPERTY);
        else System.setProperty(PROPERTY, saved);
    }

    @Test
    void thePinnedDefaultIsAPatchTagOfPostgres18() {
        String name = PostgresImage.NAME;
        int colon = name.lastIndexOf(':');
        assertTrue(colon > 0, "the default names a tag: " + name);
        assertEquals("postgres", name.substring(0, colon), "the default is the official postgres image: " + name);
        assertTrue(name.substring(colon + 1).matches("18\\.[0-9]+"),
                "the default is pinned to a postgres 18 patch tag (never a bare major, never latest), was " + name);
    }

    @Test
    void withoutThePropertyTheTestsRunOnThePinnedDefault() {
        assertEquals(PostgresImage.NAME, PostgresImage.name());
    }

    @Test
    void thePropertyNamesTheImageOfTheRun() {
        System.setProperty(PROPERTY, "registry.example.org/library/pg:17.11");
        assertEquals("registry.example.org/library/pg:17.11", PostgresImage.name());
    }

    @Test
    void thePropertyIsReadAtEachCallNotOnceAtClassLoad() {
        System.setProperty(PROPERTY, "registry.example.org/library/pg:17.11");
        assertEquals("registry.example.org/library/pg:17.11", PostgresImage.name());
        System.clearProperty(PROPERTY);
        assertEquals(PostgresImage.NAME, PostgresImage.name(), "a cleared property falls back to the pinned default");
    }

    @Test
    void aBlankPropertyIsRefusedNotSilentlyReplacedByTheDefault() {
        System.setProperty(PROPERTY, "  ");
        IllegalStateException e = assertThrows(IllegalStateException.class, PostgresImage::name);
        assertTrue(e.getMessage().contains(PROPERTY), "the refusal names the property: " + e.getMessage());
    }
}
