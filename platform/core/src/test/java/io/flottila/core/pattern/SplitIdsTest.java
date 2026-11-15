package io.flottila.core.pattern;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Hasher;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P2-15b (D-113): one worked case of the split id scheme beside {@link SplitTest}'s property, the ids written out. */
class SplitIdsTest {

    @Test
    void theTwoChildrenOfM1AreM1Dot0AndM1Dot1() {
        InMemoryBlobStore blobs = new InMemoryBlobStore(Hasher.sha256(), 1 << 16);
        Body first = blobs.write(BlobKey.body("m-1", 0), Buffer.buffer("<a/>"), 1024).await();
        Body second = blobs.write(BlobKey.body("m-1", 1), Buffer.buffer("<b/>"), 1024).await();
        Envelope parent = Envelope.ingress("m-1", "orders", Headers.empty(), first);

        List<String> ids = new ArrayList<>();
        new Split(e -> List.of(first, second)).apply(parent, child -> { ids.add(child.id()); return child; });

        assertEquals(List.of("m-1.0", "m-1.1"), ids);
    }
}
