package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static io.flottila.cli.CliRuns.DEMO;
import static io.flottila.cli.CliRuns.FIXTURES;
import static io.flottila.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03a (D-079): the archive {@code flottila deploy} PUTs is deterministic — built with java.util.zip, one entry per regular file
 * at its {@code /}-separated path from the package root (so {@code package.yaml} at the archive root), entries sorted by that path,
 * no directory entries, one fixed modification time — so the same directory gives the same bytes, hence the same SHA-256 (the
 * node's archive key, D-070), whatever the files' mtimes, the directory's place or the order the files were written in. Its layout
 * is deploy.sh's ({@code zip -r -D} / {@code jar --no-manifest} of the directory's contents, directory entries aside). The zip
 * is observed as it arrives at a {@link StubAdmin}.
 */
class PackageZipTest {

    @TempDir Path tmp;
    StubAdmin admin;

    @BeforeEach
    void startTheStub() throws IOException {
        admin = new StubAdmin();
    }

    @AfterEach
    void stopTheStub() {
        admin.close();
    }

    /** The bytes {@code flottila deploy <dir>} PUT. */
    byte[] zipOf(Path dir) {
        int before = admin.requests().size();
        Ran ran = run(Map.of(), "deploy", dir.toString(), "--admin", admin.url());
        assertEquals(0, ran.exit(), "fixture: the deploy to the stub succeeds: " + ran);
        assertEquals(before + 1, admin.requests().size(), "fixture: one PUT");
        return admin.requests().getLast().body();
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Every regular file below {@code dir} by its {@code /}-path from {@code dir}, sorted by that path. */
    static Map<String, byte[]> filesOf(Path dir) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) files.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        }
        return files;
    }

    /** The entries of {@code zip} in the order of its local headers, each with its bytes. */
    static LinkedHashMap<String, byte[]> entries(byte[] zip) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (entries.put(e.getName(), in.readAllBytes()) != null) throw new AssertionError("entry " + e.getName() + " twice");
            }
        }
        return entries;
    }

    /** The copy of {@code from} at {@code to}, its files written in reverse path order (another directory order than a forward copy). */
    static Path reverseCopy(Path from, Path to) throws IOException {
        List<Map.Entry<String, byte[]>> files = new ArrayList<>(filesOf(from).entrySet());
        java.util.Collections.reverse(files);
        for (Map.Entry<String, byte[]> f : files) {
            Path target = to.resolve(f.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, f.getValue());
        }
        return to;
    }

    static void touchAll(Path dir, Instant at) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.toList()) Files.setLastModifiedTime(p, FileTime.from(at));
        }
    }

    /**
     * The same package directory twice, then with every mtime moved, then copied elsewhere in reverse order: the same bytes each
     * time, so the same SHA-256.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"demo", "tree"})
    void sameDirSameSha256(String pkg) throws Exception {
        Path source = pkg.equals("demo") ? DEMO : FIXTURES.resolve(pkg);
        Path dir = CliRuns.copy(source, tmp.resolve("a").resolve(pkg));

        byte[] first = zipOf(dir);
        byte[] again = zipOf(dir);
        touchAll(dir, Instant.parse("2001-02-03T04:05:06Z"));
        byte[] touched = zipOf(dir);
        Path elsewhere = reverseCopy(dir, tmp.resolve("b").resolve("deeper").resolve("other-name"));
        byte[] moved = zipOf(elsewhere);

        assertEquals(sha256(first), sha256(again), "the same directory twice");
        assertEquals(sha256(first), sha256(touched), "every file's mtime changed: the entries carry a fixed time, not the file's");
        assertEquals(sha256(first), sha256(moved), "another place, another write order: nothing of either is in the archive");
    }

    /**
     * One entry per regular file, at its path from the package root ({@code package.yaml} at the root), sorted by that path, no
     * directory entry, each with the file's bytes; in the local headers and the central directory alike; one modification time
     * for all.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"demo", "tree"})
    void entriesSortedNoDirsPackageYamlAtRoot(String pkg) throws Exception {
        Path dir = pkg.equals("demo") ? DEMO : FIXTURES.resolve(pkg);
        Map<String, byte[]> files = filesOf(dir);

        byte[] zip = zipOf(dir);

        LinkedHashMap<String, byte[]> local = entries(zip);
        List<String> names = List.copyOf(local.keySet());
        assertEquals(List.copyOf(files.keySet()), names, "every file once, at its path from the root, sorted by that path, nothing else");
        assertEquals(names.stream().sorted().toList(), names, "sorted");
        assertTrue(names.contains("package.yaml"), "package.yaml at the archive root: " + names);
        assertFalse(names.stream().anyMatch(n -> n.endsWith("/") || n.startsWith("/") || n.startsWith("./")), "no directory entry, no prefix: " + names);
        for (String name : names) assertArrayEquals(files.get(name), local.get(name), name + ": the file's bytes");

        Path written = Files.write(tmp.resolve("written.zip"), zip);
        try (ZipFile central = new ZipFile(written.toFile())) {
            List<String> centralNames = central.stream().map(ZipEntry::getName).toList();
            assertEquals(names, centralNames, "the central directory lists the same entries in the same order");
            List<Long> times = central.stream().map(ZipEntry::getTime).distinct().toList();
            assertEquals(1, times.size(), "one fixed modification time for every entry: " + times);
            central.stream().forEach(e -> assertEquals(Deploy.ENTRY_TIME, e.getTimeLocal(), "central entry " + e.getName() + ": the fixed time"));
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                assertEquals(Deploy.ENTRY_TIME, e.getTimeLocal(), "local entry " + e.getName() + ": the fixed time, never the file's or the clock's");
            }
        }
    }

    /**
     * The layout deploy.sh sends, which the node deploys (QuickstartIT): its {@code jar --create --no-manifest -C dir .} fallback
     * holds the same files at the same paths with the same bytes; only its directory entries are extra.
     */
    @Test
    void theLayoutIsDeployShs() throws Exception {
        Path jar = Path.of(System.getProperty("java.home"), "bin", "jar");
        assertTrue(Files.isExecutable(jar), "fixture: the JDK's jar tool at " + jar);
        Path theirs = tmp.resolve("deploy-sh.zip");
        Process p = new ProcessBuilder(jar.toString(), "--create", "--no-manifest", "--file", theirs.toString(), "-C", DEMO.toAbsolutePath().toString(), ".")
                .redirectErrorStream(true).redirectOutput(tmp.resolve("jar.log").toFile()).start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "fixture: jar ran within 60 s");
        assertEquals(0, p.exitValue(), "fixture: jar: " + Files.readString(tmp.resolve("jar.log")));
        Map<String, byte[]> deploySh = new TreeMap<>();
        entries(Files.readAllBytes(theirs)).forEach((name, bytes) -> {
            if (!name.endsWith("/")) deploySh.put(name.startsWith("./") ? name.substring(2) : name, bytes);
        });

        LinkedHashMap<String, byte[]> ours = entries(zipOf(DEMO));

        assertEquals(List.copyOf(deploySh.keySet()), List.copyOf(ours.keySet()), "the same files at the same paths");
        for (String name : ours.keySet()) assertArrayEquals(deploySh.get(name), ours.get(name), name);
    }
}
