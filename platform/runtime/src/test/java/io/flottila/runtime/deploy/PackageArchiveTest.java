package io.flottila.runtime.deploy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09d1a (D-059): a package archive is unpacked with {@link ZipFile} into a target directory (here always an existing, empty
 * one), every entry at its path below the target. A refusal — the deploy's 400 — is an {@link IllegalArgumentException}: a file
 * that is no zip, more than 10 000 entries, more than 4 × max actual uncompressed bytes in total whatever the headers claim, an
 * entry that is absolute, duplicate, or resolves outside the target after normalisation. A zip-slip entry is refused before
 * anything is written. Library facts observed on JDK 25 (scratch probe): {@code ZipOutputStream} refuses a duplicate name, but
 * {@code ZipFile} lists and reads both entries of a zip that has one; {@code ZipFile} inflates an entry to its actual size
 * whatever the central directory claims; entry names keep a leading {@code /} and {@code ..} segments.
 */
class PackageArchiveTest {

    static final long MIB = 1024L * 1024;

    @TempDir
    Path tmp;

    // --- fixtures

    /** A zip of {@code entries} in their order; a name ending in {@code /} is a directory entry. */
    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                if (!e.getKey().endsWith("/")) out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static Path write(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes);
        return file;
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code n} empty files {@code e/0} .. {@code e/<n-1>}. */
    static Map<String, byte[]> emptyFiles(int n) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) entries.put("e/" + i, new byte[0]);
        return entries;
    }

    /** {@code zip} with every occurrence of {@code from} (same length) replaced by {@code to}; {@code expected} occurrences must be found. */
    static byte[] replaced(byte[] zip, String from, String to, int expected) {
        byte[] f = utf8(from);
        byte[] t = utf8(to);
        assertEquals(f.length, t.length, "fixture: same-length names keep every offset");
        byte[] out = zip.clone();
        int found = 0;
        for (int i = 0; i + f.length <= out.length; i++) {
            if (Arrays.equals(out, i, i + f.length, f, 0, f.length)) {
                System.arraycopy(t, 0, out, i, t.length);
                found++;
            }
        }
        assertEquals(expected, found, "fixture: occurrences of '" + from + "'");
        return out;
    }

    /** Offsets in a central directory header: compressed size, uncompressed size (ZipFile reads the sizes from here only). */
    static final int COMPRESSED_SIZE = 20, UNCOMPRESSED_SIZE = 24;

    /** {@code zip} with the 32-bit field at {@code offset} of {@code entry}'s central directory header changed by {@code change}. */
    static byte[] patchCentral(byte[] zip, String entry, int offset, LongUnaryOperator change) {
        byte[] out = zip.clone();
        byte[] name = utf8(entry);
        int patched = 0;
        for (int i = 0; i + 46 + name.length <= out.length; i++) {
            if (out[i] != 'P' || out[i + 1] != 'K' || out[i + 2] != 1 || out[i + 3] != 2) continue;
            int nameLength = (out[i + 28] & 0xff) | (out[i + 29] & 0xff) << 8;
            if (nameLength != name.length || !Arrays.equals(out, i + 46, i + 46 + nameLength, name, 0, nameLength)) continue;
            put32(out, i + offset, (int) change.applyAsLong(get32(out, i + offset)));
            patched++;
        }
        assertEquals(1, patched, "fixture: the central directory header of '" + entry + "' patched");
        return out;
    }

    static long get32(byte[] b, int at) {
        long v = 0;
        for (int k = 0; k < 4; k++) v |= (b[at + k] & 0xffL) << (8 * k);
        return v;
    }

    static void put32(byte[] b, int at, int v) {
        for (int k = 0; k < 4; k++) b[at + k] = (byte) (v >>> (8 * k));
    }

    /** Every path below {@code dir} (files and directories, not {@code dir} itself), relative, with {@code /} separators. */
    static Set<String> everything(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> !p.equals(dir)).map(p -> dir.relativize(p).toString().replace('\\', '/')).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    // --- cases

    @Test
    void unpacksEveryEntry() throws Exception {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) i;
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n"));
        files.put("endpoints/in.yaml", utf8("id: in\n"));
        files.put("fixtures/deep/sample.bin", binary);   // no directory entries for fixtures/ or fixtures/deep/
        files.put("empty.txt", new byte[0]);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("endpoints/", new byte[0]);
        entries.putAll(files);
        entries.put("unused/", new byte[0]);              // an empty directory
        Path into = Files.createDirectory(tmp.resolve("into"));

        PackageArchive.unpack(write(tmp.resolve("archive.zip"), zip(entries)), into, MIB);

        assertEquals(new TreeSet<>(List.of("empty.txt", "endpoints", "endpoints/in.yaml", "fixtures", "fixtures/deep", "fixtures/deep/sample.bin",
                "package.yaml", "unused")), everything(into), "every entry at its path, directories included, nothing else");
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            assertArrayEquals(f.getValue(), Files.readAllBytes(into.resolve(f.getKey())), "the bytes of " + f.getKey());
        }
        assertTrue(Files.isDirectory(into.resolve("unused")), "an empty directory entry is a directory");
    }

    static Stream<Arguments> noZips() throws IOException {
        byte[] whole = zip(Map.of("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n")));
        return Stream.of(
                Arguments.of("text", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n")),
                Arguments.of("empty", new byte[0]),
                Arguments.of("a zip cut in half", Arrays.copyOf(whole, whole.length / 2)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noZips")
    void aFileThatIsNoZipIsRefused(String what, byte[] bytes) throws Exception {
        Path file = write(tmp.resolve("archive.zip"), bytes);
        Path into = Files.createDirectory(tmp.resolve("into"));
        assertThrows(IllegalArgumentException.class, () -> PackageArchive.unpack(file, into, MIB), what + " is refused as a bad request");
        assertEquals(Set.of(), everything(into), "nothing written");
    }

    @Test
    void tenThousandEntriesPassTenThousandAndOneDoNot() throws Exception {
        Path ok = Files.createDirectory(tmp.resolve("ok"));
        PackageArchive.unpack(write(tmp.resolve("ten-thousand.zip"), zip(emptyFiles(10_000))), ok, 64 * MIB);
        try (Stream<Path> s = Files.walk(ok)) {
            assertEquals(10_000, s.filter(Files::isRegularFile).count(), "10 000 entries: every one written");
        }
        Path refused = Files.createDirectory(tmp.resolve("refused"));
        Path tooMany = write(tmp.resolve("ten-thousand-and-one.zip"), zip(emptyFiles(10_001)));
        assertThrows(IllegalArgumentException.class, () -> PackageArchive.unpack(tooMany, refused, 64 * MIB), "10 001 entries");
    }

    @Test
    void uncompressedAboveFourTimesMaxIsRefusedWhateverTheHeaderSays() throws Exception {
        long max = 1000;
        Map<String, byte[]> four = new LinkedHashMap<>();
        four.put("a.bin", new byte[2000]);
        four.put("b.bin", new byte[2000]);
        Path exactly = Files.createDirectory(tmp.resolve("exactly"));
        PackageArchive.unpack(write(tmp.resolve("four-times.zip"), zip(four)), exactly, max);
        assertEquals(2000, Files.size(exactly.resolve("a.bin")), "4 × max in total is unpacked");
        assertEquals(2000, Files.size(exactly.resolve("b.bin")), "4 × max in total is unpacked");

        Map<String, byte[]> overByOne = new LinkedHashMap<>();
        overByOne.put("a.bin", new byte[2000]);
        overByOne.put("b.bin", new byte[2001]);
        Path over = write(tmp.resolve("four-times-and-one.zip"), zip(overByOne));
        Path overInto = Files.createDirectory(tmp.resolve("over"));
        assertThrows(IllegalArgumentException.class, () -> PackageArchive.unpack(over, overInto, max), "4 × max + 1 in total, each entry below 4 × max");
        assertEquals(Set.of(), everything(overInto), "nothing written, not even a.bin, which fits on its own");

        Map<String, byte[]> lying = new LinkedHashMap<>();
        lying.put("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n"));   // a good entry first
        lying.put("big.bin", new byte[4001]);
        Path liar = write(tmp.resolve("lying.zip"), patchCentral(zip(lying), "big.bin", UNCOMPRESSED_SIZE, size -> 1));
        try (ZipFile z = new ZipFile(liar.toFile())) {
            assertEquals(1, z.getEntry("big.bin").getSize(), "fixture: the header claims one byte");
            try (var in = z.getInputStream(z.getEntry("big.bin"))) {
                assertEquals(4001, in.transferTo(java.io.OutputStream.nullOutputStream()), "fixture: ZipFile inflates the actual 4001");
            }
        }
        Path liarInto = Files.createDirectory(tmp.resolve("liar"));
        assertThrows(IllegalArgumentException.class, () -> PackageArchive.unpack(liar, liarInto, max), "4 × max + 1 actual bytes under a header claiming 1");
        assertEquals(Set.of(), everything(liarInto), "nothing written, not even the good entry before it");
    }

    /** {@code {tmp}} is the case's temp dir and {@code {into}} the target: absolute names that stay inside it, never in {@code /}. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"../x", "a/../../x", "{tmp}/abs", "{into}/x"})
    void aZipSlipEntryIsRefusedAndNothingIsWritten(String pattern) throws Exception {
        Path into = Files.createDirectory(tmp.resolve("into"));
        String name = pattern.replace("{tmp}", tmp.toAbsolutePath().toString()).replace("{into}", into.toAbsolutePath().toString());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n"));   // a good entry first
        entries.put(name, utf8("escaped"));
        Path zip = write(tmp.resolve("archive.zip"), zip(entries));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> PackageArchive.unpack(zip, into, MIB));

        assertTrue(String.valueOf(refused.getMessage()).contains(name), "the refusal names the entry '" + name + "': " + refused.getMessage());
        assertEquals(Set.of(), everything(into), "nothing written into the target, not even the good entry before the bad one");
        assertEquals(Set.of("archive.zip", "into"), everything(tmp), "nothing written beside the target");
    }

    @Test
    void aDuplicateEntryIsRefused() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n"));   // a good entry first
        entries.put("flows/a.yaml", utf8("first"));
        entries.put("flows/b.yaml", utf8("second"));
        Path zip = write(tmp.resolve("archive.zip"), replaced(zip(entries), "flows/b.yaml", "flows/a.yaml", 2));
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertEquals(List.of("package.yaml", "flows/a.yaml", "flows/a.yaml"), z.stream().map(ZipEntry::getName).toList(), "fixture: two entries of one name");
        }
        Path into = Files.createDirectory(tmp.resolve("into"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> PackageArchive.unpack(zip, into, MIB));

        assertTrue(String.valueOf(refused.getMessage()).contains("flows/a.yaml"), "the refusal names the entry: " + refused.getMessage());
        assertEquals(Set.of(), everything(into), "nothing written, not even the good entry or the first of the two");
    }

    /**
     * Round 3 (d1a review round 1): an entry whose data ends before its deflate stream does (the central directory, intact, says
     * its compressed data is half as long as it is) makes ZipFile throw EOFException, an IOException that is no ZipException. It
     * is a refusal like any other bad archive, found before anything is written.
     */
    @Test
    void aTruncatedEntryIsRefusedAsBadRequest() throws Exception {
        byte[] random = new byte[20_000];
        new Random(7).nextBytes(random);   // incompressible: the deflate stream is about as long as the data
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n"));   // a good entry first
        entries.put("fixtures/cut.bin", random);
        Path zip = write(tmp.resolve("archive.zip"), patchCentral(zip(entries), "fixtures/cut.bin", COMPRESSED_SIZE, size -> size / 2));
        try (ZipFile z = new ZipFile(zip.toFile()); var in = z.getInputStream(z.getEntry("fixtures/cut.bin"))) {
            assertThrows(EOFException.class, () -> in.transferTo(OutputStream.nullOutputStream()), "fixture: ZipFile reads the entry cut short");
        }
        Path into = Files.createDirectory(tmp.resolve("into"));

        assertThrows(IllegalArgumentException.class, () -> PackageArchive.unpack(zip, into, MIB), "a truncated entry is a bad request");

        assertEquals(Set.of(), everything(into), "nothing written, not even the good entry before it");
    }

    /** Round 3 (d1a review round 1): a file {@code a} and a file {@code a/b} cannot both exist; either order is refused before any write. */
    @ParameterizedTest(name = "{0} then {1}")
    @CsvSource({"a, a/b", "a/b, a"})
    void aFileDirectoryClashIsRefusedBeforeAnyWrite(String first, String second) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n"));   // a good entry first
        entries.put(first, utf8("first"));
        entries.put(second, utf8("second"));
        Path zip = write(tmp.resolve("archive.zip"), zip(entries));
        Path into = Files.createDirectory(tmp.resolve("into"));

        assertThrows(IllegalArgumentException.class, () -> PackageArchive.unpack(zip, into, MIB), "a file where another entry needs a directory");

        assertEquals(Set.of(), everything(into), "refused in the check, before any write");
    }

    /**
     * Carried into d1b (d1a review round 2): a file entry that resolves to the target itself ({@code .}, {@code a/..} without a
     * trailing slash) is refused in the check pass, as a bad request naming the entry — not a FileAlreadyExistsException at the
     * write (a 500) — and nothing is written, not even the good entry before it.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {".", "a/..", "a/b/../.."})
    void aFileEntryThatIsTheTargetItselfIsRefusedBeforeAnyWrite(String name) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n"));   // a good entry first
        entries.put(name, utf8("the target itself"));
        Path zip = write(tmp.resolve("archive.zip"), zip(entries));
        Path into = Files.createDirectory(tmp.resolve("into"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> PackageArchive.unpack(zip, into, MIB),
                "a file entry '" + name + "' that is the target directory itself");

        assertTrue(String.valueOf(refused.getMessage()).contains(name), "the refusal names the entry '" + name + "': " + refused.getMessage());
        assertEquals(Set.of(), everything(into), "refused in the check, before any write");
        assertEquals(Set.of("archive.zip", "into"), everything(tmp), "nothing written beside the target");
    }

    /** P1-09d1b (implementer): a directory entry that is the target itself ({@code ./}, {@code a/../}) names a directory that exists: harmless, unpacked. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"./", "a/../"})
    void aDirectoryEntryThatIsTheTargetItselfUnpacks(String name) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name, new byte[0]);
        entries.put("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n"));
        Path zip = write(tmp.resolve("archive.zip"), zip(entries));
        Path into = Files.createDirectory(tmp.resolve("into"));

        PackageArchive.unpack(zip, into, MIB);

        assertEquals(Set.of("package.yaml"), everything(into));
    }

    /**
     * Round 2: the target given as {@code <tmp>/x/../into} (both directories exist, so the operating system resolves it too) is the
     * same directory as {@code <tmp>/into}: every entry lands below it, nothing below {@code x}. A guard that compares normalised
     * entry paths with the target as given refuses every entry.
     */
    @Test
    void anUnnormalizedTargetStillUnpacks() throws Exception {
        Files.createDirectory(tmp.resolve("x"));
        Path normalized = Files.createDirectory(tmp.resolve("into"));
        Path into = tmp.resolve("x").resolve("..").resolve("into");
        assertTrue(!into.equals(into.normalize()) && into.normalize().equals(normalized), "fixture: " + into + " is the target, unnormalised");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("package.yaml", utf8("schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n"));
        entries.put("flows/orders.yaml", utf8("id: orders\n"));
        entries.put("fixtures/", new byte[0]);

        PackageArchive.unpack(write(tmp.resolve("archive.zip"), zip(entries)), into, MIB);

        assertEquals(new TreeSet<>(List.of("fixtures", "flows", "flows/orders.yaml", "package.yaml")), everything(normalized), "every entry below the target");
        assertArrayEquals(entries.get("package.yaml"), Files.readAllBytes(normalized.resolve("package.yaml")));
        assertArrayEquals(entries.get("flows/orders.yaml"), Files.readAllBytes(normalized.resolve("flows/orders.yaml")));
        assertEquals(Set.of(), everything(tmp.resolve("x")), "nothing below x");
    }

    // --- property

    @Provide
    Arbitrary<List<String>> segments() {
        return Arbitraries.of("a", "b", "..", ".", "t", "t2").list().ofMinSize(1).ofMaxSize(6);
    }

    /**
     * Any entry name of up to six segments ({@code ..}, {@code .}, the target's own name {@code t}, a sibling {@code t2} that
     * shares its prefix), relative or absolute, file or directory: after the unpack nothing exists outside the target that was
     * not there before. The target sits seven levels below the try's root, so every escape lands where the walk sees it.
     * An absolute or escaping name is refused; a name of plain segments unpacks at its path; a name with {@code .} or {@code ..}
     * that stays inside may unpack, be refused, or fail on the file system — never anything else.
     */
    @Property(tries = 500)
    void noEntryNameWritesOutsideTheTarget(@ForAll("segments") List<String> segments, @ForAll boolean absolute, @ForAll boolean directory) throws Exception {
        Path root = Files.createTempDirectory("package-archive-property-");
        try {
            Path parent = Files.createDirectories(root.resolve("d1/d2/d3/d4/d5/d6"));
            Path into = Files.createDirectory(parent.resolve("t"));
            String name = (absolute ? parent.toAbsolutePath() + "/" : "") + String.join("/", segments) + (directory ? "/" : "");
            byte[] payload = utf8("payload of " + name);
            Path zip = write(root.resolve("archive.zip"), zip(Map.of(name, payload)));
            Set<Path> before;
            try (Stream<Path> s = Files.walk(root)) {
                before = s.collect(Collectors.toSet());
            }

            Exception thrown = null;
            try {
                PackageArchive.unpack(zip, into, MIB);
            } catch (Exception e) {
                thrown = e;
            }

            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.toList()) {
                    assertTrue(before.contains(p) || p.startsWith(into),
                            "entry '" + name + "' wrote " + p + " outside the target " + into + (thrown == null ? "" : " (and threw " + thrown + ")"));
                }
            }
            Path dest = (absolute ? Path.of(name) : into.resolve(name)).normalize();
            boolean plain = segments.stream().noneMatch(s -> s.equals(".") || s.equals(".."));
            if (absolute || !dest.startsWith(into)) {
                assertTrue(thrown instanceof IllegalArgumentException, "entry '" + name + "' is refused, was " + (thrown == null ? "unpacked" : thrown));
            } else if (plain) {
                if (thrown != null) throw new AssertionError("a plain entry '" + name + "' unpacks", thrown);
                if (directory) assertTrue(Files.isDirectory(dest), "directory entry '" + name + "' at " + dest);
                else assertArrayEquals(payload, Files.readAllBytes(dest), "entry '" + name + "' at " + dest);
            } else if (thrown != null && !(thrown instanceof IllegalArgumentException) && !(thrown instanceof IOException)
                    && !(thrown instanceof UncheckedIOException)) {
                throw new AssertionError("entry '" + name + "' stays inside the target: it unpacks, is refused, or fails on the file system", thrown);
            }
        } finally {
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
