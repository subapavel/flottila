package io.flottila.bench;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The node bench's refusals and failures that need no database (nothing listens on port 1). */
class NodeBenchMainRunTest {

    static final String[] NOWHERE = {"--host", "127.0.0.1", "--port", "1", "--scenario", "ack", "--messages", "1", "--warmup", "0"};

    @Test
    void syncCommitOffIsRefusedBeforeTheRunConnects() {
        String[] args = java.util.stream.Stream.concat(java.util.Arrays.stream(NOWHERE), java.util.stream.Stream.of("--sync-commit", "off"))
                .toArray(String[]::new);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> NodeBenchMain.run(args));
        assertTrue(refused.getMessage().contains("--sync-commit off") && refused.getMessage().contains("D-061"), refused.getMessage());
    }

    @Test
    void syncCommitIsOnOrOffAndADatabaseThatIsNoBenchOneIsRefusedBeforeTheRunConnects() {
        IllegalArgumentException value = assertThrows(IllegalArgumentException.class,
                () -> NodeBenchMain.options(new String[] {"--scenario", "ack", "--sync-commit", "yes"}));
        assertTrue(value.getMessage().contains("'yes'"), value.getMessage());
        String[] production = java.util.stream.Stream.concat(java.util.Arrays.stream(NOWHERE), java.util.stream.Stream.of("--db", "flottila"))
                .toArray(String[]::new);
        IllegalArgumentException db = assertThrows(IllegalArgumentException.class, () -> NodeBenchMain.run(production));
        assertTrue(db.getMessage().contains("'flottila'") && db.getMessage().contains("truncates"), db.getMessage());
        assertTrue(NodeBenchMain.options(new String[] {"--scenario", "ack", "--db", "nodebench_1"}).db().equals("nodebench_1"));
    }

    @Test
    void aRunThatCannotReachItsDatabaseThrowsTheFailureItself() {
        Throwable failed = assertThrows(Throwable.class, () -> NodeBenchMain.run(NOWHERE));
        assertFalse(failed instanceof ExecutionException, "the virtual thread's failure is rethrown unwrapped: " + failed);
        assertInstanceOf(RuntimeException.class, failed, "Flyway's connect failure: " + failed);
    }

    @Test
    void theInvoiceHasOneLineAtLeastAndGrowsToTheAskedSize() {
        String small = NodeBenchMain.invoice(0).toString(StandardCharsets.UTF_8);
        assertTrue(small.startsWith("<Invoice>") && small.endsWith("</Lines></Invoice>"), small);
        assertTrue(small.contains("<Sku>SKU-0</Sku>") && !small.contains("SKU-1<"), "one line: " + small);
        int big = NodeBenchMain.invoice(NodeBenchMain.XSLT_BODY_BYTES).length();
        assertTrue(big >= NodeBenchMain.XSLT_BODY_BYTES && big < NodeBenchMain.XSLT_BODY_BYTES + 200, "grown by lines to the size: " + big);
    }
}
