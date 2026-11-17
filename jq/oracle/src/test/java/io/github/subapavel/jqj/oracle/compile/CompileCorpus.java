package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.oracle.RunnerThread;
import io.github.subapavel.jqj.oracle.diff.Corpus;
import io.github.subapavel.jqj.oracle.diff.DiffCase;
import io.github.subapavel.jqj.oracle.diff.DiffCorpus;
import io.github.subapavel.jqj.oracle.diff.DiffReport;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import io.github.subapavel.jqj.oracle.jqtest.JqTestCase;
import io.github.subapavel.jqj.oracle.jqtest.JqTestCorpus;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The compile-mode oracle's cases, ratchet and reports (ADR-0020 amendment for #60, F-58): every jqtest case, through
 * the public API, and every differential and generated case, through the command line; the json corpus is not run.
 * Each runs under the optimised compile and under each of {@link #MODES}: the conservative compile, and the trampoline
 * run, the conservative compile with {@code stackBytes} 0.
 */
final class CompileCorpus {

  /** The JUnit tag of the compile-mode tests; only the Maven profile {@code compile-oracle} runs them. */
  static final String TAG = "compile-mode";

  /**
   * The runs compared with the optimised one, in order: each a report's file suffix and the pass list's mode column.
   */
  static final List<String> MODES = List.of("conservative", "trampoline");

  /**
   * One case of the oracle.
   *
   * @param def the differential case's def; for a jqtest case, the 12 hex digits of its id, the hash of its lines
   * @param run the case's run through a hook on the engine's builder
   * @param limited whether the case is a differential one with {@code limit recursion-depth} (ADR-0047 #118), whose
   *     runs other than the optimised one pass only as a termination over that budget
   */
  record Case(String id, String def, Function<UnaryOperator<JqEngine.Builder>, Run> run, boolean limited) {
    /** A case without {@code limit recursion-depth}. */
    Case(String id, String def, Function<UnaryOperator<JqEngine.Builder>, Run> run) {
      this(id, def, run, false);
    }
  }

  private CompileCorpus() {}

  /** Every case, sorted by id; a differential case's working directories are made under {@code scratch}. */
  static List<Case> cases(Path scratch) {
    List<Case> all = new ArrayList<>();
    for (JqTestCase c : JqTestCorpus.cases()) {
      int hash = c.id().indexOf('#') + 1;
      all.add(new Case(c.id(), c.id().substring(hash, hash + 12), hook -> RunnerThread.call(() -> CompileModes.api(c, hook))));
    }
    for (DiffCorpus.Entry e : Corpus.cases()) {
      DiffCase c = e.c();
      all.add(new Case(c.id(), c.def(), hook -> RunnerThread.call(() -> CompileModes.cli(c, scratch, hook)), c.recursionDepth() != null));
    }
    all.sort(Comparator.comparing(Case::id));
    return all;
  }

  /**
   * The hook of the run of {@code mode}.
   *
   * @throws IllegalArgumentException when {@code mode} is none of {@link #MODES}
   */
  private static UnaryOperator<JqEngine.Builder> hook(String mode) {
    return switch (mode) {
      case "conservative" -> CompileModes.hook(CompileMode.CONSERVATIVE);
      case "trampoline" -> CompileModes.trampoline();
      default -> throw new IllegalArgumentException("compile oracle: no mode " + mode + ", only " + MODES);
    };
  }

  /**
   * The case's verdict under each of {@code modes}, in their order: its run of that mode against one optimised run, a
   * limited case's as {@link CompileModeRunner#judge(String, Run, String, Run, boolean)} judges it.
   *
   * @throws IllegalArgumentException before any run, when a mode is none of {@link #MODES}
   */
  static Map<String, Verdict> judge(Case c, List<String> modes) {
    Map<String, UnaryOperator<JqEngine.Builder>> hooks = new LinkedHashMap<>();
    for (String mode : modes) hooks.put(mode, hook(mode));
    Run optimised = c.run().apply(CompileModes.hook(CompileMode.OPTIMISED));
    Map<String, Verdict> verdicts = new LinkedHashMap<>();
    hooks.forEach((mode, hook) -> verdicts.put(mode, CompileModeRunner.judge(c.id(), optimised, mode, c.run().apply(hook), c.limited())));
    return verdicts;
  }

  /** {@code ratchet/oracle/compile.txt}. */
  static Path ratchet() {
    return Corpus.root().resolve("ratchet/oracle/compile.txt");
  }

  /**
   * The ids of a pass list by mode: each line's first column under its third; none if it does not exist. Blank and
   * {@code #} lines are skipped.
   */
  static Map<String, Set<String>> required(Path ratchet) {
    Map<String, Set<String>> ids = new HashMap<>();
    if (!Files.exists(ratchet)) return ids;
    try {
      for (String l : Files.readAllLines(ratchet, UTF_8)) {
        if (l.isEmpty() || l.startsWith("#")) continue;
        String[] c = l.split("\t", -1);
        ids.computeIfAbsent(c[2], m -> new HashSet<>()).add(c[0]);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return ids;
  }

  /**
   * The modes of {@link #MODES}, in order, under which {@code required} lists {@code id} when {@code listed}, or does not
   * list it otherwise.
   */
  static List<String> modes(String id, Map<String, Set<String>> required, boolean listed) {
    List<String> modes = new ArrayList<>();
    for (String mode : MODES) {
      if (required.getOrDefault(mode, Set.of()).contains(id) == listed) modes.add(mode);
    }
    return modes;
  }

  /**
   * Checks that every required mode is one of {@link #MODES} and every required id names a case, so a typo, a renamed
   * case or a mode no run has cannot pass by producing no test.
   *
   * @throws IllegalStateException listing every required mode, sorted, that is none of {@link #MODES}, or else every
   *     required id, sorted, that names no case
   */
  static void requireKnown(Map<String, Set<String>> required, List<Case> cases) {
    Set<String> strange = new TreeSet<>(required.keySet());
    MODES.forEach(strange::remove);
    if (!strange.isEmpty()) throw new IllegalStateException("compile oracle: required modes that are none of " + MODES + ": " + strange);
    Set<String> unknown = new TreeSet<>();
    required.values().forEach(unknown::addAll);
    for (Case c : cases) unknown.remove(c.id());
    if (!unknown.isEmpty()) throw new IllegalStateException("compile oracle: required ids that name no case of its corpus: " + unknown);
  }

  /**
   * Writes each mode's rows into its report {@code compile-<mode>.tsv}. Surefire sets {@code oracle.reports}; PIT does
   * not, so a mutant's verdicts are never written.
   */
  static void report(Map<String, List<DiffReport.Row>> rows) {
    String dir = System.getProperty("oracle.reports");
    if (dir == null) return;
    rows.forEach((mode, r) -> DiffReport.merge(Path.of(dir).resolve("compile-" + mode + ".tsv"), r));
  }
}
