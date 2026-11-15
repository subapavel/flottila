package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.CalibrationCell.SHAPES;
import static io.github.subapavel.jqj.CalibrationCell.STACK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.CalibrationCell.Shape;
import io.github.subapavel.jqj.CalibrationCell.Units;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stack calibration of #113 (ADR-0046 and its #111, #113 and #116 amendments, ADR-0011, ADR-0020, F-55, F-30,
 * F-93, F-95) in fresh, agent-free child JVMs ({@link ChildJvm}, {@link CalibrationCell}). A cell is one shape × tier ×
 * thread × compile mode. A cell switches before the backstop when its shape, with the thread's own size declared as
 * {@code stackBytes}, reaches the trampoline at {@code U_f} before the real stack runs out, and so gives reference jq's
 * result; the backstop is the STACK termination.
 *
 * <p>The ticket gate runs the {@code -Xint} 512 KiB column, both modes, once per shape at the committed constants
 * ({@link #everyCellSwitchesBeforeTheBackstop}). The {@code calibration-matrix} tag (the spec gate and nightly) runs
 * every cell: 3 tiers × 3 threads × 2 modes × {@link CalibrationCell#SHAPES}, measures each unit edge {@code U*} and
 * writes {@code target/calibration.tsv}, the record {@link CalibrationRecordTest} reads once it is committed.
 *
 * <p>Each cell proves its thread's size (D2, F-93): the plain recursion count of its thread, against the count of a
 * 1 MiB thread of its tier, must be 1.5 to 3.5 times smaller (#111's measure-b.sh: {@code -Xint} 4 772 frames at
 * 512 KiB and 10 730 at 1 MiB; a 512 KiB thread on a cached larger stack reached 10 729). A cell whose size is not
 * proven is INVALID, never a pass.
 */
class CalibrationTest {
  private static final long KIB = 1024;

  /** The thread of the ticket column, and of a virtual cell's child ({@code -Xss512k}). */
  private static final long SMALL = 512 * KIB;

  /** The other platform thread of the matrix. */
  private static final long LARGE = 1024 * KIB;

  /**
   * The shapes whose control, declaring 1 GiB, ends STACK on a thread of at most 1 MiB, so no cell of theirs is flat
   * and c2 cannot pass empty: #111's four (measure-b.sh ended each STACK at {@code U_f} 40 000) and F-95's descent.
   */
  private static final Set<String> NEVER_FLAT = Set.of("collect", "reduce", "first-or", "hold", "collect-descent");

  /** A tier of the matrix and its JVM flags. */
  record Tier(String name, List<String> flags) {}

  private static final Tier XINT = new Tier("xint", List.of("-Xint"));

  private static final List<Tier> TIERS = List.of(XINT, new Tier("c1", List.of("-Xbatch", "-XX:TieredStopAtLevel=1")), new Tier("warm", List.of()));

  /** A thread of the matrix: platform 512 KiB and 1 MiB, and a virtual thread on a child of {@code -Xss512k}. */
  record CellThread(String kind, long bytes) {
    String xss() {
      return "-Xss" + bytes / KIB + "k";
    }

    @Override
    public String toString() {
      return kind + " " + bytes / KIB + "KiB";
    }
  }

  private static final List<CellThread> THREADS = List.of(new CellThread("platform", SMALL), new CellThread("platform", LARGE), new CellThread("virtual", SMALL));

  @TempDir Path dir;

  /**
   * c2 (ADR-0020 as renamed by ADR-0046 #113, K1), the ticket gate's column: on {@code -Xint}, a 512 KiB launcher main
   * thread, in each mode, every shape with 512 KiB declared gives reference jq's result at the committed constants, so
   * it reached the trampoline before the backstop. Each cell's thread is proven, and each control, declaring 1 GiB on
   * the same thread, ends STACK, or completes for a flat shape; {@link #NEVER_FLAT} must end STACK. Writes {@code
   * target/calibration-column.tsv}.
   */
  @Test
  void everyCellSwitchesBeforeTheBackstop() throws Exception {
    long started = System.nanoTime();
    Map<CompileMode, String> printed = new LinkedHashMap<>();
    String reference;
    try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
      Future<String> large = pool.submit(child(XINT.flags(), new CellThread("platform", LARGE), "proof", "CONSERVATIVE"));
      Map<CompileMode, Future<String>> column = new LinkedHashMap<>();
      for (CompileMode mode : CompileMode.values()) {
        column.put(mode, pool.submit(child(XINT.flags(), new CellThread("platform", SMALL), "check", mode.name())));
      }
      reference = get(large);
      for (Map.Entry<CompileMode, Future<String>> c : column.entrySet()) {
        printed.put(c.getKey(), get(c.getValue()));
      }
    }
    long frames1MiB = proofOf(reference);
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    List<String> tsv = new ArrayList<>();
    tsv.add("# check\ttier\tkind\tthread\tmode\tshape\tD_f\tT\tb\treserve\tU_f\tend\tcontrol\tproof\tproof 1 MiB");
    for (Map.Entry<CompileMode, String> c : printed.entrySet()) {
      String cell = "xint platform 512KiB " + c.getKey();
      long frames = proofOf(c.getValue());
      got.add(cell + " proof: " + proofVerdict(frames, frames1MiB));
      want.add(cell + " proof: proven");
      Map<String, String[]> rows = rows(c.getValue(), "check");
      for (Shape shape : SHAPES) {
        String[] r = rows.get(shape.id());
        if (r == null) {
          got.add(cell + " " + shape.id() + ": no row in " + c.getValue());
          want.add(cell + " " + shape.id() + ": " + shape.completed());
          continue;
        }
        got.add(cell + " " + shape.id() + ": " + r[8]);
        want.add(cell + " " + shape.id() + ": " + shape.completed());
        got.add(cell + " " + shape.id() + " control: " + controlVerdict(shape, r[9]));
        want.add(cell + " " + shape.id() + " control: " + (NEVER_FLAT.contains(shape.id()) ? STACK : "STACK or flat"));
        tsv.add(String.join("\t", "check", "xint", "platform", Long.toString(SMALL), c.getKey().name(), shape.id(), r[3], r[4], r[5], r[6], r[7], r[8], r[9], Long.toString(frames), Long.toString(frames1MiB)));
      }
    }
    tsv.addAll(environment(started));
    write("calibration-column.tsv", tsv);
    assertThat(got)
        .as("the -Xint 512 KiB column at the committed constants: each cell's proof, each shape's end with 512 KiB declared on its 512 KiB main thread, and its control declaring 1 GiB")
        .isEqualTo(want);
  }

  /**
   * D2 (F-93): a cell proves its thread's size. On {@code -Xint} the plain recursion count of a {@code -Xss512k} main
   * thread is 1.5 to 3.5 times smaller than a {@code -Xss1m} one's, and a 1 MiB stack under a 512 KiB cell's label
   * (the cached-stack case, 10 729 frames against 10 730) is INVALID. Two fresh {@code -Xss512k} children count the
   * same frames, so the count is the thread's, not the run's.
   */
  @Test
  void cellsProveTheirThreadSize() throws Exception {
    long small = proofOf(ChildJvm.run(dir, flags(XINT, new CellThread("platform", SMALL)), CalibrationCell.class, "proof", "platform", Long.toString(SMALL), "CONSERVATIVE"));
    long again = proofOf(ChildJvm.run(dir, flags(XINT, new CellThread("platform", SMALL)), CalibrationCell.class, "proof", "platform", Long.toString(SMALL), "CONSERVATIVE"));
    long large = proofOf(ChildJvm.run(dir, flags(XINT, new CellThread("platform", LARGE)), CalibrationCell.class, "proof", "platform", Long.toString(LARGE), "CONSERVATIVE"));
    assertThat(List.of(proofVerdict(small, large), proofVerdict(large, large), Long.toString(again)))
        .as("-Xint: a 512 KiB main thread's proof (%d frames) against 1 MiB (%d frames); 1 MiB under a 512 KiB label; a second fresh 512 KiB child's count", small, large)
        .isEqualTo(List.of("proven", "INVALID " + large + " frames against " + large + " on 1 MiB", Long.toString(small)));
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
   * c2 over the full matrix (the {@code calibration-matrix} tag): every cell is proven, and at the committed constants
   * its {@code U_f} at the thread's size is at most its unit edge {@code U*}, where {@code U* + 1} ends STACK; a flat
   * cell, whose control completes, has no edge. A JIT cell's {@code U*} is the smallest of 3 fresh children and the
   * spread is recorded (D3). {@code -Xint} runs one child per shape, thread and mode, in parallel (one child per thread
   * and mode, as the design had it, ran past the 300 s of a child); C1 and warm one child per shape, thread and mode,
   * serially. A fresh run asserts one way (K7): its {@code b} and reserve are at most the
   * constants. Writes {@code target/calibration.tsv}.
   */
  @Test
  @Tag("calibration-matrix")
  void matrixCellsSwitchBeforeTheBackstop() throws Exception {
    long started = System.nanoTime();
    Map<String, List<String>> printed = new LinkedHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      // -Xint: one child per shape, thread and mode, in parallel; a child of all 13 searches ran past its 300 s.
      Map<String, List<Future<String>>> xint = new LinkedHashMap<>();
      for (CellThread thread : THREADS) {
        for (CompileMode mode : CompileMode.values()) {
          for (Shape shape : SHAPES) {
            xint.computeIfAbsent(key(XINT, thread, mode), k -> new ArrayList<>()).add(pool.submit(child(XINT.flags(), thread, "search", mode.name(), shape.id())));
          }
        }
      }
      for (Tier tier : TIERS.subList(1, TIERS.size())) {
        for (CellThread thread : THREADS) {
          for (CompileMode mode : CompileMode.values()) {
            for (Shape shape : SHAPES) {
              for (int rep = 0; rep < 3; rep++) {
                printed.computeIfAbsent(key(tier, thread, mode), k -> new ArrayList<>()).add(child(tier.flags(), thread, "search", mode.name(), shape.id(), "warm").call());
              }
            }
          }
        }
      }
      for (Map.Entry<String, List<Future<String>>> x : xint.entrySet()) {
        for (Future<String> f : x.getValue()) {
          printed.computeIfAbsent(x.getKey(), k -> new ArrayList<>()).add(get(f));
        }
      }
    }
    long guard = guardBytes();
    Units committed = CalibrationCell.units(CallRuns.F, CompileMode.OPTIMISED);
    // Per cell (tier, thread, mode, shape): U* the smallest over its children, the spread, its rows and proofs.
    Map<String, List<String[]>> cells = new LinkedHashMap<>();
    Map<String, List<Long>> proofs = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> p : printed.entrySet()) {
      for (String out : p.getValue()) {
        long frames = proofOf(out);
        for (String[] r : rows(out, "cell").values()) {
          cells.computeIfAbsent(p.getKey() + " " + r[2], k -> new ArrayList<>()).add(r);
          proofs.computeIfAbsent(p.getKey() + " " + r[2], k -> new ArrayList<>()).add(frames);
        }
      }
    }
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    List<String> tsv = new ArrayList<>();
    tsv.add("# The calibration record (ADR-0046 #113), written by CalibrationTest.matrixCellsSwitchBeforeTheBackstop; never edited by hand.");
    tsv.add("# cell\ttier\tkind\tthread\tmode\tshape\tD_f\tT\tb\treserve\tU*\tspread\tend(U*+1)\tcontrol\tproof\tb_cell\tintercept");
    long freshB = 0;
    long freshIntercept = Long.MIN_VALUE;
    for (Tier tier : TIERS) {
      for (CellThread thread : THREADS) {
        for (CompileMode mode : CompileMode.values()) {
          for (Shape shape : SHAPES) {
            String cell = key(tier, thread, mode) + " " + shape.id();
            List<String[]> rs = cells.getOrDefault(cell, List.of());
            String verdict;
            String bCell = "-";
            String intercept = "-";
            long uStar = edge(rs);
            if (rs.size() < (tier == XINT ? 1 : 3)) {
              verdict = "rows from " + rs.size() + " children";
            } else if (!proven(proofs, tier, thread, mode, shape)) {
              verdict = "INVALID " + proofs.get(cell) + " frames; 512 KiB " + proofs.get(key(tier, new CellThread("platform", SMALL), mode) + " " + shape.id()) + ", 1 MiB " + proofs.get(key(tier, new CellThread("platform", LARGE), mode) + " " + shape.id());
            } else if (rs.stream().allMatch(r -> r[7].equals("-"))) {
              verdict = rs.getFirst()[9].equals(shape.completed()) && !NEVER_FLAT.contains(shape.id()) ? "flat" : "control " + rs.getFirst()[9];
            } else if (uStar < 0) {
              verdict = "fails at U_f 0: " + rs.getFirst()[8];
            } else if (!rs.stream().allMatch(r -> r[8].equals(STACK))) {
              verdict = "U*+1 ends " + rs.stream().map(r -> r[8]).toList();
            } else if (unitLimit(rs.getFirst(), thread.bytes()) > uStar) {
              verdict = "U_f " + unitLimit(rs.getFirst(), thread.bytes()) + " past U* " + uStar + ": the backstop first";
            } else {
              verdict = "switches";
            }
            long pair = slope(cells, tier, mode, shape);
            if (pair > 0 && uStar >= 0) {
              long dF = Long.parseLong(rs.getFirst()[3]);
              bCell = Long.toString(pair);
              intercept = Long.toString(thread.bytes() - pair * (uStar + dF));
              freshB = Math.max(freshB, pair);
              freshIntercept = Math.max(freshIntercept, thread.bytes() - pair * (uStar + dF));
            }
            got.add(cell + ": " + verdict);
            want.add(cell + ": " + (verdict.equals("flat") ? "flat" : "switches"));
            String[] first = rs.isEmpty() ? new String[10] : rs.getFirst();
            tsv.add(String.join("\t", "cell", tier.name(), thread.kind(), Long.toString(thread.bytes()), mode.name(), shape.id(), first[3], first[4], first[5], first[6], uStar < 0 ? String.valueOf(first[7]) : Long.toString(uStar), Long.toString(spread(rs)), String.valueOf(first[8]), String.valueOf(first[9]), String.valueOf(proofs.get(cell)), bCell, intercept));
          }
        }
      }
    }
    long freshReserve = Math.max(Math.ceilDiv(freshIntercept, 4 * KIB) * 4 * KIB + 32 * KIB, guard + 32 * KIB);
    tsv.add("guard\t" + guard);
    tsv.add("max-b\t" + Math.ceilDiv(freshB, 8) * 8);
    tsv.add("reserve\t" + freshReserve);
    tsv.addAll(environment(started));
    write("calibration.tsv", tsv);
    assertThat(got).as("every cell of the matrix at the committed constants (b %d, reserve %d)", committed.bytesPerUnit(), committed.reserve()).isEqualTo(want);
    assertThat(Math.ceilDiv(freshB, 8) * 8).as("K7: the fresh b, rounded up to 8 B, against the committed b").isLessThanOrEqualTo(committed.bytesPerUnit());
    assertThat(freshReserve).as("K7: the fresh reserve against the committed reserve").isLessThanOrEqualTo(committed.reserve());
  }

  /** {@code U_f = floorDiv(S - reserve, b) - D_f} of a cell row, from the constants the child's explain showed. */
  private static long unitLimit(String[] row, long threadBytes) {
    return Math.floorDiv(threadBytes - Long.parseLong(row[6]), Long.parseLong(row[5])) - Long.parseLong(row[3]);
  }

  /**
   * D2: every child of the cell proves its thread against its tier's platform pair: a 512 KiB or virtual cell against
   * the smallest 1 MiB proof, a 1 MiB cell against the largest 512 KiB one.
   */
  private static boolean proven(Map<String, List<Long>> proofs, Tier tier, CellThread thread, CompileMode mode, Shape shape) {
    List<Long> small = proofs.getOrDefault(key(tier, new CellThread("platform", SMALL), mode) + " " + shape.id(), List.of());
    List<Long> large = proofs.getOrDefault(key(tier, new CellThread("platform", LARGE), mode) + " " + shape.id(), List.of());
    List<Long> own = proofs.getOrDefault(key(tier, thread, mode) + " " + shape.id(), List.of());
    if (small.isEmpty() || large.isEmpty() || own.isEmpty()) return false;
    long smallest1MiB = large.stream().mapToLong(Long::longValue).min().orElseThrow();
    long largest512KiB = small.stream().mapToLong(Long::longValue).max().orElseThrow();
    return thread.bytes() == LARGE
        ? own.stream().allMatch(f -> proofVerdict(largest512KiB, f).equals("proven"))
        : own.stream().allMatch(f -> proofVerdict(f, smallest1MiB).equals("proven"));
  }

  /**
   * The edge is exact (the {@code calibration-matrix} tag): on {@code -Xint}, a 512 KiB main thread, in each mode, a
   * fresh child given the {@code U*} another child's search found ends with reference jq's result at {@code U* - 1}
   * and {@code U*} and with STACK at {@code U* + 1}.
   */
  @Test
  @Tag("calibration-matrix")
  void edgeIsExact() throws Exception {
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    CellThread thread = new CellThread("platform", SMALL);
    // One search child per shape and mode: a child of all 13 shapes' searches ran past the 300 s of a child (#113's red).
    Map<String, Future<String>> searches = new LinkedHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      for (CompileMode mode : CompileMode.values()) {
        for (Shape shape : SHAPES) {
          searches.put(mode + " " + shape.id(), pool.submit(child(XINT.flags(), thread, "search", mode.name(), shape.id())));
        }
      }
      Map<String, Future<String>> edges = new LinkedHashMap<>();
      List<String> withEdge = new ArrayList<>();
      for (CompileMode mode : CompileMode.values()) {
        for (Shape shape : SHAPES) {
          String[] r = rows(get(searches.get(mode + " " + shape.id())), "cell").get(shape.id());
          if (r != null && r[7].matches("\\d+")) {
            edges.put(mode + " " + shape.id(), pool.submit(child(XINT.flags(), thread, "edge", mode.name(), shape.id(), shape.id() + "=" + r[7])));
            want.add(mode + " " + shape.id() + " at U* " + r[7] + ": " + shape.completed() + " | " + shape.completed() + " | " + STACK);
            if (mode == CompileMode.OPTIMISED) withEdge.add(shape.id());
          }
        }
      }
      assertThat(withEdge).as("OPTIMISED: the shapes with an edge include %s", NEVER_FLAT).containsAll(NEVER_FLAT);
      for (Map.Entry<String, Future<String>> e : edges.entrySet()) {
        String id = e.getKey().substring(e.getKey().indexOf(' ') + 1);
        String[] r = rows(get(e.getValue()), "edge").get(id);
        got.add(e.getKey() + " at U* " + (r == null ? "?" : r[3] + ": " + r[4] + " | " + r[5] + " | " + r[6]));
      }
    }
    assertThat(got).as("the ends at U* - 1, U* and U* + 1 in a fresh -Xint 512 KiB child").isEqualTo(want);
  }

  /**
   * The heap bytes per live call activation (the {@code calibration-matrix} tag; the #113 design): {@link CallRuns#F}
   * on n = 10⁵ and 2·10⁵, F on N giving N (F-87), with {@code stackBytes} 0 and 512 KiB, in both modes; at the first
   * emit, with every activation live, the used heap after two collections. bytes = Δused / Δn is positive, and it and
   * {@code 4 194 304 × bytes} against the child's {@code maxMemory} go to {@code target/calibration-heap.tsv}.
   */
  @Test
  @Tag("calibration-matrix")
  void heapPerActivationIsRecorded() throws Exception {
    String out = child(List.of(), new CellThread("platform", SMALL), "heap", "CONSERVATIVE,OPTIMISED").call();
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    List<String> tsv = new ArrayList<>();
    tsv.add("# heap\tmode\tstackBytes\tbytes per activation\t4194304 x bytes\tmaxMemory");
    Map<String, Long> used = new LinkedHashMap<>();
    long max = 0;
    for (String line : out.split("\n")) {
      String[] r = line.split("\t");
      if (r[0].equals("heap")) {
        got.add(r[1] + " " + r[2] + " n " + r[3] + ": " + r[5]);
        want.add(r[1] + " " + r[2] + " n " + r[3] + ": [" + r[3] + "], ran out");
        used.put(r[1] + " " + r[2] + " " + r[3], Long.parseLong(r[4]));
        max = Long.parseLong(r[6]);
      }
    }
    for (CompileMode mode : CompileMode.values()) {
      for (long stackBytes : new long[] {0, SMALL}) {
        String at = mode + " " + stackBytes;
        long bytes = (used.getOrDefault(at + " 200000", 0L) - used.getOrDefault(at + " 100000", 0L)) / 100_000;
        got.add(at + ": " + (bytes > 0 ? "positive" : bytes + " bytes"));
        want.add(at + ": positive");
        tsv.add(String.join("\t", "heap", mode.name(), Long.toString(stackBytes), Long.toString(bytes), Long.toString(4_194_304L * bytes), Long.toString(max)));
      }
    }
    write("calibration-heap.tsv", tsv);
    assertThat(got).as("F's results and the heap bytes per live activation, in:%n%s", out).isEqualTo(want);
  }

  /** A child of {@code tier}'s flags on {@code thread}, running {@code action} with the further args. */
  private Callable<String> child(List<String> tierFlags, CellThread thread, String action, String... more) {
    List<String> args = new ArrayList<>(List.of(action, thread.kind(), Long.toString(thread.bytes())));
    args.addAll(List.of(more));
    List<String> flags = new ArrayList<>(tierFlags);
    flags.add(thread.xss());
    return () -> ChildJvm.run(dir, flags, CalibrationCell.class, args.toArray(String[]::new));
  }

  private static List<String> flags(Tier tier, CellThread thread) {
    List<String> flags = new ArrayList<>(tier.flags());
    flags.add(thread.xss());
    return flags;
  }

  private static String key(Tier tier, CellThread thread, CompileMode mode) {
    return tier.name() + " " + thread + " " + mode;
  }

  /** What the future gave; an assertion error of the child as it is. */
  private static String get(Future<String> future) throws InterruptedException {
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

  private static String controlVerdict(Shape shape, String control) {
    if (NEVER_FLAT.contains(shape.id())) return control;
    return control.equals(STACK) || control.equals(shape.completed()) ? "STACK or flat" : control;
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

  /** The smallest numeric {@code U*} of a cell's children, or -1. */
  private static long edge(List<String[]> rows) {
    return rows.stream().filter(r -> r[7].matches("\\d+")).mapToLong(r -> Long.parseLong(r[7])).min().orElse(-1);
  }

  private static long spread(List<String[]> rows) {
    List<Long> u = rows.stream().filter(r -> r[7].matches("\\d+")).map(r -> Long.parseLong(r[7])).toList();
    return u.isEmpty() ? 0 : u.stream().mapToLong(Long::longValue).max().orElseThrow() - u.stream().mapToLong(Long::longValue).min().orElseThrow();
  }

  /** The slope {@code b_cell} of a platform pair: 512 KiB over the difference of its edges, rounded up; or 0. */
  private static long slope(Map<String, List<String[]>> cells, Tier tier, CompileMode mode, Shape shape) {
    long small = edge(cells.getOrDefault(key(tier, new CellThread("platform", SMALL), mode) + " " + shape.id(), List.of()));
    long large = edge(cells.getOrDefault(key(tier, new CellThread("platform", LARGE), mode) + " " + shape.id(), List.of()));
    return small >= 0 && large > small ? Math.ceilDiv(LARGE - SMALL, large - small) : 0;
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

  private static List<String> environment(long started) {
    return List.of(
        "time-ms\t" + (System.nanoTime() - started) / 1_000_000,
        "jdk\t" + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version"),
        "cpus\t" + Runtime.getRuntime().availableProcessors());
  }

  private static void write(String name, List<String> lines) throws IOException {
    Path target = Path.of(System.getProperty("basedir", "."), "target");
    Files.createDirectories(target);
    Files.write(target.resolve(name), lines, UTF_8);
  }
}
