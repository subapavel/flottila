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
 * The file part of a start (CORE-3 item 9), by CRC32C alone — no tenant key is asked for. A segment with a valid trailer
 * is taken as sealed, never rewritten; when its index fails its CRC it is read without the index (WARNING). A segment
 * without one (unsealed, or a torn seal) is scanned record by record (length bounds, CRC32C, a v1 header), cut at its
 * first bad record and sealed over what survived. A segment holding no record (a pre-open, or a tail torn at its first
 * record) is removed. A header that fails its CRC over records, or another journal's header, refuses the start.
 */
final class Recovery {

    private static final System.Logger LOG = System.getLogger(Recovery.class.getName());
    private static final Pattern NAME = Pattern.compile("[1-9][0-9]{0,17}\\.seg");
    /** A scan reads a record in pieces of this size (never more than one piece in memory); at least the longest header. */
    private static final int PIECE = 256 * 1024;

    /** The segments found, by number, and the number the next segment takes (above every number seen). */
    record Result(Map<Long, Journal.Extent> extents, long next) { }

    private record Scan(List<JournalIndexEntry> entries, long end) { }

    private Recovery() { }

    static Result recover(Path segments, SegmentFormat format, JournalFiles files, Clock clock) throws IOException {
        List<Long> numbers;
        try (Stream<Path> list = Files.list(segments)) {
            numbers = list.map(p -> p.getFileName().toString()).filter(name -> NAME.matcher(name).matches())
                    .map(name -> Long.parseLong(name.substring(0, name.length() - 4))).sorted().toList();
        }
        Map<Long, Journal.Extent> extents = new HashMap<>();
        long max = 0;
        for (long n : numbers) {
            max = n;
            Path file = segments.resolve(n + ".seg");
            Journal.Extent e = recover(file, n, format, files, clock);
            if (e == null) {
                Files.delete(file);
                LOG.log(System.Logger.Level.INFO, "journal segment {0} held no record: removed", n);
            } else {
                extents.put(n, e);
            }
        }
        return new Result(extents, max + 1);
    }

    /** The extent of segment {@code n}, or null when it holds no record and goes. */
    private static Journal.Extent recover(Path file, long n, SegmentFormat format, JournalFiles files, Clock clock) throws IOException {
        try (FileChannel ch = files.open(file, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE))) {
            return recover(ch, file, n, format, clock);
        }
    }

    private static Journal.Extent recover(FileChannel ch, Path file, long n, SegmentFormat format, Clock clock) throws IOException {
        int headerLength = format.headerLength();
        long size = ch.size();
        if (size <= headerLength) return null;
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
        if (t != null) return sealed(ch, n, size, t);
        Scan s = scan(ch, n, headerLength, size);
        if (s.entries().isEmpty()) return null;
        if (s.end() < size) {
            LOG.log(System.Logger.Level.WARNING, "journal segment {0} cut at {1}: {2} bytes after its last whole record dropped ({3} records kept)",
                    n, s.end(), size - s.end(), s.entries().size());
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

    /** Whole records from the header on, up to the first that runs past the file, fails its CRC32C or is not a v1 record. */
    private static Scan scan(FileChannel ch, long n, int headerLength, long size) throws IOException {
        List<JournalIndexEntry> entries = new ArrayList<>();
        byte[] piece = new byte[PIECE];
        long at = headerLength;
        while (size - at >= RecordFormat.MIN_LENGTH) {
            ByteBuffer head = ByteBuffer.wrap(JournalIo.read(ch, at, 8));
            int stored = head.getInt(0);
            long length = Integer.toUnsignedLong(head.getInt(4));
            if (length < RecordFormat.MIN_LENGTH || length > size - at) break;
            int first = (int) Math.min(length, PIECE);
            JournalIo.read(ch, at, piece, first);
            RecordFormat.Header h = RecordFormat.header(piece, first, length);
            CRC32C crc = new CRC32C();
            crc.update(piece, 4, first - 4);
            for (long off = first; off < length; ) {
                int k = (int) Math.min(PIECE, length - off);
                JournalIo.read(ch, at + off, piece, k);
                crc.update(piece, 0, k);
                off += k;
            }
            if ((int) crc.getValue() != stored || h == null) break;
            entries.add(new JournalIndexEntry(h.messageId(), h.step(), h.part(), new JournalRef(n, at, (int) length)));
            at += length;
        }
        return new Scan(entries, at);
    }
}
