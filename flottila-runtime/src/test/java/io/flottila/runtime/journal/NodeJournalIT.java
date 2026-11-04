package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.MasterKeySourceTest.assertCarriesNoKey;
import static io.flottila.runtime.journal.MasterKeySourceTest.base64;
import static io.flottila.runtime.journal.MasterKeySourceTest.material;
import static io.flottila.runtime.journal.MasterKeySourceTest.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal opened by a real node (CORE-4c1 packet Scope 2, 4, 5, 6, 7; CORE-3 item 9; D-106) on Postgres at V12:
 * the master key is required in every environment (no random default) unless the generated one is opted in, which warns
 * at every start; a key that cannot unwrap a {@code journal_keys} row refuses naming its key id; {@code <journal>/LOCK}
 * refuses a second process; a first start writes {@code NODE} and then its {@code journal_nodes} row, a NODE without its row
 * is registered, a row without NODE or a NODE of another journal is refused and changes nothing; at start a segment file
 * without its row is unlinked, a row without its file dropped, and the next number is above every number found, so a
 * spill's number is never used again; the admin health reports a failed journal. Every case runs on a database of its own;
 * the journal lives in {@code <dataDir>/journal} unless {@code FLOTTILA_JOURNAL_DIR} says otherwise.
 *
 * <p>The API beyond the unit cases' these cases compile against (proposed by the test-author, CORE-4c1 handback):
 * {@code Journal Node.journal()} (the journal the node opened; 4c2's store writes through it), the admin health check named
 * {@code journal}.
 */
class NodeJournalIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;

    static final String KEY = "FLOTTILA_JOURNAL_KEY";
    static final String KEY_FILE = "FLOTTILA_JOURNAL_KEY_FILE";
    static final String GENERATE = "FLOTTILA_JOURNAL_KEY_GENERATE";
    static final byte[] MASTER_A = material(201, 32);
    static final byte[] MASTER_B = material(202, 32);
    static final String NODE_ID = "node-it";
    /** NODE: magic FJN1 · version u8 · journal id (CORE-3 item 1, the identity file). */
    static final int ID_AT = 5;

    @TempDir Path data;
    CaseDatabase db;
    final List<Node> nodes = new ArrayList<>();
    final List<Process> children = new ArrayList<>();
    final Logs logs = new Logs();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void caseDatabase() throws Exception {
        db = CaseDatabase.create(pg, vertx, "node_journal_");
        Logger.getLogger("").addHandler(logs);
    }

    @AfterEach
    void closeEverything() throws Exception {
        try {
            for (Node n : List.copyOf(nodes)) close(n);
            children.forEach(Process::destroyForcibly);
        } finally {
            Logger.getLogger("").removeHandler(logs);
            db.close();
        }
    }

    /** Every JUL record while the case runs, formatted, with its throwable's text. */
    static final class Logs extends Handler {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Logs() { setLevel(Level.ALL); }

        @Override public void publish(LogRecord r) {
            lines.add(r.getLevel() + " " + r.getLoggerName() + ": " + new SimpleFormatter().formatMessage(r) + (r.getThrown() == null ? "" : "\n" + text(r.getThrown())));
        }

        @Override public void flush() { }

        @Override public void close() { }

        List<String> warningsNaming(String what) {
            return lines.stream().filter(l -> l.startsWith("WARNING ") && l.contains(what)).toList();
        }

        String all() { return String.join("\n", lines); }
    }

    /** A node on the case's database, admin on any free port, data under the case's directory, master key A. */
    Map<String, String> env(String flottilaEnv) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", flottilaEnv);
        env.put("FLOTTILA_NODE_ID", NODE_ID);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", db.name());
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        env.put(KEY, base64(MASTER_A));
        return env;
    }

    /** A deployed node (NodeIT's pattern: an s3 archive store over https, contacted only to restore, so nothing need listen). */
    Map<String, String> deployed() {
        Map<String, String> env = env("prod");
        env.put("FLOTTILA_ARCHIVE_STORE", "s3");
        env.put("FLOTTILA_S3_BUCKET", "flottila-archives");
        env.put("FLOTTILA_S3_REGION", "eu-central-1");
        env.put("FLOTTILA_S3_ENDPOINT", "https://127.0.0.1:9");
        env.put("FLOTTILA_S3_ACCESS_KEY", "AKIAEXAMPLE");
        env.put("FLOTTILA_S3_SECRET_KEY", "secret-example");
        return env;
    }

    Node start(Map<String, String> env) throws Exception {
        Node n = OnVirtualThread.call(() -> {
            NodeConfig config = NodeConfig.from(env);
            return Node.start(config, vertx, ctx -> List.of(), List.of(), List.of());
        });
        nodes.add(n);
        return n;
    }

    /** What the start with {@code env} throws (the configuration's refusal or the start's); it must throw. */
    Throwable refused(Map<String, String> env, String why) {
        return assertThrows(Throwable.class, () -> start(env), why);
    }

    void close(Node n) throws Exception {
        nodes.remove(n);
        OnVirtualThread.call(() -> {
            n.close();
            return null;
        });
    }

    Path journal() { return data.resolve("journal"); }

    static UUID journalIdOf(Path journalDir) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(journalDir.resolve("NODE")));
        return new UUID(b.getLong(ID_AT), b.getLong(ID_AT + 8));
    }

    Map<String, UUID> journalNodes() {
        Map<String, UUID> rows = new TreeMap<>();
        for (Row r : db.pool().query("select node, journal_id from journal_nodes").execute().await()) rows.put(r.getString(0), r.getUUID(1));
        return rows;
    }

    TreeSet<Long> segmentRows(String node) {
        TreeSet<Long> rows = new TreeSet<>();
        for (Row r : db.pool().preparedQuery("select segment from journal_segments where node = $1").execute(Tuple.of(node)).await()) rows.add(r.getLong(0));
        return rows;
    }

    static TreeSet<Long> segmentFiles(Path journalDir) throws IOException {
        TreeSet<Long> files = new TreeSet<>();
        Path segments = journalDir.resolve("segments");
        if (!Files.isDirectory(segments)) return files;
        try (Stream<Path> l = Files.list(segments)) {
            l.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(f -> f.matches("[1-9][0-9]*\\.seg"))
                    .forEach(f -> files.add(Long.parseLong(f.substring(0, f.length() - 4))));
        }
        return files;
    }

    static TreeSet<Long> segmentFilesQuietly(Path journalDir) {
        try {
            return segmentFiles(journalDir);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static HttpResponse<String> health(Node n) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + n.adminPort() + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    /** The default tenant's key id, made under {@code master} before the node starts (4c1 has no first use of its own). */
    int defaultTenantKey(byte[] master) throws Exception {
        db.migrated();
        SecretKey m = new SecretKeySpec(master, "AES");
        return OnVirtualThread.call(() -> JournalKeys.load(db.pool(), m).keyOf("default"));
    }

    // ------------------------------------------------------------------------------------------------- the master key

    @Test
    void aDeployedNodeWithoutTheKeyRefusesStart() throws Exception {
        Map<String, String> env = deployed();
        env.remove(KEY);

        Throwable e = refused(env, "a deployed node without a master key");

        assertTrue(text(e).contains(KEY), "the refusal names the key: " + text(e));
        assertFalse(Files.exists(journal().resolve("MASTER")), "no key is made up");
        env.put(KEY, base64(MASTER_A));
        assertEquals(200, health(start(env)).statusCode(), "the same node with its key starts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "ci"})
    void aDevNodeWithoutTheKeyRefusesStart(String flottilaEnv) throws Exception {
        Map<String, String> env = env(flottilaEnv);
        env.remove(KEY);

        Throwable e = refused(env, flottilaEnv + ": no random default outside a deployment either");

        assertTrue(text(e).contains(KEY), "the refusal names the key: " + text(e));
        assertFalse(Files.exists(journal().resolve("MASTER")), "no key is made up");
        env.put(KEY, base64(MASTER_A));
        assertEquals(200, health(start(env)).statusCode(), "the same node with its key starts");
    }

    /** CORE-4c1 r1 (architect): the master key is loaded before any Postgres statement (implementer's case). */
    @Test
    void aKeylessStartMigratesNothing() throws Exception {
        Map<String, String> env = env("dev");
        env.remove(KEY);

        Throwable e = refused(env, "no master key");

        assertTrue(text(e).contains(KEY), "the refusal names the key: " + text(e));
        assertEquals(0, db.count("select count(*) from information_schema.tables where table_schema = 'public'"),
                "the database is left at its version (none): not even Flyway's history table is made");
    }

    @Test
    void aGeneratedKeyNeedsItsOptInAndWarnsAtEveryStart() throws Exception {
        Map<String, String> env = env("dev");
        env.remove(KEY);
        refused(env, "without the opt-in");
        assertFalse(Files.exists(journal().resolve("MASTER")));

        env.put(GENERATE, "true");
        int before = logs.warningsNaming("MASTER").size();
        Node first = start(env);
        Path master = journal().resolve("MASTER");
        assertTrue(Files.isRegularFile(master), "the opt-in creates <journal>/MASTER");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(master), "0600");
        byte[] stored = Files.readAllBytes(master);
        assertEquals(before + 1, logs.warningsNaming("MASTER").size(), "a WARNING at the first start: " + logs.all());
        close(first);

        start(env);

        assertArrayEquals(stored, Files.readAllBytes(master), "created once, read at the next start");
        assertEquals(before + 2, logs.warningsNaming("MASTER").size(), "and a WARNING at every start: " + logs.all());
        assertCarriesNoKey(logs.all(), List.of(new String(stored, StandardCharsets.ISO_8859_1).strip()), stored);
    }

    @Test
    void bothKeySourcesSetRefuses() throws Exception {
        Path file = Files.writeString(Files.createDirectories(data.resolve("secrets")).resolve("master.b64"), base64(MASTER_B) + "\n");
        Map<String, String> env = env("dev");
        env.put(KEY_FILE, file.toString());

        Throwable e = refused(env, "a key file and a key value");

        assertTrue(text(e).contains(KEY_FILE) && Pattern.compile(KEY + "(?!_)").matcher(text(e)).find(), "names both keys: " + text(e));
        assertCarriesNoKey(text(e) + "\n" + logs.all(), List.of(base64(MASTER_A), base64(MASTER_B)), MASTER_A, MASTER_B);
        env.remove(KEY);
        assertEquals(200, health(start(env)).statusCode(), "the key file alone starts the node");
    }

    @Test
    void aKeyThatCannotUnwrapTheJournalKeysRefusesStart() throws Exception {
        int id = defaultTenantKey(MASTER_A);
        byte[] tenantKey = OnVirtualThread.call(() -> JournalKeys.load(db.pool(), new SecretKeySpec(MASTER_A, "AES")).apply(id).getEncoded());
        List<String> before = keyRows();
        Map<String, String> env = env("dev");
        env.put(KEY, base64(MASTER_B));

        Throwable e = refused(env, "the master key B cannot unwrap a key wrapped under A");

        String text = text(e);
        assertTrue(Pattern.compile("\\b" + id + "\\b").matcher(text).find(), "the refusal names the key id " + id + ": " + text);
        List<byte[]> secrets = new ArrayList<>(List.of(MASTER_A, MASTER_B, tenantKey));
        for (Row r : db.pool().query("select wrapped from journal_keys").execute().await()) secrets.add(r.getBuffer(0).getBytes());
        assertCarriesNoKey(text + "\n" + logs.all(), List.of(), secrets.toArray(byte[][]::new));
        assertEquals(before, keyRows(), "the refused start rewrapped nothing");
        env.put(KEY, base64(MASTER_A));
        assertEquals(200, health(start(env)).statusCode(), "the master key A starts the node");
    }

    List<String> keyRows() {
        List<String> rows = new ArrayList<>();
        for (Row r : db.pool().query("select key_id, tenant, encode(wrapped, 'base64') from journal_keys order by key_id").execute().await()) {
            rows.add(r.getInteger(0) + " " + r.getString(1) + " " + r.getString(2));
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------------- identity and LOCK

    /**
     * NODE first, then its row: a start whose {@code journal_nodes} insert fails (a trigger refuses it) leaves NODE written
     * and no row; the next start registers the row under NODE's journal id.
     */
    @Test
    void aNodeFileWithoutItsRowIsRegistered() throws Exception {
        db.migrated();
        db.pool().query("create function refuse_node_row() returns trigger language plpgsql as $$ begin raise exception "
                + "'the journal_nodes insert is refused (test)'; end $$").execute().await();
        db.pool().query("create trigger refuse_node_row before insert on journal_nodes for each row execute function refuse_node_row()").execute().await();

        Throwable e = refused(env("dev"), "the journal_nodes insert is refused");

        assertTrue(text(e).contains("refused (test)"), "fixture: the start failed at its journal_nodes insert: " + text(e));
        assertTrue(Files.isRegularFile(journal().resolve("NODE")), "NODE is written before its row");
        assertEquals(Map.of(), journalNodes());
        UUID written = journalIdOf(journal());
        byte[] node = Files.readAllBytes(journal().resolve("NODE"));
        db.pool().query("drop trigger refuse_node_row on journal_nodes").execute().await();

        Node n = start(env("dev"));

        assertEquals(Map.of(NODE_ID, written), journalNodes(), "the row is inserted under NODE's journal id");
        assertArrayEquals(node, Files.readAllBytes(journal().resolve("NODE")), "NODE is kept");
        assertEquals(200, health(n).statusCode());
    }

    /** Every file under {@code dir} by its path below it, with its bytes (base64). */
    static Map<String, String> filesUnder(Path dir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) files.put(dir.relativize(p).toString(), base64(Files.readAllBytes(p)));
        }
        return files;
    }

    /** CORE-4c1 r1 (architect): a NODE without its row is registered only while segments/ holds no segment file (implementer's case). */
    @Test
    void aJournalWithSegmentFilesIsRefusedOnADatabaseThatDoesNotRegisterIt() throws Exception {
        close(start(env("dev")));
        TreeSet<Long> segments = segmentFiles(journal());
        assertFalse(segments.isEmpty(), "fixture: the first run left segment files: " + segments);
        Map<String, String> before = filesUnder(journal());
        assertTrue(before.containsKey("NODE"), "fixture: NODE is there");

        try (CaseDatabase fresh = CaseDatabase.create(pg, vertx, "node_journal_fresh_")) {
            Map<String, String> env = env("dev");
            env.put("FLOTTILA_PG_DATABASE", fresh.name());

            Throwable e = refused(env, "a journal with segment files on a database that does not register it");

            assertTrue(text(e).contains(segments.size() + " segment file") && text(e).contains("wrong or re-provisioned database"),
                    "names the count of segment files and the hint: " + text(e));
            assertEquals(before, filesUnder(journal()), "every file of the journal is kept, NODE included");
            assertEquals(0, fresh.count("select count(*) from journal_nodes"), "no row is registered");
            assertEquals(0, fresh.count("select count(*) from journal_segments"), "nor any segment row");
        }
        assertEquals(200, health(start(env("dev"))).statusCode(), "on its own database the journal starts again");
    }

    @Test
    void anotherNodesJournalIsRefused() throws Exception {
        close(start(env("dev")));
        UUID mine = journalIdOf(journal());
        assertEquals(Map.of(NODE_ID, mine), journalNodes(), "a first start registers its journal");
        Path otherJournal = data.resolve("other-journal");
        try (CaseDatabase other = CaseDatabase.create(pg, vertx, "other_journal_")) {
            Map<String, String> elsewhere = env("dev");
            elsewhere.put("FLOTTILA_PG_DATABASE", other.name());
            elsewhere.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve("other-data")).toString());
            elsewhere.put("FLOTTILA_JOURNAL_DIR", otherJournal.toString());
            close(start(elsewhere));
        }
        UUID theirs = journalIdOf(otherJournal);
        assertNotEquals(mine, theirs, "fixture: another journal of the same node id");
        byte[] myNode = Files.readAllBytes(journal().resolve("NODE"));
        Files.copy(otherJournal.resolve("NODE"), journal().resolve("NODE"), StandardCopyOption.REPLACE_EXISTING);
        TreeSet<Long> files = segmentFiles(journal());

        refused(env("dev"), "NODE names another journal than the journal_nodes row");

        assertEquals(Map.of(NODE_ID, mine), journalNodes(), "the row is not overwritten");
        assertEquals(theirs, journalIdOf(journal()), "nor is NODE");
        assertEquals(files, segmentFiles(journal()), "the refused start removed no segment file");
        Files.write(journal().resolve("NODE"), myNode);
        assertEquals(200, health(start(env("dev"))).statusCode(), "its own NODE starts the node again");
    }

    @Test
    void aRowWithoutItsNodeFileIsRefused() throws Exception {
        close(start(env("dev")));
        UUID mine = journalIdOf(journal());
        Path fresh = data.resolve("fresh-journal");
        Map<String, String> env = env("dev");
        env.put("FLOTTILA_JOURNAL_DIR", fresh.toString());

        refused(env, "a journal_nodes row whose journal is not in the journal directory");

        assertFalse(Files.exists(fresh.resolve("NODE")), "no new journal is begun over the registered one");
        assertEquals(Map.of(NODE_ID, mine), journalNodes(), "the row is kept");
        assertEquals(200, health(start(env("dev"))).statusCode(), "the registered journal starts the node again");
    }

    @Test
    void aSecondProcessIsRefused() throws Exception {
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), JournalLockHolderChild.class.getName(),
                journal().toString());
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(data.resolve("child-stderr.txt").toFile());
        builder.environment().clear();
        Process child = builder.start();
        children.add(child);
        BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
        OnVirtualThread.Call<String> firstLine = OnVirtualThread.start((java.util.concurrent.Callable<String>) out::readLine);   // bounded: a child that never speaks fails the case
        assertEquals("locked", OnVirtualThread.join(firstLine), "the child holds <journal>/LOCK: " + Files.readString(data.resolve("child-stderr.txt")));

        Throwable e = refused(env("dev"), "another process holds the LOCK");

        assertTrue(text(e).contains("LOCK"), "the refusal names the LOCK: " + text(e));
        child.getOutputStream().close();
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child lets the lock go");
        assertEquals(0, child.exitValue(), Files.readString(data.resolve("child-stderr.txt")));
        assertEquals(200, health(start(env("dev"))).statusCode(), "the LOCK free, the node starts");
    }

    // ------------------------------------------------------------------------------------------------- segment rows

    @Test
    void filesWithoutARowAreUnlinkedAndRowsWithoutAFileDropped() throws Exception {
        close(start(env("dev")));
        TreeSet<Long> files = segmentFiles(journal());
        assertTrue(segmentRows(NODE_ID).containsAll(files), "fixture: every file of the first run has its row: " + files + " " + segmentRows(NODE_ID));
        assertTrue(files.last() < 7, "fixture: the first run used numbers below 7: " + files);
        Path stray = Files.write(journal().resolve("segments").resolve("9.seg"), "no row names this file, and it is no segment".getBytes(StandardCharsets.UTF_8));
        db.pool().query("insert into journal_segments(node, segment, copies, created_at) values ('" + NODE_ID + "', 7, array['" + NODE_ID
                + "'], now()), ('node-other', 50, array['node-other'], now())").execute().await();

        TreeSet<Long> found = segmentFiles(journal());

        start(env("dev"));

        assertFalse(Files.exists(stray), "a segment file without its row is unlinked (before any read of it)");
        assertFalse(segmentRows(NODE_ID).contains(7L), "a row without its file is dropped: " + segmentRows(NODE_ID));
        assertEquals(new TreeSet<>(List.of(50L)), segmentRows("node-other"), "another node's rows are not this node's to drop");
        TreeSet<Long> now = segmentFiles(journal());
        TreeSet<Long> created = new TreeSet<>(now);
        created.removeAll(found);
        assertEquals(10L, created.first(), "the next number is max(files " + found + ", rows ..7) + 1 = 10, another node's 50 not counted: " + now);
        assertTrue(segmentRows(NODE_ID).containsAll(now), "every segment file has its row: files " + now + ", rows " + segmentRows(NODE_ID));
    }

    @Test
    void spillNumbersAreNeverReusedAfterARestart() throws Exception {
        int keyId = defaultTenantKey(MASTER_A);
        Node first = start(env("dev"));
        Journal.Spill spill = first.journal().spill(BlobKey.body("sp-1", 0), keyId, new GeneratedInput(1, 100_000), 100_000, Hasher.sha256());
        long n = spill.segment();
        Path target = journal().resolve("segments").resolve(n + ".seg");
        Path blocker = Files.writeString(Files.createDirectories(target).resolve("blocker"), "in the way");   // rename(2) cannot replace it
        assertThrows(IOException.class, () -> OnVirtualThread.call(spill::persist), "fixture: the move into segments/ fails");
        assertTrue(segmentRows(NODE_ID).contains(n), "the persist inserted the row of " + n + " before the move");
        Files.delete(blocker);
        Files.delete(target);
        long highest = Math.max(n, segmentRows(NODE_ID).last());
        close(first);
        TreeSet<Long> found = segmentFiles(journal());

        Node second = start(env("dev"));

        assertFalse(segmentRows(NODE_ID).contains(n), "the row whose file never arrived is dropped");
        TreeSet<Long> created = new TreeSet<>(segmentFiles(journal()));
        created.removeAll(found);
        assertEquals(highest + 1, created.first(), "the restart numbers on above the dropped row " + n + " (files found " + found + ")");
        long next = second.journal().spill(BlobKey.body("sp-2", 0), keyId, new GeneratedInput(2, 100_000), 100_000, Hasher.sha256()).segment();
        assertTrue(next > highest, "a spill after the restart takes a number never used before (" + next + " > " + highest + ")");
    }

    // ------------------------------------------------------------------------------------------------- fail-stop health

    @Test
    void theAdminHealthReportsAFailedJournal() throws Exception {
        int keyId = defaultTenantKey(MASTER_A);
        Map<String, String> env = env("dev");
        env.put("FLOTTILA_JOURNAL_SEGMENT_RECORDS", "1");   // every record rolls: the writer soon moves to a segment it cannot open
        Node n = start(env);
        HttpResponse<String> up = health(n);
        assertEquals(200, up.statusCode(), up.body());
        assertEquals("UP", new JsonObject(up.body()).getJsonObject("checks").getString("journal"), "the journal reports itself: " + up.body());
        Path segments = journal().resolve("segments");
        // the open segment and its pre-opened successor exist once the start's pre-open is over (CORE-3 item 1); every
        // number after them is blocked by a non-empty directory, which a pre-open's create cannot replace
        JournalFixture.until(() -> segmentFilesQuietly(journal()).size() >= 2, () -> "the start's segments: " + segmentFilesQuietly(journal()));
        for (int k = 1; k <= 20; k++) {
            Path seg = segments.resolve(k + ".seg");
            if (!Files.exists(seg)) Files.writeString(Files.createDirectories(seg).resolve("blocker"), "a pre-open cannot create this segment");
        }

        Throwable failure = null;
        for (int i = 0; i < 6 && failure == null; i++) {
            JournalRecord r = new JournalRecord("hf-" + i, 0, "in", keyId, JournalFixture.body("hf-" + i, 100));
            Future<Void> forced;
            try {
                forced = n.journal().append(r, Promise.<Void>promise().future()).forced();
            } catch (JournalFailedException refused) {
                forced = Future.failedFuture(refused);
            }
            Future<Void> f = forced;
            JournalFixture.until(f::isComplete, () -> "the append of " + r.messageId() + " is answered");
            if (f.failed()) failure = f.cause();
        }
        assertInstanceOf(JournalFailedException.class, failure, "fixture: the journal failed (its pre-open could not create a segment)");
        assertTrue(n.journal().isFailed());

        HttpResponse<String> down = health(n);
        assertEquals(503, down.statusCode(), down.body());
        JsonObject checks = new JsonObject(down.body()).getJsonObject("checks");
        assertEquals("DOWN", checks.getString("journal"), down.body());
        assertEquals("UP", checks.getString("postgres"), "only the journal is down: " + down.body());
    }
}
