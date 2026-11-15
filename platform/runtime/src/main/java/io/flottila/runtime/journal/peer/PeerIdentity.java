package io.flottila.runtime.journal.peer;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a node's HELLO says of it (CORE-5 S2, W7) and the keys it proves with.
 *
 * @param node    the node id
 * @param epoch   its incarnation (the membership's epoch)
 * @param journal its journal's id ({@code journal_nodes.journal_id}): a READ naming another journal id answers MISS (W3)
 * @param masters the environment's master keys (D-106), the current first, then the previous one: a dialer proves under the
 *                current one; a listener accepts a dialer under any of them (a rolling rotation adds the new key as the second
 *                entry on every node first, then promotes it, O6)
 * @since 0.1
 */
public record PeerIdentity(String node, long epoch, UUID journal, List<SecretKey> masters) {

    public PeerIdentity {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(journal, "journal");
        masters = List.copyOf(Objects.requireNonNull(masters, "masters"));
        if (masters.isEmpty()) throw new IllegalArgumentException("a peer identity needs the environment's master key");
    }

    /** Its node, epoch and journal id and its keys' fingerprints (as a HELLO names them, current first): never a key's bytes (r2 R4). */
    @Override
    public String toString() {
        return "PeerIdentity[node=" + node + ", epoch=" + epoch + ", journal=" + journal + ", masters="
                + masters.stream().map(k -> java.util.HexFormat.of().formatHex(PeerHandshake.fingerprint(k))).toList() + "]";
    }
}
