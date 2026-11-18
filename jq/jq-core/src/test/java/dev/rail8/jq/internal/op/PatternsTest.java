package dev.rail8.jq.internal.op;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqString;
import org.joni.Option;
import org.junit.jupiter.api.Test;

/**
 * A Workspace's cache of compiled patterns: keyed by a pattern's bytes and its options, at most 16 of them with the
 * least recently used out first, none longer than 4 KiB, and a compile error kept as jq's "Regex failure" text. Every
 * pattern here is a new string value, so a hit comes from the cache and not from the latest value's identity.
 */
class PatternsTest {
  private static final long UNLIMITED = Long.MAX_VALUE;

  /** A run state whose run has begun with every budget unlimited. */
  private static RunState running() {
    RunState state = new RunState();
    state.begin(UNLIMITED, UNLIMITED, UNLIMITED);
    return state;
  }

  /** A new string value of {@code text}. */
  private static JqString pattern(String text) {
    return BuiltString.of(text);
  }

  @Test
  void theSameBytesAndOptionsGiveTheCachedPattern() {
    RunState state = running();
    Patterns patterns = new Patterns();
    Patterns.Compiled first = patterns.lookup(state, pattern("a+"), Option.CAPTURE_GROUP);
    assertThat(patterns.lookup(state, pattern("a+"), Option.CAPTURE_GROUP)).as("a second value of the same pattern").isSameAs(first);
    assertThat(patterns.lookup(state, pattern("a+"), Option.CAPTURE_GROUP | Option.IGNORECASE))
        .as("the same pattern with other options")
        .isNotSameAs(first);
  }

  @Test
  void theCacheKeepsSixteenAndDropsTheLeastRecentlyUsed() {
    RunState state = running();
    Patterns patterns = new Patterns();
    Patterns.Compiled[] compiled = new Patterns.Compiled[Patterns.ENTRIES];
    for (int i = 0; i < Patterns.ENTRIES; i++) {
      compiled[i] = patterns.lookup(state, pattern("p" + i), Option.CAPTURE_GROUP);
    }
    // p0 is used again, so p1 is now the least recently used: the 17th pattern drops it.
    assertThat(patterns.lookup(state, pattern("p0"), Option.CAPTURE_GROUP)).as("p0 among 16").isSameAs(compiled[0]);
    patterns.lookup(state, pattern("p16"), Option.CAPTURE_GROUP);
    assertThat(patterns.lookup(state, pattern("p0"), Option.CAPTURE_GROUP)).as("p0 after a 17th pattern").isSameAs(compiled[0]);
    assertThat(patterns.lookup(state, pattern("p1"), Option.CAPTURE_GROUP)).as("p1 after a 17th pattern").isNotSameAs(compiled[1]);
  }

  @Test
  void aPatternOfUpTo4KibIsCachedAndALongerOneIsNot() {
    RunState state = running();
    Patterns patterns = new Patterns();
    String kept = "a".repeat(Patterns.LONGEST);
    String longer = "a".repeat(Patterns.LONGEST + 1);
    assertThat(patterns.lookup(state, pattern(kept), Option.CAPTURE_GROUP))
        .as("a pattern of 4 KiB")
        .isSameAs(patterns.lookup(state, pattern(kept), Option.CAPTURE_GROUP));
    assertThat(patterns.lookup(state, pattern(longer), Option.CAPTURE_GROUP))
        .as("a pattern of 4 KiB and a byte")
        .isNotSameAs(patterns.lookup(state, pattern(longer), Option.CAPTURE_GROUP));
  }

  @Test
  void aPatternThatDoesNotCompileIsJqsRegexFailureEachTime() {
    RunState state = running();
    Patterns patterns = new Patterns();
    for (int i = 0; i < 2; i++) {
      FilterException e =
          assertThatExceptionOfType(FilterException.class)
              .as("lookup %s of [", i)
              .isThrownBy(() -> patterns.lookup(state, pattern("["), Option.CAPTURE_GROUP))
              .actual();
      assertThat(((JqString) e.value()).value()).as("error of lookup %s of [", i).isEqualTo("Regex failure: premature end of char-class");
    }
  }

  @Test
  void aWorkspacesRunStateKeepsItsPatterns() {
    RunState state = new RunState();
    Patterns patterns = Patterns.of(state);
    assertThat(Patterns.of(state)).as("the run state's patterns asked again").isSameAs(patterns);
  }
}
