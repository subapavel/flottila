package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The engine-internal compile-mode switch that the compile-mode oracle reaches by reflection (ADR-0018, ADR-0030,
 * #60): an engine compiles optimised unless its builder sets the conservative compile.
 */
class CompileModeSwitchTest {

  @Test
  void engineCompilesOptimisedByDefault() {
    assertThat(JqEngine.builder().build().compileMode()).as("compile mode of an engine built with defaults").isEqualTo(CompileMode.OPTIMISED);
  }

  @Test
  void builderSetsTheConservativeCompile() {
    JqEngine engine = JqEngine.builder().compileMode(CompileMode.CONSERVATIVE).build();
    assertThat(engine.compileMode()).as("compile mode the builder set").isEqualTo(CompileMode.CONSERVATIVE);

    // A filter compiled conservative runs. jq-ref 1.8.2, observed 2026-11-12: printf '{"a":1}' | jq-ref -c .a -> 1.
    Workspace workspace = engine.newWorkspace();
    JqValue root = workspace.read(MemorySegment.ofArray("{\"a\":1}".getBytes(US_ASCII))).root();
    List<JqValue> results = new ArrayList<>();
    assertThat(engine.compile(".a").run(root, Bindings.builder().build(), results::add, workspace)).as("run's answer for .a compiled conservative").isTrue();
    assertThat(results).as("results of .a on {\"a\":1} compiled conservative").singleElement().extracting(JqValue::toJson).isEqualTo("1");
  }

  @Test
  void builderRefusesANullCompileMode() {
    assertThatNullPointerException().as("compileMode(null)").isThrownBy(() -> JqEngine.builder().compileMode(null));
  }
}
