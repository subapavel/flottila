package io.flottila.runtime.journal;

import io.flottila.runtime.journal.HeldFiles.Hold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.MasterKeySourceTest.ENV;
import static io.flottila.runtime.journal.MasterKeySourceTest.FILE;
import static io.flottila.runtime.journal.MasterKeySourceTest.GENERATE;
import static io.flottila.runtime.journal.MasterKeySourceTest.assertCarriesNoKey;
import static io.flottila.runtime.journal.MasterKeySourceTest.base64;
import static io.flottila.runtime.journal.MasterKeySourceTest.material;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The master key source beyond the packet's cases (CORE-4c1, D-106; the implementer's): the opt-in beside a given key is
 * refused like both sources (architect's decision), and an opt-in other than {@code true} or {@code false} is refused, never
 * read as a no; an empty variable counts as not set; a generated key whose write fails leaves no file behind and the next
 * load makes one, as does the load after a crash that left the key's file created but empty; two first loads at once end
 * with one key, the one in {@code MASTER}; two sources are equal exactly when they name the same source (a
 * {@code NodeConfig} is a record: its equality covers them); the text of a source names its kind, never a key value.
 */
class MasterKeySourceEnvTest {

    @TempDir Path tmp;

    private final List<HeldFiles> seams = new ArrayList<>();

    @AfterEach
    void releaseHolds() {
        seams.forEach(HeldFiles::releaseAll);
    }

    private Path journal(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name).resolve("journal"));
    }

    private static List<Path> listing(Path dir) throws IOException {
        try (Stream<Path> l = Files.list(dir)) {
            return l.sorted().toList();
        }
    }

    enum Given { FILE, ENV }

    private Map<String, String> given(Given source, byte[] key) throws IOException {
        Map<String, String> env = new HashMap<>();
        if (source == Given.FILE) {
            env.put(FILE, Files.writeString(Files.createDirectories(tmp.resolve("secrets")).resolve("master.b64"), base64(key) + "\n").toString());
        } else {
            env.put(ENV, base64(key));
        }
        return env;
    }

    @ParameterizedTest
    @EnumSource(Given.class)
    void theOptInBesideAGivenKeyIsRefusedNamingBothAndShowingNoKey(Given source) throws Exception {
        byte[] key = material(31, 32);
        Map<String, String> env = given(source, key);
        env.put(GENERATE, "true");
        Path dir = journal("beside-" + source);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MasterKeySource.fromEnv(env).load(dir, JournalFiles.real()));

        String named = source == Given.FILE ? FILE : ENV;
        assertTrue(e.getMessage().contains(GENERATE) && e.getMessage().contains(named), "names the opt-in and " + named + ": " + e.getMessage());
        assertCarriesNoKey(MasterKeySourceTest.text(e), List.of(base64(key)), key);
        assertEquals(List.of(), listing(dir), "no MASTER is made");

        env.put(GENERATE, "false");
        assertArrayEquals(key, MasterKeySource.fromEnv(env).load(dir, JournalFiles.real()).getEncoded(), "opted out, the given key is the master key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "TRUE", "1"})
    void anOptInOtherThanTrueOrFalseIsRefusedNamingItBesideAGivenKeyToo(String optIn) {
        byte[] key = material(32, 32);
        Map<String, String> env = Map.of(ENV, base64(key), GENERATE, optIn);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MasterKeySource.fromEnv(env));

        assertTrue(e.getMessage().contains(GENERATE), "names the opt-in: " + e.getMessage());
        assertCarriesNoKey(MasterKeySourceTest.text(e), List.of(base64(key)), key);
    }

    @Test
    void anEmptyVariableCountsAsNotSet() throws Exception {
        Map<String, String> none = new HashMap<>(Map.of(FILE, "", ENV, "", GENERATE, ""));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MasterKeySource.fromEnv(none).load(journal("empty"), JournalFiles.real()));
        assertTrue(e.getMessage().contains(ENV), "no key, not two keys nor a malformed opt-in: " + e.getMessage());

        Map<String, String> generated = new HashMap<>(Map.of(FILE, "", ENV, "", GENERATE, "true"));
        Path dir = journal("empty-generated");
        SecretKey key = MasterKeySource.fromEnv(generated).load(dir, JournalFiles.real());
        assertEquals(32, key.getEncoded().length, "an empty key beside the opt-in is no given key: the key is generated");
        assertTrue(Files.isRegularFile(dir.resolve("MASTER")));
    }

    @Test
    void aGeneratedKeyWhoseWriteFailsLeavesNoFileAndTheNextLoadMakesOne() throws Exception {
        Path dir = journal("write-fails");
        HeldFiles files = new HeldFiles();
        seams.add(files);
        Hold write = files.holdFirstWriteOn("a file under " + dir, p -> p.getParent().equals(dir.toAbsolutePath().normalize()));
        IOException eio = new IOException("ENOSPC on the generated key (test)");
        write.fail(eio);
        Map<String, String> env = Map.of(GENERATE, "true");

        IOException e = assertThrows(IOException.class, () -> MasterKeySource.fromEnv(env).load(dir, files));

        assertSame(eio, e, "the write's own failure");
        assertTrue(write.isReached(), "fixture: the failed write is the key's");
        assertEquals(List.of(), listing(dir), "neither MASTER nor the temporary file is left");

        SecretKey key = MasterKeySource.fromEnv(env).load(dir, files);
        assertEquals(List.of(dir.resolve("MASTER")), listing(dir), "the next load makes MASTER");
        assertArrayEquals(key.getEncoded(), MasterKeySource.fromEnv(env).load(dir, files).getEncoded(), "and the one after reads it");
    }

    @Test
    void theLoadAfterACrashThatLeftAnEmptyKeyFileMakesMasterBesideIt() throws Exception {
        Path dir = journal("crashed");
        Path image = journal("crashed-image");
        HeldFiles files = new HeldFiles();
        seams.add(files);
        Hold write = files.holdFirstWriteOn("a file under " + dir, p -> p.getParent().equals(dir.toAbsolutePath().normalize()));
        Map<String, String> env = Map.of(GENERATE, "true");
        JournalFixture.Outcome<SecretKey> interrupted = JournalFixture.onVirtual(() -> MasterKeySource.fromEnv(env).load(dir, files));
        write.awaitReached();
        for (Path p : listing(dir)) Files.copy(p, image.resolve(p.getFileName().toString()));   // a kill -9 now: what the disk holds
        write.release();
        interrupted.join();
        List<Path> crashed = listing(image);
        assertEquals(1, crashed.size(), "fixture: the crash left the key's file created, nothing in it, and no MASTER: " + crashed);
        assertFalse(Files.exists(image.resolve("MASTER")), "fixture: no MASTER yet");

        SecretKey key = MasterKeySource.fromEnv(env).load(image, JournalFiles.real());

        assertEquals(java.util.stream.Stream.concat(crashed.stream(), Stream.of(image.resolve("MASTER"))).sorted().toList(), listing(image),
                "MASTER is made; the crashed start's file stays (another start's may be in use) and none of this load's is left");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(image.resolve("MASTER")), "MASTER is 0600");
        assertArrayEquals(key.getEncoded(), MasterKeySource.fromEnv(env).load(image, JournalFiles.real()).getEncoded(), "and the next load reads it");
    }

    @Test
    void twoFirstLoadsAtOnceEndWithOneKeyTheOneInMaster() throws Exception {
        Path dir = journal("two-at-once");
        HeldFiles files = new HeldFiles();
        seams.add(files);
        Predicate<Path> under = p -> p.getParent().equals(dir.toAbsolutePath().normalize());
        Hold first = files.holdFirstWriteOn("the first key file written", under);
        Hold second = files.holdFirstWriteOn("the second key file written", under);
        Map<String, String> env = Map.of(GENERATE, "true");
        JournalFixture.Outcome<SecretKey> one = JournalFixture.onVirtual(() -> MasterKeySource.fromEnv(env).load(dir, files));
        JournalFixture.Outcome<SecretKey> two = JournalFixture.onVirtual(() -> MasterKeySource.fromEnv(env).load(dir, files));
        first.awaitReached();
        second.awaitReached();
        assertEquals(2, listing(dir).size(), "fixture: each load made a key file of its own, neither published: " + listing(dir));

        first.release();
        JournalFixture.until(() -> one.done() || two.done(), () -> "the released load ends");
        assertTrue(Files.isRegularFile(dir.resolve("MASTER")), "fixture: the released load published MASTER");
        second.release();
        SecretKey a = one.join();
        SecretKey b = two.join();

        byte[] master = MasterKeySource.fromEnv(env).load(dir, JournalFiles.real()).getEncoded();
        assertArrayEquals(master, a.getEncoded(), "the one load answers MASTER's key");
        assertArrayEquals(master, b.getEncoded(), "and so does the other: one key");
        assertEquals(List.of(dir.resolve("MASTER")), listing(dir), "both key files are unlinked, MASTER stays");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(dir.resolve("MASTER")), "MASTER is 0600");
    }

    @Test
    void twoSourcesAreEqualExactlyWhenTheyNameTheSameSource() {
        String a = base64(material(41, 32));
        String b = base64(material(42, 32));
        List<Map<String, String>> distinct = List.of(Map.of(), Map.of(ENV, a), Map.of(ENV, b), Map.of(FILE, "/run/secrets/a"), Map.of(FILE, "/run/secrets/b"),
                Map.of(GENERATE, "true"));
        for (int i = 0; i < distinct.size(); i++) {
            MasterKeySource one = MasterKeySource.fromEnv(distinct.get(i));
            MasterKeySource same = MasterKeySource.fromEnv(new HashMap<>(distinct.get(i)));
            assertEquals(one, same, "the same env: " + one);
            assertEquals(one.hashCode(), same.hashCode());
            for (int j = 0; j < distinct.size(); j++) {
                if (i != j) assertNotEquals(one, MasterKeySource.fromEnv(distinct.get(j)), one + " vs " + MasterKeySource.fromEnv(distinct.get(j)));
            }
            assertNotEquals(one, distinct.get(i), "no other type is equal");
        }
        assertEquals(MasterKeySource.fromEnv(Map.of()), MasterKeySource.fromEnv(Map.of(GENERATE, "false")), "opted out is no source either");
    }

    @Test
    void theTextOfASourceNamesItsKindAndNeverAKey() {
        byte[] key = material(43, 32);
        String env = MasterKeySource.fromEnv(Map.of(ENV, base64(key))).toString();
        String file = MasterKeySource.fromEnv(Map.of(FILE, "/run/secrets/flottila-master")).toString();
        String generated = MasterKeySource.fromEnv(Map.of(GENERATE, "true")).toString();
        String none = MasterKeySource.fromEnv(Map.of()).toString();

        assertTrue(env.contains(ENV), env);
        assertCarriesNoKey(env, List.of(base64(key)), key);
        assertTrue(file.contains("/run/secrets/flottila-master"), "a file source shows its path: " + file);
        assertTrue(generated.contains(GENERATE), generated);
        assertFalse(none.contains(ENV) || none.contains(GENERATE), "no source names none of the keys: " + none);
        assertEquals(4, java.util.Set.of(env, file, generated, none).size(), "four kinds, four texts");
    }
}
