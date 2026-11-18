package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqString;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jcodings.exception.JCodingsException;
import org.jcodings.specific.UTF8Encoding;
import org.joni.NameEntry;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.constants.SyntaxProperties;
import org.joni.exception.JOniException;

/**
 * A Workspace's compiled regular expressions, kept across its runs in its run state, and the time one search of a run
 * may take. A pattern compiles with {@link #SYNTAX} over UTF-8 and the options its modifiers give.
 *
 * <p>The cache holds up to {@link #ENTRIES} patterns of up to {@link #LONGEST} bytes each, the least recently used out
 * first; a longer pattern compiles at each lookup. An entry is keyed by a copy of the pattern's bytes and the options,
 * so it pins no document, and it keeps a compile error as well as a compiled pattern. The pattern value of the latest
 * lookup is tried first, by its identity; that value is held for the running run only, {@link #end} drops it.
 *
 * <p>Each lookup ticks once per started KiB of the pattern, compiled or cached, so a run's steps never depend on what
 * the cache held.
 */
public final class Patterns {
  /** The patterns the cache keeps at most. */
  static final int ENTRIES = 16;

  /** The longest pattern the cache keeps, in bytes: 4 KiB. */
  static final int LONGEST = 4096;

  /** The time one search may take when no run set it: the engine's default, 1 s. */
  private static final long DEFAULT_MATCH_TIME = Duration.ofSeconds(1).toNanos();

  /**
   * jq's syntax as near as joni comes to it: joni's PerlNG with the operators Oniguruma's Perl_NT, which jq uses, adds
   * to it and joni reads the same way: the possessive repeats, the conditionals, {@code \R}, {@code \X} and
   * {@code \K}. joni's absent group stops where Oniguruma's does not, so it stays off and fails to compile.
   */
  static final Syntax SYNTAX =
      new Syntax(
          "PerlNT",
          Syntax.PerlNG.op,
          Syntax.PerlNG.op2
              | SyntaxProperties.OP2_PLUS_POSSESSIVE_REPEAT
              | SyntaxProperties.OP2_PLUS_POSSESSIVE_INTERVAL
              | SyntaxProperties.OP2_QMARK_LPAREN_CONDITION
              | SyntaxProperties.OP2_ESC_CAPITAL_R_LINEBREAK
              | SyntaxProperties.OP2_ESC_CAPITAL_X_EXTENDED_GRAPHEME_CLUSTER
              | SyntaxProperties.OP2_ESC_CAPITAL_K_KEEP,
          Syntax.PerlNG.op3,
          Syntax.PerlNG.behavior,
          Syntax.PerlNG.options,
          Syntax.PerlNG.metaCharTable);

  /** The cache, in access order: its eldest entry goes once it holds more than {@link #ENTRIES}. */
  private final Map<Key, Compiled> cache =
      new LinkedHashMap<>(ENTRIES * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Compiled> eldest) {
          return size() > ENTRIES;
        }
      };

  /** The time one search of the running run may take, in nanoseconds. */
  private long matchTime = DEFAULT_MATCH_TIME;

  /** The pattern value of the latest lookup in the running run, or null. */
  private JqString lastPattern;

  /** The options of the latest lookup. */
  private int lastOptions;

  /** What the latest lookup gave. */
  private Compiled lastCompiled;

  /** The Patterns of {@code state}: the one it keeps, or a new one it keeps from now on. */
  static Patterns of(RunState state) {
    if (state.kept() instanceof Patterns patterns) {
      return patterns;
    }
    Patterns patterns = new Patterns();
    state.keep(patterns);
    return patterns;
  }

  /** A run begins: each of its searches may take at most {@code matchTimeNanos}. */
  public void begin(long matchTimeNanos) {
    matchTime = matchTimeNanos;
  }

  /** The run ends: the pattern value of its latest lookup is no longer held. */
  public void end() {
    lastPattern = null;
    lastCompiled = null;
  }

  /** The time one search may take, in nanoseconds. */
  long matchTime() {
    return matchTime;
  }

  /**
   * The compiled {@code pattern} with {@code options}, from the cache or compiled now, one tick per started KiB of the
   * pattern.
   *
   * @throws dev.rail8.jq.query.run.FilterException jq's "Regex failure: " and joni's text when it does not compile
   */
  Compiled lookup(RunState state, JqString pattern, int options) {
    MemorySegment bytes = pattern.utf8();
    state.tick(Built.kibibytes(bytes.byteSize()));
    Compiled compiled;
    if (pattern == lastPattern && options == lastOptions) {
      compiled = lastCompiled;
    } else {
      compiled = find(bytes, options);
      lastPattern = pattern;
      lastOptions = options;
      lastCompiled = compiled;
    }
    if (compiled.error != null) {
      throw Arithmetic.error("Regex failure: " + compiled.error);
    }
    return compiled;
  }

  /** The cached entry of {@code bytes} and {@code options}, or the pattern compiled now and kept if short enough. */
  private Compiled find(MemorySegment bytes, int options) {
    byte[] copy = bytes.toArray(ValueLayout.JAVA_BYTE);
    if (copy.length > LONGEST) {
      return compile(copy, options);
    }
    Key key = new Key(copy, options);
    Compiled compiled = cache.get(key);
    if (compiled == null) {
      compiled = compile(copy, options);
      cache.put(key, compiled);
    }
    return compiled;
  }

  /** {@code pattern} compiled with {@code options}, or the text of joni's error. */
  private static Compiled compile(byte[] pattern, int options) {
    try {
      Regex regex = new Regex(pattern, 0, pattern.length, options, UTF8Encoding.INSTANCE, SYNTAX, WarnCallback.NONE);
      return new Compiled(regex, names(regex), null);
    } catch (JOniException | JCodingsException e) {
      return new Compiled(null, null, e.getMessage());
    }
  }

  /** The name of each group of {@code regex}, by group number, or null for a group without one. */
  private static byte[][] names(Regex regex) {
    byte[][] names = new byte[regex.numberOfCaptures() + 1][];
    for (Iterator<NameEntry> it = regex.namedBackrefIterator(); it.hasNext(); ) {
      NameEntry entry = it.next();
      byte[] name = Arrays.copyOfRange(entry.name, entry.nameP, entry.nameEnd);
      for (int group : entry.getBackRefs()) {
        names[group] = name;
      }
    }
    return names;
  }

  /** A compiled pattern and the name of each of its groups, or the text of its compile error. */
  static final class Compiled {
    /** The compiled pattern, or null when it did not compile. */
    final Regex regex;

    /** The name of each group by group number, null for a group without one; null when it did not compile. */
    private final byte[][] names;

    /** joni's text when the pattern did not compile, or null. */
    private final String error;

    private Compiled(Regex regex, byte[][] names, String error) {
      this.regex = regex;
      this.names = names;
      this.error = error;
    }

    /** The name of group {@code group} as a new string charged to {@code state}, or null when it has none. */
    BuiltString name(RunState state, int group) {
      byte[] name = names[group];
      if (name == null) {
        return null;
      }
      byte[] out = Built.string(state, name.length);
      System.arraycopy(name, 0, out, 0, name.length);
      return BuiltString.of(out);
    }
  }

  /** A cache key: a pattern's bytes and its options. */
  private static final class Key {
    private final byte[] bytes;
    private final int options;
    private final int hash;

    Key(byte[] bytes, int options) {
      this.bytes = bytes;
      this.options = options;
      this.hash = 31 * Arrays.hashCode(bytes) + options;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key k && k.options == options && Arrays.equals(k.bytes, bytes);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}
