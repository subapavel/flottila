package dev.rail8.runtime;

import io.netty.util.internal.logging.InternalLoggerFactory;
import io.vertx.core.spi.logging.LogDelegateFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On the node SLF4J logs through {@code java.util.logging}: its one provider is {@code slf4j-jdk14}, and a line a library or
 * custom code logs through SLF4J, Netty's lines among them, reaches the {@code java.util.logging} logger of the same name,
 * where the node's log and the message's history take it. Vert.x alone logs to {@code java.util.logging} straight, so that
 * its lines name their own logger in the node's log rather than SLF4J's adapter.
 */
class NodeSlf4jTest {

    @Test
    void slf4jsProviderIsJavaUtilLogging() {
        assertEquals("org.slf4j.jul.JDK14LoggerFactory", LoggerFactory.getILoggerFactory().getClass().getName());
    }

    @Test
    void anSlf4jLineAndANettyLineReachTheJavaUtilLoggingLoggerOfTheirName() {
        List<LogRecord> seen = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { seen.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger jul = Logger.getLogger("test.slf4j");
        jul.addHandler(capture);
        try {
            LoggerFactory.getLogger("test.slf4j").warn("hello {}", "there");
            InternalLoggerFactory.getInstance("test.slf4j").info("from netty");
        } finally {
            jul.removeHandler(capture);
        }
        assertEquals(List.of("test.slf4j WARNING hello there", "test.slf4j INFO from netty"),
                seen.stream().map(r -> r.getLoggerName() + " " + r.getLevel() + " " + r.getMessage()).toList());
    }

    @Test
    void theNodeHasVertxLogToJavaUtilLoggingUnlessTheOperatorNamedAnotherClass() {
        String before = System.getProperty(Main.VERTX_LOGGING);
        try {
            System.clearProperty(Main.VERTX_LOGGING);
            Main.vertxLogsToJul();
            assertEquals(Main.VERTX_JUL, System.getProperty(Main.VERTX_LOGGING));
            System.setProperty(Main.VERTX_LOGGING, "io.vertx.core.logging.SLF4JLogDelegateFactory");
            Main.vertxLogsToJul();
            assertEquals("io.vertx.core.logging.SLF4JLogDelegateFactory", System.getProperty(Main.VERTX_LOGGING));
        } finally {
            if (before == null) {
                System.clearProperty(Main.VERTX_LOGGING);
            } else {
                System.setProperty(Main.VERTX_LOGGING, before);
            }
        }
    }

    @Test
    void aVertxLineThroughTheClassTheNodeNamesHasItsLoggersNameAsItsSourceAndThroughSlf4jTheAdapters() throws ReflectiveOperationException {
        List<LogRecord> seen = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { seen.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger jul = Logger.getLogger("test.vertx");
        jul.addHandler(capture);
        try {
            for (String factory : List.of(Main.VERTX_JUL, "io.vertx.core.logging.SLF4JLogDelegateFactory")) {
                LogDelegateFactory delegates = (LogDelegateFactory) Class.forName(factory).getDeclaredConstructor().newInstance();
                assertTrue(delegates.isAvailable(), factory);
                delegates.createDelegate("test.vertx").warn("from vertx");
            }
        } finally {
            jul.removeHandler(capture);
        }
        assertEquals(List.of("test.vertx WARNING from vertx test.vertx", "test.vertx WARNING from vertx io.vertx.core.logging.SLF4JLogDelegate"),
                seen.stream().map(r -> r.getLoggerName() + " " + r.getLevel() + " " + r.getMessage() + " "
                        + (r.getSourceClassName() == null ? r.getLoggerName() : r.getSourceClassName())).toList());
    }
}
