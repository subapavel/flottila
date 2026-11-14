package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.core.flow.Intake;
import io.flottila.core.flow.SequentialIntake;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
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
        Workers workers = new Workers();
        SequentialIntake intake = new SequentialIntake(store, queue, workers, "node-1");   // P2-16a: the node's registry and id
        return new World() {
            @Override public Intake intake() { return intake; }

            @Override public BlobStore bodies() { return bodies; }

            @Override public Optional<Buffer> durable(BlobKey key) { return bodies.durable(key); }

            @Override public List<Snapshot> recorded(String messageId) { return settled(store.timeline(messageId)).result(); }

            @Override public List<Queued> queued() {
                return queue.snapshot().stream().map(i -> new Queued(i.messageId(), i.flow(), i.step(), i.partitionKey())).toList();
            }

            @Override public Workers workers() { return workers; }

            @Override public MessageStore messages() { return store; }

            @Override public WorkQueue queue() { return queue; }

            @Override public String node() { return "node-1"; }

            @Override public Optional<String> leaseHolder(String messageId) { return queue.leaseHolder(item(messageId).seq()); }

            @Override public int attempts(String messageId) { return item(messageId).attempts(); }

            private WorkQueue.Item item(String messageId) {
                return queue.snapshot().stream().filter(i -> i.messageId().equals(messageId)).findFirst()
                        .orElseThrow(() -> new AssertionError("no item of " + messageId + ": " + queue.snapshot()));
            }
        };
    }
}
