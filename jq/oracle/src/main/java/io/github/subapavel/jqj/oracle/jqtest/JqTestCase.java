package io.github.subapavel.jqj.oracle.jqtest;

import java.util.List;

/**
 * One case of a jq test file (ADR-0020).
 *
 * @param id {@code <file>#<12 hex digits>}, with {@code ~2}, {@code ~3} for identical cases in file order
 * @param line the 1-based line the case starts at: its marker, or its program
 * @param input the line after the program, raw; null for a FAIL case, which only compiles
 * @param expected the expected results' lines, or the expected compile error's lines for a FAIL case
 */
public record JqTestCase(String id, int line, Mode mode, String program, String input, List<String> expected) {

  public JqTestCase {
    expected = List.copyOf(expected);
  }

  public enum Mode {
    /** No marker: the program runs on the input and its results are compared. */
    RESULTS,
    /** {@code %%FAIL}: compiling must fail with the expected message. */
    FAIL,
    /** {@code %%FAIL IGNORE MSG}: compiling must fail. */
    FAIL_IGNORE_MSG
  }
}
