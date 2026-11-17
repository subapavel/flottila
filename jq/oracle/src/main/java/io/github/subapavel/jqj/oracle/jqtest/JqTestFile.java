package io.github.subapavel.jqj.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Reads a jq test file as jq_test.c's {@code run_jq_tests} does (ADR-0020):
 *
 * <ul>
 *   <li>a skip line is blank, or {@code #} after spaces or tabs;
 *   <li>a line that is exactly {@code %%FAIL} or {@code %%FAIL IGNORE MSG} marks the next case as one that only
 *       compiles;
 *   <li>the next line that is not a skip line is the program; for a case without a marker the line after it is the
 *       input, taken raw;
 *   <li>the expected lines run until a skip line or the end of the file.
 * </ul>
 *
 * A line of 4096 bytes or more, its LF included, is rejected: jq's {@code fgets} would split it.
 */
public final class JqTestFile {

  private static final String FAIL = "%%FAIL";
  private static final String FAIL_IGNORE_MSG = "%%FAIL IGNORE MSG";
  /** jq reads a line into {@code char buf[4096]} with {@code fgets}: at most 4095 bytes, its LF included. */
  private static final int MAX_LINE_BYTES = 4095;
  private static final int ID_HEX_DIGITS = 12;

  private JqTestFile() {}

  /**
   * The cases of one file, in file order.
   *
   * @param file the file name, the first part of each id
   * @throws IllegalArgumentException for a line of 4096 bytes or more, or a program without an input line
   */
  public static List<JqTestCase> parse(String file, String text) {
    String[] split = text.split("\n", -1);
    // The element after the last LF is a line only if it is not empty.
    int n = split[split.length - 1].isEmpty() ? split.length - 1 : split.length;
    for (int i = 0; i < n; i++) {
      int bytes = split[i].getBytes(UTF_8).length + (i < split.length - 1 ? 1 : 0);
      if (bytes > MAX_LINE_BYTES) {
        throw new IllegalArgumentException(file + ":" + (i + 1) + ": the line is " + bytes + " bytes; jq's test runner reads at most " + MAX_LINE_BYTES);
      }
    }
    List<JqTestCase> cases = new ArrayList<>();
    Map<String, Integer> seen = new HashMap<>();
    String marker = null;
    int markerLine = 0;
    int i = 0;
    while (i < n) {
      String line = split[i++];
      if (skipLine(line)) continue;
      if (line.equals(FAIL) || line.equals(FAIL_IGNORE_MSG)) {
        marker = line;
        markerLine = i;
        continue;
      }
      List<String> own = new ArrayList<>();
      if (marker != null) own.add(marker);
      own.add(line);
      int start = marker != null ? markerLine : i;
      String input = null;
      if (marker == null) {
        if (i == n) throw new IllegalArgumentException(file + ":" + i + ": the program has no input line");
        input = split[i++];
        own.add(input);
      }
      List<String> expected = new ArrayList<>();
      while (i < n && !skipLine(split[i])) expected.add(split[i++]);
      own.addAll(expected);
      String base = file + "#" + sha256Hex(String.join("\n", own)).substring(0, ID_HEX_DIGITS);
      int copy = seen.merge(base, 1, Integer::sum);
      cases.add(new JqTestCase(copy == 1 ? base : base + "~" + copy, start, mode(marker), line, input, expected));
      marker = null;
    }
    return cases;
  }

  private static JqTestCase.Mode mode(String marker) {
    if (marker == null) return JqTestCase.Mode.RESULTS;
    return marker.equals(FAIL) ? JqTestCase.Mode.FAIL : JqTestCase.Mode.FAIL_IGNORE_MSG;
  }

  /** jq_test.c's {@code skipline}: blank, or {@code #} after spaces and tabs. */
  private static boolean skipLine(String line) {
    int p = 0;
    while (p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) p++;
    return p == line.length() || line.charAt(p) == '#';
  }

  private static String sha256Hex(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JDK has SHA-256", e);
    }
  }
}
