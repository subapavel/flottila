package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CORE-5a S1 (implementer, on {@link PgMembershipPeersIT}'s fixtures): {@link PgMembership#peerAddress} answers the address the
 * last answered renew found for a node — none before the first, none for this node, none once the node's lease ran out — and
 * {@link PgMembership#epoch} the join's epoch, the one this node's HELLO names.
 */
class PgMembershipPeerAddressIT extends PgMembershipPeersIT {

    @Test
    void thePeerAddressIsTheLastAnsweredRenews() throws Exception {
        PgMembership a = member("10.0.0.1:8083");
        long epoch = await(a.join("n-a", L));
        assertEquals(epoch, a.epoch(), "the join's epoch");
        await(member("10.0.0.2:8083").join("n-b", L));
        assertEquals(Optional.empty(), a.peerAddress("n-b"), "before the first renew answered");

        await(a.renew("n-a", epoch, L));
        assertEquals(Optional.of("10.0.0.2:8083"), a.peerAddress("n-b"), "n-b where its join wrote it");
        assertEquals(Optional.empty(), a.peerAddress("n-a"), "never this node");

        admin.query("update nodes set lease_until = now() - interval '1 hour' where node = 'n-b'").execute().await();
        await(a.renew("n-a", epoch, L));
        assertEquals(Optional.empty(), a.peerAddress("n-b"), "n-b's lease ran out: at the next renew it has no address");
    }
}
