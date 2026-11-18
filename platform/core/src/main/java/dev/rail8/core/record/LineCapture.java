package dev.rail8.core.record;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The handler on the root logger of {@code java.util.logging} that gives each line logged on a thread where a
 * {@link LineBuffer} is bound to that buffer. {@code System.Logger} and SLF4J reach it through {@code java.util.logging} on
 * the node, so it sees every line their loggers let through to the root logger: those at or above the level the node's log
 * shows for the logger. It formats the message as the node's log does, with its parameters, and shows a failure logged with
 * it by its {@code toString()}. Anywhere else it does nothing; it never logs and never throws.
 *
 * <p>{@link #install} puts it on the root logger once, and again each time the logging configuration is read, which removes
 * the root logger's handlers.
 */
final class LineCapture extends Handler {

    private static final LineCapture HANDLER = new LineCapture();
    private static final Formatter TEXT = new Formatter() {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record);
        }
    };
    private static boolean listening;   // guarded by LineCapture.class

    private LineCapture() {}

    /** Puts the handler on the root logger, unless it is there, and keeps it there when the configuration is read again. */
    static synchronized void install() {
        if (!listening) {
            LogManager.getLogManager().addConfigurationListener(LineCapture::attach);
            listening = true;
        }
        attach();
    }

    private static synchronized void attach() {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) if (h == HANDLER) return;
        root.addHandler(HANDLER);
    }

    @Override
    public void publish(LogRecord record) {
        LineBuffer buffer = LineBuffer.current();
        if (buffer == null || record == null) return;
        try {
            Throwable thrown = record.getThrown();
            buffer.offer(level(record.getLevel()), record.getLoggerName(), TEXT.formatMessage(record), thrown == null ? null : shown(thrown));
        } catch (RuntimeException notKept) {   // the line still reaches the node's log; the step goes on
        }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    /** The {@code System.Logger} level nearest to {@code level}: CONFIG is INFO, FINE is DEBUG, FINER and FINEST are TRACE. */
    static System.Logger.Level level(Level level) {
        int v = level.intValue();
        if (v >= Level.SEVERE.intValue()) return System.Logger.Level.ERROR;
        if (v >= Level.WARNING.intValue()) return System.Logger.Level.WARNING;
        if (v >= Level.CONFIG.intValue()) return System.Logger.Level.INFO;
        if (v >= Level.FINE.intValue()) return System.Logger.Level.DEBUG;
        return System.Logger.Level.TRACE;
    }

    /** {@code t}'s class name and message; its class name alone when its {@code toString()} throws. */
    private static String shown(Throwable t) {
        try {
            return t.toString();
        } catch (RuntimeException unshown) {
            return t.getClass().getName();
        }
    }
}
