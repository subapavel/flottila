package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ParseAssertions.ir;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.parsesToAStub;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.syntaxError;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Pipe;
import org.junit.jupiter.api.Test;

/**
 * {@code ..} is jq's recurse, one REC token (#34 design section 4), so {@code ..a} never reads as {@code . .a}: a name
 * right after it is a syntax error (#62, ADR-0032), and {@code .. .a} is recurse then a field, which the resolver does
 * not lower before spec 2b.
 */
class LexerStubTest {

  @Test
  void aNameRightAfterRecurseIsASyntaxError() {
    // diff/syntax.rec rec-then-name, rec-then-name-in-parens, rec-then-name-after-pipe: "unexpected IDENT". Until #62
    // each was a Stub.
    syntaxError("..a", "IDENT", 1, 3, 1);
    syntaxError("(..a)", "IDENT", 1, 4, 1);
    syntaxError(". | ..a", "IDENT", 1, 7, 1);
    // jq-ref 1.8.2, observed 2026-11-12: '.. .a' on null -> null (rc 0), so it parses; recurse stays a Stub (2b).
    parsesToAStub(".. .a");
    // Witness: a dot, a space, then the name is the identity piped into .a (field/dot-space-field).
    assertThat(ir(". .a")).as("IR of . .a").isInstanceOf(Pipe.class);
  }
}
