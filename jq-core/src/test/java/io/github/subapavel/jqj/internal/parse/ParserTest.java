package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ParseAssertions.ir;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.notGiven;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.parsesToAStub;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.shape;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.syntaxError;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The IR of a parsed and resolved filter (#33 design section 1, #34 design sections 4 and 5; since #62 the parser
 * builds an AST and the resolver lowers it, ADR-0032): {@code .} is an Identity whose Source is the dot's chars;
 * {@code .name} is one FIELD token and a bare Intrinsic whose Operation is named {@code .name}; a postfix chain nests
 * left as {@code Pipe(term, Intrinsic)}; {@code |} nests right; parentheses give the inner node. A construct the
 * resolver does not lower is a Stub, and text jq's grammar rejects is a syntax error thrown by the parse.
 */
class ParserTest {

  @Test
  void theIdentitysSourceIsTheDotsChars() {
    // The whitespace before the dot is skipped, so the dot is char 2 of " \t.\n": its source is [2, 3).
    assertThat(ir(" \t.\n")).as("IR of ' \\t.\\n'").isEqualTo(new Identity(new Source(2, 3)));
    assertThat(ir(".")).as("IR of '.'").isEqualTo(new Identity(new Source(0, 1)));
  }

  @Test
  void keywordsAfterADotAreNames() {
    // jq-ref 1.8.2, recorded in diff/field.rec: .and on {"and":1} -> 1, .if on {"if":2} -> 2, .__loc__ on
    // {"__loc__":3} -> 3, .then.else.end.or.reduce.def on the nested object -> "k", ._1.A9 -> true (rc 0 each).
    assertThat(shape(ir(".and"))).as("IR of .and").isEqualTo(".and[0,4)");
    assertThat(shape(ir(".if"))).as("IR of .if").isEqualTo(".if[0,3)");
    assertThat(shape(ir(".__loc__"))).as("IR of .__loc__").isEqualTo(".__loc__[0,8)");
    assertThat(shape(ir(".then.else.end.or.reduce.def")))
        .as("IR of .then.else.end.or.reduce.def")
        .isEqualTo("(((((.then[0,5) | .else[5,10)) | .end[10,14)) | .or[14,17)) | .reduce[17,24)) | .def[24,28))");
    assertThat(shape(ir("._1.A9"))).as("IR of ._1.A9").isEqualTo("(._1[0,3) | .A9[3,6))");

    // Each operation reads its own key: applied to one object holding every name, it gives that name's value.
    JqObject all =
        (JqObject)
            JqEngine.builder()
                .build()
                .newWorkspace()
                .read(MemorySegment.ofArray("{\"and\":1,\"if\":2,\"__loc__\":3,\"_1\":4,\"def\":5}".getBytes(UTF_8)))
                .root();
    for (Map.Entry<String, String> e : Map.of(".and", "1", ".if", "2", ".__loc__", "3", "._1", "4", ".def", "5").entrySet()) {
      Intrinsic field = (Intrinsic) ir(e.getKey());
      JqValue result = (JqValue) ((Fn0) field.op().form()).apply(new RunState(), all);
      assertThat(result.toJson()).as("%s applied to the object", e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void parenthesesMakeNoNode() {
    // jq-ref 1.8.2, recorded: (.a) on {"a":"p"} -> "p", ((.a)) on {"a":[4]} -> [4] (rc 0).
    assertThat(ir("(.)")).as("IR of (.)").isEqualTo(new Identity(new Source(1, 2)));
    assertThat(ir("((.))")).as("IR of ((.))").isEqualTo(new Identity(new Source(2, 3)));
    assertThat(shape(ir("(.a)"))).as("IR of (.a)").isEqualTo(".a[1,3)");
    assertThat(shape(ir("((.a))"))).as("IR of ((.a))").isEqualTo(".a[2,4)");
    assertThat(shape(ir("( .a | .b )"))).as("IR of ( .a | .b )").isEqualTo("(.a[2,4) | .b[7,9))");
  }

  @Test
  void postfixNestsLeftPipeNestsRight() {
    // jq-ref 1.8.2, recorded: .a.b.c, .a|.b|.c, (.a).b, (.a | .b).c, .a .b and . .a all run (rc 0).
    Map<String, String> shapes =
        Map.of(
            ".a", ".a[0,2)",
            ".a.b.c", "((.a[0,2) | .b[2,4)) | .c[4,6))",
            ".a|.b|.c", "(.a[0,2) | (.b[3,5) | .c[6,8)))",
            "(.a).b", "(.a[1,3) | .b[4,6))",
            "(.a | .b).c", "((.a[1,3) | .b[6,8)) | .c[9,11))",
            ".a .b", "(.a[0,2) | .b[3,5))",
            ". .a", "(.[0,1) | .a[2,4))",
            ". | .a | .", "(.[0,1) | (.a[4,6) | .[9,10)))",
            ".a | (.b | .c)", "(.a[0,2) | (.b[6,8) | .c[11,13)))",
            "(.a | .b) | .c", "((.a[1,3) | .b[6,8)) | .c[12,14))");
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void unbuiltTokensAreStubs() {
    // Each is jq syntax the resolver does not lower yet (jq-ref 1.8.2, observed 2026-11-11 on {"a":...}: .. $__loc__
    // ".a |= .b" run), so each parses and resolves to the Stub. The inputs jq rejects (and, ",", .é, .a.é, #c) moved to
    // unbuiltTokensThatJqRejectsAreErrors when #62 built the syntax errors; #63 lowers .a?, .[0], .["a"], .[], ".a, .b"
    // and .a[0], whose IR ResolverTest.navigationAndCommaLowerToTheirIr asserts. None was dropped. #66 lowers and, and
    // #67 $__loc__, so their entries end with | .., which still stubs.
    for (String text : List.of("..", "$__loc__ | ..", ".a and .b | ..", ".a |= .b")) {
      parsesToAStub(text);
    }
    // Witness: the built prefixes resolve to IR.
    assertThat(shape(ir(".a"))).as("witness: IR of .a").isEqualTo(".a[0,2)");
    assertThat(shape(ir(".a | .b"))).as("witness: IR of .a | .b").isEqualTo("(.a[0,2) | .b[5,7))");
  }

  @Test
  void unbuiltTokensThatJqRejectsAreErrors() {
    // diff/syntax.rec and-alone, comma-alone, dot-e-acute (its second block is jq's recovery message), field-dot-e-acute
    // and program-only-comment.
    syntaxError("and", "and", 1, 1, 1);
    syntaxError(",", "','", 1, 1, 1);
    syntaxError(".é", "INVALID_CHARACTER", 1, 2, 2);
    syntaxError(".a.é", "INVALID_CHARACTER", 1, 4, 1);
    notGiven("#c");
  }

  @Test
  void malformedFiltersAreSyntaxErrors() {
    // basics/syntax-error-eof and diff/syntax.rec paren-unclosed, close-paren-alone, pipe-first, paren-empty,
    // dot-space-dot, field-space-dot, close-paren-extra, pipe-twice and program-empty. Until #62 each was a Stub.
    syntaxError(".a |", "end of file", 1, 4, 1);
    syntaxError("(.a", "end of file", 1, 2, 1);
    syntaxError(")", "INVALID_CHARACTER", 1, 1, 1);
    syntaxError("|.a", "'|'", 1, 1, 1);
    syntaxError("()", "')'", 1, 2, 1);
    syntaxError(". .", "end of file", 1, 3, 1);
    syntaxError(".a .", "end of file", 1, 4, 1);
    syntaxError("(.a))", "INVALID_CHARACTER", 1, 5, 1);
    syntaxError(".a | | .b", "'|'", 1, 6, 1);
    notGiven("");
    // Witness: the same tokens in a well-formed order resolve.
    assertThat(shape(ir("(.a) | .b"))).as("witness: IR of (.a) | .b").isEqualTo("(.a[1,3) | .b[7,9))");
  }
}
