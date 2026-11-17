package io.github.subapavel.jqj.internal.parse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADR-0042's prelude (GLOSSARY "Prelude"): jq 1.8.2's builtin.jq, the resource beside this class, parsed once per JVM
 * by a holder class on the first lookup, with no setting of any Engine, and indexed by {@code name/arity}. The AST is
 * shared read-only; each compile binds and lowers only the definitions its filter reaches ({@link Binder}). A filter
 * that needs no prelude definition never looks one up, so it never parses the file.
 */
final class Prelude {
  /** How many times the file was parsed: 0 before the first lookup, 1 after it. */
  private static final AtomicInteger LOADS = new AtomicInteger();

  /** The parsed file, made when the class is first used. */
  private static final class Library {
    /** The definitions by signature. builtin.jq's signatures are distinct (PreludeBindTest). */
    static final Map<String, Ast.FuncDef> INDEX;

    /** The signatures in file order. */
    static final List<String> SIGNATURES;

    static {
      List<Ast.FuncDef> defs = Parser.parse(text()).defs();
      Map<String, Ast.FuncDef> index = new HashMap<>();
      List<String> signatures = new ArrayList<>(defs.size());
      for (Ast.FuncDef def : defs) {
        String signature = def.name() + "/" + def.params().size();
        signatures.add(signature);
        index.put(signature, def);
      }
      INDEX = Map.copyOf(index);
      SIGNATURES = List.copyOf(signatures);
      LOADS.incrementAndGet();
    }

    private Library() {}

    private static String text() {
      try (InputStream in = Prelude.class.getResourceAsStream("builtin.jq")) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        // The resource ships in jq-core's own jar: a read error is the jar's, not the filter's.
        throw new UncheckedIOException(e);
      }
    }
  }

  private Prelude() {}

  /** How many times this JVM has parsed builtin.jq: never more than once. */
  static int loads() {
    return LOADS.get();
  }

  /** builtin.jq's top-level signatures, {@code name/arity}, in file order. */
  static List<String> signatures() {
    return Library.SIGNATURES;
  }

  /** The top-level definition {@code signature}, or null when builtin.jq has none. */
  static Ast.FuncDef definition(String signature) {
    return Library.INDEX.get(signature);
  }
}
