package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.OutputOptions;
import io.github.subapavel.jqj.bench.Inputs.Size;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.annotations.Param;

/**
 * #119's guards on the diagnostics: each recursion cell runs the call kind its row names, each cap cell sits at its
 * cap, and the reflective hook of ADR-0020's #119 amendment compiles in the mode it was given and reads it back.
 */
class DiagnosticsTest {

  private static String explain(CompileMode mode, String text) {
    return Diagnostics.engine(mode).compile(text).explain();
  }

  /** The ADR-0006 and ADR-0011 rows: the optimised compile's call kinds, as #117 and #118 left them. */
  @Test
  void cellsRunTheirCallKinds() {
    assertThat(explain(CompileMode.OPTIMISED, Diagnostics.T)).contains("call=TAIL_SELF").doesNotContain("copyOf=");
    assertThat(explain(CompileMode.OPTIMISED, Diagnostics.M)).contains("call=TAIL_GROUP").doesNotContain("call=TAIL_SELF");
    String e = explain(CompileMode.OPTIMISED, Diagnostics.E);
    assertThat(e).contains("copyOf=").contains("call=TAIL_SELF");
    assertThat(explain(CompileMode.OPTIMISED, Diagnostics.N))
        .contains("call=RECURSIVE")
        .doesNotContain("call=TAIL_")
        .doesNotContain("copyOf=");
    assertThat(explain(CompileMode.OPTIMISED, Diagnostics.EU))
        .contains("call=RECURSIVE")
        .doesNotContain("call=TAIL_")
        .doesNotContain("copyOf=")
        .contains("specialised=0 refused=0");
  }

  /** k is the last fully inlined chain, m the last tree with no key refused; their -over cells pass the caps. */
  @Test
  void capCellsSitAtTheirCaps() {
    assertThat(explain(CompileMode.OPTIMISED, Diagnostics.inlineChain(Diagnostics.INLINE_K))).doesNotContain("function fn");
    assertThat(explain(CompileMode.OPTIMISED, Diagnostics.inlineChain(Diagnostics.INLINE_K + 1))).contains("function fn");
    assertThat(explain(CompileMode.OPTIMISED, Diagnostics.specialiseTree(Diagnostics.SPECIALISE_M)))
        .contains("specialised=16380 refused=0\n");
    assertThat(explain(CompileMode.OPTIMISED, Diagnostics.specialiseTree(Diagnostics.SPECIALISE_M + 1)))
        .contains("specialised=16380 refused=1\n");
    // jq-ref rejects 2047 closures with rc 3.
    assertThat(Diagnostics.SPECIALISE_M + 1).isLessThanOrEqualTo(2046);
  }

  @Test
  void theGeneratorsWriteTheirShapes() {
    assertThat(Diagnostics.inlineChain(3)).isEqualTo("def f1: . + 1; def f2: f1 | f1; def f3: f2 | f2; 0 | f3");
    assertThat(Diagnostics.specialiseTree(3))
        .isEqualTo(
            "[(((0 | until(. >= 1; . + 1)), (0 | until(. >= 1; . + 2))), (0 | until(. >= 1; . + 3)))]"
                + " | length + (0 | until(. >= 100000; . + 1))");
    assertThat(Diagnostics.specialiseTree(1)).startsWith("[(0 | until(. >= 1; . + 1))] |");
  }

  /** The hook reaches the switch and the read-back, keeps the embedded limits, and the conservative compile follows. */
  @Test
  void hookCompilesConservative() {
    JqEngine conservative = Diagnostics.engine(CompileMode.CONSERVATIVE);
    assertThat(Diagnostics.checked(conservative, CompileMode.CONSERVATIVE)).isSameAs(conservative);
    String t = conservative.compile(Diagnostics.T).explain();
    assertThat(t).contains("call=").doesNotContain("call=TAIL_");
    assertThat(conservative.compile(Diagnostics.E).explain()).doesNotContain("copyOf=");
    assertThat(Diagnostics.checked(Diagnostics.engine(CompileMode.OPTIMISED), CompileMode.OPTIMISED)).isNotNull();
  }

