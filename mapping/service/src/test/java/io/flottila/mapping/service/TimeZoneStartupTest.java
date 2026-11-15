package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The startup check of the time zone is wired (#80): the service, started as its {@code main} with
 * a {@code flottila.mapping.time-zone} that names no zone, does not start and exits with 1, and the
 * reason names the setting. A startup that the check does not stop exits with 0 instead, through
 * {@link TraceLimitStartupTest.ExitOnceStarted}, so the launch never stays open. {@code
 * TimeZoneConfigTest} checks the refusal itself.
 */
@QuarkusMainTest
@TestProfile(TimeZoneStartupTest.NoZone.class)
class TimeZoneStartupTest {

  /** A time zone that is none, and an exit once the service has started. */
  public static final class NoZone implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("flottila.mapping.time-zone", "Mars/Olympus");
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(TraceLimitStartupTest.ExitOnceStarted.class);
    }
  }

  @Test
  @Launch(
      value = {},
      exitCode = 1)
  void aZoneThatIsNoneStopsTheServiceAtStartup(LaunchResult result) {
    assertThat(result.getOutput() + result.getErrorOutput())
        .contains("flottila.mapping.time-zone is \"Mars/Olympus\", which is no time zone");
  }
}
