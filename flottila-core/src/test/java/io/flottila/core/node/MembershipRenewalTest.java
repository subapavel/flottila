package io.flottila.core.node;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Membership.Renewal#peers} (CORE-5a): the live peers' addresses a renew answers, kept as given and never changed after. */
class MembershipRenewalTest {

    @Test
    void aRenewalKeepsTheAddressesItWasGivenAndNoLaterChange() {
        Map<String, String> given = new HashMap<>(Map.of("n-b", "10.0.0.2:8083"));
        Membership.Renewal r = new Membership.Renewal(true, 2, List.of(), given);
        given.put("n-c", "10.0.0.3:8083");
        assertEquals(Map.of("n-b", "10.0.0.2:8083"), r.peers(), "a change to the map given after is not the renewal's");
        assertThrows(UnsupportedOperationException.class, () -> r.peers().put("n-d", "x"), "nor can its reader change it");
        assertThrows(NullPointerException.class, () -> new Membership.Renewal(true, 1, List.of(), null), "no peers is an empty map, never null");
    }

    @Test
    void aRenewalWithoutAddressesAnswersNoPeers() {
        assertTrue(new Membership.Renewal(true, 1, List.of("n-x")).peers().isEmpty(), "a membership without addresses answers none");
        assertTrue(new InMemoryMembership(io.flottila.testkit.FakeClock.at(java.time.Instant.EPOCH)).renew("n-a", 1, java.time.Duration.ofSeconds(30)).result().peers().isEmpty(), "the in-memory one, too");
    }
}
