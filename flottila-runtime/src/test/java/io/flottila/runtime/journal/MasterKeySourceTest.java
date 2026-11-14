package io.flottila.runtime.journal;

import io.flottila.runtime.journal.HeldFiles.Hold;
import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal's master key (D-106, CORE-4c1 packet Scope 2): {@code FLOTTILA_JOURNAL_KEY_FILE} (preferred) or
 * {@code FLOTTILA_JOURNAL_KEY}, each the base64 of exactly 256 bits; both set is refused, neither is refused (no random
 * default) unless {@code FLOTTILA_JOURNAL_KEY_GENERATE=true}, which creates {@code <journal>/MASTER} once — a temporary file
 * of mode 0600, forced, moved into place atomically, its directory forced — and logs a WARNING at every start. No key value
 * appears in a refusal, a log line or the source's {@code toString}.
 *
 * <p>The API these cases compile against (proposed by the test-author, CORE-4c1 handback):
 * {@code MasterKeySource MasterKeySource.fromEnv(Map<String, String> env)} reads the three keys of {@code env} and nothing
 * else; {@code SecretKey load(Path journalDir, JournalFiles files) throws IOException} answers the 32-byte AES key, creating
 * {@code MASTER} through {@code files} when generation is opted in. A refusal is an {@link IllegalArgumentException} naming
 * the key it is about (NodeConfig's rule), thrown by {@code fromEnv} or {@code load} — the cases do not mind which.
 * A key file holds the base64 text, a trailing newline allowed (what {@code openssl rand -base64 32 > file} writes).
 */
class MasterKeySourceTest {

    static final String FILE = "FLOTTILA_JOURNAL_KEY_FILE";
    static final String ENV = "FLOTTILA_JOURNAL_KEY";
    static final String GENERATE = "FLOTTILA_JOURNAL_KEY_GENERATE";

    @TempDir Path tmp;

    private final List<HeldFiles> seams = new ArrayList<>();

    @AfterEach
    void releaseHolds() {
        seams.forEach(HeldFiles::releaseAll);
    }

    /** {@code n} fixed bytes from {@code seed}: a fixture key, never a secret. */
    static byte[] material(long seed, int n) {
        byte[] b = new byte[n];
        new SplittableRandom(seed).nextBytes(b);
        return b;
    }

    static String base64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    private Path journal(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name).resolve("journal"));
    }

    private HeldFiles seam() {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        return files;
    }

    /** A key file holding {@code text} and a newline, outside the journal directory. */
    private Path keyFile(String name, String text) throws IOException {
        return Files.writeString(Files.createDirectories(tmp.resolve("secrets")).resolve(name), text + "\n");
    }

    private static SecretKey load(Map<String, String> env, Path dir, JournalFiles files) throws IOException {
        return MasterKeySource.fromEnv(env).load(dir, files);
    }

    /** The refusal of {@code env}, whichever of {@code fromEnv} and {@code load} makes it. */
    private static IllegalArgumentException refusal(Map<String, String> env, Path dir) {
        return assertThrows(IllegalArgumentException.class, () -> load(env, dir, JournalFiles.real()));
    }

    /** Every message and {@code toString} of {@code t}, its causes and its suppressed ones. */
    static String text(Throwable t) {
        StringBuilder all = new StringBuilder();
        List<Throwable> seen = new ArrayList<>();
        List<Throwable> todo = new ArrayList<>(List.of(t));
        while (!todo.isEmpty()) {
            Throwable x = todo.removeFirst();
            if (x == null || seen.contains(x)) continue;
            seen.add(x);
            all.append(x).append('\n').append(x.getMessage()).append('\n');
            todo.add(x.getCause());
            todo.addAll(Arrays.asList(x.getSuppressed()));
        }
        return all.toString();
    }

    /** {@code text} carries none of {@code secrets}, nor the base64 or hex of {@code keys}. */
    static void assertCarriesNoKey(String text, List<String> secrets, byte[]... keys) {
        for (String s : secrets) assertFalse(text.contains(s), "no key value in: " + text);
        for (byte[] k : keys) {
            assertFalse(text.contains(base64(k)), "no key (base64) in: " + text);
            assertFalse(text.toLowerCase().contains(HexFormat.of().formatHex(k)), "no key (hex) in: " + text);
        }
    }

    private static List<String> formatted(LogCapture log) {
        return log.records().stream().map(r -> r.getLevel() + " " + new SimpleFormatter().formatMessage(r)).toList();
    }

    private static List<Path> listing(Path dir) throws IOException {
        try (Stream<Path> l = Files.list(dir)) {
            return l.sorted().toList();
        }
    }

    enum Source { FILE, ENV }

    private Map<String, String> envWith(Source source, String text) throws IOException {
        Map<String, String> env = new HashMap<>();
        if (source == Source.FILE) env.put(FILE, keyFile("master-" + text.hashCode() + ".b64", text).toString());
        else env.put(ENV, text);
        return env;
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void aBase64KeyOfExactly256BitsIsTheMasterKey(Source source) throws Exception {
        byte[] key = material(1, 32);
        Path dir = journal("ok-" + source);

        SecretKey master = load(envWith(source, base64(key)), dir, JournalFiles.real());

        assertEquals("AES", master.getAlgorithm());
        assertArrayEquals(key, master.getEncoded(), "the decoded 32 bytes");
        assertEquals(List.of(), listing(dir), "a given key writes nothing into the journal directory");
    }

    @Test
    void theSourceShowsNoKeyValue() throws Exception {
        byte[] key = material(2, 32);
        String shown = String.valueOf(MasterKeySource.fromEnv(Map.of(ENV, base64(key))));
        assertCarriesNoKey(shown, List.of(base64(key)), key);
    }

    @Test
    void bothSourcesSetAreRefusedNamingBothAndShowingNeither() throws Exception {
        byte[] fileKey = material(3, 32);
        byte[] envKey = material(4, 32);
        Map<String, String> env = new HashMap<>();
        env.put(FILE, keyFile("both.b64", base64(fileKey)).toString());
        env.put(ENV, base64(envKey));

        IllegalArgumentException e = refusal(env, journal("both"));

        assertTrue(e.getMessage().contains(FILE) && e.getMessage().contains(ENV), "names both keys: " + e.getMessage());
        assertCarriesNoKey(text(e), List.of(base64(fileKey), base64(envKey)), fileKey, envKey);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "false", "TRUE", "yes"})
    void neitherSourceIsRefusedAndNoKeyIsMadeUp(String generate) throws Exception {
        Map<String, String> env = new HashMap<>();
        if (!generate.isEmpty()) env.put(GENERATE, generate);
        Path dir = journal("neither-" + (generate.isEmpty() ? "unset" : generate));
        HeldFiles files = seam();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(env, dir, files));

        boolean optInMalformed = !generate.isEmpty() && !generate.equals("false");
        assertTrue(e.getMessage().contains(ENV) || optInMalformed && e.getMessage().contains(GENERATE), "names what is missing: " + e.getMessage());
        assertEquals(List.of(), listing(dir), "no MASTER and no temporary file: there is no random default (generate " + generate + ")");
        assertEquals(List.of(), files.ops(), "nothing written through the journal's files");
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void aValueThatIsNoBase64IsRefusedWithoutShowingIt(Source source) throws Exception {
        String value = "not*base64!" + base64(material(5, 30));
        IllegalArgumentException e = refusal(envWith(source, value), journal("garbled-" + source));
        assertTrue(e.getMessage().contains(source == Source.FILE ? FILE : ENV), "names the key: " + e.getMessage());
        assertCarriesNoKey(text(e), List.of(value, value.substring(11)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 16, 31, 33, 64})
    void aKeyOfAnyOtherLengthIsRefusedWithoutShowingIt(int bytes) throws Exception {
        for (Source source : Source.values()) {
            byte[] key = material(6 + bytes, bytes);
            IllegalArgumentException e = refusal(envWith(source, base64(key)), journal("length-" + bytes + "-" + source));
            assertTrue(e.getMessage().contains(source == Source.FILE ? FILE : ENV), source + " of " + bytes + " bytes names the key: " + e.getMessage());
            if (bytes > 0) assertCarriesNoKey(text(e), List.of(base64(key)), key);
        }
    }

    @Test
    void aKeyFileThatCannotBeReadIsRefusedNamingItsKey() throws Exception {
        Path missing = tmp.resolve("secrets-nowhere").resolve("master.b64");
        Exception e = assertThrows(Exception.class, () -> load(Map.of(FILE, missing.toString()), journal("missing"), JournalFiles.real()));
        assertTrue(text(e).contains(FILE), "names the key: " + text(e));
    }

    /**
     * The opt-in: the first load creates {@code MASTER} — a temporary file already of mode 0600 when its first byte is
     * written (no window in which another user may read the key), forced after its last write, then moved into place, and the
     * journal directory forced once {@code MASTER} is there — and warns; a second load (the next start) reads the same key,
     * writes nothing and warns again. No log line carries the key.
     */
    @Test
    void aGeneratedKeyIsCreatedOnceMode0600ForcedAndMovedAndWarnsAtEveryLoad() throws Exception {
        Path dir = journal("generated");
        Path master = dir.resolve("MASTER");
        HeldFiles files = seam();
        Map<String, String> env = Map.of(GENERATE, "true");
        Hold firstWrite = files.holdFirstWriteOn("a file under " + dir, p -> p.getParent().equals(dir.toAbsolutePath().normalize()));

        try (LogCapture log = new LogCapture()) {
            Outcome<SecretKey> first = onVirtual(() -> load(env, dir, files));
            firstWrite.awaitReached();
            Path written = firstWrite.file();
            assertTrue(!written.equals(master.toAbsolutePath().normalize()), "the key is written to a temporary file, not to MASTER: " + written);
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(written),
                    "the temporary file is 0600 before its first byte");
            assertFalse(Files.exists(master), "MASTER appears only by the move");
            long beforeDirForce = files.now();
            Hold dirForce = files.holdForceOn(dir, beforeDirForce);
            firstWrite.release();
            dirForce.awaitReached();
            assertTrue(Files.isRegularFile(master), "the directory is forced once MASTER is in place");
            assertFalse(Files.exists(written), "moved, not copied");
            assertTrue(files.forcedAfterItsLastWrite(written), "the temporary file was forced after its last write, before the move: " + files.describe());
            dirForce.release();
            SecretKey key = first.join();

            assertEquals("AES", key.getAlgorithm());
            assertEquals(32, key.getEncoded().length, "256 bits");
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(master), "MASTER is 0600");
            assertEquals(List.of(master), listing(dir), "MASTER alone: the temporary file is gone");
            byte[] stored = Files.readAllBytes(master);
            long afterFirst = files.now();

            SecretKey again = load(env, dir, files);

            assertArrayEquals(key.getEncoded(), again.getEncoded(), "the next start reads the same key");
            assertArrayEquals(stored, Files.readAllBytes(master), "MASTER is never rewritten");
            assertEquals(List.of(), files.ops().stream().filter(op -> op.entered > afterFirst && op.kind == HeldFiles.Kind.WRITE).toList(),
                    "the second load writes nothing");
            List<String> lines = formatted(log);
            List<String> notices = lines.stream().filter(l -> l.startsWith("WARNING ") && l.contains("MASTER")).toList();
            assertEquals(2, notices.size(), "one WARNING naming MASTER at each of the two loads: " + lines);
            assertCarriesNoKey(String.join("\n", lines), List.of(new String(stored, StandardCharsets.ISO_8859_1).strip()), key.getEncoded(), stored);
        }
    }

    @Test
    void aMasterFileIsNoSourceWithoutTheOptIn() throws Exception {
        Path dir = journal("opt-out");
        load(Map.of(GENERATE, "true"), dir, JournalFiles.real());
        byte[] stored = Files.readAllBytes(dir.resolve("MASTER"));

        IllegalArgumentException e = refusal(Map.of(), dir);

        assertTrue(e.getMessage().contains(ENV), e.getMessage());
        assertArrayEquals(stored, Files.readAllBytes(dir.resolve("MASTER")), "the refusal leaves MASTER as it was");
    }

    /** A MASTER that is no 256-bit key is refused, never replaced: a new key would make every record unreadable. */
    @Test
    void aDamagedMasterFileIsRefusedAndNeverReplaced() throws Exception {
        Path dir = journal("damaged");
        load(Map.of(GENERATE, "true"), dir, JournalFiles.real());
        Path master = dir.resolve("MASTER");
        byte[] stored = Files.readAllBytes(master);
        byte[] damaged = Arrays.copyOf(stored, stored.length / 2);
        Files.write(master, damaged);

        Exception e = assertThrows(Exception.class, () -> load(Map.of(GENERATE, "true"), dir, JournalFiles.real()));

        assertTrue(text(e).contains("MASTER"), "names the file: " + text(e));
        assertArrayEquals(damaged, Files.readAllBytes(master), "not replaced by a new key");
        assertCarriesNoKey(text(e), List.of(), stored);
    }
}
