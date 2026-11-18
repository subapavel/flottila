package dev.rail8.testkit;

import dev.rail8.api.Cache;
import dev.rail8.api.Secret;
import dev.rail8.api.StepConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakeServices}: its cache passes {@link CacheContract} on the fake clock, and its {@code secret} hands out the secrets the
 * test set and refuses what a node refuses, naming the step and the key and never showing an option that is not a reference.
 */
class FakeServicesTest {

    static StepConfig step(Map<String, Object> config) {
        return new StepConfig(1, "hmac-sign", "sign", false, config);
    }

    @Nested
    class TheCache extends CacheContract {
        final FakeClock clock = FakeClock.at(FakeRuntime.T0);

        @Override protected Cache cache() { return FakeServices.create(clock).cache(); }

        @Override protected void advance(Duration by) { clock.advance(by); }
    }

    @Test
    void aSecretTheTestSetIsHandedOutAndARotatedValueIsSeen() {
        FakeServices services = FakeServices.create(FakeClock.at(FakeRuntime.T0)).secret("secret://partner-hmac", "first");
        Secret s = services.secret(step(Map.of("key", "secret://partner-hmac")), "key");
        assertEquals("secret://partner-hmac", s.toString());
        assertEquals("first", s.value());
        services.secret("secret://partner-hmac", "second");
        assertEquals("second", s.value());
        assertFalse(services.toString().contains("second"), services.toString());
    }

    @Test
    void whatANodeRefusesIsRefusedNamingTheStepAndTheKey() {
        FakeServices services = FakeServices.create(FakeClock.at(FakeRuntime.T0)).secret("secret://set", "v");
        for (Map<String, Object> config : List.<Map<String, Object>>of(Map.of(), Map.of("key", 42), Map.of("key", "a-literal-password"),
                Map.of("key", "secret://Not-Well-Formed"), Map.of("key", "secret://not-set"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> services.secret(step(config), "key"), config.toString());
            assertTrue(e.getMessage().contains("step 'sign'") && e.getMessage().contains("'key'"), e.getMessage());
            assertFalse(e.getMessage().contains("a-literal-password"), "a literal is never shown: " + e.getMessage());
        }
    }

    @Test
    void aMalformedReferenceCannotBeSet() {
        assertThrows(IllegalArgumentException.class, () -> FakeServices.create(FakeClock.at(FakeRuntime.T0)).secret("partner", "v"));
    }

    @Test
    void stepResourcesCarryTheServices() {
        FakeRuntime rt = FakeRuntime.create();
        FakeServices services = FakeServices.create(rt.clock());
        assertSame(services, rt.stepResources(Path.of("."), services).services());
        assertSame(rt.clock(), rt.stepResources(Path.of("."), services).clock());
    }
}
