package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0044 as amended by #95: Equality takes a read right side through its key index, its proof and one lookup per left
 * key in the proof's table, so two read objects of n keys compare in about linear time, not by a field read per left
 * member that scans the right side's n keys. WideObjectTest pins the steps; a lookup that charged what the ADR says and
 * still scanned the right side's keys would keep those pins and stay quadratic in time, so the time is bounded here.
 *
 * <p>The bound is wall time, taken in a fresh child JVM with no agent and no tool or JVM options, as ObjectKeysCostTest
 * takes it. The document is {@code [W,Wrev]}: {@link #KEYS} keys {@code "k0"} to {@code "k78840"}, the width of
 * F-40's object, then the same members in reverse order. The witness is {@code [.[0][]] == [.[0][]]} on it: two walks of
 * the left object through its proof, then a compare of two arrays of {@link #KEYS} numbers, a pass per member as the
 * key index has a lookup per member. It is timed first, in the same child, so a slow or busy machine slows both runs
 * alike. {@code .[0] == .[1]} may take at most {@link #FACTOR} times the witness plus {@link #SLACK_MS}; the witness
 * keeps an absolute bound, {@link #WITNESS_BOUND_MS}, so a change that slows both alike cannot pass the relative bound.
 * jq-ref 1.8.2, observed 2026-11-14 (the test-author's {@code .logs/95-jqref.log}): the document built by
 * {@code from_entries} over {@code range(78841)} and {@code range(78840; -1; -1)}, slurped by {@code -sc .}, is 2 320 796
 * bytes with the LF, and both filters give true.
 */
class KeyIndexTimeTest {

  /** The keys of each object: F-40's width. */
  static final int KEYS = 78_841;

  /** The most milliseconds the witness's run may take. */
  static final long WITNESS_BOUND_MS = 10_000;

  /** How many times the witness's milliseconds the comparison may take, plus {@link #SLACK_MS}. */
  static final long FACTOR = 4;

  /** Milliseconds the comparison may take on top of {@link #FACTOR} times the witness. */
  static final long SLACK_MS = 1_000;

  @TempDir Path dir;

  @Test
  void twoReadObjectsOf78841KeysCompareWithinFourTimesTheWitness() throws IOException, InterruptedException {
    String printed = runChild();
    String[] lines = printed.split("\n");
    assertThat(lines).as("lines the child JVM printed: %s", printed).hasSize(3);
    assertThat(Long.parseLong(lines[0])).as("bytes of [W,Wrev] of %d keys, jq-ref's -c line without its LF", KEYS).isEqualTo(2_320_795);
    String[] witness = lines[1].split(" ");
    String[] comparison = lines[2].split(" ");
    assertThat(witness).as("the witness's milliseconds and result: %s", lines[1]).hasSize(2);
    assertThat(comparison).as("the comparison's milliseconds and result: %s", lines[2]).hasSize(2);
    assertThat(witness[1]).as("result of [.[0][]] == [.[0][]] over [W,Wrev] of %d keys", KEYS).isEqualTo("true");
    assertThat(comparison[1]).as("result of .[0] == .[1] over [W,Wrev] of %d keys", KEYS).isEqualTo("true");
    long witnessMs = Long.parseLong(witness[0]);
    assertThat(witnessMs).as("milliseconds of [.[0][]] == [.[0][]] over [W,Wrev] of %d keys, the witness; printed %s", KEYS, printed).isLessThan(WITNESS_BOUND_MS);
    long bound = FACTOR * witnessMs + SLACK_MS;
    assertThat(Long.parseLong(comparison[0]))
        .as("milliseconds of .[0] == .[1] over [W,Wrev] of %d keys, against %d times the witness's %d ms plus %d ms", KEYS, FACTOR, witnessMs, SLACK_MS)
        .isLessThan(bound);
  }

  /** What {@link Child} printed, run in a fresh JVM, once it ended within 600 s with exit code 0. */
  private String runChild() throws IOException, InterruptedException {
    File out = dir.resolve("out").toFile();
    List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Child.class.getName());
    ProcessBuilder launch = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out);
    // Each would reach the child's command line, and an agent there would slow the runs it times.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    launch.environment().remove("_JAVA_OPTIONS");
    Process child = launch.start();
    try {
      assertThat(child.waitFor(600, TimeUnit.SECONDS)).as("the child JVM ends within 600 s").isTrue();
      String printed = Files.readString(out.toPath(), UTF_8);
      assertThat(child.exitValue()).as("the child JVM's exit code; it printed %s", printed).isZero();
      return printed;
    } finally {
      child.destroyForcibly();
    }
  }

  /**
   * The child JVM: it builds {@code [W,Wrev]} and prints its byte size, then runs the witness, {@code [.[0][]] ==
   * [.[0][]]}, then {@code .[0] == .[1]}, each read in a workspace of its own on an engine with no budget, and prints for
   * each the milliseconds its run took and its results' text, a line each.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) {
      byte[] document = document();
      System.out.println(document.length);
      System.out.println(timed("[.[0][]] == [.[0][]]", document));
      System.out.println(timed(".[0] == .[1]", document));
    }

    /** {@code [{"k0":0,…,"k78840":78840},{"k78840":78840,…,"k0":0}]}, compact, in ASCII. */
    private static byte[] document() {
      StringJoiner forward = new StringJoiner(",", "{", "}");
      StringJoiner backward = new StringJoiner(",", "{", "}");
      for (int i = 0; i < KEYS; i++) {
        forward.add("\"k" + i + "\":" + i);
        int j = KEYS - 1 - i;
        backward.add("\"k" + j + "\":" + j);
      }
      return ("[" + forward + "," + backward + "]").getBytes(US_ASCII);
    }

    /** The milliseconds of {@code filter}'s run over {@code document}, a space, and its results' texts joined by commas. */
    private static String timed(String filter, byte[] document) {
      JqEngine engine = RunBudgets.unlimited();
      JqFilter compiled = engine.compile(filter);
      Workspace workspace = engine.newWorkspace();
      JqValue root = RunBudgets.read(workspace, document).root();
      Results all = new Results();
      long start = System.nanoTime();
      compiled.run(root, NONE, all, workspace);
      long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      return millis + " " + all.values.stream().map(JqValue::toJson).collect(Collectors.joining(","));
    }
  }
}
