package io.flottila.runtime.node;

import io.flottila.core.admission.Targets;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.flottila.runtime.node.NodeConfigTest.minimal;
import static io.flottila.runtime.node.NodeConfigTest.refusedNaming;
import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-09b (D-099), beyond the packet's case: the edges of a {@code FLOTTILA_TARGET_LIMITS} key — a scheme with no default port
 * keeps the port it names and is refused without one, a port out of 1..65535 is refused, nothing may follow the authority, a
 * negative n is no limit, a value with an {@code @} names a refused entry by position only, an empty entry is malformed, and a blank value or whitespace around an entry is no override and no refusal.
 */
class NodeConfigTargetsTest {

    static Map<String, Targets.Limit> overrides(String limits) {
        return NodeConfig.from(with(minimal(), "FLOTTILA_TARGET_LIMITS", limits)).targets().overrides();
    }

    @Test
    void aSchemeWithoutADefaultPortKeepsItsPortAndNeedsOne() {
        assertEquals(Map.of("kafka://broker.example:9092", new Targets.Limit(3, Duration.ofSeconds(5))), overrides("Kafka://Broker.Example:9092=3"),
                "lower-cased, the port as named, the default maxWait");
        refusedNaming(with(minimal(), "FLOTTILA_TARGET_LIMITS", "kafka://broker.example=3"), "FLOTTILA_TARGET_LIMITS", "kafka://broker.example=3");
    }

    @Test
    void aPortOutOfRangeOrAnythingAfterTheAuthorityIsRefusedNamingTheEntry() {
        assertEquals(Map.of("http://a.example:1", new Targets.Limit(2, Duration.ofSeconds(5)), "https://b.example:65535", new Targets.Limit(2, Duration.ofSeconds(5))),
                overrides("http://a.example:1=2,https://b.example:65535=2"), "ports 1 and 65535 are taken");
        for (String bad : List.of("http://a.example:0=2", "http://a.example:65536=2", "http://a.example/orders=2",
                "http://a.example?x=1=2", "http://=2", "http://a.example:=2", "http://a.example=-1", "http://a.example=+0")) {
            refusedNaming(with(minimal(), "FLOTTILA_TARGET_LIMITS", "http://ok.example=5," + bad), "FLOTTILA_TARGET_LIMITS", bad);
        }
    }

    /**
     * P2-09b r3 (D-099 amended): a value holding {@code @} anywhere names a refused entry by its position only — even when the
     * refused entry is another than the one with the {@code @}, and for a duplicate neither its text nor its target; without an
     * {@code @} the entry is named by its text.
     */
    @Test
    void anAtAnywhereNamesTheRefusedEntryByPositionOnly() {
        Map<String, String> byPosition = Map.of("http://a.example=0,http://u@b.example=2", "entry 1 of 2",
                "http://a.example=1,HTTP://A.example=2,x@y", "entry 2 of 3", "http://u:p@b.example=2", "entry 1 of 1");
        byPosition.forEach((value, named) -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(minimal(), "FLOTTILA_TARGET_LIMITS", value)), value);
            String message = e.getMessage();
            assertTrue(message.contains("FLOTTILA_TARGET_LIMITS " + named + " "), value + ": names the entry by its position: " + message);
            assertFalse(message.contains("a.example") || message.contains("b.example") || message.contains("u@") || message.contains("u:p"),
                    value + ": no entry's text, no target: " + message);
        });
        refusedNaming(with(minimal(), "FLOTTILA_TARGET_LIMITS", "http://a.example=1,HTTP://A.example=2"), "FLOTTILA_TARGET_LIMITS",
                "entry 'HTTP://A.example=2'", "http://a.example:80");
    }

    @Test
    void anEmptyEntryIsMalformedAndABlankValueIsNoOverride() {
        refusedNaming(with(minimal(), "FLOTTILA_TARGET_LIMITS", "http://a.example=2,,http://b.example=2"), "FLOTTILA_TARGET_LIMITS", "entry ''");
        refusedNaming(with(minimal(), "FLOTTILA_TARGET_LIMITS", "http://a.example=2,"), "FLOTTILA_TARGET_LIMITS", "entry ''");
        assertEquals(Map.of(), overrides("  "), "a blank value: no override");
        assertEquals(Map.of("http://a.example:80", new Targets.Limit(2, Duration.ofSeconds(5)), "http://b.example:80", new Targets.Limit(4, Duration.ofSeconds(5))),
                overrides(" http://a.example = 2 , http://b.example=4 "), "whitespace around a key or a number is not part of it");
    }
}
