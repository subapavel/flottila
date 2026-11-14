package io.flottila.runtime.journal.peer;

import io.flottila.runtime.journal.JournalFullException;
import io.flottila.runtime.journal.Replica;
import io.flottila.runtime.journal.ReplicaMismatch;
import io.flottila.runtime.journal.Replicas;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToLongFunction;

/**
 * The holder's side of one repl link (CORE-5b1): the owner's OPEN, DATA and SEAL frames of its segments, written to
 * {@link Replicas} and answered by RECEIPT and REFUSE.
 *
 * <p>The link's event loop checks each frame as it comes and closes the link (WARNING) on a breach: a frame of another
 * type, an address not the link's owner and journal, an OPEN past {@value #OPEN_MAX} unsealed segments or of a segment open on
 * the link already, or one whose epoch the bound has passed ({@code bound}: checked again at every OPEN); a DATA or SEAL of a
 * segment not open on the link, before its RECEIPT OPENED or after its SEAL, a DATA whose pos is not the segment's end, a SEAL
 * whose end is not. A DATA or SEAL of a segment that FAILED is dropped, the link up. The rest is queued for the link's one
 * writer virtual thread, which takes them in order: an OPEN (create, header, force, directory force, RECEIPT OPENED — an OPEN
 * the replicas refuse is REFUSE FULL or MISMATCH, a header not the owner's closes the link), the pwrites of its DATA, then one
 * RECEIPT WRITTEN per segment per drain, sent before that drain's force goes to the link's force thread. The force thread forces
 * and seals (CRC32C, force, rename, directory force, RECEIPT SEALED); the writer waits for it only while the link's replicas hold
 * more than {@value #HOLD_AT} bytes written and not forced. A create, write, force, rename or directory force that fails is
 * REFUSE FAILED: the replica closed, its {@code .part} kept, its later frames dropped.
 *
 * <p>Back-pressure: above {@value #HOLD_AT} bytes of frames queued and not yet done (their wire length, r1) the link's socket is
 * paused, resumed at {@value #RESUME_AT}; the writer takes more frames only once every RECEIPT and REFUSE sent went out (r1
 * R3), so an owner that reads none stops it, and then its socket. A closed link stops its writer at its next drain and its force thread at its next task; every replica
 * still open is then detached, its count trued to its size. A link superseding the owner's earlier one stops that one's holder
 * as a close does (its writer waits for no receipt more) and takes its first frame only once the earlier one's threads stopped.
 */
final class Holder implements PeerLink.Receiver {

    private static final System.Logger LOG = System.getLogger(Holder.class.getName());
    /** Queued bytes above which the socket pauses, and unforced bytes above which the writer waits for its force thread. */
    static final long HOLD_AT = 8L << 20;
    /** Queued bytes at which a paused socket resumes. */
    static final long RESUME_AT = 4L << 20;
    /** The most unsealed segments on one link (a wire constant, D-139). */
    static final int OPEN_MAX = 4;

    private static final int PENDING = 0;   // OPEN taken, not receipted
    private static final int OPEN = 1;      // RECEIPT OPENED sent: DATA and SEAL go
    private static final int SEALING = 2;   // SEAL taken: nothing more of it

    /** One segment on the link. */
    private static final class Slot {
        final long n;
        long end;                      // the event loop's: where the next DATA goes
        volatile int state = PENDING;
        volatile Replica replica;      // once opened
        boolean failed;                // guarded by this: REFUSE FAILED or MISMATCH sent, its later frames dropped

        Slot(long n, long end) {
            this.n = n;
            this.end = end;
        }

        synchronized boolean dropped() {
            return failed;
        }
    }

    /** What the force thread does. */
    private sealed interface Task permits Force, Sealing { }

    private record Force(Slot slot) implements Task { }

    private record Sealing(Slot slot, ReplFrames.Seal seal) implements Task { }

    /** A frame for the writer and its length on the wire (framing v1: head, payload, tag), what it counts in {@code queued}. */
    private record Queued(ReplFrames.ToHolder frame, int wire) { }

    private final PeerLink link;
    private final String owner;
    private final UUID journal;
    private final long epoch;
    private final Replicas replicas;
    private final ToLongFunction<String> bound;
    private final Future<Void> previous;
    private final Promise<Void> stopped = Promise.promise();
    private final Map<Long, Slot> slots = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition moved = lock.newCondition();
    // guarded by lock
    private final ArrayDeque<Queued> frames = new ArrayDeque<>();
    private final ArrayDeque<Task> tasks = new ArrayDeque<>();
    private long queued;           // the wire bytes of the frames queued and not yet done
    private int unsent;            // the RECEIPTs and REFUSEs handed to the link whose write has not completed (r1 R3)
    private boolean paused;
    private boolean closed;

