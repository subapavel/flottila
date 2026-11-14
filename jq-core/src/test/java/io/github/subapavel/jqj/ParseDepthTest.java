package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.internal.parse.Parser;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code JqEngine.compile} on a deeply nested filter (#81, DEVIATIONS "Parse depth"): it ends with jq's {@code memory
 * exhausted} compile error, never with a {@code StackOverflowError}. jq-ref 1.8.2's first rejected depths, recorded in
 * diff/depth.rec: 9995 parentheses or brackets around 1, 3332 objects {@code {"a": ...}} around 1, and 10 000 pipes,
 * each rc 3 with one {@code memory exhausted} block. jqj's parser stops earlier, past 512 nesting levels: this test pins
 * that boundary per shape, which the differential cases cannot show.
 */
class ParseDepthTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  private static final String EXHAUSTED = "jq: error: memory exhausted at <top-level>, line 1, column ";

  /** {@code depth} copies of {@code open}, then {@code 1}, then {@code depth} copies of {@code close}. */
  private static String nested(String open, String close, int depth) {
    return open.repeat(depth) + "1" + close.repeat(depth);
  }

  static Stream<Arguments> jqsFirstRejectedDepths() {
    return Stream.of(
        Arguments.argumentSet("9995 parentheses", nested("(", ")", 9995), 129),
        Arguments.argumentSet("9995 brackets", nested("[", "]", 9995), 129),
        Arguments.argumentSet("3332 objects", nested("{\"a\":", "}", 3332), 641),
        Arguments.argumentSet("10 000 pipes", nested("1|", "", 10_000), 1021),
        Arguments.argumentSet("100 000 pipes", nested("1|", "", 100_000), 1021));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("jqsFirstRejectedDepths")
  void jqsFirstRejectedDepthIsMemoryExhaustedNotAStackOverflow(String filter, int column) {
    JqCompileException e = assertThatExceptionOfType(JqCompileException.class).isThrownBy(() -> ENGINE.compile(filter)).actual();

    assertThat(e.blocks()).as("blocks").hasSize(1);
    assertThat(e.blocks().getFirst()).as("block").startsWith(EXHAUSTED + column + ":\n");
    assertThat(e.getMessage()).as("message").endsWith("\njq: 1 compile error");
    assertThat(e.column()).as("column").isEqualTo(column);
  }

  /**
   * jqj's last accepted depth per shape and the column of its block one level deeper. Of the 512 levels, the top query,
   * expression and term take 3, then each parenthesis, bracket or object 4 (its error state, query or object value,
   * expression and term), so 127 fit; each pipe, {@code -} or {@code try} 1, so 509 fit, and 509 minus signs fill the
   * 512 exactly. A pattern takes 1 (510 brackets after the query), an {@code elif} 2 (its {@code if} and error state),
   * an object value after {@code |} 1, and a definition 2 (its body and the body's query).
   */
  static Stream<Arguments> jqjsLastAcceptedDepths() {
    return Stream.of(
        shape("parentheses", n -> nested("(", ")", n), 127, 129, 1),
        shape("brackets", n -> nested("[", "]", n), 127, 129, 1),
        shape("objects", n -> nested("{\"a\":", "}", n), 127, 641, 1),
        shape("pipes", n -> nested("1|", "", n), 509, 1021, 1),
        shape("minus signs", n -> nested("-", "", n), 509, 511, 1),
        shape("tries", n -> nested("try ", "", n), 509, 2041, 1),
        shape("array patterns", n -> ". as " + "[".repeat(n) + "$a" + "]".repeat(n) + " | 1", 510, 517, 2),
        shape("elifs", n -> "if 1 then 1 " + "elif 1 then 1 ".repeat(n) + "end", 252, 3546, 1),
        shape("object-value pipes", n -> "{a: " + "1 | ".repeat(n) + "1}", 505, 2029, 1),
        shape("definitions", n -> "def f: ".repeat(n) + "1" + "; 1".repeat(n), 255, 1793, 1));
  }

  private static Arguments shape(String name, IntFunction<String> filter, int depth, int column, int length) {
    return Arguments.argumentSet(name, filter, depth, column, length);
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("jqjsLastAcceptedDepths")
  void jqjsLastAcceptedDepthParsesAndOneMoreIsMemoryExhausted(IntFunction<String> filter, int depth, int column, int length) {
    assertThatCode(() -> Parser.parse(filter.apply(depth))).as("parse at depth %d", depth).doesNotThrowAnyException();

    String deeper = filter.apply(depth + 1);
    JqCompileException e = assertThatExceptionOfType(JqCompileException.class).isThrownBy(() -> Parser.parse(deeper)).actual();
    assertThat(e.blocks()).as("blocks at depth %d", depth + 1).containsExactly(block(deeper, column, length));
  }

  /** jq's block for a stack overflow at the token of {@code length} bytes at {@code column} of the one-line {@code filter}. */
  private static String block(String filter, int column, int length) {
    return EXHAUSTED + column + ":\n    " + filter + "\n" + " ".repeat(3 + column) + "^".repeat(length);
  }

  @Test
  void theDeepestAcceptedFiltersCompileAndRunOnA512KiBStack() throws InterruptedException {
    // ADR-0017's stackBytes: 512 KiB. The JUnit thread's stack is larger (1 MiB here), so this is the tighter check.
    List<String> filters =
        List.of(
            nested("(", ")", 127),
            nested("[", "]", 127),
            nested("{\"a\":", "}", 127),
            nested("1|", "", 509),
            nested("-", "", 509),
            nested("{(", "):1}", 127),
            nested(".\"\\(", ")\"", 127),
            ". as " + nested("{a:", "}", 254).replace("1", "$a") + " | 1");
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                for (String filter : filters) {
                  compileOrStub(filter);
                }
                assertThat(results(nested("(", ")", 127))).as("results of 127 parentheses").hasSize(1);
                assertThat(results(nested("1|", "", 509))).as("results of 509 pipes").hasSize(1);
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "512 KiB",
            512 * 1024);
    thread.start();
    thread.join();

    assertThat(failure.get()).as("failure on the 512 KiB thread").isNull();
  }

  @Test
  void theDeepestAcceptedParenthesesCompileAndRunOnTheTestThread() {
    assertThat(results(nested("(", ")", 127))).as("results of 127 parentheses").hasSize(1);
  }

  @Test
  void aLongCommaChainNestsNoLevelAndEndsInNoStackOverflow() {
    // A comma nests left (jq's %left ','): jq-ref compiles 10 921 commas and rejects 10 922 with "function compiled to
    // 65536 bytes which is too long", rc 3 (diff/depth.rec). Comma is a Stub until #63; whatever compile gives must be a
    // result or a compile error.
    String chain = nested("1,", "", 100_000);
    assertThatCode(() -> Parser.parse(chain)).as("parse of 100 000 commas").doesNotThrowAnyException();
    assertThatCode(() -> compileOrStub(chain)).as("compile of 100 000 commas").doesNotThrowAnyException();
  }

  @Test
  void aWideFilterOpensAndClosesItsLevels() {
    // Each copy opens every kind of level (query, expression, term, object value, pattern, if, definition body and
    // bracketed form) a few times and closes them all, so 600 copies pass 512 only if a level is never closed.
    String copy = "(def f: [-1 // {a: 1 | 2}]; if . then f elif . then f else f end | . as [$x] ?// {a: $x} | try $x)";
    String wide = String.join(", ", Collections.nCopies(600, copy));

    assertThatCode(() -> Parser.parse(wide)).as("parse of 600 copies").doesNotThrowAnyException();
  }

  @Test
  void theBlocksBeforeTheOverflowStay() {
    // jq-ref 1.8.2, observed: "\v", then 9995 parentheses gives the lexical block, then memory exhausted, "jq: 2
    // compile errors"; {1+2:3}, then 9995 parentheses its syntax and recovery blocks, then memory exhausted, 3 errors.
    String deep = nested("(", ")", 9995);

    JqCompileException lexical =
        assertThatExceptionOfType(JqCompileException.class).isThrownBy(() -> ENGINE.compile("\"\\v\", " + deep)).actual();
    assertThat(lexical.blocks()).as("blocks after a bad escape").hasSize(2);
    assertThat(lexical.blocks().getFirst()).as("first block").startsWith("jq: error: Invalid escape");
    assertThat(lexical.blocks().getLast()).as("last block").startsWith(EXHAUSTED + 135 + ":\n");
    assertThat(lexical.getMessage()).as("message").endsWith("\njq: 2 compile errors");
    assertThat(lexical.column()).as("column of the first block").isEqualTo(2);

    JqCompileException recovered =
        assertThatExceptionOfType(JqCompileException.class).isThrownBy(() -> ENGINE.compile("{1+2:3}, " + deep)).actual();
    assertThat(recovered.blocks()).as("blocks after a recovery").hasSize(3);
    assertThat(recovered.blocks().get(1)).as("recovery block").startsWith("jq: error: May need parentheses around object key expression");
    assertThat(recovered.blocks().getLast()).as("last block").startsWith(EXHAUSTED + 138 + ":\n");
  }

  /** Compiles {@code filter}; a compile error (a Stub today, or jq's own) is no failure, a stack overflow is. */
  private static void compileOrStub(String filter) {
    try {
      ENGINE.compile(filter);
    } catch (JqCompileException e) {
      assertThat(e.getMessage()).as("compile error of a filter jqj's parser accepts").doesNotContain("memory exhausted");
    }
  }

  private static List<JqValue> results(String filter) {
    Workspace workspace = newWorkspace();
    JqValue root = read(workspace, "null").root();
    List<JqValue> results = new ArrayList<>();
    ENGINE
        .compile(filter)
        .run(
            root,
            Bindings.builder().build(),
            v -> {
              results.add(v);
              return true;
            },
            workspace);
    return results;
  }
}
