package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.host.Host;
import org.junit.jupiter.api.Test;

/**
 * ADR-0016, ADR-0017 and ADR-0024: the carriers a run needs exist with real bodies. What the output
 * options do is the writer tickets' to test.
 */
class CarriersTest {
  @Test
  void sandboxedHostEmptyBindingsAndBothOutputOptionsExist() {
    assertThat(Host.sandboxed()).as("Host.sandboxed()").isNotNull();
    assertThat(Bindings.builder().build()).as("Bindings.builder().build()").isNotNull();
    OutputOptions compact = OutputOptions.compact();
    OutputOptions pretty = OutputOptions.pretty();
    assertThat(compact).as("OutputOptions.compact()").isNotNull();
    assertThat(pretty).as("OutputOptions.pretty()").isNotNull().isNotEqualTo(compact);
  }
}
