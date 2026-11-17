package io.github.subapavel.jqj.query.run;

import io.github.subapavel.jqj.query.run.Termination.Budget;
import java.util.function.LongSupplier;

/**
 * The budgets of one evaluation (ADR-0030, ADR-0008, ADR-0035, ADR-0045 #107): its fuel, the step budget, the deadline,
 * the bytes-built and bytes-written counts and the live call activations, in a phase that is idle, primed or running;
 * the stack bytes the run declares (ADR-0030 #109 amendment); its label tokens and break
 * target (ADR-0039); and a reused scratch of ints (ADR-0044). The engine's Workspace owns one and hands it to each
 * run, so it is never shared between two running evaluations; it is not thread-safe.
 *
 * <p>A main read primes it: the run that begins next keeps the ticks counted since, less those the writer charged
 * through {@link #tickWritten} outside a run, so a large document counts against the run that consumes it and a print
 * between the read and the run does not. A read during a run is charged to that run. {@link #end} leaves every budget
 * unlimited until the next {@link #begin}: outside a run nothing is checked.
 *
 * <p>A tick is {@code if (--fuel < 0) refuel()}. Refuel comes every 4096 ticks, or at the step budget when that is
 * nearer, and checks the step budget, then the deadline, then the thread's interrupt.
 *
 * <p>The deadline counts from {@link #begin}, not from the read, so a host's pause between the two is not charged:
 * {@code begin} reads the clock, {@link System#nanoTime()}, once as the deadline's start. Refuel is the only place that
 * compares the clock with the deadline, so a run under 4096 ticks never checks it.
 */
public final class RunState {
  /** The ticks between two refuels (ADR-0008, ADR-0017). */
  private static final long INTERVAL = 4096;

  /** A budget's largest value, its unlimited one. As fuel it lasts 2^63 - 1 ticks, so it never refuels. */
  private static final long UNLIMITED = Long.MAX_VALUE;

  /** Whether a run is running. */
  private boolean running;

  /** Whether a main read came after the last begin, outside a run: never while running. Idle is neither. */
  private boolean primed;

  /** The ticks counted before the current fill: the ticks counted so far are {@code counted + filled - fuel}. */
  private long counted;

  /** The writer's ticks outside a run since the last main read outside a run: counted, but no run keeps them. */
  private long unkept;

  /** The fuel the current fill gave. */
  private long filled = UNLIMITED;

  /** The fuel left: a tick of n takes n, and below zero refuels. */
  private long fuel = UNLIMITED;

  /** The running run's step budget. */
  private long stepBudget;

  /** The running run's deadline, in nanoseconds from {@link #start}. */
  private long deadline;

  /** The clock when the running run began. */
  private long start;

  /** The running run's bytes-built budget (ADR-0035); unlimited outside a run. */
  private long builtBudget = UNLIMITED;

  /** The bytes built since the last begin. */
  private long built;

  /** The running run's bytes-written budget; unlimited outside a run. */
  private long writtenBudget = UNLIMITED;

  /** The bytes charged since the last begin. */
  private long written;

  /** The running run's recursion-depth budget (ADR-0045 #107, ADR-0046); unlimited outside a run. */
  private long depthBudget = UNLIMITED;

  /** The live call activations since the last begin: each {@link #enter} not yet left. */
  private long depth;

  /**
   * The stack bytes the last begin declared (ADR-0030 #109 amendment), {@code Long.MAX_VALUE} before any and after a
   * begin that declares none: not a budget, and nothing here checks against it.
   */
  private long stackBytes = UNLIMITED;

  /** The stack units the live push-form calls hold (ADR-0046 #111 amendment): each {@link #addUnits} not removed. */
  private long units;

  /** The run's unit limit {@code U_f} (ADR-0046 #111 amendment), {@code Long.MAX_VALUE} until a run sets it. */
  private long unitLimit = UNLIMITED;

  /**
   * The last label token handed out since the last begin: the tokens of a run count from 1 (ADR-0039). A long, so no
   * run reaches its wrap and a token is never 0 or one handed out before.
   */
  private long labels;

  /** The token a Break marked and its Label has not taken back yet, or 0 for none: a false then is a stop. */
  private long breakTo;

  /** The pending jump's target (ADR-0047 and its #118 amendment), or null when no jump is pending. */
  private Object jumpTarget;

  /** The pending jump's frame, or null when no jump is pending. */
  private Object[] jumpFrame;

