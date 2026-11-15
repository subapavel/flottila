package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The time zone of the service's runs is a configuration (#80): {@code flottila.mapping.time-zone}
 * is a {@code ZoneId}, a region or an offset, and one that names no zone stops the startup check.
 * {@code TimeZoneStartupTest} shows the check is wired to the startup, {@code TestRunTimeZoneTest}
 * that a test run takes its time in the zone.
 */
class TimeZoneConfigTest {

  /** A configuration with the trace limit of application.properties and {@code zone}. */
  private static Config config(String zone) {
    return new SmallRyeConfigBuilder()
        .withDefaultValue("flottila.mapping.trace-limit", "10000")
        .withDefaultValue("flottila.mapping.time-zone", zone)
        .build();
  }

  @Test
  void aZoneIsARegionOrAnOffset() {
    assertThat(ServiceConfig.timeZone(config("UTC"))).isEqualTo(ZoneId.of("UTC"));
    assertThat(ServiceConfig.timeZone(config("Europe/Prague")))
        .isEqualTo(ZoneId.of("Europe/Prague"));
    assertThat(ServiceConfig.timeZone(config("+01:00"))).isEqualTo(ZoneOffset.ofHours(1));
  }

  @ParameterizedTest(name = "time zone {0}")
  @ValueSource(strings = {"Mars/Olympus", "+25:00", "utc+1"})
  void aZoneThatIsNoneStopsTheStartup(String zone) {
    assertThatThrownBy(() -> new ServiceConfig().checkAtStartup(new StartupEvent(), config(zone)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(
            "flottila.mapping.time-zone is \"" + zone + "\", which is no time zone: ");
  }
}
