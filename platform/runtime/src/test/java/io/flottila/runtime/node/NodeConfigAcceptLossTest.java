package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} (CORE-4c3 item 3; the implementer's): absent by default, and taken exactly as given —
 * never trimmed, an empty value kept — since only exactly the printed value accepts a loss.
 */
class NodeConfigAcceptLossTest {

    @Test
    void theAcceptedLossIsAbsentByDefaultAndTakenAsGiven() {
        assertEquals(Optional.empty(), NodeConfig.from(NodeConfigJournalLimitsTest.env()).journal().acceptLoss());
        assertEquals(Optional.of(" 0a1b:2c3d "), NodeConfig.from(with(NodeConfigJournalLimitsTest.env(), "FLOTTILA_JOURNAL_ACCEPT_LOSS", " 0a1b:2c3d "))
                .journal().acceptLoss(), "not trimmed");
        assertEquals(Optional.of(""), NodeConfig.from(with(NodeConfigJournalLimitsTest.env(), "FLOTTILA_JOURNAL_ACCEPT_LOSS", "")).journal().acceptLoss(),
                "an empty value is a value: it accepts no loss");
    }
}
