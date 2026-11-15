package io.flottila.mapping.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.structure.importer.UnresolvedReferenceException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A bench mapping's files on the class path, and the refusals a benchmark must never time. */
class BenchMappingTest {

  @Test
  void theResolverServesTheFilesBesideTheModel() throws Exception {
    BenchMapping orders = BenchMapping.orders();

    assertThat(orders.resolver().resolve("orders-source.xsd").content())
        .as("a schema file beside the model")
        .isNotEmpty();
    assertThatThrownBy(() -> orders.resolver().resolve("missing.xsd"))
        .isInstanceOfSatisfying(
            UnresolvedReferenceException.class,
            e -> assertThat(e.reason()).isEqualTo(UnresolvedReferenceException.Reason.UNREADABLE));
  }

  @Test
  void aMappingWithoutItsDirectoryIsNoBenchMapping() {
    assertThatThrownBy(() -> BenchMapping.of("missing", (seed, size) -> new byte[0]))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing/bench.json");
  }

  @Test
  void benchJsonNamesEachNumber() {
    assertThat(BenchMapping.number("{\"seed\": 7, \"smallBytes\": 9000}", "smallBytes"))
        .isEqualTo(9000);
    assertThatThrownBy(() -> BenchMapping.number("{\"seed\": 7}", "smallBytes"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("smallBytes");
  }

  @Test
  void aRefusedCompileIsNoBenchmark() {
    assertThatThrownBy(
            () -> BenchMapping.compiled(new CompileResult.Refused(List.<Diagnostic>of()), "orders"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("orders");
  }
}
