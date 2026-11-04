package io.flottila.runtime.journal;

import io.flottila.api.Hasher;
import io.flottila.runtime.journal.SegmentRows.Pin;
import io.flottila.runtime.journal.SegmentRows.Replacement;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The checks of a journal's start that come before it changes anything (CORE-3 item 9, review r2; CORE-4c3): the room on its
 * file system, its identity, and the committed pins into records it does not hold — and the losses an operator accepted. A
 * loss is refused naming what it gives up (at most {@value #NAMED} items, then their count) and printing
 * {@code <journal id>:<digest>}, the digest a hash over the node, that journal and the sorted given-up items; a refusal
 * changes nothing on disk or in Postgres (but the {@code NODE} and row a first start or a registration wrote before a pins
 * refusal, {@link Journal#open}). {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} set to exactly that value — and not to one
 * {@code journal_losses} recorded already, which accepts nothing new (WARNING) — gives the loss up in one transaction
 * ({@link SegmentRows#giveUp}) before the journal opens for appends, each item an ERROR.
 */
final class JournalStart {

    private static final System.Logger LOG = System.getLogger(JournalStart.class.getName());
    /** A refusal names at most this many given-up items, then their count. */
    static final int NAMED = 20;
    /** The journal id a refusal prints when no file names one (a segment file too short for its header's id). */
    static final UUID NO_ID = new UUID(0, 0);
    private static final String ACCEPT = "FLOTTILA_JOURNAL_ACCEPT_LOSS";

    /** The journal a start goes on with, and whether it replaced one given up whole (whose files are no longer the journal's). */
    record Identity(UUID id, boolean replaced) { }

    private final JournalConfig config;
    private final JournalFiles files;
    private final SegmentRows rows;
    private final Path dir;
    private final String node;

    JournalStart(JournalConfig config, JournalFiles files, SegmentRows rows) {
        this.config = config;
        this.files = files;
        this.rows = rows;
        this.dir = config.dir();
        this.node = config.node();
    }

    /**
     * Refuses a file system that cannot hold the journal up to its bound: its usable space and the bytes of the segment files
     * {@code numbers} together below {@code FLOTTILA_JOURNAL_MAX_BYTES} (no ENOSPC fail-stop crash loop).
     */
    void requireRoom(Path segments, List<Long> numbers) throws IOException {
        long held = 0;
        for (long n : numbers) held += Files.size(segments.resolve(n + ".seg"));
        long usable = files.fileStore(dir).getUsableSpace();
        long room = Math.max(usable + held, usable);   // a usable space near Long.MAX_VALUE (unknown) does not overflow
        if (room < config.maxBytes()) {
            throw new IllegalStateException("the file system of the journal in " + dir + " cannot hold FLOTTILA_JOURNAL_MAX_BYTES " + config.maxBytes()
                    + ": " + usable + " bytes usable and " + held + " bytes of the journal, " + room + " in all; refused, nothing changed "
                    + "(free space, or lower FLOTTILA_JOURNAL_MAX_BYTES)");
        }
    }

    /**
     * The journal id: {@code NODE}'s, checked against the node's registered one. A first start (neither) writes {@code NODE}
     * (tmp, force, atomic move, directory force) with the id {@code journalIds} gives, then registers it; a {@code NODE} without
     * its row registers its id. A journal lost by identity — a row without {@code NODE}, a {@code NODE} naming another journal,
     * or no row while {@code segments/} holds segment files ({@code NODE} there or not, rows kept: a wrong or re-provisioned
     * database must never erase a journal) — is a loss of every pin into it: refused before anything is written, unless
     * accepted; then a new journal replaces it ({@code NODE} first, then the transaction that gives its pins up, replaces the
     * row and drops its segment rows), so a crash in between is refused by the same value again. A write, force or move of
     * {@code NODE} that fails unlinks its tmp and registers nothing.
     */
    Identity identity(List<Long> numbers, boolean rowsKept, Supplier<UUID> journalIds) throws IOException {
        Path file = dir.resolve("NODE");
        UUID found = Files.exists(file) ? identityOf(file) : null;
        UUID registered = rows.journalOf(node).await();
        String lost = null;
        UUID given = registered;
        if (registered != null && !registered.equals(found)) {
            lost = found == null
                    ? "journal_nodes names journal " + registered + " for node " + node + ", but its NODE file is not in " + dir + " (a journal lost or moved)"
                    : "the NODE file in " + dir + " names journal " + found + ", journal_nodes names journal " + registered + " for node " + node
                            + " (another journal)";
        } else if (registered == null && rowsKept && !numbers.isEmpty()) {
            given = journalIn(dir.resolve("segments").resolve(numbers.getFirst() + ".seg"));
            lost = (found == null ? "no NODE file is in " + dir + " and journal_nodes registers no journal for node " + node
                    : "the NODE file in " + dir + " names journal " + found + ", which journal_nodes does not register for node " + node)
                    + ", and segments/ holds " + numbers.size() + " segment file(s) of journal " + given
                    + " (a lost NODE file, or a wrong or re-provisioned database? start the node on the database of its journal)";
        }
        if (lost != null) {
            List<Pin> pins = rows.pinsBeyond(node, Map.of()).await();
            String digest = settle(given, pins, lost);
            UUID now = Objects.requireNonNull(journalIds.get(), "the journal id source answered null");
            writeNode(file, now);
            rows.giveUp(node, given, digest, pins, new Replacement(registered, now)).await();
            gaveUp(given + ":" + digest, pins);
            LOG.log(System.Logger.Level.ERROR, "journal " + given + " of node " + node + " was given up by " + ACCEPT + ": journal " + now
                    + " replaces it in " + dir);
            return new Identity(now, true);
        }
        UUID id = found;
        if (id == null) {
            id = Objects.requireNonNull(journalIds.get(), "the journal id source answered null");
            writeNode(file, id);
        }
        if (registered == null) rows.register(node, id).await();
        return new Identity(id, false);
    }

    /**
     * The committed pins of the journal {@code id} beyond the records {@code ends} holds (a segment file gone, or a pin at or
     * past a scanned segment's cut): none goes on; else refused, or given up when accepted.
     */
    void pins(UUID id, Map<Long, Long> ends) {
        List<Pin> pins = rows.pinsBeyond(node, ends).await();
        if (pins.isEmpty()) {
            stale();
            return;
        }
        String digest = settle(id, pins, "the journal in " + dir + " of node " + node + " does not hold every record its committed rows name "
                + "(a segment file gone, or a record at or past a segment's cut)");
        rows.giveUp(node, id, digest, pins, null).await();
        gaveUp(id + ":" + digest, pins);
    }

    /** The digest of the loss of {@code pins} from {@code journal} when {@value #ACCEPT} accepts exactly it; else the refusal. */
    private String settle(UUID journal, List<Pin> pins, String what) {
        List<String> items = pins.stream().map(Pin::item).sorted().toList();
        String digest = digest(journal, items);
        String value = journal + ":" + digest;
        if (!stale() && config.acceptLoss().equals(Optional.of(value))) return digest;
        StringBuilder b = new StringBuilder(what).append(": refused, nothing changed. ").append(items.size()).append(" committed item(s) are given up with it");
        if (!items.isEmpty()) b.append(": ").append(String.join("; ", items.subList(0, Math.min(NAMED, items.size()))));
        if (items.size() > NAMED) b.append("; and ").append(items.size() - NAMED).append(" more");
        b.append(". To give them up — work items held, never run; their bodies read as \"body lost by ACCEPT_LOSS\" — start once with ")
                .append(ACCEPT).append('=').append(value);
        throw new IllegalStateException(b.toString());
    }

    /** True when {@value #ACCEPT} is a value {@code journal_losses} recorded already: it accepts nothing new (WARNING). */
    private boolean stale() {
        Optional<String> given = config.acceptLoss();
        boolean recorded = given.isPresent() && rows.accepted(node, given.get()).await();
        if (recorded) {
            LOG.log(System.Logger.Level.WARNING, ACCEPT + "=" + given.get() + " is a loss of node " + node + " accepted already: it accepts nothing "
                    + "new (a later loss is refused printing a value of its own); unset it");
        }
        return recorded;
    }

    /** One ERROR per item given up. */
    private void gaveUp(String value, List<Pin> pins) {
        for (Pin p : pins) {
            LOG.log(System.Logger.Level.ERROR, "given up by " + ACCEPT + "=" + value + ": " + p.item() + " of node " + node
                    + (p.kept() ? ": its body reads as \"body lost by ACCEPT_LOSS\"" : ": the work item is held, never run, never parked"));
        }
    }

    /** {@code <journal>:<digest>}'s digest: the first 64 bits of SHA-256 ({@link Hasher#sha256()}) over the node, the journal and the sorted items, in hex. */
    private String digest(UUID journal, List<String> items) {
        byte[] text = ("flottila journal loss v1\n" + node + "\n" + journal + "\n" + String.join("\n", items)).getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().toHexDigits(Hasher.sha256().hash(text));
    }

    /** The journal id in the header of {@code segment}, or {@link #NO_ID} when the file is too short to hold one. */
    private UUID journalIn(Path segment) throws IOException {
        try (FileChannel ch = files.open(segment, Set.of(StandardOpenOption.READ))) {
            UUID id = SegmentFormat.journalOf(JournalIo.read(ch, 0, (int) Math.min(ch.size(), SegmentFormat.ID_END)));
            return id == null ? NO_ID : id;
        }
    }

    /** The journal id {@code file} names, once it is this node's identity file. */
    private UUID identityOf(Path file) throws IOException {
        byte[] found = Files.readAllBytes(file);
        UUID id = SegmentFormat.journalOf(found);
        if (id == null || !Arrays.equals(found, new SegmentFormat(id, node).identity())) {
            throw new IllegalStateException("the NODE file in " + dir + " is not the journal identity of node " + node
                    + " (another node's, or damaged): refused");
        }
        return id;
    }

    /** Writes {@code NODE} naming {@code id}: a tmp, its force, an atomic move over any {@code NODE} there, the directory force. */
    private void writeNode(Path file, UUID id) throws IOException {
        Path tmp = dir.resolve("NODE.tmp");
        try {
            try (FileChannel ch = files.open(tmp, Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                JournalIo.writeFully(ch, ByteBuffer.wrap(new SegmentFormat(id, node).identity()));
                ch.force(false);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error e) {
            JournalIo.unlinkAfter(tmp, e);   // no NODE and no row: the next start begins the journal again
            throw e;
        }
        JournalIo.forceDir(files, dir);
    }
}
