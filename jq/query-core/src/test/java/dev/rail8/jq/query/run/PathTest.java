package dev.rail8.jq.query.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A path is a chain of keys back to the root, its length counted, and a prefix is shared, never copied. */
class PathTest {
  @Test
  void theRootHasNoKeyNoParentAndLengthZero() {
    assertThat(Path.ROOT.parent()).as("the root's parent").isNull();
    assertThat(Path.ROOT.key()).as("the root's key").isNull();
    assertThat(Path.ROOT.length()).as("the root's length").isZero();
  }

  @Test
  void aChildAddsOneKeyAndSharesItsParent() {
    Path a = Path.ROOT.child("a");
    Path b = a.child(0);
    Path c = a.child("c");

    assertThat(b.key()).as("the last key").isEqualTo(0);
    assertThat(b.length()).as("the length of two keys").isEqualTo(2);
    assertThat(b.parent()).as("the parent of the second key").isSameAs(a);
    assertThat(c.parent()).as("a sibling's parent: the same prefix").isSameAs(a);
    assertThat(a.parent()).as("the parent of the first key").isSameAs(Path.ROOT);
  }
}
