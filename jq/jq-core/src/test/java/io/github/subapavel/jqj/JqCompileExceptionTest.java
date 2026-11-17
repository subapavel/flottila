package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code JqCompileException(List<String> blocks, int line, int column)} (ADR-0031, amending ADR-0016): the message is
 * the error blocks joined by LF, then jq's count line; line and column are the first block's, in bytes. The blocks are
 * jq-ref 1.8.2's, recorded in diff/literal.rec without the trailing LF the command line adds.
 */
class JqCompileExceptionTest {
  /** literal/error-escape-mid: the one block of {@code "u\vw"}. */
  private static final String ESCAPE_MID =
      "jq: error: Invalid escape at line 1, column 4 (while parsing '\"\\v\"') at <top-level>, line 1, column 3:\n"
          + "    \"u\\vw\"\n"
          + "      ^^";

  /** literal/error-two-blocks: the first block of {@code "\va\q"}. */
  private static final String FIRST_OF_TWO =
      "jq: error: Invalid escape at line 1, column 4 (while parsing '\"\\v\"') at <top-level>, line 1, column 2:\n"
          + "    \"\\va\\q\"\n"
          + "     ^^";

  /** literal/error-two-blocks: the second block of {@code "\va\q"}. */
  private static final String SECOND_OF_TWO =
      "jq: error: Invalid escape at line 1, column 4 (while parsing '\"\\q\"') at <top-level>, line 1, column 5:\n"
          + "    \"\\va\\q\"\n"
          + "        ^^";

  @Test
  void oneErrorBlockEndsWithJqsSingularCount() {
    JqCompileException e = new JqCompileException(List.of(ESCAPE_MID), 1, 3);

    assertThat(e.getMessage()).as("message").isEqualTo(ESCAPE_MID + "\njq: 1 compile error");
    assertThat(e.blocks()).as("blocks").containsExactly(ESCAPE_MID);
    assertThat(e.line()).as("line").isEqualTo(1);
    assertThat(e.column()).as("column").isEqualTo(3);
  }

  @Test
  void errorBlocksAreJoinedByLfAndCountedInThePlural() {
    JqCompileException e = new JqCompileException(List.of(FIRST_OF_TWO, SECOND_OF_TWO), 1, 2);

    assertThat(e.getMessage())
        .as("message")
        .isEqualTo(FIRST_OF_TWO + "\n" + SECOND_OF_TWO + "\njq: 2 compile errors");
    assertThat(e.blocks()).as("blocks, in order").containsExactly(FIRST_OF_TWO, SECOND_OF_TWO);
    assertThat(e.line()).as("line of the first block").isEqualTo(1);
    assertThat(e.column()).as("column of the first block").isEqualTo(2);
  }

  @Test
  void theBlocksCannotBeChangedFromOutside() {
    List<String> given = new ArrayList<>(List.of(ESCAPE_MID));
    JqCompileException e = new JqCompileException(given, 1, 3);
    given.add(SECOND_OF_TWO);

    assertThat(e.blocks()).as("blocks after the caller's list changed").containsExactly(ESCAPE_MID);
    assertThat(e.getMessage()).as("message after the caller's list changed").isEqualTo(ESCAPE_MID + "\njq: 1 compile error");
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .as("adding to blocks()")
        .isThrownBy(() -> e.blocks().add(SECOND_OF_TWO));
  }
}
