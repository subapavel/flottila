package io.flottila.runtime.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The segments of other nodes' journals this node holds (CORE-5b1 "Holder disk"):
 * {@code $FLOTTILA_JOURNAL_DIR/replicas/<owner>/<journal id>/<n>.part} while a replica is written, {@code <n>.seg} once sealed,
 * byte for byte the owner's {@code segments/<n>.seg}. Their files and reservations count in the journal's bytes
 * ({@link Journal#bytes()}) beside its own segments: an OPEN reserves its {@code length} and is refused
 * ({@link JournalFullException}, nothing created) when the replicas' bytes and it would pass half the intake edge
 * (max − max/16), or the journal's bytes and it the edge itself, so the holder's own intake keeps at least the other half.
 *
 * <p>A start ({@link Journal#open}) counts every replica file in its room check as it finds it, then checks each {@code .part}
 * against its header: one no longer than its header whose header is not the owner's is unlinked (WARNING); a longer one is kept
 * as it is (ERROR) and never served — an OPEN of it is a {@link ReplicaMismatch}; one with a valid trailer is renamed
 * {@code .seg}; any other is cut after its last whole record (CRC32C scan) and stays {@code .part}, never sealed here.
 *
 * @since 0.1
 */
public final class Replicas {

    /**
     * A node id ({@code FLOTTILA_NODE_ID}, a repl link's owner): a DNS label of at most 63 lower-case letters, digits and inner
     * dashes, so an owner is one directory under {@code replicas/}. The one pattern NodeConfig, PeerServer and {@link #open} check.
     */
    public static final Pattern NODE_ID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private static final System.Logger LOG = System.getLogger(Replicas.class.getName());
    private static final Pattern FILE = Pattern.compile("([1-9][0-9]{0,17})\\.(part|seg)");
    private static final Pattern JOURNAL = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Journal journal;
    private final JournalFiles files;
    private final Path root;
    private final Set<Replica> open = ConcurrentHashMap.newKeySet();

    Replicas(Journal journal, JournalFiles files, Path root) {
        this.journal = journal;
        this.files = files;
        this.root = root;
    }

    /**
     * Opens segment {@code n} of {@code owner}'s journal {@code journalId} for its {@code header} and up to {@code length} bytes:
     * creates its {@code .part} (its directories durably), writes the header, forces it and its directory. A header-only
     * {@code .part} of exactly this header (a link closed after its OPEN) is opened again: forced, its directory forced.
     *
     * @param owner the owner's node id, a DNS label ({@link #NODE_ID}; the repl link checked its HELLO's)
     * @throws IllegalArgumentException {@code owner} is not a DNS label, {@code header} is not the owner's header of segment n, or
     *                                  {@code length} is below it; nothing reserved or created
     * @throws ReplicaMismatch          n is held sealed, or its {@code .part} is not exactly {@code header}; nothing changed
     * @throws JournalFullException     the replicas or the journal have no room for {@code length}; nothing created
     * @throws IOException              a create, write or force failed: the {@code .part} kept (CORE-5b1 C2), counted at its size
     */
    public Replica open(String owner, UUID journalId, long n, long length, byte[] header) throws IOException {
        if (!NODE_ID.matcher(owner).matches()) {   // a path element below: never "..", never "a/b"
            throw new IllegalArgumentException("an OPEN of a replica of node '" + owner + "': a node id is a DNS label of at most 63 lower-case letters, digits and inner dashes");
        }
        SegmentFormat format = new SegmentFormat(journalId, owner);
        if (header.length != format.headerLength() || !format.ours(header, n)) {
            throw new IllegalArgumentException("an OPEN of segment " + n + " of " + owner + "'s journal " + journalId + " whose header of "
                    + header.length + " bytes is not that segment's (its CRC32C, its journal, its owner or its n)");
        }
        if (length < header.length) throw new IllegalArgumentException("an OPEN of " + length + " bytes, below its header's " + header.length);
        Path dir = root.resolve(owner).resolve(journalId.toString());
        if (Files.exists(dir.resolve(n + ".seg"))) throw new ReplicaMismatch("segment " + n + " is held sealed: " + dir.resolve(n + ".seg"));
        Path part = dir.resolve(n + ".part");
        Replica r = Files.exists(part) ? reopen(part, dir, n, length, header) : create(part, dir, n, length, header);
        open.add(r);
        return r;
    }

    /** The bytes the open replicas wrote and have not forced (the force lag), over all of them. */
    public long unforced() {
        long sum = 0;
        for (Replica r : open) sum += r.unforced();
        return sum;
    }

    private Replica create(Path part, Path dir, long n, long length, byte[] header) throws IOException {
        journal.reserveReplica(length);
        JournalIo.Owned ch = new JournalIo.Owned(null);
        try {
            JournalIo.createDirectoriesDurably(files, dir);
            ch = new JournalIo.Owned(files.open(part, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)));
            JournalIo.writeFully(ch.get(), ByteBuffer.wrap(header));
            ch.get().force(false);
            forceDir(dir);
            return new Replica(this, ch.handOn(), dir, n, header, length);
        } catch (IOException | RuntimeException | Error e) {
            failed(ch, length, e);
            throw e;
        }
    }

    /**
     * A create failed with {@code failure}: its file, if it opened one, is closed and counted at the bytes it holds (C2; r1 R2:
     * a torn header write's included) in place of the {@code reserved} bytes; a failing close is suppressed into {@code failure}.
     */
    private void failed(JournalIo.Owned ch, long reserved, Throwable failure) {
        long holds = 0;
        try (ch) {
            if (ch.get() != null) holds = ch.get().size();
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        } finally {
            journal.replicaCounted(holds - reserved);
        }
    }

    private Replica reopen(Path part, Path dir, long n, long length, byte[] header) throws IOException {
        try (JournalIo.Owned ch = new JournalIo.Owned(files.open(part, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)))) {
            long size = ch.get().size();
            if (size != header.length || !Arrays.equals(JournalIo.read(ch.get(), 0, header.length), header)) {
                throw new ReplicaMismatch(part + " holds " + size + " bytes that are not exactly the header of this OPEN");
            }
            long more = length - size;   // its size is counted already
            journal.reserveReplica(more);
            boolean made = false;
            try {
                ch.get().force(false);
                forceDir(dir);
                Replica r = new Replica(this, ch.handOn(), dir, n, header, length);
                made = true;
                return r;
            } finally {
                if (!made) journal.replicaCounted(-more);
            }
        }
    }

    /** A replica's count changed by {@code delta} bytes (unchecked). */
    void counted(long delta) {
        journal.replicaCounted(delta);
    }

    /** A replica was sealed or detached: its force lag is no longer counted. */
    void detached(Replica r) {
        open.remove(r);
    }

    void forceDir(Path dir) throws IOException {
        JournalIo.forceDir(files, dir);
    }

    // ---------------------------------------------------------------------------------------------------- start

    /** The replica files under {@code root} ({@code <owner>/<journal id>/<n>.part|.seg}); any other entry is left alone. */
    private static List<Path> filesUnder(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        for (Path owner : list(root)) {
            for (Path journal : list(owner)) {
                if (!JOURNAL.matcher(journal.getFileName().toString()).matches()) continue;
                for (Path f : list(journal)) if (FILE.matcher(f.getFileName().toString()).matches() && Files.isRegularFile(f)) out.add(f);
            }
        }
        return out;
    }

    /** The directories in {@code dir}, sorted. */
    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }

    /** The bytes of every replica file under {@code root} as a start finds them, before it changes any (its room check). */
    static long found(Path root) throws IOException {
        long sum = 0;
        for (Path f : filesUnder(root)) sum += Files.size(f);
        return sum;
    }

    /**
     * Checks every {@code .part} under {@code root} against its header (see the class comment) and answers the bytes of the
     * replica files left: what they count in the journal's bytes.
     */
    static long recover(JournalFiles files, Path root) throws IOException {
        for (Path f : filesUnder(root)) {
            String name = f.getFileName().toString();
            if (name.endsWith(".part")) recover(files, f, Long.parseLong(name.substring(0, name.length() - ".part".length())));
        }
        return found(root);
    }

    /** What a start does with a {@code .part} once it read it. */
    private enum Fate { KEEP, SEAL, UNLINK }

    private static void recover(JournalFiles files, Path part, long n) throws IOException {
        Path dir = part.getParent();
        Fate fate;
        try (FileChannel ch = files.open(part, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE))) {
            fate = fate(ch, part, n, new SegmentFormat(UUID.fromString(dir.getFileName().toString()), dir.getParent().getFileName().toString()));
        }
        if (fate == Fate.SEAL) {
            Files.move(part, part.resolveSibling(n + ".seg"), StandardCopyOption.ATOMIC_MOVE);
            LOG.log(System.Logger.Level.INFO, "replica {0} has a valid trailer: renamed {1}.seg", part, n);
            JournalIo.forceDir(files, dir);
        } else if (fate == Fate.UNLINK) {
            Files.delete(part);
            LOG.log(System.Logger.Level.WARNING, "replica {0} held no more than a header that is not segment {1}''s of that owner and journal: unlinked", part, n);
            JournalIo.forceDir(files, dir);
        }
    }

    /** The fate of {@code part} (segment {@code n} of {@code format}'s owner and journal); a torn one is cut here, and kept. */
    private static Fate fate(FileChannel ch, Path part, long n, SegmentFormat format) throws IOException {
        int h = format.headerLength();
        long size = ch.size();
        byte[] header = JournalIo.read(ch, 0, (int) Math.min(size, h));
        if (header.length != h || !format.ours(header, n)) {
            if (size <= h) return Fate.UNLINK;
            LOG.log(System.Logger.Level.ERROR, "replica " + part + ": its header is not segment " + n + "'s of that owner and journal, over "
                    + (size - h) + " bytes after it; kept as it is, never served (an OPEN of it is refused MISMATCH)");
            return Fate.KEEP;
        }
        if (size - h >= SegmentFormat.TRAILER && SegmentFormat.trailer(JournalIo.read(ch, size - SegmentFormat.TRAILER, SegmentFormat.TRAILER)) != null) {
            return Fate.SEAL;
        }
        long end = Recovery.wholeRecords(ch, n, h, size);
        if (end < size) {
            LOG.log(System.Logger.Level.WARNING, "replica {0} cut at {1}: {2} bytes after its last whole record dropped; it stays .part", part, end, size - end);
            ch.truncate(end);
            ch.force(false);
        }
        return Fate.KEEP;
    }
}
