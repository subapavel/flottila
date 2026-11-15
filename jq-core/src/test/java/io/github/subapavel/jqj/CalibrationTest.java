package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.CalibrationCell.SHAPES;
import static io.github.subapavel.jqj.CalibrationCell.STACK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.CalibrationCell.Shape;
import io.github.subapavel.jqj.CalibrationCell.Units;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
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
 * tag (the spec gate and nightly) runs every cell: 3 tiers × 3 threads × 2 modes × the 13 {@link
 * CalibrationCell#SHAPES}. An {@code -Xint} cell's {@code U*} is its cold edge, each probe the first run of its own
 * fresh child, with the linked edge, after two linking runs, beside it; a JIT cell's is the smallest of 3 warmed
 * children. The matrix writes {@code target/calibration.tsv}, the record {@link CalibrationRecordTest} reads once it is
 * committed, and appends each child's rows to {@code target/calibration-progress.log} as it ends.
 *
 * <p>Each cell proves its thread's size (D2, F-93): the plain recursion count of its thread, against the count of a
 * 1 MiB thread of its tier, must be 1.5 to 3.5 times smaller (#111's measure-b.sh: {@code -Xint} 4 772 frames at
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

  /**
   * The shapes whose control, declaring 1 GiB, ends STACK on a thread of at most 1 MiB, so no cell of theirs is flat
   * and c2 cannot pass empty: #111's four (measure-b.sh ended each STACK at {@code U_f} 40 000) and F-95's descent.
   */
  private static final Set<String> NEVER_FLAT = Set.of("collect", "reduce", "first-or", "hold", "collect-descent");

  /** Two slopes of a pair agree within the rounding of {@code b}, 8 B. */
  private static final long SLOPES_AGREE = 8;

  /** Every child: the proof's recursion is never compiled (the rulings' warm proof). */
  private static final List<String> PROOF_FLAGS = List.of("-XX:CompileCommand=quiet", "-XX:CompileCommand=exclude,io.github.subapavel.jqj.CalibrationCell$Probe::down");

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
        tsv.add(String.join("\t", "check", "xint", "platform", Long.toString(SMALL), mode.name(), shape.id(), r[3], r[4], r[5], r[6], r[7], r[8], r[9], Long.toString(frames), Long.toString(frames1MiB)));
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
   * the same frames, the probe's recursion never compiled, so the count is the thread's, not the run's.
   */
  @ParameterizedTest
  @MethodSource("tiers")
  void cellsProveTheirThreadSize(Tier tier) throws Exception {
    long small = proofOf(ChildJvm.run(dir, flags(tier, new CellThread("platform", SMALL)), CalibrationCell.class, "proof", "platform", Long.toString(SMALL), "CONSERVATIVE"));
    long again = proofOf(ChildJvm.run(dir, flags(tier, new CellThread("platform", SMALL)), CalibrationCell.class, "proof", "platform", Long.toString(SMALL), "CONSERVATIVE"));
    long large = proofOf(ChildJvm.run(dir, flags(tier, new CellThread("platform", LARGE)), CalibrationCell.class, "proof", "platform", Long.toString(LARGE), "CONSERVATIVE"));
    assertThat(List.of(proofVerdict(small, large), proofVerdict(large, large), Long.toString(again)))
        .as("%s: a 512 KiB main thread's proof (%d frames) against 1 MiB (%d frames); 1 MiB under a 512 KiB label; a second fresh 512 KiB child's count", tier, small, large)
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
   * c2 over the full matrix (the {@code calibration-matrix} tag), at the committed constants: every cell is proven, and
   * its {@code U_f} at the thread's size is at most its edge, the cold one for {@code -Xint}, where the edge + 1 ends
   * STACK; a flat cell, whose control completes, has no edge. The cold and linked slopes of each {@code -Xint} pair
   * agree within {@value #SLOPES_AGREE} B. A JIT cell's {@code U*} is the smallest of 3 fresh warmed children and the
   * spread is recorded (D3). {@code -Xint} and C1 children run 4 at a time, warm children one at a time after them; each
   * search starts from the committed record's edge when there is one. The record holds per cell {@code b_cell} (the
   * linked slope), the linked intercept {@code S - b_cell·(U*linked + D_f)} and the cold term {@code b_cell·(U*linked -
   * U*cold)}. A fresh run asserts one way (K7): its {@code b} and reserve are at most the constants.
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
      for (CellThread thread : THREADS) {
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
    for (CellThread thread : THREADS) {
      for (CompileMode mode : CompileMode.values()) {
        for (Shape shape : SHAPES) {
          String cell = key(WARM, thread, mode) + " " + shape.id();
          for (int rep = 0; rep < 3; rep++) {
            printed.computeIfAbsent(cell, k -> new ArrayList<>()).add(logged(cell, child(WARM.flags(), thread, "search", mode.name(), shape.id(), "warm", seedArg(record, cell, 11))).call());
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
    long freshB = 0;
    long largestIntercept = Long.MIN_VALUE;
    long largestCold = Long.MIN_VALUE;
    for (Tier tier : TIERS) {
      for (CellThread thread : THREADS) {
        for (CompileMode mode : CompileMode.values()) {
          for (Shape shape : SHAPES) {
            String cell = key(tier, thread, mode) + " " + shape.id();
            List<String[]> rs = cells.getOrDefault(cell, List.of());
            Cold cold = colds.get(cell);
            long linked = edge(rs);
            long coldEdge = cold == null ? -1 : cold.edge();
            String[] first = rs.isEmpty() ? (cold == null || cold.first() == null ? new String[10] : cold.first()) : rs.getFirst();
            String verdict;
            if (rs.size() < (tier == XINT ? 1 : 3) || (tier == XINT && (cold == null || cold.first() == null))) {
              verdict = "rows from " + rs.size() + " children" + (cold == null ? "" : ", cold " + cold.next());
            } else if (!proven(proofs, tier, thread, mode, shape)) {
              verdict = "INVALID " + proofs.get(cell) + " frames; 512 KiB " + proofs.get(key(tier, new CellThread("platform", SMALL), mode) + " " + shape.id()) + ", 1 MiB " + proofs.get(key(tier, new CellThread("platform", LARGE), mode) + " " + shape.id());
            } else if (rs.stream().allMatch(r -> r[7].equals("-")) && (cold == null || cold.edge() == -1)) {
              verdict = rs.getFirst()[9].equals(shape.completed()) && !NEVER_FLAT.contains(shape.id()) ? "flat" : "control " + rs.getFirst()[9];
            } else if ((tier == XINT ? coldEdge : linked) < 0) {
              verdict = "fails at U_f 0: " + (tier == XINT ? cold.next() : rs.getFirst()[8]);
            } else if (tier == XINT ? !cold.next().equals(STACK) : !rs.stream().allMatch(r -> r[8].equals(STACK))) {
              verdict = "U*+1 ends " + (tier == XINT ? cold.next() : rs.stream().map(r -> r[8]).toList());
            } else if (unitLimit(first, thread.bytes()) > (tier == XINT ? coldEdge : linked)) {
              verdict = "U_f " + unitLimit(first, thread.bytes()) + " past U* " + (tier == XINT ? coldEdge : linked) + ": the backstop first";
            } else {
              verdict = "switches";
            }
            got.add(cell + ": " + verdict);
            want.add(cell + ": " + (verdict.equals("flat") ? "flat" : "switches"));
            long pair = slope(edges(cells, colds, tier, mode, shape, false));
            String bCell = "-";
            String bCold = "-";
            String intercept = "-";
            String coldTerm = "-";
            if (pair > 0 && linked >= 0) {
              long dF = Long.parseLong(first[3]);
              bCell = Long.toString(pair);
              intercept = Long.toString(thread.bytes() - pair * (linked + dF));
              freshB = Math.max(freshB, pair);
              largestIntercept = Math.max(largestIntercept, thread.bytes() - pair * (linked + dF));
              if (tier == XINT && coldEdge >= 0) {
                long pairCold = slope(edges(cells, colds, tier, mode, shape, true));
                bCold = Long.toString(pairCold);
                coldTerm = Long.toString(pair * (linked - coldEdge));
                largestCold = Math.max(largestCold, pair * (linked - coldEdge));
                if (thread.bytes() == SMALL && thread.kind().equals("platform")) {
                  got.add(cell + " slopes: " + (Math.abs(pairCold - pair) <= SLOPES_AGREE ? "agree" : "cold " + pairCold + " against linked " + pair));
                  want.add(cell + " slopes: agree");
                }
              }
            }
            tsv.add(String.join("\t", "cell", tier.name(), thread.kind(), Long.toString(thread.bytes()), mode.name(), shape.id(), first[3], first[4], first[5], first[6], tier == XINT ? Long.toString(coldEdge) : "-", Long.toString(linked), Long.toString(spread(rs)), tier == XINT && cold != null ? cold.next() : String.valueOf(first[8]), String.valueOf(first[9]), String.valueOf(proofs.get(cell)), bCell, bCold, intercept, coldTerm));
          }
        }
      }
    }
    long freshReserve = Math.max(Math.ceilDiv(largestIntercept + Math.max(largestCold, 0), 4 * KIB) * 4 * KIB + 32 * KIB, guard + 32 * KIB);
    tsv.add("guard\t" + guard);
    tsv.add("largest-intercept\t" + largestIntercept);
    tsv.add("largest-cold\t" + largestCold);
    tsv.add("max-b\t" + Math.ceilDiv(freshB, 8) * 8);
    tsv.add("reserve\t" + freshReserve);
    tsv.addAll(environment(started));
    write("calibration.tsv", tsv);
    assertThat(got).as("every cell of the matrix at the committed constants (b %d, reserve %d)", committed.bytesPerUnit(), committed.reserve()).isEqualTo(want);
    assertThat(Math.ceilDiv(freshB, 8) * 8).as("K7: the fresh b, rounded up to 8 B, against the committed b").isLessThanOrEqualTo(committed.bytesPerUnit());
    assertThat(freshReserve).as("K7: the fresh reserve against the committed reserve").isLessThanOrEqualTo(committed.reserve());
  }

  /**
   * The edge is exact (the {@code calibration-matrix} tag): on {@code -Xint}, a 512 KiB main thread, in each mode, the
   * cold edge {@code U*} of a bisection over fresh children gives reference jq's result as the first run of another
   * fresh child, and {@code U* + 1} ends STACK as the first run of a third.
   */
  @Test
  @Tag("calibration-matrix")
  void edgeIsExact() throws Exception {
    CellThread thread = new CellThread("platform", SMALL);
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
                    Cold c = cold(XINT, thread, mode, shape, -1);
                    if (c.edge() < 0) return mode + " " + shape.id() + ": " + (c.edge() == -1 ? "flat" : "fails at U_f 0, " + c.next());
                    return mode + " " + shape.id() + " at U* " + c.edge() + ": " + probe(XINT, thread, mode, shape, c.edge()) + " | " + probe(XINT, thread, mode, shape, c.edge() + 1);
                  }));
        }
      }
      for (CompileMode mode : CompileMode.values()) {
        for (Shape shape : SHAPES) {
          String line = get(lines.get(mode + " " + shape.id()));
          got.add(line);
          String at = line.contains(" at U* ") ? line.substring(0, line.indexOf(':')) : mode + " " + shape.id();
          want.add(line.endsWith(": flat") && !NEVER_FLAT.contains(shape.id()) ? line : at + ": " + shape.completed() + " | " + STACK);
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

  /** A child of {@code tier}'s flags on {@code thread}, running {@code action} with the further args. */
  private Callable<String> child(List<String> tierFlags, CellThread thread, String action, String... more) {
    List<String> args = new ArrayList<>(List.of(action, thread.kind(), Long.toString(thread.bytes())));
    args.addAll(List.of(more));
    List<String> flags = new ArrayList<>(tierFlags);
    flags.addAll(PROOF_FLAGS);
    flags.add(thread.xss());
    return () -> ChildJvm.run(dir, flags, CalibrationCell.class, args.toArray(String[]::new));
  }

  /** {@code child}, its output appended to the progress log when it ends. */
  private Callable<String> logged(String cell, Callable<String> child) {
    return () -> {
      String out = child.call();
      progress(cell + "\n" + out.strip());
      return out;
    };
  }

  private static synchronized void progress(String text) throws IOException {
    Path target = Path.of(System.getProperty("basedir", "."), "target");
    Files.createDirectories(target);
    Files.writeString(target.resolve("calibration-progress.log"), text + "\n", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

  /** The 512 KiB and 1 MiB platform edges of a pair, cold or linked; -1 where there is none. */
  private static long[] edges(Map<String, List<String[]>> cells, Map<String, Cold> colds, Tier tier, CompileMode mode, Shape shape, boolean cold) {
    String small = key(tier, new CellThread("platform", SMALL), mode) + " " + shape.id();
    String large = key(tier, new CellThread("platform", LARGE), mode) + " " + shape.id();
    if (cold) {
      return new long[] {colds.containsKey(small) ? colds.get(small).edge() : -1, colds.containsKey(large) ? colds.get(large).edge() : -1};
    }
    return new long[] {edge(cells.getOrDefault(small, List.of())), edge(cells.getOrDefault(large, List.of()))};
  }

  /** The slope {@code b_cell} of a platform pair: 512 KiB over the difference of its edges, rounded up; or 0. */
  private static long slope(long[] pair) {
    return pair[0] >= 0 && pair[1] > pair[0] ? Math.ceilDiv(LARGE - SMALL, pair[1] - pair[0]) : 0;
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
