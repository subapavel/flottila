package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.PreludeSignatures.DEFINITIONS;
import static io.github.subapavel.jqj.PreludeSignatures.STUBS;
import static io.github.subapavel.jqj.PreludeSignatures.call;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every prelude definition, called as {@code name(.;…)}, compiles or is a Stub exactly as {@link PreludeSignatures#STUBS}
 * pins it at #73 (ADR-0042: a live prelude body is bound, checked and lowered; an unbuilt native or a recursive group
 * is a Stub). Through the exported API: a resolver that stubs every prelude call fails the 52 that compile, and one
 * that lowers an unbuilt native or a recursive group fails the Stubs. The Stub text is never asserted, only its prefix
 * (GLOSSARY "Stub").
 */
class PreludeCompileTest {
  private static final String MARK = "jqj stub: ";
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void eachPreludeCallCompilesOrIsAStubAsPinned() {
    List<String> wrong = new ArrayList<>();
    for (String signature : DEFINITIONS) {
      String filter = call(signature);
      String outcome = outcome(filter);
      String expected = STUBS.contains(signature) ? "stub" : "compiles";
      if (!outcome.equals(expected)) wrong.add(filter + ": expected " + expected + ", got " + outcome);
    }
    assertThat(wrong).as("prelude calls whose compile differs from the pinned list").isEmpty();
  }

  /** The pinned list itself: 54 Stubs among the 106 definitions, so 52 calls must compile (78 and 28 at #71, 60 and 46 at #72). */
  @Test
  void thePinnedStubsAreDefinitions() {
    assertThat(DEFINITIONS).as("builtin.jq's definitions").hasSize(106).containsAll(STUBS);
    assertThat(STUBS).as("the pinned Stubs").hasSize(54);
  }

  /** "compiles", "stub", or the class and message of any other compile error. */
  private static String outcome(String filter) {
    try {
      ENGINE.compile(filter);
      return "compiles";
    } catch (JqCompileException e) {
      return e.getMessage() != null && e.getMessage().startsWith(MARK) ? "stub" : "error " + e.getMessage();
    }
  }
}
