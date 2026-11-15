package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.US_ASCII;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The calibration's shapes and its child JVM (ADR-0046 and its #111, #113 and #116 amendments, F-55, F-30, F-93,
 * F-95). A calibration cell is one shape × tier × thread × compile mode; this child runs the cells of one tier, one
 * thread and its modes, and prints a tab-separated row per cell.
 *
 * <p>The child runs its work on the launcher's main thread, sized by {@code -Xss} (F-93); a virtual cell runs it on a
 * virtual thread, whose carrier takes {@code -Xss} (K8). Before any other work on that thread it proves the thread's
 * size with a plain recursion to {@link StackOverflowError} and prints the count as {@code proof <kind> <bytes>
 * <frames>}: the parent compares it with the count of a 1 MiB thread of the same tier (D2).
 *
 * <p>{@code args}: {@code <action> <platform|virtual> <thread bytes, as given to -Xss> <modes, comma-separated>
 * [<ids, comma-separated> [warm | <id>=<U*>,…]]}. The actions:
 *
 * <ul>
 *   <li>{@code proof}: the proof line only;
 *   <li>{@code check}: each shape once at the committed constants, {@code stackBytes} the thread's size, then its
 *       control, which declares 1 GiB on the same thread: {@code check <mode> <id> <D_f> <T> <b> <reserve> <U_f>
 *       <end> <control>};
 *   <li>{@code search}: each shape's unit edge {@code U*}, the largest {@code U_f} at which it gives reference jq's
 *       result, from {@code floorDiv(S - reserve, b) - D_f} by gallop and bisection: {@code cell <mode> <id> <D_f> <T>
 *       <b> <reserve> <U*> <end at U*+1> <control>}, {@code U*} {@code -} for a flat shape (its control completes) and
 *       {@code fails} for one that does not complete at {@code U_f} 0;
 *   <li>{@code probe <id> <U|-> [control]}: one run at {@code stackBytesFor(U)}, the child's first ({@code -}: at
 *       {@code U_f(S)}), then the control if asked: {@code probe <mode> <id> <D_f> <T> <b> <reserve> <U> <end>
 *       <control|->}; the parent's bisection over such children gives the cold edge;
 *   <li>{@code linked <id> [seed]}: two linking runs at {@code U_f} 64, then {@code search} in the child: the linked
 *       edge, as a {@code cell} row; {@code search <id> warm [seed]} warms up first (JIT cells);
 *   <li>{@code edge}: the ends at {@code U* - 1}, {@code U*} and {@code U* + 1}: {@code edge <mode> <id> <U*> <end>
 *       <end> <end>};
 *   <li>{@code native}: each of {@link #NATIVES} at the thread's size: {@code native <mode> <id> <end>};
 *   <li>{@code heap}: each of {@link #HELD} on n = 10⁵ and 2·10⁵ with {@code stackBytes} 0 and 512 KiB; at the first
 *       emit, two {@code System.gc()} and the used heap: {@code heap <mode> <id> <stackBytes> <n> <used> <end>
 *       <maxMemory>}.
 * </ul>
 *
 * <p>An end is the results' JSON texts, then {@code ran out}, {@code stopped}, {@code error <value>}, {@code
 * termination <cause>} or what the run threw.
 */
final class CalibrationCell {
  private CalibrationCell() {}

  /**
   * A shape: its id, filter, input and reference jq 1.8.2's one result. Recorded by oracle-record.sh in diff/deep.rec
   * (deep/shape-*, deep/native-*, and #111's deep/collect, deep/reduce, deep/first-or, deep/hold, deep/nested-drivers).
   */
  record Shape(String id, String filter, String json, String result) {
    /** The end of a run that gave reference jq's result. */
    String completed() {
      return "[" + result + "], ran out";
    }
  }

  /** The end of a run that the real stack ended before the trampoline took over: the backstop (ADR-0011). */
  static final String STACK = "[], termination STACK";

  /** A declared stack far larger than any child thread: every level stays on the Java stack (the control). */
  static final long GIB = 1L << 30;

  /** Spike A's input: an array of 10 000 ones. */
  static final String ONES = "[" + "1,".repeat(9_999) + "1]";

  /** The prefix of Spike A's T, E, M and N. */
  private static final String PRE = ". as $a|($a|length) as $L|";

  /**
   * F-95's Collect-heavy descent, on this branch: each level's call sits under 100 Collects, as each of #114's 300
   * PLAIN calls of IrDepthTest's 30 001-level filter does. That filter is a Stub until #114 lifts it (IrDepthTest
   * {@code aFilterOf30001LevelsIsAStub}); a RECURSIVE call weighs its units as a PLAIN one does (#114), so the
   * recursion is its stand-in here. As in #114's filter nothing rises above the call's result but one step, {@code |
   * $n}. It is the 13th shape (ADR-0046 #113 "Rulings on the red"); #114 adds its PLAIN-call filter as a 14th after it
   * merges, and re-runs the matrix.
   */
  static final String COLLECT_DESCENT = "def c: if . == 0 then 0 else . as $n | " + "[".repeat(100) + "$n - 1 | c" + "]".repeat(100) + " | $n end; 300 | c";

  /**
   * The 13 shapes of ADR-0046 #113 (K4 and "Rulings on the red"), F-95's Collect-heavy descent the 13th; #114 adds a
   * 14th, its PLAIN-call filter of 300 calls under 100 Collects each. Spike A's T, E, M and N on {@link #ONES}; the
   * wide body W; the closure chain C; the nested drivers ND; F-30's {@code [..]|length} on DEEP; #111's collect,
   * reduce, first-or and hold; and {@link #COLLECT_DESCENT}.
   */
  static final List<Shape> SHAPES =
      List.of(
          new Shape("T", PRE + "def s: if .[0]==$L then .[1] else [.[0]+1,.[1]+$a[.[0]]]|s end; [0,0]|s", ONES, "10000"),
          new Shape("E", PRE + "def r(f): def rr: ., (f|rr); rr; reduce (0|r(if . < $L-1 then .+1 else empty end)) as $i (0; .+$a[$i])", ONES, "10000"),
          new Shape(
              "M",
              PRE + "def ev: def od: if .[0]==$L then .[1] else [.[0]+1,.[1]+$a[.[0]]]|ev end; if .[0]==$L then .[1] else [.[0]+1,.[1]+$a[.[0]]]|od end; [0,0]|ev",
              ONES,
              "10000"),
          new Shape("N", PRE + "def s: if .==$L then 0 else $a[.]+(.+1|s) end; 0|s", ONES, "10000"),
          new Shape("W", "def w: if .==0 then 0 else " + "([{a: (try (".repeat(16) + "(.-1|w)+1" + "))}][0].a)".repeat(16) + " end; 10000|w", "null", "10000"),
          new Shape("C", "def c(f): if .==0 then f else (.-1|c(f+1)) end; 10000|c(0)", "null", "10000"),
          new Shape("ND", DeepStackFreshJvmTest.NESTED, "null", "1000"),
          new Shape("recurse", "[..]|length", DeepStackFreshJvmTest.DEEP, "9999"),
          new Shape("collect", "def d: [.[]?|d]; d|length", DeepStackFreshJvmTest.DEEP, "1"),
          new Shape("reduce", "def r: reduce .[] as $x (0; .+($x|r)+1); r", DeepStackFreshJvmTest.DEEP, "9998"),
          new Shape("first-or", "def l: first(.[]?|l)//7; l", DeepStackFreshJvmTest.DEEP, "7"),
          new Shape("hold", DeepStackFreshJvmTest.HOLD, "null", "0"),
          new Shape("collect-descent", COLLECT_DESCENT, "null", "300"));

  /** The 10 000-deep native check (D4), each native's first use in its child at the deepest emit. */
  static final List<Shape> NATIVES =
      List.of(
          new Shape("tojson", "tojson|length", DeepStackFreshJvmTest.DEEP, "19998"),
          new Shape("sort", "[.,.]|sort|length", DeepStackFreshJvmTest.DEEP, "2"),
          new Shape("sort-at-depth", "def d: [.[]?|d]|sort; d|tojson|length", DeepStackFreshJvmTest.DEEP, "19998"));

  /** The warm-up runs of a JIT child before it measures, as #111's measure-b.sh. */
  private static final int WARM_UPS = 30;

  /** The largest {@code U_f} a search tries; far past any thread of 1 MiB. */
  private static final long SEARCH_LIMIT = 1L << 22;

  /** The closing line of {@code explain()}: {@code D_f}, {@code T}, {@code b} and the reserve (ADR-0046 #111). */
  record Units(long callFreeDepth, long starterUnits, long bytesPerUnit, long reserve) {
    /** The {@code stackBytes} that give {@code unitLimit} as {@code U_f}. */
    long stackBytesFor(long unitLimit) {
      return reserve + bytesPerUnit * (unitLimit + callFreeDepth);
    }

    /** {@code U_f = floorDiv(stackBytes - reserve, b) - D_f}. */
    long unitLimitAt(long stackBytes) {
      return Math.floorDiv(stackBytes - reserve, bytesPerUnit) - callFreeDepth;
    }
  }

  /** The facts of the one line of {@code filter}'s explain text that carries {@code callFreeDepth}. */
  static Units units(String filter, CompileMode mode) {
    String text = engine(Limits.cli().stackBytes(), mode).compile(filter).explain();
    List<String> closing = Arrays.stream(text.split("\n")).filter(l -> l.contains("callFreeDepth=")).toList();
    if (closing.size() != 1) {
      throw new IllegalStateException(mode + ": not one closing line with callFreeDepth in:\n" + text);
    }
    Map<String, String> facts = new HashMap<>();
    for (String field : closing.getFirst().split(" ")) {
      int eq = field.indexOf('=');
      if (eq > 0) facts.put(field.substring(0, eq), field.substring(eq + 1));
    }
    return new Units(
        Long.parseLong(facts.get("callFreeDepth")),
        Long.parseLong(facts.get("starterUnits")),
        Long.parseLong(facts.get("bytesPerUnit")),
        Long.parseLong(facts.get("reserve")));
  }

  /** An engine of {@link Limits#cli()} whose declared stack is {@code stackBytes}, compiling in {@code mode}. */
  static JqEngine engine(long stackBytes, CompileMode mode) {
    Limits cli = Limits.cli();
    return JqEngine.builder()
        .limits(new Limits(Limits.UNLIMITED, Limits.UNLIMITED_TIME, cli.nestingDepth(), cli.recursionDepth(), Limits.UNLIMITED, Limits.UNLIMITED, cli.regexMatchTime(), stackBytes))
        .compileMode(mode)
        .build();
  }

  /** How {@code shape} ended in {@code mode} with {@code stackBytes} declared, on the calling thread. */
  static String ended(Shape shape, long stackBytes, CompileMode mode) {
    try {
      JqEngine engine = engine(stackBytes, mode);
      JqFilter compiled = engine.compile(shape.filter());
      Workspace workspace = engine.newWorkspace();
      JqValue root = read(workspace, shape.json().getBytes(US_ASCII)).root();
      Results results = new Results();
      String end;
      try {
        end = compiled.run(root, NONE, results, workspace) ? "ran out" : "stopped";
      } catch (JqFilterException e) {
        end = "error " + e.value().toJson();
      } catch (JqTermination t) {
        end = "termination " + t.cause();
      }
      return results.values.stream().map(JqValue::toJson).toList() + ", " + end;
    } catch (RuntimeException | StackOverflowError e) {
      // An outcome the parent asserts against, never a pass: it is printed as the cell's end.
      return "threw " + e;
    }
  }

  /** A plain recursion whose depth at {@link StackOverflowError} is the proof of its thread's size. */
  private static final class Probe {
    private long depth;

    private void down() {
      depth++;
      down();
    }
  }

  /** The proof: the frames of a plain recursion, the last of three runs, so a JIT tier counts its compiled frames. */
  static long proof() {
    long frames = 0;
    for (int i = 0; i < 3; i++) {
      Probe probe = new Probe();
      try {
        probe.down();
      } catch (StackOverflowError e) {
        frames = probe.depth;
      }
    }
    return frames;
  }

  public static void main(String[] args) throws InterruptedException {
    String action = args[0];
    String kind = args[1];
    long thread = Long.parseLong(args[2]);
    List<CompileMode> modes = Arrays.stream(args[3].split(",")).map(CompileMode::valueOf).toList();
    List<String> ids = args.length > 4 ? List.of(args[4].split(",")) : List.of();
    String extra = args.length > 5 ? args[5] : "";
    String more = args.length > 6 ? args[6] : "";
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Runnable work =
        () -> {
          try {
            System.out.println("proof\t" + kind + "\t" + thread + "\t" + proof());
            for (CompileMode mode : modes) {
              run(action, thread, mode, ids, extra, more);
            }
          } catch (Throwable t) {
            failure.set(t);
          }
        };
    if (kind.equals("virtual")) {
      Thread.ofVirtual().name("calibration").start(work).join();
    } else {
      work.run();
    }
    if (failure.get() != null) {
      failure.get().printStackTrace(System.out);
      System.exit(1);
    }
  }

  private static void run(String action, long thread, CompileMode mode, List<String> ids, String extra, String more) {
    switch (action) {
      case "probe" -> {
        // One run, the first of this child (the cold edge of ADR-0046 #113 "Rulings on the red"); "-" probes U_f(S).
        Shape shape = shapes(SHAPES, ids).getFirst();
        Units u = units(shape.filter(), mode);
        long unitLimit = extra.equals("-") ? Math.max(0, u.unitLimitAt(thread)) : Long.parseLong(extra);
        String end = ended(shape, u.stackBytesFor(unitLimit), mode);
        String control = more.equals("control") ? ended(shape, GIB, mode) : "-";
        row("probe", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), unitLimit, end, control);
      }
      case "linked" -> {
        // The linked edge: two linking runs that switch after 64 units, then the search in this child.
        for (Shape shape : shapes(SHAPES, ids)) {
          Units u = units(shape.filter(), mode);
          ended(shape, u.stackBytesFor(64), mode);
          ended(shape, u.stackBytesFor(64), mode);
          search(shape, thread, mode, u, extra.isEmpty() ? -1 : Long.parseLong(extra));
        }
      }
      case "proof" -> {
        // The proof line only.
      }
      case "check" -> {
        for (Shape shape : shapes(SHAPES, ids)) {
          Units u = units(shape.filter(), mode);
          String end = ended(shape, thread, mode);
          String control = ended(shape, GIB, mode);
          row("check", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), u.unitLimitAt(thread), end, control);
        }
      }
      case "search" -> {
        for (Shape shape : shapes(SHAPES, ids)) {
          Units u = units(shape.filter(), mode);
          if (extra.equals("warm")) {
            for (int i = 0; i < WARM_UPS; i++) {
              ended(shape, 0, mode);
              ended(shape, u.stackBytesFor(300), mode);
            }
          }
          search(shape, thread, mode, u, more.isEmpty() ? -1 : Long.parseLong(more));
        }
      }
      case "edge" -> {
        Map<String, Long> edges = new LinkedHashMap<>();
        for (String pair : extra.split(",")) {
          String[] kv = pair.split("=");
          edges.put(kv[0], Long.parseLong(kv[1]));
        }
        for (Shape shape : shapes(SHAPES, ids)) {
          Units u = units(shape.filter(), mode);
          long edge = edges.get(shape.id());
          row("edge", mode, shape.id(), edge, ended(shape, u.stackBytesFor(edge - 1), mode), ended(shape, u.stackBytesFor(edge), mode), ended(shape, u.stackBytesFor(edge + 1), mode));
        }
      }
      case "native" -> {
        for (Shape shape : shapes(NATIVES, ids)) {
          row("native", mode, shape.id(), ended(shape, thread, mode));
        }
      }
      case "heap" -> {
        for (Map.Entry<String, String> held : HELD.entrySet()) {
          for (long stackBytes : new long[] {0, 512 * 1024L}) {
            for (long n : new long[] {100_000, 200_000}) {
              heap(mode, held.getKey(), held.getValue(), stackBytes, n);
            }
          }
        }
      }
      default -> throw new IllegalArgumentException("no action " + action);
    }
  }

  /**
   * The gallop and bisection of the #113 design in this child, from {@code seed} (the committed record's edge), or
   * from {@code floorDiv(S - reserve, b) - D_f} when it is negative.
   */
  private static void search(Shape shape, long thread, CompileMode mode, Units u, long seed) {
    String control = ended(shape, GIB, mode);
    if (!control.equals(STACK)) {
      row("cell", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), "-", "-", control);
      return;
    }
    long start = seed >= 0 ? seed : Math.max(0, u.unitLimitAt(thread));
    long lo;
    long hi;
    String first = ended(shape, u.stackBytesFor(start), mode);
    if (first.equals(shape.completed())) {
      lo = start;
      long step = 1;
      hi = lo + step;
      while (hi < SEARCH_LIMIT && ended(shape, u.stackBytesFor(hi), mode).equals(shape.completed())) {
        lo = hi;
        step *= 2;
        hi = lo + step;
      }
    } else {
      hi = start;
      long step = 1;
      lo = Math.max(0, hi - step);
      while (lo > 0 && !ended(shape, u.stackBytesFor(lo), mode).equals(shape.completed())) {
        hi = lo;
        step *= 2;
        lo = Math.max(0, hi - step);
      }
      if (lo == 0 && !ended(shape, u.stackBytesFor(0), mode).equals(shape.completed())) {
        row("cell", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), "fails", ended(shape, u.stackBytesFor(0), mode), control);
        return;
      }
    }
    while (hi - lo > 1) {
      long mid = lo + (hi - lo) / 2;
      if (ended(shape, u.stackBytesFor(mid), mode).equals(shape.completed())) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    row("cell", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), lo, ended(shape, u.stackBytesFor(lo + 1), mode), control);
  }

  /**
   * The held generators of the heap measurement (ADR-0046 #113 "Rulings on the red"): each leaf emits twice, so at the
   * first emit every activation is still live. On n each gives n twice (jq-ref 1.8.2 at n = 100 000 and 200 000,
   * observed 2026-11-15 with -c, .logs/113-jqref-heap.log of the test-author's run).
   */
  static final Map<String, String> HELD =
      new LinkedHashMap<>(
          Map.of(
              "F-held", "def f: if . == 0 then (0, 0) else (. - 1 | f) + 1 end; f",
              "F-held-try16", "def f: if . == 0 then (0, 0) else " + "try (".repeat(16) + "(. - 1 | f) + 1" + ")".repeat(16) + " end; f",
              "C-held", "def c(f): if .==0 then (f, f) else (.-1|c(f+1)) end; c(0)"));

  /** {@code filter} on {@code n}: the used heap after two collections at its first emit, every activation live. */
  private static void heap(CompileMode mode, String id, String filter, long stackBytes, long n) {
    JqEngine engine = engine(stackBytes, mode);
    JqFilter compiled = engine.compile(filter);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, Long.toString(n).getBytes(US_ASCII)).root();
    long[] used = {-1};
    List<String> values = new ArrayList<>();
    JqSink sink =
        v -> {
          if (used[0] < 0) {
            System.gc();
            System.gc();
            // The used heap; Runtime, since jq-core's module does not read java.management.
            used[0] = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
          }
          values.add(v.toJson());
          return true;
        };
    String end;
    try {
      end = compiled.run(root, NONE, sink, workspace) ? "ran out" : "stopped";
    } catch (JqTermination t) {
      end = "termination " + t.cause();
    }
    row("heap", mode, id, stackBytes, n, used[0], values + ", " + end, Runtime.getRuntime().maxMemory());
  }

  private static List<Shape> shapes(List<Shape> all, List<String> ids) {
    return ids.isEmpty() ? all : all.stream().filter(s -> ids.contains(s.id())).toList();
  }

  private static void row(Object... fields) {
    System.out.println(String.join("\t", Arrays.stream(fields).map(String::valueOf).toList()));
  }
}
