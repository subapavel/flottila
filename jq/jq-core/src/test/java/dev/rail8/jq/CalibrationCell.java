package dev.rail8.jq;

import static dev.rail8.jq.RunBudgets.NONE;
import static dev.rail8.jq.RunBudgets.read;
import static java.nio.charset.StandardCharsets.US_ASCII;

import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.query.compile.CompileMode;
import dev.rail8.jq.value.JqValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * The calibration's shapes and its child JVM. A calibration cell is one shape × tier × thread × compile mode; this
 * child runs the cells of one tier, one thread and its modes, and prints a tab-separated row per cell.
 *
 * <p>The child runs its work on the launcher's main thread, sized by {@code -Xss} (F-93); a virtual cell runs it on a
 * virtual thread, whose carrier takes {@code -Xss} (K8). Before any other work on that thread it proves the thread's
 * size with a plain recursion to {@link StackOverflowError} and prints the count as {@code proof <kind> <bytes>
 * <frames>}: the parent compares it with the count of the next platform thread up of the same tier.
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
 *       result, from {@code floorDiv(S - reserve, b) - D_f} by gallop and bisection, repeated from its own result until
 *       two consecutive passes agree (the adviser's L1): {@code cell <mode> <id> <D_f> <T> <b> <reserve> <U*> <end at
 *       U*+1> <control> <passes>}, {@code U*} {@code -} for a flat shape (its control completes), {@code fails} for one
 *       that does not complete at {@code U_f} 0 and {@code INVALID} when no two consecutive of {@value #PASSES} passes
 *       agree; {@code <passes>} is each pass's {@code U*}, comma-separated;
 *   <li>{@code probe <id> <U|-> [control]}: one run at {@code stackBytesFor(U)}, the child's first ({@code -}: at
 *       {@code U_f(S)}), then the control if asked: {@code probe <mode> <id> <D_f> <T> <b> <reserve> <U> <end>
 *       <control|->}; the parent's bisection over such children gives the cold edge;
 *   <li>{@code linked <id> [seed]}: two linking runs at {@code U_f} 64, then {@code search} in the child: the linked
 *       edge, as a {@code cell} row; {@code search <id> warm [seed]} warms up first (C1 cells);
 *   <li>{@code warm <id> <runs>}: the warm check of K1, one shape in one mode: from the child's first run of the shape,
 *       {@code <runs>} consecutive runs with the thread's own size declared as {@code stackBytes}, each printed as
 *       {@code warm <mode> <id> <D_f> <T> <b> <reserve> <U_f> <run> <end>} ({@code <run>} 1 to {@code <runs>}), then
 *       the control, which declares 1 GiB, as the same row with {@code control} for {@code <run>};
 *   <li>{@code edge}: the ends at {@code U* - 1}, {@code U*} and {@code U* + 1}: {@code edge <mode> <id> <U*> <end>
 *       <end> <end>};
 *   <li>{@code native}: each of {@link #NATIVES} at the thread's size: {@code native <mode> <id> <end>};
 *   <li>{@code heap}: each of {@link #HELD} on n = 10⁵ and 2·10⁵ with {@code stackBytes} 0 and 512 KiB; at the first
 *       emit, two {@code System.gc()} and the used heap: {@code heap <mode> <id> <stackBytes> <n> <used> <end>
 *       <maxMemory>}.
 * </ul>
 *
 * <p>An end is the results' JSON texts, then {@code ran out}, {@code stopped}, {@code error <value>}, {@code
 * termination <cause>} or what the run threw, or {@code compile threw <throwable>} for the compile. Every cell's filter
 * is compiled on one helper thread of {@value #HELPER_STACK} bytes, started after the cell's thread on first use and
 * living as long as the child, and run on the cell's thread, so the cell's stack measures the run and never the
 * compile. A {@code probe}, {@code linked} or {@code search} child whose compile fails prints {@code compile <mode> <id>
 * <throwable>} in place of its rows.
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
   * $n}. It is the 13th shape; the PLAIN-call filter itself is the 14th, {@link #PLAIN_DESCENT}.
   */
  static final String COLLECT_DESCENT = "def c: if . == 0 then 0 else . as $n | " + "[".repeat(100) + "$n - 1 | c" + "]".repeat(100) + " | $n end; 300 | c";

  /**
   * The PLAIN-call filter, IrDepthTest's {@code nested(30_001)}, built here so a child loads no other test class:
   * {@code def f1: [[…[.]…]]; def f2: [[…[f1]…]]; … def f300: …; f300}, 100 brackets each, 30 001
   * levels of PLAIN calls past the IR bound.
   */
  static final String PLAIN_DESCENT = plainDescent();

  /**
   * Reference jq 1.8.2's output for {@link #PLAIN_DESCENT} on null, the same text as IrDepthTest's {@code
   * NESTED_30001}: 10 001 brackets, {@code <skipped: too deep>}, 10 001 brackets.
   */
  static final String NESTED_30001 = "[".repeat(10_001) + "<skipped: too deep>" + "]".repeat(10_001);

  private static String plainDescent() {
    StringBuilder f = new StringBuilder();
    for (int i = 1; i <= 300; i++) f.append("def f").append(i).append(": ").append("[".repeat(100)).append(i == 1 ? "." : "f" + (i - 1)).append("]".repeat(100)).append("; ");
    return f.append("f300").toString();
  }

  /**
   * The 14 shapes: Spike A's T, E, M and N on {@link #ONES}; the wide body W; the closure chain C; the nested drivers
   * ND; {@code [..]|length} on DEEP; the collect, reduce, first-or and hold recursions; the Collect-heavy descent
   * {@link #COLLECT_DESCENT}; and the PLAIN-call descent {@link #PLAIN_DESCENT}.
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
          new Shape("collect-descent", COLLECT_DESCENT, "null", "300"),
          new Shape("plain-descent", PLAIN_DESCENT, "null", NESTED_30001));

  /** The 10 000-deep native check (D4), each native's first use in its child at the deepest emit. */
  static final List<Shape> NATIVES =
      List.of(
          new Shape("tojson", "tojson|length", DeepStackFreshJvmTest.DEEP, "19998"),
          new Shape("sort", "[.,.]|sort|length", DeepStackFreshJvmTest.DEEP, "2"),
          new Shape("sort-at-depth", "def d: [.[]?|d]|sort; d|tojson|length", DeepStackFreshJvmTest.DEEP, "19998"));

  /** The warm-up runs of a C1 child before it measures, as #111's measure-b.sh. */
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

  /** The stack of the compile helper, far above any shape's compile (a C1-compiled Parser overflows 512 KiB). */
  private static final long HELPER_STACK = 16L << 20;

  /** What compiling threw on the helper thread, so it never reads as a run's end on the cell's thread. */
  static final class CompileFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CompileFailed(Throwable cause) {
      super(cause);
    }
  }

  /**
   * The compile helper: one platform daemon thread, started when this class is first used, which is after the cell's
   * thread, and living as long as the child. Work is handed over through one slot and an unpark, so the thread that
   * hands it over takes no lock and never parks.
   */
  private static final class Helper {
    private static final AtomicReference<Runnable> WORK = new AtomicReference<>();
    private static final Thread THREAD = start();

    private Helper() {}

    private static Thread start() {
      Thread thread = new Thread(null, Helper::serve, "calibration-compile", HELPER_STACK);
      thread.setDaemon(true);
      thread.start();
      return thread;
    }

    private static void serve() {
      while (true) {
        Runnable work = WORK.getAndSet(null);
        if (work == null) LockSupport.park();
        else work.run();
      }
    }

    /** Puts {@code work} in the slot once it is free, spinning while another caller's work waits there. */
    static void submit(Runnable work) {
      while (!WORK.compareAndSet(null, work)) Thread.onSpinWait();
      LockSupport.unpark(THREAD);
    }
  }

  /**
   * {@code compile} done on the helper thread: the cell's thread only runs filters, so {@code b} and the reserve
   * measure the run, not the compile. What it threw is a {@link CompileFailed}. A platform cell parks in {@code
   * join()}; a virtual cell spins on {@code isDone} with {@code Thread.onSpinWait} and never parks, yields or sleeps,
   * since a virtual thread that unmounts comes back with only its top frames on the carrier, which would change the
   * stack it measures.
   */
  static <T> T onHelper(Supplier<T> compile) {
    CompletableFuture<T> compiled = new CompletableFuture<>();
    Helper.submit(
        () -> {
          try {
            compiled.complete(compile.get());
          } catch (Throwable t) {
            compiled.completeExceptionally(t);
          }
        });
    if (Thread.currentThread().isVirtual()) {
      while (!compiled.isDone()) Thread.onSpinWait();
    }
    try {
      return compiled.join();
    } catch (CompletionException e) {
      throw new CompileFailed(e.getCause());
    }
  }

  /** The facts of the one line of {@code filter}'s explain text that carries {@code callFreeDepth}. */
  static Units units(String filter, CompileMode mode) {
    String text = onHelper(() -> engine(Limits.cli().stackBytes(), mode).compile(filter).explain());
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

  /** A run of a shape as an in-process test stands in for it: its end at {@code stackBytes}, given its callers. */
  interface Run {
    String end(long stackBytes, List<String> callers);
  }

  /**
   * An in-process test's stand-in for every run of {@link #ended}, which then runs no shape and passes it the methods
   * on the stack, {@code ended} first; null in a child. It holds no local of {@code ended}, so a run's frames start
   * where they did without it.
   */
  static volatile Run standIn;

  /** How {@code shape} ended in {@code mode} with {@code stackBytes} declared, on the calling thread. */
  static String ended(Shape shape, long stackBytes, CompileMode mode) {
    if (standIn != null) return standIn.end(stackBytes, StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getMethodName).toList()));
    try {
      JqEngine engine = engine(stackBytes, mode);
      JqFilter compiled = onHelper(() -> engine.compile(shape.filter()));
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
    } catch (CompileFailed e) {
      // The helper's compile, not the run on this thread.
      return "compile threw " + e.getCause();
    } catch (RuntimeException | StackOverflowError e) {
      // An outcome the parent asserts against, never a pass: it is printed as the cell's end.
      return "threw " + e;
    }
  }

  /** A plain recursion whose depth at {@link StackOverflowError} is the proof of its thread's size. */
  private static final class Probe {
    private long depth;

    // The recursion is meant to end in a StackOverflowError: the depth it reached measures the thread's stack.
    @SuppressWarnings("InfiniteRecursion")
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
        Optional<Units> compiled = compiled(shape, mode);
        if (compiled.isEmpty()) return;
        Units u = compiled.get();
        long unitLimit = extra.equals("-") ? Math.max(0, u.unitLimitAt(thread)) : Long.parseLong(extra);
        String end = ended(shape, u.stackBytesFor(unitLimit), mode);
        String control = more.equals("control") ? ended(shape, GIB, mode) : "-";
        row("probe", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), unitLimit, end, control);
      }
      case "linked" -> {
        // The linked edge: two linking runs that switch after 64 units, then the search in this child.
        for (Shape shape : shapes(SHAPES, ids)) {
          Optional<Units> compiled = compiled(shape, mode);
          if (compiled.isEmpty()) continue;
          Units u = compiled.get();
          ended(shape, u.stackBytesFor(64), mode);
          ended(shape, u.stackBytesFor(64), mode);
          search(shape, thread, mode, u, extra.isEmpty() ? -1 : Long.parseLong(extra));
        }
      }
      case "proof" -> {
        // The proof line only.
      }
      case "warm" -> {
        // K1: warm proves the constants, it does not measure them. No warm-up: run 1 is the child's first run of the
        // shape, every run declares the thread's own size, and the control comes after the last.
        Shape shape = shapes(SHAPES, ids).getFirst();
        Units u = units(shape.filter(), mode);
        int runs = Integer.parseInt(extra);
        for (int run = 1; run <= runs; run++) {
          row("warm", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), u.unitLimitAt(thread), run, ended(shape, thread, mode));
        }
        row("warm", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), u.unitLimitAt(thread), "control", ended(shape, GIB, mode));
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
          Optional<Units> compiled = compiled(shape, mode);
          if (compiled.isEmpty()) continue;
          Units u = compiled.get();
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
   * The shape's units, compiled on the helper thread, or empty after printing {@code compile <mode> <id> <what it
   * threw>} when that compile fails; the parent fails such a cell.
   */
  private static Optional<Units> compiled(Shape shape, CompileMode mode) {
    try {
      return Optional.of(units(shape.filter(), mode));
    } catch (CompileFailed e) {
      row("compile", mode, shape.id(), e.getCause());
      return Optional.empty();
    }
  }

  /** The most passes of a search before its cell is INVALID (the adviser's L1). */
  static final int PASSES = 4;

  /** The U* of each pass of a search, in order; -1 for a pass whose shape does not complete at {@code U_f} 0. */
  record Passes(List<Long> edges) {
    /** Whether the last two passes agree on {@code U*}: the search reached its fixed point (L1); a fail ends a search. */
    boolean agreed() {
      int n = edges.size();
      return n >= 2 && edges.get(n - 1).equals(edges.get(n - 2));
    }

    /** Whether the search is done: its passes agree, or {@value #PASSES} have run. */
    boolean settled() {
      return agreed() || edges.size() == PASSES;
    }

    /** The passes as a search row prints them: each pass's {@code U*}, comma-separated, {@code fails} for -1. */
    @Override
    public String toString() {
      return String.join(",", edges.stream().map(e -> e < 0 ? "fails" : Long.toString(e)).toList());
    }
  }

  /**
   * The gallop and bisection of the #113 design in this child, from {@code seed} (the committed record's edge), or
   * from {@code floorDiv(S - reserve, b) - D_f} when it is negative, repeated from its own result until two
   * consecutive passes agree (the adviser's L1): a JIT edge found while the JIT still compiles moves on the next pass,
   * and an {@code -Xint} linked edge, which is deterministic, agrees on pass 2. {@code U*} is {@code INVALID} when no
   * two consecutive of {@value #PASSES} passes agree, and a pass that fails at {@code U_f} 0 ends the search. The row's
   * last field is the passes.
   *
   * <p>Every run of the shape, the control, each pass's and the end's at {@code U* + 1}, is a call of {@link #ended}
   * from this frame, with nothing under its arguments, and the frame keeps 078dd0a's 17 local words, so a run starts
   * where 078dd0a's did and the e6f4cc0 record stays the baseline (the architect's ruling (a)). Four helper frames under
   * the passes' runs moved 82 of 156 edges by 11 to 58 units (impl/113 061f5cf).
   */
  static void search(Shape shape, long thread, CompileMode mode, Units u, long seed) {
    String control = ended(shape, GIB, mode);
    if (!control.equals(STACK)) {
      row("cell", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), "-", "-", control, "-");
      return;
    }
    List<Long> edges = new ArrayList<>();
    long start = seed >= 0 ? seed : Math.max(0, u.unitLimitAt(thread));
    long lo;
    long hi;
    do {
      if (ended(shape, u.stackBytesFor(start), mode).equals(shape.completed())) {
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
          edges.add(-1L);
          row("cell", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), "fails", ended(shape, u.stackBytesFor(0), mode), control, new Passes(edges));
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
      edges.add(lo);
      start = lo;
    } while (!new Passes(edges).settled());
    row("cell", mode, shape.id(), u.callFreeDepth(), u.starterUnits(), u.bytesPerUnit(), u.reserve(), new Passes(edges).agreed() ? Long.toString(lo) : "INVALID", ended(shape, u.stackBytesFor(lo + 1), mode), control, new Passes(edges));
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
