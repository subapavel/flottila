package io.flottila.core.flow;

import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-15a (D-101 (2)): a builder's fence fences its workers only. An at-least-once flow's pipeline built by {@code build} and its
 * replay run under a closed fence, never asking it: no other node takes such a run over.
 */
class FlowBuilderFenceTest {

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final AtomicInteger asked = new AtomicInteger();

    FlowBuilder fenced() {
        rt.withComponent(partner);
        return new FlowBuilder(rt.runtime(), List.of(), List.of(), Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of())))
                .recording(store, Meter.system()).dlq(new InMemoryDlq()).fence(() -> {
                    asked.incrementAndGet();
                    return false;
                });
    }

    @Test
    void aBuiltPipelineAndAReplayAreNotFenced() {
        FlowBuilder b = fenced();
        FlowDefinition def = FlowDefinition.from(DeliveryTest.flow("at-least-once", List.of(TO_PARTNER)));
        Envelope in = rt.ingress("orders", Headers.empty(), Buffer.buffer("<order/>"));

        b.build(def, NO_FILES).apply(in);
        Envelope again = b.replay(def, NO_FILES).from(in.id(), 1);

        assertEquals(List.of(in.id(), again.id()), partner.sent().stream().map(Envelope::id).toList(), "both sent under a closed fence");
        assertEquals(0, asked.get(), "neither asked the fence");
    }
}
