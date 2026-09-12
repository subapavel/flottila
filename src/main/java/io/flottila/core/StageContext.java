package io.flottila.core;

import java.time.Duration;

/** Dependencies injected into a stage at build time. After build, the stage holds nothing else. */
public record StageContext(String flow, Monitor monitor, Dlq dlq, RetryPolicy retry, int concurrency) {

    public record RetryPolicy(int attempts, Duration initialBackoff, Duration maxBackoff) {
        public static RetryPolicy none() { return new RetryPolicy(0, Duration.ZERO, Duration.ZERO); }
    }

    /** Called after every stage with the produced envelope (or the failure). Must be cheap and non-blocking. */
    public interface Monitor {
        void snapshot(String stage, Envelope in, Envelope out, long latencyNanos, Throwable error);
    }

    public interface Dlq {
        void park(String stage, Envelope e, Throwable t);
    }
}
