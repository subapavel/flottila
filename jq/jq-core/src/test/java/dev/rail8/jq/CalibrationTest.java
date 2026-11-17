package dev.rail8.jq;

import static dev.rail8.jq.CalibrationCell.SHAPES;
import static dev.rail8.jq.CalibrationCell.STACK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.CalibrationCell.Shape;
import dev.rail8.jq.CalibrationCell.Units;
import dev.rail8.jq.query.compile.CompileMode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The stack calibration of #113 (ADR-0046 and its #111, #113 and #116 amendments with the #113 "Rulings on the red",
 * ADR-0011, ADR-0020, F-55, F-30, F-93, F-95) in fresh, agent-free child JVMs ({@link ChildJvm}, {@link
 * CalibrationCell}). A cell is one shape × tier × thread × compile mode. A cell switches before the backstop when its
 * shape, with the thread's own size declared as {@code stackBytes}, reaches the trampoline at {@code U_f} before the
 * real stack runs out, and so gives reference jq's result; the backstop is the STACK termination.
 *
 * <p>The ticket gate runs the {@code -Xint} 512 KiB column, both modes, each shape once at the committed constants as
 * the first run of its own fresh child ({@link #everyCellSwitchesBeforeTheBackstop}). The {@code calibration-matrix}
 * tag (the spec gate and nightly) runs every cell: {@code -Xint} and C1 on platform 256 KiB, 512 KiB and 1 MiB and on
 * virtual threads, warm on the last three, × 2 modes × the 14 {@link CalibrationCell#SHAPES}, 308 cells. The constants are measured in {@code -Xint} and C1, proven in warm (the adviser's K1): a
 * warm search runs while the JIT compiles, so its edge is not the thread's. An {@code -Xint} cell's {@code U*} is its
 * cold edge, one bisection over fresh children, each probe the first run of its own, with the linked edge, after two
 * linking runs, beside it; a C1 cell's is the smallest of 3 warmed children. A child's search, C1's and the linked one,
 * repeats from its own result until two consecutive passes agree on {@code U*}, and a cell with a child that has no
 * such fixed point after {@value CalibrationCell#PASSES} passes is INVALID (the adviser's L1). At {@code U* + 1} a cell
 * ends STACK after a prefix of its reference results (L3, {@link #stackAfterAPrefix}). A warm cell is a check: in each
 * of {@value #WARM_CHILDREN} fresh children of the default tier, the shape with the thread's own size declared gives
 * reference jq's result in each of {@value #WARM_RUNS} consecutive runs, from the child's first. The matrix writes
 * {@code target/calibration.tsv}, the record {@link CalibrationRecordTest} reads once it is committed, and appends to
 * {@code target/calibration-progress.log} a line when a child or a cold search starts, and each child's rows as it
 * ends, every line led by its ISO-8601 UTC instant.
 *
 * <p>Each cell proves its thread's size (D2): the plain recursion count of its thread, against the count of the next
 * platform thread up of its tier (the 1 MiB thread against 512 KiB, a virtual one against 1 MiB), must be 1.5 to 3.5
 * times smaller (the deep-stack measurements: {@code -Xint} 4 772 frames at
 * 512 KiB and 10 730 at 1 MiB; a 512 KiB thread on a cached larger stack reached 10 729). The probe's recursion is
 * excluded from compilation in every child, so a JIT tier's count is stable. A cell whose size is not proven is
 * INVALID, never a pass.
 */
class CalibrationTest {
  private static final long KIB = 1024;

  /** The thread of the ticket column, and of a virtual cell's child ({@code -Xss512k}). */
  private static final long SMALL = 512 * KIB;

  /** The other platform thread of the matrix. */
  private static final long LARGE = 1024 * KIB;

  /** The lowest platform thread of the {@code -Xint} and C1 cells, the lower one of the lowest pair. */
  private static final long SMALLEST = 256 * KIB;

  /**
   * The shapes whose control, declaring 1 GiB, ends STACK on a thread of at most 1 MiB, so no cell of theirs is flat
   * and c2 cannot pass empty: #111's four (measure-b.sh ended each STACK at {@code U_f} 40 000) and F-95's descent.
   */
  private static final Set<String> NEVER_FLAT = Set.of("collect", "reduce", "first-or", "hold", "collect-descent", "plain-descent");

  /** A result longer than this prints as its length and sha256 in rows and progress lines. */
  private static final int SHOWN = 200;

  /** Two slopes of a pair agree within the rounding of {@code b}, 8 B. */
  private static final long SLOPES_AGREE = 8;

  /** The fresh default-tier children of a warm cell (K1). */
  static final int WARM_CHILDREN = 3;

  /** The consecutive runs of a warm child, from its first, each with the thread's own size declared (K1). */
  static final int WARM_RUNS = 60;

  /** Every child: the proof's recursion is never compiled (the rulings' warm proof). */
  private static final List<String> PROOF_FLAGS = List.of("-XX:CompileCommand=quiet", "-XX:CompileCommand=exclude,dev.rail8.jq.CalibrationCell$Probe::down");

  /** A tier of the matrix and its JVM flags. */
  record Tier(String name, List<String> flags) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static final Tier XINT = new Tier("xint", List.of("-Xint"));

  private static final Tier C1 = new Tier("c1", List.of("-Xbatch", "-XX:TieredStopAtLevel=1"));

  private static final Tier WARM = new Tier("warm", List.of());

  private static final List<Tier> TIERS = List.of(XINT, C1, WARM);

  /** A thread of the matrix: platform 256 KiB, 512 KiB and 1 MiB, and a virtual thread on a child of {@code -Xss512k}. */
  record CellThread(String kind, long bytes) {
    String xss() {
      return "-Xss" + bytes / KIB + "k";
    }

    @Override
    public String toString() {
      return kind + " " + bytes / KIB + "KiB";
    }
  }

  /** The platform threads of the matrix, smallest first: each proves against the next one up. */
  private static final List<Long> PLATFORM = List.of(SMALLEST, SMALL, LARGE);

  /**
   * The threads of an {@code -Xint} or C1 cell: platform 256 KiB, 512 KiB and 1 MiB, and virtual;
   * {@link CalibrationRecordTest#MEASURED_THREADS} names them as the record does.
   */
  private static final List<CellThread> MEASURED_THREADS = List.of(new CellThread("platform", SMALLEST), new CellThread("platform", SMALL), new CellThread("platform", LARGE), new CellThread("virtual", SMALL));

  /** The threads of a warm cell, the last three of {@link #MEASURED_THREADS}: no warm 256 KiB cell. */
  private static final List<CellThread> WARM_THREADS = MEASURED_THREADS.subList(1, 4);

  /**
   * A cold edge: the first probe's row (its constants and control), the edge ({@code -1} flat, {@code -2} fails at
   * {@code U_f} 0), the end at the edge + 1, and every probe child's proof.
   */
  record Cold(String[] first, long edge, String next, List<Long> proofs) {}

  @TempDir Path dir;

  static Stream<Tier> tiers() {
    return TIERS.stream();
  }

  /**
   * c2 (ADR-0020 as renamed by ADR-0046 #113, K1), the ticket gate's column: on {@code -Xint}, a 512 KiB launcher main
   * thread, in each mode, every shape with 512 KiB declared gives reference jq's result at the committed constants as
   * the first run of its own fresh child, so it reached the trampoline before the backstop. Each cell's thread is
   * proven, and each control, declaring 1 GiB on the same thread after it, ends STACK, or completes for a flat shape;
   * {@link #NEVER_FLAT} must end STACK. Writes {@code target/calibration-column.tsv}.
   */
  @Test
  void everyCellSwitchesBeforeTheBackstop() throws Exception {
    long started = System.nanoTime();
    Map<String, String> printed = new LinkedHashMap<>();
    String reference;
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      Future<String> large = pool.submit(child(XINT.flags(), new CellThread("platform", LARGE), "proof", "CONSERVATIVE"));
      Map<String, Future<String>> column = new LinkedHashMap<>();
      for (CompileMode mode : CompileMode.values()) {
        for (Shape shape : SHAPES) {
          column.put(mode + " " + shape.id(), pool.submit(child(XINT.flags(), new CellThread("platform", SMALL), "check", mode.name(), shape.id())));
        }
      }
      reference = get(large);
      for (Map.Entry<String, Future<String>> c : column.entrySet()) {
        printed.put(c.getKey(), get(c.getValue()));
      }
    }
    long frames1MiB = proofOf(reference);
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    List<String> tsv = new ArrayList<>();
    tsv.add("# check\ttier\tkind\tthread\tmode\tshape\tD_f\tT\tb\treserve\tU_f\tend\tcontrol\tproof\tproof 1 MiB");
    for (CompileMode mode : CompileMode.values()) {
      for (Shape shape : SHAPES) {
        String out = printed.get(mode + " " + shape.id());
        String cell = "xint platform 512KiB " + mode + " " + shape.id();
        long frames = proofOf(out);
        got.add(cell + " proof: " + proofVerdict(frames, frames1MiB));
        want.add(cell + " proof: proven");
        String[] r = rows(out, "check").get(shape.id());
        if (r == null) {
          got.add(cell + ": no row in " + out);
          want.add(cell + ": " + shape.completed());
          continue;
        }
        got.add(cell + ": " + r[8]);
        want.add(cell + ": " + shape.completed());
        got.add(cell + " control: " + controlVerdict(shape, r[9]));
        want.add(cell + " control: " + (NEVER_FLAT.contains(shape.id()) ? STACK : "STACK or flat"));
        tsv.add(String.join("\t", "check", "xint", "platform", Long.toString(SMALL), mode.name(), shape.id(), r[3], r[4], r[5], r[6], r[7], shown(r[8]), shown(r[9]), Long.toString(frames), Long.toString(frames1MiB)));
      }
    }
    tsv.addAll(environment(started));
    write("calibration-column.tsv", tsv);
    assertThat(got)
        .as("the -Xint 512 KiB column at the committed constants: each cell's proof, each shape's end with 512 KiB declared, the first run of its own fresh child, and its control declaring 1 GiB")
        .isEqualTo(want);
  }

  /**
   * D2 (F-93): a cell proves its thread's size, in each tier. The plain recursion count of a {@code -Xss512k} main
   * thread is 1.5 to 3.5 times smaller than a {@code -Xss1m} one's, and a 1 MiB stack under a 512 KiB cell's label (the
   * cached-stack case, 10 729 frames against 10 730) is INVALID. Two fresh {@code -Xss512k} children of the tier count
   * the same frames, the probe's recursion never compiled, so the count is the thread's, not the run's. A {@code
   * -Xss256k} main thread's count is 1.5 to 3.5 times smaller than the {@code -Xss512k} one's, so a 256 KiB cell can
   * prove against the next thread up (a side probe counted 1641 against 4371 on {@code -Xint}).
   */
  @ParameterizedTest
  @MethodSource("tiers")
  void cellsProveTheirThreadSize(Tier tier) throws Exception {
    long small = proofOf(ChildJvm.run(dir, flags(tier, new CellThread("platform", SMALL)), CalibrationCell.class, "proof", "platform", Long.toString(SMALL), "CONSERVATIVE"));
    long again = proofOf(ChildJvm.run(dir, flags(tier, new CellThread("platform", SMALL)), CalibrationCell.class, "proof", "platform", Long.toString(SMALL), "CONSERVATIVE"));
    long large = proofOf(ChildJvm.run(dir, flags(tier, new CellThread("platform", LARGE)), CalibrationCell.class, "proof", "platform", Long.toString(LARGE), "CONSERVATIVE"));
    long smallest = proofOf(ChildJvm.run(dir, flags(tier, new CellThread("platform", SMALLEST)), CalibrationCell.class, "proof", "platform", Long.toString(SMALLEST), "CONSERVATIVE"));
    assertThat(List.of(proofVerdict(small, large), proofVerdict(large, large), Long.toString(again), proofVerdict(smallest, small)))
        .as(
            "%s: a 512 KiB main thread's proof (%d frames) against 1 MiB (%d frames); 1 MiB under a 512 KiB label; a second fresh 512 KiB child's count; a 256 KiB main thread's proof (%d frames) against 512 KiB",
            tier,
            small,
            large,
            smallest)
        .isEqualTo(List.of("proven", "INVALID " + large + " frames against " + large + " on 1 MiB", Long.toString(small), "proven"));
  }

  /**
   * Each platform cell proves against the next thread up, the 1 MiB one, the top, against 512 KiB, and a virtual cell
   * against 1 MiB; {@link #proven} over each cell's proofs. On {@code -Xint} counts: 1641 frames at 256 KiB (a side
   * probe of the 256 KiB thread), 4371 at 512 KiB, 9833 at 1 MiB and 4354 on the virtual thread (the committed
   * record). Each cell is proven; a 512 KiB stack under a 256 KiB label (the
   * cached-stack case of D2) is INVALID, which the 1 MiB thread cannot see (4371 against 9833 is in the band). A 1 MiB
   * stack under the 512 KiB label fails that cell and the two that prove against it, 256 KiB and 1 MiB; one under the
   * virtual label fails the virtual cell.
   */
  @Test
  void aCellProvesAgainstTheNextThreadUp() {
    assertThat(List.of(ladder(1641, 4371, 9833, 4354), ladder(4371, 4371, 9833, 4354), ladder(1641, 9833, 9833, 4354), ladder(1641, 4371, 9833, 9833)))
        .as("the proofs of -Xint W CONSERVATIVE cells on 256 KiB, 512 KiB, 1 MiB and virtual, by their frames")
        .isEqualTo(
            List.of(
                "platform 256KiB proven, platform 512KiB proven, platform 1024KiB proven, virtual 512KiB proven",
                "platform 256KiB INVALID, platform 512KiB proven, platform 1024KiB proven, virtual 512KiB proven",
                "platform 256KiB INVALID, platform 512KiB INVALID, platform 1024KiB INVALID, virtual 512KiB proven",
                "platform 256KiB proven, platform 512KiB proven, platform 1024KiB proven, virtual 512KiB INVALID"));
  }

  /** Each of an {@code -Xint} W CONSERVATIVE cell's four threads, proven or INVALID, given each one's proof. */
  private static String ladder(long frames256KiB, long frames512KiB, long frames1MiB, long framesVirtual) {
    List<CellThread> threads = List.of(new CellThread("platform", SMALLEST), new CellThread("platform", SMALL), new CellThread("platform", LARGE), new CellThread("virtual", SMALL));
    List<Long> frames = List.of(frames256KiB, frames512KiB, frames1MiB, framesVirtual);
    Shape w = shape("W");
    Map<String, List<Long>> proofs = new HashMap<>();
    for (int i = 0; i < threads.size(); i++) proofs.put(key(XINT, threads.get(i), CompileMode.CONSERVATIVE) + " " + w.id(), List.of(frames.get(i)));
    return String.join(", ", threads.stream().map(t -> t + " " + (proven(proofs, XINT, t, CompileMode.CONSERVATIVE, w) ? "proven" : "INVALID")).toList());
  }

  /**
   * The 14th shape, after the Collect-heavy {@code collect-descent}, is the PLAIN-call filter {@code plain-descent}: IrDepthTest's {@code nested(30_001)}, 300 lifted functions over PLAIN calls past the IR
   * bound, on {@code null}, giving {@link IrDepthTest#NESTED_30001}, reference jq's {@code over-cap/depth-30001}. It is
   * {@link #NEVER_FLAT}.
   */
  @Test
  void theFourteenthShapeIsIrDepthTestsFilter() {
    assertThat(SHAPES.stream().map(Shape::id).toList())
        .as("the ids of CalibrationCell.SHAPES")
        .isEqualTo(List.of("T", "E", "M", "N", "W", "C", "ND", "recurse", "collect", "reduce", "first-or", "hold", "collect-descent", "plain-descent"));
    Shape plain = SHAPES.getLast();
    assertThat(List.of(plain.filter().equals(IrDepthTest.nested(30_001)), plain.json(), plain.result().equals(IrDepthTest.NESTED_30001), NEVER_FLAT.contains(plain.id())))
        .as("plain-descent: its filter is IrDepthTest's nested(30_001) (%d chars), its input, its result is NESTED_30001 (%d chars), it is NEVER_FLAT", plain.filter().length(), plain.result().length())
        .isEqualTo(List.of(true, "null", true, true));
  }

  /**
   * A calibration child compiles each cell's filter on one long-lived helper thread with a large stack and runs it on the cell's thread, so no cell is edgeless for its
   * compile. On a 256 KiB thread, plain-descent's {@code -Xint} cold probe prints a probe row whose control ends STACK,
   * and C1's W CONSERVATIVE search, after its warm-up runs (which C1-compile the Parser), finds a numeric {@code U*} with
   * its control ending STACK; neither child prints a {@code compile} row. A {@code compile} verdict is wanted of no cell,
   * plain-descent's 256 KiB ones included, so a {@code compile} row anywhere fails the matrix.
   */
  @Test
  void everyCellCompilesOnTheHelperThread() throws Exception {
    CellThread smallest = new CellThread("platform", SMALLEST);
    String xint = ChildJvm.run(dir, flags(XINT, smallest), CalibrationCell.class, "probe", "platform", Long.toString(SMALLEST), "CONSERVATIVE", "plain-descent", "-", "control");
    String c1 = ChildJvm.run(dir, flags(C1, smallest), CalibrationCell.class, "search", "platform", Long.toString(SMALLEST), "CONSERVATIVE", "W", "warm");
    String[] probe = rows(xint, "probe").get("plain-descent");
    String[] cell = rows(c1, "cell").get("W");
    assertThat(
            List.of(
                "compile rows " + rows(xint, "compile").size() + ", probe control " + (probe == null ? "no probe row" : probe[9]),
                "compile rows " + rows(c1, "compile").size() + ", U* " + (cell == null ? "no cell row" : cell[7].matches("\\d+") ? "numeric" : cell[7]) + ", control " + (cell == null ? "-" : cell[9]),
                expected("compile threw java.lang.StackOverflowError"),
                expected("rows from 0 children, cold no probe row in compile\tOPTIMISED\tplain-descent\tjava.lang.StackOverflowError"),
                expected("flat")))
        .as("a 256 KiB -Xint probe child of plain-descent and C1 search child of W, and the want of cells with a compile verdict:%n%s%n%s", xint, c1)
        .isEqualTo(List.of("compile rows 0, probe control " + STACK, "compile rows 0, U* numeric, control " + STACK, "switches", "switches", "flat"));
  }

  /**
   * A virtual cell waits for the helper's compile without parking, spinning on {@code isDone} with {@code
   * Thread.onSpinWait}, so it measures a virtual thread that has never unmounted (one that unmounts comes back with only
   * its top frames on the carrier); a platform cell keeps the parked {@code join()}. In-process: while the helper holds
   * a compile open, the cell's thread is sampled {@value #SAMPLES} times, a platform one once it has parked; a virtual
   * one is only ever RUNNABLE, a platform one WAITING. A state cannot tell a spin from a yield, which leaves the thread
   * RUNNABLE too: {@link #aVirtualCellKeepsItsCarrierWhileTheHelperCompiles} can.
   */
  @Test
  void aVirtualCellSpinsOnTheHelperAndAPlatformCellParks() throws Exception {
    List<String> got = new ArrayList<>();
    for (boolean virtual : new boolean[] {true, false}) {
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      Runnable cell =
          () ->
              CalibrationCell.onHelper(
                  () -> {
                    entered.countDown();
                    try {
                      release.await();
                    } catch (InterruptedException e) {
                      throw new IllegalStateException(e);
                    }
                    return "compiled";
                  });
      Thread thread = virtual ? Thread.ofVirtual().unstarted(cell) : Thread.ofPlatform().unstarted(cell);
      thread.start();
      entered.await();
      // join() may spin a little before it parks: a platform cell is sampled once it has parked.
      if (!virtual) awaitWaiting(thread);
      Set<Thread.State> states = new TreeSet<>();
      for (int i = 0; i < SAMPLES; i++) {
        states.add(thread.getState());
        Thread.onSpinWait();
      }
      release.countDown();
      thread.join();
      got.add((virtual ? "virtual" : "platform") + " cell waiting on the helper: " + states);
    }
    assertThat(got)
        .as("the states of a cell's thread while the helper compiles")
        .isEqualTo(List.of("virtual cell waiting on the helper: [RUNNABLE]", "platform cell waiting on the helper: [WAITING]"));
  }

  /**
   * A virtual cell keeps its carrier while it waits for the helper: in a child JVM with one carrier thread ({@link
   * HelperWaitChild}), a probe virtual thread started during the wait runs only after the cell is done. The two
   * controls show that the probe sees a cell that gives up the carrier: one that waits in a {@code Thread.yield()} loop
   * and one that parks in {@code join()} each let the probe run during the wait.
   */
  @Test
  void aVirtualCellKeepsItsCarrierWhileTheHelperCompiles() throws Exception {
    List<String> flags = List.of("-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1");
    List<String> got = new ArrayList<>();
    for (String wait : List.of("helper", "yield", "park")) got.add(ChildJvm.run(dir, flags, HelperWaitChild.class, wait).strip());
    assertThat(got)
        .as("what the probe saw while a virtual cell waited for a compile, on one carrier thread")
        .isEqualTo(List.of("helper: the probe waited for the cell", "yield: the probe ran while the cell waited", "park: the probe ran while the cell waited"));
  }

  /**
   * A row or progress line shows a shape's result longer than {@value #SHOWN} characters as its length and sha256, and
   * leaves shorter text and the text around it as it is. plain-descent's result is 20 021 characters, its sha256 taken
   * outside the JVM.
   */
  @Test
  void aLongResultIsShownByItsLengthAndDigest() throws Exception {
    String plain = SHAPES.getLast().result();
    assertThat(List.of(shown("[" + plain + "], ran out"), shown("[10000], ran out"), shown("x".repeat(SHOWN + 1))))
        .as("a row's end with plain-descent's result, one with a short result, and long text that is no shape's result")
        .isEqualTo(List.of("[20021 chars, sha256 cc5975208ff57874762a95ab46af1e98f938231dca460e3593e95da3c24ddabf], ran out", "[10000], ran out", "x".repeat(SHOWN + 1)));
  }

  /** The samples of a cell's thread state while the helper holds its compile open. */
  private static final int SAMPLES = 10_000;

  /** The spins {@link #awaitWaiting} allows a platform cell's {@code join()} before it must have parked. */
  private static final int PARK_SPINS = 100_000_000;

  /** Spins until {@code thread} is WAITING; an AssertionError if it is not within {@value #PARK_SPINS} spins. */
  private static void awaitWaiting(Thread thread) {
    for (int i = 0; i < PARK_SPINS; i++) {
      if (thread.getState() == Thread.State.WAITING) return;
      Thread.onSpinWait();
    }
    throw new AssertionError("the platform cell's thread did not park within " + PARK_SPINS + " spins: " + thread.getState());
  }

  /** What the matrix wants of a measured cell given its verdict: it switches, or it is flat; never a compile verdict. */
  static String expected(String verdict) {
    return verdict.equals("flat") ? "flat" : "switches";
  }

  /**
   * The environment rows of a record are {@code time-ms}, {@code jdk}, {@code cpus}, and
   * {@code machine}, {@code $RATCHET_MACHINE} or {@code -} when it is unset or empty, {@code cpu}, the text after the
   * colon of the first {@code model name} line of {@code /proc/cpuinfo}, stripped ({@code -} without one), and {@code
   * kernel}, {@code os.version}; each row a key and a value, one tab between them.
   */
  @Test
  void environmentNamesTheMachine() throws IOException {
    List<String> model = List.of("processor\t: 0", "model name\t: X ", "model name\t: Y");
    List<String> noModel = List.of("processor\t: 0", "vendor_id\t: GenuineIntel");
    assertThat(environment(System.nanoTime()).stream().map(l -> l.split("\t", -1).length + " fields " + l.substring(0, l.indexOf('\t'))).toList())
        .as("the environment rows of this run, each a key and a value")
        .isEqualTo(List.of("2 fields time-ms", "2 fields jdk", "2 fields cpus", "2 fields machine", "2 fields cpu", "2 fields kernel"));
    assertThat(List.of(machineRows(null, model, "6.1.0-a"), machineRows("", noModel, "6.1.0-b"), machineRows("box-1", model, "6.1.0-c"), machineRows("box-2", List.of(), "6.1.0-d")))
        .as("the machine, cpu and kernel rows: machine unset, empty and set; cpuinfo with a model name line, without one, empty")
        .isEqualTo(
            List.of(
                List.of("machine\t-", "cpu\tX", "kernel\t6.1.0-a"),
                List.of("machine\t-", "cpu\t-", "kernel\t6.1.0-b"),
                List.of("machine\tbox-1", "cpu\tX", "kernel\t6.1.0-c"),
                List.of("machine\tbox-2", "cpu\t-", "kernel\t6.1.0-d")));
  }

  /**
   * K8: {@code -Xss} sizes a virtual thread's carrier, a {@code ForkJoinWorkerThread} of stack size 0. On {@code
   * -Xint} a virtual thread on a {@code -Xss512k} child holds the frames of the child's main thread within a tenth
   * (the adviser: 4 751 against 4 771), and on a {@code -Xss1m} child it holds 1.5 to 3.5 times more, so it is proven
   * as a 512 KiB cell.
   */
  @Test
  void virtualCarrierIsTheChildStack() throws Exception {
    long main = proofOf(ChildJvm.run(dir, flags(XINT, new CellThread("platform", SMALL)), CalibrationCell.class, "proof", "platform", Long.toString(SMALL), "CONSERVATIVE"));
    long virtual = proofOf(ChildJvm.run(dir, flags(XINT, new CellThread("virtual", SMALL)), CalibrationCell.class, "proof", "virtual", Long.toString(SMALL), "CONSERVATIVE"));
    long virtualLarge = proofOf(ChildJvm.run(dir, flags(XINT, new CellThread("virtual", LARGE)), CalibrationCell.class, "proof", "virtual", Long.toString(LARGE), "CONSERVATIVE"));
    assertThat(List.of(10 * Math.abs(virtual - main) <= main ? "within a tenth" : "off by " + (virtual - main), proofVerdict(virtual, virtualLarge)))
        .as("-Xint: a virtual thread's frames on -Xss512k (%d) against the main thread's (%d), and against a virtual thread on -Xss1m (%d)", virtual, main, virtualLarge)
        .isEqualTo(List.of("within a tenth", "proven"));
  }

  /**
   * D4 (K5): the 10 000-deep native check on the {@code -Xint} 512 KiB column, each native's first use in its own
   * child at the deepest emit, with 512 KiB declared: reference jq's values (diff/deep.rec deep/native-*).
   */
  @Test
  void nativesCoverTheDeepestEmit() throws Exception {
    Map<String, Future<String>> runs = new LinkedHashMap<>();
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
      for (CompileMode mode : CompileMode.values()) {
        for (Shape n : CalibrationCell.NATIVES) {
          runs.put(mode + " " + n.id(), pool.submit(child(XINT.flags(), new CellThread("platform", SMALL), "native", mode.name(), n.id())));
          want.add("xint platform 512KiB " + mode + " " + n.id() + ": " + n.completed());
        }
      }
      for (Map.Entry<String, Future<String>> r : runs.entrySet()) {
        String id = r.getKey().substring(r.getKey().indexOf(' ') + 1);
        String[] row = rows(get(r.getValue()), "native").get(id);
        got.add("xint platform 512KiB " + r.getKey() + ": " + (row == null ? "no row" : row[3]));
      }
    }
    assertThat(got).as("the natives at the deepest emit, 512 KiB declared on a 512 KiB -Xint main thread").isEqualTo(want);
  }

  /**
   * K1: one wrong end fails a warm cell, and its verdict names the child and the run: the cold first run, a middle one
   * and the last of 60, and a child one run short. A cell missing a child fails, as does a NEVER_FLAT control that
   * completes; a control that completes is allowed for any other shape (flat). In-process, on children's text as
   * {@link CalibrationCell}'s {@code warm} action prints it: W with 512 KiB declared, {@code U_f} 3995 at b 96 and
   * reserve 128 KiB.
   */
  @Test
  void aWarmStackNamesItsChildAndRun() {
    Shape w = SHAPES.stream().filter(s -> s.id().equals("W")).findFirst().orElseThrow();
    Shape collect = SHAPES.stream().filter(s -> s.id().equals("collect")).findFirst().orElseThrow();
    String good = warmText(w, 60, 0, STACK);
    List<String> got =
        List.of(
            warmVerdict(w, List.of(good, good, good)),
            warmVerdict(w, List.of(warmText(w, 60, 1, STACK), good, good)),
            warmVerdict(w, List.of(good, warmText(w, 60, 17, STACK), good)),
            warmVerdict(w, List.of(good, good, warmText(w, 60, 60, STACK))),
            warmVerdict(w, List.of(good, warmText(w, 59, 0, STACK), good)),
            warmVerdict(w, List.of(good, good)),
            warmVerdict(w, List.of(good, good, warmText(w, 60, 0, "[10000], ran out"))),
            warmVerdict(collect, List.of(warmText(collect, 60, 0, STACK), warmText(collect, 60, 0, STACK), warmText(collect, 60, 0, "[1], ran out"))));
    assertThat(got)
        .as("the warm verdicts of W and collect")
        .isEqualTo(
            List.of(
                "3 children × 60 runs at the thread's size: [10000], ran out",
                "child 1 run 1 at U_f 3995: [], termination STACK",
                "child 2 run 17 at U_f 3995: [], termination STACK",
                "child 3 run 60 at U_f 3995: [], termination STACK",
                "child 2: 59 runs of 60",
                "rows from 2 children",
                "3 children × 60 runs at the thread's size: [10000], ran out",
                "child 3 control: [1], ran out"));
  }

  /** A warm child's text: its proof, {@code runs} runs of which run {@code stackAt} ends STACK (none at 0), a control. */
  private static String warmText(Shape shape, int runs, int stackAt, String control) {
    return warmText(shape, runs, stackAt, STACK, control);
  }

  /** A warm child's text: its proof, {@code runs} runs of which run {@code badAt} ends {@code bad} (none at 0), a control. */
  private static String warmText(Shape shape, int runs, int badAt, String bad, String control) {
    StringBuilder out = new StringBuilder("proof\tplatform\t524288\t4371\n");
    for (int run = 1; run <= runs; run++) {
      out.append(String.join("\t", "warm", "CONSERVATIVE", shape.id(), "101", "5", "96", "131072", "3995", Integer.toString(run), run == badAt ? bad : shape.completed())).append('\n');
    }
    return out.append(String.join("\t", "warm", "CONSERVATIVE", shape.id(), "101", "5", "96", "131072", "3995", "control", control)).append('\n').toString();
  }

  /**
   * L3: at {@code U* + 1} a measured cell ends STACK after a prefix of its shape's reference results, in order, empty
   * or full, in both tiers ({@code -Xint}'s cold end, each C1 child's end). For first-or that is {@code [],
   * termination STACK} or {@code [7], termination STACK} and nothing else; for W, {@code [10000]} is the full prefix.
   * Red: an end that is no STACK ({@code [7], ran out}, another termination, a throw) or results that are no prefix
   * ({@code [8]}, {@code [7, 7]}, W's {@code [1000]} and {@code [7]}). Unchanged and red: a control that is not plain
   * STACK, and any STACK in a warm cell. In-process, on rows as {@link CalibrationCell} prints them, with the numbers
   * of the K5 run at impl/113 078dd0a ({@code .logs/113-calibration-k5.tsv}): CONSERVATIVE on 512 KiB, b 192, reserve
   * 286 720; first-or D_f 17 ({@code U_f} 1220), {@code -Xint} cold {@code U*} 4730, linked 4790, C1 4430; W D_f 101
   * ({@code U_f} 1136), C1 {@code U*} 2242.
   */
  @Test
  void anEdgeEndsStackAfterAPrefixOfTheReferenceResults() {
    Shape firstOr = shape("first-or");
    Shape w = shape("W");
    List<String> got = new ArrayList<>();
    for (String next : List.of("[], termination STACK", "[7], termination STACK", "[7], ran out", "[], termination BUDGET", "[7], termination BUDGET", "[8], termination STACK", "[7, 7], termination STACK", "threw java.lang.StackOverflowError")) {
      got.add("xint " + next + ": " + provenVerdict(XINT, SMALL, firstOr, List.<String[]>of(searchRow(firstOr, 17, "4790", "[], termination STACK", STACK, "4790,4790")), xintCold(firstOr, 4730, next)));
    }
    for (String next : List.of("[], termination STACK", "[7], termination STACK", "[7], ran out", "[7], termination BUDGET", "[8], termination STACK", "[7, 7], termination STACK")) {
      got.add("c1 " + next + ": " + provenVerdict(C1, SMALL, firstOr, c1Rows(firstOr, 17, "4430", next), null));
    }
    for (String next : List.of("[10000], termination STACK", "[1000], termination STACK", "[7], termination STACK")) {
      got.add("c1 W " + next + ": " + provenVerdict(C1, SMALL, w, c1Rows(w, 101, "2242", next), null));
    }
    String flatRow = String.join("\t", "cell", "CONSERVATIVE", "first-or", "17", "5", "192", "286720", "-", "-", "[7], termination STACK", "-");
    got.add("c1 control: " + provenVerdict(C1, SMALL, firstOr, List.of(flatRow.split("\t"), flatRow.split("\t"), flatRow.split("\t")), null));
    String good = warmText(firstOr, 60, 0, STACK);
    got.add("warm: " + warmVerdict(firstOr, List.of(good, warmText(firstOr, 60, 5, "[7], termination STACK", STACK), good)));
    assertThat(got)
        .as("the verdicts of first-or and W cells by their end at U* + 1, and first-or's control and warm runs")
        .isEqualTo(
            List.of(
                "xint [], termination STACK: switches",
                "xint [7], termination STACK: switches",
                "xint [7], ran out: U*+1 ends [7], ran out",
                "xint [], termination BUDGET: U*+1 ends [], termination BUDGET",
                "xint [7], termination BUDGET: U*+1 ends [7], termination BUDGET",
                "xint [8], termination STACK: U*+1 ends [8], termination STACK",
                "xint [7, 7], termination STACK: U*+1 ends [7, 7], termination STACK",
                "xint threw java.lang.StackOverflowError: U*+1 ends threw java.lang.StackOverflowError",
                "c1 [], termination STACK: switches",
                "c1 [7], termination STACK: switches",
                "c1 [7], ran out: U*+1 ends [[], termination STACK, [7], ran out, [], termination STACK]",
                "c1 [7], termination BUDGET: U*+1 ends [[], termination STACK, [7], termination BUDGET, [], termination STACK]",
                "c1 [8], termination STACK: U*+1 ends [[], termination STACK, [8], termination STACK, [], termination STACK]",
                "c1 [7, 7], termination STACK: U*+1 ends [[], termination STACK, [7, 7], termination STACK, [], termination STACK]",
                "c1 W [10000], termination STACK: switches",
                "c1 W [1000], termination STACK: U*+1 ends [[], termination STACK, [1000], termination STACK, [], termination STACK]",
                "c1 W [7], termination STACK: U*+1 ends [[], termination STACK, [7], termination STACK, [], termination STACK]",
                "c1 control: control [7], termination STACK",
                "warm: child 2 run 5 at U_f 3995: [7], termination STACK"));
  }

  /**
   * L3 in {@link #edgeIsExact}: its expectation for a line accepts, after {@code U*}'s reference result, an end at
   * {@code U* + 1} that is STACK after a prefix of the shape's reference results, and wants {@code [], termination
   * STACK} for any other. {@code U*} must still give the reference result, a NEVER_FLAT shape may not be flat, and a
   * flat shape's line stands. On the K5 run's {@code -Xint} cold edges: first-or 4730, W 3574 (CONSERVATIVE).
   */
  @Test
  void edgeIsExactAcceptsStackAfterAPrefix() {
    Shape firstOr = shape("first-or");
    String at = "CONSERVATIVE first-or at U* 4730: ";
    List<String> lines =
        List.of(
            at + "[7], ran out | [7], termination STACK",
            at + "[7], ran out | [], termination STACK",
            at + "[7], ran out | [7], ran out",
            at + "[7], ran out | [8], termination STACK",
            at + "[7], ran out | [7, 7], termination STACK",
            at + "[7], ran out | [7], termination BUDGET",
            at + "[7], termination STACK | [7], termination STACK",
            "CONSERVATIVE first-or: flat");
    List<String> got = new ArrayList<>();
    for (String line : lines) got.add(exactEdgeWant(CompileMode.CONSERVATIVE, firstOr, line));
    got.add(exactEdgeWant(CompileMode.OPTIMISED, shape("C"), "OPTIMISED C: flat"));
    got.add(exactEdgeWant(CompileMode.CONSERVATIVE, shape("W"), "CONSERVATIVE W at U* 3574: [10000], ran out | [10000], termination STACK"));
    got.add(exactEdgeWant(CompileMode.CONSERVATIVE, shape("W"), "CONSERVATIVE W at U* 3574: [10000], ran out | [7], termination STACK"));
    assertThat(got)
        .as("edgeIsExact's expectation for each line")
        .isEqualTo(
            List.of(
                at + "[7], ran out | [7], termination STACK",
                at + "[7], ran out | [], termination STACK",
                at + "[7], ran out | [], termination STACK",
                at + "[7], ran out | [], termination STACK",
                at + "[7], ran out | [], termination STACK",
                at + "[7], ran out | [], termination STACK",
                at + "[7], ran out | [7], termination STACK",
                "CONSERVATIVE first-or: [7], ran out | [], termination STACK",
                "OPTIMISED C: flat",
                "CONSERVATIVE W at U* 3574: [10000], ran out | [10000], termination STACK",
                "CONSERVATIVE W at U* 3574: [10000], ran out | [], termination STACK"));
  }

  /**
   * L1: a search repeats from its own result until two consecutive passes agree on {@code U*}; after {@value
   * CalibrationCell#PASSES} passes without agreement its {@code U*} is INVALID. A pass that fails at {@code U_f} 0 ends
   * the search. In-process, {@link CalibrationCell}'s own search over {@link ScriptedRuns}, which give each pass's
   * scripted edge, one more than the search may use: the first run of each pass (the seed, then the {@code U*} of the
   * pass before), the run of the end, and the row's {@code U*}, end and passes. A seed that is the edge still takes two
   * passes (the steady child); 5817 to 5818 and back is a C1 edge found while C1 compiled (the adviser's L1).
   */
  @Test
  void aSearchRepeatsFromItsOwnResultUntilTwoPassesAgree() {
    assertThat(
            List.of(
                fixedPointOf(5818, 5818, 5818, 5818),
                fixedPointOf(5817, 5818, 5818, 5818),
                fixedPointOf(5817, 5818, 5817, 5817, 5817),
                fixedPointOf(2131, 2138, 2162, 2131, 2131, 2131),
                fixedPointOf(5113, 5114, 5121, 5152, 5113, 5113),
                fixedPointOf(4430, 4430, -1, -1),
                fixedPointOf(0, -1, -1)))
        .as("the first run of each pass, the end's run, and the row's U*, end and passes")
        .isEqualTo(
            List.of(
                "starts [5818, 5818], end at [5819]: 5818 | [], termination STACK | 5818,5818",
                "starts [5817, 5818], end at [5819]: 5818 | [], termination STACK | 5818,5818",
                "starts [5817, 5818, 5817], end at [5818]: 5817 | [], termination STACK | 5818,5817,5817",
                "starts [2131, 2138, 2162, 2131], end at [2132]: 2131 | [], termination STACK | 2138,2162,2131,2131",
                "starts [5113, 5114, 5121, 5152], end at [5114]: INVALID | [], termination STACK | 5114,5121,5152,5113",
                "starts [4430, 4430], end at [0]: fails | [], termination STACK | 4430,fails",
                "starts [0], end at [0]: fails | [], termination STACK | fails"));
  }

  /**
   * The architect's ruling (a) on t/113-fixpoint: every run of a search, each pass's and the end's at {@code U* + 1},
   * starts at the call depth of 078dd0a's search, whose every run was a call of {@code ended} from {@code search}. So
   * each is as deep as the control's run, which {@code search} calls first, and each is called from {@code search}. On
   * the second case of {@link #aSearchRepeatsFromItsOwnResultUntilTwoPassesAgree} (seed 5817, edge 5818): the control at
   * 1 GiB, pass 1 at 5817, 5818, 5820 and 5819, pass 2 at 5818 and 5819, the end at 5819.
   */
  @Test
  void everyRunOfASearchStartsAtItsControlsDepth() {
    ScriptedRuns runs = new ScriptedRuns(shape("first-or"), 5818, 5818, 5818);
    searched(shape("first-or"), 5817, runs);
    int control = runs.callers.getFirst().size();
    List<String> got = new ArrayList<>();
    for (int i = 0; i < runs.callers.size(); i++) {
      List<String> callers = runs.callers.get(i);
      got.add(runs.at.get(i) + ": " + (callers.size() - control) + " frames deeper than the control, " + String.join(" < ", callers.subList(0, 3)));
    }
    String direct = ": 0 frames deeper than the control, ended < search < searched";
    assertThat(got)
        .as("each run of the search: its declared stack, its depth against the control's, and its first three frames")
        .isEqualTo(Stream.of(CalibrationCell.GIB, 5817L, 5818L, 5820L, 5819L, 5818L, 5819L, 5819L).map(at -> at + direct).toList());
  }

  /** {@link CalibrationCell}'s search of first-or from {@code seed} over {@link ScriptedRuns} of {@code edges}. */
  private static String fixedPointOf(long seed, long... edges) {
    Shape firstOr = shape("first-or");
    ScriptedRuns runs = new ScriptedRuns(firstOr, edges);
    String[] r = searched(firstOr, seed, runs);
    List<Long> starts = runs.passes.subList(0, runs.passes.size() - 1).stream().map(List::getFirst).toList();
    return "starts " + starts + ", end at " + runs.passes.getLast() + ": " + r[7] + " | " + r[8] + " | " + r[10];
  }

  /**
   * The {@code cell} row of {@link CalibrationCell}'s search of {@code shape} from {@code seed}, in-process, its runs
   * stood in for by {@code runs}, at b 1, reserve 0 and D_f 0, so a run's {@code stackBytes} is its {@code U_f}.
   */
  private static String[] searched(Shape shape, long seed, ScriptedRuns runs) {
    PrintStream out = System.out;
    ByteArrayOutputStream printed = new ByteArrayOutputStream();
    CalibrationCell.standIn = runs;
    try {
      System.setOut(new PrintStream(printed, true, UTF_8));
      CalibrationCell.search(shape, SMALL, CompileMode.CONSERVATIVE, new Units(0, 5, 1, 0), seed);
    } finally {
      System.setOut(out);
      CalibrationCell.standIn = null;
    }
    return rows(printed.toString(UTF_8), "cell").get(shape.id());
  }

  /**
   * A stand-in for the runs of one search ({@link CalibrationCell#standIn}). The control, at 1 GiB, ends STACK. Any
   * other run completes when its {@code U_f} is at most the current pass's scripted edge. A pass's edge holds until the
   * search has closed its bracket: a {@code U_f} that completed and {@code U_f + 1} that did not, or, for an edge of -1
   * that never completes, {@code U_f} 0 after the pass's first run. A pass past the script throws. It keeps each run's
   * declared stack and callers, and each pass's runs; the list after the last pass's holds the end's run.
   */
  private static final class ScriptedRuns implements CalibrationCell.Run {
    private final Shape shape;
    private final long[] edges;
    final List<Long> at = new ArrayList<>();
    final List<List<String>> callers = new ArrayList<>();
    final List<List<Long>> passes = new ArrayList<>();
    private long completed = Long.MIN_VALUE;
    private long stacked = Long.MAX_VALUE;

    ScriptedRuns(Shape shape, long... edges) {
      this.shape = shape;
      this.edges = edges;
      passes.add(new ArrayList<>());
    }

    @Override
    public String end(long stackBytes, List<String> frames) {
      at.add(stackBytes);
      callers.add(frames);
      if (stackBytes == CalibrationCell.GIB) return STACK;
      long edge = edges[passes.size() - 1];
      List<Long> pass = passes.getLast();
      pass.add(stackBytes);
      boolean completes = stackBytes <= edge;
      if (completes) completed = Math.max(completed, stackBytes);
      else stacked = Math.min(stacked, stackBytes);
      if (completed + 1 == stacked || (edge < 0 && stackBytes == 0 && pass.size() > 1)) {
        passes.add(new ArrayList<>());
        completed = Long.MIN_VALUE;
        stacked = Long.MAX_VALUE;
      }
      return completes ? shape.completed() : STACK;
    }
  }

  /**
   * L1 in the matrix: a C1 child whose search found no fixed point prints {@code INVALID} for {@code U*} and its passes,
   * and the cell is INVALID, naming the child and the passes, even when the other children agree or every child is
   * INVALID; a child that moved and then agreed counts. The {@code -Xint} linked search shares the path, so an
   * {@code -Xint} cell whose linked search has no fixed point is INVALID too. First-or CONSERVATIVE on 512 KiB, the K5
   * run's numbers as in {@link #anEdgeEndsStackAfterAPrefixOfTheReferenceResults}.
   */
  @Test
  void aCellWithoutAFixedPointIsInvalid() {
    Shape firstOr = shape("first-or");
    String[] agreed = searchRow(firstOr, 17, "4430", STACK, STACK, "4430,4430");
    String[] moved = searchRow(firstOr, 17, "4430", STACK, STACK, "4431,4430,4430");
    String[] unstable = searchRow(firstOr, 17, "INVALID", STACK, STACK, "4431,4438,4445,4452");
    String[] linked = searchRow(firstOr, 17, "INVALID", STACK, STACK, "4790,4797,4811,4839");
    assertThat(
            List.of(
                provenVerdict(C1, SMALL, firstOr, List.of(agreed, moved, agreed), null),
                provenVerdict(C1, SMALL, firstOr, List.of(agreed, unstable, moved), null),
                provenVerdict(C1, SMALL, firstOr, List.of(unstable, unstable, unstable), null),
                provenVerdict(XINT, SMALL, firstOr, List.<String[]>of(linked), xintCold(firstOr, 4730, STACK))))
        .as("the verdicts of first-or cells whose children agree, or one or all have no fixed point, and of an -Xint cell whose linked search has none")
        .isEqualTo(
            List.of(
                "switches",
                "INVALID child 2: U* has no fixed point in passes 4431,4438,4445,4452",
                "INVALID child 1: U* has no fixed point in passes 4431,4438,4445,4452",
                "INVALID child 1: U* has no fixed point in passes 4790,4797,4811,4839"));
  }

  /** Each line of the progress log starts with the ISO-8601 UTC instant it was written at, then a tab. */
  @Test
  void progressLinesStartWithTheirInstant() {
    Instant at = Instant.parse("2026-11-15T09:30:00.250Z");
    assertThat(
            List.of(
                stamped(at, "xint platform 512KiB CONSERVATIVE first-or cold probe 4731: [7], termination STACK"),
                stamped(at, "c1 platform 512KiB CONSERVATIVE W\nproof\tplatform\t524288\t4371\ncell\tCONSERVATIVE\tW\t101\t5\t192\t286720\t2242\t[], termination STACK\t[], termination STACK\t2242,2242")))
        .as("the progress log's text for a cold probe line and for a child's block")
        .isEqualTo(
            List.of(
                "2026-11-15T09:30:00.250Z\txint platform 512KiB CONSERVATIVE first-or cold probe 4731: [7], termination STACK\n",
                "2026-11-15T09:30:00.250Z\tc1 platform 512KiB CONSERVATIVE W\n"
                    + "2026-11-15T09:30:00.250Z\tproof\tplatform\t524288\t4371\n"
                    + "2026-11-15T09:30:00.250Z\tcell\tCONSERVATIVE\tW\t101\t5\t192\t286720\t2242\t[], termination STACK\t[], termination STACK\t2242,2242\n"));
  }

  private static Shape shape(String id) {
    return SHAPES.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  /** A search's {@code cell} row as {@link CalibrationCell} prints it, CONSERVATIVE, T 5, b 192, reserve 286 720. */
  private static String[] searchRow(Shape shape, long dF, String edge, String next, String control, String passes) {
    return new String[] {"cell", "CONSERVATIVE", shape.id(), Long.toString(dF), "5", "192", "286720", edge, next, control, passes};
  }

  /** Three C1 children that agree on {@code edge}; the second ends {@code next} at the edge + 1, the others STACK. */
  private static List<String[]> c1Rows(Shape shape, long dF, String edge, String next) {
    String passes = edge + "," + edge;
    return List.of(searchRow(shape, dF, edge, STACK, STACK, passes), searchRow(shape, dF, edge, next, STACK, passes), searchRow(shape, dF, edge, STACK, STACK, passes));
  }

  /** An {@code -Xint} cold edge at 512 KiB, D_f 17: its first probe completes at the edge, which + 1 ends {@code next}. */
  private static Cold xintCold(Shape shape, long edge, String next) {
    String[] first = {"probe", "CONSERVATIVE", shape.id(), "17", "5", "192", "286720", Long.toString(edge), shape.completed(), STACK};
    return new Cold(first, edge, next, List.of(4371L));
  }

  /**
   * c2 over the full matrix (the {@code calibration-matrix} tag), at the committed constants, measured in {@code -Xint}
   * and C1, proven in warm (K1). Every cell is proven. An {@code -Xint} or C1 cell's {@code U_f} at the thread's size is
   * at most its edge, the cold one for {@code -Xint}, where the edge + 1 ends STACK after a prefix of the reference
   * results ({@link #provenVerdict}, L3); a flat cell, whose control completes, has no edge. A child compiles on its
   * helper thread, so a child that prints a {@code compile} row in place of its rows fails its cell. The cold and linked
   * slopes of each {@code -Xint} pair agree within {@value #SLOPES_AGREE} B. A C1 cell's {@code U*} is the smallest of
   * 3 fresh warmed children, each child's search at its fixed point (L1), and the spread is recorded (D3). A warm cell
   * holds when each of its {@value #WARM_CHILDREN} children gives reference jq's result in each of its {@value
   * #WARM_RUNS} runs at the thread's size ({@link #warmVerdict}); one STACK fails the test, naming the cell, the child
   * and the run. {@code -Xint} and C1 children run 4 at a time, warm children one at a time after them; each search
   * starts from the committed record's edge when there is one, and a cell new to the record searches unseeded. The
   * record holds per {@code -Xint} and C1 cell {@code b_cell}, {@code b_cold}, the intercept and the cold term, each
   * from {@link CalibrationRecordTest#derived} (adjacent-pair slopes, the lowest pair's intercepts), and
   * per warm cell the check's verdict in the {@code end} column; a result longer than {@value #SHOWN} characters prints
   * as {@link #shown}. Its {@code max-b} and {@code reserve} rows are {@link CalibrationRecordTest#maxima} of the {@code
   * -Xint} and C1 rows. A fresh run asserts one way (K7): its {@code b} and reserve are at most the constants.
   */
  @Test
  @Tag("calibration-matrix")
  void matrixCellsSwitchBeforeTheBackstop() throws Exception {
    long started = System.nanoTime();
    Map<String, String[]> record = committedRecord();
    Map<String, Cold> colds = new LinkedHashMap<>();
    Map<String, List<String>> printed = new LinkedHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      Map<String, Future<Cold>> coldRuns = new LinkedHashMap<>();
      Map<String, List<Future<String>>> runs = new LinkedHashMap<>();
      for (CellThread thread : MEASURED_THREADS) {
        for (CompileMode mode : CompileMode.values()) {
          for (Shape shape : SHAPES) {
            String cell = key(XINT, thread, mode) + " " + shape.id();
            coldRuns.put(cell, pool.submit(() -> cold(XINT, thread, mode, shape, seed(record, cell, 10))));
            runs.computeIfAbsent(cell, k -> new ArrayList<>()).add(pool.submit(logged(cell + " linked", child(XINT.flags(), thread, "linked", mode.name(), shape.id(), seedArg(record, cell, 11)))));
            String c1 = key(C1, thread, mode) + " " + shape.id();
            for (int rep = 0; rep < 3; rep++) {
              runs.computeIfAbsent(c1, k -> new ArrayList<>()).add(pool.submit(logged(c1, child(C1.flags(), thread, "search", mode.name(), shape.id(), "warm", seedArg(record, c1, 11)))));
            }
          }
        }
      }
      for (Map.Entry<String, Future<Cold>> c : coldRuns.entrySet()) {
        colds.put(c.getKey(), get(c.getValue()));
      }
      for (Map.Entry<String, List<Future<String>>> r : runs.entrySet()) {
        for (Future<String> f : r.getValue()) {
          printed.computeIfAbsent(r.getKey(), k -> new ArrayList<>()).add(get(f));
        }
      }
    }
    for (CellThread thread : WARM_THREADS) {
      for (CompileMode mode : CompileMode.values()) {
        for (Shape shape : SHAPES) {
          String cell = key(WARM, thread, mode) + " " + shape.id();
          for (int rep = 0; rep < WARM_CHILDREN; rep++) {
            printed.computeIfAbsent(cell, k -> new ArrayList<>()).add(logged(cell, child(WARM.flags(), thread, "warm", mode.name(), shape.id(), Integer.toString(WARM_RUNS))).call());
          }
        }
      }
    }
    long guard = guardBytes();
    Units committed = CalibrationCell.units(CallRuns.F, CompileMode.OPTIMISED);
    Map<String, List<String[]>> cells = new LinkedHashMap<>();
    Map<String, List<Long>> proofs = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> p : printed.entrySet()) {
      for (String out : p.getValue()) {
        proofs.computeIfAbsent(p.getKey(), k -> new ArrayList<>()).add(proofOf(out));
        String[] r = rows(out, "cell").get(p.getKey().substring(p.getKey().lastIndexOf(' ') + 1));
        if (r != null) cells.computeIfAbsent(p.getKey(), k -> new ArrayList<>()).add(r);
      }
    }
    for (Map.Entry<String, Cold> c : colds.entrySet()) proofs.computeIfAbsent(c.getKey(), k -> new ArrayList<>()).addAll(c.getValue().proofs());
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    List<String> tsv = new ArrayList<>();
    tsv.add("# The calibration record (ADR-0046 #113), written by CalibrationTest.matrixCellsSwitchBeforeTheBackstop; never edited by hand.");
    tsv.add("# cell\ttier\tkind\tthread\tmode\tshape\tD_f\tT\tb\treserve\tU*cold\tU*linked\tspread\tend(U*+1)\tcontrol\tproof\tb_cell\tb_cold\tintercept\tcold");
    List<String[]> recordRows = new ArrayList<>();
    List<String> recordCells = new ArrayList<>();
    for (Tier tier : TIERS) {
      for (CellThread thread : tier == WARM ? WARM_THREADS : MEASURED_THREADS) {
        for (CompileMode mode : CompileMode.values()) {
          for (Shape shape : SHAPES) {
            String cell = key(tier, thread, mode) + " " + shape.id();
            recordCells.add(cell);
            if (tier == WARM) {
              // K1: a check, never a measure; its row has no edge, slope, intercept or cold term.
              List<String> children = printed.getOrDefault(cell, List.of());
              String verdict = warmVerdict(shape, children);
              if (children.size() >= WARM_CHILDREN && !proven(proofs, tier, thread, mode, shape)) verdict = invalid(proofs, tier, thread, mode, shape);
              got.add(cell + ": " + verdict);
              want.add(cell + ": " + warmHolds(shape));
              List<String[]> warm = children.isEmpty() ? List.of() : warmRows(children.getFirst(), shape.id());
              String[] first = warm.isEmpty() ? new String[10] : warm.getFirst();
              String control = warm.stream().filter(r -> r[8].equals("control")).map(r -> r[9]).findFirst().orElse("-");
              recordRows.add(new String[] {"cell", tier.name(), thread.kind(), Long.toString(thread.bytes()), mode.name(), shape.id(), first[3], first[4], first[5], first[6], "-", "-", "-", verdict, control, String.valueOf(proofs.get(cell)), "-", "-", "-", "-"});
              continue;
            }
            List<String[]> rs = cells.getOrDefault(cell, List.of());
            Cold cold = colds.get(cell);
            long linked = edge(rs);
            long coldEdge = cold == null ? -1 : cold.edge();
            String[] first = rs.isEmpty() ? (cold == null || cold.first() == null ? new String[10] : cold.first()) : rs.getFirst();
            String verdict;
            if (rs.size() < (tier == XINT ? 1 : 3) || (tier == XINT && (cold == null || cold.first() == null))) {
              verdict = "rows from " + rs.size() + " children" + (cold == null ? "" : ", cold " + cold.next());
            } else if (!proven(proofs, tier, thread, mode, shape)) {
              verdict = invalid(proofs, tier, thread, mode, shape);
            } else {
              verdict = provenVerdict(tier, thread.bytes(), shape, rs, cold);
            }
            got.add(cell + ": " + verdict);
            want.add(cell + ": " + expected(verdict));
            recordRows.add(
                new String[] {
                  "cell", tier.name(), thread.kind(), Long.toString(thread.bytes()), mode.name(), shape.id(), first[3], first[4], first[5], first[6], tier == XINT ? Long.toString(coldEdge) : "-", Long.toString(linked), Long.toString(spread(rs)), tier == XINT && cold != null ? cold.next() : String.valueOf(first[8]), String.valueOf(first[9]), String.valueOf(proofs.get(cell)), "-", "-", "-", "-"
                });
          }
        }
      }
    }
    // Every number of the derived columns comes from the helper that maxima reads, and the cold and
    // linked slopes of each -Xint pair agree.
    List<CalibrationRecordTest.Derived> derived = CalibrationRecordTest.derived(recordRows);
    for (int i = 0; i < recordRows.size(); i++) {
      String[] row = recordRows.get(i);
      CalibrationRecordTest.Derived d = derived.get(i);
      if (row[1].equals(XINT.name()) && d.pairSlope().isPresent()) {
        long pair = d.pairSlope().getAsLong();
        boolean agree = d.pairColdSlope().isPresent() && Math.abs(d.pairColdSlope().getAsLong() - pair) <= SLOPES_AGREE;
        got.add(recordCells.get(i) + " slopes: " + (agree ? "agree" : "cold " + field(d.pairColdSlope()) + " against linked " + pair));
        want.add(recordCells.get(i) + " slopes: agree");
      }
      row[16] = field(d.bCell());
      row[17] = field(d.bCold());
      row[18] = field(d.intercept());
      row[19] = field(d.cold());
      String[] shownRow = row.clone();
      shownRow[13] = shown(row[13]);
      shownRow[14] = shown(row[14]);
      tsv.add(String.join("\t", shownRow));
    }
    // K1: the summary rows are the maxima of the -Xint and C1 rows only, as constantsAreTheMatrixMaxima reads them.
    CalibrationRecordTest.Maxima fresh = CalibrationRecordTest.maxima(recordRows, guard);
    tsv.add("guard\t" + guard);
    tsv.add("largest-intercept\t" + fresh.largestIntercept());
    tsv.add("largest-cold\t" + fresh.largestCold());
    tsv.add("max-b\t" + fresh.bytesPerUnit());
    tsv.add("reserve\t" + fresh.reserve());
    tsv.addAll(environment(started));
    write("calibration.tsv", tsv);
    assertThat(got).as("every cell of the matrix at the committed constants (b %d, reserve %d)", committed.bytesPerUnit(), committed.reserve()).isEqualTo(want);
    assertThat(fresh.bytesPerUnit()).as("K7: the fresh b of the -Xint and C1 cells, rounded up to 8 B, against the committed b").isLessThanOrEqualTo(committed.bytesPerUnit());
    assertThat(fresh.reserve()).as("K7: the fresh reserve of the -Xint and C1 cells against the committed reserve").isLessThanOrEqualTo(committed.reserve());
  }

  /**
   * The edge is exact (the {@code calibration-matrix} tag): on {@code -Xint}, a 512 KiB main thread, in each mode, the
   * cold edge {@code U*} of a bisection over fresh children gives reference jq's result as the first run of another
   * fresh child, and {@code U* + 1} ends STACK after a prefix of the reference results as the first run of a third
   * ({@link #exactEdgeWant}, L3). The bisection starts from the committed record's {@code U*cold} where the record has
   * the cell; each line also goes to the progress log.
   */
  @Test
  @Tag("calibration-matrix")
  void edgeIsExact() throws Exception {
    CellThread thread = new CellThread("platform", SMALL);
    Map<String, String[]> record = committedRecord();
    Map<String, Future<String>> lines = new LinkedHashMap<>();
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      for (CompileMode mode : CompileMode.values()) {
        for (Shape shape : SHAPES) {
          lines.put(
              mode + " " + shape.id(),
              pool.submit(
                  () -> {
                    Cold c = cold(XINT, thread, mode, shape, seed(record, key(XINT, thread, mode) + " " + shape.id(), 10));
                    String line =
                        c.edge() < 0
                            ? mode + " " + shape.id() + ": " + (c.edge() == -1 ? "flat" : "fails at U_f 0, " + c.next())
                            : mode + " " + shape.id() + " at U* " + c.edge() + ": " + probe(XINT, thread, mode, shape, c.edge()) + " | " + probe(XINT, thread, mode, shape, c.edge() + 1);
                    progress("edge " + line);
                    return line;
                  }));
        }
      }
      for (CompileMode mode : CompileMode.values()) {
        for (Shape shape : SHAPES) {
          String line = get(lines.get(mode + " " + shape.id()));
          got.add(line);
          want.add(exactEdgeWant(mode, shape, line));
        }
      }
    }
    assertThat(got).as("the ends at U* and U* + 1 on -Xint 512 KiB, each the first run of its own fresh child").isEqualTo(want);
  }

  /**
   * The heap bytes per live call activation (the {@code calibration-matrix} tag; the rulings): each held generator of
   * {@link CalibrationCell#HELD} on n = 10⁵ and 2·10⁵, giving n twice (jq-ref), with {@code stackBytes} 0 and 512 KiB,
   * in both modes; at the first emit, with every activation live, the used heap after two collections. bytes = Δused /
   * Δn per held filter, mode and declared stack is positive, and it and {@code 4 194 304 × bytes} against the child's
   * {@code maxMemory} go to {@code target/calibration-heap.tsv}.
   */
  @Test
  @Tag("calibration-matrix")
  void heapPerActivationIsRecorded() throws Exception {
    String out = child(List.of(), new CellThread("platform", SMALL), "heap", "CONSERVATIVE,OPTIMISED").call();
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    List<String> tsv = new ArrayList<>();
    tsv.add("# heap\tfilter\tmode\tstackBytes\tbytes per activation\t4194304 x bytes\tmaxMemory");
    Map<String, Long> used = new LinkedHashMap<>();
    long max = 0;
    for (String line : out.split("\n")) {
      String[] r = line.split("\t");
      if (r[0].equals("heap")) {
        got.add(r[2] + " " + r[1] + " " + r[3] + " n " + r[4] + ": " + r[6]);
        want.add(r[2] + " " + r[1] + " " + r[3] + " n " + r[4] + ": [" + r[4] + ", " + r[4] + "], ran out");
        used.put(r[2] + " " + r[1] + " " + r[3] + " " + r[4], Long.parseLong(r[5]));
        max = Long.parseLong(r[7]);
      }
    }
    for (String held : CalibrationCell.HELD.keySet()) {
      for (CompileMode mode : CompileMode.values()) {
        for (long stackBytes : new long[] {0, SMALL}) {
          String at = held + " " + mode + " " + stackBytes;
          long bytes = (used.getOrDefault(at + " 200000", 0L) - used.getOrDefault(at + " 100000", 0L)) / 100_000;
          got.add(at + ": " + (bytes > 0 ? "positive" : bytes + " bytes"));
          want.add(at + ": positive");
          tsv.add(String.join("\t", "heap", held, mode.name(), Long.toString(stackBytes), Long.toString(bytes), Long.toString(4_194_304L * bytes), Long.toString(max)));
        }
      }
    }
    write("calibration-heap.tsv", tsv);
    assertThat(got).as("the held generators' results and the heap bytes per live activation, in:%n%s", out).isEqualTo(want);
  }

  /** The cold edge of a cell: gallop and bisection over fresh children, each probe the first run of its child. */
  private Cold cold(Tier tier, CellThread thread, CompileMode mode, Shape shape, long seed) throws Exception {
    List<Long> proofs = new ArrayList<>();
    progress(key(tier, thread, mode) + " " + shape.id() + " cold started");
    String out = child(tier.flags(), thread, "probe", mode.name(), shape.id(), seed >= 0 ? Long.toString(seed) : "-", "control").call();
    proofs.add(proofOf(out));
    String[] first = rows(out, "probe").get(shape.id());
    if (first == null) return new Cold(null, -2, "no probe row in " + out, proofs);
    if (!first[9].equals(STACK)) return new Cold(first, -1, "flat", proofs);
    Map<Long, String> ends = new HashMap<>();
    long start = Long.parseLong(first[7]);
    ends.put(start, first[8]);
    String cell = key(tier, thread, mode) + " " + shape.id();
    progress(cell + " cold probe " + start + ": " + first[8]);
    long lo;
    long hi;
    if (first[8].equals(shape.completed())) {
      lo = start;
      long step = 1;
      hi = lo + step;
      while (completes(tier, thread, mode, shape, hi, ends, proofs)) {
        lo = hi;
        step *= 2;
        hi = lo + step;
      }
    } else {
      hi = start;
      long step = 1;
      lo = Math.max(0, hi - step);
      while (lo > 0 && !completes(tier, thread, mode, shape, lo, ends, proofs)) {
        hi = lo;
        step *= 2;
        lo = Math.max(0, hi - step);
      }
      if (lo == 0 && !completes(tier, thread, mode, shape, 0, ends, proofs)) return new Cold(first, -2, ends.get(0L), proofs);
    }
    while (hi - lo > 1) {
      long mid = lo + (hi - lo) / 2;
      if (completes(tier, thread, mode, shape, mid, ends, proofs)) lo = mid;
      else hi = mid;
    }
    return new Cold(first, lo, ends.get(lo + 1), proofs);
  }

  /** Whether {@code shape} at {@code unitLimit} gives reference jq's result as the first run of a fresh child. */
  private boolean completes(Tier tier, CellThread thread, CompileMode mode, Shape shape, long unitLimit, Map<Long, String> ends, List<Long> proofs) throws Exception {
    if (!ends.containsKey(unitLimit)) {
      String out = child(tier.flags(), thread, "probe", mode.name(), shape.id(), Long.toString(unitLimit)).call();
      proofs.add(proofOf(out));
      String[] r = rows(out, "probe").get(shape.id());
      ends.put(unitLimit, r == null ? "no probe row" : r[8]);
      progress(key(tier, thread, mode) + " " + shape.id() + " cold probe " + unitLimit + ": " + ends.get(unitLimit));
    }
    return ends.get(unitLimit).equals(shape.completed());
  }

  /** The end of {@code shape} at {@code unitLimit} as the first run of a fresh child. */
  private String probe(Tier tier, CellThread thread, CompileMode mode, Shape shape, long unitLimit) throws Exception {
    String[] r = rows(child(tier.flags(), thread, "probe", mode.name(), shape.id(), Long.toString(unitLimit)).call(), "probe").get(shape.id());
    return r == null ? "no probe row" : r[8];
  }

  /**
   * A child of {@code tier}'s flags on {@code thread}, running {@code action} with the further args. A linked, search or
   * warm child, which only the matrix starts, has {@value ChildJvm#MATRIX_SECONDS} s; every other {@value
   * ChildJvm#SECONDS} s.
   */
  private Callable<String> child(List<String> tierFlags, CellThread thread, String action, String... more) {
    List<String> args = new ArrayList<>(List.of(action, thread.kind(), Long.toString(thread.bytes())));
    args.addAll(List.of(more));
    List<String> flags = new ArrayList<>(tierFlags);
    flags.addAll(PROOF_FLAGS);
    flags.add(thread.xss());
    long seconds = action.equals("linked") || action.equals("search") || action.equals("warm") ? ChildJvm.MATRIX_SECONDS : ChildJvm.SECONDS;
    return () -> ChildJvm.run(dir, seconds, flags, CalibrationCell.class, args.toArray(String[]::new));
  }

  /** {@code child}, a line in the progress log when it starts and its output appended when it ends. */
  private Callable<String> logged(String cell, Callable<String> child) {
    return () -> {
      progress(cell + " started");
      String out = child.call();
      progress(cell + "\n" + out.strip());
      return out;
    };
  }

  private static synchronized void progress(String text) throws IOException, NoSuchAlgorithmException {
    Path target = Path.of(System.getProperty("basedir", "."), "target");
    Files.createDirectories(target);
    Files.writeString(target.resolve("calibration-progress.log"), stamped(Instant.now(), shown(text)), UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  /**
   * The progress log's lines for {@code text}, written at {@code at}: each line starts with the ISO-8601 UTC instant and
   * a tab, so the time per child and per tier can be measured.
   */
  static String stamped(Instant at, String text) {
    StringBuilder out = new StringBuilder();
    for (String line : text.split("\n")) out.append(at).append('\t').append(line).append('\n');
    return out.toString();
  }

  private static List<String> flags(Tier tier, CellThread thread) {
    List<String> flags = new ArrayList<>(tier.flags());
    flags.addAll(PROOF_FLAGS);
    flags.add(thread.xss());
    return flags;
  }

  private static String key(Tier tier, CellThread thread, CompileMode mode) {
    return tier.name() + " " + thread + " " + mode;
  }

  /** The committed record's cell rows by {@code <tier> <thread> <mode> <shape>}, or none before it is committed. */
  private static Map<String, String[]> committedRecord() throws IOException {
    Map<String, String[]> rows = new HashMap<>();
    try (InputStream in = CalibrationTest.class.getResourceAsStream(CalibrationRecordTest.RECORD)) {
      if (in == null) return rows;
      for (String line : new String(in.readAllBytes(), UTF_8).split("\n")) {
        String[] r = line.split("\t");
        if (r[0].equals("cell") && r.length > 11) rows.put(r[1] + " " + r[2] + " " + Long.parseLong(r[3]) / KIB + "KiB " + r[4] + " " + r[5], r);
      }
    }
    return rows;
  }

  /** The committed record's edge in {@code column} for {@code cell}, the search's seed, or -1. */
  private static long seed(Map<String, String[]> record, String cell, int column) {
    String[] r = record.get(cell);
    return r != null && r[column].matches("\\d+") ? Long.parseLong(r[column]) : -1;
  }

  private static String seedArg(Map<String, String[]> record, String cell, int column) {
    long seed = seed(record, cell, column);
    return seed >= 0 ? Long.toString(seed) : "";
  }

  /** What the future gave; an assertion error of the child as it is. */
  private static <T> T get(Future<T> future) throws InterruptedException {
    try {
      return future.get();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AssertionError a) throw a;
      throw new AssertionError("a child JVM failed", e.getCause());
    }
  }

  /** The frames of the child's one proof line. */
  static long proofOf(String printed) {
    List<String> lines = Arrays.stream(printed.split("\n")).filter(l -> l.startsWith("proof\t")).toList();
    assertThat(lines).as("the proof line of a child, which printed:%n%s", printed).hasSize(1);
    return Long.parseLong(lines.getFirst().split("\t")[3]);
  }

  /** {@code proven} when a 1 MiB thread of the same tier holds 1.5 to 3.5 times {@code frames} (measure-b.sh). */
  static String proofVerdict(long frames, long frames1MiB) {
    boolean proven = 2 * frames1MiB >= 3 * frames && 2 * frames1MiB <= 7 * frames;
    return proven ? "proven" : "INVALID " + frames + " frames against " + frames1MiB + " on 1 MiB";
  }

  /**
   * K1's warm check over the text its children printed ({@link CalibrationCell}'s {@code warm} action), or the first
   * thing that is wrong, naming the child and the run: there are {@value #WARM_CHILDREN} children, and each gives
   * reference jq's result in runs 1 to {@value #WARM_RUNS}, in order, then a control that ends STACK for a {@link
   * #NEVER_FLAT} shape and ends STACK or completes for any other.
   */
  static String warmVerdict(Shape shape, List<String> children) {
    if (children.size() < WARM_CHILDREN) return "rows from " + children.size() + " children";
    for (int c = 0; c < children.size(); c++) {
      List<String[]> rows = warmRows(children.get(c), shape.id());
      List<String[]> runs = rows.stream().filter(r -> !r[8].equals("control")).toList();
      List<String> controls = rows.stream().filter(r -> r[8].equals("control")).map(r -> r[9]).toList();
      for (int n = 0; n < runs.size(); n++) {
        String[] r = runs.get(n);
        if (!r[8].equals(Integer.toString(n + 1))) return "child " + (c + 1) + ": run " + r[8] + " in place of run " + (n + 1);
        if (!r[9].equals(shape.completed())) return "child " + (c + 1) + " run " + r[8] + " at U_f " + r[7] + ": " + r[9];
      }
      if (runs.size() != WARM_RUNS) return "child " + (c + 1) + ": " + runs.size() + " runs of " + WARM_RUNS;
      if (controls.size() != 1) return "child " + (c + 1) + ": " + controls.size() + " controls";
      if (!controlVerdict(shape, controls.getFirst()).equals(NEVER_FLAT.contains(shape.id()) ? STACK : "STACK or flat")) return "child " + (c + 1) + " control: " + controls.getFirst();
    }
    return warmHolds(shape);
  }

  /** The verdict of a warm cell that holds. */
  private static String warmHolds(Shape shape) {
    return WARM_CHILDREN + " children × " + WARM_RUNS + " runs at the thread's size: " + shape.completed();
  }

  /** The {@code warm} rows a child printed for shape {@code id}, its runs and its control, as printed. */
  private static List<String[]> warmRows(String printed, String id) {
    return Arrays.stream(printed.split("\n")).map(l -> l.split("\t")).filter(r -> r.length > 9 && r[0].equals("warm") && r[2].equals(id)).toList();
  }

  /** The verdict of a cell whose thread is not proven (D2): its proofs and those of the thread it proves against. */
  private static String invalid(Map<String, List<Long>> proofs, Tier tier, CellThread thread, CompileMode mode, Shape shape) {
    CellThread against = provesAgainst(thread);
    return "INVALID " + proofs.get(key(tier, thread, mode) + " " + shape.id()) + " frames; " + against + " " + proofs.get(key(tier, against, mode) + " " + shape.id());
  }

  private static String controlVerdict(Shape shape, String control) {
    if (NEVER_FLAT.contains(shape.id())) return control;
    return control.equals(STACK) || control.equals(shape.completed()) ? "STACK or flat" : control;
  }

  /**
   * The verdict of an {@code -Xint} or C1 cell with rows from enough children, every child proven: {@code rows} are its
   * {@code cell} rows (the linked search's for {@code -Xint}, one per C1 child), {@code cold} an {@code -Xint} cell's
   * cold edge, null for C1. INVALID, naming the child and its passes, when a child's search has no fixed point (L1);
   * {@code switches} when {@code U_f} at {@code threadBytes} is at most the edge and the edge + 1 ends as {@link
   * #stackAfterAPrefix} allows (L3); {@code flat} when the control completes and the shape is not {@link #NEVER_FLAT}.
   */
  static String provenVerdict(Tier tier, long threadBytes, Shape shape, List<String[]> rows, Cold cold) {
    for (int c = 0; c < rows.size(); c++) {
      // L1: a search without a fixed point; the -Xint linked search shares the path.
      if (rows.get(c)[7].equals("INVALID")) return "INVALID child " + (c + 1) + ": U* has no fixed point in passes " + rows.get(c)[10];
    }
    long linked = edge(rows);
    long coldEdge = cold == null ? -1 : cold.edge();
    String[] first = rows.getFirst();
    if (rows.stream().allMatch(r -> r[7].equals("-")) && (cold == null || cold.edge() == -1)) {
      return rows.getFirst()[9].equals(shape.completed()) && !NEVER_FLAT.contains(shape.id()) ? "flat" : "control " + rows.getFirst()[9];
    }
    if ((tier == XINT ? coldEdge : linked) < 0) return "fails at U_f 0: " + (tier == XINT ? cold.next() : rows.getFirst()[8]);
    if (tier == XINT ? !stackAfterAPrefix(shape, cold.next()) : !rows.stream().allMatch(r -> stackAfterAPrefix(shape, r[8]))) {
      return "U*+1 ends " + (tier == XINT ? cold.next() : rows.stream().map(r -> r[8]).toList());
    }
    if (unitLimit(first, threadBytes) > (tier == XINT ? coldEdge : linked)) {
      return "U_f " + unitLimit(first, threadBytes) + " past U* " + (tier == XINT ? coldEdge : linked) + ": the backstop first";
    }
    return "switches";
  }

  /**
   * L3: whether {@code end}, a cell's end at {@code U* + 1}, is the backstop after a prefix of the shape's reference
   * results, in order, empty or full: the emits that reached the sink before the overflow (K7). A shape has one
   * reference result, so for first-or that is {@code [], termination STACK} or {@code [7], termination STACK}.
   */
  static boolean stackAfterAPrefix(Shape shape, String end) {
    return List.of(List.<String>of(), List.of(shape.result())).stream().anyMatch(prefix -> end.equals(prefix + ", termination STACK"));
  }

  /**
   * {@link #edgeIsExact}'s expectation for the line it got for {@code shape} in {@code mode}: {@code U*} gives the
   * reference result and {@code U* + 1} ends as {@link #stackAfterAPrefix} allows, {@link #STACK} where it does not; a
   * flat shape's line, unless it is {@link #NEVER_FLAT}.
   */
  static String exactEdgeWant(CompileMode mode, Shape shape, String line) {
    if (line.endsWith(": flat") && !NEVER_FLAT.contains(shape.id())) return line;
    String at = line.contains(" at U* ") ? line.substring(0, line.indexOf(':')) : mode + " " + shape.id();
    String next = line.contains(" | ") ? line.substring(line.lastIndexOf(" | ") + 3) : "";
    return at + ": " + shape.completed() + " | " + (stackAfterAPrefix(shape, next) ? next : STACK);
  }

  /** The rows of {@code prefix} a child printed, by their shape id (field 2). */
  static Map<String, String[]> rows(String printed, String prefix) {
    Map<String, String[]> rows = new LinkedHashMap<>();
    for (String line : printed.split("\n")) {
      String[] r = line.split("\t");
      if (r[0].equals(prefix) && r.length > 2) rows.put(r[2], r);
    }
    return rows;
  }

  /** {@code U_f = floorDiv(S - reserve, b) - D_f} of a row, from the constants the child's explain showed. */
  private static long unitLimit(String[] row, long threadBytes) {
    return Math.floorDiv(threadBytes - Long.parseLong(row[6]), Long.parseLong(row[5])) - Long.parseLong(row[3]);
  }

  /** The smallest numeric {@code U*} of a cell's children, or -1. */
  private static long edge(List<String[]> rows) {
    return rows.stream().filter(r -> r[7].matches("\\d+")).mapToLong(r -> Long.parseLong(r[7])).min().orElse(-1);
  }

  private static long spread(List<String[]> rows) {
    List<Long> u = rows.stream().filter(r -> r[7].matches("\\d+")).map(r -> Long.parseLong(r[7])).toList();
    return u.isEmpty() ? 0 : u.stream().mapToLong(Long::longValue).max().orElseThrow() - u.stream().mapToLong(Long::longValue).min().orElseThrow();
  }

  /**
   * The thread a cell proves against: a platform cell the next platform thread up, the 1 MiB
   * one, the top, 512 KiB, and a virtual cell 1 MiB.
   */
  private static CellThread provesAgainst(CellThread thread) {
    if (thread.kind().equals("virtual")) return new CellThread("platform", LARGE);
    int at = PLATFORM.indexOf(thread.bytes());
    return new CellThread("platform", PLATFORM.get(at == PLATFORM.size() - 1 ? at - 1 : at + 1));
  }

  /**
   * D2: every child of the cell proves its thread against {@link #provesAgainst}: a cell below that thread against its
   * smallest proof, the 1 MiB cell, above it, against its largest.
   */
  private static boolean proven(Map<String, List<Long>> proofs, Tier tier, CellThread thread, CompileMode mode, Shape shape) {
    CellThread against = provesAgainst(thread);
    List<Long> other = proofs.getOrDefault(key(tier, against, mode) + " " + shape.id(), List.of());
    List<Long> own = proofs.getOrDefault(key(tier, thread, mode) + " " + shape.id(), List.of());
    if (other.isEmpty() || own.isEmpty()) return false;
    long smallestAbove = other.stream().mapToLong(Long::longValue).min().orElseThrow();
    long largestBelow = other.stream().mapToLong(Long::longValue).max().orElseThrow();
    return against.bytes() < thread.bytes()
        ? own.stream().allMatch(f -> proofVerdict(largestBelow, f).equals("proven"))
        : own.stream().allMatch(f -> proofVerdict(f, smallestAbove).equals("proven"));
  }

  /** An optional number as a record field: the number, or {@code -}. */
  private static String field(OptionalLong value) {
    return value.isPresent() ? Long.toString(value.getAsLong()) : "-";
  }

  /**
   * {@code text} as a row or progress line prints it, each shape's result longer than {@value
   * #SHOWN} characters in it as {@code <length> chars, sha256 <hex>}. Comparisons stay on the full text.
   */
  static String shown(String text) throws NoSuchAlgorithmException {
    String out = text;
    for (Shape shape : SHAPES) {
      if (shape.result().length() > SHOWN) out = out.replace(shape.result(), shape.result().length() + " chars, sha256 " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(shape.result().getBytes(UTF_8))));
    }
    return out;
  }

  /** The guard pages of a fresh child (red, yellow, reserved and shadow), in bytes. */
  private long guardBytes() throws IOException, InterruptedException {
    String flags = ChildJvm.command(dir, List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-XX:+PrintFlagsFinal", "-version"));
    long pages = 0;
    for (String line : flags.split("\n")) {
      String[] f = line.trim().split("\\s+");
      if (f.length >= 4 && f[1].matches("Stack(Red|Yellow|Reserved|Shadow)Pages")) pages += Long.parseLong(f[3]);
    }
    return pages * Long.parseLong(ChildJvm.command(dir, List.of("getconf", "PAGESIZE")).trim());
  }

  /**
   * The environment rows of a record: {@code time-ms}, {@code jdk}, {@code cpus}, {@code
   * machine} ({@code $RATCHET_MACHINE}, or {@code -} when it is unset or empty), {@code cpu} (the text after the colon
   * of the first {@code model name} line of {@code /proc/cpuinfo}, stripped, or {@code -} without one) and {@code
   * kernel} ({@code os.version}).
   */
  private static List<String> environment(long started) throws IOException {
    Path cpuinfo = Path.of("/proc/cpuinfo");
    List<String> rows =
        new ArrayList<>(
            List.of(
                "time-ms\t" + (System.nanoTime() - started) / 1_000_000,
                "jdk\t" + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version"),
                "cpus\t" + Runtime.getRuntime().availableProcessors()));
    rows.addAll(machineRows(System.getenv("RATCHET_MACHINE"), Files.isReadable(cpuinfo) ? Files.readAllLines(cpuinfo, UTF_8) : List.of(), System.getProperty("os.version")));
    return rows;
  }

  /**
   * The {@code machine}, {@code cpu} and {@code kernel} rows of {@link #environment} from their inputs: {@code machine}
   * ({@code $RATCHET_MACHINE}, null when unset), the lines of {@code /proc/cpuinfo} and {@code os.version}.
   */
  static List<String> machineRows(String machine, List<String> cpuinfo, String kernel) {
    return List.of(
        "machine\t" + (machine == null || machine.isEmpty() ? "-" : machine),
        "cpu\t" + cpuinfo.stream().filter(l -> l.startsWith("model name")).findFirst().map(l -> l.substring(l.indexOf(':') + 1).strip()).orElse("-"),
        "kernel\t" + kernel);
  }

  private static void write(String name, List<String> lines) throws IOException {
    Path target = Path.of(System.getProperty("basedir", "."), "target");
    Files.createDirectories(target);
    Files.write(target.resolve(name), lines, UTF_8);
  }
}
