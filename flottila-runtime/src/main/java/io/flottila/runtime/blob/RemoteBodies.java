package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.runtime.journal.JournalRef;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Where a body that lies in another node's journal is read from (CORE-5a S7): the store's read order is its own journal, then
 * this, then a retryable {@link RemoteBodyException}. The peer link's reads ({@code io.flottila.runtime.journal.peer.PeerReads})
 * are the one implementation; a store without them fails such a body retryable at once.
 *
 * @since 0.1
 */
public interface RemoteBodies {

    /**
     * The plaintext of the body under {@code key} whose record lies at {@code ref} in the journal {@code journal} of node
     * {@code owner}, blocking, on a virtual thread; every byte checked by this node (CRC32C, AEAD, the record's key).
     *
     * @throws RemoteBodyException the owner cannot be reached or does not hold the record: retryable, never other bytes
     * @throws IOException         the record read is not the key's, or fails its checks (a corrupt record)
     */
    InputStream open(BlobKey key, String owner, UUID journal, JournalRef ref) throws IOException;
}
