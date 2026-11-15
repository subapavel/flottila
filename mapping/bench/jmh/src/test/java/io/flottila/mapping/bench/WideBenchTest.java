package io.flottila.mapping.bench;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each {@code wide} benchmark once, without JMH, as its fork runs it after the trial's setup: it
 * times a run that writes the target message. That the message is right is the differential case
 * {@code bench/wide/001}.
 */
class WideBenchTest {

  @Test
  void theSmallLatencyBenchmarkRunsTheSmallInput() throws Exception {
    WideBench.Small small = new WideBench.Small();
    small.setUp();

    byte[] output = new WideBench().smallLatency(small);

    // the one record's number, its two names concatenated, its sum formatted, and its last field
    assertThat(new String(output, UTF_8))
        .startsWith("<?xml")
        .contains("\"urn:flottila:corpus:bench:wide\"")
        .contains(">P-000001<", ">Fennel, Cedar<", ">3384.04<")
        .endsWith("<ns0:LedgerCap>8390.63</ns0:LedgerCap></ns0:Entry></ns0:WideBatch>");
  }

  @Test
  void theLargeBenchmarksRunTheLargeInput() throws Exception {
    WideBench.Large large = new WideBench.Large();
    large.inputBytes = Integer.parseInt(WideBench.LARGE_BYTES);
    large.setUp();

    // the first and the last of the 1,108 records, and the last one's last field (seed 20261111)
    for (byte[] output :
        List.of(new WideBench().largeThroughput(large), new WideBench().largeHeap(large))) {
      assertThat(new String(output, UTF_8))
          .startsWith("<?xml")
          .contains(">P-000001<", ">P-001108<")
          .endsWith("<ns0:LedgerCap>4447.61</ns0:LedgerCap></ns0:Entry></ns0:WideBatch>");
    }
  }

  @Test
  void theLargeSetupRefusesAnInputOfAnotherLength() {
    WideBench.Large large = new WideBench.Large();
    large.inputBytes = 10_485_760;

    assertThatThrownBy(large::setUp)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(WideBench.LARGE_BYTES + " bytes")
        .hasMessageContaining("inputBytes 10485760");
  }
}
