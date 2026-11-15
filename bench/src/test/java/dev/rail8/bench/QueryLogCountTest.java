package dev.rail8.bench;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-219: the harness counts its timed reads in {@code system.query_log} through one seam, because under load a flush sent right
 * after the last read can run before that read's {@code QueryFinish} row is queued (observed: 19 of 20 on the first look, 20 on
 * the second; {@code agent/work/FIX-219-cause.md}).
 *
 * <p>Seam assumed (new, in {@code bench/src/main/java/dev/rail8/bench/QueryLogCount.java}):
 * <pre>
 * final class QueryLogCount {
 *     static final int MAX_LOOKS;                                   // total looks (flush + count), the first included; at least 2
 *     interface Flush { void run() throws IOException, InterruptedException; }      // SYSTEM FLUSH LOGS
 *     interface Count { long get() throws IOException, InterruptedException; }      // the harness's count query
 *     record Looked(long count, int looks) {}                       // the count answered, the looks taken to get it
 *     static Looked await(long expected, Flush flush, Count count) throws IOException, InterruptedException;
 * }
 * </pre>
 * A look is one flush followed by one count. {@code await} returns the count of the first look that reaches {@code expected}
 * (above it too), else the count of the last look, MAX_LOOKS looks in all, together with the number of looks taken (the harness
 * reports it, so a late row stays visible). No clock, no sleep: the wait is the flush itself.
 * What the harness does with the result (the IT's {@code readsLogged == 20}) is not this class's.
 */
class QueryLogCountTest {

    /** The scripted log: look n (1-based) answers {@code answers.applyAsLong(n)}; every flush and count is noted in order. */
    private static final class Log {
        final List<String> events = new ArrayList<>();
        final LongUnaryOperator answers;
        int flushes;
        int counts;

        Log(LongUnaryOperator answers) {
            this.answers = answers;
        }

        void flush() {
            flushes++;
            events.add("flush");
        }

        long count() {
            counts++;
            events.add("count");
            return answers.applyAsLong(counts);
        }

        QueryLogCount.Looked await(long expected) throws Exception {
            return QueryLogCount.await(expected, this::flush, this::count);
        }
    }

    private static void assertLooked(long count, int looks, QueryLogCount.Looked got) {
        assertEquals(new QueryLogCount.Looked(count, looks), got, "count and looks taken");
    }

    /** {@code looks} times a flush then a count. */
    private static List<String> looks(int looks) {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < looks; i++) {
            events.add("flush");
            events.add("count");
        }
        return events;
    }

    @Test
    void theBoundAllowsAtLeastOneMoreLookAfterTheFirst() {
        assertTrue(QueryLogCount.MAX_LOOKS >= 2, "a bound of one look cannot see a late row: " + QueryLogCount.MAX_LOOKS);
    }

    @Test
    void aCountWholeOnTheFirstLookCostsOneFlushAndOneCountAndNoSecondLook() throws Exception {
        Log log = new Log(n -> 20);
        assertLooked(20, 1, log.await(20));
        assertEquals(looks(1), log.events, "flush before the count, and no further look");
    }

    @Test
    void anExpectationOfZeroIsWholeAtOnce() throws Exception {
        Log log = new Log(n -> 0);
        assertLooked(0, 1, log.await(0));
        assertEquals(looks(1), log.events);
    }

    @Test
    void aCountShortOnTheFirstLookAndWholeOnTheSecondIsReturnedWhole() throws Exception {
        Log log = new Log(n -> n == 1 ? 19 : 20);
        assertLooked(20, 2, log.await(20));   // the late row found by the next flush
        assertEquals(looks(2), log.events, "one flush per look, and the second look is the last");
    }

    @Test
    void aCountWholeOnTheLastLookWithinTheBoundIsReturnedWholeWithAsManyFlushesAsLooks() throws Exception {
        int last = QueryLogCount.MAX_LOOKS;
        Log log = new Log(n -> n < last ? 19 : 20);
        assertLooked(20, last, log.await(20));
        assertEquals(last, log.flushes, "a flush for every look");
        assertEquals(last, log.counts);
        assertEquals(looks(last), log.events, "each count follows its own flush");
    }

    @Test
    void aCountThatStaysShortIsReturnedShortAfterExactlyTheBoundsLooks() throws Exception {
        int bound = QueryLogCount.MAX_LOOKS;
        Log log = new Log(n -> 10 + n);   // 11, 12, ...: one short of the expected number at the bound's last look
        assertLooked(10 + bound, bound, log.await(11 + bound));   // the last short count the log held, not the first, not the expected number
        assertEquals(bound, log.flushes);
        assertEquals(bound, log.counts);
        assertEquals(looks(bound), log.events);
    }

    @Test
    void aCountThatBecomesWholeOnlyAfterTheBoundIsNotWaitedFor() throws Exception {
        int bound = QueryLogCount.MAX_LOOKS;
        Log log = new Log(n -> n <= bound ? 19 : 20);
        assertLooked(19, bound, log.await(20));   // the look after the bound is never taken
        assertEquals(looks(bound), log.events);
    }

    @Test
    void aCountAboveTheExpectedNumberIsReturnedAsItIsAtOnce() throws Exception {
        Log log = new Log(n -> 21);
        assertLooked(21, 1, log.await(20));   // no later look can lower it; refusing it is the caller's
        assertEquals(looks(1), log.events);
    }

    @Test
    void aCountAboveTheExpectedNumberOnALaterLookIsReturnedAsItIsAndStopsTheLooking() throws Exception {
        Log log = new Log(n -> n == 1 ? 18 : 22);
        assertLooked(22, 2, log.await(20));
        assertEquals(looks(2), log.events);
    }

    @Test
    void aFailingFlushPropagatesAndNoCountFollowsIt() {
        Log log = new Log(n -> 20);
        IOException e = assertThrows(IOException.class, () -> QueryLogCount.await(20, () -> {
            log.flush();
            throw new IOException("flush refused");
        }, log::count));
        assertEquals("flush refused", e.getMessage());
        assertEquals(List.of("flush"), log.events, "the failure is not retried and not counted over");
    }

    @Test
    void aFailingCountOnALaterLookPropagatesAndNoFurtherLookFollows() {
        Log log = new Log(n -> 19);
        IOException e = assertThrows(IOException.class, () -> QueryLogCount.await(20, log::flush, () -> {
            long c = log.count();
            if (log.counts == 2) throw new IOException("count refused");
            return c;
        }));
        assertEquals("count refused", e.getMessage());
        assertEquals(looks(2), log.events);
    }
}
