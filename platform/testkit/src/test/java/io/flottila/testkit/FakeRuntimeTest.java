package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.vertx.core.buffer.Buffer;
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
        assertSame(fake.timer(), rt.timer());
        assertSame(fake.blobs(), rt.blobs());
        assertSame(mock, rt.component(mock.scheme()));
        assertThrows(IllegalArgumentException.class, () -> rt.component("sftp"));
    }

    /**
     * D-066: {@link FakeRuntime#store} writes what a step hands on under the key the envelope names for its next step: the
     * plain key on a first attempt, the part suffixed with the attempt from the second on, so a rerun leaves the first run's
     * bytes where they were.
     */
    @Test
    void storeWritesUnderTheNextKeyOfTheEnvelopesRun() {
        FakeRuntime rt = FakeRuntime.create();
        Envelope atOne = rt.ingress("f", Headers.empty(), Buffer.buffer("<in/>")).advance();
        Body first = rt.store(atOne, Buffer.buffer("<first/>"));
        Body second = rt.store(atOne.with(Envelope.ATTEMPT, 2), Buffer.buffer("<second/>"));
        assertEquals(BlobKey.body(atOne.id(), 2), first.key(), "a first attempt: the next step's plain body key");
        assertEquals(new BlobKey(atOne.id(), 2, "body~2"), second.key(), "the second attempt: its own key");
        assertEquals("<first/>", rt.blobs().read(first.key()).await().toString(), "the first run's bytes stay under its key");
        assertEquals("<second/>", rt.blobs().read(second.key()).await().toString());
    }

    @Test
    void treeLimitAndStartInstantAreConfigurable() {
        FakeRuntime rt = FakeRuntime.create().withTreeLimit(32).withClockAt(Instant.parse("2030-01-01T00:00:00Z"));
        assertEquals(32L, rt.blobs().treeLimit());
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), rt.clock().instant());
    }
}
