// The case census (#57, spec #56): every case of jq's test files gets the spec that makes it pass and, for spec 2a,
// the S2a ticket that completes it. Single-file source launcher, no dependencies:
//
//   java scripts/census.java write [<list>]   run jq-ref on every case and write the list
//   java scripts/census.java verify [<list>]  write the list again and compare it with the given or checked-in one
//   java scripts/census.java counts [<list>]  the cases per spec and per ticket
//   java scripts/census.java gate <issue>     spec gate: exit 1 if a case of the spec with that issue number is
//                                             missing from ratchet/oracle/jqtest.txt
//
// Environment: CENSUS_ROOT (default: the current directory), CENSUS_TESTS (default
// oracle/src/test/resources/jq/tests under the root), CENSUS_JQ (default /usr/local/bin/jq-ref).
// The list is oracle/src/test/resources/census/jqtest.tsv; its columns are in HEADER.
// Exit codes: 0 ok, 1 a difference, a missing case, an unknown spec or a case jq-ref does not compile, 2 usage.
//
// The cases are read by the jqtest oracle's own parser (JqTestFile, compiled from the oracle's sources), so the ids
// are the oracle's. Each case gives two sets of needs, and its spec is the latest spec among them (SPECS order):
//
// - the closure, from jq-ref --debug-dump-disasm of the program, run with no input, so the program never runs. The
//   walk starts at the main code and follows every call and every closure argument. A call to a native (NATIVES, by
//   name and arity; F-31) is not followed into its body. C builtins are in BUILTINS by name; one not listed is
//   spec 4's. A definition of builtin.jq that is reached is the prelude's (S2a.21; error/1 is S2a.18's, #68); the
//   builtin definitions are the leading run of builtin names under main (see closure). A cycle among the reached
//   definitions is recursion, spec 2b. $ENV is found by a marker variable in jq-ref's environment, a data import by
//   STORE_GLOBAL; both are spec 7's. The operator builtins are not needs of the closure: jq calls them for its own
//   lowering too (string interpolation calls _plus, label calls _equal), and the operators come from the tokens.
// - the tokens of the program (lex): the constructs of spec 2a by ticket, and the module directives (spec 7). jq
//   folds constants (1 + 2 is LOADK 3), so the constructs cannot come from the disassembly. A comma between an
//   object's pairs is construction's, any other the comma's; a '?' after a field, after '.' and a string, or after
//   the ']' of a postfix bracket is optional navigation, any other a try (jq's Term '?'; #64's carries, #75, #91).
//
// A case needing nothing beyond `.`, field access, `|` and parentheses is spec 1's. A spec-2a case's ticket is the
// earliest S2a ticket (TICKETS order) that is, or transitively follows in BLOCKED_BY, every ticket the case needs;
// when no ticket up to S2a.23 does, the census sweep S2a.25. A %%FAIL case is not compiled by jq-ref: with a module
// directive it is spec 7's; one whose expected message is a syntax error, a parser message (PARSER_MESSAGES) or not
// compared needs only the parser (S2a.12); otherwise the tickets of its tokens.

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
import java.util.stream.Stream;
import javax.tools.ToolProvider;

public class census {

  static final Path root = Path.of(System.getenv().getOrDefault("CENSUS_ROOT", ".")).toAbsolutePath().normalize();
  static final String LIST = "oracle/src/test/resources/census/jqtest.tsv";
  static final String ENV_MARK = "JQJ_CENSUS_ENV";

  /** The specs in build order (ADR-0023, docs/GATE.md), and the issue of each spec the gate can name. */
  static final List<String> SPECS = List.of("1", "2a", "2b", "3", "4", "5", "6", "7");
  static final Map<String, String> SPEC_ISSUES = Map.of("22", "1", "56", "2a");

  record Ticket(String name, int issue, String title) {}

  /** The S2a tickets a case can get, in numbering order; S2a.25 is the census sweep, which follows them all. */
  static final List<Ticket> TICKETS =
      List.of(
          new Ticket("S2a.11", 61, "Literals and the full lexer"),
          new Ticket("S2a.12", 62, "The full parser"),
          new Ticket("S2a.13", 63, "Comma and navigation"),
          new Ticket("S2a.14", 64, "Construction"),
          new Ticket("S2a.15", 65, "Arithmetic and computed numbers"),
          new Ticket("S2a.16", 66, "Comparison and sort order"),
          new Ticket("S2a.17", 67, "Variables and destructuring"),
          new Ticket("S2a.18", 68, "Control flow: if, try, error, label"),
          new Ticket("S2a.19", 69, "reduce, foreach and range/2"),
          new Ticket("S2a.20", 70, "Definitions and closures"),
          new Ticket("S2a.21", 71, "Prelude loader"),
          new Ticket("S2a.22", 72, "Language natives"),
          new Ticket("S2a.23", 73, "Sort and its kin"),
          new Ticket("S2a.25", 75, "Census sweep"));

