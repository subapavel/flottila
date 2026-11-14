package io.github.subapavel.jqj.oracle.compile;

import static java.nio.charset.StandardCharsets.UTF_8;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The compile-mode oracle's cases, ratchet and report (ADR-0020 amendment for #60): every jqtest case, through the
 * public API, and every differential and generated case, through the command line; the json corpus is not run. Each
 * runs under the optimised and the conservative compile.
 */
final class CompileCorpus {

  /** The JUnit tag of the compile-mode tests; only the Maven profile {@code compile-oracle} runs them. */
  static final String TAG = "compile-mode";

  /** The compile compared with the optimised one: the report's file suffix and the pass list's mode column. */
  static final String MODE = "conservative";

  /**
   * One case of the oracle.
   *
   * @param def the differential case's def; for a jqtest case, the 12 hex digits of its id, the hash of its lines
   * @param run the case's run under a compile mode
   */
  record Case(String id, String def, Function<CompileMode, Run> run) {}

  private CompileCorpus() {}

  /** Every case, sorted by id; a differential case's working directories are made under {@code scratch}. */
  static List<Case> cases(Path scratch) {
    List<Case> all = new ArrayList<>();
    for (JqTestCase c : JqTestCorpus.cases()) {
      int hash = c.id().indexOf('#') + 1;
      all.add(new Case(c.id(), c.id().substring(hash, hash + 12), m -> CompileModes.api(c, CompileModes.hook(m))));
    }
    for (DiffCorpus.Entry e : Corpus.cases()) {
      DiffCase c = e.c();
      all.add(new Case(c.id(), c.def(), m -> CompileModes.cli(c, scratch, CompileModes.hook(m))));
    }
    all.sort(Comparator.comparing(Case::id));
    return all;
  }

  /** The case's verdict from its optimised and its conservative run. */
  static Verdict judge(Case c) {
    return CompileModeRunner.judge(c.id(), c.run().apply(CompileMode.OPTIMISED), c.run().apply(CompileMode.CONSERVATIVE));
  }

  /** {@code ratchet/oracle/compile.txt}. */
  static Path ratchet() {
    return Corpus.root().resolve("ratchet/oracle/compile.txt");
  }

  /** The ids of a pass list, its first column; none if it does not exist. Blank and {@code #} lines are skipped. */
  static Set<String> required(Path ratchet) {
    Set<String> ids = new HashSet<>();
    if (!Files.exists(ratchet)) return ids;
    try {
      for (String l : Files.readAllLines(ratchet, UTF_8)) {
        if (!l.isEmpty() && !l.startsWith("#")) ids.add(l.split("\t", 2)[0]);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return ids;
  }

  /**
   * Checks that every required id names a case, so a typo or a renamed case cannot pass by producing no test.
   *
   * @throws IllegalStateException listing every required id, sorted, that names no case
   */
  static void requireKnown(Set<String> required, List<Case> cases) {
    Set<String> unknown = new TreeSet<>(required);
    for (Case c : cases) unknown.remove(c.id());
    if (!unknown.isEmpty()) throw new IllegalStateException("compile oracle: required ids that name no case of its corpus: " + unknown);
  }

  /**
   * Writes rows into the report {@code compile-conservative.tsv}. Surefire sets {@code oracle.reports}; PIT does not,
   * so a mutant's verdicts are never written.
   */
  static void report(List<DiffReport.Row> rows) {
    String dir = System.getProperty("oracle.reports");
    if (dir != null) DiffReport.merge(Path.of(dir).resolve("compile-" + MODE + ".tsv"), rows);
  }
}
