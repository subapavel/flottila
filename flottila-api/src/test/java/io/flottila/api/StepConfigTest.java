package io.flottila.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The step SPI's value types: a step's config as a factory reads it, and what a factory may hold on to. */
class StepConfigTest {

    final StepConfig step = new StepConfig(2, "xslt", "toPartner", true, Map.of("stylesheet", "a.xsl", "n", 3, "empty", ""));

    @Test
    void readsStringsAndNamesTheStepAndTheKeyWhenOneIsMissingOrNotAString() {
        assertEquals("a.xsl", step.requireString("stylesheet"));
        assertEquals(Optional.of("a.xsl"), step.string("stylesheet"));
        assertEquals(Optional.empty(), step.string("n"), "a number is not a string");
        assertEquals(Optional.empty(), step.string("empty"), "an empty string is as good as none");
        assertEquals(Optional.empty(), step.string("missing"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> step.requireString("n"));
        assertTrue(e.getMessage().contains("steps[1]") && e.getMessage().contains("toPartner") && e.getMessage().contains("'n'"), e.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> step.requireString("missing")).getMessage().contains("'missing'"));
        assertEquals("step 'toPartner' (steps[1], xslt)", step.toString());
        assertTrue(step.retryable());
        assertFalse(new StepConfig(1, "t", "n", false, Map.of()).retryable());
    }

    @Test
    void theIndexStartsAtOneAndTheConfigIsACopy() {
        assertThrows(IllegalArgumentException.class, () -> new StepConfig(0, "t", "n", false, Map.of()), "ingress is 0; the first configured step is 1");
        assertThrows(NullPointerException.class, () -> new StepConfig(1, null, "n", false, Map.of()));
        assertThrows(NullPointerException.class, () -> new StepConfig(1, "t", null, false, Map.of()));
        var m = new java.util.HashMap<String, Object>(Map.of("k", "v"));
        StepConfig s = new StepConfig(1, "t", "n", false, m);
        m.put("k", "changed");
        assertEquals("v", s.config().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> s.config().put("x", "y"));
    }

    @Test
    void resourcesResolveRelativeFilesAgainstTheBaseDirAndRefuseANonPositiveLimit() {
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        Timer timer = (delay, task) -> () -> false;
        IdGenerator ids = () -> "id";
        BlobStore store = new InMemoryBlobStore(1024);
        StepResources r = new StepResources(clock, timer, ids, store, Hasher.sha256(), 1024, Path.of("packages/demo/flows"));
        assertEquals(Path.of("packages/demo/flows/invoice-to-partner.xsl"), r.file("invoice-to-partner.xsl"));
        assertEquals(Path.of("packages/demo/common.xsl"), r.file("../common.xsl"), "normalized");
        assertEquals(Path.of("/abs/x.xsl"), r.file("/abs/x.xsl"), "an absolute path stays");
        assertEquals(1024L, r.maxBodyBytes());
        assertThrows(IllegalArgumentException.class, () -> new StepResources(clock, timer, ids, store, Hasher.sha256(), 0, Path.of("")));
        assertThrows(NullPointerException.class, () -> new StepResources(clock, timer, ids, store, Hasher.sha256(), 1, null));
        assertThrows(NullPointerException.class, () -> r.file(null));
    }
}
