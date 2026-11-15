package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The startup check of the trace limit is wired (#53 ruling; ADR-0015, "Trace"): the service,
 * started as its {@code main} with {@code flottila.mapping.trace-limit} 0, does not start and exits
 * with 1, and the reason names the limit. A startup that the check does not stop exits with 0
 * instead, so the launch never stays open. {@code TraceLimitConfigTest} checks the refusal itself.
 */
@QuarkusMainTest
@TestProfile(TraceLimitStartupTest.LimitZero.class)
class TraceLimitStartupTest {

  /** A trace limit of zero, and an exit once the service has started. */
  public static final class LimitZero implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("flottila.mapping.trace-limit", "0");
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(ExitOnceStarted.class);
    }
  }

  /**
   * The last startup observer, selected only by {@link LimitZero}: it ends a service that started,
   * with 0, which a service waiting for its exit would otherwise never do.
   */
  @Alternative
  @Singleton
  public static class ExitOnceStarted {

    void exit(@Observes @Priority(Integer.MAX_VALUE) StartupEvent event) {
      Quarkus.asyncExit(0);
    }
  }

  @Test
  @Launch(
      value = {},
      exitCode = 1)
  void aTraceLimitOfZeroStopsTheServiceAtStartup(LaunchResult result) {
    assertThat(result.getOutput() + result.getErrorOutput())
        .contains("flottila.mapping.trace-limit is 0, but a trace limit is at least 1");
  }
}
