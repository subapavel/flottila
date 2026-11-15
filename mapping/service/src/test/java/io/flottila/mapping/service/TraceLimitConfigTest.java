package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A trace limit below 1 is a configuration error (ADR-0015, "Trace"; #53 ruling): the startup check
 * refuses {@code flottila.mapping.trace-limit}, so the service does not start, Integer.MIN_VALUE
 * too. Witness: the least limit, 1, is read as it is. {@code TraceLimitStartupTest} shows the check
 * is wired to the startup.
 */
class TraceLimitConfigTest {

  private static Config config(String limit) {
    return new SmallRyeConfigBuilder()
        .withDefaultValue("flottila.mapping.trace-limit", limit)
        .build();
  }

  @ParameterizedTest(name = "trace limit {0}")
  @ValueSource(strings = {"0", "-1", "-2147483648"})
  void aTraceLimitBelowOneStopsTheStartup(String limit) {
    assertThat(ServiceConfig.traceLimit(config("1"))).isEqualTo(1);

    assertThatThrownBy(() -> new ServiceConfig().checkAtStartup(new StartupEvent(), config(limit)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("flottila.mapping.trace-limit is " + limit);
  }
}
