package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.value.JqNull;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixtures of #70's definition tests (ADR-0040), through the exported API only: the filters of a doubling chain and
 * of a call chain, a run that keeps its results as JSON, and the Stub check (GLOSSARY "Stub": the message's prefix
 * only).
 */
final class Inlining {
  private Inlining() {}

  /** GLOSSARY "Stub": the prefix of every Stub's message; no test asserts the text after it. */
  static final String MARK = "jqj stub: ";

  /** An engine with no budget. */
  static final JqEngine ENGINE = JqEngine.builder().build();

  /**
   * {@code def f0: .; def f1: f0,f0; …; def fn: f(n-1),f(n-1);} without a main filter: fn has 2^n leaves. Under
   * ADR-0040's rule (each node lowered inside an expansion spends one), a call of fk lowers 2^(k+1) - 1 nodes.
   */
  static String doubling(int n) {
    StringBuilder f = new StringBuilder("def f0: .; ");
    for (int i = 1; i <= n; i++) {
      f.append("def f").append(i).append(": f").append(i - 1).append(",f").append(i - 1).append("; ");
    }
    return f.toString();
  }

  /**
   * {@code def f1: .; def f2: f1; …; def fn: f(n-1); fn}: the call of fn nests n expansions, f1's the deepest; one node
   * ({@code .}) is lowered inside them. jq-ref 1.8.2 prints {@code null} for n = 511 and 512 (observed 2026-11-13).
   */
  static String callChain(int n) {
    StringBuilder f = new StringBuilder("def f1: .; ");
    for (int i = 2; i <= n; i++) {
      f.append("def f").append(i).append(": f").append(i - 1).append("; ");
    }
    return f.append('f').append(n).toString();
  }

  /** The results of {@code filter} on null, as compact JSON, with a sink that never stops. */
  static List<String> results(String filter) {
    List<String> results = new ArrayList<>();
    boolean ranOut =
        ENGINE
            .compile(filter)
            .run(
                JqNull.NULL,
                NONE,
                v -> {
                  results.add(v.toJson());
                  return true;
                },
                ENGINE.newWorkspace());
    assertThat(ranOut).as("run's answer for %s when the sink never stops", shown(filter)).isTrue();
    return results;
  }

  /** The results of {@code filter} on null as compact JSON, then {@code error <value>} if the run raised. */
  static List<String> outcome(String filter) {
    List<String> outcome = new ArrayList<>();
    try {
      ENGINE
          .compile(filter)
          .run(
              JqNull.NULL,
              NONE,
              v -> {
                outcome.add(v.toJson());
                return true;
              },
              ENGINE.newWorkspace());
    } catch (JqFilterException e) {
      outcome.add("error " + e.value().toJson());
    }
    return outcome;
  }

  /** Asserts that compiling {@code filter} ends with a Stub. */
  static void assertStub(String filter) {
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class).as("compile of %s", shown(filter)).isThrownBy(() -> ENGINE.compile(filter)).actual();
    assertThat(e.getMessage()).as("message of the compile error of %s", shown(filter)).startsWith(MARK);
  }

  /** {@code filter}, or its first 60 bytes and its length when it is longer. */
  static String shown(String filter) {
    return filter.length() <= 80 ? filter : filter.substring(0, 60) + "… (" + filter.length() + " chars)";
  }
}
