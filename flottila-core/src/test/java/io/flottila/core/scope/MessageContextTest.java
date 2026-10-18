package io.flottila.core.scope;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.NoBlockedPlatformThreads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ARCHITECTURE §4: "ScopedValue carries message id, tenant, trace id and deadline through every step without
 * threading them through signatures" — and through every child a RequestScope forks.
 */
@ExtendWith(NoBlockedPlatformThreads.class)
@Timeout(60)
class MessageContextTest {

    final FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
    final FakeTimer timer = new FakeTimer(clock);
    final MessageContext ctx = new MessageContext("m-1", "invoice-sync", "acme", "trace-42", Instant.parse("2026-01-01T00:00:05Z"));

    @Test
    void fieldsAreRequiredAndReadable() {
        assertEquals("m-1", ctx.messageId());
        assertEquals("invoice-sync", ctx.flow());
        assertEquals("acme", ctx.tenant());
        assertEquals("trace-42", ctx.traceId());
        assertEquals(Instant.parse("2026-01-01T00:00:05Z"), ctx.deadline());
        assertThrows(NullPointerException.class, () -> new MessageContext(null, "f", "t", "tr", Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new MessageContext("m", null, "t", "tr", Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new MessageContext("m", "f", null, "tr", Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new MessageContext("m", "f", "t", null, Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new MessageContext("m", "f", "t", "tr", null));
    }

    @Test
    void currentIsBoundOnlyInsideCallOrRun() throws Exception {
        assertFalse(MessageContext.isBound());
        assertThrows(IllegalStateException.class, MessageContext::current);
        String seen = ctx.call(() -> MessageContext.current().messageId());
        assertEquals("m-1", seen);
        AtomicReference<String> inRun = new AtomicReference<>();
        ctx.run(() -> inRun.set(MessageContext.current().traceId()));
        assertEquals("trace-42", inRun.get());
        assertFalse(MessageContext.isBound(), "unbound again after the call");
        MessageContext inner = new MessageContext("m-2", "f", "t", "tr", Instant.EPOCH);
        assertEquals("m-2", ctx.call(() -> inner.call(() -> MessageContext.current().messageId())), "a nested binding shadows the outer one");
        assertEquals("m-1", ctx.call(() -> { inner.call(() -> null); return MessageContext.current().messageId(); }), "and is gone after its call");
    }

    @Test
    void remainingIsMeasuredOnTheGivenClockAndClampsAtZero() {
        assertEquals(Duration.ofSeconds(5), ctx.remaining(clock));
        clock.advance(Duration.ofSeconds(7));
        assertEquals(Duration.ZERO, ctx.remaining(clock));
    }

    @Test
    void everyChildAndGrandchildAScopeForksSeesTheContextOfTheForkingThread() throws Exception {
        RequestScopeTest.onVirtual(() -> ctx.call(() -> {
            try (RequestScope scope = RequestScope.open(clock, timer, Duration.ofSeconds(5))) {
                RequestScope.Subtask<String> child = scope.fork(() -> MessageContext.current().messageId() + "/" + MessageContext.current().tenant());
                RequestScope.Subtask<String> grandchild = scope.fork(() -> {
                    try (RequestScope nested = RequestScope.open(clock, timer, Duration.ofSeconds(5))) {
                        RequestScope.Subtask<String> g = nested.fork(() -> MessageContext.current().traceId());
                        nested.join();
                        return g.get();
                    }
                });
                scope.join();
                assertEquals("m-1/acme", child.get());
                assertEquals("trace-42", grandchild.get());
            }
            return null;
        }));
    }

    @Test
    void aScopeOpenedOutsideAnyContextForksChildrenWithoutOne() throws Exception {
        RequestScopeTest.onVirtual(() -> {
            try (RequestScope scope = RequestScope.open(clock, timer, Duration.ofSeconds(5))) {
                RequestScope.Subtask<Boolean> child = scope.fork(MessageContext::isBound);
                scope.join();
                assertEquals(Boolean.FALSE, child.get());
            }
            return null;
        });
    }

    @Test
    void aChildDoesNotLeakTheContextToTheThreadItRanOn() throws Exception {
        // the binding is a ScopedValue: it ends with the child's call, nothing to "clear"
        RequestScopeTest.onVirtual(() -> ctx.call(() -> {
            try (RequestScope scope = RequestScope.open(clock, timer, Duration.ofSeconds(5))) {
                RequestScope.Subtask<Boolean> child = scope.fork(() -> MessageContext.isBound());
                scope.join();
                assertTrue(child.get());
            }
            return null;
        }));
        assertFalse(MessageContext.isBound());
    }
}
