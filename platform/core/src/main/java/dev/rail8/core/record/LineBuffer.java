package dev.rail8.core.record;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The lines of one run of a step. While {@link #during} runs, the buffer is bound on its thread and on the forks a
 * structured scope starts there, and {@link LineCapture} offers it every line logged there. It takes lines in the bound of
 * {@link StepLog} until it is sealed, then takes none. Its methods may be called from several threads.
 */
final class LineBuffer {

    private static final ScopedValue<LineBuffer> CURRENT = ScopedValue.newInstance();

    private final Clock clock;
    private final List<StepLog.Line> lines = new ArrayList<>();   // guarded by this
    private long bytes;                                           // guarded by this
    private long dropped;                                         // guarded by this
    private boolean sealed;                                       // guarded by this

    /** A buffer that times its lines on {@code clock}. */
    LineBuffer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The buffer bound on this thread, or null. */
    static LineBuffer current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /** What {@code body} gives, run with this buffer bound; what it throws passes through as thrown. */
    <T> T during(Supplier<T> body) {
        return ScopedValue.where(CURRENT, this).call(body::get);
    }

    /**
     * A line logged now. Until the buffer is sealed it is taken if it fits in what is left of the bound, else counted as
     * dropped; after that it is neither. A null logger is the empty name, a null text {@code "null"}; each NUL and each
     * unpaired surrogate is replaced by U+FFFD, so every store can keep the text as it is.
     */
    void offer(System.Logger.Level level, String logger, String text, String thrown) {
        StepLog.Line line = new StepLog.Line(clock.instant(), level, clean(Objects.toString(logger, "")), clean(String.valueOf(text)),
                Optional.ofNullable(thrown).map(LineBuffer::clean));
        take(line);
    }

    /** Takes no more lines; gives the ones taken. */
    synchronized StepLog seal() {
        sealed = true;
        return new StepLog(lines, dropped);
    }

    /**
     * {@link #seal()}'s lines, each logger, text and failure passed through the operator {@code scrubber} gives, then fitted in
     * the bound again: a line that no longer fits is counted as dropped. The operator is asked for only when a line was taken.
     */
    StepLog kept(Supplier<UnaryOperator<String>> scrubber) {
        StepLog taken = seal();
        if (taken.lines().isEmpty()) return taken;
        UnaryOperator<String> scrub = scrubber.get();
        LineBuffer fitted = new LineBuffer(clock);
        fitted.dropped = taken.dropped();
        for (StepLog.Line l : taken.lines()) {
            fitted.take(new StepLog.Line(l.at(), l.level(), scrub.apply(l.logger()), scrub.apply(l.text()), l.thrown().map(scrub)));
        }
        return fitted.seal();
    }

    private synchronized void take(StepLog.Line line) {
        if (sealed) return;
        long size = line.bytes();
        if (lines.size() < StepLog.MAX_LINES && bytes + size <= StepLog.MAX_BYTES) {
            lines.add(line);
            bytes += size;
        } else {
            dropped++;
        }
    }

    /** {@code s} with each NUL and each unpaired surrogate replaced by U+FFFD; {@code s} itself when it has none. */
    static String clean(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                if (out != null) out.append(c).append(s.charAt(i + 1));
                i++;
                continue;
            }
            boolean bad = c == '\u0000' || Character.isSurrogate(c);
            if (bad && out == null) out = new StringBuilder(s.length()).append(s, 0, i);
            if (out != null) out.append(bad ? '�' : c);
        }
        return out == null ? s : out.toString();
    }
}
