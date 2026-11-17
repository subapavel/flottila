package dev.rail8.jq.internal.value;

import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.RunState;

/**
 * A run's tables of element positions for the read arrays a filter indexes, which make an index loop linear. The tables
 * live in the run state's memo, so they last one run and what a run costs never depends on another run.
 *
 * <p>The memo has {@link #SLOTS} entries, each a document, the tape index of one of its arrays, the elements the walks
 * of that array have stepped in this run, and the array's table, null until built. A read of an array the memo lacks
 * enters it. A length enters nothing. Every walk of an entered array, a read's or a length's, adds the elements it
 * steps to its entry.
 *
 * <p>Without a table, a read walks from the array's start and ticks the elements it steps, and a length ticks every
 * element. Once the walks of an entered array have stepped more elements than its tape span, which is at least its
 * length, the next read or length builds its table: one tick per element and {@link Charges#POSITION} bytes built per
 * element, charged before the table is allocated. The walks before a build thus cost at least the build, so a table
 * never more than doubles what the walks alone would have cost, and a read that builds none costs what the walk costs.
 * With a table a read and a length cost no tick. An array of more than {@link #CAP} elements, or whose table the run's
 * bytes-built budget cannot take, gets no table while its entry lasts and its reads go on walking. A built table's
 * bytes count against the run's later allocations like any other allocation.
 *
 * <p>The memo counts its reads, every read and every length, entered or not, and each entry keeps the count at its
 * last read. A new entry takes a free slot. Otherwise it replaces the least recently used entry if that entry has not
 * been read in the last {@link #AGE} reads. Failing that, it replaces the entry with no table whose walks have stepped
 * the fewest elements, the least recently used among equals; only when every entry has a table does it replace the
 * least recently used one. A one-off read thus replaces another one-off read before an array a loop reads, while an
 * array no longer read gives up its slot. An entry that is replaced loses its count with its table, so a rebuild again
 * follows walks that cost more than it. The caller checks the document with {@link Document#live()} first, so a read
 * of a stale view fails before the memo is consulted.
 */
final class ElementTable {
  /** The most elements a table holds: 2^24 positions, 64 MiB. */
  static final int CAP = 1 << 24;

  /** The arrays a run's memo keeps at once. */
  private static final int SLOTS = 4;

  /** The reads of the memo after which an entry not read among them may be replaced first. */
  static final int AGE = 16 * SLOTS;

  private ElementTable() {}

  /**
   * The tape index of element {@code i >= 0} of the array at tape index {@code array} of {@code doc}, or -1 when the
   * array is shorter. {@code tape} is {@code doc}'s live tape.
   */
  static int at(RunState state, Document doc, long[] tape, int array, int i) {
    Memo memo = memo(state);
    memo.reads++;
    int slot = memo.find(doc, array);
    if (slot < 0) {
      memo.enter(doc, array);
    } else {
      memo.front(slot);
    }
    memo.last[0] = memo.reads;
    int[] table = table(state, memo, tape, array);
    if (table != null) {
      return i < table.length ? table[i] : -1;
    }
    int end = Tape.end(tape, array);
    int at = array + 1;
    int steps = 0;
    while (steps < i && at < end) {
      at = Tape.next(tape, at);
      steps++;
    }
    state.tick(steps);
    memo.stepped[0] += steps;
    return at < end ? at : -1;
  }

  /** The number of elements of the array at tape index {@code array} of {@code doc}, whose live tape is {@code tape}. */
  static int length(RunState state, Document doc, long[] tape, int array) {
    Memo memo = state.memo() instanceof Memo m ? m : null;
    int slot = -1;
    if (memo != null) {
      memo.reads++;
      slot = memo.find(doc, array);
    }
    if (slot < 0) {
      int length = count(tape, array);
      state.tick(length);
      return length;
    }
    memo.front(slot);
    memo.last[0] = memo.reads;
    int[] table = table(state, memo, tape, array);
    if (table != null) {
      return table.length;
    }
    int length = count(tape, array);
    state.tick(length);
    memo.stepped[0] += length;
    return length;
  }

