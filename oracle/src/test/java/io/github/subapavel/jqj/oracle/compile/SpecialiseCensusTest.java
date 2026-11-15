package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.internal.parse.Ast;
import io.github.subapavel.jqj.internal.parse.Parser;
import io.github.subapavel.jqj.internal.parse.Resolver;
import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import io.github.subapavel.jqj.oracle.diff.Corpus;
import io.github.subapavel.jqj.oracle.jqtest.JqTestCase;
import io.github.subapavel.jqj.oracle.jqtest.JqTestCorpus;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.Compiler;
import io.github.subapavel.jqj.query.compile.Executable;
import io.github.subapavel.jqj.query.compile.Frozen;
import io.github.subapavel.jqj.query.compile.Specialiser;
import io.github.subapavel.jqj.query.ir.Program;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * The specialisation census of #117 (design comment, "SpecialiseCensusTest.largestSpecialisedNodeCount"; ADR-0045
 * "Amendment (ticket #117)", S5; GLOSSARY "Specialisation cap"): every CompileCorpus filter, each jqtest case's program
 * and each differential case's {@code filter} key, plus each prelude definition of builtin.jq called as {@code
 * name(.; …)}, compiled OPTIMISED as {@code JqEngine.compile} does it (the Resolver with no declared variables, then
 * {@code Compiler.compile}). A filter jqj does not compile ({@link JqCompileException}, a Stub among them) is counted and
 * left out; a throw from the Compiler, which runs the pass and the IrVerifier after it, fails the census.
 *
 * <p>Each filter is compiled twice. Under {@link Specialiser#SPECIALISE_CAP} its {@link Frozen#refused()} must be 0.
 * Under the lifted cap {@link #INLINE_CAP}, S5's ceiling, through the package-private {@code Compiler.compile(Program,
 * int, CompileMode, int cap)}: when that compile refuses nothing, every key is built and {@link Frozen#specialised()} is
 * the filter's demand, the nodes its copies hold; when it refuses a key, the demand is above INLINE_CAP (keys are spent
 * whole in order, so a refused key did not fit what was spent before it). The cap bounds the census's own compile: a
 * filter of #114's over-cap family may demand far more. No filter may demand more than the cap, and a filter that
 * refuses nothing under the cap has spent exactly its demand there. If anything is refused, S5 applies: the cap becomes
 * the next power of two at or above the largest demand, up to INLINE_CAP, and a demand above INLINE_CAP is a finding;
 * the failure and the log name it.
 *
 * <p>Witness that the census sees copies: each of #117's {@link #COPYING} cases, whose outside calls run copies by
 * design (SpecialiseExplainTest), compiles with a demand above 0. Without it a pass that copies nothing would pass with
 * nothing refused and a largest demand of 0.
 *
 * <p>The census writes its counts, the largest demand and the ten largest distinct filters to {@link #LOG} under the
 * repository root, before any verdict, so a failing run records them too; the #117 evidence comment cites that file.
 * The counts are read from {@code Executable.frozen()}, not from {@code explain()}: ExplainTest's {@code Line} lives in
 * jq-core's test classes, which the oracle module does not see.
 */
class SpecialiseCensusTest {

  /** The census log, below the repository root (#117 design comment). */
  static final String LOG = ".logs/117-specialise-census.log";

  /**
   * ADR-0040's inline cap ({@code Resolver.INLINE_CAP}, package-private there): S5's ceiling for a raised cap, and the
   * lifted cap of the demand compile.
   */
  static final int INLINE_CAP = 65_536;

  /** The top of the log: how many distinct filters it lists by demand. */
  static final int TOP = 10;

  /**
   * #117's required differential cases (diff/specialise.cases, require/117.txt): each outside call runs a copy, so each
   * demand is above 0 (SpecialiseExplainTest's closed-argument, over-INLINE_CAP, free-slot, free-Break and
   * literal-argument cases).
   */
  static final List<String> COPYING =
      List.of(
          "specialise/until-closed",
          "specialise/while-closed",
          "specialise/recurse-closed",
          "specialise/repeat-closed",
          "specialise/over-inline-cap-recurse",
          "specialise/range-3-free-slots-30000",
          "specialise/until-free-slot-30000",
          "specialise/free-break",
          "specialise/literal-arguments");

  /**
   * builtin.jq's top-level definitions: 106, jq-core's PreludeSignatures.DEFINITIONS (jq 1.8.2's {@code
   * src/builtin.jq}, cross-checked on jq-ref 1.8.2 by #71's test-author).
   */
  static final int PRELUDE_DEFINITIONS = 106;

  /** The package-private compile with a test-only cap, reached as CompileModes reaches the compile-mode switch. */
  private static final Method CAPPED = CompileModes.method(Compiler.class, "compile", Program.class, int.class, CompileMode.class, int.class);

  /** One filter of the census: where it comes from and its text. */
  record Filter(String id, String text) {}

  /**
   * One compiled filter: under the lifted cap its demand, exact when {@code above} is false and above INLINE_CAP when it
   * is true; under SPECIALISE_CAP the nodes it spent and the keys it refused.
   */
  record Count(Filter filter, int demand, boolean above, int spent, int refused) {
    /** The demand for sorting and showing: above INLINE_CAP counts as INLINE_CAP + 1. */
    int ranked() {
      return above ? INLINE_CAP + 1 : demand;
    }
  }

  @Test
  void largestSpecialisedNodeCount() {
    List<Filter> filters = new ArrayList<>();
    for (JqTestCase c : JqTestCorpus.cases()) {
      filters.add(new Filter(c.id(), c.program()));
    }
    Map<String, Integer> left = new TreeMap<>();
    List<Filter> differential = differential(left);
    filters.addAll(differential);
    List<Filter> prelude = prelude();
    filters.addAll(prelude);

    Set<String> corpus = new TreeSet<>();
    Corpus.cases().forEach(e -> corpus.add(e.c().id()));
    Set<String> read = new TreeSet<>(left.keySet());
    differential.forEach(f -> read.add(f.id()));
    assertThat(read).as("premise: the differential ids the census read are CompileCorpus's").isEqualTo(corpus);
    assertThat(prelude).as("premise: builtin.jq's definitions, called as name(.; …)").hasSize(PRELUDE_DEFINITIONS);

    Map<String, Integer> notCompiled = new TreeMap<>();
    List<String> threw = new ArrayList<>();
    List<Count> counts = new ArrayList<>();
    for (Filter f : filters) {
      Program forCap;
      Program forDemand;
      try {
        forCap = resolve(f.text());
        forDemand = resolve(f.text());
      } catch (JqCompileException e) {
        notCompiled.merge(e.getMessage() != null && e.getMessage().startsWith("jqj stub: ") ? "Stub" : "compile error", 1, Integer::sum);
        continue;
      }
      try {
        Frozen capped = Compiler.compile(forCap, 0, CompileMode.OPTIMISED).frozen();
        Frozen lifted = compile(forDemand, INLINE_CAP).frozen();
        counts.add(new Count(f, lifted.specialised(), lifted.refused() != 0, capped.specialised(), capped.refused()));
      } catch (RuntimeException e) {
        threw.add(f.id() + ": " + e);
      }
    }
    counts.sort(Comparator.comparingInt(Count::ranked).reversed().thenComparing(c -> c.filter().id()));

    String s5 = s5(counts.isEmpty() ? 0 : counts.getFirst().ranked());
    List<Count> refusing = counts.stream().filter(c -> c.refused() != 0).toList();
    List<Count> overCap = counts.stream().filter(c -> c.ranked() > Specialiser.SPECIALISE_CAP).toList();
    log(filters.size(), differential.size(), prelude.size(), left, notCompiled, threw, counts, refusing, s5);

    Map<String, Count> byId = new LinkedHashMap<>();
    counts.forEach(c -> byId.put(c.filter().id(), c));
    SoftAssertions soft = new SoftAssertions();
    soft.assertThat(threw).as("filters whose OPTIMISED compile threw (the pass or the IrVerifier after it)").isEmpty();
    for (String id : COPYING) {
      Count c = byId.get(id);
      soft.assertThat(c).as("witness: %s compiles, and its compiles do not throw", id).isNotNull();
      if (c != null) {
        soft.assertThat(c.ranked()).as("witness: the demand of %s, whose outside calls run copies (%s)", id, c.filter().text()).isPositive();
      }
    }
    soft.assertThat(refusing.stream().map(SpecialiseCensusTest::shown).toList())
        .as("filters with a key refused under SPECIALISE_CAP %d; S5: %s (log %s)", Specialiser.SPECIALISE_CAP, s5, LOG)
        .isEmpty();
    soft.assertThat(overCap.stream().map(SpecialiseCensusTest::shown).toList())
        .as("the largest demand is at most SPECIALISE_CAP %d: the filters that demand more, largest first; S5: %s", Specialiser.SPECIALISE_CAP, s5)
        .isEmpty();
    soft.assertThat(counts.stream().filter(c -> c.refused() == 0 && (c.above() || c.spent() != c.demand())).map(SpecialiseCensusTest::shown).toList())
        .as("filters that refuse nothing under the cap but spend other than their demand")
        .isEmpty();
    soft.assertAll();
  }

  /** {@code text} parsed and resolved as {@code JqEngine.compile(text)} does: no declared variables. */
  private static Program resolve(String text) {
    return Resolver.resolved(Parser.parse(text), List.of()).program();
  }

  /** The OPTIMISED compile of {@code program} with the specialisation cap {@code cap}. */
  private static Executable compile(Program program, int cap) {
    try {
      return (Executable) CAPPED.invoke(null, program, 0, CompileMode.OPTIMISED, cap);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException r) {
        throw r;
      }
      if (e.getCause() instanceof Error r) {
        throw r;
      }
      throw new IllegalStateException(e.getCause());
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("the census cannot call Compiler.compile(Program, int, CompileMode, int)", e);
    }
  }

  /**
   * Each differential case's filter, its one {@code filter} key, read from the same files DiffCorpus.load walks; a
   * case with no {@code filter} key or with more than one is counted in {@code left} and has none.
   */
  private static List<Filter> differential(Map<String, Integer> left) {
    Path dir = Corpus.casesDir();
    List<Filter> out = new ArrayList<>();
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".cases")).sorted().toList()) {
        for (CaseFormat.Block b : CaseFormat.read(f, CaseFormat.family(dir, f))) {
          List<String> keys = b.lines().stream().filter(l -> l.key().equals("filter")).map(CaseFormat.Line::value).toList();
          if (keys.size() == 1) {
            out.add(new Filter(b.id(), new String(CaseFormat.unescape(keys.getFirst()), UTF_8)));
          } else {
            left.put(b.id(), keys.size());
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  /**
   * Each top-level definition of the builtin.jq jq-core ships beside its Parser (ADR-0042), called with {@code .} for
   * every argument: {@code until(.; .)}, {@code recurse(.)}, {@code add}.
   */
  private static List<Filter> prelude() {
    String text;
    try (InputStream in = Parser.class.getResourceAsStream("builtin.jq")) {
      assertThat(in).as("premise: jq-core's builtin.jq resource").isNotNull();
      text = new String(in.readAllBytes(), UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Filter> out = new ArrayList<>();
    for (Ast.FuncDef def : Parser.parse(text).defs()) {
      int arity = def.params().size();
      String call = arity == 0 ? def.name() : def.name() + "(" + String.join("; ", Collections.nCopies(arity, ".")) + ")";
      out.add(new Filter("prelude/" + def.name() + "/" + arity, call));
    }
    return out;
  }

  /**
   * S5 (ADR-0045 "Amendment (ticket #117)") for the largest demand {@code ranked} ({@link Count#ranked()}): the next
   * power of two at or above it, up to INLINE_CAP; above INLINE_CAP, a finding.
   */
  private static String s5(int ranked) {
    if (ranked > INLINE_CAP) {
      return "a finding: the largest demand is above INLINE_CAP " + INLINE_CAP;
    }
    int cap = 1;
    while (cap < ranked) {
      cap <<= 1;
    }
    return "the cap becomes " + cap + ", the next power of two at or above the largest demand " + ranked;
  }

  /** A demand as the log and the failures show it: the count, or {@code >65536} above INLINE_CAP. */
  private static String demand(Count c) {
    return c.above() ? ">" + INLINE_CAP : Integer.toString(c.demand());
  }

  private static String shown(Count c) {
    return c.filter().id() + " demand=" + demand(c) + " spent=" + c.spent() + " refused=" + c.refused() + " " + abbreviated(c.filter().text());
  }

  /** {@code text} on one line, at most 100 characters. */
  private static String abbreviated(String text) {
    String line = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
    return line.length() <= 100 ? line : line.substring(0, 97) + "...";
  }

  private static void log(
      int filters,
      int differential,
      int prelude,
      Map<String, Integer> left,
      Map<String, Integer> notCompiled,
      List<String> threw,
      List<Count> counts,
      List<Count> refusing,
      String s5) {
    List<String> lines = new ArrayList<>();
    lines.add("# SpecialiseCensusTest (#117): every CompileCorpus filter and each prelude definition called as name(.; ...), compiled OPTIMISED");
    lines.add("SPECIALISE_CAP\t" + Specialiser.SPECIALISE_CAP);
    lines.add("filters\t" + filters + "\tjqtest " + (filters - differential - prelude) + ", differential " + differential + ", prelude " + prelude);
    lines.add("differential cases without one filter key\t" + left.size() + "\t" + left.keySet());
    lines.add("not compiled\t" + notCompiled.values().stream().mapToInt(Integer::intValue).sum() + "\t" + notCompiled);
    lines.add("compile threw\t" + threw.size());
    threw.forEach(t -> lines.add("  " + t));
    lines.add("compiled\t" + counts.size());
    lines.add("specialised (demand > 0)\t" + counts.stream().filter(c -> c.ranked() > 0).count());
    lines.add("demand above INLINE_CAP " + INLINE_CAP + "\t" + counts.stream().filter(Count::above).count());
    lines.add("refusing under the cap\t" + refusing.size());
    refusing.forEach(c -> lines.add("  " + shown(c)));
    lines.add("largest demand\t" + (counts.isEmpty() ? "0" : demand(counts.getFirst())));
    lines.add("S5 on a refusal\t" + s5);
    lines.add("top " + TOP + " distinct filters by demand: demand, spent, refused, id, filter");
    Set<String> listed = new HashSet<>();
    for (Count c : counts) {
      if (listed.size() == TOP) {
        break;
      }
      if (listed.add(c.filter().text())) {
        lines.add(demand(c) + "\t" + c.spent() + "\t" + c.refused() + "\t" + c.filter().id() + "\t" + abbreviated(c.filter().text()));
      }
    }
    Path log = Corpus.root().resolve(LOG);
    try {
      Files.createDirectories(log.getParent());
      Files.write(log, lines, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    System.out.println(
        "SpecialiseCensusTest: largest demand " + (counts.isEmpty() ? "0" : demand(counts.getFirst())) + " over " + counts.size() + " compiled filters; " + refusing.size() + " refusing; log " + log);
  }
}
