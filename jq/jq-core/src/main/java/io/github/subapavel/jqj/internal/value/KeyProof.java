package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.JsonStrings;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqObject;
import java.util.Arrays;

/**
 * The key proof of a document's object (ADR-0044, GLOSSARY "Key proof"): at the start of each walk it finds which of
 * its key entries repeat an earlier one by their decoded bytes, and gives the {@link ObjectWalk} that takes each key
 * once, at its first place with its last value, as jq does (ADR-0002). What it finds lives as long as that walk.
 *
 * <p>Up to {@link #PAIRWISE} key entries, each is compared with the distinct keys before it. Past that, the keys go
 * into an open-addressing table in the run state's scratch ({@link RunState#ints}), a power of two of at least
 * {@link #SLOTS_PER_KEY} slots per key entry and at most {@link #MAX_SLOTS}, each key from its home slot, its
 * {@link #hash} masked, to the next free one. The whole proof may probe {@link #PROBES_PER_KEY} slots per key entry; a
 * probe that compares the bytes of two keys of one length costs one per started KiB of them, at least one. Past that
 * budget (keys built to collide) it sorts the key entries instead, by their unsigned decoded bytes with a stable
 * bottom-up merge sort, jqj's (ADR-0012, F-48), and equal neighbours are repeats. So a proof takes O(key bytes + n log
 * n), never n * n. No repeat leaves the walk without a plan; the first one makes it. A tag-6 key is decoded at most
 * once per proof, the first time a compare or the hash needs its bytes (#85 review).
 *
 * <p>Ticks (ADR-0008 as amended by ADR-0044): one per key entry, or one per started KiB of a longer key's text, and
 * one per compare of the sort, one per started KiB of the shorter key, at least one; the table's probes charge none.
 * A writer's proof charges through {@link RunState#tickWritten}. A host's accessor proves on a run state of its own, so
 * it charges no run.
 */
public final class KeyProof {
  /** The most key entries compared pairwise, without a table. */
  static final int PAIRWISE = 8;

  /** The table's least slots per key entry. */
  static final int SLOTS_PER_KEY = 4;

  /** The table's most slots. */
  static final int MAX_SLOTS = 1 << 20;

  /** The probes the whole proof may make per key entry before it sorts instead. */
  static final int PROBES_PER_KEY = 4;

  private KeyProof() {}

  /** The walk of {@code object}, a document's object, after its proof; for {@code written} the writer's ticks. */
  public static ObjectWalk walk(RunState state, ObjectView object, boolean written) {
    return walk(state, object.doc(), object.index(), written);
  }

  /** The walk of the object at tape index {@code index} of {@code doc}, after its proof. */
  public static ObjectWalk walk(RunState state, Document doc, int index, boolean written) {
    long[] tape = doc.live();
    long entries = entries(state, tape, index, written);
    int[] plan = prove(state, doc, tape, index, entries, written);
    return new ObjectWalk(doc, index, plan == null ? (int) (entries >>> 1) : plan.length / 2, plan);
  }

  /**
   * The plan of the object at tape index {@code index} of {@code doc} after its proof, as {@link ObjectWalk#plan}: null
   * when its keys are distinct, so its key entries are its members in source order. The Printer's walk, which keeps
   * no walk object per printed object (#85).
   */
  public static int[] plan(RunState state, Document doc, int index, boolean written) {
    long[] tape = doc.live();
    return prove(state, doc, tape, index, entries(state, tape, index, written), written);
  }

