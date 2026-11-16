package dev.rail8.runtime.journal;

import dev.rail8.api.BlobKey;
import dev.rail8.runtime.blob.RemoteBodyException;
import dev.rail8.runtime.journal.peer.PeerReads;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-264 (CORE-5a, the class comment's "a dial that fails or throws"): a live owner whose peer address is no {@code host:port}
 * — the dial throws before anything is connected — fails each read at once with a retryable {@link RemoteBodyException} naming
 * the owner and the address, and gives its read permit back: more reads than {@code RAIL8_PEER_READS} permits all fail, none waits.
 * The peer that never got a link then closes with the reads: {@link PeerReads#close()} completes.
 */
@Timeout(120)
class PeerReadMalformedAddressTest {

    static final String ADDRESS = "127.0.0.1:eighty";

    @TempDir Path tmp;
    Vertx vertx;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    Journal b;

    @BeforeEach
    void open() throws Exception {
        vertx = Vertx.vertx();
        JournalFixture.Settings s = JournalFixture.settings(Files.createDirectories(tmp.resolve("node-b"))).segmentAge(Duration.ofHours(1));
        s.node = "node-b";
        JournalConfig config = s.config();
        b = OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
    }

    @AfterEach
    void close() throws Exception {
        b.close();
        vertx.close().await();
    }

    @Test
    void anAddressThatIsNoHostAndPortFailsEveryReadRetryablyGivesItsPermitBackAndTheReadsClose() throws Exception {
        PeerReads reads = new PeerReads(vertx, PeerLinkTest.CONFIG, PeerProtocolTest.b(), b, timer, owner -> Optional.of(ADDRESS));
        BlobKey key = BlobKey.body("m-remote", 0);
        JournalRef ref = new JournalRef(1, 49, 100);
        int opens = PeerLinkTest.CONFIG.reads() + 1;

        List<Throwable> failures = OnVirtualThread.call(() -> {
            List<Throwable> seen = new ArrayList<>();
            for (int i = 0; i < opens; i++) {
                try {
                    reads.open(key, "node-a", UUID.randomUUID(), ref).close();
                    seen.add(null);
                } catch (Exception failed) {
                    seen.add(failed);
                }
            }
            return seen;
        });

        assertEquals(opens, failures.size(), "every read returned, none waits for a permit: " + failures);
        for (Throwable failed : failures) {
            assertNotNull(failed, "no read opens on an address that is no host:port: " + failures);
            assertEquals(RemoteBodyException.class, failed.getClass(), "a retryable remote-body failure: " + failed);
            String text = failed.getMessage();
            assertTrue(text.contains("node node-a") && text.contains("no peer link to " + ADDRESS) && text.endsWith("retryable"),
                    "naming the owner and the address: " + text);
        }
        reads.close().await(20, TimeUnit.SECONDS);
    }
}
