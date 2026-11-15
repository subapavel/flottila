package io.flottila.core.pattern;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Hasher;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.buffer.Buffer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P2-15b (D-101 (3), D-112): a split child's id is stable — {@code parent.id() + "." + i}, {@code i} its index from 0 — so a
 * rerun of the parent after a takeover (another node, another Split instance, no shared id generator) sends each child under the
 * id the first run sent it with, and a partner deduping on {@code x-message-id} sees the same key twice. Within one split the
 * ids are distinct and none is the parent's.
 *
 * <p>The API assumed (packet P2-15b): {@code new Split(Function<Envelope, List<Body>> parts)} — no {@code IdGenerator}.
 */
class SplitTest {

    @Provide
    Arbitrary<String> parentIds() {
        // node-minted ids, a nested child's (already dotted) and odd ones: the scheme must not depend on the id's shape
        return Arbitraries.oneOf(
                Arbitraries.strings().withCharRange('a', 'z').numeric().withChars('-').ofMinLength(1).ofMaxLength(40),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(12).map(s -> s + ".3"),
                Arbitraries.of("m-1", "0", "a.b.c", "9f1c2e7a-8d4b-4c1e-9a3f-1b2c3d4e5f60"));
    }

    @Property(tries = 200)
    void childIdsAreStableAcrossRunsAndDistinct(@ForAll("parentIds") String parentId, @ForAll @IntRange(min = 0, max = 40) int parts) {
        InMemoryBlobStore blobs = new InMemoryBlobStore(Hasher.sha256(), 1 << 16);
        Body body = blobs.write(BlobKey.body(parentId, 0), Buffer.buffer("<l/>"), 1024).await();
        Envelope parent = Envelope.ingress(parentId, "orders", Headers.empty(), body);

        List<String> first = childIds(new Split(e -> Collections.nCopies(parts, e.body())), parent);
        List<String> rerun = childIds(new Split(e -> Collections.nCopies(parts, e.body())), parent);   // another node's build

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < parts; i++) expected.add(parentId + "." + i);
        assertEquals(expected, first, "child i of parent " + parentId + " is " + parentId + "." + "i, from 0");
        assertEquals(first, rerun, "a rerun of the same parent mints the same child ids, in the same order");
        assertEquals(parts, new HashSet<>(first).size(), "distinct within the split: " + first);
        assertFalse(first.contains(parentId), "no child takes its parent's id: " + first);
    }

    private static List<String> childIds(Split split, Envelope parent) {
        List<String> ids = new ArrayList<>();
        split.apply(parent, child -> { ids.add(child.id()); return child; });
        return ids;
    }
}
