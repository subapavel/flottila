package dev.rail8.core.record;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The lines a step's code logged while one run of the step went on, kept in the step's row: every line logged through
 * {@code System.Logger}, {@code java.util.logging} or SLF4J on the message's thread, at or above the level the node's log
 * shows for its logger, from the step's start until the step handed its first envelope on, returned or threw. A line logged
 * after that, or on a thread the code started itself, reaches the node's log only.
 *
 * <p>A run keeps at most {@value #MAX_LINES} lines and {@value #MAX_BYTES} bytes of them (a line weighs {@link Line#bytes}),
 * in the order they were logged. A line is kept whole or not at all: one that does not fit in what is left is counted in
 * {@link #dropped} and a later one that fits is still kept. Every value a step of the flow took as a secret is replaced by
 * its reference before the line is kept, and the line is fitted in the bound again after that.
 *
 * @param lines the lines kept, in the order they were logged
 * @param dropped the lines logged that were not kept, never negative
 * @since 0.1
 */
public record StepLog(List<Line> lines, long dropped) {

    /** The most lines one run of a step keeps. */
    public static final int MAX_LINES = 100;

    /** The most bytes of lines one run of a step keeps. */
    public static final long MAX_BYTES = 32_768L;

    /** No line kept and none dropped. */
    public static final StepLog EMPTY = new StepLog(List.of(), 0);

    public StepLog {
        lines = List.copyOf(lines);
        if (dropped < 0) throw new IllegalArgumentException("dropped < 0");
    }

    /**
     * One line.
     *
     * @param at when it was logged, on the runtime's clock
     * @param level its level; never {@code ALL} or {@code OFF}
     * @param logger the name of the logger it was logged through
     * @param text its message, formatted as the node's log formats it
     * @param thrown the failure logged with it: its class name and message, without its stack
     * @since 0.1
     */
    public record Line(Instant at, System.Logger.Level level, String logger, String text, Optional<String> thrown) {

        public Line {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(logger, "logger");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(thrown, "thrown");
            if (level == System.Logger.Level.ALL || level == System.Logger.Level.OFF) throw new IllegalArgumentException("a line's level is not " + level);
        }

        /** What the line weighs in the bound: the UTF-8 bytes of its logger, its text and its failure. */
        public long bytes() {
            return utf8(logger) + utf8(text) + thrown.map(Line::utf8).orElse(0L);
        }

        private static long utf8(String s) {
            long n = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x80) {
                    n += 1;
                } else if (c < 0x800) {
                    n += 2;
                } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    n += 4;
                    i++;
                } else {
                    n += 3;
                }
            }
            return n;
        }
    }
}
