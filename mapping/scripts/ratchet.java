// The ratchet (ADR-0019, format in ratchet/README.md). Single-file program, no dependencies:
//
//   java scripts/ratchet.java check        ticket-gate step 5: fail on a drop; unbuilt reports print NOT_RUN
//   java scripts/ratchet.java regen --all [<bench result>...]
//                                          spec-gate step 4: require every report, refuse a drop, write ratchet/;
//                                          with bench results also judge them and write bench.tsv
//   java scripts/ratchet.java bench <jmh.json> [<tree.json>] [<jmh-2.json> <jmh-3.json>]
//                                          spec-gate step 5: judge the bench results, write nothing
//
// Run from the repository root (or the root of a scratch copy; the working directory is the root).
//
// Inputs (all relative to the root):
//   engine/oracle/target/surefire-reports/TEST-*.xml   a test case's name is its case id. With the
//       surefire phrased reporter a dynamic test is named "cases() <display name>", so the case id is the
//       text after the first space, or the whole name when there is none. A test class whose simple
//       name contains "Differential" feeds ratchet/oracle/differential.txt, one that contains "Corpus"
//       feeds ratchet/oracle/corpus.txt, any other class is ignored. A case passes only when all
//       its metamorphic variants pass, so the oracle tests report one test case per case id.
//   <module>/target/site/jacoco/jacoco.csv             lines and branches, rounded down
//   <module>/target/pit-reports/mutations.xml          surviving mutants (generated - killed, NON_VIABLE excluded)
//   ui/coverage/coverage-summary.json                  summed per directory (ui/src/model, ui/src/studio/store)
//
//
// Bench results (ADR-0021 "The bench ratchet", #184, "Notes of 2026-11-11", #193), in the order given. A scenario in
// three or more files is judged on the median of their scores (the first run and the two re-runs of a regressed or
// missed scenario); in one or two files a later file's scenario replaces an earlier one's:
//   a JMH JSON file (-rf json, a top-level array). The score is the median of the per-fork means of rawData
//       (one array per fork). Names: see SCENARIOS. The large throughput benchmark carries the @Param
//       inputBytes, the exact byte length of its input; MB/s = inputBytes / 2^20 / (seconds per op).
//   the tree-scenario result, a JSON object {"scenario": "ui/tree-20128/scroll", "score": <p95>, "unit": "ms"}.
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class Ratchet {

  private static final Path ROOT = Path.of("").toAbsolutePath();
  private static final Path RATCHET = ROOT.resolve("ratchet");
  private static final Path ORACLE_REPORTS = ROOT.resolve("engine/oracle/target/surefire-reports");
  private static final List<String> UI_DIRECTORIES = List.of("ui/src/model", "ui/src/studio/store");
  // #161 moved the store; the baseline follows the module
  private static final Map<String, String> MOVED_COVERAGE_MODULES =
      Map.of("ui/src/editor/store", "ui/src/studio/store");
  private static final List<String> MUTATION_MODULES =
      List.of(
          "engine/xdm",
          "engine/ir",
          "engine/runtime",
          "engine/serializer",
          "engine/mapping",
          "engine/xslt-export",
          "engine/api",
          "engine/udf-api",
          "engine/udf");

  private static int failures = 0;

  public static void main(String[] args) throws Exception {
    String verdict;
    if (args.length == 1 && args[0].equals("check")) {
      run(false, List.of());
      verdict = "RATCHET CHECK OK";
    } else if (args.length >= 2 && args[0].equals("regen") && args[1].equals("--all")) {
      run(true, paths(args, 2));
      verdict = "RATCHET REGEN OK";
    } else if (args.length >= 2 && args[0].equals("bench")) {
      judgeBench(paths(args, 1));
      verdict = "RATCHET BENCH OK";
    } else {
      System.err.println(
          "usage: java scripts/ratchet.java check | regen --all [<bench result>...] | bench <bench result>...");
      System.exit(2);
      return;
    }
    if (failures > 0) {
      System.out.println("RATCHET FAIL: " + failures + " problem(s)");
      System.exit(1);
    }
    System.out.println(verdict);
  }

  private static List<Path> paths(String[] args, int from) {
    List<Path> paths = new ArrayList<>();
    for (int i = from; i < args.length; i++) {
      paths.add(ROOT.resolve(args[i]));
    }
    return paths;
  }

  /** benchResults empty: bench.tsv is neither judged nor written (off the VM, or check). */
  private static void run(boolean regen, List<Path> benchResults) throws Exception {
    // current = what this build measured; null = report absent
    Map<String, List<String>> oracle = readOracle();
    Map<String, int[]> coverage = measureCoverage();
    Map<String, int[]> mutation = measureMutation();

    Map<String, List<String>> newFiles = new LinkedHashMap<>();

    for (String file : List.of("corpus", "differential")) {
      String name = "oracle/" + file + ".txt";
      List<String> listed = readLines(RATCHET.resolve(name));
      List<String> passing = oracle == null ? null : oracle.get(file);
      checkOracle(name, listed, passing, regen);
      TreeSet<String> merged = new TreeSet<>(listed);
      if (passing != null) {
        merged.addAll(passing);
      }
      newFiles.put(name, new ArrayList<>(merged));
    }

    // coverage.tsv: <module> TAB <lines %> TAB <branches %>
    List<String> coverageLines = new ArrayList<>();
    TreeMap<String, String> coverageRows = new TreeMap<>();
    for (String row : readLines(RATCHET.resolve("coverage.tsv"))) {
      String[] c = row.split("\t");
      String module = c[0];
      String moved = MOVED_COVERAGE_MODULES.get(module);
      if (moved != null) {
        System.out.println("MOVED coverage " + module + " -> " + moved);
        module = moved;
      }
      int lines = Integer.parseInt(c[1]);
      int branches = Integer.parseInt(c[2]);
      int[] now = coverage.get(module);
      if (now == null) {
        notRun("coverage " + module, regen);
        coverageRows.put(module, moved == null ? row : module + "\t" + lines + "\t" + branches);
        continue;
      }
      compare("coverage " + module + " lines", lines, now[0]);
      compare("coverage " + module + " branches", branches, now[1]);
      coverageRows.put(module, module + "\t" + Math.max(lines, now[0]) + "\t" + Math.max(branches, now[1]));
    }
    for (Map.Entry<String, int[]> e : coverage.entrySet()) {
      if (!coverageRows.containsKey(e.getKey())) {
        coverageRows.put(e.getKey(), e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1]);
        System.out.println("NEW  coverage " + e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]);
      }
    }
    coverageLines.addAll(coverageRows.values());
    newFiles.put("coverage.tsv", coverageLines);

    // mutation.tsv: <module> TAB <surviving mutants> TAB <generated mutants> (#87). One more survivor is a drop.
    // The old two-column row (<module> TAB <score %>) is still read, compared as a whole percent and rewritten
    // in the new format by regen.
    TreeMap<String, String> mutationRows = new TreeMap<>();
    for (String row : readLines(RATCHET.resolve("mutation.tsv"))) {
      String[] c = row.split("\t");
      int[] now = mutation.get(c[0]);
      if (now == null) {
        // PIT runs only on the modules a spec touched (ADR-0019, step 1b): a missing report is not a drop
        notRun("mutation " + c[0], false);
        mutationRows.put(c[0], row);
        continue;
      }
      int survivors = now[0];
      if (c.length < 3) {
        int listed = Integer.parseInt(c[1]);
        compare("mutation " + c[0], listed, percent(now[1] - now[0], now[0]));
      } else {
        int listed = Integer.parseInt(c[1]);
        if (survivors > listed) {
          fail("mutation " + c[0] + ": " + survivors + " surviving mutants, the ratchet's " + listed);
        } else if (survivors < listed) {
          System.out.println("RISE mutation " + c[0] + ": " + listed + " -> " + survivors + " surviving mutants");
        }
        survivors = Math.min(listed, survivors);
      }
      mutationRows.put(c[0], c[0] + "\t" + survivors + "\t" + now[1]);
    }
    for (Map.Entry<String, int[]> e : mutation.entrySet()) {
      if (!mutationRows.containsKey(e.getKey())) {
        mutationRows.put(e.getKey(), e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1]);
        System.out.println("NEW  mutation " + e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]);
      }
    }
    newFiles.put("mutation.tsv", new ArrayList<>(mutationRows.values()));

    if (!benchResults.isEmpty()) {
      newFiles.put("bench.tsv", judgeBench(benchResults));
    }

    if (regen) {
      requireReportsForRegen(oracle, coverage);
      if (failures == 0) {
        write(newFiles);
      }
    }
  }

  // ---- oracle ----

  /** Passing case ids per oracle ("corpus", "differential"), or null when no report exists. */
  private static Map<String, List<String>> readOracle() throws Exception {
    if (!Files.isDirectory(ORACLE_REPORTS)) {
      return null;
    }
    List<Path> reports;
    try (Stream<Path> s = Files.list(ORACLE_REPORTS)) {
      reports =
          s.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml"))
              .sorted()
              .toList();
    }
    if (reports.isEmpty()) {
      return null;
    }
    Map<String, TreeSet<String>> passing = new LinkedHashMap<>();
    passing.put("corpus", new TreeSet<>());
    passing.put("differential", new TreeSet<>());
    TreeSet<String> notPassing = new TreeSet<>();
    for (Path report : reports) {
      Element suite = parse(report).getDocumentElement();
      NodeList cases = suite.getElementsByTagName("testcase");
      for (int i = 0; i < cases.getLength(); i++) {
        Element tc = (Element) cases.item(i);
        String oracle = oracleOf(tc.getAttribute("classname"));
        if (oracle == null) {
          continue;
        }
        String id = caseId(tc.getAttribute("name"));
        boolean ok =
            tc.getElementsByTagName("failure").getLength() == 0
                && tc.getElementsByTagName("error").getLength() == 0
                && tc.getElementsByTagName("skipped").getLength() == 0
                && tc.getElementsByTagName("rerunFailure").getLength() == 0
                && tc.getElementsByTagName("flakyFailure").getLength() == 0;
        if (ok) {
          passing.get(oracle).add(id);
        } else {
          notPassing.add(oracle + ":" + id);
        }
      }
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (String oracle : passing.keySet()) {
      TreeSet<String> ids = passing.get(oracle);
      // a case that failed in any report is not passing, whatever else was reported for it
      ids.removeIf(id -> notPassing.contains(oracle + ":" + id));
      result.put(oracle, new ArrayList<>(ids));
    }
    result.put("failing", new ArrayList<>(notPassing));
    return result;
  }

  private static String oracleOf(String classname) {
    String simple = classname.substring(classname.lastIndexOf('.') + 1);
    if (simple.contains("Differential")) {
      return "differential";
    }
    if (simple.contains("Corpus")) {
      return "corpus";
    }
    return null;
  }

  private static String caseId(String name) {
    int space = name.indexOf(' ');
    return space < 0 ? name : name.substring(space + 1);
  }

  private static void checkOracle(String file, List<String> listed, List<String> passing, boolean regen) {
    if (passing == null) {
      if (!listed.isEmpty()) {
        notRun(file + " (" + listed.size() + " cases)", regen);
      }
      return;
    }
    TreeSet<String> passingSet = new TreeSet<>(passing);
    int missing = 0;
    for (String id : listed) {
      if (!passingSet.contains(id)) {
        fail(file + ": listed case " + id + " is failing, skipped or missing");
        missing++;
      }
    }
    int rise = 0;
    TreeSet<String> listedSet = new TreeSet<>(listed);
    for (String id : passing) {
      if (!listedSet.contains(id)) {
        rise++;
      }
    }
    if (rise > 0) {
      System.out.println("RISE " + file + ": " + rise + " new passing case(s)");
    }
  }

  // ---- coverage ----

  private static Map<String, int[]> measureCoverage() throws Exception {
    Map<String, int[]> result = new TreeMap<>();
    for (String module : modulesWithJacoco()) {
      Path csv = ROOT.resolve(module).resolve("target/site/jacoco/jacoco.csv");
      List<String> rows = Files.readAllLines(csv, StandardCharsets.UTF_8);
      if (rows.size() < 2) {
        continue; // header only: the module has no classes to measure
      }
      List<String> header = List.of(rows.get(0).split(","));
      int lm = header.indexOf("LINE_MISSED");
      int lc = header.indexOf("LINE_COVERED");
      int bm = header.indexOf("BRANCH_MISSED");
      int bc = header.indexOf("BRANCH_COVERED");
      long linesMissed = 0;
      long linesCovered = 0;
      long branchesMissed = 0;
      long branchesCovered = 0;
      for (String row : rows.subList(1, rows.size())) {
        String[] c = row.split(",");
        linesMissed += Long.parseLong(c[lm]);
        linesCovered += Long.parseLong(c[lc]);
        branchesMissed += Long.parseLong(c[bm]);
        branchesCovered += Long.parseLong(c[bc]);
      }
      result.put(module, new int[] {percent(linesCovered, linesMissed), percent(branchesCovered, branchesMissed)});
    }
    Path summary = ROOT.resolve("ui/coverage/coverage-summary.json");
    if (Files.isRegularFile(summary)) {
      Object json = new Json(Files.readString(summary)).parse();
      for (String dir : UI_DIRECTORIES) {
        String prefix = ROOT.resolve(dir) + "/";
        long[] lines = new long[2]; // covered, total
        long[] branches = new long[2];
        boolean any = false;
        for (Map.Entry<String, Object> e : asMap(json).entrySet()) {
          if (e.getKey().startsWith(prefix)) {
            any = true;
            Map<String, Object> file = asMap(e.getValue());
            lines[0] += number(asMap(file.get("lines")).get("covered"));
            lines[1] += number(asMap(file.get("lines")).get("total"));
            branches[0] += number(asMap(file.get("branches")).get("covered"));
            branches[1] += number(asMap(file.get("branches")).get("total"));
          }
        }
        if (any) {
          result.put(dir, new int[] {percent(lines[0], lines[1] - lines[0]), percent(branches[0], branches[1] - branches[0])});
        }
      }
    }
    return result;
  }

  /** Every directory with a JaCoCo report: engine modules and service. */
  private static List<String> modulesWithJacoco() throws IOException {
    List<String> modules = new ArrayList<>();
    try (Stream<Path> engine = Files.list(ROOT.resolve("engine"))) {
      engine.sorted().forEach(p -> modules.add("engine/" + p.getFileName()));
    }
    modules.add("service");
    modules.removeIf(m -> !Files.isRegularFile(ROOT.resolve(m).resolve("target/site/jacoco/jacoco.csv")));
    return modules;
  }

  private static int percent(long covered, long missed) {
    long total = covered + missed;
    return total == 0 ? 100 : (int) (covered * 100 / total);
  }

  // ---- mutation ----

  /** Per module {surviving, generated}; generated without NON_VIABLE, surviving = generated - detected. */
  private static Map<String, int[]> measureMutation() throws Exception {
    Map<String, int[]> result = new TreeMap<>();
    for (String module : MUTATION_MODULES) {
      Path xml = ROOT.resolve(module).resolve("target/pit-reports/mutations.xml");
      if (!Files.isRegularFile(xml)) {
        continue;
      }
      NodeList mutations = parse(xml).getDocumentElement().getElementsByTagName("mutation");
      long total = 0;
      long detected = 0;
      for (int i = 0; i < mutations.getLength(); i++) {
        Element m = (Element) mutations.item(i);
        if (m.getAttribute("status").equals("NON_VIABLE")) {
          continue;
        }
        total++;
        if (m.getAttribute("detected").equals("true")) {
          detected++;
        }
      }
      result.put(module, new int[] {(int) (total - detected), (int) total});
    }
    return result;
  }

  // ---- regen ----

  /** Every report that exists must be present: oracle, and a JaCoCo report per module with production code. */
  private static void requireReportsForRegen(Map<String, List<String>> oracle, Map<String, int[]> coverage)
      throws IOException {
    if (oracle == null) {
      if (hasTestSources(ROOT.resolve("engine/oracle"))) {
        fail("regen: no oracle report in " + ROOT.relativize(ORACLE_REPORTS));
      }
    } else if (!oracle.get("failing").isEmpty()) {
      System.out.println("note: failing oracle cases are not written: " + oracle.get("failing"));
    }
    List<String> modules = new ArrayList<>();
    try (Stream<Path> engine = Files.list(ROOT.resolve("engine"))) {
      engine.sorted().forEach(p -> modules.add("engine/" + p.getFileName()));
    }
    modules.add("service");
    for (String module : modules) {
      if (module.equals("engine/oracle") || !hasProductionCode(ROOT.resolve(module))) {
        continue;
      }
      if (!coverage.containsKey(module)) {
        fail("regen: no JaCoCo report for " + module + " (it has production code)");
      }
    }
    for (String dir : UI_DIRECTORIES) {
      if (hasSources(ROOT.resolve(dir)) && !coverage.containsKey(dir)) {
        fail("regen: no Vitest coverage for " + dir + " (ui/coverage/coverage-summary.json)");
      }
    }
  }

  private static boolean hasProductionCode(Path module) throws IOException {
    Path main = module.resolve("src/main/java");
    if (!Files.isDirectory(main)) {
      return false;
    }
    try (Stream<Path> s = Files.walk(main)) {
      return s.anyMatch(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("module-info.java"));
    }
  }

  private static boolean hasTestSources(Path module) throws IOException {
    Path test = module.resolve("src/test/java");
    if (!Files.isDirectory(test)) {
      return false;
    }
    try (Stream<Path> s = Files.walk(test)) {
      return s.anyMatch(p -> p.toString().endsWith(".java"));
    }
  }

  private static boolean hasSources(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      return s.anyMatch(p -> p.toString().matches(".*\\.tsx?$") && !p.toString().matches(".*\\.test\\.tsx?$"));
    }
  }

  private static void write(Map<String, List<String>> newFiles) throws IOException {
    for (Map.Entry<String, List<String>> e : newFiles.entrySet()) {
      Path file = RATCHET.resolve(e.getKey());
      List<String> before = readLines(file);
      List<String> after = e.getValue();
      if (before.equals(after)) {
        continue;
      }
      Files.createDirectories(file.getParent());
      Files.writeString(file, after.isEmpty() ? "" : String.join("\n", after) + "\n", StandardCharsets.UTF_8);
      System.out.println("--- ratchet/" + e.getKey());
      for (String line : before) {
        if (!after.contains(line)) {
          System.out.println("- " + line);
        }
      }
      for (String line : after) {
        if (!before.contains(line)) {
          System.out.println("+ " + line);
        }
      }
    }
  }

  // ---- bench (ADR-0021 "The bench ratchet", GATE check 7) ----

  /** The machine of the targets and of every bench.tsv row; scripts/gate-spec.sh judges only there. */
  private static final String MACHINE = "claude-cloud-4vcpu";

  private record Unit(boolean lowerIsBetter, double tolerance) {}

  /** Direction and tolerance per unit; a result in any other unit fails. */
  private static final Map<String, Unit> UNITS =
      Map.of(
          "us/op", new Unit(true, 0.20),
          "ms/op", new Unit(true, 0.20),
          "ms", new Unit(true, 0.20),
          "B/op", new Unit(true, 0.10),
          "MB/s", new Unit(false, 0.20));

  /** Seconds per unit of a time per operation, for the large throughput. */
  private static final Map<String, Double> SECONDS_PER_OP = Map.of("us/op", 1e-6, "ms/op", 1e-3);

  private static final double MB = 1 << 20;

  private enum Kind {
    SCORE,
    THROUGHPUT,
    COMPLETES
  }

  /** Scenario names with %s for the mapping; alloc null: the benchmark has no alloc scenario. */
  private record Naming(String primary, String alloc, Kind kind) {}

  // The naming rule, the only one: the JMH benchmark io.flottila.mapping.bench.<Mapping>Bench.<method> gives the
  // scenarios of SCENARIOS[<method>], where <mapping> is <Mapping> with a lower-case first letter
  // (OrdersBench.smallLatency: orders/small/latency). The primary metric gives the primary scenario, the
  // gc.alloc.rate.norm secondary metric (-prof gc) the alloc scenario. The heap fork (-Xmx128m) only has to
  // complete, so it has no row. Any other benchmark name fails.
  private static final Pattern BENCHMARK = Pattern.compile("io\\.flottila\\.mapping\\.bench\\.(\\w+)Bench\\.(\\w+)");
  private static final Map<String, Naming> SCENARIOS =
      Map.of(
          "smallLatency", new Naming("%s/small/latency", "%s/small/alloc", Kind.SCORE),
          "largeThroughput", new Naming("%s/large/throughput", "%s/large/alloc", Kind.THROUGHPUT),
          "largeHeap", new Naming("%s/large/heap", null, Kind.COMPLETES),
          "compile", new Naming("compile/%s", null, Kind.SCORE));

  private enum Op {
    AT_MOST("<="),
    AT_LEAST(">="),
    BELOW("<");

    final String text;

    Op(String text) {
      this.text = text;
    }
  }

  private record Target(Pattern scenario, Op op, double value, String unit) {
    boolean met(double score) {
      return switch (op) {
        case AT_MOST -> score <= value;
        case AT_LEAST -> score >= value;
        case BELOW -> score < value;
      };
    }
  }

  /** GATE check 7 on claude-cloud-4vcpu. */
  private static final List<Target> TARGETS =
      List.of(
          new Target(Pattern.compile("\\w+/small/latency"), Op.AT_MOST, 500, "us/op"),
          new Target(Pattern.compile("\\w+/large/throughput"), Op.AT_LEAST, 20, "MB/s"),
          new Target(Pattern.compile("compile/orders"), Op.AT_MOST, 5, "ms/op"),
          new Target(Pattern.compile("ui/tree-20128/scroll"), Op.BELOW, 33.3, "ms"));

  /** benchmark: the JMH benchmark that measured it, null for the tree result. */
  private record Result(double score, String unit, String benchmark) {}

  /**
   * Judges the bench results against bench.tsv and the targets, prints the JMH benchmarks that regressed or missed
   * a target as one include regex (RERUN), and returns the new bench.tsv: a row moves only on a gain beyond its
   * tolerance.
   */
  private static List<String> judgeBench(List<Path> files) throws IOException {
    Map<String, Result> results = readBenchResults(files);
    results
        .entrySet()
        .removeIf(
            e -> {
              boolean unknown = !UNITS.containsKey(e.getValue().unit());
              if (unknown) {
                fail("bench " + e.getKey() + ": unknown unit " + e.getValue().unit());
              }
              return unknown;
            });
    TreeMap<String, String> rows = new TreeMap<>();
    TreeSet<String> rerun = new TreeSet<>();
    for (String row : readLines(RATCHET.resolve("bench.tsv"))) {
      String[] c = row.split("\t");
      String scenario = c[0];
      rows.put(scenario, row);
      Result now = results.get(scenario);
      if (!c[1].equals(MACHINE)) {
        fail("bench " + scenario + ": machine label " + c[1] + ", this judgement is for " + MACHINE);
      } else if (now == null) {
        fail("bench " + scenario + ": no result");
      } else if (!now.unit().equals(c[3])) {
        fail("bench " + scenario + ": unit " + now.unit() + ", the baseline is in " + c[3]);
      } else {
        Unit unit = UNITS.get(c[3]);
        double base = Double.parseDouble(c[2]);
        double worse = unit.lowerIsBetter() ? now.score() - base : base - now.score();
        double margin = base * unit.tolerance();
        String score = format(now.score()) + " " + c[3];
        if (worse > margin) {
          fail("bench " + scenario + ": " + score + " is worse than the baseline " + c[2] + " " + c[3] + " beyond "
              + Math.round(unit.tolerance() * 100) + " %");
          if (now.benchmark() != null) {
            rerun.add(now.benchmark());
          }
        } else if (-worse > margin) {
          System.out.println("GAIN " + scenario + " " + c[2] + " -> " + score);
          rows.put(scenario, String.join("\t", scenario, MACHINE, format(now.score()), c[3]));
        } else {
          System.out.println("ok   " + scenario + " " + score + " (baseline " + c[2] + ")");
        }
      }
    }
    for (Map.Entry<String, Result> e : results.entrySet()) {
      String scenario = e.getKey();
      Result now = e.getValue();
      if (!rows.containsKey(scenario)) {
        System.out.println("NEW  " + scenario + " " + format(now.score()) + " " + now.unit());
        rows.put(scenario, String.join("\t", scenario, MACHINE, format(now.score()), now.unit()));
      }
      for (Target t : TARGETS) {
        if (!t.scenario().matcher(scenario).matches()) {
          continue;
        }
        if (!t.unit().equals(now.unit())) {
          fail("bench " + scenario + ": unit " + now.unit() + ", the target is in " + t.unit());
        } else if (!t.met(now.score())) {
          fail("bench " + scenario + ": " + format(now.score()) + " " + now.unit() + " misses the target "
              + t.op().text + " " + format(t.value()) + " " + t.unit());
          if (now.benchmark() != null) {
            rerun.add(now.benchmark());
          }
        }
      }
    }
    if (!rerun.isEmpty()) {
      List<String> names = rerun.stream().map(n -> n.replace(".", "\\.")).toList();
      System.out.println("RERUN ^(?:" + String.join("|", names) + ")$");
    }
    return new ArrayList<>(rows.values());
  }

  /**
   * Per scenario: in three or more files the median of their scores (the first run and two re-runs); in one or two
   * files the later file's result (a re-run replaces the first run).
   */
  private static Map<String, Result> readBenchResults(List<Path> files) throws IOException {
    Map<String, List<Result>> runs = new TreeMap<>();
    for (Path file : files) {
      Object json = new Json(Files.readString(file)).parse();
      if (json instanceof List<?> jmh) {
        for (Object r : jmh) {
          readJmhResult(asMap(r), runs);
        }
      } else {
        Map<String, Object> tree = asMap(json);
        add(
            runs,
            (String) tree.get("scenario"),
            new Result(((Number) tree.get("score")).doubleValue(), (String) tree.get("unit"), null));
      }
    }
    Map<String, Result> results = new TreeMap<>();
    runs.forEach(
        (scenario, list) -> {
          Result last = list.getLast();
          results.put(
              scenario,
              list.size() < 3
                  ? last
                  : new Result(median(list.stream().map(Result::score).toList()), last.unit(), last.benchmark()));
        });
    return results;
  }

  private static void add(Map<String, List<Result>> runs, String scenario, Result result) {
    runs.computeIfAbsent(scenario, s -> new ArrayList<>()).add(result);
  }

  private static void readJmhResult(Map<String, Object> r, Map<String, List<Result>> results) {
    String name = (String) r.get("benchmark");
    Matcher m = BENCHMARK.matcher(name);
    Naming naming = m.matches() ? SCENARIOS.get(m.group(2)) : null;
    if (naming == null) {
      fail("bench " + name + ": no scenario name (SCENARIOS in scripts/ratchet.java)");
      return;
    }
    String mapping = Character.toLowerCase(m.group(1).charAt(0)) + m.group(1).substring(1);
    String scenario = naming.primary().formatted(mapping);
    Map<String, Object> primary = asMap(r.get("primaryMetric"));
    double score = medianOfForkMeans(primary.get("rawData"));
    String unit = (String) primary.get("scoreUnit");
    switch (naming.kind()) {
      case COMPLETES -> {
        System.out.println("ok   " + scenario + " completed");
        return;
      }
      case THROUGHPUT -> {
        Double seconds = SECONDS_PER_OP.get(unit);
        Object params = r.get("params"); // JMH 1.37 writes "params" only for a benchmark with a @Param
        Object bytes = params == null ? null : asMap(params).get("inputBytes");
        if (seconds == null) {
          fail("bench " + name + ": unit " + unit + " is not a time per operation");
          return;
        }
        if (bytes == null) {
          fail("bench " + name + ": no inputBytes param");
          return;
        }
        score = Long.parseLong((String) bytes) / MB / (score * seconds);
        unit = "MB/s";
      }
      case SCORE -> {}
    }
    add(results, scenario, new Result(score, unit, name));
    // JMH 1.37 always writes "secondaryMetrics"; gc.alloc.rate.norm is there with -prof gc
    Object gc = asMap(r.get("secondaryMetrics")).get("gc.alloc.rate.norm");
    if (naming.alloc() != null && gc != null) {
      add(
          results,
          naming.alloc().formatted(mapping),
          new Result(medianOfForkMeans(asMap(gc).get("rawData")), (String) asMap(gc).get("scoreUnit"), name));
    }
  }

  /** rawData holds one array of iteration scores per fork. */
  private static double medianOfForkMeans(Object rawData) {
    List<Double> means = new ArrayList<>();
    for (Object fork : (List<?>) rawData) {
      double sum = 0;
      for (Object v : (List<?>) fork) {
        sum += ((Number) v).doubleValue();
      }
      means.add(sum / ((List<?>) fork).size());
    }
    return median(means);
  }

  private static double median(List<Double> values) {
    List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int n = sorted.size();
    return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
  }

  /** Four significant digits, no exponent, no trailing zeros. */
  private static String format(double score) {
    return new BigDecimal(score).round(new MathContext(4)).stripTrailingZeros().toPlainString();
  }

  // ---- helpers ----

  private static void compare(String what, int listed, int now) {
    if (now < listed) {
      fail(what + ": " + now + " % is below the ratchet's " + listed + " %");
    } else if (now > listed) {
      System.out.println("RISE " + what + ": " + listed + " % -> " + now + " %");
    }
  }

  private static void notRun(String what, boolean regen) {
    if (regen) {
      fail("regen: no report for " + what);
    } else {
      System.out.println("NOT_RUN " + what);
    }
  }

  private static void fail(String message) {
    failures++;
    System.out.println("FAIL " + message);
  }

  private static List<String> readLines(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return new ArrayList<>();
    }
    List<String> lines = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        lines.add(line);
      }
    }
    return lines;
  }

  private static org.w3c.dom.Document parse(Path xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory.newDocumentBuilder().parse(xml.toFile());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (Map<String, Object>) o;
  }

  private static long number(Object o) {
    return ((Number) o).longValue();
  }

  /** Minimal JSON reader: objects, arrays, strings, numbers, true/false/null. */
  private static final class Json {
    private final String s;
    private int i;

    Json(String s) {
      this.s = s;
    }

    Object parse() {
      Object v = value();
      ws();
      if (i != s.length()) {
        throw new IllegalStateException("trailing JSON at " + i);
      }
      return v;
    }

    private void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }

    private Object value() {
      ws();
      char c = s.charAt(i);
      if (c == '{') {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (s.charAt(i) == '}') {
          i++;
          return m;
        }
        while (true) {
          ws();
          String k = string();
          ws();
          expect(':');
          m.put(k, value());
          ws();
          if (s.charAt(i) == ',') {
            i++;
          } else {
            expect('}');
            return m;
          }
        }
      }
      if (c == '[') {
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (s.charAt(i) == ']') {
          i++;
          return l;
        }
        while (true) {
          l.add(value());
          ws();
          if (s.charAt(i) == ',') {
            i++;
          } else {
            expect(']');
            return l;
          }
        }
      }
      if (c == '"') {
        return string();
      }
      if (s.startsWith("true", i)) {
        i += 4;
        return Boolean.TRUE;
      }
      if (s.startsWith("false", i)) {
        i += 5;
        return Boolean.FALSE;
      }
      if (s.startsWith("null", i)) {
        i += 4;
        return null;
      }
      int start = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
      return Double.valueOf(s.substring(start, i));
    }

    private String string() {
      expect('"');
      StringBuilder b = new StringBuilder();
      while (s.charAt(i) != '"') {
        char c = s.charAt(i++);
        if (c == '\\') {
          char n = s.charAt(i++);
          switch (n) {
            case 'n' -> b.append('\n');
            case 't' -> b.append('\t');
            case 'r' -> b.append('\r');
            case 'b' -> b.append('\b');
            case 'f' -> b.append('\f');
            case 'u' -> {
              b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
              i += 4;
            }
            default -> b.append(n);
          }
        } else {
          b.append(c);
        }
      }
      i++;
      return b.toString();
    }

    private void expect(char c) {
      if (s.charAt(i) != c) {
        throw new IllegalStateException("expected " + c + " at " + i);
      }
      i++;
    }
  }
}
