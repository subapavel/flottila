package dev.rail8.jq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * The prelude's Stubs (spec 2a "Prelude"): a replaced definition whose native is not built. Only the Stub
 * prefix is asserted (GLOSSARY "Stub"). These cases are Stubs before #71 too; what makes them a check is the twin in
 * each test that compiles, and {@link PreludeCompileTest}. An entry leaves this class with its Stub, which is not a
 * weakened test: #72 for {@code flatten}; #110 for {@code ..}, {@code recurse} and {@code limit(3; repeat(1))}
 * (ADR-0042 amendment for #110), which RecursivePreludeTest, PreludeLiftTest and diff/rec-prelude.cases now run;
 * the string natives for {@code ltrimstr}, which StringNativesTest now runs; the regex native for {@code test}, which
 * RegexTest and diff/regex.cases now run; {@code path/1} and the update operators for {@code walk} and {@code paths},
 * which PreludeLiftTest and diff/path-update.cases now run.
 */
class PreludeStubTest {
  private static final String MARK = "rail8-jq stub: ";
  private static final JqEngine ENGINE = JqEngine.builder().build();

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
