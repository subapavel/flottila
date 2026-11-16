package io.github.subapavel.jqj.oracle.diff;

import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.cli.Invocation;
import io.github.subapavel.jqj.cli.Main;
import io.github.subapavel.jqj.oracle.RunnerThread;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Where the differential corpus and the ratchet are, found from the class path so any working directory works. Public
 * for the compile-mode oracle, which runs the same cases.
 */
public final class Corpus {

  /** The one backend so far: the report's file suffix and the pass list's backend column (ADR-0020). */
  static final String BACKEND = "interpreter";

  private Corpus() {}

  /** {@code oracle/target/test-classes/diff}: the copied {@code oracle/src/test/resources/diff}. */
  public static Path casesDir() {
    URL url = Corpus.class.getResource("/diff");
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
  public static List<DiffCorpus.Entry> cases() {
    return Cases.ALL;
  }

  /** The holder: its initializer reads the corpus on the first call of {@link #cases()}. */
  private static final class Cases {
    static final List<DiffCorpus.Entry> ALL = List.copyOf(DiffCorpus.load(casesDir()));
  }

  /** The repository root: four levels above {@code oracle/target/test-classes/diff}. */
  public static Path root() {
    return casesDir().getParent().getParent().getParent().getParent();
  }

  /**
   * The runner of the differential oracle and its survey over {@code entry}, {@code Main::run}, which runs on the
   * {@link RunnerThread} (F-88). A case with {@code limit recursion-depth n} runs the command line's engine with
   * {@link Limits#cli()} under a recursion-depth budget of {@code n} instead (ADR-0047 #118), on the same thread; that
   * entry ends no run on a termination, so one there is the runner's CRASH.
   */
  static DiffRunner runner(Path scratch, ToIntFunction<Invocation> entry) {
    return new DiffRunner(onRunnerThread(entry), depth -> onRunnerThread(invocation -> Main.run(invocation, b -> b.limits(recursionDepth(Limits.cli(), depth)))), scratch, deviations());
  }

  /** {@code l} with a recursion-depth budget of {@code depth}. */
  private static Limits recursionDepth(Limits l, long depth) {
    return new Limits(l.steps(), l.deadline(), l.nestingDepth(), Math.toIntExact(depth), l.bytesBuilt(), l.bytesWritten(), l.regexMatchTime(), l.stackBytes());
  }

  /** {@code entry} on the {@link RunnerThread} (F-88): the differential and the json runners' entry. */
  public static ToIntFunction<Invocation> onRunnerThread(ToIntFunction<Invocation> entry) {
    return invocation -> RunnerThread.call(() -> entry.applyAsInt(invocation));
  }

  /** The deviation slugs of the repository's {@code DEVIATIONS.md}. */
  static Set<String> deviations() {
    return Deviations.slugs(root().resolve("DEVIATIONS.md"));
  }

  /** The ratcheted ids and the differential ids the ticket names with {@code -Doracle.require=<file>}. */
  static Set<String> required() {
    return required(System.getProperty("oracle.require"));
  }

  /** The ratcheted ids and the differential ids of {@code requireFile} (null: none). */
  static Set<String> required(String requireFile) {
    return DiffReport.required(root().resolve("ratchet/oracle/differential.txt"), requireFile, DiffReport.Oracle.DIFFERENTIAL);
  }

  /**
   * Writes rows into the report. Surefire sets {@code oracle.reports}; PIT does not, so a mutant's verdicts are never
   * written.
   */
  static void report(List<DiffReport.Row> rows) {
    String dir = System.getProperty("oracle.reports");
    if (dir != null) DiffReport.merge(Path.of(dir).resolve("differential-" + BACKEND + ".tsv"), rows);
  }
}
