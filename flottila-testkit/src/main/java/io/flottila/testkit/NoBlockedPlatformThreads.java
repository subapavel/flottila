package io.flottila.testkit;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * JUnit extension: starts a {@link BlockedPlatformThreadDetector} (threshold 20 ms) for each test,
 * injects it as a parameter and fails the test if a watched platform thread blocked.
 *
 * @since 0.1
 */
public final class NoBlockedPlatformThreads implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(NoBlockedPlatformThreads.class);
    private static final Duration THRESHOLD = Duration.ofMillis(20);

    @Override public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE).put(BlockedPlatformThreadDetector.class, BlockedPlatformThreadDetector.start(THRESHOLD));
    }

    @Override public void afterEach(ExtensionContext context) {
        BlockedPlatformThreadDetector detector =
                context.getStore(NAMESPACE).remove(BlockedPlatformThreadDetector.class, BlockedPlatformThreadDetector.class);
        List<BlockedPlatformThreadDetector.Violation> violations = detector.violations();
        detector.close();
        if (violations.isEmpty()) return;
        StringBuilder message = new StringBuilder();
        for (BlockedPlatformThreadDetector.Violation v : violations) {
            message.append("blocked a platform thread: ").append(v.thread()).append(" for ").append(v.blockedFor().toMillis())
                    .append(" ms\n");
            for (StackTraceElement frame : v.stackTrace()) message.append("    at ").append(frame).append('\n');
        }
        fail(message.toString());
    }

    @Override public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == BlockedPlatformThreadDetector.class;
    }

    @Override public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return context.getStore(NAMESPACE).get(BlockedPlatformThreadDetector.class);
    }
}