  /** The pending jump's input, or null when no jump is pending. */
  private Object jumpInput;

  /** The monotonic clock in nanoseconds that the deadline reads: {@link System#nanoTime()} outside the tests. */
  private final LongSupplier clock;

  /** The longest scratch {@link #ints} keeps: 2^20 ints, 4 MiB. */
  static final int KEPT_INTS = 1 << 20;

  /** {@link #ints}'s scratch: it grows to the largest length asked, at most {@link #KEPT_INTS}, and lives as long as this run state. */
  private int[] ints = {};

  /** An idle run state: no run, no tick, every budget unlimited. */
  public RunState() {
    this(System::nanoTime);
  }

  /** An idle run state whose deadline reads {@code clock}, a monotonic clock in nanoseconds: a test's fake (F-37). */
  RunState(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * A main read (ADR-0030, ADR-0015): outside a run, the ticks count from here and the next run keeps them. A read
   * during a run is that run's: its ticks count against the run's budget, and it primes nothing.
   */
  public void prime() {
    if (!running) {
      primed = true;
      unkept = 0;
      fill(0, UNLIMITED);
    }
  }

  /**
   * {@link #begin(long, long, long, long, long)} with the bytes built and the recursion depth unlimited.
   *
   * @throws IllegalStateException when a run is running on this state, which then stays as it was
   * @throws Termination over the step budget when the primed ticks already pass it; no run is then running
   */
  public void begin(long steps, long deadlineNanos, long bytesWritten) {
    begin(steps, deadlineNanos, UNLIMITED, bytesWritten, UNLIMITED);
  }

  /**
   * {@link #begin(long, long, long, long, long)} with the recursion depth unlimited.
   *
   * @throws IllegalStateException when a run is running on this state, which then stays as it was
   * @throws Termination over the step budget when the primed ticks already pass it; no run is then running
   */
  public void begin(long steps, long deadlineNanos, long bytesBuilt, long bytesWritten) {
    begin(steps, deadlineNanos, bytesBuilt, bytesWritten, UNLIMITED);
  }

  /**
   * {@link #begin(long, long, long, long, long, long)} declaring no stack, {@code Long.MAX_VALUE}: the run's unit limit
   * is out of any call's reach, so main and every call run in push form, and only the StackOverflowError backstop ends
   * a run that exhausts its thread (ADR-0046 #111). An in-suite test declares a stack on a thread four times as large
   * (ADR-0046 "Where the stack is tested").
   *
   * @throws IllegalStateException when a run is running on this state, which then stays as it was
   * @throws Termination over the step budget when the primed ticks already pass it; no run is then running
   */
  public void begin(long steps, long deadlineNanos, long bytesBuilt, long bytesWritten, long recursionDepth) {
    begin(steps, deadlineNanos, bytesBuilt, bytesWritten, recursionDepth, UNLIMITED);
  }

  /**
   * Begins a run with its budgets, each {@code Long.MAX_VALUE} when unlimited: the steps, the deadline in nanoseconds,
   * the bytes built (ADR-0035), the bytes written and the recursion depth, the live call activations (ADR-0045 #107);
   * then the stack bytes the run declares (ADR-0030 #109 amendment), which {@link #stackBytes()} reads and which are no
   * budget: the run derives its unit limit from them, and at 0, zero bytes, never unlimited, main and every body run on
   * the trampoline (ADR-0046 and its #111 amendment); the stack units count from zero. A primed read's ticks count
   * against it, the
   * writer's outside a run do not; without a primed read it starts from no tick. The bytes built and written and the
   * live activations count from zero, the label tokens from 1, no break is marked (ADR-0008) and no jump is pending
   * (ADR-0047 #118).
   *
   * @throws IllegalStateException when a run is running on this state, which then stays as it was
   * @throws Termination over the step budget when the primed ticks already pass it; no run is then running
   */
  public void begin(long steps, long deadlineNanos, long bytesBuilt, long bytesWritten, long recursionDepth, long stackBytes) {
    if (running) {
      throw new IllegalStateException("a run is already running on this run state");
    }
    long kept = primed ? steps() - unkept : 0;
    primed = false;
    if (kept > steps) {
      throw Termination.over(Budget.STEPS);
    }
    running = true;
    stepBudget = steps;
    deadline = deadlineNanos;
    builtBudget = bytesBuilt;
    built = 0;
    writtenBudget = bytesWritten;
    written = 0;
    depthBudget = recursionDepth;
    depth = 0;
    this.stackBytes = stackBytes;
    units = 0;
    unitLimit = UNLIMITED;
    labels = 0;
    breakTo = 0;
    clearJump();
    start = clock.getAsLong();
    refill(kept);
  }

  /**
   * Ends the running run, however it ended: every budget is unlimited until the next {@link #begin}, {@link #steps()}
   * goes on from the run's count, and a pending jump is cleared, so no value stays pinned between runs (ADR-0047,
   * ADR-0034).
   */
  public void end() {
    running = false;
    builtBudget = UNLIMITED;
    writtenBudget = UNLIMITED;
    depthBudget = UNLIMITED;
    clearJump();
    fill(steps(), UNLIMITED);
  }

  /**
   * Sets the pending jump (ADR-0047 and its #118 amendment): a tail call's {@code target}, the {@code frame} of the
   * activation it jumps from and its {@code in}put. The tail call then returns at once; the entry loop of that
   * activation, the one running in {@code frame}, takes the jump with {@link #clearJump} and re-enters there, so the
   * jump holds no Java frame and no further activation.
   */
  public void jump(Object target, Object[] frame, Object in) {
    jumpTarget = target;
    jumpFrame = frame;
    jumpInput = in;
  }

  /** The pending jump's target, or null when no jump is pending. */
  public Object jumpTarget() {
    return jumpTarget;
  }

  /** The frame of the activation the pending jump jumps from, or null when no jump is pending. */
  public Object[] jumpFrame() {
    return jumpFrame;
  }

  /** The pending jump's input, or null when no jump is pending. */
  public Object jumpInput() {
    return jumpInput;
  }

  /** No jump is pending: the entry loop takes the jump, and {@link #begin} and {@link #end} clear it. */
  public void clearJump() {
    jumpTarget = null;
    jumpFrame = null;
    jumpInput = null;
  }

  /**
   * A call activation begins (ADR-0045 #107, ADR-0046): one more live activation, which {@link #leave} ends when the
   * call ends, by its results running out, a stop, a break or an error, and in push form a termination too; a
   * termination on the trampoline leaves it counted until the next {@link #begin}. One over the recursion-depth budget
   * is not counted.
   *
   * @throws Termination over the recursion-depth budget
   */
  public void enter() {
    if (depth >= depthBudget) {
      throw Termination.over(Budget.RECURSION_DEPTH);
    }
    depth++;
  }

  /** The call activation of the last {@link #enter} not left yet ends. */
  public void leave() {
    depth--;
  }

  /**
   * Sets the running run's unit limit {@code U_f} (ADR-0046 #111 amendment), which the run derives at its start from
   * its declared stack; {@link #begin} sets it to {@code Long.MAX_VALUE}, which no call reaches.
   */
  public void limitUnits(long unitLimit) {
    this.unitLimit = unitLimit;
  }

  /**
   * A push-form call holds {@code units} more stack units until {@link #removeUnits} gives them back (ADR-0046 #111
   * amendment): true when the units held now reach the unit limit, where the call starts the trampoline.
   */
  public boolean addUnits(long units) {
    this.units += units;
    return this.units >= unitLimit;
  }

  /** A push-form call ends and gives back the {@code units} it held. */
  public void removeUnits(long units) {
    this.units -= units;
  }

  /** The stack units the live push-form calls hold now: 0 after each {@link #begin}. */
  public long units() {
    return units;
  }

  /**
   * Charges one tick (ADR-0008).
   *
   * @throws Termination when refuel finds a budget over or the thread interrupted
   */
  public void tick() {
    if (--fuel < 0) {
      refuel();
    }
  }

  /**
   * Charges {@code n} ticks at once: the reader and the writer charge one per KiB (ADR-0015).
   *
   * @throws Termination when refuel finds a budget over or the thread interrupted
   */
  public void tick(int n) {
    fuel -= n;
    if (fuel < 0) {
      refuel();
    }
  }

  /**
   * Charges the writer's {@code n} ticks for a drain it wrote, one per KiB (ADR-0015): in a run as {@link #tick(int)};
   * outside a run they count in {@link #steps()}, but the next run does not keep them (ADR-0030).
   *
   * @throws Termination when refuel finds a budget over or the thread interrupted
   */
  public void tickWritten(int n) {
    if (!running) {
      unkept += n;
    }
    tick(n);
  }

  /**
   * Charges {@code bytes} a builder is about to allocate (ADR-0035, {@link Charges}): a charge that would pass the
   * bytes-built budget is not counted, and the builder must not allocate, so a run never holds more than its limit.
   *
   * @throws Termination over the bytes-built budget
   */
  public void chargeBuilt(long bytes) {
    if (bytes > builtBudget - built) {
      throw Termination.over(Budget.BYTES_BUILT);
    }
    built += bytes;
  }

  /**
   * The termination for a builder that needs a longer array than the JVM allocates: whatever its limit, the
   * bytes-built budget cannot hold that container (ADR-0035). The builder throws it instead of allocating.
   */
  public Termination overBuilt() {
    return Termination.over(Budget.BYTES_BUILT);
  }

  /**
   * Charges one drain of {@code bytes} before it is written (ADR-0030): a drain that would pass the bytes-written budget
   * is not charged and must not be written, so the output never exceeds the budget.
   *
   * @throws Termination over the bytes-written budget
   */
  public void chargeWritten(int bytes) {
    if (bytes > writtenBudget - written) {
      throw Termination.over(Budget.BYTES_WRITTEN);
    }
    written += bytes;
  }

  /** A new Label activation's token (ADR-0039): the next number of the run, from 1 after each {@link #begin}. */
  public long label() {
    return ++labels;
  }

  /**
   * For tests only: the next {@link #label} token is {@code last + 1}, so a test reaches the tokens past 2^32
   * activations without running them. No engine code calls it.
   */
  void labelsAfter(long last) {
    labels = last;
  }

  /**
   * A Break to the activation of {@code token} (ADR-0008, ADR-0039): marks it. The Break then returns false, which
   * every node passes on until the Label of that token takes it back with {@link #caught}.
   */
  public void breakTo(long token) {
    breakTo = token;
  }

  /**
   * Whether the false a Label's body gave is a Break to that Label's {@code token}: then the mark is cleared and the
   * Label's run goes on as if the body ran out. Otherwise the false is a stop or a Break to an outer Label, which the
   * Label passes on.
   */
  public boolean caught(long token) {
    if (breakTo != token) {
      return false;
    }
    breakTo = 0;
    return true;
  }

  /**
   * A reused scratch of at least {@code n} ints for one operation's positions within one call (ADR-0030 as amended by
   * ADR-0044): the same array until a longer one is asked for, its contents whatever the last caller left. The run state
   * keeps at most {@link #KEPT_INTS}; a longer ask gets a new array of its own, which it does not keep. It holds no
   * value and is never charged to bytes built; a caller asks for no more than it bounds itself to, and does not keep it
   * past its call.
   */
  public int[] ints(int n) {
    if (n > KEPT_INTS) {
      return new int[n];
    }
    if (ints.length < n) {
      ints = new int[n];
    }
    return ints;
  }

  /**
   * The stack bytes the last {@link #begin} declared (ADR-0030 #109 amendment), {@code Long.MAX_VALUE} before any begin
   * and after one that declared none.
   */
  public long stackBytes() {
    return stackBytes;
  }

  /** The bytes built since the last begin (ADR-0035): each charge {@link #chargeBuilt} counted, none it refused. */
  public long built() {
    return built;
  }

  /** The ticks counted since the last main read outside a run, or since the last begin that no read primed. */
  public long steps() {
    return counted + filled - fuel;
  }

  /** Checks the step budget, then the deadline, then the interrupt (ADR-0030), and fills the fuel again. */
  private void refuel() {
    long steps = steps();
    if (steps > stepBudget) {
      throw Termination.over(Budget.STEPS);
    }
    if (clock.getAsLong() - start > deadline) {
      throw Termination.over(Budget.DEADLINE);
    }
    // isInterrupted leaves the flag set, as ADR-0017 asks of a cancelled run.
    if (Thread.currentThread().isInterrupted()) {
      throw Termination.cancelled();
    }
    refill(steps);
  }

  /** In a run, at {@code steps} ticks counted: fuel up to the next refuel, 4096 ticks or the rest of the step budget. */
  private void refill(long steps) {
    fill(steps, Math.min(INTERVAL, stepBudget - steps));
  }

  private void fill(long counted, long fuel) {
    this.counted = counted;
    this.filled = fuel;
    this.fuel = fuel;
  }
}