    /**
     * The holder of {@code link}'s frames, the owner's as its HELLO named it, into {@code replicas}; its writer takes a frame
     * once {@code previous} (the owner's earlier link's {@link #stopped()}) completed.
     */
    Holder(PeerLink link, Replicas replicas, ToLongFunction<String> bound, Future<Void> previous) {
        this.link = link;
        this.owner = link.peerNode();
        this.journal = link.peerJournal();
        this.epoch = link.peerEpoch();
        this.replicas = replicas;
        this.bound = bound;
        this.previous = previous;
        Thread.ofVirtual().name("flottila-holder-" + owner).start(this::write);
    }

    PeerLink link() {
        return link;
    }

    /** Completes once the writer and the force thread stopped and every replica of the link is detached. */
    Future<Void> stopped() {
        return stopped.future();
    }

    // ------------------------------------------------------------------------------------------------- event loop

    @Override public void received(PeerLink l, int type, byte[] frame) throws PeerRefusedException {
        ByteBuffer p = Frames.payload(frame);
        switch (type) {
            case Frames.OPEN -> open(ReplFrames.Open.decode(p), frame.length);
            case Frames.DATA -> data(ReplFrames.Data.decode(p), frame.length);
            case Frames.SEAL -> seal(ReplFrames.Seal.decode(p), frame.length);
            default -> throw new PeerRefusedException("a frame of type " + type + " on a repl link (OPEN, DATA and SEAL go to a holder)");
        }
    }

    private void open(ReplFrames.Open o, int wire) throws PeerRefusedException {
        long n = ours(o.address());
        long least = bound.applyAsLong(owner);
        if (epoch < least) {
            throw new PeerRefusedException("an OPEN of node " + owner + "'s epoch " + epoch + ": the holder knows epoch " + least + " of it now");
        }
        if (slots.containsKey(n)) throw new PeerRefusedException("an OPEN of segment " + n + " of " + owner + ", open on this link already");
        if (slots.size() >= OPEN_MAX) throw new PeerRefusedException("an OPEN of a segment past " + OPEN_MAX + " unsealed on one link");
        slots.put(n, new Slot(n, o.header().length));
        enqueue(o, wire);
    }

    private void data(ReplFrames.Data d, int wire) throws PeerRefusedException {
        Slot s = taking(d.address(), "a DATA");
        if (s == null) return;
        if (d.pos() != s.end) throw new PeerRefusedException("a DATA of segment " + s.n + " at " + d.pos() + ", its end is " + s.end);
        s.end += d.bytes().length;
        enqueue(d, wire);
    }

    private void seal(ReplFrames.Seal x, int wire) throws PeerRefusedException {
        Slot s = taking(x.address(), "a SEAL");
        if (s == null) return;
        if (x.end() != s.end) throw new PeerRefusedException("a SEAL of segment " + s.n + " at " + x.end() + ", its end is " + s.end);
        s.state = SEALING;
        enqueue(x, wire);
    }

    /** The slot a DATA or SEAL goes to; null when its segment FAILED (the frame dropped). */
    private Slot taking(ReplFrames.Address a, String what) throws PeerRefusedException {
        long n = ours(a);
        Slot s = slots.get(n);
        if (s == null) throw new PeerRefusedException(what + " of segment " + n + ", not open on this link");
        if (s.dropped()) return null;
        if (s.state != OPEN) {
            throw new PeerRefusedException(what + " of segment " + n + (s.state == PENDING ? " before its RECEIPT OPENED" : " after its SEAL"));
        }
        return s;
    }

    /** The segment {@code a} names, which must be of the link's owner and journal. */
    private long ours(ReplFrames.Address a) throws PeerRefusedException {
        if (!a.owner().equals(owner) || !a.journal().equals(journal)) {
            throw new PeerRefusedException("a frame of " + a.owner() + "'s journal " + a.journal() + " on the repl link of " + owner + "'s journal " + journal);
        }
        return a.n();
    }

