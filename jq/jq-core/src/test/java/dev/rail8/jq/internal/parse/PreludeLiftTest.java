package dev.rail8.jq.internal.parse;

import static dev.rail8.jq.PreludeSignatures.DEFINITIONS;
import static dev.rail8.jq.PreludeSignatures.LIFTED;
import static dev.rail8.jq.PreludeSignatures.STUBS;
import static dev.rail8.jq.PreludeSignatures.call;
import static dev.rail8.jq.internal.parse.PreludeInlineTest.sourceless;
import static dev.rail8.jq.internal.parse.PreludeInlineTest.sources;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.rail8.jq.query.compile.CompileMode;
import dev.rail8.jq.query.compile.Compiler;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Source;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * The Resolver's lifting of the recursive prelude (ADR-0042 amendment for #110, ADR-0045, GLOSSARY "Lifted function"):
 * a call into a recursive prelude group is a Call of a function lifted once per definition per compile, and a filter
 * that names no recursive builtin lifts nothing. Below the API because the API cannot see a Program; every program
 * here passes the Compiler, whose verifier checks each Call's closure and value counts against its function's.
 */
class PreludeLiftTest {

  /** The program of {@code text}, which the Compiler accepts: its verifier checked it. */
  private static Program program(String text) {
    Program program = Resolver.resolve(Parser.parse(text));
    assertThatCode(() -> Compiler.compile(program, 0, CompileMode.OPTIMISED)).as("compile of %s", text).doesNotThrowAnyException();
    return program;
  }

  /**
   * The design's guard for "a filter that names no recursive builtin lowers to the same IR as before": each of the 76
   * prelude calls that compile and reach no recursive definition (PreludeSignatures: neither a Stub nor lifted) lifts
   * no function, and neither does any core-v1 seed but those of form 6, which define their own recursive r and lift
   * exactly that one (CoreV1 "Form": seed % 7 == 6 is the one recursive template, and no seed names a recursive
   * builtin, CoreV1Test.noExcludedName). A Resolver that lifts the prelude's groups whenever it loads the prelude fails
   * both halves.
   */
  @Test
  void aFilterNamingNoRecursiveBuiltinLiftsNothing() throws IOException {
    List<String> lifting = new ArrayList<>();
    int calls = 0;
    for (String signature : DEFINITIONS) {
      if (STUBS.contains(signature) || LIFTED.contains(signature)) continue;
      calls++;
      String filter = call(signature);
      int functions = program(filter).functions().size();
      if (functions != 0) lifting.add(filter + ": " + functions);
    }
    assertThat(calls).as("prelude calls that compile and reach no recursive definition").isEqualTo(76);
    assertThat(lifting).as("non-recursive prelude calls that lift a function").isEmpty();
    // A recursive builtin named only in a dead definition is never reached, so nothing lifts (ADR-0042 "Live
    // definitions"; jq-ref 1.8.2, observed 2026-11-15: 'def f: ..; 1' on null -> 1).
    assertThat(program("def f: ..; 1").functions()).as("functions of def f: ..; 1").isEmpty();

    Map<Integer, String> corpus = coreV1();
    List<String> wrong = new ArrayList<>();
    int resolved = 0;
    int recursive = 0;
    for (Map.Entry<Integer, String> seed : corpus.entrySet()) {
      Program p = Resolver.resolve(Parser.parse(seed.getValue()));
      resolved++;
      int expected = seed.getKey() % 7 == 6 ? 1 : 0;
      recursive += expected;
      if (p.functions().size() != expected) wrong.add("seed " + seed.getKey() + " lifts " + p.functions().size() + ": " + seed.getValue());
    }
    // Every seed must resolve; one that does not fails the test with its exception. The counts are
    // ratchet/oracle/compile.txt's: it lists all 2100 core-v1 seeds, 0 to 2099, 300 of them of form 6, as cases whose
    // optimised and conservative compiles agree.
    assertThat(resolved).as("core-v1 seeds that resolve").isEqualTo(2100);
    assertThat(recursive).as("form-6 seeds among those that resolve").isEqualTo(300);
    assertThat(wrong).as("core-v1 seeds whose lifted functions are not their own recursive r").isEmpty();
  }

  /**
   * Once per definition per compile: each of the ten lifted calls reaches one recursive definition and lifts one
   * function; two uses of {@code ..}, two calls of recurse/1 with different closures and two calls of the top-level
   * combinations/0 each share one function and make two Calls in the main tree; range/3's two calls of while/2 share one
   * {@code _while}. {@code _flatten} and combinations/0 are top-level groups that call themselves: a Resolver that
   * re-lifts on each self-call never ends but fills the heap, and its OutOfMemoryError fails the test before the 120 s
   * timeout does; the timeout fails a re-lifting that runs without filling the heap.
   */
  @Test
  @Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
  void oneFunctionPerDefinitionPerCompile() {
    Map<String, Integer> wrong = new LinkedHashMap<>();
    for (String signature : LIFTED) {
      String filter = call(signature);
      int functions = program(filter).functions().size();
      if (functions != 1) wrong.put(filter, functions);
    }
    assertThat(wrong).as("lifted prelude calls whose function count is not 1").isEmpty();

    for (String filter : List.of("[..,..]", "[recurse(.a),recurse(.b)]", "[combinations, combinations]")) {
      Program p = program(filter);
      assertThat(p.functions()).as("functions of %s", filter).hasSize(1);
      List<Call> main = calls(p.main());
      assertThat(main).as("Calls in the main tree of %s", filter).hasSize(2);
      assertThat(main).as("functions the main Calls of %s name", filter).extracting(Call::fn).containsOnly(0);
    }
    assertThat(program("range(0;5;1)").functions()).as("functions of range(0;5;1)").hasSize(1);
    assertThat(program("[range(0;5;1), range(5;0;-1)]").functions()).as("functions of two range/3 calls").hasSize(1);
    assertThat(program("_flatten(2)").functions()).as("functions of _flatten(2)").hasSize(1);
  }

