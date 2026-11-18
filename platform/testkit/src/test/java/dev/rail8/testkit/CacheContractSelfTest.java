package dev.rail8.testkit;

import dev.rail8.api.Cache;
import dev.rail8.api.Services;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cache contract tells a cache that keeps nothing from one that keeps: {@link Services#NONE}'s cache, which runs the
 * loader on every call, passes the cases about keys, loaders and failures and fails every case about keeping a value or
 * sharing a load.
 */
class CacheContractSelfTest {

    static class KeepsNothing extends CacheContract {
        @Override protected Cache cache() { return Services.NONE.cache(); }
        @Override protected void advance(Duration by) {}
    }

    @Test
    void aCacheThatKeepsNothingFailsEveryCaseAboutKeepingOrSharing() {
        Map<String, Throwable> failures = ContractRun.failures(KeepsNothing.class);
        assertEquals(Set.of(
                "aMissLoadsAndAHitDoesNot",
                "keysAreSeparate",
                "aValueIsKeptUntilItsTtlHasPassed",
                "aTtlAboveTheLimitIsCutToIt",
                "aLoaderThatAsksForItsOwnKeyFails",
                "callersOfOneKeyShareOneLoad",
                "aCallerThatWaitedForALoadThatFailedGetsAnIllegalStateWithTheFailureAsCause"), failures.keySet());
        ContractRun.caughtBy(failures, "callersOfOneKeyShareOneLoad", "the second caller waited for the first one's load");
    }
}
