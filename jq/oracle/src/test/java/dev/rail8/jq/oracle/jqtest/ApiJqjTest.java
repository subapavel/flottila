package dev.rail8.jq.oracle.jqtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rail8.jq.JqCompileException;
import dev.rail8.jq.JqFilterException;
import dev.rail8.jq.JqSyntaxException;
import dev.rail8.jq.JqTermination;
import dev.rail8.jq.Limits;
import dev.rail8.jq.oracle.diff.Verdict;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The jqtest oracle's jqj over the public API (ADR-0020, ADR-0024), against the S1.02 Stubs. The Stub construct moves
 * as tickets build each one; since #110 it is {@code $ENV} (spec 7).
 */
class ApiJqjTest {

  @Test
  void theLimitsAreEmbeddedWithoutTheDeadline() {
    Limits e = Limits.embedded();

    assertThat(ApiJqj.limits())
        .isEqualTo(
            new Limits(
                e.steps(), Limits.UNLIMITED_TIME, e.nestingDepth(), e.recursionDepth(), e.bytesBuilt(), e.bytesWritten(), e.regexMatchTime(), e.stackBytes()));
  }

  @Test
  void eachOfJqjsFourExceptionsKeepsItsKindAndACompileErrorItsBlocks() {
    JqjException compile = ApiJqj.translate(new JqCompileException("jq: error: x", 1, 2));
    assertThat(compile.kind()).isEqualTo(JqjException.Kind.COMPILE);
    assertThat(compile.getMessage()).isEqualTo("jq: error: x");
    assertThat(compile.blocks()).containsExactly("jq: error: x");

    JqjException syntax = ApiJqj.translate(new JqSyntaxException("Unfinished JSON term", 3, 1, 4));
    assertThat(syntax.kind()).isEqualTo(JqjException.Kind.SYNTAX);
    assertThat(syntax.getMessage()).isEqualTo("Unfinished JSON term");

    assertThat(ApiJqj.translate(new JqFilterException(JqNull.NULL)).kind()).isEqualTo(JqjException.Kind.FILTER);

    JqjException halt = ApiJqj.translate(new JqTermination(JqTermination.Cause.HALT, 5, JqNull.NULL, null));
    assertThat(halt.kind()).isEqualTo(JqjException.Kind.TERMINATION);
    assertThat(halt.getMessage()).isEqualTo("HALT exit 5");
  }

  @Test
  void nullAndTheBooleansAreCopiedAsTheirKinds() {
    assertThat(ApiJqj.copy(JqNull.NULL)).isEqualTo(JvValue.Constant.NULL);
    assertThat(ApiJqj.copy(JqBool.TRUE)).isEqualTo(JvValue.Constant.TRUE);
    assertThat(ApiJqj.copy(JqBool.FALSE)).isEqualTo(JvValue.Constant.FALSE);
  }

  @Test
  void compilingReadingAndRereadingEndWithTheStubsAsJqjExceptions() {
    ApiJqj jqj = new ApiJqj();

    // #33 built compile("."), and #34 compile(".a"), and #63 builds .[0], so this line compiled .., which #110 built
    // (ADR-0042 amendment for #110), so it now compiles $ENV, a Stub until spec 7 (#33 design C12, #34 design C4). #36
    // built doubleValue(), so read and reread of "1" no longer end in a Stub; they now read the malformed document
    // "[1,", whose JqSyntaxException carries the Stub mark until spec 5 (ADR-0024's malformed-input Stub; the Stub
    // ending approved for #36 under #33's C12 rule).
    assertThatThrownBy(() -> jqj.compile("$ENV")).isInstanceOfSatisfying(JqjException.class, e -> assertThat(e.stub()).isTrue());
    assertThatThrownBy(() -> jqj.read("[1,")).isInstanceOfSatisfying(JqjException.class, e -> assertThat(e.stub()).isTrue());
    assertThatThrownBy(() -> jqj.reread("[1,")).isInstanceOfSatisfying(JqjException.class, e -> assertThat(e.stub()).isTrue());
  }

  @Test
  void stubConstructScoresFailThroughTheApi() {
    // #33 acceptance: "A Stub construct such as 1 throws JqCompileException at the API, and the jqtest runner scores
    // it FAIL" (ADR-0024, F-20: before any other verdict rule). #61 built the literal 1, #63 .[0] and #110 .. (ADR-0042
    // amendment for #110), so the Stub construct is now $ENV, a Stub until spec 7 (jq-ref 1.8.2, observed 2026-11-15:
    // jq-ref -nc '$ENV|type' -> "object", rc 0).
    ApiJqj jqj = new ApiJqj();
    assertThatThrownBy(() -> jqj.compile("$ENV|type"))
        .isInstanceOfSatisfying(
            JqjException.class,
            e -> {
              assertThat(e.kind()).as("kind of compile(\"$ENV|type\")").isEqualTo(JqjException.Kind.COMPILE);
              assertThat(e.stub()).as("compile(\"$ENV|type\") carries the Stub mark").isTrue();
            });
    JqTestRunner runner = new JqTestRunner(jqj);
    Verdict env = runner.run(JqTestFile.parse("api.test", "$ENV|type\nnull\n\"object\"\n").getFirst());
    assertThat(env.kind()).as("verdict of the case $ENV|type").isEqualTo(Verdict.Kind.FAIL);
    assertThat(env.detail()).as("detail of the case $ENV|type").startsWith("jqj ended with a Stub");

    // Witness: through the same API a built case passes, so the FAIL above is the Stub's. jq-ref 1.8.2, observed
    // 2026-11-11: printf -- '[null,true,{"a":"b"}]' | jq-ref -c . -> [null,true,{"a":"b"}] (rc 0).
    Verdict dot = runner.run(JqTestFile.parse("api.test", ".\n[null,true,{\"a\":\"b\"}]\n[null,true,{\"a\":\"b\"}]\n").getFirst());
    assertThat(dot.kind()).as("verdict of the case . on [null,true,{\"a\":\"b\"}], detail '%s'", dot.detail()).isEqualTo(Verdict.Kind.PASS);
    // The former Stub construct passes since #110: jq-ref 1.8.2 gives [1] then 1 on [1] (diff/basics.rec's
    // basics/recurse-of-array, re-recorded byte-identical 2026-11-15).
    Verdict recurse = runner.run(JqTestFile.parse("api.test", "..\n[1]\n[1]\n1\n").getFirst());
    assertThat(recurse.kind()).as("verdict of the case .. on [1], detail '%s'", recurse.detail()).isEqualTo(Verdict.Kind.PASS);
  }

  @Test
  void theStubMarkIsThePrefixOfJqjsStubs() {
    assertThat(new JqjException(JqjException.Kind.COMPILE, "jqj stub: x", List.of()).stub()).isTrue();
    assertThat(new JqjException(JqjException.Kind.FILTER, null, List.of()).stub()).isFalse();
  }
}
