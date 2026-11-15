package dev.rail8.bench;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-219 review round 1: the refusals the cell saw (failed inserts, a failed OPTIMIZE) are counted in {@code system.query_log}
 * ({@code queryExceptions}) through {@link QueryLogCount} too: with no read between the refused OPTIMIZE and the flush (no row was
 * inserted: no read), nothing says an exception row is queued before the client has its answer.
 *
 * <p>Extraction assumed (new, package-private in {@code ClickHouseCompressionMain}; {@code Sql} is the one of
 * {@link ClickHouseCompressionMainTimedReadsTest}):
 * <pre>
 * static QueryLogCount.Looked countQueryExceptions(Sql sql, String qid, int failedInserts, String optimizeFailure)
 *         throws IOException, InterruptedException;
 * </pre>
 * The bound is made inside: expected = {@code failedInserts} + (1 when {@code optimizeFailure != null}, the harness's own variable),
 * a lower bound (the log may hold more: refused reads, which the harness cannot tell from a content mismatch, are left out). Per look:
 * {@code SYSTEM FLUSH LOGS}, then the count of the cell's exception rows (TSV text), and nothing else.
 *
 * <p>Not pinned here: that {@code run} needs no extra look for errors the reads' looks already gave. {@code await} flushes before it
 * counts, so the errors' look always costs a flush; skipping it would need the count of one look to be shared across the two awaits,
 * a harness-wide seam.
 */
class ClickHouseCompressionMainQueryExceptionsTest {

    static final String QID = "core2-1a2b3c4d-p-8k-mixed-json-z3-r1";

    /** A scripted server: the n-th count answers {@code counts.apply(n)}; every statement is recorded as flush, count or other. */
    private static final class Server {
        final List<String> statements = new ArrayList<>();
        final List<String> counted = new ArrayList<>();
        final IntFunction<Long> counts;
        int served;

        Server(IntFunction<Long> counts) {
            this.counts = counts;
        }

        String query(String sql) {
            if (sql.equals("SYSTEM FLUSH LOGS")) {
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

    private static List<String> looks(int n) {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            events.add("flush");
            events.add("count");
        }
        return events;
    }

    @Test
    void threeFailedInsertsAndAFailedOptimizeExpectFourAndALogAnsweringThreeThenFourTakesTwoLooks() throws Exception {
        Server log = new Server(n -> n == 1 ? 3L : 4L);
        QueryLogCount.Looked got = ClickHouseCompressionMain.countQueryExceptions(log::query, QID, 3, "HTTP 403: ACCESS_DENIED");
        assertEquals(new QueryLogCount.Looked(4, 2), got, "the late exception row is found by the second flush");
        assertEquals(looks(2), log.statements);
    }

    @Test
    void aLogAnsweringTheBoundAtOnceCostsOneLook() throws Exception {
        Server log = new Server(n -> 4L);
        assertEquals(new QueryLogCount.Looked(4, 1), ClickHouseCompressionMain.countQueryExceptions(log::query, QID, 3, "refused"));
        assertEquals(looks(1), log.statements);
    }

    @Test
    void nothingRefusedExpectsNothingAndTakesOneLookWhateverTheLogHolds() throws Exception {
        Server log = new Server(n -> 5L);   // a refused read's row, say
        assertEquals(new QueryLogCount.Looked(5, 1), ClickHouseCompressionMain.countQueryExceptions(log::query, QID, 0, null));
        assertEquals(looks(1), log.statements);
        Server empty = new Server(n -> 0L);
        assertEquals(new QueryLogCount.Looked(0, 1), ClickHouseCompressionMain.countQueryExceptions(empty::query, QID, 0, null));
        assertEquals(looks(1), empty.statements);
    }

    @Test
    void aCountAboveTheBoundIsReturnedAsItIsAtOnce() throws Exception {
        Server log = new Server(n -> 6L);
        assertEquals(new QueryLogCount.Looked(6, 1), ClickHouseCompressionMain.countQueryExceptions(log::query, QID, 3, "refused"));
        assertEquals(looks(1), log.statements);
    }

    @Test
    void aCountThatStaysShortIsReturnedShortAfterTheBoundsLooks() throws Exception {
        Server log = new Server(n -> 3L);
        assertEquals(new QueryLogCount.Looked(3, QueryLogCount.MAX_LOOKS),
                ClickHouseCompressionMain.countQueryExceptions(log::query, QID, 3, "refused"), "what the log holds, not the bound");
        assertEquals(looks(QueryLogCount.MAX_LOOKS), log.statements);
    }

    @Test
    void theOptimizeCountsOnlyWhenItFailedAndTheInsertsCountOneEach() throws Exception {
        Server inserts = new Server(n -> n == 1 ? 1L : 2L);
        assertEquals(new QueryLogCount.Looked(2, 2), ClickHouseCompressionMain.countQueryExceptions(inserts::query, QID, 2, null),
                "two failed inserts, OPTIMIZE fine: expects 2, not 3");
        Server optimize = new Server(n -> n == 1 ? 0L : 1L);
        assertEquals(new QueryLogCount.Looked(1, 2), ClickHouseCompressionMain.countQueryExceptions(optimize::query, QID, 0, "refused"),
                "no failed insert, OPTIMIZE refused: expects 1");
    }

    @Test
    void theCountStatementNamesTheCellsExceptionRowsOnly() throws Exception {
        Server log = new Server(n -> 4L);
        ClickHouseCompressionMain.countQueryExceptions(log::query, QID, 3, "refused");
        assertEquals(1, log.counted.size());
        String sql = log.counted.get(0);
        assertTrue(sql.startsWith("SELECT count() FROM system.query_log WHERE "), sql);
        assertTrue(sql.contains("query_id LIKE '" + QID + "-%'"), "every statement of the cell, inserts and OPTIMIZE included: " + sql);
        assertTrue(sql.contains("'ExceptionBeforeStart'") && sql.contains("'ExceptionWhileProcessing'"), sql);
        assertFalse(sql.contains("QueryFinish"), sql);
        assertTrue(sql.endsWith("FORMAT TSV"), sql);
    }

    @Test
    void aFailingFlushOrCountPropagatesAndIsNotRetried() {
        List<String> sent = new ArrayList<>();
        IOException flush = assertThrows(IOException.class, () -> ClickHouseCompressionMain.countQueryExceptions(sql -> {
            sent.add(sql);
            throw new IOException("HTTP 500: flush refused");
        }, QID, 3, "refused"));
        assertEquals("HTTP 500: flush refused", flush.getMessage());
        assertEquals(List.of("SYSTEM FLUSH LOGS"), sent);
        List<String> sent2 = new ArrayList<>();
        IOException count = assertThrows(IOException.class, () -> ClickHouseCompressionMain.countQueryExceptions(sql -> {
            sent2.add(sql);
            if (sql.equals("SYSTEM FLUSH LOGS")) return "";
            throw new IOException("HTTP 500: count refused");
        }, QID, 3, "refused"));
        assertEquals("HTTP 500: count refused", count.getMessage());
        assertEquals(2, sent2.size(), "one flush, one failed count, nothing after");
    }
}
