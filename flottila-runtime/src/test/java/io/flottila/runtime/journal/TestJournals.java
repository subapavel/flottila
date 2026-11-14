package io.flottila.runtime.journal;

import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.sqlclient.Pool;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * A node's body journal for the CORE-4c2 ITs of any package: opened in {@code dir} under {@code node} over a migrated database
 * ({@code rows}: its {@code journal_segments}, {@code journal_nodes} and {@code journal_keys} rows), the tenant key of tenant
 * {@code default} made or unwrapped under {@link #MASTER}, a fake clock at {@link JournalFixture#START} and a fake timer (a
 * segment rolls by age only when a case advances it). The design's defaults otherwise (RECORD_MAX 256 KiB). Opening blocks: it
 * runs on a virtual thread. A required configuration of the tests, not an expectation.
 */
public final class TestJournals {

    /** The master key of every journal these tests open: a fixture, never a secret. */
    static final SecretKey MASTER = JournalFixture.key(4242);

    private TestJournals() { }

    /** A journal open on a node, with what it needs to be closed and its tenant key id. */
    public record Opened(Journal journal, int tenant, FakeClock clock, FakeTimer timer, JournalConfig config, java.util.Set<Integer> deletedKeys)
            implements AutoCloseable {
        /** Deletes tenant key {@code keyId} from the keys the journal reads with (a shredded tenant key, CORE-4d r2): it answers none. */
        public void deleteKey(int keyId) { deletedKeys.add(keyId); }

        @Override public void close() throws IOException {
            journal.close();
        }
    }

    /** The journal of {@code node} in {@code dir}, its rows and keys in the database {@code rows} reaches; real files. */
    public static Opened open(Pool rows, Path dir, String node) throws Exception {
        return open(rows, dir, node, JournalFiles.real(), s -> { });
    }

    /** As {@link #open(Pool, Path, String)}, through {@code files}, the settings changed by {@code tweak} first. */
    static Opened open(Pool rows, Path dir, String node, JournalFiles files, Consumer<JournalFixture.Settings> tweak) throws Exception {
        return open(rows, dir, node, files, tweak, JournalFixture.START);
    }

    /** As {@link #open(Pool, Path, String, JournalFiles, Consumer)} with the fake clock at {@code at} (CORE-4d: a restart later on). */
    static Opened open(Pool rows, Path dir, String node, JournalFiles files, Consumer<JournalFixture.Settings> tweak, java.time.Instant at) throws Exception {
        JournalFixture.Settings s = JournalFixture.settings(dir);
        s.node = node;
        tweak.accept(s);
        JournalConfig config = s.config();
        FakeClock clock = FakeClock.at(at);
        FakeTimer timer = new FakeTimer(clock);
        return OnVirtualThread.call(() -> {
            JournalKeys keys = JournalKeys.load(rows, MASTER);
            int tenant = keys.keyOf("default");
            java.util.Set<Integer> deleted = java.util.concurrent.ConcurrentHashMap.newKeySet();
            Journal journal = Journal.open(config, clock, timer, id -> deleted.contains(id) ? null : keys.apply(id), files, new PgSegmentRows(rows),
                    UUID::randomUUID);
            return new Opened(journal, tenant, clock, timer, config, deleted);
        });
    }

    /** The tenant key id of a {@link #local} journal. */
    public static final int LOCAL_TENANT = JournalFixture.KEY;

    /**
     * A journal without Postgres for the unit tests of the store (CORE-4c2): in {@code dir}, node {@code node-1}, no segment
     * rows, the tenant key {@link #LOCAL_TENANT} from {@code keys}, RECORD_MAX {@code recordMax}, the design's defaults
     * otherwise. Opening blocks: it runs on a virtual thread.
     */
    public static Opened local(Path dir, IntFunction<SecretKey> keys, long recordMax) throws Exception {
        JournalConfig config = JournalFixture.settings(dir).recordMax(recordMax).pendingBytes(Math.max(16L << 20, recordMax)).config();
        FakeClock clock = FakeClock.at(JournalFixture.START);
        FakeTimer timer = new FakeTimer(clock);
        java.util.Set<Integer> deleted = java.util.concurrent.ConcurrentHashMap.newKeySet();
        IntFunction<SecretKey> deletable = id -> deleted.contains(id) ? null : keys.apply(id);
        return OnVirtualThread.call(() -> new Opened(Journal.open(config, clock, timer, deletable, JournalFiles.real()), LOCAL_TENANT, clock, timer, config, deleted));
    }

    /** {@link #local(Path, IntFunction, long)} with the fixture's tenant key and the smallest RECORD_MAX (4 KiB). */
    public static Opened local(Path dir) throws Exception {
        return local(dir, JournalFixture.keys(LOCAL_TENANT), 4096);
    }
}
