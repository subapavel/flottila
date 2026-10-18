package io.flottila.bench;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.testkit.GeneratedReadStream;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;

/**
 * P1-01 numbers: a body of {@code --mb} megabytes streamed through the tiered BlobStore — memory tier
 * and encrypted spool — written and read back {@code --rounds} times; MB/s per direction, hash checked
 * every round. {@code --spool-dir} defaults to a temp dir on the VM's disk. Exit 3 on a hash mismatch.
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
        for (String tier : new String[] {"memory", "spool"}) {
            long memoryTier = tier.equals("memory") ? size : 0;
            TieredBlobStore store = new TieredBlobStore(new TieredBlobStore.Config(size + 1, memoryTier, size + 1, spool, key(), null, false), hasher);
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
        }
        json.put("hashesMatched", ok).put("machine", BenchMain.machine()).put("date", Instant.now().toString())
                .put("commit", a.getOrDefault("commit", System.getenv().getOrDefault("GIT_COMMIT", "unknown")));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, json.encodePrettily() + "\n");
        System.out.println(json.encodePrettily());
        return Boolean.compare(true, ok) * 3;   // 0, or 3 on any hash mismatch
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
