package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.cli.Invocation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.ToIntFunction;

/**
 * Runs a differential case through the command line's in-process entry and judges it against its recording (ADR-0020,
 * ADR-0024). The expected outcome is the recording's; for a deviation case, each part its {@code jqj-*} keys give
 * replaces the recording's part (ADR-0020). The rules, in order:
 *
 * <ol>
 *   <li>a deviation case whose slug names no row of {@code DEVIATIONS.md} is an error, before jqj runs, and so is a case
 *       with {@code limit recursion-depth} for a runner without a limited entry;
 *   <li>an exception out of the entry is FAIL if its message carries the Stub prefix, otherwise CRASH;
 *   <li>jqj's stderr carrying the Stub prefix is FAIL;
 *   <li>a case without a recording, or whose def differs from the recording's, is STALE;
 *   <li>a deviation case whose recording equals every part its {@code jqj-*} keys give is FAIL, "deviation gone";
 *   <li>otherwise the exit code, stdout and stderr must equal the expected outcome byte for byte, except that stderr
 *       is not compared when the expected stderr has a line starting {@code jq: error: syntax error}: PASS or FAIL.
 * </ol>
 */
public final class DiffRunner {

  private static final String SYNTAX_ERROR = "\njq: error: syntax error";
  /** Bytes shown of a differing stream, starting a little before the first difference. */
  private static final int WINDOW = 200;
  private static final int BEFORE = 40;

  private final ToIntFunction<Invocation> entry;
  private final LongFunction<ToIntFunction<Invocation>> limited;
  private final Path scratch;
  private final Set<String> deviations;

  /**
   * A runner of the cases without {@code limit recursion-depth}.
   *
   * @param entry the in-process entry, {@code Main::run}
   * @param scratch where each run's working directory is made and deleted again
   * @param deviations the slugs that {@code DEVIATIONS.md} names, {@link Deviations#slugs}
   */
  public DiffRunner(ToIntFunction<Invocation> entry, Path scratch, Set<String> deviations) {
    this(entry, null, scratch, deviations);
  }

  /**
   * @param entry the in-process entry, {@code Main::run}
   * @param limited the entry of a case with {@code limit recursion-depth n}, given {@code n}: jqj under that budget
   *     (ADR-0047 #118); null for a runner of no such case
   * @param scratch where each run's working directory is made and deleted again
   * @param deviations the slugs that {@code DEVIATIONS.md} names, {@link Deviations#slugs}
   */
  public DiffRunner(ToIntFunction<Invocation> entry, LongFunction<ToIntFunction<Invocation>> limited, Path scratch, Set<String> deviations) {
    this.entry = entry;
    this.limited = limited;
    this.scratch = scratch;
    this.deviations = Set.copyOf(deviations);
  }

  /**
   * The verdict of one case; {@code recording} is null when the case was never recorded. A case with {@code limit
   * recursion-depth} runs through the limited entry.
   *
   * @throws IllegalArgumentException when the case's deviation slug names no row of {@code DEVIATIONS.md}, or when the
   *     case has {@code limit recursion-depth} and this runner has no limited entry
   */
  public Verdict run(DiffCase c, Recording recording) {
    if (c.deviation() != null && !deviations.contains(c.deviation())) {
      throw new IllegalArgumentException("case " + c.id() + ": deviation '" + c.deviation() + "' names no row of DEVIATIONS.md");
    }
    ToIntFunction<Invocation> cli = entry;
    if (c.recursionDepth() != null) {
      if (limited == null) {
        throw new IllegalArgumentException("case " + c.id() + ": limit recursion-depth, and this runner has no limited entry");
      }
      cli = limited.apply(c.recursionDepth());
    }
    Outcome jqj;
    try (CaseDirectory dir = CaseDirectory.of(c.files(), scratch)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      Invocation invocation =
          new Invocation(c.args(), c.environment(dir.path()), dir.path(), new ByteArrayInputStream(c.stdin()), out, err);
      int rc;
      try {
        rc = cli.applyAsInt(invocation);
      } catch (RuntimeException e) {
        String line = CaseFormat.escape(e.toString().getBytes(UTF_8));
        if (String.valueOf(e.getMessage()).contains(Verdict.STUB)) {
          return new Verdict(Verdict.Kind.FAIL, "jqj ended with a Stub: " + line, c.id() + ": FAIL, jqj ended with a Stub: " + e);
        }
        return new Verdict(Verdict.Kind.CRASH, line, c.id() + ": CRASH, the entry threw " + e);
      }
      jqj = new Outcome(rc, out.toByteArray(), err.toByteArray());
    }
    return judge(c, recording, jqj);
  }

