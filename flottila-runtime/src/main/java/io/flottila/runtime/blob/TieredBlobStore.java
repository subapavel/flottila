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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
 * <p>With a durable tier ({@link PgBodies}, D-058, D-061) {@link #persist} moves a memory or spool body into Postgres: once
 * the insert has committed, the index entry and its memory budget go before the future completes, a spool file is unlinked
 * afterwards on a virtual thread. From then on — and on any node, after any restart — a key missing from the index is read
 * from Postgres; nothing is cached locally. An S3 body is durable already. No path of this store deletes a durable body.
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
    private final PgBodies bodies;   // the durable tier; null = none
    private volatile long peakQueued;   // the most bytes any streaming write held ahead of its sink (a test seam for the back-pressure bound)
    private volatile Runnable afterSinkDecision;   // test seam: see afterSinkDecision(Runnable)
    private volatile Consumer<BlobKey> afterRelease;   // test seam: see afterRelease(Consumer)

    private static final System.Logger LOG = System.getLogger(TieredBlobStore.class.getName());

    /**
     * One write's index entry. Identity equality, never value equality (D-062): a same-size rewrite of a spool body has the
     * same location, file and size, and {@link #release} must not take it for the entry its persist read.
     */
    private static final class Stored {
        private final Location location;
        private final Buffer bytes;
        private final Path file;
        private final String s3Key;
        private final long size;

        Stored(Location location, Buffer bytes, Path file, String s3Key, long size) {
            this.location = location;
            this.bytes = bytes;
            this.file = file;
            this.s3Key = s3Key;
            this.size = size;
        }

        Location location() { return location; }
        Buffer bytes() { return bytes; }
        Path file() { return file; }
        String s3Key() { return s3Key; }
        long size() { return size; }
    }

    /** A store without a durable tier: {@link #persist} succeeds for an S3 body only. */
    public TieredBlobStore(Config config, Hasher hasher) {
        this(config, hasher, null, DEFAULT_QUEUE_HIGH_WATER);
    }

    /** A store whose {@link #persist} moves memory and spool bodies into {@code bodies}, the Postgres tier. */
    public TieredBlobStore(Config config, Hasher hasher, PgBodies bodies) {
        this(config, hasher, Objects.requireNonNull(bodies, "bodies"), DEFAULT_QUEUE_HIGH_WATER);
    }

    /** Test seam: a small high-water mark makes the pause / resume of the source deterministic. */
    TieredBlobStore(Config config, Hasher hasher, long queueHighWater) {
        this(config, hasher, null, queueHighWater);
    }

    private TieredBlobStore(Config config, Hasher hasher, PgBodies bodies, long queueHighWater) {
        this.config = Objects.requireNonNull(config, "config");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        if (queueHighWater <= 1) throw new IllegalArgumentException("queueHighWater must be above 1, was " + queueHighWater);
        this.budget = new MemoryBudget(config.memoryBudgetBytes());
        this.cipher = new SpoolCipher(config.spoolKey());
        this.queueHighWater = queueHighWater;
        this.bodies = bodies;
    }

    /** The most bytes any streaming write has queued ahead of its sink since this store was created: back-pressure keeps it near the high-water mark plus one chunk. */
    long peakQueuedBytes() { return peakQueued; }

    /**
     * Test-only: runs on the sink thread once per {@code drain} loop iteration that polled (a chunk or the end), after the
     * monitor block that decides on the resume and before the resume it may issue, the final iteration included — not on the
     * iteration that leaves the loop on a recorded failure; {@code null} (the default) = none.
     */
    void afterSinkDecision(Runnable hook) { this.afterSinkDecision = hook; }

    /**
     * Test-only: called once per persisted local body with its key once its local copy is gone — a memory body's right after
     * its budget is released (before the persist completes), a spool body's after the unlink was attempted, on the unlinking
     * virtual thread; {@code null} (the default) = none.
     */
    void afterRelease(Consumer<BlobKey> hook) { this.afterRelease = hook; }

    @Override public long treeLimit() { return config.treeLimit(); }

    /** Where a body is held locally (memory, spool) or in S3, or empty when none is — a persisted body lives in Postgres only. */
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

    /**
     * {@inheritDoc} A key the index misses is read from the durable tier when there is one, lazily: the stream asks Postgres
     * when it is first read and fails with an {@link IllegalArgumentException} when no row exists. Without a durable tier an
     * unknown key throws that at once.
     */
    @Override
    public ReadStream<Buffer> open(BlobKey key) {
        Stored s = objects.get(key);
        if (s != null) return open(key, s);
        if (bodies == null) throw noObject(key);
        return new InputStreamReadStream(() -> durable(key));
    }

    private ReadStream<Buffer> open(BlobKey key, Stored s) {
        return switch (s.location()) {
            case MEMORY -> new BufferReadStream(s.bytes());
            case SPOOL -> new InputStreamReadStream(() -> spooled(key, s));
            case S3 -> new InputStreamReadStream(() -> s3Stream(s.s3Key()));
        };
    }

    /** A key the index misses is read from the durable tier when there is one; no row (or no tier) fails with an {@link IllegalArgumentException}. */
    @Override
    public Future<Buffer> read(BlobKey key) {
        Stored s = objects.get(key);
        if (s == null) {
            if (bodies == null) return Future.failedFuture(noObject(key));
            return bodies.select(key).compose(row -> row.isPresent() ? Future.succeededFuture(row.get()) : Future.failedFuture(noObject(key)));
        }
        if (s.location() == Location.MEMORY) return Future.succeededFuture(s.bytes().copy());
        return collect(open(key, s));
    }

    /**
     * Makes a body durable (D-061). An S3 body is already: done at once. Without a durable tier every other key — a local body
     * or one the index misses — is refused with an {@link IllegalStateException}. With one: a key the index misses is done when
     * its row exists, else refused with an
     * {@link IllegalArgumentException}; a body above the tier's bound fails with {@link BodyTooLargeException} and stays where it
     * is; otherwise its bytes are read and inserted, and once the insert has committed the local copy goes — the index entry and
     * the memory budget before the future completes, a spool file afterwards on a virtual thread (an unlink that fails is
     * logged). A failed read or insert fails the persist and leaves the body local. Nothing is ever deleted from Postgres.
     */
    @Override
    public Future<Void> persist(BlobKey key) {
        Stored s = objects.get(key);
        if (s != null && s.location() == Location.S3) return Future.succeededFuture();   // the S3 object outlives the process already
        if (bodies == null) return Future.failedFuture(new IllegalStateException("this blob store has no durable tier: " + key + " cannot be persisted"));
        if (s == null) return bodies.exists(key).compose(found -> found ? Future.succeededFuture() : Future.failedFuture(noObject(key)));
        if (s.size() > bodies.maxBytes()) return Future.failedFuture(new BodyTooLargeException(key, s.size(), bodies.maxBytes()));
        Future<Buffer> local = s.location() == Location.MEMORY ? Future.succeededFuture(s.bytes()) : collect(open(key, s));
        return local.compose(bytes -> bodies.insert(key, bytes)).map(inserted -> {
            release(key, s);   // before the future completes: whoever awaits it finds the local copy gone
            return null;
        });
    }

    /**
     * The local copy of a persisted body goes, when the index still holds {@code s} — otherwise it was evicted, written again
     * or released by another persist meanwhile, and its budget and file are not this persist's: the entry and its memory budget
     * now, a spool file afterwards on a virtual thread, never on the caller's.
     */
    private void release(BlobKey key, Stored s) {
        if (!objects.remove(key, s)) return;
        if (s.location() == Location.MEMORY) {
            budget.release(s.size());
            released(key);
            return;
        }
        Thread.ofVirtual().name("flottila-blob-unlink").start(() -> {
            try {
                Files.deleteIfExists(s.file());
            } catch (IOException left) {   // the bytes are durable: a stale spool file costs disk until the next start clears the spool
                LOG.log(System.Logger.Level.WARNING, "the spool file of the persisted body " + key + " was not unlinked: " + s.file(), left);
            }
            released(key);
        });
    }

    private void released(BlobKey key) {
        Consumer<BlobKey> hook = afterRelease;
        if (hook != null) hook.accept(key);
    }

    /**
     * A spool body's bytes, on the stream's virtual thread; a file a persist unlinked after this stream was opened means the
     * bytes are in Postgres now (a store without a durable tier fails as before).
     */
    private InputStream spooled(BlobKey key, Stored s) throws IOException {
        try {
            return cipher.decrypting(s.file());
        } catch (NoSuchFileException gone) {
            if (bodies == null) throw gone;
            return durable(key);
        }
    }

    /** The durable row's bytes, on the stream's virtual thread (it blocks on the query); no row fails with an {@link IllegalArgumentException}. */
    private InputStream durable(BlobKey key) {
        Optional<Buffer> row = bodies.select(key).await();
        if (row.isEmpty()) throw noObject(key);
        return new ByteArrayInputStream(row.get().getBytes());
    }

    private static IllegalArgumentException noObject(BlobKey key) { return new IllegalArgumentException("no object stored under " + key); }

    /** Every byte of {@code stream}, in order. */
    private static Future<Buffer> collect(ReadStream<Buffer> stream) {
        Promise<Buffer> p = Promise.promise();
        Buffer all = Buffer.buffer();
        stream.exceptionHandler(p::tryFail);
        stream.endHandler(v -> p.tryComplete(all));
        stream.handler(all::appendBuffer);
        return p.future();
    }

    /**
     * Forgets a body and frees what it held (budget, spool file, S3 object); quiet for an unknown key — a persisted body's row
     * stays, only the retention job deletes from {@code bodies} (P2-07). Not for the hot path.
     */
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
        private boolean resumeOwed = true;   // a resume is owed to the source until start() or a re-check issues it: for the handover (the stream may arrive paused) and after every pause of ours; read by start() only, so meaningful until the attach
        private boolean pausing;   // true while a pause of ours is being entered — from enqueue marking the source paused, or start()'s reconcile, until that pause's re-check has run; the sink leaves a pause it decides for while it is being entered to that re-check
        private Thread handingOver;   // the thread whose hand-over resume (start()'s, issued after the monitor) is in flight; null = none. A pause issued meanwhile on another thread may be undone by it; one on this thread is inside the resume (a synchronous source) and so after it
        private boolean pausedDuringHandover;   // a pause of ours issued on another thread while the hand-over resume was in flight — the resume may have undone it: the hand-over thread issues it once more after its resume (start()), or the pending re-check does (again)
        private boolean resumeDeferred;   // the sink decided a resume while the hand-over resume was in flight and left it to the hand-over thread: after its resume, issued plainly when no pause of ours was marked; otherwise the marked pause's re-check decides it — of start()'s re-issued pause (repause), or the pending one (again)
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
            // decides here, under the monitor — except a pause of ours still being entered, which its own re-check resumes; afterwards
            // the sink does (BlobStore contract: a stream handed over paused flows once the handlers are attached, and a pause or
            // failure of our own is never undone). The hand-over resume is issued after the monitor, so what another thread does or wants
            // meanwhile is reconciled after it (handingOver): a pause of ours it may undo (pausedDuringHandover), a resume the sink must
            // not duplicate (resumeDeferred).
            boolean resume;
            synchronized (this) {
                attached = true;
                resume = resumeOwed && !sourcePaused && !pausing && failure == null && !result.future().isComplete();
                if (resume) { resumeOwed = false; handingOver = Thread.currentThread(); }
            }
            if (!resume) return;
            in.resume();
            boolean repause, plainResume;
            synchronized (this) {
                handingOver = null;
                // what another thread did or wanted during our resume: a pause of ours it issued (our resume may have undone it) is issued once
                // more and its re-check decides the resume; a resume the sink left to us with no such pause finds the source paused by a pause of
                // ours our resume did not undo, sourcePaused cleared — issued plainly. A re-check still pending (pausing) reconciles itself (again).
                repause = pausedDuringHandover && !pausing;
                plainResume = !repause && !pausing && resumeDeferred && failure == null;
                if (repause || plainResume) { pausedDuringHandover = false; resumeDeferred = false; }
                if (repause) pausing = true;
            }
            if (repause) pauseAndReCheck();
            else if (plainResume) in.resume();
            if (failed()) in.pause();   // a failure that landed in the window: never consume past it (a second pause cannot hang, nothing resumes after a failure)
        }

        private synchronized boolean over() { return failure != null || result.future().isComplete(); }

        /** Every failure (the source's in fail(), the sink's in drain()) is recorded here before the future fails; the first recorded is the cause. A sink failure after the source's rides as suppressed; a source failure after the sink's is dropped (fail() returns on over(); the future fails with the sink's after the sink's abort()). */
        private synchronized boolean failed() { return failure != null; }

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
                if (pauseSource) {
                    sourcePaused = true;
                    resumeOwed = true;
                    // one pausing at a time: a pause already being entered (start()'s re-issue) takes effect for this one too, and its
                    // re-check sees sourcePaused and leaves the resume to the sink — a second re-check would resume a second time
                    if (pausing) pauseSource = false;
                    else {
                        pausing = true;
                        if (handingOver != null && handingOver != Thread.currentThread()) pausedDuringHandover = true;
                    }
                }
                if (queued > peakQueued) peakQueued = queued;
                notifyAll();
            }
            if (pauseSource) pauseAndReCheck();
        }

        /** Pauses the source for a pause already marked under the monitor and re-checks after in.pause() returned: the pause is ours to resume when the sink's decision (or, before the attach, nobody) overtook it; a pause issued on another thread while the hand-over resume was in flight (pausedDuringHandover) or a resume the sink deferred meanwhile (resumeDeferred) is reconciled after it: the pause once more, this re-check decides. */
        private void pauseAndReCheck() {
            while (true) {
                in.pause();   // outside the monitor: the source may lock around its own dispatch
                boolean again, resume = false;
                synchronized (this) {
                    again = (pausedDuringHandover || resumeDeferred) && handingOver == null;   // the hand-over resume completed while this pause was being entered and may have undone it
                    if (again) { pausedDuringHandover = false; resumeDeferred = false; }
                    else {
                        pausing = false;
                        resume = !sourcePaused && failure == null;   // ours to resume — never after a failure
                        if (resume && handingOver != null && handingOver != Thread.currentThread()) resume = false;   // the hand-over resume is in flight on another thread: ours now would be a second one for this pause; the hand-over thread reconciles after its resume (this pause is marked)
                        if (resume) resumeOwed = false;
                    }
                }
                if (again) continue;
                if (resume) {
                    in.resume();
                    if (failed()) in.pause();   // a failure that landed between the decision and the resume: never consume past it
                }
                return;
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
                                // after the attach the sink resumes a pause whose re-check has run — unless the hand-over resume is in flight: then the hand-over
                                // thread reconciles (a resume of ours now would be a second one for this pause and could undo the source's next); a pause still
                                // being entered is resumed by its own re-check (which sees sourcePaused cleared); before the attach the re-check or start() issues the one resume
                                if (attached && !pausing) {
                                    if (handingOver == null) resumeSource = true;
                                    else resumeDeferred = true;   // the hand-over resume is in flight: the hand-over thread issues this resume after it; a resume of ours now would be a second one for this pause and could undo the source's next
                                }
                            }
                        }
                    }
                    Runnable hook = afterSinkDecision;
                    if (hook != null) hook.run();
                    if (resumeSource) {
                        in.resume();
                        if (failed()) in.pause();   // the source's fail() landed between the decision and the resume: never consume past it
                    }
                    if (next == null) {
                        sink.finish();
                        objects.put(key, stored(key, seen));
                        result.complete(Body.of(TieredBlobStore.this, key, hash.finish(), seen));
                        return;
                    }
                    sink.write(next);
                }
            } catch (Throwable t) {
                // the sink's failure takes the source's path: recorded under the monitor first, then the pause, then the promise,
                // so every party that later looks (start()'s re-check, chunk(), fail()) sees the failure before the future fails; the first
                // failure recorded under the monitor (the source's in fail() or the sink's here) is the cause. A sink failure after the
                // source's rides as suppressed; a source failure after the sink's is dropped (fail() returns on over(); the future fails
                // with the sink's after the sink's abort())
                synchronized (this) {
                    if (failure == null) failure = t;
                    else if (t != failure) failure.addSuppressed(t);   // a source failure racing in here has already stopped the source
                    problem = failure;
                    notifyAll();
                }
                in.pause();
            }
            if (sink != null) sink.abort();
            result.tryFail(problem);
        }
    }
}