  /**
   * The blocking graph: each ticket's direct blockers, from the "Blocked by" section of its issue body (2026-11-12).
   * S2a.10 is #59 and S2a.02 is #58; neither gets cases. A case's ticket is the earliest of TICKETS that is, or
   * transitively follows, every ticket the case needs.
   */
  static final Map<String, List<String>> BLOCKED_BY =
      Map.ofEntries(
          Map.entry("S2a.11", List.of("S2a.10")), // #61 <- #59
          Map.entry("S2a.12", List.of("S2a.11")), // #62 <- #61
          Map.entry("S2a.13", List.of("S2a.12")), // #63 <- #62
          // #64 <- #62; #63 added 2026-11-13 from the #64 design approval (C6: #64 merged after #63), carried to #75
          Map.entry("S2a.14", List.of("S2a.12", "S2a.13")),
          Map.entry("S2a.15", List.of("S2a.13", "S2a.14")), // #65 <- #63, #64
          Map.entry("S2a.16", List.of("S2a.14")), // #66 <- #64
          // #67 <- #63, #64; #65, #66 added 2026-11-12 from the census
          Map.entry("S2a.17", List.of("S2a.13", "S2a.14", "S2a.15", "S2a.16")),
          // #68 <- #63; #65, #66 added 2026-11-12 from the census
          Map.entry("S2a.18", List.of("S2a.13", "S2a.15", "S2a.16")),
          Map.entry("S2a.19", List.of("S2a.17")), // #69 <- #67
          Map.entry("S2a.20", List.of("S2a.17", "S2a.18")), // #70 <- #67, #68
          // #71 <- #70; #69 added 2026-11-13 from the #71 design approval (C2: add, limit, skip and range/1 need
          // reduce, foreach and range/2)
          Map.entry("S2a.21", List.of("S2a.19", "S2a.20")),
          // #72 <- #71; #69 added 2026-11-12 from the census
          Map.entry("S2a.22", List.of("S2a.19", "S2a.21")),
          Map.entry("S2a.23", List.of("S2a.16", "S2a.21")), // #73 <- #66, #71
          Map.entry("S2a.25", List.of("S2a.15", "S2a.16", "S2a.19", "S2a.22", "S2a.23", "S2a.02"))); // #75 <- #65, #66, #69, #72, #73, #58

  /** The ticket itself and every ticket it transitively follows. */
  static Set<String> follows(String ticket) {
    Set<String> out = new HashSet<>();
    Deque<String> todo = new ArrayDeque<>(List.of(ticket));
    while (!todo.isEmpty()) {
      String t = todo.pop();
      if (out.add(t)) todo.addAll(BLOCKED_BY.getOrDefault(t, List.of()));
    }
    return out;
  }

  /** What a case needs: a spec, the S2a ticket for spec 2a (null otherwise), and the item that says why. */
  record Need(String spec, String ticket, String item) {}

  static Need t(String ticket, String item) {
    return new Need("2a", ticket, item);
  }

  /** C builtins by name (jq's function_list). A name not here is spec 4's; format is decided by its argument. */
  static final Map<String, Need> BUILTINS = new HashMap<>();

  /** Natives jq writes as bytecode or in jq, by name and arity: their bodies are not followed (F-31). */
  static final Map<String, Need> NATIVES = new HashMap<>();

  /** The operator builtins: needs from the tokens, never from the closure. */
  static final Set<String> OPERATORS =
      Set.of("_plus", "_minus", "_multiply", "_divide", "_mod", "_negate", "_equal", "_notequal", "_less", "_greater",
          "_lesseq", "_greatereq");

  static {
    for (String n : List.of("length", "type", "tostring", "tojson", "tonumber", "has", "keys", "keys_unsorted", "nan",
        "infinite", "isnan", "isinfinite", "isnormal", "have_decnum", "have_literal_numbers")) {
      BUILTINS.put(n, t("S2a.22", n));
    }
    for (String n : List.of("sort", "unique", "min", "max", "_sort_by_impl", "_group_by_impl", "_unique_by_impl",
        "_min_by_impl", "_max_by_impl")) {
      BUILTINS.put(n, t("S2a.23", n));
    }
    BUILTINS.put("error", t("S2a.18", "error"));
    for (String n : List.of("getpath", "setpath", "delpaths")) BUILTINS.put(n, new Need("3", null, n));
    BUILTINS.put("input", new Need("5", null, "input"));
    for (String n : List.of("env", "halt", "halt_error", "get_search_list", "get_prog_origin", "get_jq_origin",
        "modulemeta", "debug", "stderr", "now", "input_filename", "input_line_number")) {
      BUILTINS.put(n, new Need("7", null, n));
    }
    for (String n : List.of("not/0", "last/1", "flatten/0", "flatten/1")) NATIVES.put(n, t("S2a.22", n));
    NATIVES.put("empty/0", t("S2a.14", "empty/0")); // built by #64 (#75)
    NATIVES.put("range/2", t("S2a.19", "range/2"));
    NATIVES.put("path/1", new Need("3", null, "path/1"));
    NATIVES.put("inputs/0", new Need("5", null, "inputs/0"));
    NATIVES.put("builtins/0", new Need("4", null, "builtins/0"));
  }

