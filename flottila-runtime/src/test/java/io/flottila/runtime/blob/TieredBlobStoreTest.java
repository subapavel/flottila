package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.flottila.testkit.BlobStoreContract;
import io.flottila.testkit.GeneratedReadStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tiers (ARCHITECTURE §4 "where the bytes live"): memory up to the per-object tier size while the node-wide byte budget
 * allows, else the journal's spill (D-105), encrypted at rest; sizes are long, every limit finite.
 *
 * <p>CORE-4c2 migrated this class from the spool to the journal: the store is {@link LocalStores}' (a journal in the case's
 * directory, no Postgres); "spooled" reads "spilled" ({@code Location.SPILL}, a file under {@code <journal>/spill}); the
 * Config has no spool directory or key and a durable bound instead. Retired with the spool, each for its reason: the spool
 * path's shape ({@code <messageId>/<step>-<part>-<n>.blob}) — a spill is named by its segment number; the refusal of a
 * message id that is not one path segment ({@code aDotDotMessageIdIsRefused} and its siblings) — no id is a path any more;
 * the spool file's exact overhead (salt, segment header, tag) — the spill is the journal's format (CORE-4b's tests).
 */
class TieredBlobStoreTest {

    static final Hasher HASHER = Hasher.sha256();
    /** The tenant key of {@link LocalStores}' journals: a fixture, never a secret. */
    static final SecretKey FIXED = key();

    static SecretKey key() {
        try {
            KeyGenerator g = KeyGenerator.getInstance("AES");
            g.init(256);
            return g.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static TieredBlobStore store(Path dir, long memoryTier, long budget) {
        return LocalStores.store(dir, new TieredBlobStore.Config(BlobStoreContract.TREE_LIMIT, memoryTier, budget, LocalStores.DURABLE));
    }

    @AfterEach
    void closeTheJournals() {
        LocalStores.closeAll();
    }

    @Test
    void limitsAreFiniteAndPositive() {
        assertThrows(IllegalArgumentException.class, () -> new TieredBlobStore.Config(0, 1024, 4096, 4096));
        assertThrows(IllegalArgumentException.class, () -> new TieredBlobStore.Config(4096, -1, 4096, 4096));
        assertThrows(IllegalArgumentException.class, () -> new TieredBlobStore.Config(4096, 1024, 0, 4096));
        assertThrows(IllegalArgumentException.class, () -> new TieredBlobStore.Config(4096, 8192, 4096, 4096), "a memory tier above the budget could never be used");
        assertThrows(IllegalArgumentException.class, () -> new TieredBlobStore.Config(4096, 1L << 31, 1L << 32, 4096), "one in-memory body is one buffer: the tier cannot exceed an int");
        assertThrows(IllegalArgumentException.class, () -> new TieredBlobStore.Config(4096, 1024, 4096, 0), "a durable bound is finite and positive, never unlimited");
        assertThrows(IllegalArgumentException.class, () -> new TieredBlobStore.Config(4096, 1024, 4096, -1));
    }

    @Test
    void smallBodiesStayInMemoryLargeOnesSpillIntoTheJournal(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 1024, 1 << 20);
        Body small = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), Buffer.buffer(new byte[1024]), 4096));
        Body large = BlobStoreContract.await(s.write(BlobKey.body("m-2", 0), Buffer.buffer(new byte[1025]), 4096));
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(small.key()));
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), s.location(large.key()));
        assertEquals(Optional.empty(), s.location(BlobKey.body("nobody", 0)));
        assertEquals(1024, s.memoryInUse());
        assertEquals(1, LocalStores.spillFiles(dir).size(), "one spill file for the large body");
    }

    @Test
    void theNodeWideByteBudgetIsTheSecondSpillTrigger(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 2048, 3000);
        Body a = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), Buffer.buffer(new byte[1600]), 4096));
        Body b = BlobStoreContract.await(s.write(BlobKey.body("m-2", 0), Buffer.buffer(new byte[1600]), 4096));
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(a.key()));
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), s.location(b.key()), "1600 fits the tier but 1600 + 1600 does not fit the budget of 3000");
        assertEquals(1600, s.memoryInUse());
        BlobStoreContract.await(s.evict(a.key()));
        assertEquals(0, s.memoryInUse());
        assertEquals(Optional.empty(), s.location(a.key()));
        assertTrue(BlobStoreContract.awaitOutcome(s.read(a.key())).failed());
        Body c = BlobStoreContract.await(s.write(BlobKey.body("m-3", 0), Buffer.buffer(new byte[1600]), 4096));
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(c.key()), "the released budget is usable again");
        assertEquals(1600, BlobStoreContract.await(s.read(b.key())).length(), "the spilled body is unaffected by the eviction");
    }

    @Test
    void aStreamThatOutgrowsTheMemoryTierSpillsMidWayAndStillHashesEveryByte(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 10_000, 1 << 20);
        long size = 100_000;
        Body body = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), new GeneratedReadStream(size, 3000), size));
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), s.location(body.key()));
        assertEquals(0, s.memoryInUse(), "the prefix buffered before the spill gave its budget back");
        assertEquals(GeneratedReadStream.expectedHash(HASHER, size), body.hash());
        assertEquals(size, body.size());
        assertEquals(GeneratedReadStream.expectedHash(HASHER, size), BlobStoreContract.await(new Reader().hashOf(s.open(body.key()))));
    }

    @Test
    void theSpillIsEncryptedAtRestAndATamperedFileDoesNotReadBack(@TempDir Path dir) throws IOException {
        TieredBlobStore s = store(dir, 0, 1 << 20);
        byte[] plain = "<secret>customer 4711 owes 1234.50 EUR</secret>".repeat(50).getBytes();
        Body body = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), Buffer.buffer(plain), 1 << 20));
        Path file = LocalStores.spillFiles(dir).get(0);
        byte[] onDisk = Files.readAllBytes(file);
        assertFalse(new String(onDisk, java.nio.charset.StandardCharsets.ISO_8859_1).contains("customer 4711"), "plaintext must not be on disk");
        assertTrue(onDisk.length >= plain.length + 16, "at least a tag around the ciphertext");
        assertEquals(Buffer.buffer(plain), BlobStoreContract.await(s.read(body.key())));
        onDisk[onDisk.length / 2] ^= 0x55;
        Files.write(file, onDisk);
        assertTrue(BlobStoreContract.awaitOutcome(s.read(body.key())).failed(), "the authentication tag catches the flip");
        assertTrue(BlobStoreContract.awaitOutcome(new Reader().hashOf(s.open(body.key()))).failed(), "streaming reads fail too");
    }

    @Test
    void aStreamAboveTheMaxSizeStopsSpillingAndLeavesNoFileBehind(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 1024, 1 << 20);
        Future<Body> f = s.write(BlobKey.body("m-1", 0), new GeneratedReadStream(200_000, 4096), 100_000);
        assertTrue(BlobStoreContract.awaitOutcome(f).failed());
        assertInstanceOf(BodyTooLargeException.class, f.cause());
        assertTrue(LocalStores.spillFiles(dir).isEmpty(), "the partial spill file was removed");
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-1", 0)));
        assertEquals(0, s.memoryInUse());
    }

    @Test
    void evictDropsASpilledBodyAndItsFile(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 0, 1 << 20);
        Body body = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), Buffer.buffer("bytes"), 4096));
        assertEquals(1, LocalStores.spillFiles(dir).size());
        BlobStoreContract.await(s.evict(body.key()));
        assertTrue(LocalStores.spillFiles(dir).isEmpty());
        assertTrue(BlobStoreContract.awaitOutcome(s.read(body.key())).failed());
        BlobStoreContract.await(s.evict(body.key()));   // evicting twice is quiet
    }

    /** D-061: a store without Postgres has no durable refs; persisting a local body is refused, and the body stays where it was. */
    @Test
    void persistWithoutADurableTierFailsIllegalState(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 1024, 1 << 20);
        Body inMemory = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), Buffer.buffer("in memory"), 4096));
        Body spilled = BlobStoreContract.await(s.write(BlobKey.body("m-2", 0), Buffer.buffer(new byte[2000]), 4096));
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(inMemory.key()), "fixture");
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), s.location(spilled.key()), "fixture");
        for (Body b : List.of(inMemory, spilled)) {
            Future<Void> f = BlobStoreContract.awaitOutcome(s.persist(b.key()));
            assertTrue(f.failed(), "no durable tier: nothing can be made durable (" + b.key() + ")");
            assertInstanceOf(IllegalStateException.class, f.cause(), "a store without a durable tier, not the caller's key: " + f.cause());
        }
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(inMemory.key()), "the body stays local");
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), s.location(spilled.key()));
        assertEquals(9, s.memoryInUse(), "its budget stays reserved");
        assertEquals(1, LocalStores.spillFiles(dir).size(), "the spill file stays");
        assertEquals("in memory", BlobStoreContract.await(s.read(inMemory.key())).toString());
        assertEquals(2000, BlobStoreContract.await(s.read(spilled.key())).length());
    }

    /** Borrow the contract's stream helpers without being a contract. */
    static final class Reader extends BlobStoreContract {
        @Override protected io.flottila.api.BlobStore store() { throw new UnsupportedOperationException(); }
        @Override public Future<Hash> hashOf(io.vertx.core.streams.ReadStream<Buffer> stream) { return super.hashOf(stream); }
    }
}