  private static Verdict judge(DiffCase c, Recording recording, Outcome jqj) {
    // Where the expected outcome comes from, as the diff names it.
    boolean deviation = c.deviation() != null;
    String name = deviation ? "expect" : "jq-ref";
    String against = deviation ? " against deviation " + c.deviation() + "'s jqj-* keys, the recording where one is absent" : "";
    if (new String(jqj.stderr(), UTF_8).contains(Verdict.STUB)) {
      String headline = "FAIL" + against + ", jqj ended with a Stub";
      String diff =
          recording == null
              ? c.id() + ": " + headline + "; the case has no recording\n"
              : diff(c.id(), headline, name, c.jqj().over(recording.outcome()), jqj, true);
      return new Verdict(Verdict.Kind.FAIL, "jqj ended with a Stub", diff);
    }
    if (recording == null) {
      String why = "no recording; run scripts/oracle-record.sh";
      return new Verdict(Verdict.Kind.STALE, why, c.id() + ": STALE, " + why);
    }
    if (!recording.def().equals(c.def())) {
      String why = "the case's def " + c.def() + " differs from the recording's " + recording.def() + "; re-record with scripts/oracle-record.sh";
      return new Verdict(Verdict.Kind.STALE, why, c.id() + ": STALE, " + why);
    }
    if (deviation && c.jqj().matches(recording.outcome())) {
      String why = "jq-ref's recording equals the jqj-* keys of deviation " + c.deviation() + "; drop the case's deviation and jqj-* lines, and the DEVIATIONS.md row if no case is left";
      return new Verdict(Verdict.Kind.FAIL, "deviation gone", c.id() + ": FAIL, deviation gone: " + why + "\n");
    }
    Outcome ref = c.jqj().over(recording.outcome());
    boolean compareStderr = !("\n" + new String(ref.stderr(), UTF_8)).contains(SYNTAX_ERROR);
    List<String> why = new ArrayList<>();
    if (ref.rc() != jqj.rc()) why.add("rc " + jqj.rc() + ", " + name + " " + ref.rc());
    int d = Arrays.mismatch(ref.stdout(), jqj.stdout());
    if (d >= 0) why.add("stdout differs at byte " + d);
    d = Arrays.mismatch(ref.stderr(), jqj.stderr());
    if (compareStderr && d >= 0) why.add("stderr differs at byte " + d);
    if (why.isEmpty()) return new Verdict(Verdict.Kind.PASS, compareStderr ? "" : "stderr not compared: syntax error", "");
    return new Verdict(Verdict.Kind.FAIL, String.join("; ", why), diff(c.id(), "FAIL" + against, name, ref, jqj, compareStderr));
  }

  /**
   * The readable comparison: each part either "same" or the expected and jqj's bytes, escaped as in the case format.
   *
   * @param name what the expected outcome is called: {@code jq-ref}, or {@code expect} for a deviation case
   */
  private static String diff(String id, String headline, String name, Outcome ref, Outcome jqj, boolean compareStderr) {
    StringBuilder b = new StringBuilder(id).append(": ").append(headline).append('\n');
    if (ref.rc() == jqj.rc()) b.append("  rc      same\n");
    else b.append("  rc      ").append(name).append(' ').append(ref.rc()).append("\n          jqj    ").append(jqj.rc()).append('\n');
    stream(b, "stdout", name, ref.stdout(), jqj.stdout());
    if (compareStderr) stream(b, "stderr", name, ref.stderr(), jqj.stderr());
    else b.append("  stderr  not compared: jq-ref's is a syntax error (ADR-0020)\n");
    return b.toString();
  }

  private static void stream(StringBuilder b, String part, String name, byte[] ref, byte[] jqj) {
    String label = String.format("  %-8s", part);
    int d = Arrays.mismatch(ref, jqj);
    if (d < 0) {
      b.append(label).append("same\n");
      return;
    }
    b.append(label).append(name).append(' ').append(window(ref, d)).append('\n');
    b.append("          jqj    ").append(window(jqj, d)).append('\n');
    b.append("          first difference at byte ").append(d).append('\n');
  }

  /** Up to {@value #WINDOW} bytes from a little before the first difference, escaped. */
  private static String window(byte[] bytes, int difference) {
    if (bytes.length == 0) return "(empty)";
    int from = Math.max(0, difference - BEFORE);
    int to = Math.min(bytes.length, from + WINDOW);
    String shown = CaseFormat.escape(Arrays.copyOfRange(bytes, from, to));
    return (from > 0 ? "..." : "") + shown + (to < bytes.length ? "... (" + bytes.length + " bytes)" : "");
  }
}
