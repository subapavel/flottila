package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.flottila.core.queue.WorkQueue;
import io.flottila.runtime.queue.PgWorkQueue;
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
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import java.util.regex.Matcher;
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

    // ------------------------------------------------------------------------------------------------- pins at start, ACCEPT_LOSS (CORE-4c3)
    //
    // CORE-4c3 packet items 1-3, 5, 8 (CORE-3 item 9, "Review r2 additions", "Added in CORE-4c1"). The API these cases assume is
    // the env only: FLOTTILA_JOURNAL_ACCEPT_LOSS, FLOTTILA_JOURNAL_MAX_BYTES; V13's work.lost_at and journal_losses; the health
    // JSON's "notices" array. A refusal prints <journal id>:<digest>; the digest's form is the implementer's (hex or base64url).

    static final String ACCEPT = "FLOTTILA_JOURNAL_ACCEPT_LOSS";
    static final Pattern PRINTED = Pattern.compile("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):([0-9A-Za-z_-]{8,})");
    static final Duration LEASE = Duration.ofSeconds(30);

    /** A dev node whose segments never roll by age (the real timer) and roll after {@code segmentRecords} records. */
    Map<String, String> pinned(int segmentRecords) {
        Map<String, String> env = env("dev");
        env.put("FLOTTILA_JOURNAL_SEGMENT_AGE", "PT1H");
        env.put("FLOTTILA_JOURNAL_SEGMENT_RECORDS", String.valueOf(segmentRecords));
        return env;
    }

    /** A first run on {@code env} that appended one forced 200-byte record per id (step 0, part body), then closed: the refs. */
    Map<String, JournalRef> firstRun(Map<String, String> env, String... ids) throws Exception {
        int keyId = defaultTenantKey(MASTER_A);
        Node n = start(env);
        Map<String, JournalRef> refs = new LinkedHashMap<>();
        for (String id : ids) {
            Journal.Appended a = n.journal().append(new JournalRecord(id, 0, BlobKey.BODY, keyId, JournalFixture.body(id, 200)), Promise.<Void>promise().future());
            JournalFixture.await(a.forced());
            refs.put(id, a.ref());
        }
        close(n);
        return refs;
    }

    Path segmentFile(Path journalDir, long n) { return journalDir.resolve("segments").resolve(n + ".seg"); }

    void sql(String statement, Object... params) {
        db.pool().preparedQuery(statement).execute(Tuple.wrap(params)).await();
    }

    /** A work row of flow orders at step 1 naming {@code ref} on {@code node}, its own partition key. */
    void workRow(String id, String node, JournalRef ref) {
        sql("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length) values ($1, 'orders', 1, $1, $2, $3, $4, $5)",
                id, node, ref.segment(), ref.position(), ref.length());
    }

    /** A kept ref of {@code (id, 0, body)} naming {@code ref} on this node; archived when said. */
    void keptRef(String id, JournalRef ref, boolean archived) {
        sql("insert into body_refs(message_id, step, part, node, segment, position, length, archived_at) values ($1, 0, 'body', $2, $3, $4, $5, "
                + (archived ? "now()" : "null") + ")", id, NODE_ID, ref.segment(), ref.position(), ref.length());
    }

    /** A DLQ row of {@code id} whose body is {@code (id, 0, body)}. */
    void parked(String id) {
        sql("insert into dlq(id, flow, stage, step, error, body_key, body_size) values ($1, 'orders', 'a', 1, 'partner down (test)', $2, 200)", id, id + "/0/body");
    }

    /** Every row of the tables a start may touch, whole (to_jsonb: every column, whatever the version). */
    List<String> pgState() {
        List<String> rows = new ArrayList<>();
        for (String t : List.of("journal_nodes", "journal_segments", "work", "body_refs", "dlq", "journal_losses")) {
            boolean there = db.pool().preparedQuery("select to_regclass($1) is not null").execute(Tuple.of(t)).await().iterator().next().getBoolean(0);
            if (!there) continue;
            for (Row r : db.pool().query("select to_jsonb(x)::text from " + t + " x order by 1").execute().await()) rows.add(t + " " + r.getString(0));
        }
        return rows;
    }

    /** Which of {@code ids} are marked lost: work rows by id, body_refs by message id (null: no such row). */
    Map<String, Boolean> lost(String table, String column, List<String> ids) {
        Map<String, Boolean> m = new TreeMap<>();
        for (String id : ids) {
            for (Row r : db.pool().preparedQuery("select lost_at is not null from " + table + " where " + column + " = $1").execute(Tuple.of(id)).await()) m.put(id, r.getBoolean(0));
        }
        return m;
    }

    List<String> losses() {
        List<String> rows = new ArrayList<>();
        for (Row r : db.pool().query("select node, journal_id, digest, accepted_at is not null from journal_losses order by digest").execute().await()) {
            rows.add(r.getString(0) + " " + r.getUUID(1) + ":" + r.getString(2) + " " + r.getBoolean(3));
        }
        return rows;
    }

    /** The {@code <journal id>:<digest>} a refusal prints. */
    static String printed(Throwable e) {
        Matcher m = PRINTED.matcher(text(e));
        assertTrue(m.find(), "the refusal prints <journal id>:<digest>: " + text(e));
        return m.group();
    }

    List<String> severeNaming(String id) {
        return logs.lines.stream().filter(l -> l.startsWith("SEVERE ") && l.contains(id)).toList();
    }

    /**
     * A committed pin into a segment whose file is gone — a work row, an unarchived kept ref, a DLQ row with its kept ref —
     * refuses the start naming those messages and printing {@code <this journal's id>:<digest>}; rows into it that pin nothing
     * (an archived ref, another node's work row) and pins elsewhere are not named. Nothing changes on disk or in Postgres; a
     * second refusal prints the same value (a stable digest).
     */
    @Test
    void aPinnedMissingSegmentRefusesStart() throws Exception {
        Map<String, String> env = pinned(3);
        Map<String, JournalRef> refs = firstRun(env, "pin-work", "pin-kept", "pin-parked", "live-1", "live-2");
        long gone = refs.get("pin-work").segment();
        assertEquals(List.of(gone, gone), List.of(refs.get("pin-kept").segment(), refs.get("pin-parked").segment()), "fixture: three records in one segment " + refs);
        assertNotEquals(gone, refs.get("live-1").segment(), "fixture: the live ones in another");
        workRow("pin-work", NODE_ID, refs.get("pin-work"));
        keptRef("pin-kept", refs.get("pin-kept"), false);
        keptRef("pin-parked", refs.get("pin-parked"), false);
        parked("pin-parked");
        keptRef("archived", refs.get("pin-work"), true);
        workRow("other-node", "node-other", refs.get("pin-work"));
        workRow("live-1", NODE_ID, refs.get("live-1"));
        Files.delete(segmentFile(journal(), gone));
        Map<String, String> files = filesUnder(journal());
        List<String> rows = pgState();

        Throwable e = refused(env, "a committed pin into a missing segment");

        String text = text(e);
        for (String id : List.of("pin-work", "pin-kept", "pin-parked")) assertTrue(text.contains(id), "names " + id + ": " + text);
        for (String id : List.of("archived", "other-node", "live-1")) assertFalse(text.contains(id), id + " pins nothing there: not named: " + text);
        String value = printed(e);
        assertEquals(journalIdOf(journal()).toString(), value.substring(0, 36), "the journal id is this journal's: " + value);
        assertEquals(files, filesUnder(journal()), "nothing changed on disk");
        assertEquals(rows, pgState(), "nothing changed in Postgres (the pinned segment's row is kept)");
        assertEquals(value, printed(refused(env, "the same loss again")), "the same loss prints the same value");
    }

    /** At most a bounded list of the given-up items is named, and their count: 257 pins name fewer than 257 ids and say 257. */
    @Test
    void aRefusalNamesABoundedListOfItemsAndTheirCount() throws Exception {
        Map<String, String> env = pinned(1);
        Map<String, JournalRef> refs = firstRun(env, "bulk", "live");
        JournalRef r = refs.get("bulk");
        sql("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length) select 'bulk-' || lpad(g::text, 3, '0'), "
                + "'orders', 1, 'bulk-' || g, $1, $2, $3, $4 from generate_series(1, 257) g", NODE_ID, r.segment(), r.position(), r.length());
        Files.delete(segmentFile(journal(), r.segment()));

        Throwable e = refused(env, "257 pins into a missing segment");

        Throwable carrier = e;
        for (Throwable t = e; t != null; t = t.getCause()) if (t.getMessage() != null && PRINTED.matcher(t.getMessage()).find()) carrier = t;
        String message = String.valueOf(carrier.getMessage());
        long named = Pattern.compile("bulk-\\d{3}").matcher(message).results().map(m -> m.group()).distinct().count();
        assertTrue(named >= 1 && named < 257, "a bounded list, not every id (" + named + " named): " + message.length() + " chars");
        assertTrue(Pattern.compile("(?<![0-9A-Za-z_-])257(?![0-9A-Za-z_-])").matcher(message).find(), "and the count 257: " + message);
        assertTrue(message.length() < 16 * 1024, "the refusal stays readable: " + message.length() + " chars");
    }

    /**
     * A segment without a valid trailer (a crash mid-record cut its footer off with the record's tail) is cut at its first bad
     * record: a pin at the cut (its first byte) or past it refuses the start; a pin ending exactly at the cut does not and is
     * not named. Nothing changes — the file keeps its length. Witness: without those two pins the start goes on and the record
     * before the cut reads.
     */
    @Test
    void aPinAtOrPastTheCutRefusesStart() throws Exception {
        Map<String, String> env = pinned(65_536);
        Map<String, JournalRef> refs = firstRun(env, "before-cut", "at-cut", "past-cut");
        JournalRef before = refs.get("before-cut"), at = refs.get("at-cut"), past = refs.get("past-cut");
        assertEquals(List.of(before.segment(), before.segment()), List.of(at.segment(), past.segment()), "fixture: one segment " + refs);
        assertEquals(before.position() + before.length(), at.position(), "fixture: back to back");
        workRow("before-cut", NODE_ID, before);
        workRow("at-cut", NODE_ID, at);
        keptRef("past-cut", past, false);
        try (FileChannel ch = FileChannel.open(segmentFile(journal(), at.segment()), StandardOpenOption.WRITE)) {
            ch.truncate(at.position() + 7);   // the trailer, the footer and all but 7 bytes of at-cut gone
        }
        Map<String, String> files = filesUnder(journal());
        List<String> rows = pgState();

        Throwable e = refused(env, "pins at and past the cut");

        String text = text(e);
        assertTrue(text.contains("at-cut") && text.contains("past-cut"), "names the pins at and past the cut: " + text);
        assertFalse(text.contains("before-cut"), "a record ending at the cut is whole: " + text);
        printed(e);
        assertEquals(files, filesUnder(journal()), "nothing changed on disk: the segment is not cut on a refusal");
        assertEquals(rows, pgState(), "nothing changed in Postgres");

        sql("delete from work where id = 'at-cut'");
        sql("delete from body_refs where message_id = 'past-cut'");
        Node n = start(env);
        assertArrayEquals(JournalFixture.body("before-cut", 200), n.journal().read(before), "witness: the start goes on, the record before the cut reads");
    }

    /**
     * A row whose segment file is gone is dropped when nothing pins it: an archived ref, a lost ref, a lost work row, a DLQ row
     * whose ref is lost. The node starts; the rows naming the bodies stay; a segment that still has its file keeps its row.
     */
    @Test
    void anUnpinnedRowWithoutAFileIsDropped() throws Exception {
        Map<String, String> env = pinned(1);
        Map<String, JournalRef> refs = firstRun(env, "archived", "lost-ref", "lost-work", "lost-parked", "live");
        keptRef("archived", refs.get("archived"), true);
        keptRef("lost-ref", refs.get("lost-ref"), false);
        workRow("lost-work", NODE_ID, refs.get("lost-work"));
        keptRef("lost-parked", refs.get("lost-parked"), false);
        parked("lost-parked");
        workRow("live", NODE_ID, refs.get("live"));
        sql("update body_refs set lost_at = now() where message_id in ('lost-ref', 'lost-parked')");
        sql("update work set lost_at = now() where id = 'lost-work'");
        List<Long> unpinned = new ArrayList<>();
        for (String id : List.of("archived", "lost-ref", "lost-work", "lost-parked")) {
            unpinned.add(refs.get(id).segment());
            Files.delete(segmentFile(journal(), refs.get(id).segment()));
        }
        assertEquals(4, unpinned.stream().distinct().count(), "fixture: a segment each " + refs);

        Node n = start(env);

        assertEquals(200, health(n).statusCode());
        TreeSet<Long> rows = segmentRows(NODE_ID);
        for (long seg : unpinned) assertFalse(rows.contains(seg), "the row of unpinned segment " + seg + " is dropped: " + rows);
        assertTrue(rows.contains(refs.get("live").segment()), "a segment with its file keeps its row: " + rows);
        assertEquals(3, db.count("select count(*) from body_refs"), "the refs stay");
        assertEquals(2, db.count("select count(*) from work"), "the work rows stay");
        assertEquals(1, db.count("select count(*) from dlq"), "the DLQ row stays");
    }

    /**
     * FLOTTILA_JOURNAL_ACCEPT_LOSS = the printed value starts the node and gives the loss up in one transaction: the lost work
     * rows and refs get lost_at (one instant, the accepted_at of the journal_losses row), nothing is deleted, each item is an
     * ERROR, and a held work row is never claimed. The live ones are untouched and claimed.
     */
    @Test
    void acceptLossWithThisJournalIdHoldsTheLostItems() throws Exception {
        Map<String, String> env = pinned(3);
        Map<String, JournalRef> refs = firstRun(env, "lost-work", "lost-kept", "lost-parked", "live-work", "live-kept");
        workRow("lost-work", NODE_ID, refs.get("lost-work"));
        keptRef("lost-kept", refs.get("lost-kept"), false);
        keptRef("lost-parked", refs.get("lost-parked"), false);
        parked("lost-parked");
        workRow("live-work", NODE_ID, refs.get("live-work"));
        keptRef("live-kept", refs.get("live-kept"), false);
        Files.delete(segmentFile(journal(), refs.get("lost-work").segment()));
        String value = printed(refused(env, "the loss is not accepted yet"));
        UUID journalId = journalIdOf(journal());

        env.put(ACCEPT, value);
        Node n = start(env);

        assertEquals(200, health(n).statusCode(), "the accepted loss starts the node");
        assertEquals(Map.of("lost-work", true, "live-work", false), lost("work", "id", List.of("lost-work", "live-work")));
        assertEquals(Map.of("lost-kept", true, "lost-parked", true, "live-kept", false), lost("body_refs", "message_id", List.of("lost-kept", "lost-parked", "live-kept")));
        assertEquals(1, db.count("select count(*) from dlq where id = 'lost-parked'"), "the DLQ row is not deleted");
        assertEquals(List.of(NODE_ID + " " + value + " true"), losses(), "the accepted digest is recorded: " + journalId);
        assertEquals(1, db.count("select count(distinct t) from (select lost_at t from work where lost_at is not null union all "
                + "select lost_at from body_refs where lost_at is not null union all select accepted_at from journal_losses) x"),
                "one transaction: every mark and the record carry one instant");
        for (String id : List.of("lost-work", "lost-kept", "lost-parked")) assertFalse(severeNaming(id).isEmpty(), "an ERROR names " + id + ": " + logs.all());
        for (String id : List.of("live-work", "live-kept")) assertEquals(List.of(), severeNaming(id), "no ERROR names " + id);
        List<WorkQueue.Item> claimed = new PgWorkQueue(db.pool(), NODE_ID).claim("w-test", "orders", 10, LEASE).await();
        assertEquals(List.of("live-work"), claimed.stream().map(WorkQueue.Item::messageId).toList(), "a held item is never claimed; the live one is");
    }

    /** Any value but exactly the printed one refuses and changes nothing; the accepted value again, with no new loss, accepts nothing new (WARNING). */
    @Test
    void aWrongOrStaleAcceptLossAcceptsNothing() throws Exception {
        Map<String, String> env = pinned(2);
        Map<String, JournalRef> refs = firstRun(env, "lost-a", "lost-b", "live");
        workRow("lost-a", NODE_ID, refs.get("lost-a"));
        keptRef("lost-b", refs.get("lost-b"), false);
        workRow("live", NODE_ID, refs.get("live"));
        Files.delete(segmentFile(journal(), refs.get("lost-a").segment()));
        String value = printed(refused(env, "not accepted"));
        String digest = value.substring(37);
        String flipped = digest.substring(0, digest.length() - 1) + (digest.endsWith("0") ? "1" : "0");
        Map<String, String> files = filesUnder(journal());
        List<String> rows = pgState();

        for (String wrong : List.of(value.substring(0, 37) + flipped, UUID.randomUUID() + ":" + digest, value + " ", " " + value,
                value.toUpperCase(java.util.Locale.ROOT), value.substring(0, 36), "true", "")) {
            env.put(ACCEPT, wrong);
            Throwable e = refused(env, "'" + wrong + "' is not the printed value");
            assertEquals(value, printed(e), "the refusal prints the loss's value again");
            assertEquals(files, filesUnder(journal()), "'" + wrong + "': nothing changed on disk");
            assertEquals(rows, pgState(), "'" + wrong + "': nothing changed in Postgres");
        }

        env.put(ACCEPT, value);
        close(start(env));
        List<String> accepted = losses();
        Map<String, Boolean> marks = lost("work", "id", List.of("lost-a", "live"));
        String instants = String.valueOf(db.count("select count(*) from body_refs where lost_at is not null")) + losses();
        int warnings = logs.warningsNaming(value).size();

        Node again = start(env);

        assertEquals(200, health(again).statusCode(), "no new loss: the node starts");
        assertEquals(accepted, losses(), "a value already recorded accepts nothing new");
        assertEquals(marks, lost("work", "id", List.of("lost-a", "live")));
        assertEquals(instants, String.valueOf(db.count("select count(*) from body_refs where lost_at is not null")) + losses());
        assertEquals(warnings + 1, logs.warningsNaming(value).size(), "a WARNING names the stale value: " + logs.all());
    }

    /** A later loss is refused under the first value (WARNING), prints its own, and is accepted only by that one. */
    @Test
    void aSecondLossNeedsItsOwnAcceptance() throws Exception {
        Map<String, String> env = pinned(2);
        Map<String, JournalRef> refs = firstRun(env, "first-a", "first-b", "second-a", "second-b", "live");
        assertNotEquals(refs.get("first-a").segment(), refs.get("second-a").segment(), "fixture: two segments " + refs);
        workRow("first-a", NODE_ID, refs.get("first-a"));
        keptRef("first-b", refs.get("first-b"), false);
        workRow("second-a", NODE_ID, refs.get("second-a"));
        keptRef("second-b", refs.get("second-b"), false);
        workRow("live", NODE_ID, refs.get("live"));
        Files.delete(segmentFile(journal(), refs.get("first-a").segment()));
        String v1 = printed(refused(env, "the first loss"));
        env.put(ACCEPT, v1);
        close(start(env));
        List<String> firstLost = new ArrayList<>();
        for (Row r : db.pool().query("select message_id, lost_at::text from body_refs where lost_at is not null union all select id, lost_at::text from work "
                + "where lost_at is not null order by 1").execute().await()) firstLost.add(r.getString(0) + " " + r.getString(1));
        Files.delete(segmentFile(journal(), refs.get("second-a").segment()));
        int warnings = logs.warningsNaming(v1).size();

        Throwable e = refused(env, "a later loss under the first value");

        String v2 = printed(e);
        assertNotEquals(v1, v2, "another loss, another digest");
        assertEquals(v1.substring(0, 36), v2.substring(0, 36), "of the same journal");
        assertTrue(text(e).contains("second-a") && text(e).contains("second-b"), "names the new loss: " + text(e));
        assertFalse(text(e).contains("first-a") || text(e).contains("first-b"), "lost items pin nothing: " + text(e));
        assertEquals(warnings + 1, logs.warningsNaming(v1).size(), "a WARNING: the value set is recorded already: " + logs.all());
        assertEquals(Map.of("second-a", false), lost("work", "id", List.of("second-a")), "the new loss is not given up");
        assertEquals(1, losses().size());

        env.put(ACCEPT, v2);
        assertEquals(200, health(start(env)).statusCode(), "its own value accepts it");
        assertEquals(Map.of("second-a", true), lost("work", "id", List.of("second-a")));
        assertEquals(Map.of("second-b", true), lost("body_refs", "message_id", List.of("second-b")));
        assertEquals(2, losses().size(), "each accepted digest is recorded: " + losses());
        List<String> stillFirst = new ArrayList<>();
        for (Row r : db.pool().query("select message_id, lost_at::text from body_refs where message_id like 'first-%' and lost_at is not null union all "
                + "select id, lost_at::text from work where id like 'first-%' and lost_at is not null order by 1").execute().await()) stillFirst.add(r.getString(0) + " " + r.getString(1));
        assertEquals(firstLost, stillFirst, "the first loss's marks are unchanged");
    }

    /**
     * The NODE-lost guard (carried from CORE-4c1 r2): no NODE and no journal_nodes row, but segment files — the start is refused
     * (a lost NODE on a fresh database must not begin a journal over one), nothing changed, and the printed value accepts it.
     */
    @Test
    void aLostNodeFileWithSegmentFilesRefuses() throws Exception {
        close(start(env("dev")));
        assertFalse(segmentFiles(journal()).isEmpty(), "fixture: segment files");
        Files.delete(journal().resolve("NODE"));
        try (CaseDatabase fresh = CaseDatabase.create(pg, vertx, "node_journal_lost_")) {
            Map<String, String> env = env("dev");
            env.put("FLOTTILA_PG_DATABASE", fresh.name());
            Map<String, String> files = filesUnder(journal());

            Throwable e = refused(env, "no NODE, no row, segment files");

            String value = printed(e);
            assertEquals(files, filesUnder(journal()), "no NODE written, no segment file unlinked");
            assertEquals(0, fresh.count("select count(*) from journal_nodes"), "no row registered");
            assertEquals(0, fresh.count("select count(*) from journal_segments"));

            env.put(ACCEPT, value);
            assertEquals(200, health(start(env)).statusCode(), "the printed value accepts the loss");
            UUID now = journalIdOf(journal());
            assertEquals(now, fresh.pool().preparedQuery("select journal_id from journal_nodes where node = $1").execute(Tuple.of(NODE_ID)).await()
                    .iterator().next().getUUID(0), "NODE and its row name one journal");
        }
    }

    /**
     * A journal gone by identity — the journal directory without NODE, or with another journal's NODE — is refused printing the
     * registered journal's id; its printed value starts the node on a journal id other than the old one, NODE and its row agree,
     * and every pin into the old journal is given up (lost_at, recorded under the old id).
     */
    @ParameterizedTest
    @ValueSource(strings = {"no NODE file", "another journal's NODE"})
    void anIdentityLossIsAcceptedByItsPrintedValue(String variant) throws Exception {
        Map<String, String> env = pinned(65_536);
        Map<String, JournalRef> refs = firstRun(env, "old-work", "old-kept");
        UUID old = journalIdOf(journal());
        workRow("old-work", NODE_ID, refs.get("old-work"));
        keptRef("old-kept", refs.get("old-kept"), false);
        Path dir = journal();
        if (variant.equals("no NODE file")) {
            dir = data.resolve("new-journal");
            env.put("FLOTTILA_JOURNAL_DIR", dir.toString());
        } else {
            Path otherJournal = data.resolve("other-journal");
            try (CaseDatabase other = CaseDatabase.create(pg, vertx, "other_identity_")) {
                Map<String, String> elsewhere = env("dev");
                elsewhere.put("FLOTTILA_PG_DATABASE", other.name());
                elsewhere.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve("other-data")).toString());
                elsewhere.put("FLOTTILA_JOURNAL_DIR", otherJournal.toString());
                close(start(elsewhere));
            }
            Files.copy(otherJournal.resolve("NODE"), journal().resolve("NODE"), StandardCopyOption.REPLACE_EXISTING);
        }

        Throwable e = refused(env, variant);

        String value = printed(e);
        assertEquals(old.toString(), value.substring(0, 36), "the journal given up is the registered one");
        assertTrue(text(e).contains("old-work") && text(e).contains("old-kept"), "names its pins: " + text(e));

        env.put(ACCEPT, value);
        assertEquals(200, health(start(env)).statusCode(), "the printed value accepts the identity loss");
        UUID now = journalIdOf(dir);
        assertNotEquals(old, now, "a journal id other than the old one");
        assertEquals(Map.of(NODE_ID, now), journalNodes(), "replaces the old row; NODE and row agree");
        assertEquals(Map.of("old-work", true), lost("work", "id", List.of("old-work")));
        assertEquals(Map.of("old-kept", true), lost("body_refs", "message_id", List.of("old-kept")));
        assertEquals(List.of(NODE_ID + " " + value + " true"), losses());
    }

    /** Usable space + journal bytes below FLOTTILA_JOURNAL_MAX_BYTES (1 PiB here, a real file system) refuses the start, naming it; nothing changes. */
    @Test
    void tooLittleFreeSpaceRefusesStart() throws Exception {
        close(start(env("dev")));
        long max = 1L << 50;
        assertTrue(Files.getFileStore(journal()).getUsableSpace() < max / 2, "fixture: this file system holds far less than 1 PiB");
        Map<String, String> files = filesUnder(journal());
        List<String> rows = pgState();
        Map<String, String> env = env("dev");
        env.put("FLOTTILA_JOURNAL_MAX_BYTES", String.valueOf(max));

        Throwable e = refused(env, "MAX_BYTES above what the file system can hold");

        assertTrue(text(e).contains("FLOTTILA_JOURNAL_MAX_BYTES") && text(e).contains(String.valueOf(max)), "names the key and its value: " + text(e));
        assertEquals(files, filesUnder(journal()), "nothing changed on disk");
        assertEquals(rows, pgState(), "nothing changed in Postgres");
        assertEquals(200, health(start(env("dev"))).statusCode(), "witness: the default MAX_BYTES starts");
    }

    /** A child JVM holding {@code <journal>/LOCK} until its stdin closes. */
    Process lockHolder() throws Exception {
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), JournalLockHolderChild.class.getName(), journal().toString());
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(data.resolve("child-stderr.txt").toFile());
        builder.environment().clear();
        Process child = builder.start();
        children.add(child);
        BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
        OnVirtualThread.Call<String> firstLine = OnVirtualThread.start((java.util.concurrent.Callable<String>) out::readLine);
        assertEquals("locked", OnVirtualThread.join(firstLine), "the child holds <journal>/LOCK: " + Files.readString(data.resolve("child-stderr.txt")));
        return child;
    }

    /** A crashed generation's MASTER.*.tmp is removed at the next start, under LOCK: not while another process holds it. MASTER and other files stay. */
    @Test
    void aCrashedMasterTmpIsRemoved() throws Exception {
        Map<String, String> env = env("dev");
        env.remove(KEY);
        env.put(GENERATE, "true");
        close(start(env));
        byte[] master = Files.readAllBytes(journal().resolve("MASTER"));
        Path tmp = Files.writeString(journal().resolve("MASTER.4242017.tmp"), "half a key a crashed start left (test)");
        Path other = Files.writeString(journal().resolve("MASTER.keep"), "not a generation's tmp");

        Process child = lockHolder();
        refused(env, "another process holds the LOCK");
        assertTrue(Files.exists(tmp), "not removed without the LOCK (another start's generation may be in use)");
        child.getOutputStream().close();
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child lets the lock go");

        assertEquals(200, health(start(env)).statusCode());
        assertFalse(Files.exists(tmp), "the crashed generation's tmp is removed under LOCK");
        assertArrayEquals(master, Files.readAllBytes(journal().resolve("MASTER")), "MASTER is kept");
        assertTrue(Files.exists(other), "a file that is no MASTER.*.tmp is kept");
    }

    /** D-106: the admin health names a generated master key in its notices (no key bytes); a configured key has none. */
    @Test
    void theAdminHealthNamesAGeneratedKey() throws Exception {
        Node configured = start(env("dev"));
        HttpResponse<String> plain = health(configured);
        assertEquals(200, plain.statusCode(), plain.body());
        assertTrue(new JsonObject(plain.body()).getJsonArray("notices", new JsonArray()).isEmpty(), "a configured key: no notice: " + plain.body());
        close(configured);

        try (CaseDatabase fresh = CaseDatabase.create(pg, vertx, "node_journal_generated_")) {
            Map<String, String> env = env("dev");
            env.remove(KEY);
            env.put(GENERATE, "true");
            env.put("FLOTTILA_PG_DATABASE", fresh.name());
            env.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve("generated-data")).toString());
            HttpResponse<String> h = health(start(env));

            assertEquals(200, h.statusCode(), "a notice is no failure: " + h.body());
            JsonArray notices = new JsonObject(h.body()).getJsonArray("notices", new JsonArray());
            assertTrue(notices.stream().anyMatch(n -> String.valueOf(n).contains(GENERATE)), "a notice names " + GENERATE + ": " + h.body());
            byte[] master = Files.readAllBytes(data.resolve("generated-data").resolve("journal").resolve("MASTER"));
            assertCarriesNoKey(h.body(), List.of(new String(master, StandardCharsets.ISO_8859_1).strip()), master);
        }
    }
}
