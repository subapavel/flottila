package dev.rail8.bench;

import java.io.IOException;

/**
 * Counts rows of {@code system.query_log} that must be there: the server stamps a query's {@code QueryFinish} row, then queues
 * it, and a {@code SYSTEM FLUSH LOGS} sent right after the last read can take the queue before that row is in it (FIX-219: 19 of
 * 20 on the first look, 20 on the second, under load). A look is one flush, then one count; the count of the first look that reaches
 * the expected number is returned, else the last look's count. The wait is the flush itself: no sleep, no clock; a failing flush
 * or count propagates and is not retried.
 */
final class QueryLogCount {

    /**
     * Looks at most, the first included. The probe measured one row stamped 1.1 ms before the flush that missed it (that flush took
     * 191 ms on the loaded machine) and carried by a later flush; it did not measure how soon after, so the second look is the one
     * that is known to be needed and the third is the spare. Each look is a whole flush, so the bound stays small; a count still
     * short after it is the caller's to refuse.
     */
    static final int MAX_LOOKS = 3;

    /** {@code SYSTEM FLUSH LOGS}. */
    interface Flush {
        void run() throws IOException, InterruptedException;
    }

    /** The count query over the rows expected. */
    interface Count {
        long get() throws IOException, InterruptedException;
    }

    /** The count answered and the looks taken to get it. */
    record Looked(long count, int looks) {}

    private QueryLogCount() {}

    static Looked await(long expected, Flush flush, Count count) throws IOException, InterruptedException {
        for (int looks = 1; ; looks++) {
            flush.run();
            long seen = count.get();
            if (seen >= expected || looks == MAX_LOOKS) return new Looked(seen, looks);
        }
    }
}
