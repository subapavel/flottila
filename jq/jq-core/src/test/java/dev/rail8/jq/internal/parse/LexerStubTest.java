package dev.rail8.jq.internal.parse;

import static dev.rail8.jq.internal.parse.ParseAssertions.ir;
import static dev.rail8.jq.internal.parse.ParseAssertions.shape;
import static dev.rail8.jq.internal.parse.ParseAssertions.syntaxError;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Pipe;
import org.junit.jupiter.api.Test;

/**
 * {@code ..} is jq's recurse, one REC token (#34 design section 4), so {@code ..a} never reads as {@code . .a}: a name
 * right after it is a syntax error (#62, ADR-0032), and {@code .. .a} is recurse then a field, which the resolver
 * lowers since #110 (ADR-0042 amendment for #110: recurse/0 is a Call of recurse/1's lifted r).
 */
class LexerStubTest {

  @Test
  void aNameRightAfterRecurseIsASyntaxError() {
    // diff/syntax.rec rec-then-name, rec-then-name-in-parens, rec-then-name-after-pipe: "unexpected IDENT". Until #62
    // each was a Stub.
    syntaxError("..a", "IDENT", 1, 3, 1);
    syntaxError("(..a)", "IDENT", 1, 4, 1);
    syntaxError(". | ..a", "IDENT", 1, 7, 1);
    // jq-ref 1.8.2, observed 2026-11-12 and 2026-11-15: '.. .a' on null -> null (rc 0), so it parses; recurse was a
    // Stub until #110, which lowers it: the recursion's Call piped into the field .a, bytes [3,5).
    Node recurseThenField = ir(".. .a");
    assertThat(recurseThenField).as("IR of .. .a").isInstanceOf(Pipe.class);
    assertThat(((Pipe) recurseThenField).left()).as("left of the IR of .. .a").isInstanceOf(Call.class);
    assertThat(shape(((Pipe) recurseThenField).right())).as("right of the IR of .. .a").isEqualTo(".a[3,5)");
    // Witness: a dot, a space, then the name is the identity piped into .a (field/dot-space-field).
    assertThat(ir(". .a")).as("IR of . .a").isInstanceOf(Pipe.class);
  }
}
