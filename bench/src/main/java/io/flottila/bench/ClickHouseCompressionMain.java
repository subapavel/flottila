package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

/**
 * CORE-2 numbers: invoice payloads ({@link InvoiceGenerator}) stored in a ClickHouse MergeTree table as a {@code payload String
 * CODEC(ZSTD(n))} column, ordered by {@code (flow, created_at, id)}, inserted over HTTP in RowBinary batches of at most
 * {@code --batch-bytes} as a mover would, then {@code OPTIMIZE … FINAL}. One cell is size × mix × format × zstd level; per
 * run: compression ratio from {@code system.parts_columns} (payload column and whole part), insert duration and peak memory
 * from {@code system.query_log}, merge peak memory, duration and fan-in (parts merged: {@code length(merged_from)}, the largest,
 * the one of the merge with the peak memory and the one of the final merge) from {@code system.part_log} and the OPTIMIZE query's own
 * entry, the server's {@code MemoryTracking} sampled every 100 ms, failed inserts and a failed OPTIMIZE as ClickHouse reported
 * them (a result of the cell, not an abort), and single-payload reads by id ({@code FORMAT RawBLOB}, client-side, after a
 * warm-up), each checked against the inserted length and CRC32C. A cell runs {@code --runs} times when its first run took less
 * than {@code --max-run-minutes}, else once. Each run uses a fresh table, dropped after it. Exit 3 when any read failed or
 * returned other bytes than were inserted.
 * <p>Arguments: {@code --url --user --password --sizes (KiB) --counts --mixes --formats --levels --runs --max-run-minutes
 * --reads --warmup-reads --batch-bytes --seed --image --out --commit}.
 *
 * @since 0.1
 */
public final class ClickHouseCompressionMain {

    private static final String DB = "bench";

    /** Room in a batch beyond the payload: the generator's overshoot (under 4 KiB) plus flow, created_at, id and varints. */
    static final int ROW_SLACK = 8192;

    private ClickHouseCompressionMain() {}

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    /** An inserted row: its id and the length and CRC32C of its payload in one value. */
    private record Row(String id, long fingerprint) {}

