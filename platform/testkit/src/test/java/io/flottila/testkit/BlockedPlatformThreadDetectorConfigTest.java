package io.flottila.testkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockedPlatformThreadDetectorConfigTest {

    @Test
    void thresholdMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> BlockedPlatformThreadDetector.start(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> BlockedPlatformThreadDetector.start(Duration.ofMillis(-5)));
        assertThrows(IllegalArgumentException.class, () -> BlockedPlatformThreadDetector.driven(Duration.ZERO, FakeClock.at(Instant.EPOCH)));
        assertThrows(IllegalArgumentException.class, () -> BlockedPlatformThreadDetector.driven(Duration.ofMillis(-5), FakeClock.at(Instant.EPOCH)));
    }
}
