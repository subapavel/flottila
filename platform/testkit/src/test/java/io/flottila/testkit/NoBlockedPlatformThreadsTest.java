package io.flottila.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** FIX-53: the JUnit extension closes its detector after each test and resolves only the detector parameter. */
class NoBlockedPlatformThreadsTest {

    static Events run(Class<?> testCase) {
        return EngineTestKit.engine("junit-jupiter").selectors(selectClass(testCase)).execute().testEvents();
    }

    @Test
    void afterEachClosesTheDetectorWhichShutsDownThePoolsItHandedOut() {
        HandsOutAPool.pool = null;
        Events events = run(HandsOutAPool.class);
        assertEquals(1, events.succeeded().count(), "the case ran and passed: " + events.failed().list());
        ExecutorService pool = HandsOutAPool.pool;
        assertNotNull(pool, "the case received the detector and took a pool from it");
        assertTrue(pool.isShutdown(), "the extension closed the detector after the test: its pool is shut down");
    }

    @Test
    void aParameterOfAnotherTypeIsLeftToItsOwnResolver() {
        Events events = run(TakesTestInfo.class);
        assertEquals(0, events.failed().count(), "no competing resolver for TestInfo: " + events.failed().list());
        assertEquals(1, events.succeeded().count());
    }

    /** Run only through EngineTestKit above (not picked up by surefire: no *Test suffix). */
    @ExtendWith(NoBlockedPlatformThreads.class)
    static class HandsOutAPool {
        static volatile ExecutorService pool;

        @Test
        void takesAPool(BlockedPlatformThreadDetector detector) {
            pool = detector.watchedPlatformPool("handed-out", 1);
        }
    }

    /** Run only through EngineTestKit above. */
    @ExtendWith(NoBlockedPlatformThreads.class)
    static class TakesTestInfo {
        @Test
        void seesItsOwnName(TestInfo info) {
            assertEquals("seesItsOwnName(TestInfo)", info.getDisplayName());
        }
    }
}