    static int run(String[] args) throws Exception {
        Map<String, String> a = BenchMain.parse(args);
        ClickHouseHttp ch = new ClickHouseHttp(a.getOrDefault("url", "http://127.0.0.1:18123"), a.getOrDefault("user", "default"),
                a.getOrDefault("password", ""));
        long[] sizesKib = Arrays.stream(a.getOrDefault("sizes", "100,1024,10240,51200").split(",")).mapToLong(Long::parseLong).toArray();
        int[] counts = Arrays.stream(a.getOrDefault("counts", "2000,500,100,20").split(",")).mapToInt(Integer::parseInt).toArray();
        String[] mixes = a.getOrDefault("mixes", "mixed,similar").split(",");
        String[] formats = a.getOrDefault("formats", "xml,json").split(",");
        int[] levels = Arrays.stream(a.getOrDefault("levels", "1,3,9").split(",")).mapToInt(Integer::parseInt).toArray();
        int runs = Integer.parseInt(a.getOrDefault("runs", "3"));
        long maxRunMs = Long.parseLong(a.getOrDefault("max-run-minutes", "5")) * 60_000;
        int reads = Integer.parseInt(a.getOrDefault("reads", "200"));
        int warmupReads = Integer.parseInt(a.getOrDefault("warmup-reads", "50"));
        int batchBytes = Integer.parseInt(a.getOrDefault("batch-bytes", String.valueOf(64 << 20)));
        long seed = Long.parseLong(a.getOrDefault("seed", "20261104"));
        Path out = Path.of(a.getOrDefault("out", "bench/out/clickhouse-compression.json"));
        require(counts.length == sizesKib.length, "--counts needs one count per size");
        require(runs >= 1, "--runs at least 1: " + runs);
        for (long kib : sizesKib) {
            require(kib >= 1 && (kib << 10) + ROW_SLACK <= batchBytes, "--sizes in KiB, 1 up to --batch-bytes " + batchBytes + " less "
                    + ROW_SLACK + ": " + kib);
        }
        for (int count : counts) require(count >= 1, "--counts at least 1: " + count);
        for (int level : levels) require(level >= 1 && level <= 22, "--levels are zstd levels 1..22: " + level);

        ch.query("CREATE DATABASE IF NOT EXISTS " + DB);
        JsonObject json = new JsonObject().put("name", "clickhouse-compression")
                .put("server", new JsonObject().put("image", a.getOrDefault("image", "unknown"))
                        .put("version", ch.query("SELECT version()").strip())
                        .put("maxServerMemoryUsage", ch.query("SELECT value FROM system.server_settings WHERE name = 'max_server_memory_usage'").strip())
                        .put("indexGranularityBytes", ch.query("SELECT value FROM system.merge_tree_settings WHERE name = 'index_granularity_bytes'").strip())
                        .put("maxCompressBlockSize", ch.query("SELECT getSetting('max_compress_block_size')").strip()))
                .put("config", new JsonObject().put("sizesKib", new JsonArray(Arrays.stream(sizesKib).boxed().toList()))
                        .put("counts", new JsonArray(Arrays.stream(counts).boxed().toList())).put("levels", new JsonArray(Arrays.stream(levels).boxed().toList()))
                        .put("runs", runs).put("maxRunMinutes", maxRunMs / 60_000).put("reads", reads).put("warmupReads", warmupReads)
                        .put("batchBytes", batchBytes).put("seed", seed));
        JsonArray cells = new JsonArray();
        json.put("cells", cells);
        List<String> table = new ArrayList<>();
        boolean readsOk = true;
        for (int s = 0; s < sizesKib.length; s++) {
            for (String mix : mixes) {
                InvoiceGenerator gen = new InvoiceGenerator(seed, InvoiceGenerator.Mix.valueOf(mix.toUpperCase()));
                for (String format : formats) {
                    for (int level : levels) {
                        JsonArray runResults = new JsonArray();
                        for (int r = 0; r < runs; r++) {
                            JsonObject one = runOnce(ch, gen, InvoiceGenerator.Format.valueOf(format.toUpperCase()), sizesKib[s] << 10, counts[s],
                                    level, mix, r, reads, warmupReads, batchBytes, seed);
                            runResults.add(one);
                            readsOk &= one.getLong("readMismatches") == 0;
                            System.err.println("cell " + sizesKib[s] + "KiB " + mix + " " + format + " zstd" + level + " run " + (r + 1) + ": " + one.encode());
                            if (r == 0 && one.getLong("wallMs") >= maxRunMs) break;
                        }
                        JsonObject cell = new JsonObject().put("sizeKib", sizesKib[s]).put("count", counts[s]).put("mix", mix)
                                .put("format", format).put("zstd", level).put("runsMade", runResults.size()).put("runs", runResults)
                                .put("median", aggregate(runResults, 0.5)).put("min", aggregate(runResults, 0)).put("max", aggregate(runResults, 1));
                        cells.add(cell);
                        table.add(row(cell));
                        writeOut(out, json, a);
                    }
                }
            }
        }
        System.out.println("| size | mix | format | zstd | runs | part ratio | payload ratio | insert s | insert peak MiB | OPTIMIZE s | merge peak MiB | server peak MiB | read p50 ms | read p99 ms | failed |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        table.forEach(System.out::println);
        return readsOk ? 0 : 3;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static void writeOut(Path out, JsonObject json, Map<String, String> a) throws IOException {
        json.put("machine", BenchMain.machine()).put("date", Instant.now().toString())
                .put("commit", a.getOrDefault("commit", System.getenv().getOrDefault("GIT_COMMIT", "unknown")));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, json.encodePrettily() + "\n");
    }

    /** RowBinary rows collected up to the batch limit, then sent as one insert; a failed insert is counted, its rows not kept. */
    private static final class Inserter {
        final ClickHouseHttp ch;
        final String table;
        final String qid;
        final byte[] batch;
        final List<Row> rows = new ArrayList<>();
        final List<Row> pending = new ArrayList<>();
        int len;
        int inserts;
        int failures;
        String firstFailure;
        long nanos;

        Inserter(ClickHouseHttp ch, String table, String qid, int batchBytes) {
            this.ch = ch;
            this.table = table;
            this.qid = qid;
            this.batch = new byte[batchBytes];
        }

        void add(String flow, long createdAt, String id, byte[] payload) throws InterruptedException {
            long rowBytes = 15L + flow.length() + 8 + id.length() + payload.length;   // three varints of at most 5 bytes each
            if (len + rowBytes > batch.length) flush();
            len = string(batch, len, flow.getBytes(StandardCharsets.US_ASCII));
            len = int64(batch, len, createdAt);
            len = string(batch, len, id.getBytes(StandardCharsets.US_ASCII));
            len = string(batch, len, payload);
            pending.add(new Row(id, fingerprint(payload)));
        }

        void flush() throws InterruptedException {
            long w = System.nanoTime();
            try {
                ch.insertRowBinary(table, qid + "-ins-" + inserts, batch, len);
                rows.addAll(pending);
            } catch (IOException e) {
                failures++;
                if (firstFailure == null) firstFailure = e.getMessage();
            }
            nanos += System.nanoTime() - w;
            inserts++;
            len = 0;
            pending.clear();
        }
    }

    static JsonObject runOnce(ClickHouseHttp ch, InvoiceGenerator gen, InvoiceGenerator.Format format, long size, int count, int level,
                              String mix, int run, int reads, int warmupReads, int batchBytes, long seed) throws Exception {
        long t0 = System.nanoTime();
        String tableName = String.format("p_%dk_%s_%s_z%d_r%d", size >> 10, mix, format.name().toLowerCase(), level, run + 1);
        String t = DB + "." + tableName;
        String qid = "core2-" + UUID.randomUUID().toString().substring(0, 8) + "-" + tableName.replace('_', '-');
        ch.query("DROP TABLE IF EXISTS " + t + " SYNC");
        ch.query("CREATE TABLE " + t + " (flow LowCardinality(String), created_at DateTime64(3, 'UTC'), id String, payload String CODEC(ZSTD("
                + level + "))) ENGINE = MergeTree ORDER BY (flow, created_at, id)");
        SplittableRandom meta = new SplittableRandom(seed ^ size ^ ((long) run << 40));
        int flows = mix.equals("mixed") ? 8 : 1;
        long createdAt = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
        long generateNanos = 0;
        long raw = 0;
        Inserter ins = new Inserter(ch, t, qid, batchBytes);
        AtomicLong serverPeak = new AtomicLong();
        AtomicLong samples = new AtomicLong();
        long idle = memoryTracking(ch);
        Thread sampler = Thread.ofVirtual().start(() -> sample(ch, serverPeak, samples));
        try {
            for (int i = 0; i < count; i++) {
                long g = System.nanoTime();
                byte[] doc = gen.document(i, format, size);
                generateNanos += System.nanoTime() - g;
                raw += doc.length;
                createdAt += 1 + meta.nextInt(100);
                ins.add("invoice-in-" + meta.nextInt(flows), createdAt, new UUID(meta.nextLong(), meta.nextLong()).toString(), doc);
            }
            ins.flush();
            long o = System.nanoTime();
            String optimizeFailure = null;
            try {
                ch.query("OPTIMIZE TABLE " + t + " FINAL", qid + "-opt", Map.of());
            } catch (IOException e) {
                optimizeFailure = e.getMessage();
            }
            long optimizeNanos = System.nanoTime() - o;
            sampler.interrupt();
            sampler.join();
            LatencyStats readStats = new LatencyStats(Math.max(1, reads));
            int mismatches = read(ch, t, qid, ins.rows, reads, warmupReads, readStats, new SplittableRandom(seed + run));
            ch.query("SYSTEM FLUSH LOGS");
            long[] rd = longs(ch.query("SELECT count(), quantileExact(0.5)(query_duration_ms), quantileExact(0.99)(query_duration_ms),"
                    + " toUInt64(ifNotFinite(avg(read_bytes), 0)), max(memory_usage) FROM system.query_log WHERE query_id LIKE '" + qid
                    + "-read-%' AND type = 'QueryFinish' AND toUInt32(splitByChar('-', query_id)[-1]) >= " + warmupReads + " FORMAT TSV"));
            long[] in = longs(ch.query("SELECT count(), sum(query_duration_ms), max(memory_usage), max(query_duration_ms) FROM system.query_log"
                    + " WHERE query_id LIKE '" + qid + "-ins-%' AND type = 'QueryFinish' FORMAT TSV"));
            long[] errs = longs(ch.query("SELECT count() FROM system.query_log WHERE query_id LIKE '" + qid + "-%'"
                    + " AND type IN ('ExceptionBeforeStart', 'ExceptionWhileProcessing') FORMAT TSV"));
            long[] opt = longs(ch.query("SELECT count(), sum(query_duration_ms), max(memory_usage) FROM system.query_log WHERE query_id = '"
                    + qid + "-opt' AND type = 'QueryFinish' FORMAT TSV"));
            long[] merges = longs(ch.query("SELECT count(), max(peak_memory_usage), countIf(error != 0), argMax(peak_memory_usage, event_time_microseconds),"
                    + " argMax(duration_ms, event_time_microseconds), max(length(merged_from)), argMax(length(merged_from), peak_memory_usage),"
                    + " argMax(length(merged_from), event_time_microseconds) FROM system.part_log WHERE database = '" + DB + "' AND table = '" + tableName
                    + "' AND event_type = 'MergeParts' FORMAT TSV"));
            long[] parts = longs(ch.query("SELECT count(), sum(rows), sum(column_data_uncompressed_bytes), sum(column_data_compressed_bytes),"
                    + " sum(data_uncompressed_bytes), sum(data_compressed_bytes), countIf(part_type = 'Wide') FROM system.parts_columns WHERE database = '" + DB
                    + "' AND table = '" + tableName + "' AND column = 'payload' AND active FORMAT TSV"));
            JsonObject result = new JsonObject().put("rows", ins.rows.size()).put("rawBytes", raw).put("inserts", ins.inserts)
                    .put("insertFailures", ins.failures).put("insertFailure", ins.firstFailure)
                    .put("generateMs", generateNanos / 1_000_000).put("insertClientMs", ins.nanos / 1_000_000)
                    .put("insertsLogged", in[0]).put("insertServerMs", in[1]).put("insertPeakMemoryBytes", in[2]).put("insertMaxMs", in[3])
                    .put("queryExceptions", errs[0])
                    .put("optimizeClientMs", optimizeNanos / 1_000_000).put("optimizeFailure", optimizeFailure).put("optimizeLogged", opt[0])
                    .put("optimizeServerMs", opt[1]).put("optimizeMemoryBytes", opt[2])
                    .put("merges", merges[0]).put("mergePeakMemoryBytes", merges[1]).put("mergeFailures", merges[2])
                    .put("finalMergePeakMemoryBytes", merges[3]).put("finalMergeMs", merges[4])
                    .put("mergeFanInMax", merges[5]).put("mergePeakFanIn", merges[6]).put("finalMergeFanIn", merges[7])
                    .put("serverMemoryIdleBytes", idle).put("serverMemoryPeakBytes", serverPeak.get()).put("serverMemorySamples", samples.get())
                    .put("activeParts", parts[0]).put("wideParts", parts[6]).put("partRows", parts[1])
                    .put("payloadUncompressedBytes", parts[2]).put("payloadCompressedBytes", parts[3])
                    .put("payloadRatio", ratio(parts[2], parts[3])).put("partRatio", ratio(parts[4], parts[5]))
                    .put("reads", readStats.count()).put("readMismatches", mismatches).put("readsLogged", rd[0])
                    .put("readServerP50Ms", rd[1]).put("readServerP99Ms", rd[2]).put("readServerBytesAvg", rd[3]).put("readPeakMemoryBytes", rd[4]);
            if (readStats.count() > 0) {
                result.put("readP50Ms", round(readStats.percentileMillis(50))).put("readP99Ms", round(readStats.percentileMillis(99)))
                        .put("readMaxMs", round(readStats.maxMillis()));
            }
            return result.put("wallMs", (System.nanoTime() - t0) / 1_000_000);
        } finally {
            sampler.interrupt();
            ch.query("DROP TABLE IF EXISTS " + t + " SYNC");
        }
    }

    /**
     * Warm-up reads (distinct ids, not timed), then {@code reads} random reads by id, each checked; returns the reads that failed
     * or returned other bytes. No inserted row: no read (the failed inserts are the cell's result).
     */
    private static int read(ClickHouseHttp ch, String t, String qid, List<Row> rows, int reads, int warmupReads, LatencyStats stats,
                            SplittableRandom r) throws InterruptedException {
        if (rows.isEmpty()) return 0;
        String sql = "SELECT payload FROM " + t + " WHERE id = {id:String} FORMAT RawBLOB";
        int mismatches = 0;
        for (int i = 0; i < warmupReads + reads; i++) {
            Row row = rows.get(i < warmupReads ? i % rows.size() : r.nextInt(rows.size()));
            boolean ok;
            try {
                long n = System.nanoTime();
                byte[] got = ch.query(sql, qid + "-read-" + i, Map.of("id", row.id));
                long nanos = System.nanoTime() - n;
                if (i >= warmupReads) stats.record(nanos);
                ok = fingerprint(got) == row.fingerprint;
            } catch (IOException e) {
                ok = false;
            }
            if (!ok) mismatches++;
        }
        return mismatches;
    }

    /** Samples until interrupted (sleep or the HTTP call throws); a failed sample ends sampling, visible as fewer samples. */
    private static void sample(ClickHouseHttp ch, AtomicLong peak, AtomicLong samples) {
        try {
            for (;;) {
                peak.accumulateAndGet(memoryTracking(ch), Math::max);
                samples.incrementAndGet();
                Thread.sleep(100);
            }
        } catch (InterruptedException | IOException e) {
            // interrupted at the end of the cell, or a failed sample: serverMemorySamples shows how far it got
        }
    }

    private static long memoryTracking(ClickHouseHttp ch) throws IOException, InterruptedException {
        return Long.parseLong(ch.query("SELECT value FROM system.metrics WHERE metric = 'MemoryTracking'").strip());
    }

    private static long fingerprint(byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(payload);
        return ((long) payload.length << 32) | crc.getValue();
    }

    /** One TSV row of numbers (aggregates without GROUP BY: always one row). */
    private static long[] longs(String tsv) {
        return Arrays.stream(tsv.strip().split("\t")).mapToLong(Long::parseLong).toArray();
    }

    private static int string(byte[] buf, int at, byte[] s) {
        long n = s.length;
        while (n >= 0x80) {
            buf[at++] = (byte) (n | 0x80);
            n >>>= 7;
        }
        buf[at++] = (byte) n;
        System.arraycopy(s, 0, buf, at, s.length);
        return at + s.length;
    }

    private static int int64(byte[] buf, int at, long v) {
        for (int i = 0; i < 8; i++) buf[at++] = (byte) (v >>> (8 * i));
        return at;
    }

    /** uncompressed / compressed to two decimals; 0 when nothing was stored (no part, or a Compact part without column sizes). */
    private static double ratio(long uncompressed, long compressed) {
        return compressed == 0 ? 0.0 : round((double) uncompressed / compressed);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** Per numeric field of the runs: the value at quantile q of the sorted runs (0 min, 0.5 median, 1 max). */
    private static JsonObject aggregate(JsonArray runs, double q) {
        JsonObject first = runs.getJsonObject(0);
        JsonObject out = new JsonObject();
        for (String k : first.fieldNames()) {
            if (!(first.getValue(k) instanceof Number)) continue;
            double[] v = runs.stream().mapToDouble(o -> ((JsonObject) o).getDouble(k)).sorted().toArray();
            out.put(k, v[(int) Math.round(q * (v.length - 1))]);
        }
        return out;
    }

    private static String row(JsonObject cell) {
        JsonObject md = cell.getJsonObject("median");
        JsonObject lo = cell.getJsonObject("min");
        JsonObject hi = cell.getJsonObject("max");
        long failed = 0;
        for (Object o : cell.getJsonArray("runs")) {
            JsonObject r = (JsonObject) o;
            failed += r.getLong("insertFailures") + r.getLong("mergeFailures") + r.getLong("queryExceptions") + r.getLong("readMismatches")
                    + (r.getString("optimizeFailure") == null ? 0 : 1);
        }
        return "| " + cell.getLong("sizeKib") + " KiB x " + cell.getInteger("count") + " | " + cell.getString("mix") + " | " + cell.getString("format")
                + " | " + cell.getInteger("zstd") + " | " + cell.getInteger("runsMade")
                + " | " + range(md, lo, hi, "partRatio", 1, 2) + " | " + range(md, lo, hi, "payloadRatio", 1, 2) + " | " + range(md, lo, hi, "insertServerMs", 1000, 1)
                + " | " + range(md, lo, hi, "insertPeakMemoryBytes", 1 << 20, 0) + " | " + range(md, lo, hi, "optimizeServerMs", 1000, 1)
                + " | " + range(md, lo, hi, "mergePeakMemoryBytes", 1 << 20, 0) + " | " + range(md, lo, hi, "serverMemoryPeakBytes", 1 << 20, 0)
                + " | " + range(md, lo, hi, "readP50Ms", 1, 1) + " | " + range(md, lo, hi, "readP99Ms", 1, 1) + " | " + failed + " |";
    }

    private static String range(JsonObject md, JsonObject lo, JsonObject hi, String k, double div, int digits) {
        if (!md.containsKey(k)) return "n/a";
        String f = "%." + digits + "f";
        String m = String.format(f, md.getDouble(k) / div);
        if (lo.getDouble(k).equals(hi.getDouble(k))) return m;
        return m + " [" + String.format(f, lo.getDouble(k) / div) + ".." + String.format(f, hi.getDouble(k) / div) + "]";
    }
}
