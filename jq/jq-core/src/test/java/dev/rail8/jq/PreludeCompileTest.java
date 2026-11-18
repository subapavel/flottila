package dev.rail8.jq;

import static dev.rail8.jq.PreludeSignatures.DEFINITIONS;
import static dev.rail8.jq.PreludeSignatures.LIFTED;
import static dev.rail8.jq.PreludeSignatures.STUBS;
import static dev.rail8.jq.PreludeSignatures.call;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every prelude definition, called as {@code name(.;…)}, compiles or is a Stub exactly as {@link PreludeSignatures#STUBS}
 * pins it at #110 (ADR-0042: a live prelude body is bound, checked and lowered; an unbuilt native is a Stub; since its
 * #110 amendment a recursive group lifts to a function). Through the exported API: a resolver that stubs every prelude
 * call fails the 70 that compile, one that still stubs a recursive group fails the ten of {@link
 * PreludeSignatures#LIFTED}, and one that lowers an unbuilt native fails the Stubs. The Stub text is never asserted,
 * only its prefix (GLOSSARY "Stub").
 */
class PreludeCompileTest {
  private static final String MARK = "rail8-jq stub: ";
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

  /**
   * The pinned list itself: 36 Stubs among the 106 definitions, so 70 calls must compile. The ten recursive definitions
   * that lift a function are definitions and no Stub.
   */
  @Test
  void thePinnedStubsAreDefinitions() {
    assertThat(DEFINITIONS).as("builtin.jq's definitions").hasSize(106).containsAll(STUBS).containsAll(LIFTED);
    assertThat(STUBS).as("the pinned Stubs").hasSize(36).doesNotContainAnyElementsOf(LIFTED);
    assertThat(LIFTED).as("the recursive definitions that lift a function").hasSize(10);
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
