package io.flottila.testkit;

import io.flottila.api.Component;
import io.flottila.api.Hasher;
import io.flottila.api.Runtime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeRuntimeTest {

    @Test
    void defaultsAreDeterministicAndInMemory() {
        FakeRuntime rt = FakeRuntime.create();
        assertEquals("m-1", rt.ids().next());
        assertEquals(Hasher.sha256().algorithm(), rt.hasher().algorithm());
        assertEquals(FakeRuntime.DEFAULT_TREE_LIMIT, rt.blobs().treeLimit());
        Instant before = rt.clock().instant();
        rt.clock().advance(Duration.ofSeconds(5));
        assertEquals(before.plusSeconds(5), rt.clock().instant());
        assertEquals(FakeRuntime.T0, before, "every test starts at the same instant");
    }

    @Test
    void theApiRuntimeRecordIsBuiltFromTheFakes() {
        Component mock = new LoopbackComponent();
        FakeRuntime fake = FakeRuntime.create().withComponent(mock);
        Runtime rt = fake.runtime();
        assertSame(fake.clock(), rt.clock());
        assertSame(fake.ids(), rt.ids());
        assertSame(fake.blobs(), rt.blobs());
        assertSame(mock, rt.component(mock.scheme()));
        assertThrows(IllegalArgumentException.class, () -> rt.component("sftp"));
    }

    @Test
    void treeLimitAndStartInstantAreConfigurable() {
        FakeRuntime rt = FakeRuntime.create().withTreeLimit(32).withClockAt(Instant.parse("2030-01-01T00:00:00Z"));
        assertEquals(32L, rt.blobs().treeLimit());
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), rt.clock().instant());
    }
}
