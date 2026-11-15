package io.flottila.runtime.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32C;

/**
 * One segment of another node's journal this holder keeps a copy of (CORE-5b1): {@code replicas/<owner>/<journal id>/<n>.part},
 * opened by {@link Replicas#open}, its header written and forced. Its bytes are appended by one thread ({@link #write}, the link's
 * writer) and forced and sealed by one other ({@link #force}, {@link #seal}, the link's force thread) — a seal only once every
 * write before it returned. What it counts in the journal's bytes is the larger of its reservation and its end until it is
 * sealed or {@link #detach detached}, then the file's size.
 *
 * @since 0.1
 */
public final class Replica {

    private final Replicas replicas;
    private final FileChannel channel;
    private final Path dir;
    private final long n;
    private final CRC32C crc = new CRC32C();   // the bytes [0, end): the writer's, then the sealer's once it has the SEAL
    private long end;       // guarded by this: the bytes the file holds
    private long forced;    // guarded by this: the bytes a force returned for
    private long counted;   // guarded by this: what it counts in the journal's bytes
    private boolean detached;   // guarded by this

    Replica(Replicas replicas, FileChannel channel, Path dir, long n, byte[] header, long reserved) {
        this.replicas = replicas;
        this.channel = channel;
        this.dir = dir;
        this.n = n;
        this.crc.update(header);
        this.end = header.length;
        this.forced = header.length;
        this.counted = reserved;
    }

    /** The bytes the file holds: its header and every byte written. */
    public synchronized long end() {
        return end;
    }

    /** The bytes written and not forced yet: 0 once sealed or detached. */
    public synchronized long unforced() {
        return end - forced;
    }

    /**
     * Writes {@code bytes} at {@link #end()} (a pwrite) and answers the new end; bytes past the reservation are counted in the
     * journal's bytes as they come (unchecked: the OPEN reserved what the owner announced).
     */
    public long write(ByteBuffer bytes) throws IOException {
        long at = end();
        int length = bytes.remaining();
        ByteBuffer sent = bytes.duplicate();
        for (long pos = at; bytes.hasRemaining(); pos = at + length - bytes.remaining()) channel.write(bytes, pos);
        crc.update(sent);
        synchronized (this) {
            end = at + length;
            if (end > counted) {
                replicas.counted(end - counted);
                counted = end;
            }
            return end;
        }
    }

    /** Forces the file: every byte written before the call is durable once it returns. */
    public void force() throws IOException {
        long upTo = end();
        channel.force(false);
        synchronized (this) {
            forced = Math.max(forced, upTo);
        }
    }

    /**
     * Seals the replica at its end: {@code crc32c} must be the CRC32C of its bytes [0, end) (checked before anything is changed,
     * else {@link ReplicaMismatch}, the {@code .part} as it is); then the file is forced, renamed {@code <n>.seg} (an atomic move)
     * and its directory forced, and the replica detached, counting its size.
     */
    public void seal(int crc32c) throws IOException {
        int held = (int) crc.getValue();
        if (held != crc32c) {
            throw new ReplicaMismatch("the SEAL of " + dir.resolve(n + ".part") + " names CRC32C " + Integer.toHexString(crc32c) + ", its "
                    + end() + " bytes have " + Integer.toHexString(held));
        }
        channel.force(false);
        Files.move(dir.resolve(n + ".part"), dir.resolve(n + ".seg"), StandardCopyOption.ATOMIC_MOVE);
        replicas.forceDir(dir);
        detach();
    }

    /**
     * Closes the file and trues its count to the bytes it holds (its size, read before the close: a torn write's bytes past
     * {@link #end()} included, r1 R2), the close failing or not (a link closed, a replica failed: the {@code .part} is kept,
     * CORE-5b1 C2); a size that cannot be read counts its end. Nothing is forced. A second call does nothing.
     */
    public void detach() throws IOException {
        synchronized (this) {
            if (detached) return;   // closed: its size is no longer there to read
            detached = true;
        }
        long holds = end();
        try (channel) {
            holds = channel.size();
        } finally {
            synchronized (this) {
                replicas.counted(holds - counted);
                counted = holds;
                forced = end;
            }
            replicas.detached(this);
        }
    }
}
