package io.flottila.testkit;

import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Key;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.Step;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0-04 done-when: a sample pipeline over the api types runs in the fake Runtime in under 100 ms. */
class SamplePipelineTest {

    static final Key<Integer> LENGTH = new Key<>("length");

    @Test
    void samplePipelineRunsInTheFakeRuntimeInUnder100Ms() {
        long started = System.nanoTime();

        FakeRuntime rt = FakeRuntime.create();
        Envelope in = rt.ingress("invoice", Headers.empty().with(Envelope.H_CONTENT_TYPE, "text/xml"), Buffer.buffer("<invoice id='4711'/>"));

        Step extractKey = e -> e.withKey("invoiceId", e.body().whole().toString().replaceAll(".*id='(\\d+)'.*", "$1"));
        Step measure = e -> e.with(LENGTH, (int) e.body().size());
        io.flottila.api.BlobStore blobs = rt.blobs();   // a step holds the store it was given, never the runtime
        Step transform = e -> e.next(blobs.write(io.flottila.api.BlobKey.body(e.id(), e.step() + 1),
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

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(elapsed.toMillis() < 100, "sample pipeline took " + elapsed.toMillis() + " ms");
    }
}
