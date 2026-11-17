package io.github.subapavel.jqj.oracle.jqtest;

import io.github.subapavel.jqj.oracle.diff.DiffReport;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The jqtest oracle report {@code oracle/target/oracle-reports/jqtest-<backend>.tsv} as {@code scripts/ratchet.java}
 * reads it (ADR-0020). Columns: {@code id}, verdict, detail. The required ids come from
 * {@link io.github.subapavel.jqj.oracle.diff.DiffReport#required}, whose format is the same.
 */
public final class JqTestReport {

  /** One case's line of the report. */
  public record Row(String id, Verdict verdict) {
    String line() {
      return id + "\t" + verdict.kind() + "\t" + verdict.detail();
    }
  }

  private JqTestReport() {}

  /** Writes {@code rows} into the report: each replaces the line of its id, other lines stay; sorted by id. */
  public static void merge(Path report, List<Row> rows) {
    Map<String, String> byId = new LinkedHashMap<>();
    for (Row r : rows) byId.put(r.id(), r.line());
    DiffReport.mergeLines(report, byId);
  }
}
