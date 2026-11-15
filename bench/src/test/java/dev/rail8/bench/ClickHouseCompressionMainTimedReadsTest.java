package dev.rail8.bench;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-219: how the harness USES {@link QueryLogCount}: the step "count the timed reads in the log". The seam's own rules are in
 * {@link QueryLogCountTest}; here the expected number, the statements and the answer's reading are the harness's.
 *
 * <p>Extraction assumed (new, package-private in {@code ClickHouseCompressionMain}, the call site then has nothing to choose):
 * <pre>
 * interface Sql { String query(String statement) throws IOException, InterruptedException; }   // ch::query satisfies it
 * static QueryLogCount.Looked countTimedReads(Sql sql, String qid, int warmupReads, LatencyStats timed)
 *         throws IOException, InterruptedException;
 * </pre>
 * It expects as many rows as {@code timed} holds (the reads that returned and were timed, not the reads planned), sends
 * {@code SYSTEM FLUSH LOGS} then the count of the cell's read rows per look (the answer is TSV text, {@code "20\n"}), and nothing
 * else.
 */
class ClickHouseCompressionMainTimedReadsTest {

    static final String FLUSH = "SYSTEM FLUSH LOGS";
    static final String QID = "core2-1a2b3c4d-p-8k-mixed-json-z3-r1";

    /** A scripted server: records each statement as "flush", "count" or "other: <text>"; the n-th count answers {@code counts.apply(n)}. */
    private static final class Server {
        final List<String> statements = new ArrayList<>();
        final List<String> counted = new ArrayList<>();
        final IntFunction<Long> counts;
        int served;

        Server(IntFunction<Long> counts) {
            this.counts = counts;
        }

        String query(String sql) {
            if (sql.equals(FLUSH)) {
                statements.add("flush");
                return "";
            }
            if (sql.startsWith("SELECT count()")) {
                statements.add("count");
                counted.add(sql);
                return counts.apply(++served) + "\n";
            }
            statements.add("other: " + sql);
            return "";
        }
    }

    private static LatencyStats timed(int planned, int returned) {
        LatencyStats s = new LatencyStats(Math.max(1, planned));
        for (int i = 0; i < returned; i++) s.record(1_000_000L);
        return s;
    }

    @Test
    void twentyTimedReadsWhereTheLogAnswersNineteenThenTwentyTakeTwoLooksAndReturnTwenty() throws Exception {
        Server log = new Server(n -> n == 1 ? 19L : 20L);
        QueryLogCount.Looked got = ClickHouseCompressionMain.countTimedReads(log::query, QID, 3, timed(20, 20));
        assertEquals(new QueryLogCount.Looked(20, 2), got, "the late row is found by the second flush");
        assertEquals(List.of("flush", "count", "flush", "count"), log.statements);
    }

    @Test
    void twentyTimedReadsWhereTheLogAnswersTwentyAtOnceTakeOneFlushAndOneCount() throws Exception {
        Server log = new Server(n -> 20L);
        QueryLogCount.Looked got = ClickHouseCompressionMain.countTimedReads(log::query, QID, 3, timed(20, 20));
        assertEquals(new QueryLogCount.Looked(20, 1), got);
        assertEquals(List.of("flush", "count"), log.statements, "no second look when the log is whole");
    }

    @Test
    void theExpectedNumberIsTheReadsThatReturnedNotTheReadsPlanned() throws Exception {
        Server log = new Server(n -> 18L);   // 2 of 20 planned reads failed: not timed, not expected in the log
        QueryLogCount.Looked got = ClickHouseCompressionMain.countTimedReads(log::query, QID, 3, timed(20, 18));
        assertEquals(new QueryLogCount.Looked(18, 1), got);
        assertEquals(List.of("flush", "count"), log.statements);
    }

    @Test
    void noTimedReadIsWholeAtOnceWithOneLook() throws Exception {
        Server log = new Server(n -> 0L);
        QueryLogCount.Looked got = ClickHouseCompressionMain.countTimedReads(log::query, QID, 3, timed(5, 0));
        assertEquals(new QueryLogCount.Looked(0, 1), got);
        assertEquals(List.of("flush", "count"), log.statements);
    }

    @Test
    void aCountThatStaysShortIsReturnedShortAfterTheBoundsLooks() throws Exception {
        Server log = new Server(n -> 19L);
        QueryLogCount.Looked got = ClickHouseCompressionMain.countTimedReads(log::query, QID, 3, timed(20, 20));
        assertEquals(new QueryLogCount.Looked(19, QueryLogCount.MAX_LOOKS), got, "what the log holds, not the expected number");
        assertEquals(QueryLogCount.MAX_LOOKS, log.counted.size());
    }

    @Test
    void theCountStatementNamesTheCellsReadRowsTheFinishTypeAndTheWarmUpBound() throws Exception {
        Server log = new Server(n -> 20L);
        ClickHouseCompressionMain.countTimedReads(log::query, QID, 7, timed(20, 20));
        assertEquals(1, log.counted.size());
        String sql = log.counted.get(0);
        assertTrue(sql.startsWith("SELECT count() FROM system.query_log WHERE "), sql);
        assertTrue(sql.contains("query_id LIKE '" + QID + "-read-%'"), "the cell's reads only: " + sql);
        assertTrue(sql.contains("type = 'QueryFinish'"), sql);
        assertTrue(sql.contains(">= 7"), "warm-up reads (index below 7) are not counted: " + sql);
        assertTrue(sql.contains("splitByChar('-', query_id)[-1]"), "the read index is the id's last part: " + sql);
        assertTrue(sql.endsWith("FORMAT TSV"), "the answer is read as TSV: " + sql);
    }

    @Test
    void aFailingFlushOrCountPropagatesAndIsNotRetried() {
        List<String> sent = new ArrayList<>();
        IOException flush = assertThrows(IOException.class, () -> ClickHouseCompressionMain.countTimedReads(sql -> {
            sent.add(sql);
            throw new IOException("HTTP 500: flush refused");
        }, QID, 3, timed(20, 20)));
        assertEquals("HTTP 500: flush refused", flush.getMessage());
        assertEquals(List.of(FLUSH), sent);
        List<String> sent2 = new ArrayList<>();
        IOException count = assertThrows(IOException.class, () -> ClickHouseCompressionMain.countTimedReads(sql -> {
            sent2.add(sql);
            if (sql.equals(FLUSH)) return "";
            throw new IOException("HTTP 500: count refused");
        }, QID, 3, timed(20, 20)));
        assertEquals("HTTP 500: count refused", count.getMessage());
        assertEquals(2, sent2.size(), "one flush, one failed count, nothing after");
    }
}