    private void enqueue(ReplFrames.ToHolder frame, int wire) {
        lock.lock();
        try {
            frames.add(new Queued(frame, wire));
            queued += wire;
            if (queued > HOLD_AT && !paused) {
                paused = true;
                link.hold();
            }
            moved.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override public void closed(PeerLink l, IOException why) {
        stop();
    }

    @Override public boolean waiting() {
        return false;
    }

    /** Stops the writer at its next drain and the force thread at its next task (the link closed, its server, or a superseding link). */
    void stop() {
        lock.lock();
        try {
            closed = true;
            moved.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // ----------------------------------------------------------------------------------------------------- writer

    private void write() {
        Promise<Void> forcing = Promise.promise();
        Thread.ofVirtual().name("flottila-holder-force-" + owner).start(() -> {
            try {
                forceAll();
            } finally {
                forcing.complete();
            }
        });
        try {
            previous.await();   // completes, never fails: the owner's earlier link stopped
            for (List<Queued> drain; (drain = take()) != null; ) drain(drain);
        } finally {
            stop();
            forcing.future().await();
            for (Slot s : slots.values()) {
                Replica r = s.replica;
                if (r != null) detach(r);   // a failed one again: detach is safe to repeat
            }
            link.close(new IOException("the holder of " + owner + "'s journal stopped"));
            stopped.complete();
        }
    }

    /**
     * The frames queued, once there are any and every RECEIPT and REFUSE sent went out (r1 R3, as a READ's BYTES: an owner that
     * reads none stops the writer, its frames then queue past {@value #HOLD_AT} and pause the socket); null once closed.
     */
    private List<Queued> take() {
        lock.lock();
        try {
            while ((frames.isEmpty() || unsent > 0) && !closed) moved.awaitUninterruptibly();
            if (closed) return null;
            List<Queued> d = new ArrayList<>(frames);
            frames.clear();
            return d;
        } finally {
            lock.unlock();
        }
    }

    /** One drain: every frame queued, in order; one RECEIPT WRITTEN per segment written, before its force is handed on. */
    private void drain(List<Queued> drain) {
        Map<Slot, Long> written = new LinkedHashMap<>();
        for (Queued q : drain) {
            switch (q.frame()) {
                case ReplFrames.Open o -> {
                    flush(written);
                    opened(o);
                }
                case ReplFrames.Data d -> write(d, written);
                case ReplFrames.Seal x -> {
                    flush(written);
                    hand(new Sealing(slots.get(x.address().n()), x));
                }
            }
            taken(q.wire());
        }
        flush(written);
    }

    private void opened(ReplFrames.Open o) {
        long n = o.address().n();
        Slot s = slots.get(n);
        try {
            Replica r = replicas.open(owner, journal, n, o.length(), o.header());
            s.replica = r;
            s.state = OPEN;
            send(Frames.RECEIPT, new ReplFrames.Receipt(o.address(), r.end(), ReplFrames.OPENED).encode());
        } catch (JournalFullException full) {
            slots.remove(n);
            refuse(o.address(), ReplFrames.FULL, full.getMessage());
        } catch (ReplicaMismatch mismatch) {
            slots.remove(n);
            refuse(o.address(), ReplFrames.MISMATCH, mismatch.getMessage());
        } catch (IllegalArgumentException bad) {
            LOG.log(System.Logger.Level.WARNING, "the repl link with {0} is closed: {1}", link.remote(), bad.getMessage());
            link.close(new PeerRefusedException(bad.getMessage()));
        } catch (IOException e) {
            fail(s, e);
        }
    }

    private void write(ReplFrames.Data d, Map<Slot, Long> written) {
        Slot s = slots.get(d.address().n());
        if (s.dropped() || !roomToWrite(written)) return;   // it failed after the event loop took the frame, or the link closed
        try {
            written.put(s, s.replica.write(ByteBuffer.wrap(d.bytes())));
        } catch (IOException e) {
            fail(s, e);
        }
    }

    /**
     * Past {@value #HOLD_AT} unforced bytes on the link the writer sends its receipts, hands its forces on and waits, until the
     * force thread brought them to {@value #HOLD_AT} or the link closed (its force thread then stops: the wait must not outlive
     * it); false once closed, the write not made.
     */
    private boolean roomToWrite(Map<Slot, Long> written) {
        if (unforced() <= HOLD_AT) return true;
        flush(written);
        lock.lock();
        try {
            while (unforced() > HOLD_AT && !closed) moved.awaitUninterruptibly();
            return !closed;
        } finally {
            lock.unlock();
        }
    }

    private long unforced() {
        long sum = 0;
        for (Slot s : slots.values()) {
            Replica r = s.replica;
            if (r != null) sum += r.unforced();
        }
        return sum;
    }

    /** One RECEIPT WRITTEN per segment written since the last flush (none for one that failed since), then its force handed on. */
    private void flush(Map<Slot, Long> written) {
        for (Map.Entry<Slot, Long> w : written.entrySet()) {
            Slot s = w.getKey();
            synchronized (s) {
                if (s.failed) continue;
                send(Frames.RECEIPT, new ReplFrames.Receipt(address(s), w.getValue(), ReplFrames.WRITTEN).encode());
            }
            hand(new Force(s));
        }
        written.clear();
    }

    /** A frame of {@code wire} bytes is done: a paused socket resumes once {@value #RESUME_AT} or fewer are queued. */
    private void taken(int wire) {
        lock.lock();
        try {
            queued -= wire;
            if (paused && queued <= RESUME_AT) {
                paused = false;
                link.unhold();
            }
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------------ force thread

    private void hand(Task task) {
        lock.lock();
        try {
            tasks.add(task);
            moved.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void forceAll() {
        for (Task task; (task = next()) != null; ) {
            switch (task) {
                case Force f -> force(f.slot());
                case Sealing x -> sealed(x.slot(), x.seal());
            }
            lock.lock();
            try {
                moved.signalAll();   // the writer may wait for room under HOLD_AT
            } finally {
                lock.unlock();
            }
        }
    }

    private Task next() {
        lock.lock();
        try {
            while (tasks.isEmpty() && !closed) moved.awaitUninterruptibly();
            return closed ? null : tasks.poll();
        } finally {
            lock.unlock();
        }
    }

    private void force(Slot s) {
        try {
            s.replica.force();
        } catch (IOException e) {   // a replica failed before closes its channel: its force fails, and fail() does nothing more
            fail(s, e);
        }
    }

    private void sealed(Slot s, ReplFrames.Seal x) {
        try {
            s.replica.seal(x.crc32c());
            slots.remove(s.n);
            send(Frames.RECEIPT, new ReplFrames.Receipt(x.address(), x.end(), ReplFrames.SEALED).encode());
        } catch (ReplicaMismatch mismatch) {
            failAs(s, ReplFrames.MISMATCH, mismatch);
        } catch (IOException e) {
            fail(s, e);
        }
    }

    // ------------------------------------------------------------------------------------------------------ either

    private void fail(Slot s, IOException e) {
        failAs(s, ReplFrames.FAILED, e);
    }

    /** The segment failed for {@code reason}: once, its REFUSE sent, its replica detached (its {@code .part} kept), its later frames dropped. */
    private void failAs(Slot s, int reason, IOException e) {
        synchronized (s) {
            if (s.failed) return;
            s.failed = true;
            refuse(address(s), reason, String.valueOf(e.getMessage()));
        }
        LOG.log(System.Logger.Level.WARNING, "replica " + s.n + " of " + owner + "'s journal " + journal + " is refused ("
                + (reason == ReplFrames.FAILED ? "FAILED" : "MISMATCH") + "), its .part kept", e);
        Replica r = s.replica;
        if (r != null) detach(r);
    }

    private void detach(Replica r) {
        try {
            r.detach();
        } catch (IOException e) {   // a close of the file failed: its count is trued already
            LOG.log(System.Logger.Level.WARNING, "a replica of " + owner + "'s journal " + journal + " did not close", e);
        }
    }

    private ReplFrames.Address address(Slot s) {
        return new ReplFrames.Address(owner, journal, s.n);
    }

    private void refuse(ReplFrames.Address a, int reason, String detail) {
        send(Frames.REFUSE, new ReplFrames.Refuse(a, reason, detail).encode());
    }

    /**
     * Hands a RECEIPT or REFUSE to the link (a closed link fails the write: the owner hears nothing more of it); the writer takes
     * no more frames until it went out ({@link #take}).
     */
    private void send(int type, byte[] payload) {
        byte[] f = Frames.frame(type, payload.length);
        System.arraycopy(payload, 0, f, Frames.HEAD, payload.length);
        lock.lock();
        try {
            unsent++;
        } finally {
            lock.unlock();
        }
        link.send(f).onComplete(done -> wentOut());
    }

    /** A send's write completed, or failed (the link closed). */
    private void wentOut() {
        lock.lock();
        try {
            unsent--;
            moved.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
