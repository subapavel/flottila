package io.flottila.runtime.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * The file part of a start (CORE-3 item 9), by CRC32C alone — no tenant key is asked for — in two steps, so a start that
 * refuses changes nothing: {@link #plan} reads and {@link #apply} writes. A segment with a valid trailer is taken as sealed,
 * never rewritten; when its index fails its CRC it is read without the index (WARNING). A segment without one (unsealed, or
 * a torn seal) is scanned record by record (length bounds, CRC32C, a v1 header; a large record, whose CRC32C covers its
 * header alone, must have its every chunk in the file), then cut at its first bad record and sealed over what survived. A
 * segment holding no record (a pre-open, or a tail torn at its first record) is removed. A header that fails its CRC over
 * records, or another journal's header, refuses the start.
 */
final class Recovery {

    private static final System.Logger LOG = System.getLogger(Recovery.class.getName());
    private static final Pattern NAME = Pattern.compile("[1-9][0-9]{0,17}\\.seg");
    /** A scan reads a record in pieces of this size (never more than one piece in memory); at least the longest header. */
    private static final int PIECE = 256 * 1024;

    private record Scan(List<JournalIndexEntry> entries, long end) { }

    /**
     * What the read of segment {@code n} found: sealed ({@code sealed}, read as it is), or scanned ({@code scan}: the whole
     * records from the header on, ending at {@code scan.end()} in a file of {@code size} bytes); neither: no record.
     */
    private record Found(long n, Path file, long size, Journal.Extent sealed, Scan scan) {
        boolean holdsRecords() { return sealed != null || scan != null; }
        long end() { return sealed != null ? sealed.end : scan.end(); }
    }

    /** What a read of the segments found, by number. */
    static final class Plan {
        private final Map<Long, Found> found;

        private Plan(Map<Long, Found> found) {
            this.found = found;
        }

        /** The end of the records each segment holding records will hold once applied: a scanned one's at its cut. */
        Map<Long, Long> ends() {
            Map<Long, Long> ends = new HashMap<>();
            for (Found f : found.values()) if (f.holdsRecords()) ends.put(f.n(), f.end());
            return ends;
        }
    }

    private Recovery() { }

    /** The numbers of the segment files in {@code segments} ({@code <n>.seg}), in ascending order; any other file is left alone. */
    static List<Long> numbers(Path segments) throws IOException {
        try (Stream<Path> list = Files.list(segments)) {
            return list.map(p -> p.getFileName().toString()).filter(name -> NAME.matcher(name).matches())
                    .map(name -> Long.parseLong(name.substring(0, name.length() - 4))).sorted().toList();
        }
    }

    /** Reads segments {@code numbers} of {@code segments}, writing nothing. */
    static Plan plan(Path segments, SegmentFormat format, JournalFiles files, List<Long> numbers) throws IOException {
        Map<Long, Found> found = new HashMap<>();
        for (long n : numbers) {
            Path file = segments.resolve(n + ".seg");
            try (FileChannel ch = files.open(file, Set.of(StandardOpenOption.READ))) {
                found.put(n, read(ch, file, n, format));
            }
        }
        return new Plan(found);
    }

    /** Removes the segments of {@code plan} that hold no record; cuts and seals the scanned ones: the extent of each one left. */
    static Map<Long, Journal.Extent> apply(Plan plan, JournalFiles files, Clock clock) throws IOException {
        Map<Long, Journal.Extent> extents = new HashMap<>();
        for (long n : plan.found.keySet().stream().sorted().toList()) {
            Found f = plan.found.get(n);
            if (!f.holdsRecords()) {
                Files.delete(f.file());
                LOG.log(System.Logger.Level.INFO, "journal segment {0} held no record: removed", n);
            } else if (f.sealed() != null) {
                extents.put(n, f.sealed());
            } else {
                try (FileChannel ch = files.open(f.file(), Set.of(StandardOpenOption.WRITE))) {
                    extents.put(n, seal(ch, f, clock));
                }
            }
        }
        return extents;
    }

    private static Found read(FileChannel ch, Path file, long n, SegmentFormat format) throws IOException {
        int headerLength = format.headerLength();
        long size = ch.size();
        if (size <= headerLength) return new Found(n, file, size, null, null);
        byte[] header = JournalIo.read(ch, 0, headerLength);
        if (!format.crcHolds(header)) {
            throw new JournalCorruptException("journal segment " + n + ": its header fails its CRC32C over " + (size - headerLength)
                    + " bytes of records; the start refuses and cuts nothing (" + file + ")");
        }
        if (!format.ours(header, n)) {
            throw new JournalCorruptException("journal segment " + n + " is not segment " + n + " of journal " + format.journal()
                    + " on this node; the start refuses (" + file + ")");
        }
        SegmentFormat.Trailer t = SegmentFormat.trailer(JournalIo.read(ch, size - SegmentFormat.TRAILER, SegmentFormat.TRAILER));
        if (t != null) return new Found(n, file, size, sealed(ch, n, size, t), null);
        Scan s = scan(ch, n, headerLength, size);
        return new Found(n, file, size, null, s.entries().isEmpty() ? null : s);
    }

    /** A scanned segment: cut after its last whole record (WARNING), then sealed over what survived. */
    private static Journal.Extent seal(FileChannel ch, Found f, Clock clock) throws IOException {
        Scan s = f.scan();
        long n = f.n();
        if (s.end() < f.size()) {
            LOG.log(System.Logger.Level.WARNING, "journal segment {0} cut at {1}: {2} bytes after its last whole record dropped ({3} records kept)",
                    n, s.end(), f.size() - s.end(), s.entries().size());
            ch.truncate(s.end());
        }
        SegmentFormat.Footer footer = SegmentFormat.footer(s.entries(), s.end(), clock.millis());
        ch.position(s.end());
        JournalIo.writeFully(ch, footer.bytes());
        ch.force(false);
        LOG.log(System.Logger.Level.INFO, "journal segment {0} scanned and sealed: {1} records", n, s.entries().size());
        return Journal.Extent.sealed(s.end(), new Journal.Index(footer.indexLength(), footer.indexCrc(), s.entries().size()));
    }

    /** A segment with a valid trailer: read with its index when the index CRC holds, else without it (WARNING). */
    private static Journal.Extent sealed(FileChannel ch, long n, long size, SegmentFormat.Trailer t) throws IOException {
        int indexLength = (int) (size - SegmentFormat.TRAILER - t.indexPosition());
        byte[] index = JournalIo.read(ch, t.indexPosition(), indexLength);
        if (JournalIo.crc(index, 0, indexLength) == t.indexCrc()) {
            return Journal.Extent.sealed(t.indexPosition(), new Journal.Index(indexLength, t.indexCrc(), t.count()));
        }
        LOG.log(System.Logger.Level.WARNING, "journal segment {0}: its footer index fails its CRC32C; its records are read without the index", n);
        return Journal.Extent.sealed(t.indexPosition(), null);
    }

    /** The end of the whole records of segment {@code n} from its header on (a replica's {@code .part}, CORE-5b1): where a cut goes. */
    static long wholeRecords(FileChannel ch, long n, int headerLength, long size) throws IOException {
        return scan(ch, n, headerLength, size).end();
    }

    /**
     * Whole records from the header on, up to the first that runs past the file, fails its CRC32C or is not a v1 record.
     * The CRC32C covers what a record's {@code length} field says: a body record whole, a large record's header.
     */
    private static Scan scan(FileChannel ch, long n, int headerLength, long size) throws IOException {
        List<JournalIndexEntry> entries = new ArrayList<>();
        byte[] piece = new byte[PIECE];
        long at = headerLength;
        while (size - at >= RecordFormat.MIN_LENGTH) {
            ByteBuffer head = ByteBuffer.wrap(JournalIo.read(ch, at, 8));
            int stored = head.getInt(0);
            long covered = Integer.toUnsignedLong(head.getInt(4));
            if (covered < RecordFormat.FIXED || covered > size - at) break;
            int first = (int) Math.min(covered, PIECE);
            JournalIo.read(ch, at, piece, first);
            RecordFormat.Header h = RecordFormat.header(piece, first);
            CRC32C crc = new CRC32C();
            crc.update(piece, 4, first - 4);
            for (long off = first; off < covered; ) {
                int k = (int) Math.min(PIECE, covered - off);
                JournalIo.read(ch, at + off, piece, k);
                crc.update(piece, 0, k);
                off += k;
            }
            if ((int) crc.getValue() != stored || h == null || h.length() > size - at) break;
            entries.add(new JournalIndexEntry(h.messageId(), h.step(), h.part(), new JournalRef(n, at, h.length())));
            at += h.length();
        }
        return new Scan(entries, at);
    }
}
