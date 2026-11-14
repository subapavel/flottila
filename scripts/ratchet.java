// The ratchet (ADR-0020, ADR-0014): what passes, per oracle and per module. Single-file source launcher, no
// dependencies:
//
//   java scripts/ratchet.java check                  CI build: exit 1 if a ratcheted case or percentage fails
//   java scripts/ratchet.java check --require-compile  ticket gate: check, and the compile report is required (F-46)
//   java scripts/ratchet.java regen --oracles        after every merge: the oracle pass lists only
//   java scripts/ratchet.java regen --all            spec gate: every report required, no drop allowed
//   java scripts/ratchet.java floor-mutation <m>...  PIT score of the modules against quality/floors.tsv
//   java scripts/ratchet.java drop compile <file>    an owner-approved drop of the listed compile.txt ids (F-46)
//
// The compile-mode oracle runs only under -Pcompile-oracle: in the ticket gate since #89 (F-46), after a merge and in
// the spec gate, not in the CI build. check prints it NOT_RUN when it wrote no report, unless --require-compile is
// given; then each listed id without a report row fails like any other oracle's. regen refuses any report row that is
// a Disagreement (F-29, ADR-0020 amendment for #60). drop removes only the ids its file lists, each only while its
// compile report row is FAIL stub:, and writes nothing if any listed id is not.
//
// Environment: RATCHET_ROOT (default: the current directory), RATCHET_JMH (JMH json result, default
// bench/jmh/target/jmh-result.json), RATCHET_MACHINE (the machine label of bench.tsv lines).
// Exit codes: 0 ok, 1 a failure or a refused drop, 2 usage. Format: ratchet/README.md.

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ratchet {

  /** Where a column of a pass-list line comes from: the id, the report file's suffix, or report column n. */
  record Col(String name, int reportColumn) {
    static final int ID = -1;
    static final int SUFFIX = -2;
  }

  record Oracle(String name, List<Col> cols) {
    boolean hasSuffix() {
      return cols.stream().anyMatch(c -> c.reportColumn() == Col.SUFFIX);
    }
  }

  static final List<Oracle> ORACLES =
      List.of(
          oracle("jqtest", "id", "backend"),
          oracle("differential", "id", "def:3", "backend"),
          oracle("compile", "id", "def:3", "mode"),
          oracle("backend", "id", "def:3"),
          oracle("json", "id", "label:3", "def:4"));

  static Oracle oracle(String name, String... cols) {
    List<Col> list = new ArrayList<>();
    for (String c : cols) {
      if (c.equals("id")) list.add(new Col("id", Col.ID));
      else if (c.equals("backend") || c.equals("mode")) list.add(new Col(c, Col.SUFFIX));
      else list.add(new Col(c.substring(0, c.indexOf(':')), Integer.parseInt(c.substring(c.indexOf(':') + 1))));
    }
    return new Oracle(name, list);
  }

  /** A row of an oracle report; extras are the report columns from the fourth on. */
  record Row(String verdict, String[] cells) {}

  record Floor(String module, boolean classC, Integer lines, Integer branches, Integer mutation) {}

  static final Path root = Path.of(System.getenv().getOrDefault("RATCHET_ROOT", ".")).toAbsolutePath();
  static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws IOException {
    String cmd = args.length == 0 ? "" : args[0];
    int rc;
    switch (cmd) {
      case "check" -> rc = args.length == 1 ? check(false)
          : args.length == 2 && args[1].equals("--require-compile") ? check(true) : usage();
      case "regen" -> rc = args.length == 2 && args[1].equals("--oracles") ? regen(false)
          : args.length == 2 && args[1].equals("--all") ? regen(true) : usage();
      case "floor-mutation" -> rc = args.length >= 2 ? floorMutation(List.of(args).subList(1, args.length)) : usage();
      case "drop" -> rc = args.length == 3 && args[1].equals("compile") ? drop(root.resolve(args[2])) : usage();
      default -> rc = usage();
    }
    System.exit(rc);
  }

  static int usage() {
    System.err.println(
        "usage: java scripts/ratchet.java check [--require-compile] | regen --oracles | regen --all | floor-mutation <module>... | drop compile <file>");
    return 2;
  }

  static void fail(String message) {
    failures.add(message);
    System.out.println("FAIL " + message);
  }

  static int finish(String what) {
    if (failures.isEmpty()) {
      System.out.println(what + ": ok");
      return 0;
    }
    System.out.println(what + ": " + failures.size() + " failure(s)");
    return 1;
  }

  // ---------------------------------------------------------------- check

  /**
   * The oracles that run only under a Maven profile (compile-oracle: the ticket gate since #89, after a merge, the spec
   * gate; not the CI build): check reports them NOT_RUN when they wrote no report (ADR-0020 amendment for #60), unless
   * the caller requires their report (F-46).
   */
  static final Set<String> ONLY_UNDER_PROFILE = Set.of("compile");

  /** The detail prefix of the compile-mode oracle's Disagreement, which regen refuses (F-29). */
  static final String DISAGREE = "disagree:";

  static int check(boolean requireCompile) throws IOException {
    for (Oracle o : ORACLES) {
      if (!requireCompile && ONLY_UNDER_PROFILE.contains(o.name()) && reportFiles(o).isEmpty()) {
        System.out.println("NOT_RUN " + o.name() + ": no report");
      } else {
        checkOracle(o);
      }
    }
    List<Floor> floors = floors();
    Map<String, int[]> cov = coverageOf(floors);
    checkCoverage(floors, cov);
    checkMutation(floors);
    checkBench();
    return finish("check");
  }

  // --- oracles

  /** The report files {@code <oracle>-<suffix>.tsv} of an oracle, sorted. */
  static List<Path> reportFiles(Oracle o) throws IOException {
    Path dir = root.resolve("oracle/target/oracle-reports");
    if (!Files.isDirectory(dir)) return List.of();
    try (var files = Files.list(dir)) {
      return files.filter(f -> isReportOf(o, f.getFileName().toString())).sorted().toList();
    }
  }

  static boolean isReportOf(Oracle o, String fileName) {
    return fileName.startsWith(o.name() + "-") && fileName.endsWith(".tsv");
  }

  static Map<String, Row> reports(Oracle o) throws IOException {
    Map<String, Row> rows = new LinkedHashMap<>();
    for (Path f : reportFiles(o)) {
      String n = f.getFileName().toString();
      String suffix = n.substring(o.name().length() + 1, n.length() - 4);
      for (String line : lines(f)) {
        if (line.isEmpty()) continue;
        String[] c = line.split("\t", -1);
        if (c.length < 2) continue;
        rows.put(o.hasSuffix() ? c[0] + "\t" + suffix : c[0], new Row(c[1], c));
      }
    }
    return rows;
  }

  static List<String> lines(Path f) throws IOException {
    String s = Files.readString(f, StandardCharsets.UTF_8);
    List<String> out = new ArrayList<>();
    for (String l : s.split("\n", -1)) out.add(l);
    if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
    return out;
  }

  static String sha256(Path f) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The ratcheted lines of an oracle: header lines and entries. */
  record PassList(List<String> headers, List<String> entries) {}

  static PassList passList(Oracle o) throws IOException {
    Path f = root.resolve("ratchet/oracle/" + o.name() + ".txt");
    List<String> headers = new ArrayList<>(), entries = new ArrayList<>();
    if (Files.exists(f)) {
      for (String l : lines(f)) {
        if (l.startsWith("#")) headers.add(l);
        else if (!l.isEmpty()) entries.add(l);
      }
    }
    return new PassList(headers, entries);
  }

  /** True if the entry's source file is among the headers and its sha now differs from the header's. */
  static boolean retirable(String id, List<String> headers) throws IOException {
    for (String h : headers) {
      String[] t = h.split(" ");
      if (t.length >= 5 && t[1].equals("source")) {
        Path p = root.resolve(t[2]);
        String base = p.getFileName().toString();
        if (id.startsWith(base + "#") && Files.exists(p) && !sha256(p).equals(t[t.length - 1])) return true;
      }
    }
    return false;
  }

  /** The report key of a pass-list entry's columns: its id, and its suffix column if the oracle has one. */
  static String keyOf(Oracle o, String[] c) {
    String key = c[0];
    for (int i = 0; i < o.cols().size(); i++) {
      if (o.cols().get(i).reportColumn() == Col.SUFFIX) key = c[0] + "\t" + c[i];
    }
    return key;
  }

  /** Checks one pass list; returns the entries that survive (not retired). */
  static List<String> checkOracle(Oracle o) throws IOException {
    PassList pl = passList(o);
    Map<String, Row> rep = reports(o);
    List<String> survivors = new ArrayList<>();
    for (String entry : pl.entries()) {
      String[] c = entry.split("\t", -1);
      String id = c[0];
      String key = keyOf(o, c);
      Row r = rep.get(key);
      if (r == null) {
        if (retirable(id, pl.headers())) {
          System.out.println("warning " + o.name() + ": " + id + " retired, its source file changed");
        } else {
          survivors.add(entry);
          fail(o.name() + ": " + key.replace('\t', ' ') + " is missing from the report");
        }
        continue;
      }
      survivors.add(entry);
      if (!r.verdict().equals("PASS")) {
        fail(o.name() + ": " + key.replace('\t', ' ') + " is " + r.verdict());
        continue;
      }
      for (int i = 0; i < o.cols().size() && i < c.length; i++) {
        Col col = o.cols().get(i);
        if (col.reportColumn() < 0) continue;
        String now = col.reportColumn() < r.cells().length ? r.cells()[col.reportColumn()] : "";
        if (!now.equals(c[i])) fail(o.name() + ": " + id + " " + col.name() + " changed (" + c[i] + " -> " + now + ")");
      }
    }
    return survivors;
  }

  // --- floors, coverage

  static List<Floor> floors() throws IOException {
    List<Floor> out = new ArrayList<>();
    Path f = root.resolve("quality/floors.tsv");
    if (!Files.exists(f)) return out;
    List<String> ls = lines(f);
    for (String l : ls.subList(Math.min(1, ls.size()), ls.size())) {
      String[] c = l.split("\t", -1);
      if (c.length < 5) continue;
      out.add(new Floor(c[0], c[1].equals("C"), num(c[2]), num(c[3]), num(c[4])));
    }
    return out;
  }

  static Integer num(String s) {
    return s.equals("-") || s.isEmpty() ? null : Integer.valueOf(s);
  }

  static int pct(long covered, long missed) {
    long total = covered + missed;
    return total == 0 ? 100 : (int) (100 * covered / total);
  }

  /** Lines % and branches % per module, from JaCoCo's csv; a module without a report is absent. */
  static Map<String, int[]> coverageOf(List<Floor> floors) throws IOException {
    Map<String, int[]> out = new TreeMap<>();
    Map<String, Boolean> classC = new TreeMap<>();
    for (Floor f : floors) classC.put(f.module(), f.classC());
    for (String m : table("coverage.tsv").keySet()) classC.putIfAbsent(m, false);
    for (var e : classC.entrySet()) {
      String m = e.getKey();
      // Class-C modules are measured by every test in the reactor: oracle's aggregate report (ADR-0014).
      Path csv = e.getValue() ? root.resolve("oracle/target/site/jacoco-aggregate/jacoco.csv")
          : root.resolve(m + "/target/site/jacoco/jacoco.csv");
      if (!Files.exists(csv)) continue;
      List<String> ls = lines(csv);
      List<String> head = List.of(ls.get(0).split(",", -1));
      int g = head.indexOf("GROUP"), bm = head.indexOf("BRANCH_MISSED"), bc = head.indexOf("BRANCH_COVERED"),
          lm = head.indexOf("LINE_MISSED"), lc = head.indexOf("LINE_COVERED");
      long[] s = new long[4];
      boolean any = false;
      for (String l : ls.subList(1, ls.size())) {
        String[] c = l.split(",", -1);
        if (e.getValue() && !c[g].equals(m) && !c[g].endsWith("/" + m)) continue;
        any = true;
        s[0] += Long.parseLong(c[lm]);
        s[1] += Long.parseLong(c[lc]);
        s[2] += Long.parseLong(c[bm]);
        s[3] += Long.parseLong(c[bc]);
      }
      if (any) out.put(m, new int[] {pct(s[1], s[0]), pct(s[3], s[2])});
    }
    return out;
  }

  /** A two-or-more column table file of ratchet/ keyed by its first column, header dropped. */
  static Map<String, String[]> table(String name) throws IOException {
    Map<String, String[]> out = new TreeMap<>();
    Path f = root.resolve("ratchet/" + name);
    if (!Files.exists(f)) return out;
    List<String> ls = lines(f);
    for (String l : ls.subList(Math.min(1, ls.size()), ls.size())) {
      if (l.isEmpty()) continue;
      String[] c = l.split("\t", -1);
      out.put(name.equals("bench.tsv") ? c[0] + "\t" + c[1] : c[0], c);
    }
    return out;
  }

  static void checkCoverage(List<Floor> floors, Map<String, int[]> cov) throws IOException {
    Map<String, String[]> old = table("coverage.tsv");
    TreeSet<String> modules = new TreeSet<>(old.keySet());
    for (Floor f : floors) modules.add(f.module());
    for (String m : modules) {
      int[] a = cov.get(m);
      if (a == null) {
        System.out.println("NOT_RUN coverage " + m + ": no JaCoCo report");
        continue;
      }
      Floor f = floors.stream().filter(x -> x.module().equals(m)).findFirst().orElse(null);
      String[] o = old.get(m);
      compare("coverage " + m + " lines", a[0], f == null ? null : f.lines(), o == null ? null : Integer.valueOf(o[1]));
      compare("coverage " + m + " branches", a[1], f == null ? null : f.branches(), o == null ? null : Integer.valueOf(o[2]));
    }
  }

  static void compare(String what, int actual, Integer floor, Integer ratcheted) {
    if (floor != null && actual < floor) fail(what + " " + actual + " % is below the floor " + floor + " %");
    if (ratcheted != null && actual < ratcheted) fail(what + " " + actual + " % is below the ratchet " + ratcheted + " %");
  }

  // --- mutation

  /** A mutation element: its attributes and, unless it is self-closing, its body. */
  static final Pattern MUTATION = Pattern.compile("<mutation\\b([^>]*?)(?:/>|>(.*?)</mutation>)", Pattern.DOTALL);
  static final Pattern STATUS = Pattern.compile("status='([A-Z_]+)'");
  /** The fields that identify a mutant across PIT reports of the same classes. */
  static final List<String> MUTANT_ID = List.of("mutatedClass", "mutatedMethod", "methodDescription", "lineNumber", "mutator", "indexes");
  /**
   * The crossModule runs: every test of oracle, and of jq-cli, against the classes of the modules it depends on
   * (ADR-0014, #47).
   */
  static final List<String> CROSS_MODULE_PIT =
      List.of("oracle/target/pit-reports/mutations.xml", "jq-cli/target/pit-reports/mutations.xml");

  record Mutant(String id, boolean killed) {}

  /** The mutants of a PIT report. Killed means KILLED, TIMED_OUT or MEMORY_ERROR. */
  static List<Mutant> mutants(Path f) throws IOException {
    List<Mutant> out = new ArrayList<>();
    Matcher m = MUTATION.matcher(Files.readString(f, StandardCharsets.UTF_8));
    while (m.find()) {
      Matcher s = STATUS.matcher(m.group(1));
      boolean killed = s.find() && List.of("KILLED", "TIMED_OUT", "MEMORY_ERROR").contains(s.group(1));
      String body = m.group(2) == null ? "" : m.group(2);
      StringBuilder id = new StringBuilder();
      for (String field : MUTANT_ID) {
        int from = body.indexOf("<" + field + ">");
        id.append(from < 0 ? "" : body.substring(from + field.length() + 2, body.indexOf("</" + field + ">", from))).append('\t');
      }
      out.add(new Mutant(id.toString(), killed));
    }
    return out;
  }

  /**
   * {survivors, generated} of a module, or null without its own PIT report. The mutants are those of its own report; one
   * counts as killed if its own report or any crossModule report kills it (ADR-0014).
   */
  static int[] pit(String module) throws IOException {
    Path f = root.resolve(module + "/target/pit-reports/mutations.xml");
    if (!Files.exists(f)) return null;
    Set<String> killedCrossModule = new HashSet<>();
    for (String report : CROSS_MODULE_PIT) {
      Path cross = root.resolve(report);
      if (Files.exists(cross)) for (Mutant c : mutants(cross)) if (c.killed()) killedCrossModule.add(c.id());
    }
    int generated = 0, killed = 0;
    for (Mutant own : mutants(f)) {
      generated++;
      if (own.killed() || killedCrossModule.contains(own.id())) killed++;
    }
    return new int[] {generated - killed, generated};
  }

  static int score(int[] p) {
    return pct(p[1] - p[0], p[0]);
  }

  static void checkMutation(List<Floor> floors) throws IOException {
    Map<String, String[]> old = table("mutation.tsv");
    TreeSet<String> modules = new TreeSet<>(old.keySet());
    for (Floor f : floors) if (f.classC()) modules.add(f.module());
    for (String m : modules) {
      int[] p = pit(m);
      if (p == null) {
        System.out.println("NOT_RUN mutation " + m + ": no PIT report");
        continue;
      }
      Floor f = floors.stream().filter(x -> x.module().equals(m)).findFirst().orElse(null);
      String[] o = old.get(m);
      compare("mutation " + m + " score", score(p), f == null ? null : f.mutation(),
          o == null ? null : Integer.valueOf(pct(Long.parseLong(o[2]) - Long.parseLong(o[1]), Long.parseLong(o[1]))));
    }
  }

  static int floorMutation(List<String> modules) throws IOException {
    List<Floor> floors = floors();
    for (String m : modules) {
      int[] p = pit(m);
      if (p == null) {
        System.out.println("NOT_RUN mutation " + m + ": no PIT report");
        continue;
      }
      Floor f = floors.stream().filter(x -> x.module().equals(m)).findFirst().orElse(null);
      System.out.println("mutation " + m + ": " + score(p) + " % (" + p[0] + " surviving of " + p[1] + ")");
      compare("mutation " + m + " score", score(p), f == null ? null : f.mutation(), null);
    }
    return finish("floor-mutation");
  }

  // --- bench

  /** A JMH result; score is null when JMH gave no finite number. */
  record Bench(String scenario, BigDecimal score, String unit) {}

  static String noScore(Bench b) {
    return "bench " + b.scenario() + " has no finite score (NaN or INF)";
  }

  static List<Bench> jmh() throws IOException {
    Path f = Path.of(System.getenv().getOrDefault("RATCHET_JMH", root.resolve("bench/jmh/target/jmh-result.json").toString()));
    if (!Files.exists(f)) return null;
    Object doc = new Json(Files.readString(f, StandardCharsets.UTF_8)).value();
    List<Bench> out = new ArrayList<>();
    for (Object o : (List<?>) doc) {
      Map<?, ?> m = (Map<?, ?>) o;
      String name = (String) m.get("benchmark");
      // The method name is the ADR-0024 id with each '.' written '_' (G6_compact_10m is G6.compact.10m).
      name = name.substring(name.lastIndexOf('.') + 1).replace('_', '.');
      if (m.get("params") instanceof Map<?, ?> ps) {
        StringBuilder b = new StringBuilder(name);
        new TreeMap<>(ps).forEach((k, v) -> b.append('/').append(k).append('=').append(v));
        name = b.toString();
      }
      Map<?, ?> pm = (Map<?, ?>) m.get("primaryMetric");
      // JMH 1.37 writes a non-finite score as a string ("NaN", "+INF", "-INF"): no score.
      out.add(new Bench(name, pm.get("score") instanceof BigDecimal s ? s : null, (String) pm.get("scoreUnit")));
    }
    return out;
  }

  static String machine() {
    return System.getenv("RATCHET_MACHINE");
  }

  static String plain(BigDecimal b) {
    BigDecimal r = b.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    return r.scale() < 0 ? r.setScale(0).toPlainString() : r.toPlainString();
  }

  /** A line with an empty tolerance% is skipped (ADR-0019, ADR-0023). Lower is better: the score is jqj's time. */
  static void checkBench() throws IOException {
    Map<String, String[]> old = table("bench.tsv");
    List<Bench> now = null;
    for (String[] line : old.values()) {
      if (line.length < 5 || line[4].isEmpty()) continue;
      if (now == null) now = jmh();
      if (now == null || !line[1].equals(machine())) {
        System.out.println("NOT_RUN bench " + line[0] + " on " + line[1] + ": no result for that machine");
        continue;
      }
      boolean found = false;
      for (Bench b : now) {
        if (!b.scenario().equals(line[0])) continue;
        found = true;
        if (b.score() == null) {
          fail(noScore(b));
          continue;
        }
        BigDecimal limit = new BigDecimal(line[2]).multiply(BigDecimal.ONE.add(new BigDecimal(line[4]).movePointLeft(2)));
        if (b.score().compareTo(limit) > 0) {
          fail("bench " + line[0] + " " + plain(b.score()) + " " + b.unit() + " is slower than " + line[2] + " by more than " + line[4] + " %");
        }
      }
      if (!found) fail("bench " + line[0] + " is missing from the JMH result of " + line[1]);
    }
  }

  // ---------------------------------------------------------------- regen

  static int regen(boolean all) throws IOException {
    Map<String, List<String>> writes = new TreeMap<>();
    List<String> report = new ArrayList<>();

    for (Oracle o : ORACLES) {
      List<String> survivors = checkOracle(o);
      Map<String, Row> rep = reports(o);
      PassList pl = passList(o);
      if (pl.entries().isEmpty() && rep.isEmpty()) continue;
      TreeSet<String> body = new TreeSet<>(survivors);
      int before = body.size();
      for (var e : rep.entrySet()) {
        String[] cells = e.getValue().cells();
        if (cells.length > 2 && cells[2].startsWith(DISAGREE)) {
          fail(o.name() + ": " + e.getKey().replace('\t', ' ') + " disagrees between the optimised and that compile (" + cells[2] + ")");
          continue;
        }
        if (!e.getValue().verdict().equals("PASS")) continue;
        body.add(entryOf(o, e.getKey(), e.getValue()));
      }
      List<String> out = new ArrayList<>();
      for (String h : pl.headers()) out.add(refreshHeader(h));
      out.addAll(body);
      writes.put("oracle/" + o.name() + ".txt", out);
      report.add(o.name() + ": +" + (body.size() - before) + " -" + (pl.entries().size() - survivors.size()));
    }

    if (all) {
      List<Floor> floors = floors();
      Map<String, int[]> cov = coverageOf(floors);
      Map<String, String[]> oldCov = table("coverage.tsv");
      List<String> covOut = new ArrayList<>(List.of("module\tlines %\tbranches %"));
      TreeSet<String> covBody = new TreeSet<>();
      for (Floor f : floors) {
        int[] a = cov.get(f.module());
        if (a == null) {
          fail("coverage " + f.module() + ": no JaCoCo report; regen --all needs every report");
          continue;
        }
        String[] o = oldCov.get(f.module());
        compare("coverage " + f.module() + " lines", a[0], f.lines(), o == null ? null : Integer.valueOf(o[1]));
        compare("coverage " + f.module() + " branches", a[1], f.branches(), o == null ? null : Integer.valueOf(o[2]));
        covBody.add(f.module() + "\t" + a[0] + "\t" + a[1]);
        report.add("coverage " + f.module() + ": lines " + a[0] + " %, branches " + a[1] + " %");
      }
      covOut.addAll(covBody);
      writes.put("coverage.tsv", covOut);

      Map<String, String[]> oldMut = table("mutation.tsv");
      List<String> mutOut = new ArrayList<>(List.of("module\tsurviving mutants\tgenerated mutants"));
      TreeSet<String> mutBody = new TreeSet<>();
      for (Floor f : floors) {
        if (!f.classC()) continue;
        int[] p = pit(f.module());
        if (p == null) {
          fail("mutation " + f.module() + ": no PIT report; regen --all needs every report");
          continue;
        }
        String[] o = oldMut.get(f.module());
        compare("mutation " + f.module() + " score", score(p), f.mutation(),
            o == null ? null : Integer.valueOf(pct(Long.parseLong(o[2]) - Long.parseLong(o[1]), Long.parseLong(o[1]))));
        mutBody.add(f.module() + "\t" + p[0] + "\t" + p[1]);
        report.add("mutation " + f.module() + ": " + p[0] + " surviving of " + p[1]);
      }
      mutOut.addAll(mutBody);
      writes.put("mutation.tsv", mutOut);

      List<Bench> now = jmh();
      if (now == null) fail("bench: no JMH result; regen --all needs every report");
      else if (machine() == null || machine().isEmpty()) fail("bench: RATCHET_MACHINE is not set");
      else {
        Map<String, String[]> oldBench = table("bench.tsv");
        Map<String, String> lines = new TreeMap<>();
        for (var e : oldBench.entrySet()) lines.put(e.getKey(), String.join("\t", e.getValue()));
        for (Bench b : now) {
          if (b.score() == null) {
            fail(noScore(b));
            continue;
          }
          String key = b.scenario() + "\t" + machine();
          String[] o = oldBench.get(key);
          String line = key + "\t" + plain(b.score()) + "\t" + b.unit() + "\t";
          if (o != null && o.length >= 5 && !o[4].isEmpty()) {
            BigDecimal tol = new BigDecimal(o[4]).movePointLeft(2), base = new BigDecimal(o[2]);
            if (b.score().compareTo(base.multiply(BigDecimal.ONE.add(tol))) > 0) {
              fail("bench " + b.scenario() + " " + plain(b.score()) + " is slower than " + o[2] + " by more than " + o[4] + " %");
              continue;
            }
            // The baseline moves only on a gain beyond its tolerance.
            line = b.score().compareTo(base.multiply(BigDecimal.ONE.subtract(tol))) < 0 ? line + o[4] : String.join("\t", o);
          }
          lines.put(key, line);
          report.add("bench " + b.scenario() + ": " + plain(b.score()) + " " + b.unit());
        }
        // A toleranced line of this machine whose scenario the result lacks fails, as in check.
        TreeSet<String> measured = new TreeSet<>();
        for (Bench b : now) measured.add(b.scenario());
        for (String[] o : oldBench.values()) {
          if (o.length >= 5 && !o[4].isEmpty() && o[1].equals(machine()) && !measured.contains(o[0])) {
            fail("bench " + o[0] + " is missing from the JMH result of " + o[1]);
          }
        }
        List<String> out = new ArrayList<>(List.of("scenario\tmachine\tscore\tunit\ttolerance%"));
        out.addAll(lines.values());
        writes.put("bench.tsv", out);
      }
    }

    if (!failures.isEmpty()) {
      System.out.println("regen refused: nothing written");
      return finish("regen");
    }
    for (var e : writes.entrySet()) {
      Path f = root.resolve("ratchet/" + e.getKey());
      Files.createDirectories(f.getParent());
      Files.writeString(f, e.getValue().isEmpty() ? "" : String.join("\n", e.getValue()) + "\n", StandardCharsets.UTF_8);
    }
    report.forEach(System.out::println);
    return finish("regen");
  }

  /** The pass-list line of a report row: the columns of the oracle's format, tab separated. */
  static String entryOf(Oracle o, String key, Row r) {
    String suffix = key.contains("\t") ? key.substring(key.indexOf('\t') + 1) : "";
    List<String> cells = new ArrayList<>();
    for (Col c : o.cols()) {
      cells.add(switch (c.reportColumn()) {
        case Col.ID -> r.cells()[0];
        case Col.SUFFIX -> suffix;
        default -> c.reportColumn() < r.cells().length ? r.cells()[c.reportColumn()] : "";
      });
    }
    return String.join("\t", cells);
  }

  /** A "# source <path> sha256 <hex>" header takes the file's current sha; other headers stay. */
  static String refreshHeader(String h) throws IOException {
    String[] t = h.split(" ");
    if (t.length >= 5 && t[1].equals("source") && Files.exists(root.resolve(t[2]))) {
      return "# source " + t[2] + " sha256 " + sha256(root.resolve(t[2]));
    }
    return h;
  }

  // ---------------------------------------------------------------- drop

  /** The first line of a drop file: the finding that records the owner's decision (F-46). */
  static final Pattern FINDING = Pattern.compile("# finding (F-[0-9]+)(?:[: ].*)?");

  /** The detail prefix of the compile-mode oracle's FAIL on a Stub, the only row drop accepts. */
  static final String STUB = "stub:";

  /**
   * Drops the compile.txt entries of the ids that {@code file} lists, one per line after its finding header. Refuses
   * the whole drop, writing nothing, if the header names no existing finding, or if a listed id is not in compile.txt
   * or any of its entries' report rows is not FAIL with a {@code stub:} detail. Every other line of compile.txt stays.
   */
  static int drop(Path file) throws IOException {
    Oracle o = ORACLES.stream().filter(x -> x.name().equals("compile")).findFirst().orElseThrow();
    List<String> lines = lines(file);
    Matcher h = FINDING.matcher(lines.isEmpty() ? "" : lines.get(0));
    List<String> dropped = new ArrayList<>();
    if (!h.matches()) {
      fail("drop: the first line of " + file.getFileName() + " must be '# finding F-<n>', the finding that records the owner's decision");
    } else if (!Files.exists(root.resolve("docs/findings/" + h.group(1) + ".md"))) {
      fail("drop: docs/findings/" + h.group(1) + ".md does not exist");
    } else {
      PassList pl = passList(o);
      Map<String, Row> rep = reports(o);
      for (String id : new LinkedHashSet<>(lines.subList(1, lines.size()))) {
        boolean listed = false;
        for (String entry : pl.entries()) {
          String[] c = entry.split("\t", -1);
          if (!c[0].equals(id)) continue;
          listed = true;
          String name = keyOf(o, c).replace('\t', ' ');
          Row r = rep.get(keyOf(o, c));
          if (r == null) fail(o.name() + ": " + name + " is missing from the report");
          else if (!r.verdict().equals("FAIL") || r.cells().length < 3 || !r.cells()[2].startsWith(STUB)) {
            fail(o.name() + ": " + name + " is " + r.verdict() + ", not FAIL " + STUB);
          } else dropped.add(entry);
        }
        if (!listed) fail(o.name() + ": " + id + " is not in " + o.name() + ".txt");
      }
    }
    if (!failures.isEmpty()) {
      System.out.println("drop refused: nothing written");
      return finish("drop");
    }
    Path f = root.resolve("ratchet/oracle/" + o.name() + ".txt");
    Set<String> gone = new HashSet<>(dropped);
    StringBuilder kept = new StringBuilder();
    for (String l : lines(f)) if (!gone.contains(l)) kept.append(l).append('\n');
    Files.writeString(f, kept.toString(), StandardCharsets.UTF_8);
    for (String entry : dropped) System.out.println("dropped " + o.name() + ": " + keyOf(o, entry.split("\t", -1)).replace('\t', ' '));
    System.out.println("drop " + o.name() + ": " + dropped.size() + " dropped (" + h.group(1) + ")");
    return finish("drop");
  }

  // ---------------------------------------------------------------- JSON (JMH results only)

  static final class Json {
    private final String s;
    private int i;

    Json(String s) {
      this.s = s;
    }

    Object value() {
      ws();
      char c = s.charAt(i);
      if (c == '{') {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        do {
          ws();
          String k = string();
          ws();
          i++; // ':'
          m.put(k, value());
          ws();
        } while (s.charAt(i++) == ',');
        return m;
      }
      if (c == '[') {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        do {
          l.add(value());
          ws();
        } while (s.charAt(i++) == ',');
        return l;
      }
      if (c == '"') return string();
      int start = i;
      while (i < s.length() && ",]} \n\r\t".indexOf(s.charAt(i)) < 0) i++;
      String lit = s.substring(start, i);
      return switch (lit) {
        case "true" -> Boolean.TRUE;
        case "false" -> Boolean.FALSE;
        case "null" -> "null";
        case "NaN" -> "NaN";
        default -> new BigDecimal(lit);
      };
    }

    private String string() {
      StringBuilder b = new StringBuilder();
      i++; // opening quote
      while (s.charAt(i) != '"') {
        char c = s.charAt(i++);
        if (c == '\\') {
          char e = s.charAt(i++);
          b.append(switch (e) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; case 'b' -> '\b'; case 'f' -> '\f'; default -> e; });
        } else b.append(c);
      }
      i++;
      return b.toString();
    }

    private void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
  }
}
