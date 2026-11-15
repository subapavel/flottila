package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.CalibrationCell.Shape;
import io.github.subapavel.jqj.CalibrationCell.Units;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * c3 of #113 (ADR-0046 #113 "The record", K7, K9): the release constants {@code b} and the reserve equal the maxima of
 * the committed calibration record, whichever tier holds them. The record is the tsv that {@link
 * CalibrationTest#matrixCellsSwitchBeforeTheBackstop} writes to {@code target/calibration.tsv}, committed unedited as
 * the test resource {@value #RECORD} and cited in {@code bench/RESULTS.md}. This class reads it in-process and starts
 * no child JVM, so it stays in PIT's test set.
 *
 * <p>From the rows: {@code b} is the largest {@code b_cell}, rounded up to 8 B. The reserve is the largest {@code S -
 * b_cell·(U* + D_f)}, rounded up to 4 KiB, plus 32 KiB, and never below the record's guard pages plus 32 KiB; there is
 * no {@code T} term (D1).
 */
class CalibrationRecordTest {
  /** The committed record, a class-path resource of jq-core's tests. */
  static final String RECORD = "/stack/calibration.tsv";

  private static final long KIB = 1024;

  /**
   * The constants that {@code explain()} shows in each mode equal the record's maxima, and the record holds every cell
   * of the matrix: 3 tiers × 3 threads × 2 modes × {@link CalibrationCell#SHAPES}.
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
    long largestSlope = 0;
    long largestIntercept = Long.MIN_VALUE;
    for (String[] r : cells) {
      // Columns: cell tier kind thread mode shape D_f T b reserve U* spread end(U*+1) control proof b_cell intercept.
      if (r[15].equals("-")) continue;
      long slope = Long.parseLong(r[15]);
      largestSlope = Math.max(largestSlope, slope);
      largestIntercept = Math.max(largestIntercept, Long.parseLong(r[3]) - slope * (Long.parseLong(r[10]) + Long.parseLong(r[6])));
    }
    assertThat(largestSlope).as("the largest b_cell of the record").isPositive();
    long guard = Long.parseLong(summary.get("guard"));
    Units units = CalibrationCell.units(CallRuns.F, mode);
    assertThat(List.of(units.bytesPerUnit(), units.reserve()))
        .as("%s: b and the reserve of explain() against the record's maxima (largest b_cell %d, largest intercept %d, guard pages %d bytes)", mode, largestSlope, largestIntercept, guard)
        .isEqualTo(List.of(Math.ceilDiv(largestSlope, 8) * 8, Math.max(Math.ceilDiv(largestIntercept, 4 * KIB) * 4 * KIB + 32 * KIB, guard + 32 * KIB)));
  }
}
