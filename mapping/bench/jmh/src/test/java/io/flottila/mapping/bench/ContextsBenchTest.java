package io.flottila.mapping.bench;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each {@code contexts} benchmark once, without JMH, as its fork runs it after the trial's setup:
 * it times a run that writes the target message. That the message is right is the differential case
 * {@code bench/contexts/001}.
 */
class ContextsBenchTest {

  @Test
  void theSmallLatencyBenchmarkRunsTheSmallInput() throws Exception {
    ContextsBench.Small small = new ContextsBench.Small();
    small.setUp();

    byte[] output = new ContextsBench().smallLatency(small);

    // the first customer's first order, and its items by rank: 18 in PO-200001, then 289 in
    // PO-200003 before 606 in PO-200002; the last customer's last pick (ContextsGenerator, seed
    // 20261111)
    assertThat(new String(output, UTF_8))
        .startsWith("<?xml")
        .contains("\"urn:flottila:corpus:bench:contexts\"")
        .contains(">CU-10001<", ">Jonah Fairbank<", ">PO-200001<")
        .contains(
            "<ns0:Sku>SKU-23641</ns0:Sku><ns0:Quantity>16</ns0:Quantity>"
                + "<ns0:Order>PO-200001</ns0:Order></ns0:Item>"
                + "<ns0:Item><ns0:Sku>SKU-22073</ns0:Sku><ns0:Quantity>5</ns0:Quantity>"
                + "<ns0:Order>PO-200003</ns0:Order></ns0:Item>")
        .endsWith(
            "<ns0:Customer>CU-10015</ns0:Customer><ns0:Sku>SKU-27131</ns0:Sku></ns0:Pick>"
                + "</ns0:Ledger>");
  }

  @Test
  void theLargeBenchmarksRunTheLargeInput() throws Exception {
    ContextsBench.Large large = new ContextsBench.Large();
    large.inputBytes = Integer.parseInt(ContextsBench.LARGE_BYTES);
    large.setUp();

    // the first customer, and the last customer's last pick (ContextsGenerator, seed 20261111)
    for (byte[] output :
        List.of(new ContextsBench().largeThroughput(large), new ContextsBench().largeHeap(large))) {
      assertThat(new String(output, UTF_8))
          .startsWith("<?xml")
          .contains(">CU-10001<")
          .endsWith(
              "<ns0:Customer>CU-22542</ns0:Customer><ns0:Sku>SKU-17295</ns0:Sku></ns0:Pick>"
                  + "</ns0:Ledger>");
    }
  }

  @Test
  void theLargeSetupRefusesAnInputOfAnotherLength() {
    ContextsBench.Large large = new ContextsBench.Large();
    large.inputBytes = 10_485_760;

    assertThatThrownBy(large::setUp)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ContextsBench.LARGE_BYTES + " bytes")
        .hasMessageContaining("inputBytes 10485760");
  }
}
