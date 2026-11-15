package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4d review r1 (1): a spill is counted while it streams. A spill started when the journal is at max is refused
 * {@link JournalFullException} and the journal's files stay at or below max, as measured on disk; an intake's spill (an ingress
 * body, step 0, written to the store) started past max − max/16 is refused at its start and writes no chunk under
 * {@code spill/}. Witness: a step body's spill at the same bytes is taken (the reserve). Max 1 MiB, RECORD_MAX 4096 (so 10 000
 * bytes is a spill); the bytes are filled with reserve records sized from one measured record overhead.
 *
 * <p>The API assumed (r1, test-author): the store's {@code write} of a step-0 key is an intake spill; the journal's five-argument
 * {@code spill} is bounded at least by max.
 */
@Timeout(60)
class JournalSpillBoundTest {

    static final long MAX = 1L << 20;
    static final long EDGE = MAX - MAX / 16;
    static final long LARGE = 10_000;

    @TempDir Path tmp;
    Path dir;
    HeldFiles files;
    Journal journal;
    TieredBlobStore store;
    int next;

    @BeforeEach
    void open() throws Exception {
        dir = Files.createDirectories(tmp.resolve("journal"));
        files = new HeldFiles();
        FakeClock clock = FakeClock.at(JournalFixture.START);
        JournalConfig config = settings(dir).recordMax(4096).pendingBytes(512L << 10).maxBytes(MAX).segmentBytes(MAX).config();
        journal = OnVirtualThread.call(() -> Journal.open(config, clock, new FakeTimer(clock), keys(KEY), files));
        store = new TieredBlobStore(new TieredBlobStore.Config(1L << 20, 4096, 8L << 20, 1L << 20), Hasher.sha256(), journal, KEY);
    }

    @AfterEach
    void close() throws Exception {
        files.releaseAll();
        journal.close();
    }

    long onDisk() {
        long sum = 0;
        for (String sub : List.of("segments", "spill")) {
            Path d = dir.resolve(sub);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> l = Files.list(d)) {
                for (Path p : l.filter(Files::isRegularFile).toList()) sum += Files.size(p);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return sum;
    }

    String id() { return String.format("r-%05d", next++); }

    JournalRef reserve(int size) throws Exception {
        String id = id();
        Journal.Appended a = journal.append(new JournalRecord(id, 1, "body", KEY, new byte[size]), Promise.promise().future(), Journal.Bound.RESERVE);
        a.forced().await();
        return a.ref();
    }

    /** Reserve records until the journal holds exactly {@code target} bytes. */
    void fillTo(long target) throws Exception {
        long overhead = OnVirtualThread.call(() -> reserve(1_000)).length() - 1_000;
        while (target - journal.bytes() - overhead > 3_000) OnVirtualThread.call(() -> reserve(3_000));
        long last = target - journal.bytes() - overhead;
        assertTrue(last > 0, "fixture: a last record that ends at the target: " + last);
        OnVirtualThread.call(() -> reserve(Math.toIntExact(last)));
        assertEquals(target, journal.bytes(), "fixture: the journal holds the target bytes");
        assertEquals(target, onDisk(), "fixture: at rest the bytes are its files");
    }

    @Test
    void aSpillStartedAtMaxIsRefusedAndTheFilesStayAtMax() throws Exception {
        fillTo(MAX);

        Exception refused = assertThrows(Exception.class, () -> OnVirtualThread.call(() ->
                journal.spill(BlobKey.body("s-full", 2), KEY, new GeneratedInput(3, LARGE), LARGE, Hasher.sha256())));
        assertInstanceOf(JournalFullException.class, refused instanceof JournalFullException ? refused : refused.getCause(),
                "a spill that would pass max is refused while it streams: " + refused);
        assertTrue(onDisk() <= MAX, "the journal's files never pass max: " + onDisk());
        assertEquals(MAX, journal.bytes(), "nothing of it stays counted");
    }

    @Test
    void anIntakeSpillPastTheEdgeWritesNoChunk() throws Exception {
        fillTo(EDGE + 1_000);
        Path spill = dir.resolve("spill");

        Future<io.flottila.api.Body> ingress = JournalWorld.settled(store.write(BlobKey.body("m-ingress", 0), JournalWorld.bytes((int) LARGE, 4), 1L << 20));
        assertTrue(ingress.failed(), "an ingress body past max − max/16 is refused at its spill's start");
        assertInstanceOf(JournalFullException.class, ingress.cause(), "retryable JournalFull (503): " + JournalWorld.causes(ingress.cause()));
        assertEquals(List.of(), files.writesUnder(spill), "no chunk of it was written under spill/");
        assertEquals(EDGE + 1_000, journal.bytes(), "nothing counted");

        Future<io.flottila.api.Body> step = JournalWorld.settled(store.write(BlobKey.body("m-step", 2), JournalWorld.bytes((int) LARGE, 5), 1L << 20));
        assertTrue(step.succeeded(), "witness: a step body's spill at the same bytes uses the reserve: " + (step.failed() ? JournalWorld.causes(step.cause()) : ""));
        assertTrue(!files.writesUnder(spill).isEmpty(), "witness: its chunks are written under spill/");
    }
}
