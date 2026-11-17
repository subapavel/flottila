package dev.rail8.jq.query.internal.interpret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The sink a tail emitter runs into (ADR-0043 as amended by F-85, GLOSSARY "Holder"): it keeps the one result and
 * answers it with a stop, so the tail run's false means a result came and its true means none. A take gives the result
 * and leaves the holder cleared, so the one holder of a Binds run serves each of its tail values in turn.
 */
class HolderTest {
  @Test
  void answersItsOneResultWithAStop() {
    Holder holder = new Holder();
    assertThat(holder.emit("a")).as("answer of a holder to its one result").isFalse();
    assertThat(holder.take()).as("what a take gives after the result \"a\"").isEqualTo("a");

    // A Java null is a result like any other: the stop, not the value, tells that one came.
    Holder nil = new Holder();
    assertThat(nil.emit(null)).as("answer of a holder to a null result").isFalse();
    assertThat(nil.take()).as("what a take gives after the null result").isNull();
  }

  @Test
  void takeGivesTheResultAndClearsIt() {
    Holder holder = new Holder();
    holder.emit("a");
    assertThat(holder.take()).as("the first take, after \"a\"").isEqualTo("a");
    assertThat(holder.take()).as("a second take: the first one cleared the holder").isNull();

    // The same holder takes the next tail value's result, not the one before it.
    assertThat(holder.emit("b")).as("answer of the cleared holder to its next result").isFalse();
    assertThat(holder.take()).as("the take after \"b\"").isEqualTo("b");
  }
}
