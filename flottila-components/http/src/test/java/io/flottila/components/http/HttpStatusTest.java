package io.flottila.components.http;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Fault;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.components.http.HttpConsumerStatusTest.TestFault;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-03b1 (D-080): {@code HttpComponent.status(Ack ack, Throwable failure)} — the http door's answer status, extracted so
 * {@code flottila test}'s {@code expect.status} is the door's own mapping (D-049): an answer that failed by its failure's
 * {@link Fault.Kind} (MALFORMED 400, INVALID 422, DOWNSTREAM 503 when the failure says the sender may retry else 502, INTERNAL
 * and no fault at all 500); an Ack by its kind (Accepted 202, a retryable Nack 503, a final Nack 400, a Reply its
 * {@code http.status} prop or 200). Exactly one of {@code ack} and {@code failure} is given. The door itself stays as
 * {@code HttpConsumerStatusTest} pins it.
 */
class HttpStatusTest {

    record Row(String what, Ack ack, Throwable failure, int status) {}

    @Test
    void theD049Table() {
        Envelope reply = FakeRuntime.create().ingress("f", Headers.empty(), Buffer.buffer("<r/>"));
        List<Row> table = List.of(
                new Row("Accepted", new Ack.Accepted("m-1"), null, 202),
                new Row("retryable Nack", new Ack.Nack("busy", true), null, 503),
                new Row("final Nack", new Ack.Nack("refused", false), null, 400),
                new Row("Reply without a status", new Ack.Reply(reply), null, 200),
                new Row("Reply with status 201", new Ack.Reply(reply.with(HttpComponent.STATUS, 201)), null, 201),
                new Row("Reply with status 299", new Ack.Reply(reply.with(HttpComponent.STATUS, 299)), null, 299),
                new Row("retryable NackException", null, new NackException("mock partner answered 503", true), 503),
                new Row("final NackException", null, new NackException("mock partner answered 404", false), 502),
                new Row("partner 503", null, new HttpStatusException(503, "down"), 503),
                new Row("partner 500", null, new HttpStatusException(500, ""), 503),
                new Row("partner 429", null, new HttpStatusException(429, "slow down"), 503),
                new Row("partner 404", null, new HttpStatusException(404, "gone"), 502),
                new Row("partner 400", null, new HttpStatusException(400, null), 502),
                new Row("MALFORMED", null, new TestFault(Fault.Kind.MALFORMED, "not xml"), 400),
                new Row("INVALID", null, new TestFault(Fault.Kind.INVALID, "schema"), 422),
                new Row("DOWNSTREAM, not saying it may be retried", null, new TestFault(Fault.Kind.DOWNSTREAM, "down"), 502),
                new Row("INTERNAL", null, new TestFault(Fault.Kind.INTERNAL, "bug"), 500),
                new Row("no fault", null, new IllegalStateException("bug"), 500),
                new Row("a checked exception", null, new IOException("disk"), 500));

        List<String> wrong = new ArrayList<>();
        for (Row row : table) {
            int status = HttpComponent.status(row.ack(), row.failure());
            if (status != row.status()) wrong.add(row.what() + ": expected " + row.status() + ", was " + status);
        }

        assertEquals(List.of(), wrong);
    }
}