  /** Whether a table of {@code elements} positions may be built now: within {@link #CAP} and the bytes-built budget. */
  static boolean fits(RunState state, int elements) {
    return elements <= CAP && state.fits(Charges.POSITION * elements);
  }

  /**
   * The table of the entry in slot 0, the array at tape index {@code array}: built now if its walks have stepped more
   * elements than the array's span and it fits, or null.
   */
  private static int[] table(RunState state, Memo memo, long[] tape, int array) {
    int[] table = memo.tables[0];
    if (table != null || memo.refused[0] || memo.stepped[0] <= Tape.span(tape[array])) {
      return table;
    }
    int length = count(tape, array);
    if (!fits(state, length)) {
      memo.refused[0] = true;
      return null;
    }
    state.tick(length);
    state.chargeBuilt(Charges.POSITION * length);
    table = new int[length];
    int at = array + 1;
    for (int i = 0; i < length; i++) {
      table[i] = at;
      at = Tape.next(tape, at);
    }
    memo.tables[0] = table;
    return table;
  }

  /** The elements of the array at tape index {@code array}, counted by a walk that ticks nothing. */
  private static int count(long[] tape, int array) {
    int end = Tape.end(tape, array);
    int length = 0;
    for (int at = array + 1; at < end; at = Tape.next(tape, at)) {
      length++;
    }
    return length;
  }

  /** The run's memo, made on the first read. */
  private static Memo memo(RunState state) {
    if (state.memo() instanceof Memo memo) {
      return memo;
    }
    Memo memo = new Memo();
    state.memo(memo);
    return memo;
  }

  /** The entries, most recently used first, in parallel arrays: slots 0 to {@code used - 1} hold one each. */
  private static final class Memo {
    private final Document[] docs = new Document[SLOTS];
    private final int[] arrays = new int[SLOTS];
    private final long[] stepped = new long[SLOTS];
    private final int[][] tables = new int[SLOTS][];

    /** The memo's read count at the entry's last read. */
    private final long[] last = new long[SLOTS];

    /** Whether the entry's array gets no table in this run: past the cap or past the bytes-built budget. */
    private final boolean[] refused = new boolean[SLOTS];

    private int used;

    /** The reads of the memo in this run: every read and every length. */
    private long reads;

    /** The slot of the entry for the array at tape index {@code array} of {@code doc}, or -1. */
    int find(Document doc, int array) {
      for (int s = 0; s < used; s++) {
        if (docs[s] == doc && arrays[s] == array) {
          return s;
        }
      }
      return -1;
    }

    /** Moves the entry in slot {@code s} to slot 0 and the entries before it one slot on. */
    void front(int s) {
      Document doc = docs[s];
      int array = arrays[s];
      long steps = stepped[s];
      int[] table = tables[s];
      boolean refuse = refused[s];
      long read = last[s];
      for (int k = s; k > 0; k--) {
        docs[k] = docs[k - 1];
        arrays[k] = arrays[k - 1];
        stepped[k] = stepped[k - 1];
        tables[k] = tables[k - 1];
        refused[k] = refused[k - 1];
        last[k] = last[k - 1];
      }
      docs[0] = doc;
      arrays[0] = array;
      stepped[0] = steps;
      tables[0] = table;
      refused[0] = refuse;
      last[0] = read;
    }

    /** Enters the array at tape index {@code array} of {@code doc} in slot 0, replacing an entry when all are used. */
    void enter(Document doc, int array) {
      int s = used < SLOTS ? used++ : victim();
      docs[s] = doc;
      arrays[s] = array;
      stepped[s] = 0;
      tables[s] = null;
      refused[s] = false;
      front(s);
    }

    /**
     * The slot to replace: the last entry if it was not read in the last {@link #AGE} reads; else the entry with no
     * table whose walks stepped the fewest elements, the last among equals; the last entry when every entry has a table.
     */
    private int victim() {
      int victim = SLOTS - 1;
      if (reads - last[victim] >= AGE) {
        return victim;
      }
      long fewest = Long.MAX_VALUE;
      for (int s = SLOTS - 1; s >= 0; s--) {
        if (tables[s] == null && stepped[s] < fewest) {
          victim = s;
          fewest = stepped[s];
        }
      }
      return victim;
    }
  }
}
