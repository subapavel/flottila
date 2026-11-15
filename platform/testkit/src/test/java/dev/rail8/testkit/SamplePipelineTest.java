package dev.rail8.testkit;

import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Key;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Stage;
import dev.rail8.api.Step;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-04 done-when: a sample pipeline over the api types runs in the fake Runtime, which means it moves no fake
 * time and leaves no timer pending; the former 100 ms wall-clock bound went because it measured the CI machine (FIX-217).
 */
class SamplePipelineTest {

    static final Key<Integer> LENGTH = new Key<>("length");

    @Test
    void samplePipelineRunsInTheFakeRuntimeWithoutAdvancingTimeOrLeavingATimerPending() {
        FakeRuntime rt = FakeRuntime.create();
        Envelope in = rt.ingress("invoice", Headers.empty().with(Envelope.H_CONTENT_TYPE, "text/xml"), Buffer.buffer("<invoice id='4711'/>"));

        Step extractKey = e -> e.withKey("invoiceId", e.body().whole().toString().replaceAll(".*id='(\\d+)'.*", "$1"));
        Step measure = e -> e.with(LENGTH, (int) e.body().size());
        dev.rail8.api.BlobStore blobs = rt.blobs();   // a step holds the store it was given, never the runtime
        Step transform = e -> e.next(blobs.write(dev.rail8.api.BlobKey.body(e.id(), e.step() + 1),
                Buffer.buffer("<partner>" + e.key("invoiceId").orElseThrow() + "</partner>"), 1_024).await());
        Pipeline p = Pipeline.of(List.of(Stage.of(extractKey), Stage.of(measure), Stage.of(transform)));

        Envelope out = p.apply(in);

        assertEquals("m-1", out.id());
        assertEquals(1, out.step(), "one new body, one step");
        assertEquals("4711", out.key("invoiceId").orElseThrow());
        assertEquals(20, out.get(LENGTH).orElseThrow());
        assertEquals("<partner>4711</partner>", out.body().whole().toString());
        assertEquals(0, in.step());
        assertTrue(rt.blobs().contains(out.body().key()));

        assertEquals(FakeRuntime.T0, rt.clock().instant(), "the run advanced no fake time");
        assertEquals(0, rt.timer().pending(), "the run left no timer pending");
    }
}
