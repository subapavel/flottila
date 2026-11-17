package io.github.subapavel.jqj.query.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/** A Pipe rejects a missing part when it is built (#34 design section 1), not later in the verifier or a backend. */
class PipeTest {
  private static final Source AT = new Source(0, 1);

  @Test
  void pipeRejectsNullParts() {
    Identity dot = new Identity(AT);
    assertThatNullPointerException().as("a Pipe without a source").isThrownBy(() -> new Pipe(null, dot, new Identity(AT))).withMessage("source");
    assertThatNullPointerException().as("a Pipe without a left side").isThrownBy(() -> new Pipe(AT, null, dot)).withMessage("left");
    assertThatNullPointerException().as("a Pipe without a right side").isThrownBy(() -> new Pipe(AT, dot, null)).withMessage("right");

    // Witness: the same parts, none missing, make a Pipe that keeps them.
    Identity other = new Identity(AT);
    Pipe pipe = new Pipe(AT, dot, other);
    assertThat(pipe.left()).as("left").isSameAs(dot);
    assertThat(pipe.right()).as("right").isSameAs(other);
    assertThat(pipe.source()).as("source").isSameAs(AT);
  }
}