  /**
   * Counts the key entries of the object at {@code index} and charges their ticks: the count shifted left by one, its
   * low bit set when a key is tag 6.
   */
  private static long entries(RunState state, long[] tape, int index, boolean written) {
    int end = Tape.end(tape, index);
    long n = 0;
    int ticks = 0;
    boolean escaped = false;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      n++;
      ticks += Math.max(1, kibibytes(Tape.span(tape[k]) - 2));
      escaped |= Tape.tag(tape[k]) != Tape.STRING;
    }
    if (written) {
      state.tickWritten(ticks);
    } else {
      state.tick(ticks);
    }
    return n << 1 | (escaped ? 1 : 0);
  }

  /** The plan of the {@code entries} (as {@link #entries} gives them) of the object at {@code index}, null for none. */
  private static int[] prove(RunState state, Document doc, long[] tape, int index, long entries, boolean written) {
    int end = Tape.end(tape, index);
    int n = (int) (entries >>> 1);
    byte[] bytes = doc.bytes();
    // Each tag-6 key is decoded once, into its ordinal's place, when the proof first needs its bytes (#85 review); with
    // no tag-6 key there is nothing to keep, and no proof allocates for a key's bytes.
    byte[][] texts = (entries & 1) != 0 ? new byte[n][] : null;
    if (n <= PAIRWISE) {
      return pairwise(state.ints(3 * n), tape, bytes, texts, index, end, n);
    }
    int[] scratch = state.ints(slots(n) + 3 * n);
    int[] hashed = hashed(scratch, tape, bytes, texts, index, end, n);
    return hashed != OVER ? hashed : sorted(state, scratch, tape, bytes, texts, index, end, n, written);
  }

  /** {@link KeyIndex}'s way to find a left key: among the pairwise proof's pairs. */
  private static final int PAIRS = 0;

  /** {@link KeyIndex}'s way to find a left key: in the proof's table. */
  private static final int TABLE = 1;

  /** {@link KeyIndex}'s way to find a left key: by a binary search among the sort's distinct keys. */
  private static final int SEARCH = 2;

  /**
   * The key index of {@code right}, a document's object, for Equality's compare of {@code left}, an object's walk or a
   * built object, against it (ADR-0044 as amended by #95, GLOSSARY "Key index"). It proves {@code right} once, with the
   * proof's code and ticks, then looks up each key of {@code left} in walk order, within this call, while the proof's
   * scratch still holds its pairs, its table or its sorted keys, and stops at the first key {@code right} lacks, or
   * once it has found as many keys as {@code right} has: a further key of {@code left}, which are distinct, cannot be
   * among them. A lookup among the pairs or in the table charges nothing, since a found key retraces its own insertion
   * probes; a lookup among the sorted keys charges one tick per compare, one per started KiB of the shorter key, at
   * least one. The index keeps no scratch: a nested pair proves again in it.
   */
  static KeyIndex index(RunState state, ObjectView right, Members left) {
    Document doc = right.doc();
    long[] tape = doc.live();
    int index = right.index();
    long entries = entries(state, tape, index, false);
    int end = Tape.end(tape, index);
    int n = (int) (entries >>> 1);
    byte[] bytes = doc.bytes();
    byte[][] texts = (entries & 1) != 0 ? new byte[n][] : null;
    int[] scratch;
    int[] plan;
    int way;
    if (n <= PAIRWISE) {
      scratch = state.ints(3 * n);
      plan = pairwise(scratch, tape, bytes, texts, index, end, n);
      way = PAIRS;
    } else {
      scratch = state.ints(slots(n) + 3 * n);
      plan = hashed(scratch, tape, bytes, texts, index, end, n);
      way = TABLE;
      if (plan == OVER) {
        plan = sorted(state, scratch, tape, bytes, texts, index, end, n, false);
        way = SEARCH;
      }
    }
    int size = plan == null ? n : plan.length / 2;
    int count = left instanceof ObjectWalk walk ? walk.size() : ((BuiltObject) left).size();
    int most = Math.min(count, size);
    // One place more than the keys it may find, so a 0 follows the last one found.
    int[] values = new int[most + 1];
    int found = 0;
    for (int c = left.first(); found < most; c = left.next(state, c)) {
      int value =
          switch (((Keyed) left).key(c)) {
            case BuiltString s -> find(state, way, tape, bytes, texts, scratch, n, size, s.bytes(), 0, s.byteLength());
            case StringView v -> {
              long key = v.doc().live()[v.index()];
              byte[] text = v.doc().bytes();
              yield Tape.tag(key) == Tape.STRING
                  ? find(state, way, tape, bytes, texts, scratch, n, size, text, Tape.off(key) + 1, Tape.off(key) + Tape.span(key) - 1)
                  : find(state, way, tape, bytes, texts, scratch, n, size, JsonStrings.decode(text, key));
            }
          };
      if (value == 0) {
        break;
      }
      values[found++] = value;
    }
    return new KeyIndex(doc, size, values);
  }

  /** {@link #find(RunState, int, long[], byte[], byte[][], int[], int, int, byte[], int, int)} of all of {@code key}. */
  private static int find(RunState state, int way, long[] tape, byte[] bytes, byte[][] texts, int[] scratch, int n, int size, byte[] key) {
    return find(state, way, tape, bytes, texts, scratch, n, size, key, 0, key.length);
  }

  /**
   * The tape index of the last value of the decoded key {@code key[from, to)} among the {@code size} distinct keys of
   * the {@code n} key entries the proof left in {@code scratch} the {@code way} it proved them, or 0 when there is none.
   */
  private static int find(RunState state, int way, long[] tape, byte[] bytes, byte[][] texts, int[] scratch, int n, int size, byte[] key, int from, int to) {
    return switch (way) {
      case PAIRS -> amongPairs(tape, bytes, texts, scratch, n, size, key, from, to);
      case TABLE -> inTable(tape, bytes, texts, scratch, n, key, from, to);
      default -> bySearch(state, tape, bytes, texts, scratch, n, size, key, from, to);
    };
  }

  /** {@link #find} among the pairs {@link #pairwise} left. */
  private static int amongPairs(long[] tape, byte[] bytes, byte[][] texts, int[] scratch, int n, int size, byte[] key, int from, int to) {
    for (int at = 0; at < size; at++) {
      int other = scratch[2 * at];
      byte[] otherKey = text(tape, bytes, texts, scratch[2 * n + at], other);
      if (Arrays.equals(key, from, to, otherKey, from(tape, other), to(tape, otherKey, other))) {
        return scratch[2 * at + 1];
      }
    }
    return 0;
  }

  /**
   * {@link #find} in the table {@link #hashed} left, from the key's home slot to the next free one; a table of
   * {@link #MAX_SLOTS} may have none, so it probes each slot at most once.
   */
  private static int inTable(long[] tape, byte[] bytes, byte[][] texts, int[] scratch, int n, byte[] key, int from, int to) {
    int slots = slots(n);
    int mask = slots - 1;
    int slot = hash(key, from, to) & mask;
    for (int probes = 0; probes < slots; probes++) {
      int held = scratch[slot];
      if (held == 0) {
        return 0;
      }
      int other = scratch[slots + 2 * held - 2];
      byte[] otherKey = text(tape, bytes, texts, scratch[slots + 2 * n + held - 1], other);
      if (Arrays.equals(key, from, to, otherKey, from(tape, other), to(tape, otherKey, other))) {
        return scratch[slots + 2 * held - 1];
      }
      slot = (slot + 1) & mask;
    }
    return 0;
  }

  /**
   * {@link #find} by a midpoint binary search among the {@code size} distinct keys {@link #sorted} left in order, one
   * tick per compare, one per started KiB of the shorter key, at least one.
   */
  private static int bySearch(RunState state, long[] tape, byte[] bytes, byte[][] texts, int[] scratch, int n, int size, byte[] key, int from, int to) {
    int keys = sortedAt(n);
    int values = 3 * n - keys;
    int lo = 0;
    int hi = size - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int ordinal = scratch[keys + mid];
      int other = scratch[ordinal];
      byte[] otherKey = text(tape, bytes, texts, ordinal, other);
      int otherFrom = from(tape, other);
      int otherTo = to(tape, otherKey, other);
      state.tick(Math.max(1, kibibytes(Math.min(to - from, otherTo - otherFrom))));
      int order = Arrays.compareUnsigned(key, from, to, otherKey, otherFrom, otherTo);
      if (order == 0) {
        return scratch[values + ordinal];
      }
      if (order < 0) {
        hi = mid - 1;
      } else {
        lo = mid + 1;
      }
    }
    return 0;
  }

  /**
   * Where {@link #sorted}'s merge passes over {@code n > 1} key entries leave the sorted ordinals in its scratch: at
   * {@code n} after an even number of passes, at {@code 2 n} after an odd one; the last values lie in the other half.
   */
  private static int sortedAt(int n) {
    int passes = Integer.SIZE - Integer.numberOfLeadingZeros(n - 1);
    return (passes & 1) == 0 ? n : 2 * n;
  }

  /** What {@link #hashed} gives when its probe budget runs out. */
  private static final int[] OVER = new int[0];

  /**
   * The array the decoded bytes of key entry {@code ordinal}, at tape index {@code k}, lie in: a tag-5 key's are the
   * document's {@code bytes}, a tag-6 key's its place in {@code texts}, decoded there the first time the proof reads
   * them; {@code texts} is null when the object has no tag-6 key.
   */
  private static byte[] text(long[] tape, byte[] bytes, byte[][] texts, int ordinal, int k) {
    long entry = tape[k];
    if (Tape.tag(entry) == Tape.STRING) {
      return bytes;
    }
    byte[] text = texts[ordinal];
    if (text == null) {
      text = JsonStrings.decode(bytes, entry);
      texts[ordinal] = text;
    }
    return text;
  }

  /** Where the decoded bytes of the key at tape index {@code k} start in its {@link #text}. */
  private static int from(long[] tape, int k) {
    long entry = tape[k];
    return Tape.tag(entry) == Tape.STRING ? Tape.off(entry) + 1 : 0;
  }

  /** Where the decoded bytes of the key at tape index {@code k} end in {@code text}, its {@link #text}. */
  private static int to(long[] tape, byte[] text, int k) {
    long entry = tape[k];
    return Tape.tag(entry) == Tape.STRING ? Tape.off(entry) + Tape.span(entry) - 1 : text.length;
  }

  /** The members of {@code object} for a filter's walk: a document's after its proof, a built one's as they are. */
  public static Members members(RunState state, JqObject object) {
    return switch (object) {
      case ObjectView view -> walk(state, view, false);
      case BuiltObject built -> built;
    };
  }

  /**
   * The plan of up to {@link #PAIRWISE} key entries: each compared with the distinct keys before it, kept in
   * {@code pairs}, the caller's scratch of at least 3 n ints, as a pair of its key's first place and its last value,
   * and after the pairs the ordinal of each first place.
   */
  private static int[] pairwise(int[] pairs, long[] tape, byte[] bytes, byte[][] texts, int index, int end, int n) {
    int size = 0;
    boolean repeated = false;
    int ordinal = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      byte[] key = text(tape, bytes, texts, ordinal, k);
      int from = from(tape, k);
      int to = to(tape, key, k);
      int at = 0;
      while (at < size) {
        int other = pairs[2 * at];
        byte[] otherKey = text(tape, bytes, texts, pairs[2 * n + at], other);
        if (Arrays.equals(key, from, to, otherKey, from(tape, other), to(tape, otherKey, other))) {
          break;
        }
        at++;
      }
      if (at == size) {
        pairs[2 * size] = k;
        pairs[2 * n + size] = ordinal;
        size++;
      } else {
        repeated = true;
      }
      pairs[2 * at + 1] = k + 1;
      ordinal++;
    }
    return repeated ? Arrays.copyOf(pairs, 2 * size) : null;
  }

  /**
   * The plan of {@code n} key entries proved through the table, or {@link #OVER} when the probe budget runs out. The
   * scratch, the caller's of at least {@link #slots} plus 3 n ints, holds the table's slots first, each 0 when free or one more than the number of a distinct key, then a pair per
   * distinct key, its first place and its last value, then the ordinal of each distinct key's first place.
   */
  private static int[] hashed(int[] scratch, long[] tape, byte[] bytes, byte[][] texts, int index, int end, int n) {
    int slots = slots(n);
    int mask = slots - 1;
    int ordinals = slots + 2 * n;
    Arrays.fill(scratch, 0, slots, 0);
    long budget = (long) PROBES_PER_KEY * n;
    int size = 0;
    boolean repeated = false;
    int ordinal = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      byte[] key = text(tape, bytes, texts, ordinal, k);
      int from = from(tape, k);
      int to = to(tape, key, k);
      int slot = hash(key, from, to) & mask;
      for (;;) {
        int held = scratch[slot];
        if (held == 0) {
          if (--budget < 0) {
            return OVER;
          }
          scratch[slot] = ++size;
          scratch[slots + 2 * size - 2] = k;
          scratch[slots + 2 * size - 1] = k + 1;
          scratch[ordinals + size - 1] = ordinal;
          break;
        }
        int other = scratch[slots + 2 * held - 2];
        byte[] otherKey = text(tape, bytes, texts, scratch[ordinals + held - 1], other);
        int otherFrom = from(tape, other);
        int otherTo = to(tape, otherKey, other);
        budget -= to - from == otherTo - otherFrom ? Math.max(1, kibibytes(to - from)) : 1;
        if (budget < 0) {
          return OVER;
        }
        if (Arrays.equals(key, from, to, otherKey, otherFrom, otherTo)) {
          scratch[slots + 2 * held - 1] = k + 1;
          repeated = true;
          break;
        }
        slot = (slot + 1) & mask;
      }
      ordinal++;
    }
    return repeated ? Arrays.copyOfRange(scratch, slots, slots + 2 * size) : null;
  }

  /**
   * The plan of {@code n} key entries proved by a sort, null when they are distinct. The scratch, the caller's of at
   * least 3 n ints, holds the key entries' tape indices in source order, then their ordinals, sorted stably by their
   * keys' bytes, afterwards the ordinal of each distinct key's first place first, in order, then the merge's other half,
   * which afterwards holds, at the ordinal of each distinct key's first place, the tape index of its last value, and 0
   * at a repeat's. {@link #sortedAt} tells which half is which.
   */
  private static int[] sorted(RunState state, int[] scratch, long[] tape, byte[] bytes, byte[][] texts, int index, int end, int n, boolean written) {
    int i = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      scratch[i] = k;
      scratch[n + i] = i;
      i++;
    }
    int from = n;
    int to = 2 * n;
    for (int width = 1; width < n; width *= 2) {
      for (int lo = 0; lo < n; lo += 2 * width) {
        int mid = Math.min(lo + width, n);
        int hi = (int) Math.min((long) lo + 2 * width, n);
        merge(state, tape, bytes, texts, scratch, from, to, lo, mid, hi, written);
      }
      int merged = to;
      to = from;
      from = merged;
    }
    // `from` holds the sorted ordinals, `to` is free for the last values.
    Arrays.fill(scratch, to, to + n, 0);
    int size = 0;
    int start = 0;
    while (start < n) {
      int first = scratch[from + start];
      int next = start + 1;
      while (next < n && compare(state, tape, bytes, texts, scratch, first, scratch[from + next], written) == 0) {
        next++;
      }
      scratch[to + first] = scratch[scratch[from + next - 1]] + 1;
      // The distinct keys in order, for a key index's search: size <= start, so no place still to be read.
      scratch[from + size] = first;
      size++;
      start = next;
    }
    if (size == n) {
      return null;
    }
    int[] plan = new int[2 * size];
    int p = 0;
    for (int ordinal = 0; ordinal < n; ordinal++) {
      int value = scratch[to + ordinal];
      if (value != 0) {
        plan[p++] = scratch[ordinal];
        plan[p++] = value;
      }
    }
    return plan;
  }

  /**
   * Merges the sorted runs of ordinals {@code [lo, mid)} and {@code [mid, hi)} at {@code from} into {@code to}: the left
   * run's ordinal first unless the right one's key comes strictly before it, so equal keys keep their source order.
   */
  private static void merge(RunState state, long[] tape, byte[] bytes, byte[][] texts, int[] scratch, int from, int to, int lo, int mid, int hi, boolean written) {
    int i = lo;
    int j = mid;
    int k = lo;
    while (i < mid && j < hi) {
      int left = scratch[from + i];
      int right = scratch[from + j];
      if (compare(state, tape, bytes, texts, scratch, left, right, written) > 0) {
        scratch[to + k++] = right;
        j++;
      } else {
        scratch[to + k++] = left;
        i++;
      }
    }
    System.arraycopy(scratch, from + i, scratch, to + k, mid - i);
    System.arraycopy(scratch, from + j, scratch, to + k + mid - i, hi - j);
  }

  /**
   * The order of the keys of ordinals {@code a} and {@code b}, whose tape indices {@code scratch} holds at those places,
   * by their unsigned decoded bytes: one sort compare.
   */
  private static int compare(RunState state, long[] tape, byte[] bytes, byte[][] texts, int[] scratch, int a, int b, boolean written) {
    int x = scratch[a];
    int y = scratch[b];
    byte[] p = text(tape, bytes, texts, a, x);
    byte[] q = text(tape, bytes, texts, b, y);
    int pf = from(tape, x);
    int pt = to(tape, p, x);
    int qf = from(tape, y);
    int qt = to(tape, q, y);
    int ticks = Math.max(1, kibibytes(Math.min(pt - pf, qt - qf)));
    if (written) {
      state.tickWritten(ticks);
    } else {
      state.tick(ticks);
    }
    return Arrays.compareUnsigned(p, pf, pt, q, qf, qt);
  }

  /** The table's slots for {@code n} key entries, past {@link #PAIRWISE}: the least power of two of 4 n, at most 2^20. */
  static int slots(int n) {
    return Math.min(MAX_SLOTS, Integer.highestOneBit(SLOTS_PER_KEY * n - 1) << 1);
  }

  /** The started KiB of {@code bytes} bytes. */
  private static int kibibytes(int bytes) {
    return (bytes + 1023) >>> 10;
  }

  /**
   * The proof's hash of the decoded bytes {@code key[from, to)}, a function of them alone: a 31-polynomial, then
   * MurmurHash3's finaliser (public domain), which spreads its high bits into the low ones that make the home slot. It
   * has no seed, so a run's ticks never depend on one; keys built to collide in it end in the sort.
   */
  static int hash(byte[] key, int from, int to) {
    int h = 0;
    for (int i = from; i < to; i++) {
      h = 31 * h + key[i];
    }
    h = (h ^ (h >>> 16)) * 0x85EB_CA6B;
    h = (h ^ (h >>> 13)) * 0xC2B2_AE35;
    return h ^ (h >>> 16);
  }
}
