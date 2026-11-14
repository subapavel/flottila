package io.github.subapavel.jqj.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.oracle.diff.DiffReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Where the jqtest oracle's files and its ratchet are (ADR-0020): jq 1.8.2's seven test files. Public for the
 * compile-mode oracle, which runs the same cases.
 */
public final class JqTestCorpus {

  /** The one backend so far: the report's file suffix. */
  static final String BACKEND = "interpreter";

  private JqTestCorpus() {}

  /** {@code oracle/target/test-classes/jq/tests}: the copied {@code oracle/src/test/resources/jq/tests}. */
  static Path dir() {
    URL url = JqTestCorpus.class.getResource("/jq/tests");
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The {@code .test} files, sorted by name. */
  static List<Path> files() {
    try (Stream<Path> s = Files.list(dir())) {
      return s.filter(p -> p.getFileName().toString().endsWith(".test")).sorted(Comparator.comparing(Path::toString)).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Every case of every file, sorted by id, read once per JVM: PIT reruns a factory for each of its dynamic tests (F-84
   * (C)). Sound because PIT mutates no oracle class, so the loader is never a mutant (F-84 (B)). A file that is not
   * UTF-8 fails to read.
   */
  public static List<JqTestCase> cases() {
    return Cases.ALL;
  }

  /** The holder: its initializer reads the files on the first call of {@link #cases()}. */
  private static final class Cases {
    static final List<JqTestCase> ALL = read();

    private static List<JqTestCase> read() {
      List<JqTestCase> all = new ArrayList<>();
      for (Path f : files()) all.addAll(parse(f));
      all.sort(Comparator.comparing(JqTestCase::id));
      return List.copyOf(all);
    }
  }

  /** The repository root: five levels above {@code oracle/target/test-classes/jq/tests}. */
  private static Path root() {
    return dir().getParent().getParent().getParent().getParent().getParent();
  }

  /** The ratcheted ids and the jqtest ids the ticket names with {@code -Doracle.require=<file>}. */
  static Set<String> required() {
    return required(System.getProperty("oracle.require"));
  }

  /** The ratcheted ids and the jqtest ids of {@code requireFile} (null: none). */
  static Set<String> required(String requireFile) {
    return DiffReport.required(root().resolve("ratchet/oracle/jqtest.txt"), requireFile, DiffReport.Oracle.JQTEST);
  }

  /**
   * Writes rows into the report. Surefire sets {@code oracle.reports}; PIT does not, so a mutant's verdicts are never
   * written.
   */
  static void report(List<JqTestReport.Row> rows) {
    String reports = System.getProperty("oracle.reports");
    if (reports != null) JqTestReport.merge(Path.of(reports).resolve("jqtest-" + BACKEND + ".tsv"), rows);
  }

  /** The runner over the public API, {@link ApiJqj}'s host for every file (ADR-0020:50). */
  static JqTestRunner runner() {
    return new JqTestRunner(new ApiJqj());
  }

  /** The number of cases per file name. */
  static Map<String, Integer> countsPerFile() {
    Map<String, Integer> counts = new TreeMap<>();
    for (Path f : files()) counts.put(f.getFileName().toString(), parse(f).size());
    return counts;
  }

  private static List<JqTestCase> parse(Path f) {
    try {
      return JqTestFile.parse(f.getFileName().toString(), Files.readString(f, UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
