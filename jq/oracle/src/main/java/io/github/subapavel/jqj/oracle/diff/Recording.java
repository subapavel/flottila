package io.github.subapavel.jqj.oracle.diff;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The reference jq's outcome for one case, from a {@code <family>.rec} file that only {@code scripts/oracle-record.sh}
 * writes (ADR-0020). Its first line is {@code # jq-<version> sha256 <hex of the binary>}; each block holds {@code def},
 * {@code rc}, {@code stdout} and {@code stderr}, once each.
 *
 * @param def the def of the case block it was recorded from
 */
public record Recording(String id, String def, Outcome outcome) {

  /** The recordings of a {@code .rec} file by case id; none when the file does not exist. */
  public static Map<String, Recording> read(Path file) {
    Map<String, Recording> out = new LinkedHashMap<>();
    if (!Files.exists(file)) return out;
    for (CaseFormat.Block b : CaseFormat.read(file)) out.put(b.id(), of(file.getFileName().toString(), b));
    return out;
  }

  /**
   * The recording of one block.
   *
   * @throws IllegalArgumentException on a missing, repeated or unknown key, or an {@code rc} that is not an integer
   */
  public static Recording of(String where, CaseFormat.Block block) {
    Map<String, String> values = new HashMap<>();
    for (CaseFormat.Line line : block.lines()) {
      String at = where + ":" + line.number() + ": case " + block.id() + ": ";
      switch (line.key()) {
        case "def", "rc", "stdout", "stderr" -> {
          if (values.put(line.key(), line.value()) != null) throw new IllegalArgumentException(at + "'" + line.key() + "' given twice");
        }
        default -> throw new IllegalArgumentException(at + "unknown key '" + line.key() + "' in a recording");
      }
    }
    String at = where + ":" + block.number() + ": case " + block.id() + ": ";
    if (values.size() != 4) throw new IllegalArgumentException(at + "a recording needs def, rc, stdout and stderr");
    int rc;
    try {
      rc = Integer.parseInt(values.get("rc"));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(at + "rc '" + values.get("rc") + "' is not an integer", e);
    }
    return new Recording(
        block.id(),
        values.get("def"),
        new Outcome(rc, CaseFormat.unescape(values.get("stdout")), CaseFormat.unescape(values.get("stderr"))));
  }

  /** The block of this recording as {@code scripts/oracle-record.sh} writes it, LF-terminated. */
  public String block() {
    StringBuilder b = new StringBuilder();
    b.append("case ").append(id).append('\n');
    b.append("def ").append(def).append('\n');
    b.append("rc ").append(outcome.rc()).append('\n');
    line(b, "stdout", outcome.stdout());
    line(b, "stderr", outcome.stderr());
    b.append("end\n");
    return b.toString();
  }

  private static void line(StringBuilder b, String key, byte[] value) {
    b.append(key);
    if (value.length > 0) b.append(' ').append(CaseFormat.escape(value));
    b.append('\n');
  }

  /** The text of a recording file: its header, then each block followed by a blank line. */
  public static byte[] file(String header, Iterable<Recording> recordings) {
    StringBuilder b = new StringBuilder(header).append('\n');
    for (Recording r : recordings) b.append('\n').append(r.block());
    return b.toString().getBytes(StandardCharsets.UTF_8);
  }
}
