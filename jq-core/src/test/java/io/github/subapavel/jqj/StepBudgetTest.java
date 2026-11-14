package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.letters;
import static io.github.subapavel.jqj.RunBudgets.printed;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.repeat;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static io.github.subapavel.jqj.RunBudgets.writer;
import static io.github.subapavel.jqj.TestDocuments.assertStale;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The step budget at the public API (ADR-0030, ADR-0008, #59). Before any loop exists the ticks are the reader's and the
 * writer's, one per started KiB (ADR-0015, F-23): a main read primes the Workspace's run state and the run that
 * consumes the document keeps the read's ticks; the writer ticks per drain of its 64 KiB buffer (ADR-0027). A run may
 * take exactly its budget of ticks; the next tick ends it with {@code JqTermination(BUDGET, STEPS)}.
 */
class StepBudgetTest {

  @Test
  void readTicksCountAgainstStepBudget() {
    JqEngine engine = steps(10);
    JqFilter dot = engine.compile(".");
    Workspace workspace = engine.newWorkspace();

    // 10 238 letters: 10 240 bytes, 10 ticks, the whole budget. The sink neither writes nor ticks.
    JqValue tenKiB = read(workspace, letters(10_238)).root();
    Results results = new Results();
    assertThat(dot.run(tenKiB, NONE, results, workspace)).as("run's answer over 10 240 bytes read").isTrue();
    assertThat(results.values).as("results over 10 240 bytes read").singleElement().isInstanceOf(JqString.class);
    assertThat(((JqString) results.values.get(0)).value()).as("the result's text").hasSize(10_238);

    // 10 239 letters: 10 241 bytes, 11 ticks. The run ends before it emits anything.
    JqValue overTenKiB = read(workspace, letters(10_239)).root();
    Results none = new Results();
    assertBudget(() -> dot.run(overTenKiB, NONE, none, workspace), Budget.STEPS, "run over 10 241 bytes read with 10 steps");
    assertThat(none.values).as("results of the run over 10 241 bytes read").isEmpty();

    // The bytes read count, not the size of the value: 1 and 10 240 spaces is 10 241 bytes, 11 ticks.
    JqValue spacedOne = read(workspace, bytes("1" + " ".repeat(10_240))).root();
    assertBudget(() -> dot.run(spacedOne, NONE, new Results(), workspace), Budget.STEPS, "run over 1 and 10 240 spaces with 10 steps");
    // Witness: 1 and 10 239 spaces, 10 240 bytes, completes.
    JqValue one = read(workspace, bytes("1" + " ".repeat(10_239))).root();
    Results number = new Results();
    assertThat(dot.run(one, NONE, number, workspace)).as("run's answer over 1 and 10 239 spaces").isTrue();
    assertThat(number.values).as("results over 1 and 10 239 spaces").singleElement().isInstanceOf(JqNumber.class);
    assertThat(((JqNumber) number.values.get(0)).longValue()).as("the result").isEqualTo(1L);
  }

  @Test
  void zeroStepsEndsAtFirstTick() {
    JqEngine engine = steps(0);
    JqFilter dot = engine.compile(".");

    // A run with no tick at all: nothing was read on this Workspace and the sink does not write.
    Workspace idle = engine.newWorkspace();
    Results results = new Results();
    assertThat(dot.run(JqNull.NULL, NONE, results, idle)).as("run's answer for a run without a tick").isTrue();
    assertThat(results.values).as("results of the run without a tick").containsExactly(JqNull.NULL);

    // The read's one tick: the document 1, one byte.
    JqValue one = read(idle, bytes("1")).root();
    Results none = new Results();
    assertBudget(() -> dot.run(one, NONE, none, idle), Budget.STEPS, "run over one byte read with 0 steps");
    assertThat(none.values).as("results of the run over one byte read").isEmpty();

    // The writer's one tick: null and an LF, 5 bytes, on a Workspace that read nothing.
    Workspace writing = engine.newWorkspace();
    assertBudget(
        () -> dot.run(JqNull.NULL, NONE, writer(writing, new RecordingTarget()), writing), Budget.STEPS, "run writing null with 0 steps");
    // Witness: one step is enough for that tick. jq-ref 1.8.2: null under -c . prints null and an LF.
    JqEngine oneStep = steps(1);
    Workspace witness = oneStep.newWorkspace();
    RecordingTarget target = new RecordingTarget();
    assertThat(oneStep.compile(".").run(JqNull.NULL, NONE, writer(witness, target), witness)).as("run's answer writing null with 1 step").isTrue();
    assertThat(target.text()).as("output writing null with 1 step").isEqualTo("null\n");
  }

  @Test
  void writerTicksCountAgainstStepBudget() {
    // The documents are read on a Workspace of another engine: its read ticks are its own, so only the writer's ticks
    // reach the run's Workspace.
    Workspace reading = JqEngine.builder().build().newWorkspace();
    JqEngine engine = steps(10);
    JqFilter dot = engine.compile(".");
    Workspace workspace = engine.newWorkspace();

    // 10 237 letters print 10 240 bytes in one drain: 10 ticks, the whole budget.
    JqValue tenKiB = read(reading, letters(10_237)).root();
    RecordingTarget target = new RecordingTarget();
    assertThat(dot.run(tenKiB, NONE, writer(workspace, target), workspace)).as("run's answer printing 10 240 bytes").isTrue();
    assertThat(target.text()).as("output printing 10 240 bytes").isEqualTo(printed(10_237));

    // 10 238 letters print 10 241 bytes: 11 ticks.
    JqValue overTenKiB = read(reading, letters(10_238)).root();
    assertBudget(
        () -> dot.run(overTenKiB, NONE, writer(workspace, new RecordingTarget()), workspace), Budget.STEPS, "run printing 10 241 bytes with 10 steps");
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 64);
  }

  /**
   * The cut is exact: a run over a document read with {@code r} ticks that prints one line of {@code w} ticks completes
   * under a budget of r + w steps and ends under r + w - 1. Each seed picks r and w first, as KiB counts, and then builds
   * the document from them: a string whose printed line (the string and an LF, at most 64 KiB, so one drain) is longer
   * than w - 1 KiB and at most w KiB, followed or preceded by spaces up to a length longer than r - 1 KiB and at most r
   * KiB (ADR-0015, F-23: one tick per started KiB).
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void stepBudgetCutsExactly(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    int writeTicks = random.nextInt(1, 65);
    int readTicks = writeTicks + random.nextInt(0, 21);
    // The printed line: the letters, two quotes and the LF, so at least 3 bytes.
    int line = Math.max(3, random.nextInt((writeTicks - 1) * 1024 + 1, writeTicks * 1024 + 1));
    int letters = line - 3;
    int text = letters + 2;
    int length = random.nextInt(Math.max(text, (readTicks - 1) * 1024 + 1), readTicks * 1024 + 1);
    int before = random.nextInt(0, length - text + 1);
    byte[] document = new byte[length];
    Arrays.fill(document, (byte) ' ');
    System.arraycopy(letters(letters), 0, document, before, text);
    long ticks = readTicks + writeTicks;
    String what = String.format("seed %d: %d letters at %d in %d bytes (%d ticks read, %d printed)", seed, letters, before, length, readTicks, writeTicks);

    JqEngine under = steps(ticks - 1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, document).root();
    assertBudget(() -> under.compile(".").run(cutRoot, NONE, writer(cut, new RecordingTarget()), cut), Budget.STEPS, what + " with one step too few");

    JqEngine exact = steps(ticks);
    Workspace whole = exact.newWorkspace();
    JqValue wholeRoot = read(whole, document).root();
    RecordingTarget target = new RecordingTarget();
    assertThat(exact.compile(".").run(wholeRoot, NONE, writer(whole, target), whole)).as("%s: run's answer with exactly its steps", what).isTrue();
    assertThat(target.text()).as("%s: output with exactly its steps", what).isEqualTo(printed(letters));
  }

  /** 1 048 576 letters: printed as 1 048 579 bytes, 16 drains of 64 KiB and 3 bytes, 1 025 ticks (RunBudgets.letters). */
  private static final int MIB = 1 << 20;

  /**
   * The cut is exact past a refuel too (ADR-0030): five 1 MiB lines are 5 125 writer ticks, which cross the refuel
   * after 4 096. The document is read on another engine's Workspace, so only the writer ticks on the run's.
   */
  @Test
  void stepBudgetCutsExactlyPastARefuel() {
    JqValue mib = read(JqEngine.builder().build().newWorkspace(), letters(MIB)).root();

    JqEngine exact = steps(5_125);
    Workspace whole = exact.newWorkspace();
    RecordingTarget target = new RecordingTarget();
    assertThat(exact.compile(".").run(mib, NONE, repeat(writer(whole, target), 5), whole)).as("run's answer for five 1 MiB lines with 5 125 steps").isTrue();
    assertThat(target.bytes().length).as("bytes written for five 1 MiB lines with 5 125 steps").isEqualTo(5 * (MIB + 3));

    JqEngine under = steps(5_124);
    Workspace cut = under.newWorkspace();
    assertBudget(
        () -> under.compile(".").run(mib, NONE, repeat(writer(cut, new RecordingTarget()), 5), cut), Budget.STEPS, "five 1 MiB lines with 5 124 steps");
  }

  /**
   * The next run keeps the primed read's ticks only (ADR-0030, #77): a print outside a run between the read and the run
   * is not charged to it. 1 MiB of letters is 1 048 578 bytes read, 1 025 ticks, and prints as 1 048 579 bytes, 1 025
   * writer ticks; the sink of the run neither writes nor ticks.
   */
  @Test
  void writerTicksOutsideARunAreNotChargedToTheNextRun() {
    JqEngine engine = steps(1_100);
    Workspace workspace = engine.newWorkspace();
    JqValue mib = read(workspace, letters(MIB)).root();
    RecordingTarget outside = new RecordingTarget();
    assertThat(writer(workspace, outside).emit(mib)).as("emit's answer for 1 MiB printed outside a run").isTrue();
    assertThat(outside.bytes().length).as("bytes printed outside a run").isEqualTo(MIB + 3);
    Results results = new Results();
    assertThat(engine.compile(".").run(mib, NONE, results, workspace)).as("run's answer after 1 MiB read and printed, with 1 100 steps").isTrue();
    assertThat(results.values).as("results after 1 MiB read and printed").singleElement().isInstanceOf(JqString.class);
    assertThat(((JqString) results.values.get(0)).value()).as("the result's text").hasSize(MIB);

    // Witness: the read's 1 025 ticks still count, so 1 024 steps end the same sequence at the run's begin.
    JqEngine under = steps(1_024);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, letters(MIB)).root();
    assertThat(writer(cut, new RecordingTarget()).emit(cutRoot)).as("emit's answer for 1 MiB printed outside a run on the cut").isTrue();
    Results none = new Results();
    assertBudget(() -> under.compile(".").run(cutRoot, NONE, none, cut), Budget.STEPS, "run after 1 MiB read and printed, with 1 024 steps");
    assertThat(none.values).as("results of the run with 1 024 steps").isEmpty();
  }

  /**
   * The writer's key proof outside a run (ADR-0044, #85) is the writer's: its ticks count, but the next run does not
   * keep them, as a drain's. {@code {"k0":0,…,"k19":19}} reads in 1 tick, and its print proves 20 key entries; the 17
   * colliding keys of {@link RunBudgets#colliding()} read in 1 and their print's proof sorts them, 17 entry ticks and 64
   * compares. Either way the run of {@code .} after the print takes 1 step; a proof charged as a run's tick would end it.
   */
  @Test
  void theWritersKeyProofOutsideARunIsNotChargedToTheNextRun() {
    StringBuilder twenty = new StringBuilder("{");
    for (int i = 0; i < 20; i++) twenty.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":").append(i);
    for (String json : new String[] {twenty.append('}').toString(), RunBudgets.colliding()}) {
      JqEngine one = steps(1);
      Workspace workspace = one.newWorkspace();
      JqValue root = read(workspace, json.getBytes(US_ASCII)).root();
      RecordingTarget outside = new RecordingTarget();
      assertThat(writer(workspace, outside).emit(root)).as("emit's answer for %s printed outside a run", json).isTrue();
      assertThat(outside.text()).as("text of %s printed outside a run", json).isEqualTo(json + "\n");
      Results results = new Results();
      assertThat(one.compile(".").run(root, NONE, results, workspace)).as("run's answer after %s read and printed, with 1 step", json).isTrue();
      assertThat(results.values).as("results after %s read and printed", json).hasSize(1);

      // Witness: the read's tick still counts, so 0 steps end the same sequence at the run's begin.
      JqEngine zero = steps(0);
      Workspace cut = zero.newWorkspace();
      JqValue cutRoot = read(cut, json.getBytes(US_ASCII)).root();
      assertThat(writer(cut, new RecordingTarget()).emit(cutRoot)).as("emit's answer for %s printed outside a run on the cut", json).isTrue();
      assertBudget(() -> zero.compile(".").run(cutRoot, NONE, new Results(), cut), Budget.STEPS, "run after " + json + " read and printed, with 0 steps");
    }
  }

  /**
   * A target that reads a main document during a drain outside a run primes the run state anew (ADR-0030, #77): the
   * next run keeps that read's one tick, not the 64 ticks of the drain that charges after its write. The document
   * {@code ["a…a",1]} prints one full 64 KiB buffer before the comma, so the print then finds it stale
   * (PrintBufferEdgeTest).
   */
  @Test
  void writerTicksAfterAReadInItsTargetAreNotChargedToTheNextRun() {
    JqEngine one = steps(1);
    Workspace primed = printReadingInTarget(one);
    Results results = new Results();
    assertThat(one.compile(".").run(JqNull.NULL, NONE, results, primed)).as("run's answer after the target's read, with 1 step").isTrue();
    assertThat(results.values).as("results after the target's read, with 1 step").containsExactly(JqNull.NULL);

    // Witness: the target's read of 7 is a primed read, so its one tick counts against 0 steps.
    JqEngine zero = steps(0);
    Workspace cut = printReadingInTarget(zero);
    assertBudget(() -> zero.compile(".").run(JqNull.NULL, NONE, new Results(), cut), Budget.STEPS, "run after the target's read, with 0 steps");
  }

  /**
   * Reads {@code ["a…a",1]} (65 539 bytes, 65 ticks) on a new Workspace of {@code engine} and prints it outside a run
   * into a target that reads 7 on its first write; returns the Workspace, primed by that read.
   */
  private static Workspace printReadingInTarget(JqEngine engine) {
    byte[] fullBeforeComma = new byte[65_539];
    fullBeforeComma[0] = '[';
    System.arraycopy(letters(65_533), 0, fullBeforeComma, 1, 65_535);
    System.arraycopy(bytes(",1]"), 0, fullBeforeComma, 65_536, 3);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, fullBeforeComma).root();
    RecordingTarget reading =
        new RecordingTarget(
            n -> {
              if (n == 1) read(workspace, bytes("7"));
            });
    assertStale(() -> writer(workspace, reading).emit(root), "emit after its target read 7");
    assertThat(reading.bytes().length).as("bytes written before the document went stale").isEqualTo(65_536);
    return workspace;
  }

  /**
   * A read during a run is charged to that run (ADR-0030: reads during a run count too), also when it is a main read
   * on the run's own Workspace, as a sink or a writer's target may make (PrintBufferEdgeTest). The sink reads 20 000
   * letters, 20 002 bytes, 20 ticks; nothing else ticks, since the input is null and the sink writes nothing.
   */
  @Test
  void readDuringARunCountsAgainstItsSteps() {
    JqEngine twenty = steps(20);
    Workspace whole = twenty.newWorkspace();
    List<JqValue> readInside = new ArrayList<>();
    JqSink reading = v -> readInside.add(read(whole, letters(20_000)).root());
    assertThat(twenty.compile(".").run(JqNull.NULL, NONE, reading, whole)).as("run's answer for a 20-tick read inside it with 20 steps").isTrue();
    assertThat(readInside).as("documents read inside the run with 20 steps").singleElement().isInstanceOf(JqString.class);
    assertThat(((JqString) readInside.get(0)).value()).as("the document read inside the run").hasSize(20_000);

    JqEngine nineteen = steps(19);
    Workspace cut = nineteen.newWorkspace();
    assertBudget(
        () ->
            nineteen
                .compile(".")
                .run(
                    JqNull.NULL,
                    NONE,
                    v -> {
                      read(cut, letters(20_000));
                      return true;
                    },
                    cut),
        Budget.STEPS,
        "a 20-tick read inside a run with 19 steps");

    // Far under the read: 10 steps.
    JqEngine ten = steps(10);
    Workspace small = ten.newWorkspace();
    assertBudget(
        () ->
            ten.compile(".")
                .run(
                    JqNull.NULL,
                    NONE,
                    v -> {
                      read(small, letters(20_000));
                      return true;
                    },
                    small),
        Budget.STEPS,
        "a 20-tick read inside a run with 10 steps");
  }

  /**
   * {@code @text} charges ticks for the text it builds (ADR-0008: a native loop whose length depends on the data ticks,
   * the writer's included; #61). The document is {@code [1,1,…,1]} with 20 000 elements, 40 001 bytes: its read is 40
   * ticks (ADR-0015, one per started KiB), which {@code .} shows by completing with 40 steps and ending with 39. With
   * the same 40 steps, {@code @text} must end when it dumps those 40 001 bytes. The sinks neither write nor tick.
   */
  @Test
  void textFormatChargesTicksForItsDump() {
    byte[] ones = ones(20_000);
    JqEngine forty = steps(40);

    Workspace dotWorkspace = forty.newWorkspace();
    JqValue dotRoot = read(dotWorkspace, ones).root();
    Results dotted = new Results();
    assertThat(forty.compile(".").run(dotRoot, NONE, dotted, dotWorkspace)).as("run's answer for . over 40 001 bytes with 40 steps").isTrue();
    assertThat(dotted.values).as("results of . over 40 001 bytes").hasSize(1);
    JqEngine thirtyNine = steps(39);
    Workspace cut = thirtyNine.newWorkspace();
    JqValue cutRoot = read(cut, ones).root();
    assertBudget(() -> thirtyNine.compile(".").run(cutRoot, NONE, new Results(), cut), Budget.STEPS, ". over 40 001 bytes with 39 steps");

    Workspace textWorkspace = forty.newWorkspace();
    JqValue textRoot = read(textWorkspace, ones).root();
    Results none = new Results();
    assertBudget(() -> forty.compile("@text").run(textRoot, NONE, none, textWorkspace), Budget.STEPS, "@text over 40 001 bytes with 40 steps");
    assertThat(none.values).as("results of @text with 40 steps").isEmpty();

    // Witness: with steps to spare @text gives the array's compact text, here the document's own bytes (jq-ref 1.8.2,
    // recorded: literal/format-text-array, [1,"b",null] -> "[1,\"b\",null]").
    JqEngine spare = steps(1_000);
    Workspace spareWorkspace = spare.newWorkspace();
    JqValue spareRoot = read(spareWorkspace, ones).root();
    Results text = new Results();
    assertThat(spare.compile("@text").run(spareRoot, NONE, text, spareWorkspace)).as("run's answer for @text with 1 000 steps").isTrue();
    assertThat(text.values).as("results of @text with 1 000 steps").singleElement().isInstanceOf(JqString.class);
    assertThat(((JqString) text.values.get(0)).value()).as("the text of @text").isEqualTo(new String(ones, US_ASCII));
  }

  /**
   * {@code @text} charges one tick per started KiB of the text it builds (ADR-0008, ADR-0030; #80). The document's
   * 40 001 bytes are not a multiple of 1 024: its read is 40 ticks and its text 40 more (ceil(40 001 / 1 024) = 40;
   * a floor gives 39, a shift by 11 gives 20, a flat charge gives 1). So 80 steps complete and 79 end.
   */
  @Test
  void textFormatChargesOneTickPerStartedKiB() {
    byte[] ones = ones(20_000);
    JqEngine exact = steps(80);
    Workspace exactWorkspace = exact.newWorkspace();
    JqValue exactRoot = read(exactWorkspace, ones).root();
    Results text = new Results();
    assertThat(exact.compile("@text").run(exactRoot, NONE, text, exactWorkspace)).as("run's answer for @text over 40 001 bytes with 80 steps").isTrue();
    assertThat(text.values).as("results of @text with 80 steps").singleElement().isInstanceOf(JqString.class);
    assertThat(((JqString) text.values.get(0)).value()).as("the text of @text with 80 steps").isEqualTo(new String(ones, US_ASCII));

    JqEngine fewer = steps(79);
    Workspace fewerWorkspace = fewer.newWorkspace();
    JqValue fewerRoot = read(fewerWorkspace, ones).root();
    assertBudget(() -> fewer.compile("@text").run(fewerRoot, NONE, new Results(), fewerWorkspace), Budget.STEPS, "@text over 40 001 bytes with 79 steps");
  }

  /** {@code [1,1,…,1]} with {@code n} elements: 2n + 1 bytes. */
  private static byte[] ones(int n) {
    return ("[" + "1,".repeat(n - 1) + "1]").getBytes(US_ASCII);
  }

  private static byte[] bytes(String ascii) {
    return ascii.getBytes(US_ASCII);
  }
}
