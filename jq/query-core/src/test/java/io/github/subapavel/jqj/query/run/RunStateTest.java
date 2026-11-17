package io.github.subapavel.jqj.query.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.query.internal.interpret.LiftedFunction;
import io.github.subapavel.jqj.query.run.Termination.Budget;
import java.util.Map;
import java.util.function.LongSupplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * The run state below the public API (ADR-0030, #59), where a budget's largest value can be given directly. An
 * unlimited budget holds {@code Long.MAX_VALUE}: the engine's unlimited count, and its unlimited time as nanoseconds
 * ({@code Duration.ofNanos(Long.MAX_VALUE).toNanos()} is {@code Long.MAX_VALUE}, one nanosecond more throws
 * {@code ArithmeticException}; observed on JDK 25 on 2026-11-12). {@code System.nanoTime() + Long.MAX_VALUE} was
 * negative in the same observation, so a deadline kept as an end time would have passed at the first refuel.
 *
 * <p>The calls are the run state's protocol as ADR-0030 and ADR-0008 name it: {@code begin} with the run's steps,
 * deadline in nanoseconds and bytes written; {@code tick()} and {@code tick(int n)}; the charge of one drain's bytes
 * before it is written; {@code steps()}, the ticks counted in the run.
 */
class RunStateTest {
  /** 3 x (2^31 - 1): three of the largest ticks a call can charge, past what an int counts. */
  private static final long THREE_LARGEST = 3L * Integer.MAX_VALUE;

  @Test
  void unlimitedNeverOverflows() {
    RunState unlimited = new RunState();
    unlimited.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    for (int i = 0; i < 3; i++) {
      unlimited.chargeWritten(Integer.MAX_VALUE);
    }
    // Each tick of 2^31 - 1 overdraws the fuel, so each refuels: the step budget, the deadline and the interrupt are
    // checked three times against their largest values.
    for (int i = 0; i < 3; i++) {
      unlimited.tick(Integer.MAX_VALUE);
    }
    unlimited.tick();
    assertThat(unlimited.steps()).as("ticks counted with no step budget").isEqualTo(6_442_450_942L);
    unlimited.end();

    // Witness: the same ticks against a budget of exactly 3 x (2^31 - 1) steps pass, and the next one ends the run, so
    // the ticks above were checked and counted past 2^31.
    RunState steps = new RunState();
    steps.begin(THREE_LARGEST, Long.MAX_VALUE, Long.MAX_VALUE);
    for (int i = 0; i < 3; i++) {
      steps.tick(Integer.MAX_VALUE);
    }
    assertThat(steps.steps()).as("ticks counted at the step budget").isEqualTo(THREE_LARGEST);
    assertBudget(steps::tick, Budget.STEPS, "the tick after 3 x (2^31 - 1) steps");

    // Witness: the same charges against exactly 3 x (2^31 - 1) bytes pass, and one more byte ends the run.
    RunState written = new RunState();
    written.begin(Long.MAX_VALUE, Long.MAX_VALUE, THREE_LARGEST);
    for (int i = 0; i < 3; i++) {
      written.chargeWritten(Integer.MAX_VALUE);
    }
    assertBudget(() -> written.chargeWritten(1), Budget.BYTES_WRITTEN, "one byte after 3 x (2^31 - 1) bytes");
  }

  /** ADR-0008, ADR-0017: refuel's interval. */
  private static final int INTERVAL = 4096;

  /**
   * Refuel comes every 4096 ticks, and only there is the thread's interrupt seen (ADR-0030, ADR-0017): a run interrupted
   * after one refuel goes on for a whole interval, then ends with CANCELLED at the next tick, the flag still set.
   */
  @Test
  void interruptEndsTheRunAtTheNextRefuel() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    try {
      // The first refuel, at tick 4097, passes: nothing is over and the thread is not interrupted.
      for (int i = 0; i < INTERVAL + 1; i++) {
        state.tick();
      }
      assertThat(state.steps()).as("ticks after the first refuel").isEqualTo(INTERVAL + 1L);

      Thread.currentThread().interrupt();
      // A whole interval of ticks after the refuel reaches no refuel, so the interrupt is not seen yet.
      state.tick(INTERVAL);
      assertThat(state.steps()).as("ticks after one more interval").isEqualTo(2L * INTERVAL + 1);
      Termination cancelled = assertThatExceptionOfType(Termination.class).as("the tick after it").isThrownBy(state::tick).actual();
      assertThat(cancelled.cause()).as("cause of the interrupted run").isEqualTo(Termination.Cause.CANCELLED);
      assertThat(cancelled.budget()).as("budget of the interrupted run").isNull();
      assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag after the run was cancelled").isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  /** A deadline in nanoseconds for the fake clock's runs. */
  private static final long DEADLINE = 100;

  /** A fake monotonic clock (F-37): it reads what the test set, in nanoseconds. */
  private static final class FakeClock implements LongSupplier {
    long now;

    FakeClock(long now) {
      this.now = now;
    }

    @Override
    public long getAsLong() {
      return now;
    }
  }

  /**
   * The deadline counts from begin, not from the read (ADR-0030): a host's pause between a primed read and begin that
   * is longer than the deadline is not charged, and the run ends as DEADLINE once the clock passes begin + deadline.
   */
  @Test
  void deadlineCountsFromBegin() {
    FakeClock clock = new FakeClock(1_000);
    RunState state = new RunState(clock);
    state.prime();
    state.tick(10);
    clock.now = 1_000 + 2 * DEADLINE;
    long begun = clock.now;
    state.begin(Long.MAX_VALUE, DEADLINE, Long.MAX_VALUE);
    // The first refuel, at the run's 4097th tick after the read's 10, finds no time passed since begin.
    state.tick(INTERVAL + 1);
    assertThat(state.steps()).as("ticks after the first refuel of a run begun after a long pause").isEqualTo(10L + INTERVAL + 1);

    clock.now = begun + DEADLINE + 1;
    assertBudget(() -> state.tick(INTERVAL + 1), Budget.DEADLINE, "the next refuel 1 ns past begin + deadline");
  }

  /** The deadline is over only past it (ADR-0017): a refuel exactly at begin + deadline passes, 1 ns later it ends. */
  @Test
  void deadlineEndsOnlyPastIt() {
    FakeClock clock = new FakeClock(5_000);
    RunState state = new RunState(clock);
    state.begin(Long.MAX_VALUE, DEADLINE, Long.MAX_VALUE);
    clock.now = 5_000 + DEADLINE;
    state.tick(INTERVAL + 1);
    assertThat(state.steps()).as("ticks after a refuel exactly at the deadline").isEqualTo(INTERVAL + 1L);

    clock.now = 5_000 + DEADLINE + 1;
    assertBudget(() -> state.tick(INTERVAL + 1), Budget.DEADLINE, "the next refuel 1 ns past the deadline");
  }

  /**
   * The deadline is checked at refuel only, after 4096 ticks of the run (ADR-0030, F-36), and a run state made without
   * a clock reads the system's: a deadline of 1 ns has passed by the time refuel checks it, so the run ticks until a
   * refuel ends it, and the tick that ended it is a refuel's.
   */
  @Test
  void deadlineEndsTheRunAtARefuel() {
    RunState late = new RunState();
    late.begin(Long.MAX_VALUE, 1, Long.MAX_VALUE);
    assertBudget(
        () -> {
          for (long i = 0; i < (long) INTERVAL * INTERVAL; i++) {
            late.tick();
          }
        },
        Budget.DEADLINE,
        "ticks with a 1 ns deadline");
    assertThat(late.steps()).as("ticks when the deadline ended the run").isGreaterThan(INTERVAL);
    assertThat((late.steps() - 1) % INTERVAL).as("ticks past the last interval when the deadline ended the run").isZero();

    // Witness: with an unlimited deadline the same ticks pass every refuel.
    RunState unlimited = new RunState();
    unlimited.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    for (int i = 0; i < 3 * INTERVAL; i++) {
      unlimited.tick();
    }
    assertThat(unlimited.steps()).as("ticks with an unlimited deadline").isEqualTo(3L * INTERVAL);
  }

  /**
   * A main read primes the run state (ADR-0030): the next run keeps the read's ticks, and ends at its begin when they
   * already pass its step budget. That run is then not running, and the ticks are charged once.
   */
  @Test
  void primedTicksCountAgainstTheNextRun() {
    RunState exact = new RunState();
    exact.prime();
    exact.tick(10);
    exact.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(exact.steps()).as("ticks kept from the primed read").isEqualTo(10L);
    assertBudget(exact::tick, Budget.STEPS, "the first tick of a run that kept 10 ticks with 10 steps");

    RunState over = new RunState();
    over.prime();
    over.tick(11);
    assertBudget(() -> over.begin(10, Long.MAX_VALUE, Long.MAX_VALUE), Budget.STEPS, "begin after 11 primed ticks with 10 steps");
    // The run that ended at its begin is not running and took the read with it: the next one begins from no tick.
    over.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(over.steps()).as("ticks at the begin after the one that ended").isZero();
    over.tick(10);
    over.end();

    // Witness: ticks outside a run with no read before them are not kept.
    RunState idle = new RunState();
    idle.tick(11);
    idle.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(idle.steps()).as("ticks at a begin with no primed read").isZero();
  }

  /**
   * The next run keeps the primed read's ticks only (ADR-0030, #77): the writer's ticks outside a run count in the
   * steps, but not against the run; in a run they count against it like any tick.
   */
  @Test
  void writerTicksOutsideARunAreNotKept() {
    RunState state = new RunState();
    state.prime();
    state.tick(10);
    state.tickWritten(7);
    assertThat(state.steps()).as("ticks after a read of 10 and a print of 7 outside a run").isEqualTo(17L);
    state.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(state.steps()).as("ticks kept from the read, not the print").isEqualTo(10L);
    assertBudget(state::tick, Budget.STEPS, "the first tick of a run that kept the read's 10 ticks with 10 steps");
    state.end();

    // In a run the writer's ticks count against it.
    state.begin(5, Long.MAX_VALUE, Long.MAX_VALUE);
    state.tickWritten(5);
    assertThat(state.steps()).as("ticks of a print of 5 in a run").isEqualTo(5L);
    assertBudget(() -> state.tickWritten(1), Budget.STEPS, "a print of one more tick in a run with 5 steps");
    state.end();

    // A main read starts the count again: a print before it is not taken off the read's ticks.
    state.tickWritten(4);
    state.prime();
    state.tickWritten(5);
    state.prime();
    state.tick(3);
    state.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(state.steps()).as("ticks kept from the last read, the prints before it not taken off").isEqualTo(3L);
  }

  /** A read during a run is charged to that run (ADR-0030: reads during a run count too): it does not prime. */
  @Test
  void readDuringARunIsChargedToIt() {
    RunState state = new RunState();
    state.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    state.tick(5);
    state.prime();
    assertThat(state.steps()).as("ticks after a read during the run").isEqualTo(5L);
    state.tick(5);
    assertBudget(state::tick, Budget.STEPS, "the eleventh tick of the run, a read in between");
  }

  /** A run inside a running one is refused, and the running one keeps its budgets (ADR-0030, ADR-0016). */
  @Test
  void beginWhileRunningIsIllegalState() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, 4);
    assertThatExceptionOfType(IllegalStateException.class)
        .as("begin while running")
        .isThrownBy(() -> state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    assertBudget(() -> state.chargeWritten(5), Budget.BYTES_WRITTEN, "5 bytes with the running run's limit of 4");

    // Witness: after end the next run begins, with its own budget.
    state.end();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    state.chargeWritten(5);
    assertThat(state.steps()).as("ticks of the next run").isZero();
  }

  /**
   * The bytes built count each charge since the last begin and none that is refused (ADR-0035): what a caller reads to
   * share one budget among several runs, as the parse-time folds of one compile do (#65).
   */
  @Test
  void builtCountsTheChargesSinceTheLastBegin() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, 10, Long.MAX_VALUE);
    state.chargeBuilt(4);
    state.chargeBuilt(3);
    assertThatExceptionOfType(Termination.class).as("4 bytes with 3 left").isThrownBy(() -> state.chargeBuilt(4));
    assertThat(state.built()).as("bytes built after a refused charge").isEqualTo(7);
    state.end();
    assertThat(state.built()).as("bytes built after end").isEqualTo(7);

    // Witness: the next begin counts from zero.
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, 10, Long.MAX_VALUE);
    assertThat(state.built()).as("bytes built at the next begin").isZero();
  }

  /**
   * Label tokens count from 1 in each run, and a Break's mark is taken back only by its own token (ADR-0039, ADR-0008):
   * a false is a break to the Label whose token was marked, and a stop for any other.
   */
  @Test
  void labelTokensCountFromOneInEachRun() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(state.label()).as("first token of the run").isEqualTo(1);
    long inner = state.label();
    assertThat(inner).as("second token of the run").isEqualTo(2);
    state.breakTo(1);
    assertThat(state.caught(inner)).as("the inner Label of token 2 after a break to 1").isFalse();
    assertThat(state.caught(1)).as("the Label of token 1 after a break to 1").isTrue();
    assertThat(state.caught(1)).as("the same Label again, the mark taken back: a stop").isFalse();

    // A mark left at the end of a run is no break in the next one, whose first token is 1 again: the mark of 1 left
    // here would otherwise read as a break to it.
    state.breakTo(1);
    state.end();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    long first = state.label();
    assertThat(first).as("first token of the next run").isEqualTo(1);
    assertThat(state.caught(first)).as("its Label after a stop").isFalse();
  }

  @Test
  void labelTokensDoNotWrapPastTwoToThe32Activations() {
    // ADR-0039: a token is never 0, the mark of no break, and no two activations of a run share one. A counter of 32
    // bits gives 0 after 2^32 - 1 activations, so that Label would take a stop for its break (review of #68).
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    state.labelsAfter(0xFFFF_FFFFL);
    long token = state.label();
    assertThat(state.caught(token)).as("the Label of the token after 2^32 - 1, after a stop").isFalse();
    assertThat(token).as("the token after 2^32 - 1").isEqualTo(0x1_0000_0000L);
    long next = state.label();
    state.breakTo(next);
    assertThat(state.caught(token)).as("the outer Label after a break to the next token").isFalse();
    assertThat(state.caught(next)).as("the Label of the next token after its break").isTrue();
  }

  /**
   * ADR-0030 as amended by ADR-0044: one reused scratch of ints, the same array while it is long enough, a longer one
   * when asked for more, which stays; nothing is charged for it.
   */
  @Test
  void intsIsOneReusedScratchThatGrowsToTheLongestAsked() {
    RunState state = new RunState();
    int[] first = state.ints(8);
    assertThat(first).as("scratch for 8").hasSize(8);
    assertThat(state.ints(8)).as("scratch for 8 again").isSameAs(first);
    assertThat(state.ints(3)).as("scratch for 3 after 8").isSameAs(first);
    int[] longer = state.ints(9);
    assertThat(longer).as("scratch for 9 after 8").hasSize(9).isNotSameAs(first);
    assertThat(state.ints(8)).as("scratch for 8 after 9").isSameAs(longer);
    assertThat(state.steps()).as("ticks of the scratch").isZero();
    assertThat(state.built()).as("bytes built of the scratch").isZero();
  }

  @Test
  void intsKeepsAtMostTwoToTheTwentyAndGivesALongerAskItsOwnArray() {
    // ADR-0030 as amended by ADR-0044: the scratch kept is capped at 2^20 ints (4 MiB); a longer ask gets an array of
    // its own, which the run state does not keep, so the kept one stays.
    RunState state = new RunState();
    int[] kept = state.ints(1 << 20);
    assertThat(kept).as("scratch for 2^20").hasSize(1 << 20);
    int[] over = state.ints((1 << 20) + 1);
    assertThat(over).as("scratch for 2^20 + 1").hasSize((1 << 20) + 1).isNotSameAs(kept);
    assertThat(state.ints((1 << 20) + 1)).as("scratch for 2^20 + 1 again").isNotSameAs(over);
    assertThat(state.ints(1 << 20)).as("scratch for 2^20 after a longer ask").isSameAs(kept);
  }

  /**
   * The recursion-depth count starts afresh at each begin (ADR-0045 #107): a run that ended with an activation still
   * entered, as a termination leaves it, does not count it against the next run. Under a budget of 1 the next run's
   * first enter is taken and its second is over.
   */
  @Test
  void eachBeginCountsTheActivationsAfresh() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1);
    state.enter();
    state.end();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1);
    state.enter();
    assertBudget(state::enter, Budget.RECURSION_DEPTH, "a second enter under a recursion depth of 1");
  }

  /**
   * The declared stack bytes are the last begin's (ADR-0030 #109 amendment): {@code Long.MAX_VALUE} before any begin and
   * after a begin that declares none, 0 when a begin declares 0, and no budget: a run declaring 0 still counts its
   * steps, and its recursion depth of 1 takes one enter and refuses a second, as any other run's.
   */
  @Test
  void stackBytesAreTheLastBeginsDeclaredStack() {
    RunState state = new RunState();
    assertThat(state.stackBytes()).as("stack bytes before any begin").isEqualTo(Long.MAX_VALUE);
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1, 0);
    assertThat(state.stackBytes()).as("stack bytes of a run declaring 0").isZero();
    state.tick();
    assertThat(state.steps()).as("steps of a run declaring 0 after one tick").isEqualTo(1);
    state.enter();
    assertBudget(state::enter, Budget.RECURSION_DEPTH, "a second enter under a recursion depth of 1, declaring 0");
    state.end();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1);
    assertThat(state.stackBytes()).as("stack bytes of a run declaring none").isEqualTo(Long.MAX_VALUE);
    state.end();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1, 524_288);
    assertThat(state.stackBytes()).as("stack bytes of a run declaring 512 KiB").isEqualTo(524_288L);
  }

  /** Asserts that {@code call} ends the run with {@code Termination(BUDGET, budget)} and the budget's message. */
  private static void assertBudget(ThrowingCallable call, Budget budget, String what) {
    Termination t = assertThatExceptionOfType(Termination.class).as("%s", what).isThrownBy(call).actual();
    assertThat(t.cause()).as("cause of %s", what).isEqualTo(Termination.Cause.BUDGET);
    assertThat(t.budget()).as("budget of %s", what).isEqualTo(budget);
    assertThat(t.getMessage()).as("message of %s", what).isEqualTo(MESSAGES.get(budget));
  }

  /**
   * ADR-0046 #111: the units the push-form calls hold reach the unit limit at equality, and each begin starts from no
   * unit and a limit no call reaches, whatever the run before left (a run the stack backstop ended may leave units).
   */
  @Test
  void unitsReachTheLimitAtEqualityAndBeginClearsThem() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    state.limitUnits(5);
    assertThat(state.addUnits(4)).as("4 units against a limit of 5").isFalse();
    assertThat(state.addUnits(1)).as("5 units against a limit of 5").isTrue();
    state.removeUnits(2);
    assertThat(state.units()).as("units after giving 2 back").isEqualTo(3);
    state.end();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(state.units()).as("units after the next begin").isZero();
    assertThat(state.addUnits(Long.MAX_VALUE / 2)).as("half of Long.MAX_VALUE units after a begin, which sets no limit").isFalse();
  }

  /**
   * ADR-0047 and its #118 amendment: the pending jump, the callee, its frame and its input that a JumpOp sets and the
   * function's entry loop takes, is cleared by {@code end}, so no value stays pinned between runs (ADR-0034), and by
   * {@code begin}, so a run never starts with a jump another left. {@code clearJump} is how the entry loop takes it.
   * Witness: between {@code jump} and each clear, the run state holds exactly what was set.
   */
  @Test
  void pendingJumpClearedAtEnd() {
    RunState state = new RunState();
    LiftedFunction fn = new LiftedFunction(1, 0);
    Object[] frame = {"slot 0"};
    Object in = "the input";

    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    state.jump(fn, frame, in);
    assertThat(state.jumpTarget()).as("witness: the target of the jump set in a run").isSameAs(fn);
    assertThat(state.jumpFrame()).as("witness: the frame of the jump set in a run").isSameAs(frame);
    assertThat(state.jumpInput()).as("witness: the input of the jump set in a run").isSameAs(in);
    state.end();
    assertThat(state.jumpTarget()).as("the target after end").isNull();
    assertThat(state.jumpFrame()).as("the frame after end").isNull();
    assertThat(state.jumpInput()).as("the input after end").isNull();

    state.jump(fn, frame, in);
    assertThat(state.jumpTarget()).as("witness: the target of a jump left outside a run").isSameAs(fn);
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(state.jumpTarget()).as("the target after the next begin").isNull();
    assertThat(state.jumpFrame()).as("the frame after the next begin").isNull();
    assertThat(state.jumpInput()).as("the input after the next begin").isNull();

    state.jump(fn, frame, in);
    assertThat(state.jumpInput()).as("witness: the input of a second jump in the run").isSameAs(in);
    state.clearJump();
    assertThat(state.jumpTarget()).as("the target after clearJump").isNull();
    assertThat(state.jumpFrame()).as("the frame after clearJump").isNull();
    assertThat(state.jumpInput()).as("the input after clearJump").isNull();
  }

  /** ADR-0030 and the design approval of #59: the budgets' messages. */
  private static final Map<Budget, String> MESSAGES =
      Map.of(
          Budget.STEPS, "Step budget exceeded",
          Budget.DEADLINE, "Deadline exceeded",
          Budget.RECURSION_DEPTH, "Recursion-depth budget exceeded",
          Budget.BYTES_WRITTEN, "Bytes-written budget exceeded");
}
