package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * Where the binding errors of #69's constructs come (ADR-0041, ADR-0040): a {@code reduce} or {@code foreach} reports
 * its init first, then its source, its pattern's keys, its update and its extract, in jq's order; the bounds of
 * {@code range/2}, a bytecoded native, are inner compile levels, reported only when the level around them has no error
 * of its own. Each expected message is jq-ref 1.8.2's whole stderr for the filter with {@code -nc}, rc 3, observed
 * 2026-11-13 (the implementer's scratchpad probe 69-errs.sh), and the exception's line and column are its first
 * block's.
 */
class LoopBindingErrorsTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  private static JqCompileException compileError(String text) {
    return assertThatExceptionOfType(JqCompileException.class).as("compile of %s", text).isThrownBy(() -> ENGINE.compile(text)).actual();
  }

  @Test
  void aLoopReportsItsInitBeforeItsSourcePatternUpdateAndExtract() {
    JqCompileException reduce = compileError("reduce $a as [$x, {($k): $y}] ($b; $c)");
    assertThat(reduce.getMessage())
        .as("message of reduce $a as [$x, {($k): $y}] ($b; $c)")
        .isEqualTo(
            "jq: error: $b is not defined at <top-level>, line 1, column 32:\n    reduce $a as [$x, {($k): $y}] ($b; $c)\n                                   ^^\njq: error: $a is not defined at <top-level>, line 1, column 8:\n    reduce $a as [$x, {($k): $y}] ($b; $c)\n           ^^\njq: error: $k is not defined at <top-level>, line 1, column 21:\n    reduce $a as [$x, {($k): $y}] ($b; $c)\n                        ^^\njq: error: $c is not defined at <top-level>, line 1, column 36:\n    reduce $a as [$x, {($k): $y}] ($b; $c)\n                                       ^^\njq: 4 compile errors");
    assertThat(reduce.line()).as("line of the reduce's first error").isEqualTo(1);
    assertThat(reduce.column()).as("column of the reduce's first error, its init's").isEqualTo(32);
    assertThat(compileError("foreach $a as $x ($b; $c; $d)").getMessage())
        .as("message of foreach $a as $x ($b; $c; $d)")
        .isEqualTo(
            "jq: error: $b is not defined at <top-level>, line 1, column 19:\n    foreach $a as $x ($b; $c; $d)\n                      ^^\njq: error: $a is not defined at <top-level>, line 1, column 9:\n    foreach $a as $x ($b; $c; $d)\n            ^^\njq: error: $c is not defined at <top-level>, line 1, column 23:\n    foreach $a as $x ($b; $c; $d)\n                          ^^\njq: error: $d is not defined at <top-level>, line 1, column 27:\n    foreach $a as $x ($b; $c; $d)\n                              ^^\njq: 4 compile errors");
  }

  @Test
  void theBoundsOfRangeAreReportedOnlyWhenTheLevelAroundThemHasNoError() {
    assertThat(compileError("$a, range($b; $c)").getMessage())
        .as("message of $a, range($b; $c)")
        .isEqualTo("jq: error: $a is not defined at <top-level>, line 1, column 1:\n    $a, range($b; $c)\n    ^^\njq: 1 compile error");
    assertThat(compileError("reduce range($a; $b) as $x ($c; $d)").getMessage())
        .as("message of reduce range($a; $b) as $x ($c; $d)")
        .isEqualTo(
            "jq: error: $c is not defined at <top-level>, line 1, column 29:\n    reduce range($a; $b) as $x ($c; $d)\n                                ^^\njq: error: $d is not defined at <top-level>, line 1, column 33:\n    reduce range($a; $b) as $x ($c; $d)\n                                    ^^\njq: 2 compile errors");
    // The outer error comes after the bounds in the text, and still places the exception.
    JqCompileException after = compileError("range(1; $a), break $g");
    assertThat(after.getMessage())
        .as("message of range(1; $a), break $g")
        .isEqualTo("jq: error: $*label-g is not defined at <top-level>, line 1, column 15:\n    range(1; $a), break $g\n                  ^^^^^^^^\njq: 1 compile error");
    assertThat(after.column()).as("column of range(1; $a), break $g").isEqualTo(15);
  }

  @Test
  void eachBoundIsReportedWhenTheLevelsAroundItHaveNoError() {
    // Witnesses for the test above: the same bounds, with no outer error, are reported in order.
    JqCompileException both = compileError("range($b; $c)");
    assertThat(both.getMessage())
        .as("message of range($b; $c)")
        .isEqualTo(
            "jq: error: $b is not defined at <top-level>, line 1, column 7:\n    range($b; $c)\n          ^^\njq: error: $c is not defined at <top-level>, line 1, column 11:\n    range($b; $c)\n              ^^\njq: 2 compile errors");
    assertThat(both.column()).as("column of range($b; $c)").isEqualTo(7);
    // The end's own range is a level inside the end's: its error comes after the start's, which is a sibling level.
    assertThat(compileError("range($a; range($b; 1))").getMessage())
        .as("message of range($a; range($b; 1))")
        .isEqualTo(
            "jq: error: $a is not defined at <top-level>, line 1, column 7:\n    range($a; range($b; 1))\n          ^^\njq: error: $b is not defined at <top-level>, line 1, column 17:\n    range($a; range($b; 1))\n                    ^^\njq: 2 compile errors");
  }
}
