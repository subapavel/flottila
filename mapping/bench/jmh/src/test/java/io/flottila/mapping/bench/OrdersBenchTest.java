package io.flottila.mapping.bench;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.api.CompileResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each {@code orders} benchmark once, without JMH, as its fork runs it after the trial's setup: it
 * times a run that writes the target message and a compile that is not refused. That the message is
 * right is the differential case {@code bench/orders/001}.
 */
class OrdersBenchTest {

  @Test
  void theSmallLatencyBenchmarkRunsTheSmallInput() throws Exception {
    OrdersBench.Small small = new OrdersBench.Small();
    small.setUp();

    byte[] output = new OrdersBench().smallLatency(small);

    // the first order's number, date, customer and price, and the last order's last pick
    assertThat(new String(output, UTF_8))
        .startsWith("<?xml")
        .contains("\"urn:flottila:corpus:bench:orders\"")
        .contains(">SO-100001<", ">2026-06-30<", ">Brookfield, Kira<", ">371.55<")
        .endsWith("NORTH</ns0:Warehouse><ns0:Sku>SKU-99013</ns0:Sku></ns0:Pick></ns0:OrderBatch>");
  }

  @Test
  void theLargeBenchmarksRunTheLargeInput() throws Exception {
    OrdersBench.Large large = new OrdersBench.Large();
    large.inputBytes = Integer.parseInt(OrdersBench.LARGE_BYTES);
    large.setUp();

    // the first order's number, and the last order's last pick (OrdersGenerator, seed 20261110)
    for (byte[] output :
        List.of(new OrdersBench().largeThroughput(large), new OrdersBench().largeHeap(large))) {
      assertThat(new String(output, UTF_8))
          .startsWith("<?xml")
          .contains(">SO-100001<")
          .endsWith(
              "HARBOUR</ns0:Warehouse><ns0:Sku>SKU-49992</ns0:Sku></ns0:Pick></ns0:OrderBatch>");
    }
  }

  @Test
  void theLargeSetupRefusesAnInputOfAnotherLength() {
    OrdersBench.Large large = new OrdersBench.Large();
    large.inputBytes = 10_485_760;

    assertThatThrownBy(large::setUp)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(OrdersBench.LARGE_BYTES + " bytes")
        .hasMessageContaining("inputBytes 10485760");
  }

  @Test
  void theCompileBenchmarkCompilesTheModelAsATestRun() throws Exception {
    OrdersBench.Model model = new OrdersBench.Model();
    model.setUp();

    assertThat(new OrdersBench().compile(model)).isInstanceOf(CompileResult.Compiled.class);
  }
}
