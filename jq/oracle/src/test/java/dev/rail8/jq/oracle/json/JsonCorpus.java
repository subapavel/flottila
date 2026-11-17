package dev.rail8.jq.oracle.json;

import dev.rail8.jq.cli.Main;
import dev.rail8.jq.oracle.diff.Corpus;
import dev.rail8.jq.oracle.diff.Deviations;
import dev.rail8.jq.oracle.diff.DiffCorpus;
import dev.rail8.jq.oracle.diff.DiffReport;
import dev.rail8.jq.oracle.diff.DiffRunner;
import dev.rail8.jq.oracle.diff.Verdict;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Where the json oracle's cases and its ratchet are (ADR-0020). The cases are the differential family {@code json}:
 * run by the differential runner, reported in the json oracle's format.
 */
final class JsonCorpus {

  /** The one backend so far: the report's file suffix. */
  static final String BACKEND = "interpreter";

  private JsonCorpus() {}

  /** {@code oracle/target/test-classes/json}: the copied {@code oracle/src/test/resources/json}. */
  static Path casesDir() {
    URL url = JsonCorpus.class.getResource("/json");
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Every case of {@link #casesDir()}, sorted by id, read once per JVM: PIT reruns a factory for each of its dynamic
   * tests (F-84 (C)). Sound because PIT mutates no oracle class, so the loader is never a mutant (F-84 (B)).
   */
  static List<DiffCorpus.Entry> cases() {
    return Cases.ALL;
  }

  /** The holder: its initializer reads the corpus on the first call of {@link #cases()}. */
  private static final class Cases {
    static final List<DiffCorpus.Entry> ALL = List.copyOf(DiffCorpus.load(casesDir()));
  }

  /** The repository root: four levels above {@code oracle/target/test-classes/json}. */
  private static Path root() {
    return casesDir().getParent().getParent().getParent().getParent();
  }

  /** The ratcheted ids and the json ids the ticket names with {@code -Doracle.require=<file>}. */
  static Set<String> required() {
    return required(System.getProperty("oracle.require"));
  }

  /** The ratcheted ids and the json ids of {@code requireFile} (null: none). */
  static Set<String> required(String requireFile) {
    return DiffReport.required(root().resolve("ratchet/oracle/json.txt"), requireFile, DiffReport.Oracle.JSON);
  }

  /** The label of a case id {@code json/<label><name>}: its prefix {@code y_}, {@code n_} or {@code i_}. */
  static String label(String id) {
    return id.substring("json/".length(), "json/".length() + 2);
  }

  /** The row of one verdict: id, verdict, detail, label, def. */
  static DiffReport.Row row(DiffCorpus.Entry e, Verdict v) {
    return new DiffReport.Row(e.c().id(), v, label(e.c().id()), e.c().def());
  }

  /**
   * Writes rows into the report. Surefire sets {@code oracle.reports}; PIT does not, so a mutant's verdicts are never
   * written.
   */
  static void report(List<DiffReport.Row> rows) {
    String dir = System.getProperty("oracle.reports");
    if (dir != null) DiffReport.merge(Path.of(dir).resolve("json-" + BACKEND + ".tsv"), rows);
  }

  /** The runner of the differential oracle over the command line's entry, with the repository's deviations. */
  static DiffRunner runner(Path scratch) {
    return new DiffRunner(Corpus.onRunnerThread(Main::run), scratch, Deviations.slugs(root().resolve("DEVIATIONS.md")));
  }
}
