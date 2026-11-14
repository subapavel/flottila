package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.PreludeSignatures.DEFINITIONS;
import static io.github.subapavel.jqj.PreludeSignatures.REPLACED;
import static io.github.subapavel.jqj.PreludeSignatures.call;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqEngine;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ADR-0042 "Compile level" and "Lookup order": every prelude definition binds with no error, its signatures are
 * distinct, and no body refers to a definition after it (a prelude body sees only the definitions before it, then
 * itself, its params and nested defs, and the native table). {@link Prelude#signatures()} is the holder's index, in
 * file order. The binding is observed through the exported API: each definition's call makes its body live (ADR-0040),
 * so any binding error inside it would end the compile with the Binder's IllegalStateException, which names the
 * undefined name and the prelude definition, instead of a result or a Stub.
 */
class PreludeBindTest {
  private static final String MARK = "jqj stub: ";

  @Test
  void signaturesAreBuiltinJqsDefinitionsInFileOrder() {
    assertThat(Prelude.signatures()).as("Prelude.signatures()").isEqualTo(DEFINITIONS);
  }

  @Test
  void signaturesAreDistinct() {
    assertThat(Prelude.signatures()).as("Prelude.signatures()").hasSize(106).doesNotHaveDuplicates();
  }

  /**
   * Each definition but the three a native replaces (their bodies are never live) compiles or is a Stub: no binding
   * error. A binding error in a prelude body is the Binder's IllegalStateException, which this test does not catch, so
   * it fails with that exception: a binder that does not look in the native table from a prelude body fails at the
   * first definition, halt_error/0 ({@code halt_error/1 is not defined in the prelude's halt_error/0, bytes 16 to 26 of
   * builtin.jq}); one that does not see earlier definitions fails at add/0 ({@code add/1}).
   */
  @Test
  void everyDefinitionBindsWithNoError() {
    JqEngine engine = JqEngine.builder().build();
    List<String> errors = new ArrayList<>();
    for (String signature : DEFINITIONS) {
      if (REPLACED.contains(signature)) continue;
      try {
        engine.compile(call(signature));
      } catch (JqCompileException e) {
        if (e.getMessage() == null || !e.getMessage().startsWith(MARK)) errors.add(call(signature) + ": " + e.getMessage());
      }
    }
    assertThat(errors).as("prelude calls that end with a compile error other than a Stub").isEmpty();
  }

  /** On the shipped file: no call names a top-level definition that comes later. */
  @Test
  void builtinJqHasNoForwardReference() throws IOException {
    String text;
    try (InputStream in = PreludeBindTest.class.getResourceAsStream("/io/github/subapavel/jqj/internal/parse/builtin.jq")) {
      assertThat(in).as("the main resource builtin.jq").isNotNull();
      text = new String(in.readAllBytes(), UTF_8);
    }
    Program library = Parser.parse(text);
    assertThat(library.defs()).as("top-level definitions of builtin.jq").hasSize(106);
    assertThat(forwardReferences(library)).as("calls of a later top-level definition").isEmpty();
  }

  /** The walk itself finds a forward reference, and leaves out a param and a nested def of the same name. */
  @Test
  void theWalkFindsAForwardReference() {
    assertThat(forwardReferences(Parser.parse("def a: b; def b: 1;"))).as("def a: b; def b: 1;").containsExactly("a/0 -> b/0");
    assertThat(forwardReferences(Parser.parse("def a: [..]; def recurse: 1;"))).as("def a: [..]; def recurse: 1;").containsExactly("a/0 -> recurse/0");
    assertThat(forwardReferences(Parser.parse("def a(b): b; def b: 1;"))).as("a param").isEmpty();
    assertThat(forwardReferences(Parser.parse("def a: def b: 2; b; def b: 1;"))).as("a nested def").isEmpty();
    assertThat(forwardReferences(Parser.parse("def b: 1; def a: b;"))).as("a backward reference").isEmpty();
  }

  /** {@code "f/n -> g/m"} for each call in f's body of a later top-level g/m that no param or nested def of f shadows. */
  private static List<String> forwardReferences(Program library) {
    Map<String, Integer> position = new HashMap<>();
    for (int i = 0; i < library.defs().size(); i++) position.putIfAbsent(signature(library.defs().get(i)), i);
    List<String> found = new ArrayList<>();
    for (int i = 0; i < library.defs().size(); i++) {
      Ast.FuncDef def = library.defs().get(i);
      Set<String> local = new HashSet<>();
      for (Ast.Param p : def.params()) local.add(p.name() + "/0");
      List<String> calls = new ArrayList<>();
      walk(def.body(), local, calls);
      for (String c : calls) {
        Integer at = position.get(c);
        if (at != null && at > i && !local.contains(c)) found.add(signature(def) + " -> " + c);
      }
    }
    return found;
  }

  private static String signature(Ast.FuncDef def) {
    return def.name() + "/" + def.params().size();
  }

  /** Collects the calls under {@code node} ({@code ..} as recurse/0) and the signatures of its nested defs. */
  private static void walk(Object node, Set<String> local, List<String> calls) {
    switch (node) {
      case null -> {}
      case List<?> list -> {
        for (Object o : list) walk(o, local, calls);
      }
      case Ast.Call c -> {
        calls.add(c.name() + "/" + c.args().size());
        walk(c.args(), local, calls);
      }
      case Ast.Recurse _ -> calls.add("recurse/0");
      case Ast.FuncDef d -> {
        local.add(signature(d));
        for (Ast.Param p : d.params()) local.add(p.name() + "/0");
        walk(d.body(), local, calls);
      }
      case Record r -> {
        for (RecordComponent component : r.getClass().getRecordComponents()) {
          try {
            walk(component.getAccessor().invoke(r), local, calls);
          } catch (ReflectiveOperationException e) {
            throw new AssertionError("reading " + component + " of " + r.getClass(), e);
          }
        }
      }
      default -> {}
    }
  }
}
