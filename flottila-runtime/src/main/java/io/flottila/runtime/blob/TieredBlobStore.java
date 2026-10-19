package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hasher;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime {@link BlobStore} (ARCHITECTURE §4 "where the bytes live"): a body stays in memory while it is
 * at most {@link Config#memoryTierBytes()} and the node-wide {@link MemoryBudget} has room; otherwise it goes
 * to the encrypted local spool, or straight to S3 as a multipart upload when S3 is configured and preferred
 * (at-least-once flows). A streamed body starts in memory and spills the moment it outgrows either limit —
 * the prefix already seen is replayed into the sink, the hash runs over every byte once. Objects are keyed
 * by message id + step ({@link BlobKey}), never by content. Every limit is finite; sizes are long.
 *
* <p>Blocking I/O (file, S3) runs on a virtual thread per write or read — opening the sink included; the
 * caller's stream is paused while the sink is behind and resumed as it drains, so an event-loop source is
 * never blocked.
 *
 * @since 0.1
 */
public final class TieredBlobStore implements BlobStore {

    /** @since 0.1 */
    public enum Location { MEMORY, SPOOL, S3 }

    /** An S3 bucket and key prefix reached through a configured client (credentials, endpoint, region are the client's). @since 0.1 */
    public record S3Target(S3Client client, String bucket, String prefix) {
        public S3Target {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(prefix, "prefix");
        }
    }

    /**
     * The tiers. {@code memoryTierBytes} may be 0 (no memory tier) and never exceeds {@code memoryBudgetBytes};
     * {@code preferS3} sends every spilled body to S3 instead of the spool and requires {@code s3}.
     *
     * @since 0.1
     */
    public record Config(long treeLimit, long memoryTierBytes, long memoryBudgetBytes, Path spoolDir, SecretKey spoolKey, S3Target s3, boolean preferS3) {
        public Config {
            if (treeLimit <= 0) throw new IllegalArgumentException("treeLimit must be positive, was " + treeLimit);
            if (memoryTierBytes < 0) throw new IllegalArgumentException("memoryTierBytes must not be negative, was " + memoryTierBytes);
            if (memoryBudgetBytes <= 0) throw new IllegalArgumentException("memoryBudgetBytes must be positive, was " + memoryBudgetBytes);
            if (memoryTierBytes > memoryBudgetBytes) throw new IllegalArgumentException("memoryTierBytes " + memoryTierBytes + " above the memory budget " + memoryBudgetBytes);
            if (memoryTierBytes > Integer.MAX_VALUE) throw new IllegalArgumentException("memoryTierBytes above " + Integer.MAX_VALUE + ": one in-memory body is one buffer");
            Objects.requireNonNull(spoolDir, "spoolDir");
            Objects.requireNonNull(spoolKey, "spoolKey");
            if (preferS3 && s3 == null) throw new IllegalArgumentException("preferS3 without an S3 target");
        }
    }

    static final long DEFAULT_QUEUE_HIGH_WATER = 1L << 20;   // bytes queued for the sink before the source is paused

    private final Config config;
    private final Hasher hasher;
    private final MemoryBudget budget;
    private final SpoolCipher cipher;
    private final long queueHighWater;
    private final Map<BlobKey, Stored> objects = new ConcurrentHashMap<>();
    private volatile long peakQueued;   // the most bytes any streaming write held ahead of its sink (a test seam for the back-pressure bound)

    private record Stored(Location location, Buffer bytes, Path file, String s3Key, long size) {}

    public TieredBlobStore(Config config, Hasher hasher) {
        this(config, hasher, DEFAULT_QUEUE_HIGH_WATER);
    }

    /** Test seam: a small high-water mark makes the pause / resume of the source deterministic. */
    TieredBlobStore(Config config, Hasher hasher, long queueHighWater) {
        this.config = Objects.requireNonNull(config, "config");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        if (queueHighWater <= 1) throw new IllegalArgumentException("queueHighWater must be above 1, was " + queueHighWater);
        this.budget = new MemoryBudget(config.memoryBudgetBytes());
        this.cipher = new SpoolCipher(config.spoolKey());
        this.queueHighWater = queueHighWater;
    }

    /** The most bytes any streaming write has queued ahead of its sink since this store was created: back-pressure keeps it near the high-water mark plus one chunk. */
    long peakQueuedBytes() { return peakQueued; }

    @Override public long treeLimit() { return config.treeLimit(); }

    /** Where a stored body lives, or empty when nothing is stored under the key. */
    public Optional<Location> location(BlobKey key) { return Optional.ofNullable(objects.get(key)).map(Stored::location); }

    /** Bytes of bodies currently held in the memory tier. */
    public long memoryInUse() { return budget.inUse(); }

    /** The S3 object key a body is (or would be) stored under: {@code <prefix>/<messageId>/<step>/<part>}. */
    public String objectKey(BlobKey key) { return config.s3().prefix() + "/" + key.messageId() + "/" + key.step() + "/" + key.part(); }

    private Path spoolFile(BlobKey key) { return config.spoolDir().resolve(key.messageId()).resolve(key.step() + "-" + key.part() + ".blob"); }

    /** Ids become path segments: a separator or a dot-name would escape the spool directory or the S3 prefix. */
    private static void checkKey(BlobKey key) {
        String id = key.messageId();
        if (id.isEmpty() || id.equals(".") || id.equals("..") || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("message id is not a safe path segment: '" + id + "'");
        }
    }

    @Override
    public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
        checkKey(key);
        long size = whole.length();
        if (size > maxSize) return Future.failedFuture(new BodyTooLargeException(key, size, maxSize));
        if (size <= config.memoryTierBytes() && budget.tryReserve(size)) {
            objects.put(key, new Stored(Location.MEMORY, whole.copy(), null, null, size));
            return Future.succeededFuture(Body.of(this, key, hasher.hashOf(whole), size));
        }
        Promise<Body> result = Promise.promise();
        Thread.ofVirtual().name("flottila-blob-write").start(() -> {
            try {
                Sink sink = newSink(key);
                try {
                    sink.write(whole);
                    sink.finish();
                } catch (Throwable t) {
                    sink.abort();
                    throw t;
                }
                objects.put(key, stored(key, size));
                result.complete(Body.of(this, key, hasher.hashOf(whole), size));
            } catch (Throwable t) {
                result.fail(t);
            }
        });
        return result.future();
    }

    @Override
    public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
        checkKey(key);
        StreamingWrite w = new StreamingWrite(key, in, maxSize);
        w.start();
        return w.result.future();
    }

    @Override
    public ReadStream<Buffer> open(BlobKey key) {
        Stored s = objects.get(key);
        if (s == null) throw new IllegalArgumentException("no object stored under " + key);
        return switch (s.location()) {
            case MEMORY -> new BufferReadStream(s.bytes());
            case SPOOL -> new InputStreamReadStream(() -> cipher.decrypting(s.file()));
            case S3 -> new InputStreamReadStream(() -> s3Stream(s.s3Key()));
        };
    }

    @Override
    public Future<Buffer> read(BlobKey key) {
        Stored s = objects.get(key);
        if (s == null) return Future.failedFuture(new IllegalArgumentException("no object stored under " + key));
        if (s.location() == Location.MEMORY) return Future.succeededFuture(s.bytes().copy());
        Promise<Buffer> p = Promise.promise();
        Buffer all = Buffer.buffer();
        ReadStream<Buffer> stream = open(key);
        stream.exceptionHandler(p::tryFail);
        stream.endHandler(v -> p.tryComplete(all));
        stream.handler(all::appendBuffer);
        return p.future();
    }

    /** Forgets a body and frees what it held (budget, spool file, S3 object); quiet for an unknown key. Not for the hot path. */
    public Future<Void> evict(BlobKey key) {
        Stored s = objects.remove(key);
        if (s == null) return Future.succeededFuture();
        if (s.location() == Location.MEMORY) {
            budget.release(s.size());
            return Future.succeededFuture();
        }
        Promise<Void> p = Promise.promise();
        Thread.ofVirtual().name("flottila-blob-evict").start(() -> {
            try {
                if (s.location() == Location.SPOOL) Files.deleteIfExists(s.file());
                else config.s3().client().deleteObject(DeleteObjectRequest.builder().bucket(config.s3().bucket()).key(s.s3Key()).build());
                p.complete();
            } catch (Throwable t) {
                p.fail(t);
            }
        });
        return p.future();
    }

    private InputStream s3Stream(String s3Key) {
        return config.s3().client().getObject(GetObjectRequest.builder().bucket(config.s3().bucket()).key(s3Key).build());
    }

    private boolean spillsToS3() { return config.preferS3(); }   // Config guarantees an S3 target when preferred

    private Sink newSink(BlobKey key) throws IOException {
        return spillsToS3() ? new S3Sink(config.s3(), objectKey(key)) : new SpoolSink(cipher, spoolFile(key));
    }

    private Stored stored(BlobKey key, long size) {
        return spillsToS3() ? new Stored(Location.S3, null, null, objectKey(key), size) : new Stored(Location.SPOOL, null, spoolFile(key), null, size);
    }

    /** One streamed write: memory first; after the spill a virtual thread opens the sink and feeds it from a bounded queue. */
    private final class StreamingWrite {
        private final BlobKey key;
        private final ReadStream<Buffer> in;
        private final long maxSize;
        final Promise<Body> result = Promise.promise();
        private final Hasher.Session hash = hasher.begin();
        private Buffer collected = Buffer.buffer();
        private long reserved;
        private long seen;
        // after the spill (all guarded by this):
        private boolean spilled;
        private Sink sink;
        private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
        private long queued;
        private boolean ended;
        private boolean sourcePaused;
        private boolean attached;      // start() has attached its handlers: from then on the sink thread may resume the source itself
        private boolean resumeOwed = true;   // a resume is owed: for the handover (the stream may arrive paused) and after every pause of ours; cleared by whoever resumes
        private Throwable failure;

        StreamingWrite(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            this.key = key;
            this.in = in;
            this.maxSize = maxSize;
        }

        void start() {
            in.exceptionHandler(this::fail);
            in.endHandler(v -> end());
            in.handler(this::chunk);
            // attaching may already have delivered, paused or failed: a synchronous source (BufferReadStream for a memory-tier body, the
            // testkit's streams) runs inside handler(). Until this moment the sink thread never resumes the source, so exactly one party
            // decides here, under the monitor; afterwards the sink does (BlobStore contract: a stream handed over paused flows once the
            // handlers are attached, and a pause or failure of our own is never undone).
            boolean resume;
            synchronized (this) {
                attached = true;
                resume = resumeOwed && !sourcePaused && failure == null && !result.future().isComplete();
                if (resume) resumeOwed = false;
            }
            if (resume) in.resume();
            if (resume && failed()) in.pause();   // a failure that landed in the window: never consume past it (a second pause cannot hang, nothing resumes after a failure)
        }

        private synchronized boolean over() { return failure != null || result.future().isComplete(); }

        private synchronized boolean failed() { return failure != null || result.future().failed(); }

        private void chunk(Buffer c) {
            if (over()) return;
            seen += c.length();
            if (seen > maxSize) {
                fail(new BodyTooLargeException(key, seen, maxSize));
                return;
            }
            hash.update(c);
            if (!spilled) {
                if (seen <= config.memoryTierBytes() && budget.tryReserve(c.length())) {
                    reserved += c.length();
                    collected.appendBuffer(c);
                    return;
                }
                spill();
            }
            enqueue(c);
        }

        /** Hands the prefix seen so far to the sink thread; no I/O happens on the source's thread. */
        private void spill() {
            budget.release(reserved);
            reserved = 0;
            Buffer prefix = collected;
            collected = null;
            spilled = true;
            if (prefix.length() > 0) enqueue(prefix);
            Thread.ofVirtual().name("flottila-blob-write").start(this::drain);
        }

        private void enqueue(Buffer c) {
            boolean pauseSource;
            synchronized (this) {
                queue.addLast(c);
                queued += c.length();
                pauseSource = queued >= queueHighWater && !sourcePaused;
                if (pauseSource) { sourcePaused = true; resumeOwed = true; }
                if (queued > peakQueued) peakQueued = queued;
                notifyAll();
            }
            if (pauseSource) {
                in.pause();   // outside the monitor: the source may lock around its own dispatch
                boolean resumedMeanwhile;
                synchronized (this) {
                    resumedMeanwhile = !sourcePaused;   // the sink drained and "resumed" before this pause landed (or, before the attach, left the resume to us)
                    if (resumedMeanwhile) resumeOwed = false;
                }
                if (resumedMeanwhile) in.resume();
            }
        }

        private void end() {
            if (over()) return;
            if (!spilled) {
                objects.put(key, new Stored(Location.MEMORY, collected, null, null, seen));
                result.complete(Body.of(TieredBlobStore.this, key, hash.finish(), seen));
                return;
            }
            synchronized (this) {
                ended = true;
                notifyAll();
            }
        }

        /** Records the failure; before the spill the promise fails at once, after it the sink thread aborts first and then fails it. */
        private void fail(Throwable t) {
            synchronized (this) {
                if (over()) return;
                failure = t;
                notifyAll();
            }
            in.pause();
            budget.release(reserved);
            reserved = 0;
            collected = null;
            if (!spilled) result.tryFail(t);
        }

        /** The sink's virtual thread: opens the sink, writes queued chunks in order, resumes the source as the queue drains, commits at the end; on any failure aborts the sink before the promise fails. */
        private void drain() {
            Throwable problem;
            try {
                sink = newSink(key);
                while (true) {
                    Buffer next;
                    boolean resumeSource = false;
                    synchronized (this) {
                        while (queue.isEmpty() && !ended && failure == null) wait();
                        if (failure != null) {
                            problem = failure;
                            break;
                        }
                        next = queue.pollFirst();   // null only when the queue is empty and the source has ended
                        if (next != null) {
                            queued -= next.length();
                            if (sourcePaused && queued < queueHighWater / 2) {
                                sourcePaused = false;
                                resumeSource = attached;   // before attach, the source thread (its re-check) or start() issues the one resume
                                if (resumeSource) resumeOwed = false;
                            }
                        }
                    }
                    if (resumeSource) in.resume();
                    if (next == null) {
                        sink.finish();
                        objects.put(key, stored(key, seen));
                        result.complete(Body.of(TieredBlobStore.this, key, hash.finish(), seen));
                        return;
                    }
                    sink.write(next);
                }
            } catch (Throwable t) {
                problem = t;
                in.pause();
            }
            if (sink != null) sink.abort();
            result.tryFail(problem);
        }
    }
}
