package io.flottila.testkit;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The suite's own waiting helpers: a failed future is a test failure with the cause attached, not a hang. */
class BlobStoreContractHelpersTest {

    @Test
    void awaitRethrowsAFailureAsAnAssertionErrorWithTheCause() {
        RuntimeException cause = new RuntimeException("store down");
        AssertionError e = assertThrows(AssertionError.class, () -> BlobStoreContract.await(Future.failedFuture(cause)));
        assertEquals(cause, e.getCause());
        assertEquals("ok", BlobStoreContract.await(Future.succeededFuture("ok")));
    }

    @Test
    void awaitOutcomeReturnsTheFutureWhetherItSucceededOrFailed() {
        assertTrue(BlobStoreContract.awaitOutcome(Future.failedFuture(new RuntimeException("x"))).failed());
        assertTrue(BlobStoreContract.awaitOutcome(Future.succeededFuture(1)).succeeded());
        Promise<Void> never = Promise.promise();
        assertThrows(AssertionError.class, () -> BlobStoreContract.awaitOutcome(never.future(), 1), "a future that never completes is a failure, not a hang");
    }
}