  /** The ticket of a reached builtin.jq definition other than the prelude loader. */
  static final Map<String, String> PRELUDE_OWNERS = Map.of("error/1", "S2a.18");

  /** The parser's messages among jq's %%FAIL cases: a case expecting one needs only the parser. */
  static final List<String> PARSER_MESSAGES =
      List.of("jq: error: syntax error", "May need parentheses around object key expression");

  static final String HEADER =
      """
      # The case census (#57): each case of jq's test files, the spec that makes it pass and, for spec 2a, the S2a
      # ticket that completes it. Written by `java scripts/census.java write`; never edited by hand.
      # Columns: id, spec, ticket, line (file:line of the case), tickets (every S2a ticket the case needs),
      # why (what decides the spec, or for spec 2a the needs of the tickets no other needed ticket follows).
      # Rules: the comment at the top of scripts/census.java.
      """;

  public static void main(String[] args) throws Exception {
    String cmd = args.length == 0 ? "" : args[0];
    int rc;
    try {
      rc = switch (cmd) {
        case "write" -> args.length <= 2 ? write(args.length == 2 ? Path.of(args[1]) : root.resolve(LIST)) : usage();
        case "verify" -> args.length <= 2 ? verify(args.length == 2 ? Path.of(args[1]) : root.resolve(LIST)) : usage();
        case "counts" -> args.length <= 2 ? counts(args.length == 2 ? Path.of(args[1]) : root.resolve(LIST)) : usage();
        case "gate" -> args.length == 2 ? gate(args[1]) : usage();
        default -> usage();
      };
    } catch (CensusException e) {
      System.err.println("census: " + e.getMessage());
      rc = 1;
    }
    System.exit(rc);
  }

  static int usage() {
    System.err.println("usage: java scripts/census.java write [<list>] | verify [<list>] | counts [<list>] | gate <issue>");
    return 2;
  }

  static final class CensusException extends RuntimeException {
    CensusException(String message) {
      super(message);
    }
  }

  // --- commands