  /**
   * The design's lowering of {@code ..}: recurse/0 inlines {@code recurse(.[]?)}, which inlines recurse/1's body around
   * {@code Call(r, [.[]?], [])}, where r takes the captured f as its one closure parameter and no value:
   * {@code Function(1, 0, Comma(Identity, Pipe(ParamCall 0, Call(r, [ParamCall 0], []))))}. That is the program of the
   * same recursion written by the user with f as its own parameter, Sources aside.
   */
  @Test
  void dotDotLowersAsItsUserWrittenRecursion() {
    assertThat(sourceless(program("..")))
        .as("program of .., Sources aside")
        .isEqualTo(sourceless(program("def r(f): ., (f | r(f)); r(.[]?)")));
  }

  /**
   * ADR-0042 amendment for #110: the nodes of a lifted body take the Source of the call that first placed it, in
   * lowering order; closure arguments keep their own. In {@code [recurse(.a),recurse(.b)]} the first call is bytes
   * [1,12), its closure {@code .a} [9,11), the second call [13,24) and its {@code .b} [21,23).
   */
  @Test
  void aLiftedBodyTakesTheSourceOfTheCallThatFirstPlacedIt() {
    String filter = "[recurse(.a),recurse(.b)]";
    Program p = program(filter);
    List<Source> body = sources(p.functions().getFirst().body());
    assertThat(body).as("Sources of r's body").isNotEmpty();
    List<Source> outside = body.stream().filter(s -> s.start() < 1 || s.end() > 12).toList();
    assertThat(outside).as("Sources of r's body outside the first call [1,12)").isEmpty();
    List<Call> main = calls(p.main());
    assertThat(sources(main.get(0).closures().getFirst())).as("Sources of the first Call's closure").contains(new Source(9, 11)).allMatch(s -> s.start() >= 9 && s.end() <= 11);
    assertThat(sources(main.get(1).closures().getFirst())).as("Sources of the second Call's closure").contains(new Source(21, 23)).allMatch(s -> s.start() >= 21 && s.end() <= 23);
  }

  /**
   * A Source is text of the filter's (ADR-0031): a function lifted from the prelude takes the Source of the call that
   * first placed it, as its body's nodes do, not its definition's bytes in builtin.jq, which {@code explain()} would
   * print on the function's line (#108). In {@code [..]} that is the {@code ..} at [1,3); a function of the filter's own
   * text keeps its definition's, {@code def r: ., (.[]?|r);} at [0,19).
   */
  @Test
  void aLiftedPreludeFunctionTakesTheSourceOfTheCallThatFirstPlacedIt() {
    assertThat(program("[recurse(.a),recurse(.b)]").functions().getFirst().source()).as("Source of r in [recurse(.a),recurse(.b)]").isEqualTo(new Source(1, 12));
    assertThat(program("[..]").functions().getFirst().source()).as("Source of r in [..]").isEqualTo(new Source(1, 3));
    assertThat(program("def r: ., (.[]?|r); r").functions().getFirst().source()).as("Source of the user's r").isEqualTo(new Source(0, 19));
  }

  /** The Calls under {@code node}, pre-order, through every record component and list. */
  private static List<Call> calls(Object node) {
    List<Call> out = new ArrayList<>();
    collectCalls(node, out);
    return out;
  }

  private static void collectCalls(Object node, List<Call> out) {
    switch (node) {
      case null -> {}
      case List<?> list -> {
        for (Object o : list) collectCalls(o, out);
      }
      case Record r -> {
        if (r instanceof Call call) out.add(call);
        for (RecordComponent c : r.getClass().getRecordComponents()) {
          try {
            collectCalls(c.getAccessor().invoke(r), out);
          } catch (ReflectiveOperationException e) {
            throw new AssertionError("reading " + c + " of " + r.getClass(), e);
          }
        }
      }
      default -> {}
    }
  }

  /**
   * The filters of the core-v1 corpus by seed (oracle/src/test/resources/diff/gen/core-v1.cases, generated with its
   * recording by scripts/oracle-record.sh), each {@code filter} line unescaped as CaseFormat does: {@code \n}, {@code \t},
   * {@code \\} and {@code \xHH}.
   */
  private static Map<Integer, String> coreV1() throws IOException {
    Path cases = Path.of(System.getProperty("user.dir")).resolve("../oracle/src/test/resources/diff/gen/core-v1.cases").normalize();
    Map<Integer, String> filters = new LinkedHashMap<>();
    Integer seed = null;
    for (String line : Files.readAllLines(cases, UTF_8)) {
      if (line.startsWith("case gen/core-v1/")) {
        seed = Integer.valueOf(line.substring("case gen/core-v1/".length()));
      } else if (line.startsWith("filter ") && seed != null) {
        filters.put(seed, unescape(line.substring("filter ".length())));
      } else if (line.equals("end")) {
        seed = null;
      }
    }
    return filters;
  }

  private static String unescape(String value) {
    byte[] bytes = value.getBytes(UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] != '\\') {
        out.write(bytes[i]);
        continue;
      }
      char next = (char) bytes[++i];
      switch (next) {
        case 'n' -> out.write('\n');
        case 't' -> out.write('\t');
        case '\\' -> out.write('\\');
        case 'x' -> {
          out.write(HexFormat.fromHexDigits(new String(bytes, i + 1, 2, UTF_8)));
          i += 2;
        }
        default -> throw new IllegalArgumentException("escape \\" + next + " in " + value);
      }
    }
    return out.toString(UTF_8);
  }
}
