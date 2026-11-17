package dev.rail8.jq;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.CalibrationCell.Shape;
import dev.rail8.jq.CalibrationCell.Units;
import dev.rail8.jq.query.compile.CompileMode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
 */
class CalibrationRecordTest {
  /** The committed record, a class-path resource of jq-core's tests. */
  static final String RECORD = "/stack/calibration.tsv";

  private static final long KIB = 1024;

  /** The tiers whose rows measure the constants (K1); warm rows are checks. */
  static final Set<String> MEASURED = Set.of("xint", "c1");

  /** The maxima of a record's measured rows, and the {@code b} and reserve they give. */
  record Maxima(long largestSlope, long largestIntercept, long largestCold, long bytesPerUnit, long reserve) {}

  /**
   * The maxima over the {@link #MEASURED} cell rows of a record, each row split at its tabs. Columns: cell tier kind
   * thread mode shape D_f T b reserve U*cold U*linked spread end(U*+1) control proof b_cell b_cold intercept cold. A row
   * whose {@code b_cell} is {@code -} (flat, or no pair) gives nothing; the cold term is an {@code -Xint} row's, the only
   * one with a numeric {@code U*cold}.
   */
  static Maxima maxima(List<String[]> cells, long guard) {
    long largestSlope = 0;
    long largestIntercept = Long.MIN_VALUE;
    long largestCold = 0;
    for (String[] r : cells) {
      if (!MEASURED.contains(r[1]) || r[16].equals("-")) continue;
      long slope = Long.parseLong(r[16]);
      largestSlope = Math.max(largestSlope, slope);
      largestIntercept = Math.max(largestIntercept, Long.parseLong(r[3]) - slope * (Long.parseLong(r[11]) + Long.parseLong(r[6])));
      if (r[10].matches("\\d+")) largestCold = Math.max(largestCold, slope * (Long.parseLong(r[11]) - Long.parseLong(r[10])));
    }
    long reserve = Math.max(Math.ceilDiv(largestIntercept + largestCold, 4 * KIB) * 4 * KIB + 32 * KIB, guard + 32 * KIB);
    return new Maxima(largestSlope, largestIntercept, largestCold, Math.ceilDiv(largestSlope, 8) * 8, reserve);
  }

  /**
   * The constants that {@code explain()} shows in each mode equal the record's {@code -Xint} and C1 maxima, and the
   * record holds every cell of the matrix: 3 tiers × 3 threads × 2 modes × {@link CalibrationCell#SHAPES}, the warm
   * checks included.
   */
  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void constantsAreTheMatrixMaxima(CompileMode mode) throws IOException {
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
    Set<String> covered = new LinkedHashSet<>();
    for (String[] r : cells) covered.add(String.join(" ", r[1], r[2], r[3], r[4], r[5]));
    Set<String> matrix = new LinkedHashSet<>();
    for (String tier : List.of("xint", "c1", "warm")) {
      for (String thread : List.of("platform 524288", "platform 1048576", "virtual 524288")) {
        for (CompileMode m : CompileMode.values()) {
          for (Shape shape : CalibrationCell.SHAPES) matrix.add(String.join(" ", tier, thread, m.name(), shape.id()));
        }
      }
    }
    assertThat(covered).as("the cells of the record %s", RECORD).containsAll(matrix);
    assertThat(summary).as("the record's summary rows").containsKey("guard");
    long guard = Long.parseLong(summary.get("guard"));
    Maxima maxima = maxima(cells, guard);
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
   * K1, K2 and K3 on five rows of the matrix at e6f4cc0 (the adviser's {@code .logs/113-calibration.tsv}), as that run
   * wrote them but for the shortened proof lists, and a flat C1 row, which gives nothing. The largest slope is C1's W
   * CONSERVATIVE pair, 189, so {@code b} is 192 (K2), not the warm W's 220, which gives 224. The reserve is 220 832,
   * {@code -Xint}'s virtual ND OPTIMISED intercept, plus 29 988, {@code -Xint}'s 1 MiB collect-descent CONSERVATIVE
   * cold term, rounded up to 253 952, plus 32 768: 286 720 (K3). The warm ND OPTIMISED row's intercept, 503 810, would
   * make it 569 344, the reserve row that run wrote.
   */
  @Test
  void warmRowsFeedNeitherBNorTheReserve() {
    List<String> record =
        List.of(
            "cell\txint\tvirtual\t524288\tOPTIMISED\tND\t24\t5\t96\t131072\t12620\t12620\t0\t[], termination STACK\t[], termination STACK\t[4354]\t24\t24\t220832\t0",
            "cell\txint\tplatform\t1048576\tCONSERVATIVE\tcollect-descent\t116\t5\t96\t131072\t7582\t7834\t0\t[], termination STACK\t[], termination STACK\t[9833]\t119\t123\t102526\t29988",
            "cell\tc1\tplatform\t524288\tCONSERVATIVE\tW\t101\t5\t96\t131072\t-\t2242\t0\t[], termination STACK\t[], termination STACK\t[4371, 4371, 4371]\t189\t-\t81461\t-",
            "cell\tc1\tplatform\t524288\tOPTIMISED\tC\t12\t5\t96\t131072\t-\t-\t0\t-\t[1], ran out\t[4371, 4371, 4371]\t-\t-\t-\t-",
            "cell\twarm\tplatform\t524288\tCONSERVATIVE\tW\t101\t5\t96\t131072\t-\t2971\t0\t[10000], ran out\t[], termination STACK\t[4371, 4371, 4371]\t220\t-\t-151552\t-",
            "cell\twarm\tplatform\t524288\tOPTIMISED\tND\t24\t5\t96\t131072\t-\t20454\t0\t[1000], ran out\t[], termination STACK\t[4371, 4371, 4371]\t1\t-\t503810\t-");
    List<String[]> rows = record.stream().map(l -> l.split("\t")).toList();
    assertThat(maxima(rows, 98_304))
        .as("the maxima of the -Xint and C1 rows only, with the record's guard pages of 98 304 bytes")
        .isEqualTo(new Maxima(189, 220_832, 29_988, 192, 286_720));
  }
}
