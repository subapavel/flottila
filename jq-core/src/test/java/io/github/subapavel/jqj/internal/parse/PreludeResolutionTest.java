package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.PreludeSignatures.DEFINITIONS;
import static io.github.subapavel.jqj.PreludeSignatures.STUBS;
import static io.github.subapavel.jqj.PreludeSignatures.call;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.ir;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.parsesToAStub;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.query.ir.Source;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The implementer's cases of #71's binding and lowering (ADR-0042), beside the test-author's: a C native's arguments in
 * the caller's compile level, an unbuilt native that is a Stub and not jq's "not defined", a prelude body bound only
 * when the main filter reaches it, and the Sources of the nodes inlined from builtin.jq, which all lie in the filter's
 * own text. Below the API because the API cannot see a binding or a Source.
 */
class PreludeResolutionTest {

  /**
   * A C native's arguments stay in the caller's level, their own errors in source order and their inner levels right
   * first, as an operator's (jq-ref 1.8.2, observed 2026-11-13 with -nc, rc 3 and exactly these blocks).
   */
  @Test
  void aCNativesArgumentsStayInTheCallersLevelTheirInnerLevelsRightFirst() {
    assertThat(blocks("setpath(map(zz1); map(zz2))"))
        .as("blocks of setpath(map(zz1); map(zz2))")
        .containsExactly(notDefined("zz2", 23, "setpath(map(zz1); map(zz2))"), notDefined("zz1", 13, "setpath(map(zz1); map(zz2))"));
    assertThat(blocks("setpath(zz1; zz2)"))
        .as("blocks of setpath(zz1; zz2)")
        .containsExactly(notDefined("zz1", 9, "setpath(zz1; zz2)"), notDefined("zz2", 14, "setpath(zz1; zz2)"));
    // The inner level of map's closure reports nothing, since the main level has an error.
    assertThat(blocks("zz0, getpath(map(zz1))")).as("blocks of zz0, getpath(map(zz1))").containsExactly(notDefined("zz0", 1, "zz0, getpath(map(zz1))"));
    // A replacing native's argument is an inner level, as builtin.jq's flatten/1's is (jq-ref: zz0 only).
    assertThat(blocks("zz0, flatten(zz1)")).as("blocks of zz0, flatten(zz1)").containsExactly(notDefined("zz0", 1, "zz0, flatten(zz1)"));
  }

  /**
   * A bytecoded builtin's argument is an inner level, so the main level's error hides it (jq-ref 1.8.2, observed
   * 2026-11-13 with -nc: rc 3 and only the zz1 block).
   */
  @Test
  void aBytecodedBuiltinsArgumentIsAnInnerLevel() {
    assertThat(blocks("zz1, last(zz2)")).as("blocks of zz1, last(zz2)").containsExactly(notDefined("zz1", 1, "zz1, last(zz2)"));
  }

  /**
   * GLOSSARY "Unbuilt native": a call binds, and its lowering is a Stub. jq-ref 1.8.2 compiles each, so none is "not
   * defined" (observed 2026-11-13 with -nc on null: utf8bytelength and .a | utf8bytelength raise "null (null) only
   * strings have UTF-8 byte length", rc 5; [.[]? | explode] gives [], rc 0). #72 built length and tostring, so the
   * natives here are spec 4's, still unbuilt.
   */
  @Test
  void anUnbuiltNativeIsAStubNotJqsNotDefined() {
    parsesToAStub("utf8bytelength");
    parsesToAStub(".a | utf8bytelength");
    parsesToAStub("[.[]? | explode]");
  }

  /**
   * ADR-0042 "Live definitions": a prelude body is bound only when the main filter reaches it. map/1's body is {@code
   * [.[] | f]}; its call of {@code f} has a binding when the filter calls map and none when only a dead definition does.
   */
  @Test
  void aPreludeBodyIsBoundOnlyWhenTheMainFilterReachesIt() {
    Ast.Call f = (Ast.Call) ((Ast.Pipe) ((Ast.ArrayCons) Prelude.definition("map/1").body()).body()).right();
    assertThat(f.name()).as("the call in map/1's body").isEqualTo("f");
    assertThat(binder("map(.)").binding(f)).as("binding of map's f when map(.) is the filter").isInstanceOf(Binder.Parameter.class);
    assertThat(binder("def g: map(.); 1").binding(f)).as("binding of map's f when only the dead g calls map").isNull();
  }

  /**
   * ADR-0042 "Source": every node of the IR of a prelude call that compiles is at the call or at a closure argument, so
   * every Source lies in the filter's text and none is a place in builtin.jq (each definition's body there starts past
   * the length of its call).
   */
  @Test
  void everySourceOfAnInlinedPreludeCallLiesInTheFilter() {
    List<String> outside = new ArrayList<>();
    for (String signature : DEFINITIONS) {
      if (STUBS.contains(signature)) continue;
      String filter = call(signature);
      int length = filter.getBytes(UTF_8).length;
      for (Source s : PreludeInlineTest.sources(ir(filter))) {
        if (s.start() < 0 || s.end() > length || s.start() > s.end()) outside.add(filter + ": " + s);
      }
    }
    assertThat(outside).as("Sources outside the filter's text").isEmpty();
  }

  /** The filter's own nodes after a prelude call keep their own Sources: {@code .b} of {@code map(.a) | .b} is [10,12). */
  @Test
  void aNodeAfterAPreludeCallKeepsItsOwnSource() {
    List<Source> all = PreludeInlineTest.sources(ir("map(.a) | .b"));
    assertThat(all).as("Sources of map(.a) | .b in pre-order").last().isEqualTo(new Source(10, 12));
  }

  private static Binder binder(String filter) {
    Program p = Parser.parse(filter);
    return Binder.bind(p.main(), List.of(), p.text());
  }

  private static List<String> blocks(String filter) {
    return assertThatExceptionOfType(JqCompileException.class).as("resolve of %s", filter).isThrownBy(() -> ir(filter)).actual().blocks();
  }

  /** jq's block for {@code name/0 is not defined} at {@code column} of the one-line {@code filter}. */
  private static String notDefined(String name, int column, String filter) {
    return "jq: error: " + name + "/0 is not defined at <top-level>, line 1, column " + column + ":\n    " + filter + "\n    " + " ".repeat(column - 1) + "^".repeat(name.length());
  }
}
