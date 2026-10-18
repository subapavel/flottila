package io.flottila.testkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockedPlatformThreadDetectorConfigTest {

    @Test
    void thresholdMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> BlockedPlatformThreadDetector.start(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> BlockedPlatformThreadDetector.start(Duration.ofMillis(-5)));
    }
}
