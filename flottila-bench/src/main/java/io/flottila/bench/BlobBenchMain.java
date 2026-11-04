package io.flottila.bench;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.journal.Journal;
import io.flottila.runtime.journal.JournalConfig;
import io.flottila.runtime.journal.JournalFiles;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.testkit.GeneratedReadStream;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * P1-01 numbers: a body of {@code --mb} megabytes streamed through the tiered BlobStore — memory tier
 * and the journal's encrypted spill (CORE-4c2, D-105: the tier the spool was; still reported as {@code spool}, so the
 * numbers compare with the earlier rows) — written and read back {@code --rounds} times; MB/s per direction, hash checked
 * every round. Each tier writes through a journal of its own, without Postgres, under {@code --spool-dir} (default a temp
 * dir on the VM's disk): the memory tier's takes a record as large as the body, the spill's the smallest RECORD_MAX and a
 * memory tier of 0. Nothing is persisted: each round's body is evicted. Exit 3 on a hash mismatch.
 *
 * @since 0.1
 */
public final class BlobBenchMain {

    private BlobBenchMain() {}

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        Map<String, String> a = BenchMain.parse(args);
        long mb = Long.parseLong(a.getOrDefault("mb", "50"));
        int rounds = Integer.parseInt(a.getOrDefault("rounds", "5"));
        int chunk = Integer.parseInt(a.getOrDefault("chunk", "65536"));
        Path spool = Path.of(a.getOrDefault("spool-dir", Files.createTempDirectory("flottila-blob-bench").toString()));
        Path out = Path.of(a.getOrDefault("out", "bench/out/blob.json"));
        long size = mb << 20;
        Hasher hasher = Hasher.sha256();
        Hash expected = GeneratedReadStream.expectedHash(hasher, size);
        JsonObject json = new JsonObject().put("name", "blob-tiers").put("bodyMb", mb).put("rounds", rounds).put("chunkBytes", chunk);
        boolean ok = true;
        Vertx vertx = Vertx.vertx();   // the journals' timer only
        try {
            for (String tier : new String[] {"memory", "spool"}) {
                boolean memory = tier.equals("memory");
                try (Journal journal = journal(spool.resolve(tier), memory ? Math.max(size, MIN_RECORD) : MIN_RECORD, vertx)) {
                    TieredBlobStore store = new TieredBlobStore(new TieredBlobStore.Config(size + 1, memory ? size : 0, size + 1, size), hasher,
                            journal, TENANT);
                    ok = rounds(json, tier, store, size, mb, rounds, chunk, expected, hasher) & ok;
                }
            }
        } finally {
            vertx.close().await();
        }
        json.put("hashesMatched", ok).put("machine", BenchMain.machine()).put("date", Instant.now().toString())
                .put("commit", a.getOrDefault("commit", System.getenv().getOrDefault("GIT_COMMIT", "unknown")));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, json.encodePrettily() + "\n");
        System.out.println(json.encodePrettily());
        return Boolean.compare(true, ok) * 3;   // 0, or 3 on any hash mismatch
    }

    /** The smallest RECORD_MAX a journal takes: every body above it is a spill. */
    private static final long MIN_RECORD = 4096;
    /** The one tenant key id of the bench's journals. */
    private static final int TENANT = 1;

    /** A journal without Postgres in {@code dir} (node {@code bench}), RECORD_MAX {@code recordMax}, the tenant key a fresh AES key. */
    private static Journal journal(Path dir, long recordMax, Vertx vertx) throws IOException, NoSuchAlgorithmException {
        SecretKey tenant = key();
        JournalConfig config = new JournalConfig(dir, 64L << 20, 65_536, Duration.ofSeconds(1), Math.max(16L << 20, recordMax),
                Duration.ofSeconds(5), recordMax, "bench");
        return Journal.open(config, Clock.systemUTC(), new VertxTimer(vertx), id -> id == TENANT ? tenant : null, JournalFiles.real());
    }

    /** {@code rounds} writes and reads of the body through {@code store}, put into {@code json} under {@code tier}; whether every hash matched. */
    private static boolean rounds(JsonObject json, String tier, TieredBlobStore store, long size, long mb, int rounds, int chunk, Hash expected,
                                  Hasher hasher) {
        boolean ok = true;
        long[] writeNanos = new long[rounds];
        long[] readNanos = new long[rounds];
        for (int r = 0; r < rounds; r++) {
            BlobKey key = BlobKey.body(tier + "-" + r, 0);
            long t0 = System.nanoTime();
            Body body = store.write(key, new GeneratedReadStream(size, chunk), size).await();
            writeNanos[r] = System.nanoTime() - t0;
            long t1 = System.nanoTime();
            Hash read = hashOf(store.open(key), hasher).await();
            readNanos[r] = System.nanoTime() - t1;
            ok = ok & expected.equals(body.hash()) & expected.equals(read);   // non-short-circuit on purpose: both checks always run
            store.evict(key).await();
        }
        json.put(tier, new JsonObject()
                .put("writeMbPerSecond", mbPerSecond(mb, writeNanos))
                .put("readMbPerSecond", mbPerSecond(mb, readNanos))
                .put("writeMsPerRound", millis(writeNanos))
                .put("readMsPerRound", millis(readNanos)));
        return ok;
    }

    static SecretKey key() throws NoSuchAlgorithmException {
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        return g.generateKey();
    }

    /** Median round, as MB/s. */
    static double mbPerSecond(long mb, long[] nanos) {
        long[] sorted = nanos.clone();
        java.util.Arrays.sort(sorted);
        return mb / (sorted[sorted.length / 2] / 1_000_000_000.0);
    }

    static io.vertx.core.json.JsonArray millis(long[] nanos) {
        io.vertx.core.json.JsonArray a = new io.vertx.core.json.JsonArray();
        for (long n : nanos) a.add(Math.round(n / 1_000_00.0) / 10.0);
        return a;
    }

    static io.vertx.core.Future<Hash> hashOf(ReadStream<Buffer> stream, Hasher hasher) {
        Promise<Hash> p = Promise.promise();
        Hasher.Session session = hasher.begin();
        stream.exceptionHandler(p::tryFail);
        stream.endHandler(v -> p.tryComplete(session.finish()));
        stream.handler(session::update);
        return p.future();
    }
}
