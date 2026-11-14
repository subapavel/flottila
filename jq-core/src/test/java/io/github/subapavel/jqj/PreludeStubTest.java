package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * The prelude's Stubs at #71 (spec 2a "Prelude", ADR-0042): {@code ..} and the recursive definitions until 2b, an
 * unbuilt native reached from a prelude body, and a replaced definition whose native is not built. Only the Stub
 * prefix is asserted (GLOSSARY "Stub"). These cases are Stubs before #71 too; what makes them a check is the twin in
 * each test that compiles, and {@link PreludeCompileTest}. An entry leaves this class with its Stub (2b for the
 * recursive ones, #72 for {@code flatten}, spec 4 for {@code ltrimstr}); that is not a weakened test.
 */
class PreludeStubTest {
  private static final String MARK = "jqj stub: ";
  private static final JqEngine ENGINE = JqEngine.builder().build();

  /** {@code ..} is a call of recurse/0 (parser.y), which reaches recurse/1, a recursive group. */
  @Test
  void dotDotIsAStub() {
    assertStub("[..]");
  }

  /** The same call by name; its non-recursive neighbour first/0 compiles. */
  @Test
  void recurseIsAStub() {
    assertStub("[recurse]");
    assertCompiles("[first]");
  }

  /** walk/1's nested {@code w} calls itself; map/1, which w also calls, compiles alone. */
  @Test
  void walkIsAStub() {
    assertStub("walk(.)");
    assertCompiles("map(.)");
  }

  /** paths/0 reaches path/1 (spec 3) and recurse; select/1, its other callee, compiles alone. */
  @Test
  void pathsIsAStub() {
    assertStub("[paths]");
    assertCompiles("[select(true)]");
  }

  /** repeat/1 is recursive; the limit around it is not what stubs: limit over a range compiles. */
  @Test
  void limitOverRepeatIsAStub() {
    assertStub("[limit(3; repeat(1))]");
    assertCompiles("[limit(3; range(10))]");
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
