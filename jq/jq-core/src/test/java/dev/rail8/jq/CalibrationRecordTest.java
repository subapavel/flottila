package dev.rail8.jq;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.CalibrationCell.Shape;
import dev.rail8.jq.CalibrationCell.Units;
import dev.rail8.jq.query.compile.CompileMode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * c3 of #113 (ADR-0046 #113 "The record", K7, K9, and the adviser's K1): the release constants {@code b} and the
 * reserve equal the maxima of the committed calibration record's {@code -Xint} and C1 rows. They are measured in
 * {@code -Xint} and C1, proven in warm (K1): a warm row records a check and feeds neither {@code b} nor the reserve,
 * because a warm search runs while the JIT compiles. The record is the tsv that {@link
 * CalibrationTest#matrixCellsSwitchBeforeTheBackstop} writes to {@code target/calibration.tsv}, committed unedited as
 * the test resource {@value #RECORD} and cited in {@code bench/RESULTS.md}. This class reads it in-process and starts
 * no child JVM, so it stays in PIT's test set.
 *
 * <p>From the {@link #MEASURED} rows: {@code b} is the largest {@code b_cell}, rounded up to 8 B. The reserve is the
 * largest linked intercept {@code S - b_cell·(U*linked + D_f)} plus the largest cold term {@code b_cell·(U*linked -
 * U*cold)} of an {@code -Xint} cell (ADR-0046 #113 "Rulings on the red", O1), rounded up to 4 KiB, plus 32 KiB, and
 * never below the record's guard pages plus 32 KiB; there is no {@code T} term (D1).
 *
 * <p>The lowest-pair rule (GLOSSARY "Lowest pair") replaces the 512 KiB → 1 MiB chord: {@link
 * #maxima} recomputes every number from the rows' {@code U*}. Per tier, mode and shape each adjacent pair of platform
 * threads has the slope {@code s = ceilDiv(ΔS, ΔU*linked)}; a platform row's {@code b_cell} is the largest {@code s},
 * a virtual row's the lowest pair's {@code s_low}; the intercept {@code S - s_low·(U*linked + D_f)} is taken on the
 * lowest pair's rows and the virtual one, never on the 1 MiB row ({@link #interceptsComeFromTheLowestPair}, {@link
 * #bCellIsTheLargestAdjacentSlope}).
 */
class CalibrationRecordTest {
  /** The committed record, a class-path resource of jq-core's tests. */
  static final String RECORD = "/stack/calibration.tsv";

  private static final long KIB = 1024;

  /** The tiers whose rows measure the constants (K1); warm rows are checks. */
  static final Set<String> MEASURED = Set.of("xint", "c1");

  /**
   * The threads of an {@code -Xint} or C1 cell, as a record row names them (kind, bytes): platform 256 KiB, 512 KiB and
   * 1 MiB, and a virtual thread on a child of {@code -Xss512k}.
   */
  static final List<String> MEASURED_THREADS = List.of("platform 262144", "platform 524288", "platform 1048576", "virtual 524288");

  /** The threads of a warm cell: the last three of {@link #MEASURED_THREADS}, no warm 256 KiB cell. */
  static final List<String> WARM_THREADS = MEASURED_THREADS.subList(1, 4);

  /** The guard pages of the e6f4cc0 record and of the committed one, in bytes. */
  private static final long GUARD = 98_304;

  /** The maxima of a record's measured rows, and the {@code b} and reserve they give. */
  record Maxima(long largestSlope, long largestIntercept, long largestCold, long bytesPerUnit, long reserve) {}

  /**
   * What the lowest-pair rule gives one row, each value empty where it gives none: {@code b_cell}, {@code b_cold}
   * (the same rule on {@code U*cold}), the intercept, the cold term, and the linked and cold slopes of the pair whose
   * upper thread is this row's.
   */
  record Derived(OptionalLong bCell, OptionalLong bCold, OptionalLong intercept, OptionalLong cold, OptionalLong pairSlope, OptionalLong pairColdSlope) {
    static final Derived NONE = new Derived(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
  }

  /**
   * Every number the calibration computes from a record's rows, each row split at its tabs, in the rows' order: {@link
   * #maxima} reads it, and the matrix fills its {@code b_cell}, {@code b_cold}, {@code intercept} and
   * {@code cold} columns with it. Columns: cell tier kind thread mode shape D_f T b reserve U*cold U*linked spread
   * end(U*+1) control proof b_cell b_cold intercept cold; the last four are not read.
   *
   * <p>Per {@link #MEASURED} tier, mode and shape, the platform rows with a numeric {@code U*linked}, by thread, form
   * adjacent pairs, each with the slope {@code s = ceilDiv(ΔS, ΔU*linked)}, none where {@code U*linked} does not rise.
   * The lowest pair is the two lowest of those threads (256 KiB → 512 KiB, or 512 KiB → 1 MiB in a record without
   * 256 KiB rows), its slope {@code s_low}. A platform row's {@code b_cell} is the largest {@code s} of its group; a virtual row's
   * is {@code s_low}. The intercept {@code S - s_low·(U*linked + D_f)} is the lowest pair's rows' and the virtual row's,
   * no other platform row's. The cold term {@code b_cell·(U*linked - U*cold)} is a row's with a numeric {@code U*cold},
   * an {@code -Xint} row's. {@code b_cold} is the same rule on the pairs' {@code U*cold}. Warm rows get nothing (K1).
   */
  static List<Derived> derived(List<String[]> cells) {
    Map<String, List<Integer>> groups = new LinkedHashMap<>();
    for (int i = 0; i < cells.size(); i++) {
      String[] r = cells.get(i);
      if (MEASURED.contains(r[1]) && numeric(r[11])) groups.computeIfAbsent(r[1] + " " + r[4] + " " + r[5], k -> new ArrayList<>()).add(i);
    }
    List<Derived> derived = new ArrayList<>(Collections.nCopies(cells.size(), Derived.NONE));
    for (List<Integer> group : groups.values()) {
      List<Integer> platform = new ArrayList<>(group.stream().filter(i -> cells.get(i)[2].equals("platform")).toList());
      platform.sort(Comparator.comparingLong(i -> Long.parseLong(cells.get(i)[3])));
      List<OptionalLong> linked = new ArrayList<>(List.of(OptionalLong.empty()));
      List<OptionalLong> cold = new ArrayList<>(List.of(OptionalLong.empty()));
      for (int k = 1; k < platform.size(); k++) {
        String[] lower = cells.get(platform.get(k - 1));
        String[] upper = cells.get(platform.get(k));
        linked.add(slope(lower, upper, 11));
        cold.add(numeric(lower[10]) && numeric(upper[10]) ? slope(lower, upper, 10) : OptionalLong.empty());
      }
      // The lowest pair's slopes; none in a group of one platform row.
      OptionalLong sLow = linked.stream().skip(1).findFirst().orElse(OptionalLong.empty());
      OptionalLong coldLow = cold.stream().skip(1).findFirst().orElse(OptionalLong.empty());
      OptionalLong sMax = largest(linked);
      OptionalLong coldMax = largest(cold);
      for (int i : group) {
        String[] r = cells.get(i);
        int k = platform.indexOf(i);
        boolean virtual = r[2].equals("virtual");
        OptionalLong bCell = virtual ? sLow : sMax;
        OptionalLong intercept = (virtual || k < 2) && sLow.isPresent() ? OptionalLong.of(Long.parseLong(r[3]) - sLow.getAsLong() * (Long.parseLong(r[11]) + Long.parseLong(r[6]))) : OptionalLong.empty();
        OptionalLong coldTerm = bCell.isPresent() && numeric(r[10]) ? OptionalLong.of(bCell.getAsLong() * (Long.parseLong(r[11]) - Long.parseLong(r[10]))) : OptionalLong.empty();
        derived.set(i, new Derived(bCell, virtual ? coldLow : coldMax, intercept, coldTerm, virtual ? OptionalLong.empty() : linked.get(k), virtual ? OptionalLong.empty() : cold.get(k)));
      }
    }
    return derived;
  }

  /** The slope {@code ceilDiv(ΔS, ΔU*)} of two platform rows on the edges in {@code column}, none where they do not rise. */
  private static OptionalLong slope(String[] lower, String[] upper, int column) {
    long units = Long.parseLong(upper[column]) - Long.parseLong(lower[column]);
    return units > 0 ? OptionalLong.of(Math.ceilDiv(Long.parseLong(upper[3]) - Long.parseLong(lower[3]), units)) : OptionalLong.empty();
  }

  private static OptionalLong largest(List<OptionalLong> values) {
    return values.stream().filter(OptionalLong::isPresent).mapToLong(OptionalLong::getAsLong).max();
  }

  private static boolean numeric(String field) {
    return field.matches("\\d+");
  }

  /**
   * The maxima over the {@link #MEASURED} cell rows of a record, each row split at its tabs, from {@link #derived}: the
   * largest {@code b_cell}, intercept and cold term, {@code b} the largest {@code b_cell} rounded up to 8 B, the reserve
   * the largest intercept plus the largest cold term, rounded up to 4 KiB, plus 32 KiB, and never below {@code guard}
   * plus 32 KiB.
   */
  static Maxima maxima(List<String[]> cells, long guard) {
    long largestSlope = 0;
    long largestIntercept = Long.MIN_VALUE;
    long largestCold = 0;
    for (Derived d : derived(cells)) {
      if (d.bCell().isPresent()) largestSlope = Math.max(largestSlope, d.bCell().getAsLong());
      if (d.intercept().isPresent()) largestIntercept = Math.max(largestIntercept, d.intercept().getAsLong());
      if (d.cold().isPresent()) largestCold = Math.max(largestCold, d.cold().getAsLong());
    }
    long reserve = Math.max(Math.ceilDiv(largestIntercept + largestCold, 4 * KIB) * 4 * KIB + 32 * KIB, guard + 32 * KIB);
    return new Maxima(largestSlope, largestIntercept, largestCold, Math.ceilDiv(largestSlope, 8) * 8, reserve);
  }

  /** The committed record: its cell rows, split at their tabs, and its summary rows by key. */
  record Committed(List<String[]> cells, Map<String, String> summary) {}

  /** The committed record {@value #RECORD}, read in-process. */
  static Committed committed() throws IOException {
    List<String[]> cells = new ArrayList<>();
    Map<String, String> summary = new LinkedHashMap<>();
    try (InputStream in = CalibrationRecordTest.class.getResourceAsStream(RECORD)) {
      assertThat(in).as("the committed calibration record %s, written by CalibrationTest.matrixCellsSwitchBeforeTheBackstop (ADR-0046 #113)", RECORD).isNotNull();
      for (String line : new String(in.readAllBytes(), UTF_8).split("\n")) {
        String[] r = line.split("\t");
        if (r[0].equals("cell")) cells.add(r);
        else if (r.length == 2) summary.put(r[0], r[1]);
      }
    }
    return new Committed(cells, summary);
  }

  /**
   * The constants that {@code explain()} shows in each mode equal the record's {@code -Xint} and C1 maxima, and the
   * record holds exactly the cells of the matrix: {@code -Xint} and C1 on the 4 {@link
   * #MEASURED_THREADS}, warm on the 3 {@link #WARM_THREADS}, both modes, each of the 14 {@link CalibrationCell#SHAPES}.
   * Red on the older record, which has no 256 KiB row and no {@code plain-descent}.
   */
  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void constantsAreTheMatrixMaxima(CompileMode mode) throws IOException {
    Committed record = committed();
    Set<String> covered = new LinkedHashSet<>();
    for (String[] r : record.cells()) covered.add(String.join(" ", r[1], r[2], r[3], r[4], r[5]));
    Set<String> matrix = new LinkedHashSet<>();
    for (String tier : List.of("xint", "c1", "warm")) {
      for (String thread : tier.equals("warm") ? WARM_THREADS : MEASURED_THREADS) {
        for (CompileMode m : CompileMode.values()) {
          for (Shape shape : CalibrationCell.SHAPES) matrix.add(String.join(" ", tier, thread, m.name(), shape.id()));
        }
      }
    }
    assertThat(covered).as("the cells of the record %s: -Xint and C1 on platform 256 KiB, 512 KiB, 1 MiB and virtual, warm on the last three, both modes, each shape (ADR-0046 #199)", RECORD).containsExactlyInAnyOrderElementsOf(matrix);
    assertThat(record.summary()).as("the record's summary rows").containsKey("guard");
    long guard = Long.parseLong(record.summary().get("guard"));
    Maxima maxima = maxima(record.cells(), guard);
    assertThat(maxima.largestSlope()).as("the largest b_cell of the record's -Xint and C1 rows").isPositive();
    Units units = CalibrationCell.units(CallRuns.F, mode);
    assertThat(List.of(units.bytesPerUnit(), units.reserve()))
        .as(
            "%s: b and the reserve of explain() against the maxima of the record's -Xint and C1 rows (largest b_cell %d, largest linked intercept %d, largest cold term %d, guard pages %d bytes)",
            mode,
            maxima.largestSlope(),
            maxima.largestIntercept(),
            maxima.largestCold(),
            guard)
        .isEqualTo(List.of(maxima.bytesPerUnit(), maxima.reserve()));
  }

  /**
   * The record names the machine it was measured on: beside {@code time-ms}, {@code jdk}
   * and {@code cpus} it has {@code machine} ({@code $RATCHET_MACHINE} of the run, which a committed record must have
   * set), {@code cpu} and {@code kernel}. Red on the older record, whose summary holds only the first three.
   */
  @Test
  void theRecordNamesItsMachine() throws IOException {
    Map<String, String> summary = committed().summary();
    assertThat(summary.keySet()).as("the summary rows of the record %s", RECORD).containsAll(List.of("machine", "cpu", "kernel", "jdk", "cpus", "time-ms"));
    assertThat(summary.get("machine")).as("the record's machine row, $RATCHET_MACHINE of the run that wrote it").isNotEqualTo("-");
  }

  /**
   * L2's oracle: {@code -Xint} OPTIMISED ND on the older record's three rows (written at b 192 and reserve 286 720,
   * verbatim) and a side probe's 256 KiB row ({@code -Xint -Xss256k}, linked {@code U*} 4715, proof 1641; it measured
   * no cold edge, so
   * {@code U*cold} is {@code -}, and no matrix filled its derived columns). Linked {@code U*} 4715, 12 671 and 34 703 on
   * the platform threads, 12 620 on the virtual one, D_f 24. {@code s_low = ceilDiv(262 144, 7956) = 33}, {@code s_up =
   * ceilDiv(524 288, 22 032) = 24}, so {@code b_cell} is 33. The intercepts at 33: 105 757 (256 KiB), 105 353 (512 KiB)
   * and 107 036 (virtual); the 1 MiB row gives none. The cold terms: 0, 0, and 33·(34 703 - 34 729) on the 1 MiB row.
   * So {@code b} is 40 and the reserve 107 036 rounded up to 110 592, plus 32 768: 143 360. The rows' own columns hold
   * the chord's {@code b_cell} 24 and intercept 220 832, which the helper does not read.
   */
  @Test
  void interceptsComeFromTheLowestPair() {
    List<String> record =
        List.of(
            "cell\txint\tplatform\t262144\tOPTIMISED\tND\t24\t5\t192\t286720\t-\t4715\t0\t[], termination STACK\t[], termination STACK\t[1641]\t-\t-\t-\t-",
            "cell\txint\tplatform\t524288\tOPTIMISED\tND\t24\t5\t192\t286720\t12671\t12671\t0\t[], termination STACK\t[], termination STACK\t[4371, 4371, 4371]\t24\t24\t219608\t0",
            "cell\txint\tplatform\t1048576\tOPTIMISED\tND\t24\t5\t192\t286720\t34729\t34703\t0\t[], termination STACK\t[], termination STACK\t[9833, 9833, 9833]\t24\t24\t215128\t-624",
            "cell\txint\tvirtual\t524288\tOPTIMISED\tND\t24\t5\t192\t286720\t12620\t12620\t0\t[], termination STACK\t[], termination STACK\t[4354, 4354, 4354]\t24\t24\t220832\t0");
    List<String[]> rows = record.stream().map(l -> l.split("\t")).toList();
    assertThat(maxima(rows, GUARD))
        .as("the maxima of -Xint OPTIMISED ND on 256 KiB, 512 KiB, 1 MiB and virtual, with the record's guard pages of 98 304 bytes")
        .isEqualTo(new Maxima(33, 107_036, 0, 40, 143_360));
  }

  static LongStream seeds() {
    return LongStream.range(0, 64);
  }

  /**
   * The lowest-pair rule as a property over {@code e256 < e512 < e1M}: each seed builds one {@code -Xint} cell's
   * linked edges from two slopes, one at least half again the other, rising with depth on even seeds (as W) and falling
   * on odd ones (as ND), and a virtual edge up to 60 under the 512 KiB one. The expected numbers are the spec's
   * definitions on those edges: {@code s_low} and {@code s_up}, each {@code ceilDiv(ΔS, ΔU*linked)}; a platform row's
   * {@code b_cell} is {@code max(s_low, s_up)}, the virtual row's {@code s_low}; the intercepts {@code S -
   * s_low·(U*linked + D_f)} of the 256 KiB, 512 KiB and virtual rows, the 1 MiB row none. Each row in turn gets a
   * {@code U*cold} k under its linked edge, the others none, so the largest cold term {@code b_cell·k} shows that row's
   * {@code b_cell}. On a rising seed the 1 MiB row's intercept at {@code s_low} would be the largest, so its absence
   * shows, and {@code s_low} differs from the largest slope. Three decoys share all but one of tier, mode and shape with
   * the cell, each with smaller slopes and an intercept 40 000 lower, so they set no maximum unless rows of two cells are
   * paired. Rows go in shuffled; their derived columns are {@code -}.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void bCellIsTheLargestAdjacentSlope(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    boolean rising = seed % 2 == 0;
    long gentle = random.nextLong(30, 121);
    long steep = random.nextLong(Math.ceilDiv(3 * gentle, 2), 201);
    long lowSlope = rising ? gentle : steep;
    long upSlope = rising ? steep : gentle;
    long dF = random.nextLong(0, 121);
    long intercept = random.nextLong(70_000, 120_001);
    long under = random.nextLong(0, 61);
    long k = random.nextLong(1, 301);
    long[] e = edges(lowSlope, upSlope, dF, intercept, under);
    long sLow = Math.ceilDiv(256 * KIB, e[1] - e[0]);
    long sUp = Math.ceilDiv(512 * KIB, e[2] - e[1]);
    long platform = Math.max(sLow, sUp);
    long lowest = Math.max(256 * KIB - sLow * (e[0] + dF), Math.max(512 * KIB - sLow * (e[1] + dF), 512 * KIB - sLow * (e[3] + dF)));
    long top = 1024 * KIB - sLow * (e[2] + dF);
    String caseText = "seed " + seed + ": linked " + e[0] + ", " + e[1] + ", " + e[2] + ", virtual " + e[3] + ", D_f " + dF + ", s_low " + sLow + ", s_up " + sUp;
    assertThat(rising ? sUp > sLow && top > lowest : sLow > sUp).as("the case's slopes %s (and on a rising seed the 1 MiB intercept %d at s_low above the lowest pair's %d)", caseText, top, lowest).isTrue();
    List<String[]> decoys = new ArrayList<>();
    long[] d = edges(lowSlope - 10, upSlope - 10, dF, intercept - 40_000, under);
    decoys.addAll(cell("xint", "CONSERVATIVE", "T", dF, d, -1, 0));
    decoys.addAll(cell("xint", "OPTIMISED", "E", dF, d, -1, 0));
    decoys.addAll(cell("c1", "OPTIMISED", "T", dF, d, -1, 0));
    List<String> got = new ArrayList<>();
    List<String> want = new ArrayList<>();
    List<String> threads = List.of("256 KiB", "512 KiB", "1 MiB", "virtual");
    for (int cold = 0; cold < 4; cold++) {
      List<String[]> rows = new ArrayList<>(cell("xint", "OPTIMISED", "T", dF, e, cold, k));
      rows.addAll(decoys);
      Collections.shuffle(rows, new Random(seed * 4 + cold));
      Maxima m = maxima(rows, GUARD);
      String at = "U*cold " + k + " under the " + threads.get(cold) + " row: ";
      got.add(at + "largest slope " + m.largestSlope() + ", intercept " + m.largestIntercept() + ", cold " + m.largestCold());
      want.add(at + "largest slope " + platform + ", intercept " + lowest + ", cold " + (cold == 3 ? sLow : platform) * k);
    }
    assertThat(got).as("the maxima of %s", caseText).isEqualTo(want);
  }

  /**
   * The linked edges 256 KiB, 512 KiB, 1 MiB and virtual of a cell whose stack rises {@code low} B a unit up to 512 KiB
   * and {@code up} B a unit above, over {@code intercept} B, the virtual edge {@code under} below the 512 KiB one.
   */
  private static long[] edges(long low, long up, long dF, long intercept, long under) {
    long e256 = (256 * KIB - intercept) / low - dF;
    long e512 = e256 + 256 * KIB / low;
    return new long[] {e256, e512, e512 + 512 * KIB / up, e512 - under};
  }

  /**
   * A cell's four rows as the matrix writes them before it fills the derived columns, linked edges {@code e}; an
   * {@code -Xint} row's {@code U*cold} is its linked edge, but on row {@code cold} (none for -1) {@code k} less; a C1
   * row's is {@code -}.
   */
  private static List<String[]> cell(String tier, String mode, String shape, long dF, long[] e, int cold, long k) {
    List<String[]> rows = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      String[] thread = MEASURED_THREADS.get(i).split(" ");
      String coldEdge = tier.equals("xint") ? Long.toString(e[i] - (i == cold ? k : 0)) : "-";
      rows.add(new String[] {"cell", tier, thread[0], thread[1], mode, shape, Long.toString(dF), "5", "192", "286720", coldEdge, Long.toString(e[i]), "0", "[], termination STACK", "[], termination STACK", "[4371]", "-", "-", "-", "-"});
    }
    return rows;
  }

  /**
   * K1, K2 and K3 on rows of the older matrix of 234 cells, as that run wrote them but for the shortened {@code -Xint}
   * proof lists: each row with its 512 KiB, 1 MiB and virtual partners from that record, so a helper that recomputes from {@code U*} sees each
   * pair. That record has no 256 KiB row, so each group's lowest pair is its 512 KiB → 1 MiB pair. A flat C1 row, which
   * is not that record's and has no partner there, gives nothing. The largest slope is C1's W CONSERVATIVE pair,
   * {@code ceilDiv(524 288, 5017 - 2242)} = 189, so {@code b} is 192 (K2), not the warm W's 220 (2971 → 5360), which
   * gives 224. The reserve is 220 832, {@code -Xint}'s virtual ND OPTIMISED intercept at the pair's 24 (12 671 → 34 703),
   * plus 29 988, {@code -Xint}'s 1 MiB collect-descent CONSERVATIVE cold term at 119 (3424 → 7834), rounded up to
   * 253 952, plus 32 768: 286 720 (K3). The warm ND OPTIMISED virtual row's intercept at the warm pair's 1 (20 454 → 4 203
   * 835), 503 810, would make it 569 344, the reserve row that run wrote. These numbers stay the older record's: a
   * move to the new rule's numbers would weaken the test.
   */
  @Test
  void warmRowsFeedNeitherBNorTheReserve() {
    List<String> record =
        List.of(
            "cell\txint\tvirtual\t524288\tOPTIMISED\tND\t24\t5\t96\t131072\t12620\t12620\t0\t[], termination STACK\t[], termination STACK\t[4354]\t24\t24\t220832\t0",
            "cell\txint\tplatform\t524288\tOPTIMISED\tND\t24\t5\t96\t131072\t12671\t12671\t0\t[], termination STACK\t[], termination STACK\t[4371]\t24\t24\t219608\t0",
            "cell\txint\tplatform\t1048576\tOPTIMISED\tND\t24\t5\t96\t131072\t34729\t34703\t0\t[], termination STACK\t[], termination STACK\t[9833]\t24\t24\t215128\t-624",
            "cell\txint\tplatform\t1048576\tCONSERVATIVE\tcollect-descent\t116\t5\t96\t131072\t7582\t7834\t0\t[], termination STACK\t[], termination STACK\t[9833]\t119\t123\t102526\t29988",
            "cell\txint\tplatform\t524288\tCONSERVATIVE\tcollect-descent\t116\t5\t96\t131072\t3298\t3424\t0\t[], termination STACK\t[], termination STACK\t[4371]\t119\t123\t103028\t14994",
            "cell\txint\tvirtual\t524288\tCONSERVATIVE\tcollect-descent\t116\t5\t96\t131072\t3298\t3424\t0\t[], termination STACK\t[], termination STACK\t[4354]\t119\t123\t103028\t14994",
            "cell\tc1\tplatform\t524288\tCONSERVATIVE\tW\t101\t5\t96\t131072\t-\t2242\t0\t[], termination STACK\t[], termination STACK\t[4371, 4371, 4371]\t189\t-\t81461\t-",
            "cell\tc1\tplatform\t1048576\tCONSERVATIVE\tW\t101\t5\t96\t131072\t-\t5017\t0\t[], termination STACK\t[], termination STACK\t[9833, 9833, 9833]\t189\t-\t81274\t-",
            "cell\tc1\tvirtual\t524288\tCONSERVATIVE\tW\t101\t5\t96\t131072\t-\t2131\t0\t[], termination STACK\t[], termination STACK\t[4354, 4354, 4354]\t189\t-\t102440\t-",
            "cell\tc1\tplatform\t524288\tOPTIMISED\tC\t12\t5\t96\t131072\t-\t-\t0\t-\t[1], ran out\t[4371, 4371, 4371]\t-\t-\t-\t-",
            "cell\twarm\tplatform\t524288\tCONSERVATIVE\tW\t101\t5\t96\t131072\t-\t2971\t0\t[10000], ran out\t[], termination STACK\t[4371, 4371, 4371]\t220\t-\t-151552\t-",
            "cell\twarm\tplatform\t1048576\tCONSERVATIVE\tW\t101\t5\t96\t131072\t-\t5360\t3840\t[10000], ran out\t[], termination STACK\t[9833, 9833, 9833]\t220\t-\t-152844\t-",
            "cell\twarm\tvirtual\t524288\tCONSERVATIVE\tW\t101\t5\t96\t131072\t-\t2971\t0\t[10000], ran out\t[], termination STACK\t[4354, 4354, 4354]\t220\t-\t-151552\t-",
            "cell\twarm\tplatform\t524288\tOPTIMISED\tND\t24\t5\t96\t131072\t-\t20454\t0\t[1000], ran out\t[], termination STACK\t[4371, 4371, 4371]\t1\t-\t503810\t-",
            "cell\twarm\tplatform\t1048576\tOPTIMISED\tND\t24\t5\t96\t131072\t-\t4203835\t0\t[1000], ran out\t[], termination STACK\t[9833, 9833, 9833]\t1\t-\t-3155283\t-",
            "cell\twarm\tvirtual\t524288\tOPTIMISED\tND\t24\t5\t96\t131072\t-\t20454\t0\t[1000], ran out\t[], termination STACK\t[4354, 4354, 4354]\t1\t-\t503810\t-");
    List<String[]> rows = record.stream().map(l -> l.split("\t")).toList();
    assertThat(maxima(rows, GUARD))
        .as("the maxima of the -Xint and C1 rows only, with the record's guard pages of 98 304 bytes")
        .isEqualTo(new Maxima(189, 220_832, 29_988, 192, 286_720));
  }
}
