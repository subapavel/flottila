package io.github.subapavel.jqj.bench;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JqFilter;
import io.github.subapavel.jqj.JqTermination;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.OutputOptions;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.bench.Gates.Discard;
import io.github.subapavel.jqj.bench.Inputs.Size;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The recursion, {@code limit}, {@code ..} and cap diagnostics of spec 2b and the analyses gain (#119; ADR-0019 and its
 * #119 amendment, ADR-0006, ADR-0011, GLOSSARY "Analyses gain"), with Gates' ADR-0019 settings. Its operation is
 * {@link Gates#once} under {@link Limits#embedded()}, in compact output except G6's pretty cell; a cap cell compiles
 * its filter in each operation too. A termination ends an operation without failing it: it is the cell's outcome,
 * which {@link #setUp} prints on a line {@code # outcome <id>: …}.
 *
 * <p>A cell is {@link #mode} and {@link #cell}. Its ADR-0024 id puts the mode before the size, in lower case:
 * {@code recur-T.1e6} under CONSERVATIVE is {@code recur-T.conservative.1e6}. The mode reaches the engine by the
 * reflective hook of ADR-0020's #119 amendment ({@link #engine}), on the class path only. {@link #main} writes the
 * documents and filters {@code bench/jq-ref.sh} times.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(
    value = 2,
    jvmArgs = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@State(Scope.Thread)
public class Diagnostics {

  /** The compile mode of the cell. */
  @Param({"OPTIMISED", "CONSERVATIVE"})
  public CompileMode mode;

  /**
   * The cell without its mode: Table A's recursion, {@code limit} and {@code ..} cells, Table B's cap cells, then
   * Gates' cells but G7 for Table C. G4 runs at 10⁴ only (ADR-0022).
   */
  @Param({
    "recur-T.1e6", "recur-E.1e6", "recur-Eu.1e6", "recur-M.1e6", "recur-N.1e6",
    "limit-iter.1e6", "limit-recurse.1e6",
    "dotdot.1k", "dotdot.100k", "dotdot.10m", "dotdot-deep.9999", "dotdot-deep.1k",
    "cap-inline.1k", "cap-inline-over.1k", "cap-specialise.1k", "cap-specialise-over.1k",
    "G1.1k", "G1.100k", "G1.10m", "G2.1k", "G2.100k", "G2.10m", "G3.100k", "G3.10m", "G4.1e4",
    "G6.compact.10m", "G6.pretty.10m"
  })
  public String cell;

  private final Discard out = new Discard();
  private JqEngine engine;
  /** The cell's filter, which a cap cell compiles in each operation; package-private, so a test can witness that. */
  String text;
  private boolean compiles;
  private JqFilter filter;
  private Workspace workspace;
  private MemorySegment input;
  private OutputOptions options;
  private JqTermination termination;

  /** A cell's filter, its document, made only when the cell runs, its output options, and whether it times the compile. */
  record Cell(String text, Supplier<byte[]> document, OutputOptions options, boolean compiles) {}

  /** Builds the engine of {@link #mode}, compiles the cell, and prints the outcome of one operation. */
  @Setup
  public void setUp() {
    prepare(cell(cell));
    System.out.println("# outcome " + id(cell, mode) + ": " + outcome());
  }

  /** The cell {@code c} under {@link #mode}, warmed by one operation. */
  void prepare(Cell c) {
    engine = engine(mode);
    text = c.text();
    compiles = c.compiles();
    filter = engine.compile(text);
    workspace = engine.newWorkspace();
    input = MemorySegment.ofArray(c.document().get());
    options = c.options();
    operation();
  }

  /**
   * ADR-0019's operation, after the compile in a cap cell. Returns the bytes written so far, or after a termination
   * its exit code, which JMH consumes.
   */
  @Benchmark
  public long operation() {
    try {
      return Gates.once(compiles ? engine.compile(text) : filter, workspace, input, options, out);
    } catch (JqTermination t) {
      termination = t;
      return t.exitCode();
    }
  }

  /** The cell's outcome: the termination that ended an operation so far, or else the bytes written so far. */
  String outcome() {
    if (termination != null) {
      return "termination " + termination.cause() + ": " + termination.getMessage();
    }
    return out.written + " bytes written";
  }

  /** The ADR-0024 id of {@code cell} under {@code mode}: the mode in lower case before the size. */
  static String id(String cell, CompileMode mode) {
    int size = cell.lastIndexOf('.');
    return cell.substring(0, size) + "." + mode.name().toLowerCase(Locale.ROOT) + cell.substring(size);
  }

  /**
   * The cell {@code id} of {@link #cell}.
   *
   * @throws IllegalArgumentException if there is no such cell
   */
  static Cell cell(String id) {
    OutputOptions compact = OutputOptions.compact();
    return switch (id) {
      case "recur-T.1e6" -> new Cell(T, () -> Inputs.ints(INTS_1E6), compact, false);
      case "recur-E.1e6" -> new Cell(E, () -> Inputs.ints(INTS_1E6), compact, false);
      case "recur-Eu.1e6" -> new Cell(EU, () -> Inputs.ints(INTS_1E6), compact, false);
      case "recur-M.1e6" -> new Cell(M, () -> Inputs.ints(INTS_1E6), compact, false);
      case "recur-N.1e6" -> new Cell(N, () -> Inputs.ints(INTS_1E6), compact, false);
      case "limit-iter.1e6" -> new Cell(LIMIT_ITER, () -> Inputs.ints(INTS_1E6), compact, false);
      case "limit-recurse.1e6" -> new Cell(LIMIT_RECURSE, () -> Inputs.ints(INTS_1E6), compact, false);
      case "dotdot.1k" -> new Cell(DOTDOT, () -> Inputs.orders(Size.K1), compact, false);
      case "dotdot.100k" -> new Cell(DOTDOT, () -> Inputs.orders(Size.K100), compact, false);
      case "dotdot.10m" -> new Cell(DOTDOT, () -> Inputs.orders(Size.M10), compact, false);
      case "dotdot-deep.9999" -> new Cell(DOTDOT, () -> Inputs.deep(DEEP_9999), compact, false);
      case "dotdot-deep.1k" -> new Cell(DOTDOT, () -> Inputs.deep(Size.K1), compact, false);
      case "cap-inline.1k" -> new Cell(inlineChain(INLINE_K), () -> Inputs.ints(Size.K1), compact, true);
      case "cap-inline-over.1k" -> new Cell(inlineChain(INLINE_K + 1), () -> Inputs.ints(Size.K1), compact, true);
      case "cap-specialise.1k" -> new Cell(specialiseTree(SPECIALISE_M), () -> Inputs.ints(Size.K1), compact, true);
      case "cap-specialise-over.1k" -> new Cell(specialiseTree(SPECIALISE_M + 1), () -> Inputs.ints(Size.K1), compact, true);
      case "G1.1k" -> new Cell(".a.b.c", () -> Inputs.orders(Size.K1), compact, false);
      case "G1.100k" -> new Cell(".a.b.c", () -> Inputs.orders(Size.K100), compact, false);
      case "G1.10m" -> new Cell(".a.b.c", () -> Inputs.orders(Size.M10), compact, false);
      case "G2.1k" -> new Cell(Gates.G2, () -> Inputs.orders(Size.K1), compact, false);
      case "G2.100k" -> new Cell(Gates.G2, () -> Inputs.orders(Size.K100), compact, false);
      case "G2.10m" -> new Cell(Gates.G2, () -> Inputs.orders(Size.M10), compact, false);
      case "G3.100k" -> new Cell(Gates.G3, () -> Inputs.recs(Size.K100), compact, false);
      case "G3.10m" -> new Cell(Gates.G3, () -> Inputs.recs(Size.M10), compact, false);
      case "G4.1e4" -> new Cell(Gates.G4, () -> Inputs.ints(Inputs.INTS_1E4), compact, false);
      case "G6.compact.10m" -> new Cell(".", () -> Inputs.orders(Size.M10), compact, false);
      case "G6.pretty.10m" -> new Cell(".", () -> Inputs.orders(Size.M10), OutputOptions.pretty(), false);
      default -> throw new IllegalArgumentException("no diagnostic cell " + id);
    };
  }

  /**
   * An engine with {@link Limits#embedded()} that compiles in {@code mode}: the hook sets the mode only, through the
   * package-private {@code JqEngine.Builder.compileMode(CompileMode)}, and {@link #checked} reads it back.
   *
   * @throws IllegalStateException when the switch is not there or fails, or the engine compiles in another mode
   */
  static JqEngine engine(CompileMode mode) {
    JqEngine.Builder builder = JqEngine.builder().limits(Limits.embedded());
    Method compileMode = method(JqEngine.Builder.class, "compileMode", CompileMode.class);
    return checked(((JqEngine.Builder) invoke(compileMode, builder, mode)).build(), mode);
  }

  /**
   * {@code engine}, once the package-private {@code JqEngine.compileMode()} reads back {@code mode}.
   *
   * @throws IllegalStateException when it reads another mode, or the read fails
   */
  static JqEngine checked(JqEngine engine, CompileMode mode) {
    Object read = invoke(method(JqEngine.class, "compileMode"), engine);
    if (read != mode) {
      throw new IllegalStateException("the engine compiles " + read + ", not " + mode);
    }
    return engine;
  }

  /**
   * The declared method {@code name} of {@code owner}, made accessible.
   *
   * @throws IllegalStateException when there is none
   */
  static Method method(Class<?> owner, String name, Class<?>... parameters) {
    try {
      Method m = owner.getDeclaredMethod(name, parameters);
      m.setAccessible(true);
      return m;
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("the diagnostics cannot reach " + owner.getName() + "." + name, e);
    }
  }

  /**
   * {@code m} on {@code target} with {@code args}.
   *
   * @throws IllegalStateException when the call fails
   */
  private static Object invoke(Method m, Object target, Object... args) {
    try {
      return m.invoke(target, args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("the compile-mode switch " + m.getName() + " failed", e);
    }
  }

  /**
   * The prefix of every recursion shape (owner's ruling on #119, 2026-11-16): the input's elements as a built array
   * {@code $a}, its length as {@code $L}. Spike A bound the input itself, but {@code $a[i]} on a document array costs i
   * ticks (ADR-0034, F-111), which made every shape quadratic and ended each 1e5 and 1e6 cell on the step budget.
   */
  static final String PREFIX = "[.[]] as $a | ($a|length) as $L | ";

  /** T, Spike A's self tail call: state {@code [i, acc]}, the call the last thing the function does. */
  static final String T =
      PREFIX + "def s: if .[0] == $L then .[1] else [.[0] + 1, .[1] + $a[.[0]]] | s end; [0, 0] | s";

  /** The run E and Eu share: the sum of {@code $a} over the results of {@code r}. */
  private static final String SUM_OF_R = "reduce (0 | r(if . < $L - 1 then . + 1 else empty end)) as $i (0; . + $a[$i])";

  /** E, Spike A's self call once per result of {@code f}, jq's own {@code recurse/1} shape. */
  static final String E = PREFIX + "def r(f): def rr: ., (f | rr); rr; " + SUM_OF_R;

  /**
   * Eu, E with permuted parameters: no parameter is invariant, so the optimised compile makes no copy (ADR-0006) and
   * the call stays RECURSIVE.
   */
  static final String EU = PREFIX + "def r(f): def rr(g; h): ., (g | rr(h; g)); rr(f; f); " + SUM_OF_R;

  /** M, Spike A's two functions calling each other, both in tail position. */
  static final String M =
      PREFIX
          + "def ev: def od: if .[0] == $L then .[1] else [.[0] + 1, .[1] + $a[.[0]]] | ev end;"
          + " if .[0] == $L then .[1] else [.[0] + 1, .[1] + $a[.[0]]] | od end; [0, 0] | ev";

  /** N, Spike A's non-tail recursion: the addition waits for the recursive result. */
  static final String N = PREFIX + "def s: if . == $L then 0 else $a[.] + (. + 1 | s) end; 0 | s";

  /** {@code limit} over an iteration. */
  static final String LIMIT_ITER = "[limit(length - 1; .[])] | length";

  /** {@code limit} over a recursion. */
  static final String LIMIT_RECURSE = "last(limit(length; 0 | recurse(. + 1)))";

  /** {@code ..}, on orders and on {@code deep}. */
  static final String DOTDOT = "[..] | length";

  /**
   * The largest k whose {@link #inlineChain} {@code explain()} shows fully inlined, with no function, measured on
   * 9052c29: k + 1 lifts every {@code f<i>}.
   */
  static final int INLINE_K = 15;

  /**
   * The largest m whose {@link #specialiseTree} {@code explain()} shows with {@code refused=0}, measured on 9052c29:
   * each {@code until} copy holds 10 nodes, so the m closures of the tree and the long {@code until} hold 16 380 of the
   * cap's 16 384, and at m + 1 the long {@code until}, the last key, is refused and calls the original. jq-ref rejects
   * the tree from 2047 closures (rc 3), so m + 1 stays at most 2046.
   */
  static final int SPECIALISE_M = 1_637;

  /** The element count of the 1e6 cells. */
  static final int INTS_1E6 = 1_000_000;

  /** The levels of {@code dotdot-deep.9999}, one below the nesting depth 10 000 of {@code Limits.embedded()}. */
  static final int DEEP_9999 = 9_999;

  /** The doubling chain of {@code k} levels: {@code def f1: . + 1; def f2: f1 | f1; …; 0 | fk}. */
  static String inlineChain(int k) {
    StringBuilder text = new StringBuilder("def f1: . + 1; ");
    for (int i = 2; i <= k; i++) {
      text.append("def f").append(i).append(": f").append(i - 1).append(" | f").append(i - 1).append("; ");
    }
    return text.append("0 | f").append(k).toString();
  }

  /**
   * {@code [P_m] | length + (0 | until(. >= 100000; . + 1))}, where {@code P_m} is a balanced comma tree of the
   * {@code m} distinct closures {@code (0 | until(. >= 1; . + i))}, {@code i} from 1 to {@code m}: each is a key of its
   * own for the specialiser, and the long {@code until} after them is the last key, the one refused past the cap.
   */
  static String specialiseTree(int m) {
    StringBuilder text = new StringBuilder("[");
    tree(text, 1, m);
    return text.append("] | length + (0 | until(. >= 100000; . + 1))").toString();
  }

  /**
   * Writes what {@code bench/jq-ref.sh} times for Tables A and B into the directory {@code args[0]}, which it creates:
   * the documents {@code ints-1e6.json}, {@code ints-1k.json}, {@code deep-9999.json} and {@code deep-1k.json}, and
   * each filter as {@code <name>.jq}, with no LF.
   *
   * @throws IOException if a file cannot be written
   */
  public static void main(String[] args) throws IOException {
    Path dir = Files.createDirectories(Path.of(args[0]));
    Files.write(dir.resolve("ints-1e6.json"), Inputs.ints(INTS_1E6));
    Files.write(dir.resolve("ints-1k.json"), Inputs.ints(Size.K1));
    Files.write(dir.resolve("deep-9999.json"), Inputs.deep(DEEP_9999));
    Files.write(dir.resolve("deep-1k.json"), Inputs.deep(Size.K1));
    String[][] filters = {
      {"recur-T", T}, {"recur-E", E}, {"recur-Eu", EU}, {"recur-M", M}, {"recur-N", N},
      {"limit-iter", LIMIT_ITER}, {"limit-recurse", LIMIT_RECURSE}, {"dotdot", DOTDOT},
      {"cap-inline", inlineChain(INLINE_K)}, {"cap-inline-over", inlineChain(INLINE_K + 1)},
      {"cap-specialise", specialiseTree(SPECIALISE_M)}, {"cap-specialise-over", specialiseTree(SPECIALISE_M + 1)}
    };
    for (String[] f : filters) {
      Files.writeString(dir.resolve(f[0] + ".jq"), f[1], StandardCharsets.US_ASCII);
    }
  }

  /** The balanced comma tree of the closures {@code from} to {@code to}, each in parentheses. */
  private static void tree(StringBuilder text, int from, int to) {
    if (from == to) {
      text.append("(0 | until(. >= 1; . + ").append(from).append("))");
      return;
    }
    int middle = (from + to) >>> 1;
    text.append('(');
    tree(text, from, middle);
    text.append(", ");
    tree(text, middle + 1, to);
    text.append(')');
  }
}