  static int write(Path out) throws Exception {
    List<String> lines = census();
    Files.writeString(out, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    System.out.println("census: " + rows(lines).size() + " cases written to " + out);
    return 0;
  }

  static int verify(Path f) throws Exception {
    List<String> was = readList(f);
    List<String> now = census();
    if (now.equals(was)) {
      System.out.println("census: the list matches jq-ref (" + rows(now).size() + " cases)");
      return 0;
    }
    Set<String> a = new TreeSet<>(was), b = new TreeSet<>(now);
    for (String l : was) if (!b.contains(l)) System.out.println("- " + l);
    for (String l : now) if (!a.contains(l)) System.out.println("+ " + l);
    System.out.println("census: " + f + " differs from what jq-ref gives; run java scripts/census.java write");
    return 1;
  }

  static int counts(Path f) throws IOException {
    List<String[]> rows = rows(readList(f));
    Map<String, Integer> perSpec = new TreeMap<>(Comparator.comparingInt(SPECS::indexOf));
    Map<String, Integer> perTicket = new LinkedHashMap<>();
    for (Ticket t : TICKETS) perTicket.put(t.name(), 0);
    for (String[] r : rows) {
      perSpec.merge(r[1], 1, Integer::sum);
      if (r[1].equals("2a")) perTicket.merge(r[2], 1, Integer::sum);
    }
    for (var e : perSpec.entrySet()) System.out.println("spec " + e.getKey() + "\t" + e.getValue());
    for (Ticket t : TICKETS) {
      System.out.println("ticket " + t.name() + " #" + t.issue() + "\t" + perTicket.get(t.name()) + "\t" + t.title());
    }
    System.out.println("total " + rows.size());
    return 0;
  }

  static int gate(String issue) throws IOException {
    String spec = SPEC_ISSUES.get(issue);
    if (spec == null) {
      throw new CensusException("no census spec for #" + issue + ": add it to SPEC_ISSUES in scripts/census.java");
    }
    Set<String> ratcheted = new HashSet<>();
    for (String l : Files.readAllLines(root.resolve("ratchet/oracle/jqtest.txt"), StandardCharsets.UTF_8)) {
      ratcheted.add(l.split("\t", -1)[0]);
    }
    int cases = 0, missing = 0;
    for (String[] r : rows(readList(root.resolve(LIST)))) {
      if (!r[1].equals(spec)) continue;
      cases++;
      if (!ratcheted.contains(r[0])) {
        missing++;
        System.out.println("missing " + r[0] + "\t" + r[3] + "\t" + r[2]);
      }
    }
    System.out.println("census gate: spec " + spec + " (#" + issue + "): " + cases + " cases, " + missing
        + " missing from ratchet/oracle/jqtest.txt");
    return missing == 0 ? 0 : 1;
  }

  static List<String> readList(Path f) throws IOException {
    if (!Files.exists(f)) throw new CensusException("no census list " + f);
    return Files.readAllLines(f, StandardCharsets.UTF_8);
  }

  static List<String[]> rows(List<String> lines) {
    List<String[]> rows = new ArrayList<>();
    for (String l : lines) if (!l.startsWith("#")) rows.add(l.split("\t", -1));
    return rows;
  }

  // --- the census

  record Case(String file, String id, int line, String mode, String program, List<String> expected) {}

  static Path tests() {
    String t = System.getenv("CENSUS_TESTS");
    return t != null ? Path.of(t).toAbsolutePath() : root.resolve("oracle/src/test/resources/jq/tests");
  }

  static String jq() {
    return System.getenv().getOrDefault("CENSUS_JQ", "/usr/local/bin/jq-ref");
  }

  static List<String> census() throws Exception {
    Path dir = tests();
    List<Path> files;
    try (Stream<Path> s = Files.list(dir)) {
      files = s.filter(p -> p.getFileName().toString().endsWith(".test")).sorted().toList();
    }
    List<Case> cases = new ArrayList<>(readCases(files));
    cases.sort(Comparator.comparing(Case::id));
    Set<String> builtins = new HashSet<>(run(List.of(jq(), "-n", "-r", "builtins[]"), "builtins").lines().toList());
    List<String> out = new ArrayList<>(HEADER.lines().toList());
    for (Ticket t : TICKETS) out.add("# ticket " + t.name() + " #" + t.issue() + " " + t.title());
    for (Path f : files) out.add("# source " + f.getFileName() + " sha256 " + sha256(f));
    out.add("# jq-ref " + run(List.of(jq(), "--version"), "--version").strip() + " sha256 " + sha256(Path.of(jq())));
    out.addAll(cases.parallelStream().map(c -> classify(c, dir.resolve("modules"), builtins)).toList());
    return out;
  }

  /** The cases of every file, read by the jqtest oracle's JqTestFile, compiled here from its source. */
  @SuppressWarnings("unchecked")
  static List<Case> readCases(List<Path> files) throws Exception {
    Path src = root.resolve("oracle/src/main/java/io/github/subapavel/jqj/oracle/jqtest");
    Path classes = Files.createTempDirectory("census-classes");
    try {
      ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", classes.toString(),
          src.resolve("JqTestCase.java").toString(), src.resolve("JqTestFile.java").toString());
      List<Case> cases = new ArrayList<>();
      try (URLClassLoader cl = new URLClassLoader(new URL[] {classes.toUri().toURL()}, census.class.getClassLoader())) {
        String pkg = "io.github.subapavel.jqj.oracle.jqtest.";
        Method parse = cl.loadClass(pkg + "JqTestFile").getMethod("parse", String.class, String.class);
        Class<?> k = cl.loadClass(pkg + "JqTestCase");
        Method id = k.getMethod("id"), line = k.getMethod("line"), mode = k.getMethod("mode");
        Method program = k.getMethod("program"), expected = k.getMethod("expected");
        for (Path f : files) {
          String name = f.getFileName().toString();
          for (Object c : (List<Object>) parse.invoke(null, name, Files.readString(f, StandardCharsets.UTF_8))) {
            cases.add(new Case(name, (String) id.invoke(c), (int) line.invoke(c), mode.invoke(c).toString(),
                (String) program.invoke(c), (List<String>) expected.invoke(c)));
          }
        }
      }
      return cases;
    } finally {
      try (Stream<Path> s = Files.walk(classes)) {
        for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }

  /**
   * Runs jq-ref with no input, so a program is compiled and never run, and with only the $ENV marker in its
   * environment. Its stdout, or a CensusException.
   */
  static String run(List<String> cmd, String what) {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.environment().clear();
    pb.environment().put(ENV_MARK, "1");
    try {
      Process p = pb.start();
      p.getOutputStream().close();
      byte[] out = p.getInputStream().readAllBytes();
      byte[] err = p.getErrorStream().readAllBytes();
      if (p.waitFor() != 0) {
        throw new CensusException(what + ": jq-ref exited " + p.exitValue() + ": " + new String(err, StandardCharsets.UTF_8).strip());
      }
      return new String(out, StandardCharsets.UTF_8);
    } catch (IOException | InterruptedException e) {
      throw new CensusException(what + ": " + e);
    }
  }

  /** One line of the list. */
  static String classify(Case c, Path modules, Set<String> builtins) {
    Lexed lexed = lex(c.program());
    List<Need> needs;
    if (c.mode().equals("RESULTS")) {
      needs = new ArrayList<>(lexed.needs());
      String disasm = run(List.of(jq(), "--debug-dump-disasm", "-L", modules.toString(), "--", c.program()),
          c.file() + ":" + c.line() + " " + c.id());
      closure(parse(disasm), lexed.defs(), builtins, needs);
    } else {
      needs = failNeeds(c, lexed);
    }
    return row(c, needs);
  }

  static List<Need> failNeeds(Case c, Lexed lexed) {
    List<Need> module = lexed.needs().stream().filter(n -> n.spec().equals("7")).toList();
    if (!module.isEmpty()) return module;
    String message = String.join("\n", c.expected());
    boolean parser = c.mode().equals("FAIL_IGNORE_MSG") || PARSER_MESSAGES.stream().anyMatch(message::contains);
    return parser ? List.of(t("S2a.12", "parser")) : lexed.needs();
  }

  static String row(Case c, List<Need> needs) {
    String spec = "1";
    for (Need n : needs) if (SPECS.indexOf(n.spec()) > SPECS.indexOf(spec)) spec = n.spec();
    String ticket = "-", tickets = "-";
    Set<String> why = new TreeSet<>();
    if (spec.equals("2a")) {
      TreeSet<String> all = new TreeSet<>(Comparator.comparingInt(census::ticketIndex));
      for (Need n : needs) all.add(n.ticket());
      ticket = TICKETS.stream().map(Ticket::name).filter(x -> follows(x).containsAll(all)).findFirst().orElseThrow();
      tickets = String.join(",", all);
      // why: the needs of the tickets no other needed ticket follows.
      for (Need n : needs) {
        if (all.stream().noneMatch(o -> !o.equals(n.ticket()) && follows(o).contains(n.ticket()))) why.add(n.item());
      }
    } else {
      for (Need n : needs) if (n.spec().equals(spec)) why.add(n.item());
    }
    return String.join("\t", c.id(), spec, ticket, c.file() + ":" + c.line(), tickets, why.isEmpty() ? "-" : String.join(" ", why));
  }

  static int ticketIndex(String name) {
    for (int i = 0; i < TICKETS.size(); i++) if (TICKETS.get(i).name().equals(name)) return i;
    throw new IllegalStateException("unknown ticket " + name);
  }

  static String sha256(Path f) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
  }

  // --- the disassembly

  /** A function of the disassembly: main (parent null), or a definition or closure with its index in the parent. */
  static final class Section {
    final String name;
    final int index;
    final Section parent;
    final int codeIndent;
    final List<String> params = new ArrayList<>();
    final List<String[]> ops = new ArrayList<>();
    final Map<Integer, Section> children = new HashMap<>();
    final List<Section> order = new ArrayList<>();
    boolean prelude;

    Section(String name, int index, Section parent, int codeIndent) {
      this.name = name;
      this.index = index;
      this.parent = parent;
      this.codeIndent = codeIndent;
    }

    String sig() {
      return name + "/" + params.size();
    }

    /** The definition directly under main this section, which is not main, is part of. */
    Section top() {
      Section s = this;
      while (s.parent.parent != null) s = s.parent;
      return s;
    }
  }

  static final Pattern OP = Pattern.compile("( *)\\d{4,} ([A-Z_0-9]+)(?: (.*))?");
  static final Pattern PARAMS = Pattern.compile("( *)\\[params: (.*)\\]");
  static final Pattern HEAD = Pattern.compile("( *)(.+):(\\d+):");

  /**
   * jq's dump: a function's code, then each of its subfunctions as "name:index:", its "[params: …]" line and its code,
   * indented two more. Every line of jq-ref 1.8.2's dump is one of the three patterns.
   */
  static Section parse(String text) {
    Section main = new Section("<main>", -1, null, 0);
    Deque<Section> stack = new ArrayDeque<>();
    stack.push(main);
    for (String l : text.split("\n")) {
      Matcher m;
      if ((m = OP.matcher(l)).matches()) {
        at(stack, m.group(1).length()).ops.add(new String[] {m.group(2), m.group(3) == null ? "" : m.group(3)});
      } else if ((m = PARAMS.matcher(l)).matches()) {
        at(stack, m.group(1).length()).params.addAll(List.of(m.group(2).split(", ")));
      } else if ((m = HEAD.matcher(l)).matches()) {
        Section parent = at(stack, m.group(1).length());
        Section s = new Section(m.group(2), Integer.parseInt(m.group(3)), parent, parent.codeIndent + 2);
        parent.children.put(s.index, s);
        parent.order.add(s);
        stack.push(s);
      }
    }
    return main;
  }

  /** The section whose code is at this indent. */
  static Section at(Deque<Section> stack, int indent) {
    while (stack.peek().codeIndent > indent) stack.pop();
    return stack.peek();
  }

  /**
   * The section a call names: "name:index" is subfunction index of the function "^level" levels up (0 if absent), or
   * that function's closure parameter of that index, for which the answer is null.
   */
  static Section resolve(Section from, String ref) {
    int caret = ref.lastIndexOf('^');
    String r = caret < 0 ? ref : ref.substring(0, caret);
    Section frame = from;
    for (int i = caret < 0 ? 0 : Integer.parseInt(ref.substring(caret + 1)); i > 0; i--) frame = frame.parent;
    int colon = r.lastIndexOf(':');
    Section child = frame.children.get(Integer.parseInt(r.substring(colon + 1)));
    return child != null && child.name.equals(r.substring(0, colon)) ? child : null;
  }

  /** The needs of the closure reached from main. */
  static void closure(Section main, Set<String> userDefs, Set<String> builtins, List<Need> needs) {
    // The bytecoded builtins and builtin.jq's definitions come first under main, before the program's own. In the
    // leading run of builtin names, one the program also defines is the program's unless a name it does not define
    // comes after it.
    int run = 0;
    while (run < main.order.size()) {
      Section s = main.order.get(run);
      if (!builtins.contains(s.sig()) && !s.name.startsWith("_")) break;
      run++;
    }
    int last = -1;
    for (int i = 0; i < run; i++) if (!userDefs.contains(main.order.get(i).name)) last = i;
    for (int i = 0; i <= last; i++) main.order.get(i).prelude = true;
    Set<Section> seen = new LinkedHashSet<>();
    Map<Section, List<Section>> edges = new HashMap<>();
    Deque<Section> todo = new ArrayDeque<>();
    seen.add(main);
    todo.add(main);
    while (!todo.isEmpty()) {
      Section s = todo.poll();
      String[] prev = {"", ""};
      for (String[] op : s.ops) {
        switch (op[0]) {
          case "CALL_BUILTIN" -> builtin(op[1].split(" ")[0], prev, needs);
          case "LOADK", "PUSHK_UNDER" -> {
            if (op[1].contains("\"" + ENV_MARK + "\"")) needs.add(new Need("7", null, "$ENV"));
          }
          case "STORE_GLOBAL" -> needs.add(new Need("7", null, "data-import"));
          case "CALL_JQ", "TAIL_CALL_JQ" -> {
            for (String ref : op[1].split(" ")) {
              Section to = resolve(s, ref);
              if (to == null) continue;
              Need nat = to.prelude ? NATIVES.get(to.sig()) : null;
              if (nat != null) {
                needs.add(nat);
                continue;
              }
              edges.computeIfAbsent(s, x -> new ArrayList<>()).add(to);
              if (seen.add(to)) todo.add(to);
            }
          }
          default -> {}
        }
        prev = op;
      }
    }
    for (Section s : seen) {
      if (s.prelude) needs.add(t(PRELUDE_OWNERS.getOrDefault(s.sig(), "S2a.21"), s.sig()));
    }
    for (Section s : recursive(seen, edges)) needs.add(new Need("2b", null, "recursion:" + s.top().sig()));
  }

  /** A C builtin's need. format's is its constant name, pushed just before the call; a computed name is spec 4's. */
  static void builtin(String name, String[] prev, List<Need> needs) {
    if (OPERATORS.contains(name)) return;
    if (name.equals("format")) {
      String f = prev[0].equals("PUSHK_UNDER") ? prev[1].substring(1, prev[1].length() - 1) : "?";
      needs.add(f.equals("text") ? t("S2a.11", "interpolation") : new Need("4", null, "@" + f));
      return;
    }
    needs.add(BUILTINS.getOrDefault(name, new Need("4", null, name)));
  }

  /** The sections on a cycle of the reached calls (Tarjan). */
  static Set<Section> recursive(Set<Section> nodes, Map<Section, List<Section>> edges) {
    Map<Section, Integer> index = new HashMap<>(), low = new HashMap<>();
    Deque<Section> stack = new ArrayDeque<>();
    Set<Section> on = new HashSet<>(), out = new LinkedHashSet<>();
    int[] counter = {0};
    for (Section n : nodes) if (!index.containsKey(n)) tarjan(n, edges, index, low, stack, on, out, counter);
    return out;
  }

  static void tarjan(Section v, Map<Section, List<Section>> edges, Map<Section, Integer> index, Map<Section, Integer> low,
      Deque<Section> stack, Set<Section> on, Set<Section> out, int[] counter) {
    index.put(v, counter[0]);
    low.put(v, counter[0]++);
    stack.push(v);
    on.add(v);
    List<Section> next = edges.getOrDefault(v, List.of());
    for (Section w : next) {
      if (!index.containsKey(w)) {
        tarjan(w, edges, index, low, stack, on, out, counter);
        low.put(v, Math.min(low.get(v), low.get(w)));
      } else if (on.contains(w)) {
        low.put(v, Math.min(low.get(v), index.get(w)));
      }
    }
    if (low.get(v).equals(index.get(v))) {
      List<Section> scc = new ArrayList<>();
      Section w;
      do {
        w = stack.pop();
        on.remove(w);
        scc.add(w);
      } while (w != v);
      if (scc.size() > 1 || next.contains(v)) out.addAll(scc);
    }
  }

  // --- the tokens

  record Tok(String kind, String text) {}

  /** The needs of a program's tokens and the names it defines. */
  record Lexed(List<Need> needs, Set<String> defs) {}

  static final Set<String> KEYWORDS =
      Set.of("as", "def", "module", "import", "include", "if", "then", "elif", "else", "end", "and", "or", "reduce",
          "foreach", "try", "catch", "label", "break", "__loc__");

  /**
   * The tokens that end a term, after which `[` indexes rather than builds an array. A `}` is left out: the object
   * before it already needs construction, the later ticket.
   */
  static final Set<String> TERM_KINDS = Set.of("dot", "field", "str", "var", "ident");
  static final Set<String> TERM_ENDS = Set.of("]", ")", "?");
  /** The token of a string right after a '.', jq's '.' String: a field name. Any other string's text is '"'. */
  static final Tok FIELD_STRING = new Tok("str", ".\"");

  static Lexed lex(String program) {
    List<Tok> toks = new ArrayList<>();
    new Lexer(program, toks).code(false);
    List<Need> needs = new ArrayList<>();
    Set<String> defs = new HashSet<>();
    // The open brackets around each token, innermost first: "(", "{", "[" (an array), ".[" (a postfix bracket), "\(" (an
    // interpolation) and "if" (until its end). A comma whose innermost is "{" separates an object's pairs.
    Deque<String> open = new ArrayDeque<>();
    // What each closing token closed: a '?' after the ']' of a postfix bracket is navigation, after any other a try.
    String[] closed = new String[toks.size()];
    for (int k = 0; k < toks.size(); k++) {
      Tok tok = toks.get(k), prev = k > 0 ? toks.get(k - 1) : null, next = k + 1 < toks.size() ? toks.get(k + 1) : null;
      // A keyword before ':' directly in an object is a key ({if: 1}); elsewhere ':' is a slice's (.[0:if . then 1 end]).
      boolean key = new Tok("op", ":").equals(next) && "{".equals(open.peek());
      switch (tok.kind() + " " + tok.text()) {
        case "op (", "op {" -> open.push(tok.text());
        case "op [" -> open.push(postfix(prev) ? ".[" : "[");
        case "interp \\(" -> open.push("\\(");
        case "kw if" -> {
          if (!key) open.push("if");
        }
        case "op )", "op ]", "op }", "interp-end )" -> closed[k] = open.poll();
        case "kw end" -> {
          if (!key) open.poll();
        }
        default -> {}
      }
      switch (tok.kind()) {
        case "num", "str" -> needs.add(t("S2a.11", "literal"));
        // ADR-0005 lowers interpolation to nested Binds, which S2a.17 brings (F-35); bare @text stays S2a.11's.
        case "interp" -> needs.add(t("S2a.17", "interpolation"));
        case "var" -> needs.add(t("S2a.17", "$var"));
        case "loc" -> needs.add(t("S2a.17", "$__loc__"));
        case "ident" -> {
          if (Set.of("true", "false", "null").contains(tok.text())) needs.add(t("S2a.11", "literal"));
          if (new Tok("kw", "def").equals(prev)) defs.add(tok.text());
        }
        case "kw" -> {
          if (key) break;
          switch (tok.text()) {
            case "as" -> needs.add(t("S2a.17", "as"));
            case "and", "or" -> needs.add(t("S2a.16", tok.text()));
            case "if", "try", "catch", "label", "break" -> needs.add(t("S2a.18", tok.text()));
            case "reduce", "foreach" -> needs.add(t("S2a.19", tok.text()));
            case "def" -> needs.add(t("S2a.20", "def"));
            case "module", "import", "include" -> needs.add(new Need("7", null, tok.text()));
            default -> {}
          }
        }
        case "op" -> {
          switch (tok.text()) {
            case "," -> needs.add("{".equals(open.peek()) ? t("S2a.14", "{}") : t("S2a.13", ","));
            case "?" -> {
              // Optional navigation after a field, '.' String or a postfix bracket (parser.y's FIELD '?', '.' String '?',
              // Term '[' … ']' '?'); after any other term a try (Term '?').
              boolean nav = k > 0 && (prev.kind().equals("field") || prev.equals(FIELD_STRING) || ".[".equals(closed[k - 1]));
              needs.add(nav ? t("S2a.13", "?") : t("S2a.18", "term?"));
            }
            case "[" -> needs.add(postfix(prev) ? t("S2a.13", ".[]") : t("S2a.14", "[]"));
            case "{" -> needs.add(t("S2a.14", "{}"));
            case "+", "-", "*", "/", "%" -> needs.add(t("S2a.15", tok.text()));
            case "==", "!=", "<", "<=", ">", ">=", "//" -> needs.add(t("S2a.16", tok.text()));
            case "?//" -> needs.add(t("S2a.17", "?//"));
            default -> {}
          }
        }
        default -> {}
      }
    }
    return new Lexed(needs, defs);
  }

  /** A '[' after a term (jq's Term '[' …) indexes, iterates or slices; otherwise it builds an array. */
  static boolean postfix(Tok prev) {
    return prev != null && (TERM_KINDS.contains(prev.kind()) || prev.kind().equals("op") && TERM_ENDS.contains(prev.text()));
  }

  /** jq's lexer, as far as the census needs it: strings with interpolation, comments, fields, numbers, words. */
  static final class Lexer {
    final String s;
    final List<Tok> out;
    int i;

    Lexer(String s, List<Tok> out) {
      this.s = s;
      this.out = out;
    }

    /** Tokens until the end, or in an interpolation until its closing parenthesis. */
    void code(boolean interp) {
      int depth = 0;
      Matcher m = TOKEN.matcher(s);
      while (i < s.length()) {
        if (s.charAt(i) == '"') {
          string();
          continue;
        }
        m.region(i, s.length()).lookingAt();
        i = m.end();
        String kind = TOKEN_KINDS.stream().filter(k -> m.group(k) != null).findFirst().orElseThrow();
        String text = m.group(kind);
        if (interp && text.equals(")") && depth-- == 0) {
          out.add(new Tok("interp-end", ")"));
          return;
        }
        if (text.equals("(")) depth++;
        switch (kind) {
          case "space", "comment" -> {}
          case "word" -> out.add(new Tok(KEYWORDS.contains(text) ? "kw" : "ident", text));
          case "var" -> out.add(new Tok(text.equals("$__loc__") ? "loc" : "var", text));
          default -> out.add(new Tok(kind, text));
        }
      }
    }

    /**
     * A string literal; each interpolation's tokens, ended by an interp-end token, come before the string's own token,
     * which is FIELD_STRING when the string follows a '.'.
     */
    void string() {
      boolean field = !out.isEmpty() && out.getLast().kind().equals("dot");
      Matcher m = STRING_PART.matcher(s);
      i++;
      while (i < s.length() && s.charAt(i) != '"') {
        if (s.startsWith("\\(", i)) {
          out.add(new Tok("interp", "\\("));
          i += 2;
          code(true);
        } else {
          m.region(i, s.length()).lookingAt();
          i = m.end();
        }
      }
      i++;
      out.add(field ? FIELD_STRING : new Tok("str", "\""));
    }
  }

  static final String WORD = "(?:[a-zA-Z_][a-zA-Z_0-9]*::)*[a-zA-Z_][a-zA-Z_0-9]*";
  /** jq's tokens outside strings, longest first where two share a start; the op group takes any other character. */
  static final Pattern TOKEN =
      Pattern.compile("(?<space>\\s+)|(?<comment>#[^\\n]*)|(?<num>(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)"
          + "|(?<field>\\." + WORD + ")|(?<dot>\\.)|(?<var>\\$" + WORD + ")|(?<word>" + WORD + ")"
          + "|(?<op>\\?//|//=|[|+*/%-]=|[=!<>]=|//|(?s:.))");
  static final List<String> TOKEN_KINDS = List.of("space", "comment", "num", "field", "dot", "var", "word", "op");
  /** A run of a string's characters up to its end or an interpolation, or one escape. */
  static final Pattern STRING_PART = Pattern.compile("\\\\(?s:.)|[^\"\\\\]+");
}
