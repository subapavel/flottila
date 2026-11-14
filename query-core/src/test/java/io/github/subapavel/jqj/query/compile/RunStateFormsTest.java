package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.run.Fn0;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0030 (#78): the forms without a run state are gone, so no caller can run outside a run. ADR-0038 (#67) adds the
 * forms with parameter slots, which take a run state and a mode too.
 */
class RunStateFormsTest {
  private static List<String> parameterLists(Class<?> type, String name) {
    return Arrays.stream(type.getMethods())
        .filter(m -> m.getName().equals(name))
        .map(m -> Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).toList().toString())
        .toList();
  }

  @Test
  void fn0AppliesOnlyWithARunState() {
    assertThat(parameterLists(Fn0.class, "apply")).as("Fn0.apply forms").containsExactly("[RunState, Object]");
  }

  @Test
  void executableRunsOnlyWithARunState() {
    assertThat(parameterLists(Executable.class, "run"))
        .as("Executable.run forms")
        .containsExactlyInAnyOrder("[RunState, Object, Sink]", "[RunState, Object[], Object, Sink]");
  }

  @Test
  void compilerCompilesOnlyWithAMode() {
    assertThat(parameterLists(Compiler.class, "compile")).as("Compiler.compile forms").containsExactlyInAnyOrder("[Node, CompileMode]", "[Node, int, CompileMode]");
  }
}
