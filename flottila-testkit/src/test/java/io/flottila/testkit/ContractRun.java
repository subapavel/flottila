package io.flottila.testkit;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * FIX-53: runs a contract suite through the JUnit engine and keeps, per failing case, what it failed with — so a self-test
 * pins not only which cases a broken variant fails but the check that caught it (the first failing assertion of the case).
 */
final class ContractRun {

    private ContractRun() {}

    /** Case name (without "()") → the throwable it failed with; nothing skipped, nothing aborted. */
    static Map<String, Throwable> failures(Class<?> suite) {
        EngineExecutionResults run = EngineTestKit.engine("junit-jupiter").selectors(selectClass(suite)).execute();
        Events events = run.testEvents();
        assertEquals(0, run.containerEvents().failed().count(), "no container failed: " + run.containerEvents().failed().list());
        assertEquals(cases(suite), events.started().count(), "every case of " + suite.getSimpleName() + " started");
        assertEquals(0, events.skipped().count(), "nothing skipped");
        assertEquals(0, events.aborted().count(), "nothing aborted");
        Map<String, Throwable> failed = new TreeMap<>();
        events.failed().stream().forEach(e -> failed.put(e.getTestDescriptor().getDisplayName().replace("()", ""),
                e.getPayload(TestExecutionResult.class).flatMap(TestExecutionResult::getThrowable).orElseThrow()));
        return failed;
    }

    /** The {@code @Test} methods of {@code suite} and its superclasses, by name — what the engine must start. */
    static long cases(Class<?> suite) {
        Set<String> names = new HashSet<>();
        for (Class<?> c = suite; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) if (m.isAnnotationPresent(Test.class)) names.add(m.getName());
        }
        assertTrue(names.size() > 0, suite + " declares no case");
        return names.size();
    }

    /** The case {@code name} failed, and what it failed with — class and message — contains {@code check}. */
    static void caughtBy(Map<String, Throwable> failures, String name, String check) {
        Throwable t = failures.get(name);
        assertNotNull(t, name + " did not fail: " + failures.keySet());
        assertTrue(t.toString().contains(check), name + " failed, but not at the check expected (" + check + "): " + t);
    }

    /** The case {@code name} failed with exactly {@code failure} (class and message) — for checks that carry no message of their own. */
    static void caughtExactlyBy(Map<String, Throwable> failures, String name, String failure) {
        Throwable t = failures.get(name);
        assertNotNull(t, name + " did not fail: " + failures.keySet());
        assertEquals(failure, t.toString(), name + " failed, but not at the check expected");
    }
}
