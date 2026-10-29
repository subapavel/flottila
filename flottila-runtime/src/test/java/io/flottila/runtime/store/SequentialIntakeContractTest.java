package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.core.flow.Intake;
import io.flottila.core.flow.SequentialIntake;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.buffer.Buffer;

import java.util.List;
import java.util.Optional;

/**
 * P2-08a (D-082): core's {@code SequentialIntake} — persist beside record, then enqueue — passes the intake contract over the
 * in-memory message store and work queue, the ingress bodies in the testkit's in-memory store with a durable bound
 * ({@link BoundedBodies}).
 */
class SequentialIntakeContractTest extends IntakeContract {

    @Override
    protected World emptyWorld() {
        FakeRuntime rt = FakeRuntime.create();
        BoundedBodies bodies = new BoundedBodies(new InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT), BOUND);
        InMemoryMessageStore store = new InMemoryMessageStore();
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        SequentialIntake intake = new SequentialIntake(store, queue);
        return new World() {
            @Override public Intake intake() { return intake; }

            @Override public BlobStore bodies() { return bodies; }

            @Override public Optional<Buffer> durable(BlobKey key) { return bodies.durable(key); }

            @Override public List<Snapshot> recorded(String messageId) { return settled(store.timeline(messageId)).result(); }

            @Override public List<Queued> queued() {
                return queue.snapshot().stream().map(i -> new Queued(i.messageId(), i.flow(), i.step(), i.partitionKey())).toList();
            }
        };
    }
}
