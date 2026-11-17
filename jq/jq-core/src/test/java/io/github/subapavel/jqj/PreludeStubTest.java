package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * The prelude's Stubs (spec 2a "Prelude", ADR-0042): an unbuilt native reached from a prelude body, a replaced
 * definition whose native is not built, and the recursive definitions that also reach spec 3's paths. Only the Stub
 * prefix is asserted (GLOSSARY "Stub"). These cases are Stubs before #71 too; what makes them a check is the twin in
 * each test that compiles, and {@link PreludeCompileTest}. An entry leaves this class with its Stub, which is not a
 * weakened test: #72 for {@code flatten}; #110 for {@code ..}, {@code recurse} and {@code limit(3; repeat(1))}
 * (ADR-0042 amendment for #110), which RecursivePreludeTest, PreludeLiftTest and diff/rec-prelude.cases now run;
 * spec 4 for {@code ltrimstr}.
 */
class PreludeStubTest {
  private static final String MARK = "jqj stub: ";
  private static final JqEngine ENGINE = JqEngine.builder().build();

  /**
   * walk/1's nested {@code w} calls itself, which #110 lifts; its Stub is spec 3's: w calls map_values/1, an update
   * operator ({@code .[] |= f}). map/1, which w also calls, compiles alone.
   */
  @Test
  void walkIsAStub() {
    assertStub("walk(.)");
    assertCompiles("map(.)");
  }

  /**
   * paths/0 calls recurse/0, which #110 lifts, inside {@code path/1}, spec 3's Stub; select/1, its other callee,
   * compiles alone, and so does {@code [recurse]} since #110.
   */
  @Test
  void pathsIsAStub() {
    assertStub("[paths]");
    assertCompiles("[select(true)]");
    assertCompiles("[recurse]");
  }

  /** ltrimstr/1 reaches startswith, an unbuilt native of spec 4. */
  @Test
  void ltrimstrIsAStub() {
    assertStub("ltrimstr(\"a\")");
    assertCompiles("first(\"a\")");
  }

  /**
   * inputs/0 is a replaced definition (F-31): its native, unbuilt until spec 5, wins over builtin.jq's body. flatten/0,
   * the other replaced definition this test named, is built since #72 and compiles.
   */
  @Test
  void inputsIsAStub() {
    assertStub("[inputs]");
    assertCompiles("[[1,[2]]|flatten]");
  }

  private static void assertStub(String filter) {
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class).as("compile of %s", filter).isThrownBy(() -> ENGINE.compile(filter)).actual();
    assertThat(e.getMessage()).as("message of the compile error of %s", filter).startsWith(MARK);
  }

  private static void assertCompiles(String filter) {
    assertThatCode(() -> ENGINE.compile(filter)).as("compile of %s", filter).doesNotThrowAnyException();
  }
}
