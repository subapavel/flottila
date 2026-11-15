package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A differential case (ADR-0020): a command line, its stdin, files and environment, never its expected output, which
 * only {@code scripts/oracle-record.sh} writes into the recording.
 *
 * <p>Keys: {@code arg} and {@code filter} are the command-line arguments in the order they appear (a case may put
 * options before or after the filter); {@code stdin} once; {@code file <name> <bytes>} and {@code env K=V}, each
 * repeatable; {@code deviation <slug>} once marks a case that pins a {@code DEVIATIONS.md} row (ADR-0020), and only
 * such a case may carry jqj's column of that row: {@code jqj-rc}, {@code jqj-stdout} and {@code jqj-stderr}, each at
 * most once. {@code limit recursion-depth <n>} once runs jqj under a recursion-depth budget of {@code n}, a positive
 * integer of at most {@link Integer#MAX_VALUE}, in every run of the case, the differential run and each compile mode's (ADR-0047 #118); reference jq
 * ignores it. Without {@code stdin} the input is empty.
 *
 * @param files file name to content, written into the working directory of each run
 * @param deviation the slug of the deviation the case pins, or null
 * @param jqj jqj's column: every part null unless the case gives its {@code jqj-*} key
 * @param recursionDepth the recursion-depth budget of {@code limit recursion-depth}, or null when the case has no such
 *     key
 */
public record DiffCase(
    String id,
    List<String> args,
    byte[] stdin,
    Map<String, byte[]> files,
    Map<String, String> env,
    String deviation,
    JqjColumn jqj,
    String def,
    Long recursionDepth) {

  /** The key that sets a jqj limit for every run of the case (ADR-0047 #118). */
  private static final String LIMIT = "limit";

  /** The one limit the {@code limit} key names. */
  private static final String RECURSION_DEPTH = "recursion-depth ";

  /** A case without {@code limit recursion-depth}. */
  public DiffCase(
      String id, List<String> args, byte[] stdin, Map<String, byte[]> files, Map<String, String> env, String deviation, JqjColumn jqj, String def) {
    this(id, args, stdin, files, env, deviation, jqj, def, null);
  }

  /**
   * jqj's column of a deviation case: what jqj gives where it differs from jq-ref on purpose. Each part is null when
   * the case has no key for it.
   *
   * @param rc {@code jqj-rc}
   * @param stdout {@code jqj-stdout}, unescaped
   * @param stderr {@code jqj-stderr}, unescaped
   */
  public record JqjColumn(Integer rc, byte[] stdout, byte[] stderr) {

    /** The column of a case without {@code jqj-*} keys. */
    public static final JqjColumn NONE = new JqjColumn(null, null, null);

    /**
     * Whether every part this column gives equals that part of jq-ref's recording, so the deviation is gone (ADR-0020).
     * A column that gives no part matches.
     */
    public boolean matches(Outcome recorded) {
      return (rc == null || rc == recorded.rc())
          && (stdout == null || Arrays.equals(stdout, recorded.stdout()))
          && (stderr == null || Arrays.equals(stderr, recorded.stderr()));
    }

    /** What jqj must give: each part of this column, and jq-ref's recorded part where this column has none. */
    public Outcome over(Outcome recorded) {
      return new Outcome(
          rc == null ? recorded.rc() : rc, stdout == null ? recorded.stdout() : stdout, stderr == null ? recorded.stderr() : stderr);
    }
  }

  /** The cases of one {@code <family>.cases} file, in file order. */
  public static List<DiffCase> read(Path file) {
    List<DiffCase> cases = new ArrayList<>();
    for (CaseFormat.Block b : CaseFormat.read(file)) cases.add(of(file.getFileName().toString(), b));
    return cases;
  }

  /**
   * The case of one block.
   *
   * @throws IllegalArgumentException naming {@code where} and the line of an unknown key, a repeated {@code stdin},
   *     {@code deviation}, {@code limit} or {@code jqj-*} key, a {@code file} without a name, an {@code env} without
   *     {@code =}, a {@code jqj-rc} that is not an integer, a {@code limit} that is not {@code recursion-depth} and a
   *     positive integer, a malformed escape, or the first {@code jqj-*} key of a case without {@code deviation}
   */
  public static DiffCase of(String where, CaseFormat.Block block) {
    List<String> args = new ArrayList<>();
    byte[] stdin = null;
    Map<String, byte[]> files = new LinkedHashMap<>();
    Map<String, String> env = new LinkedHashMap<>();
    String deviation = null;
    Integer jqjRc = null;
    byte[] jqjStdout = null;
    byte[] jqjStderr = null;
    Long recursionDepth = null;
    CaseFormat.Line firstJqj = null;
    for (CaseFormat.Line line : block.lines()) {
      String at = where + ":" + line.number() + ": ";
      try {
        switch (line.key()) {
          case "arg", "filter" -> args.add(new String(CaseFormat.unescape(line.value()), UTF_8));
          case "stdin" -> {
            if (stdin != null) throw new IllegalArgumentException("'stdin' given twice");
            stdin = CaseFormat.unescape(line.value());
          }
          case "file" -> {
            String[] nameAndBytes = line.value().split(" ", 2);
            if (nameAndBytes[0].isEmpty()) throw new IllegalArgumentException("'file' needs a name");
            files.put(nameAndBytes[0], CaseFormat.unescape(nameAndBytes.length == 2 ? nameAndBytes[1] : ""));
          }
          case "env" -> {
            String kv = new String(CaseFormat.unescape(line.value()), UTF_8);
            int eq = kv.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("'env' needs K=V");
            env.put(kv.substring(0, eq), kv.substring(eq + 1));
          }
          case "deviation" -> {
            if (deviation != null) throw new IllegalArgumentException("'deviation' given twice");
            if (line.value().isEmpty()) throw new IllegalArgumentException("'deviation' needs a slug");
            deviation = line.value();
          }
          case "jqj-rc" -> {
            if (jqjRc != null) throw new IllegalArgumentException("'jqj-rc' given twice");
            try {
              jqjRc = Integer.parseInt(line.value());
            } catch (NumberFormatException e) {
              throw new IllegalArgumentException("'jqj-rc' needs an integer, not '" + line.value() + "'", e);
            }
          }
          case "jqj-stdout" -> {
            if (jqjStdout != null) throw new IllegalArgumentException("'jqj-stdout' given twice");
            jqjStdout = CaseFormat.unescape(line.value());
          }
          case "jqj-stderr" -> {
            if (jqjStderr != null) throw new IllegalArgumentException("'jqj-stderr' given twice");
            jqjStderr = CaseFormat.unescape(line.value());
          }
          case LIMIT -> {
            if (recursionDepth != null) throw new IllegalArgumentException("'limit' given twice");
            recursionDepth = recursionDepth(line.value());
          }
          default ->
              throw new IllegalArgumentException(
                  "unknown key '" + line.key() + "': a case holds the command line only; its expected output is"
                      + " recorded by scripts/oracle-record.sh, never written");
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(at + "case " + block.id() + ": " + e.getMessage(), e);
      }
      if (firstJqj == null && line.key().startsWith("jqj-")) firstJqj = line;
    }
    if (firstJqj != null && deviation == null) {
      throw new IllegalArgumentException(
          where + ":" + firstJqj.number() + ": case " + block.id() + ": '" + firstJqj.key() + "' needs 'deviation': only a"
              + " deviation case carries jqj's column (ADR-0020)");
    }
    JqjColumn jqj = new JqjColumn(jqjRc, jqjStdout, jqjStderr);
    return new DiffCase(block.id(), List.copyOf(args), stdin == null ? new byte[0] : stdin, files, env, deviation, jqj, block.def(), recursionDepth);
  }

  /** The budget of a {@code limit} line's value {@code recursion-depth <n>}, {@code n} an integer in 1..{@link Integer#MAX_VALUE}. */
  private static long recursionDepth(String value) {
    String why = "'limit' needs 'recursion-depth <n>' with an integer n in 1.." + Integer.MAX_VALUE + ", not '" + value + "'";
    if (!value.startsWith(RECURSION_DEPTH)) throw new IllegalArgumentException(why);
    long n;
    try {
      n = Long.parseLong(value.substring(RECURSION_DEPTH.length()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(why, e);
    }
    // Limits takes the budget as an int, so a larger one is refused here, not when the runner builds the Limits.
    if (n <= 0 || n > Integer.MAX_VALUE) throw new IllegalArgumentException(why);
    return n;
  }

  /** The environment of a run: exactly {@code HOME=<home> TZ=UTC LC_ALL=C} plus the case's {@code env} (ADR-0020). */
  public Map<String, String> environment(Path home) {
    Map<String, String> all = new LinkedHashMap<>();
    all.put("HOME", home.toString());
    all.put("TZ", "UTC");
    all.put("LC_ALL", "C");
    all.putAll(env);
    return all;
  }
}