  @Test
  void aModeTheEngineDoesNotReadBackIsRefused() {
    JqEngine optimised = JqEngine.builder().build();
    assertThatThrownBy(() -> Diagnostics.checked(optimised, CompileMode.CONSERVATIVE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the engine compiles OPTIMISED, not CONSERVATIVE");
  }

  @Test
  void aSwitchThatIsNotThereOrFailsIsAnIllegalState() {
    assertThatThrownBy(() -> Diagnostics.method(JqEngine.Builder.class, "compileModes", CompileMode.class))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(NoSuchMethodException.class);
    // The switch refuses null: the reflective call fails, and that is no optimised engine.
    assertThatThrownBy(() -> Diagnostics.engine(null))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(InvocationTargetException.class);
  }

  /** A termination is the cell's outcome; the operation after it runs and ends the same way, without a throw. */
  @Test
  void aTerminationIsTheOutcome() {
    Diagnostics d = new Diagnostics();
    d.mode = CompileMode.CONSERVATIVE;
    // Two billion bytes pass the embedded 1 GiB bytes-built budget, charged before anything is built.
    d.prepare(new Diagnostics.Cell("\"x\" * 2000000000", () -> Inputs.ints(3), OutputOptions.compact(), false));
    assertThat(d.outcome()).isEqualTo("termination BUDGET: Bytes-built budget exceeded");
    assertThat(d.operation()).isEqualTo(5);
  }

  @Test
  void setUpRunsACellOnce() {
    Diagnostics d = new Diagnostics();
    d.mode = CompileMode.OPTIMISED;
    d.cell = "dotdot.1k";
    d.setUp();
    // [..] | length on orders 1k, recorded by jq-ref as bench/dotdot.1k.
    assertThat(d.outcome()).isEqualTo("4 bytes written");
    assertThat(d.operation()).isEqualTo(8);
  }

  /** A cap cell's operation compiles the text it holds then; any other runs the filter its setup compiled. */
  @Test
  void aCapCellCompilesInEachOperation() {
    Diagnostics cap = new Diagnostics();
    cap.mode = CompileMode.CONSERVATIVE;
    cap.prepare(Diagnostics.cell("cap-inline.1k"));
    // 0 | f15 is 2^14.
    assertThat(cap.outcome()).isEqualTo("6 bytes written");
    cap.text = "[1]";
    assertThat(cap.operation()).isEqualTo(10);

    Diagnostics run = new Diagnostics();
    run.mode = CompileMode.CONSERVATIVE;
    run.prepare(new Diagnostics.Cell(".[0]", () -> Inputs.ints(3), OutputOptions.compact(), false));
    run.text = "[1]";
    assertThat(run.operation()).isEqualTo(4);
  }

  @Test
  void everyParamCellResolvesAndOthersAreRefused() throws NoSuchFieldException {
    List<String> cells = List.of(Diagnostics.class.getField("cell").getAnnotation(Param.class).value());
    assertThat(cells).hasSize(27).doesNotHaveDuplicates();
    for (String id : cells) {
      assertThat(Diagnostics.cell(id).text()).as(id).isNotBlank();
    }
    assertThat(List.of(Diagnostics.class.getField("mode").getAnnotation(Param.class).value()))
        .containsExactly("OPTIMISED", "CONSERVATIVE");
    assertThatThrownBy(() -> Diagnostics.cell("G7.engine.1k"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no diagnostic cell G7.engine.1k");
  }

  @Test
  void theCellsTakeTheirDocumentsAndOptions() {
    assertThat(Diagnostics.cell("recur-T.1e6").document().get()).isEqualTo(Inputs.ints(1_000_000));
    assertThat(Diagnostics.cell("dotdot-deep.9999").document().get()).isEqualTo(Inputs.deep(9_999));
    assertThat(Diagnostics.cell("dotdot-deep.1k").document().get()).isEqualTo(Inputs.deep(Size.K1));
    assertThat(Diagnostics.cell("cap-specialise-over.1k").compiles()).isTrue();
    assertThat(Diagnostics.cell("recur-N.1e6").compiles()).isFalse();
    assertThat(Diagnostics.cell("G6.pretty.10m").options()).isSameAs(OutputOptions.pretty());
    assertThat(Diagnostics.cell("G6.compact.10m").options()).isSameAs(OutputOptions.compact());
  }

  @Test
  void theIdPutsTheModeBeforeTheSize() {
    assertThat(Diagnostics.id("recur-T.1e6", CompileMode.CONSERVATIVE)).isEqualTo("recur-T.conservative.1e6");
    assertThat(Diagnostics.id("G6.pretty.10m", CompileMode.OPTIMISED)).isEqualTo("G6.pretty.optimised.10m");
  }

  @Test
  void mainWritesTheDocumentsAndFilters(@TempDir Path dir) throws IOException {
    Diagnostics.main(new String[] {dir.resolve("inputs").toString()});
    Path inputs = dir.resolve("inputs");
    assertThat(Files.readAllBytes(inputs.resolve("ints-1e6.json"))).isEqualTo(Inputs.ints(1_000_000));
    assertThat(Files.readAllBytes(inputs.resolve("ints-1k.json"))).isEqualTo(Inputs.ints(Size.K1));
    assertThat(Files.readAllBytes(inputs.resolve("deep-9999.json"))).isEqualTo(Inputs.deep(9_999));
    assertThat(Files.readAllBytes(inputs.resolve("deep-1k.json"))).isEqualTo(Inputs.deep(Size.K1));
    assertThat(Files.readString(inputs.resolve("recur-T.jq"), US_ASCII)).isEqualTo(Diagnostics.T);
    assertThat(Files.readString(inputs.resolve("recur-E.jq"), US_ASCII)).isEqualTo(Diagnostics.E);
    assertThat(Files.readString(inputs.resolve("recur-Eu.jq"), US_ASCII)).isEqualTo(Diagnostics.EU);
    assertThat(Files.readString(inputs.resolve("recur-M.jq"), US_ASCII)).isEqualTo(Diagnostics.M);
    assertThat(Files.readString(inputs.resolve("recur-N.jq"), US_ASCII)).isEqualTo(Diagnostics.N);
    assertThat(Files.readString(inputs.resolve("limit-iter.jq"), US_ASCII)).isEqualTo(Diagnostics.LIMIT_ITER);
    assertThat(Files.readString(inputs.resolve("limit-recurse.jq"), US_ASCII)).isEqualTo(Diagnostics.LIMIT_RECURSE);
    assertThat(Files.readString(inputs.resolve("dotdot.jq"), US_ASCII)).isEqualTo(Diagnostics.DOTDOT);
    assertThat(Files.readString(inputs.resolve("cap-inline.jq"), US_ASCII)).isEqualTo(Diagnostics.inlineChain(15));
    assertThat(Files.readString(inputs.resolve("cap-inline-over.jq"), US_ASCII)).isEqualTo(Diagnostics.inlineChain(16));
    assertThat(Files.readString(inputs.resolve("cap-specialise.jq"), US_ASCII)).isEqualTo(Diagnostics.specialiseTree(1_637));
    assertThat(Files.readString(inputs.resolve("cap-specialise-over.jq"), US_ASCII))
        .isEqualTo(Diagnostics.specialiseTree(1_638));
  }
}
