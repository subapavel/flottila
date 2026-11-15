package io.github.subapavel.jqj.query.analysis;

import static io.github.subapavel.jqj.query.analysis.Cardinality.AT_MOST_ONE;
import static io.github.subapavel.jqj.query.analysis.Cardinality.MANY;
import static io.github.subapavel.jqj.query.analysis.Cardinality.NEVER;
import static io.github.subapavel.jqj.query.analysis.Cardinality.NONE;
import static io.github.subapavel.jqj.query.analysis.Cardinality.ONE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cardinality lattice and its binary rules (ADR-0007 "Amendment (ticket #112)", GLOSSARY "Cardinality"): the least
 * fixpoint over a recursive group exists and is reached from NEVER only if every rule is monotone, and a body is read
 * as a chain of {@code seq}, so {@code seq} must not depend on how a chain is grouped. Exhaustive over the 5 x 5 inputs.
 *
 * <p>Every expected value is the design's rule text (issue #112, the architect-approved design comment), transcribed
 * here by hand into a literal table, row by row; the order is the ADR's: NEVER below NONE and ONE, both below
 * AT_MOST_ONE, below MANY, and NONE and ONE not comparable.
 *
 * <p>Seam (named in the #112 hand-back): {@code Cardinality} with the constants {@code NEVER, NONE, ONE, AT_MOST_ONE,
 * MANY} and {@code public static Cardinality join(Cardinality, Cardinality)} and {@code public static Cardinality
 * seq(Cardinality, Cardinality)}; the package-private {@code CardinalityAnalysis} with {@code static Cardinality
 * comma(Cardinality left, Cardinality right)}, {@code static Cardinality alt(Cardinality left, Cardinality right)} and
 * {@code static Cardinality tryCatch(Cardinality body, Cardinality handler)}.
 */
class LatticeTablesTest {

  /** The inputs of every table, in the order of the tables' rows and columns. */
  private static final Cardinality[] ALL = {NEVER, NONE, ONE, AT_MOST_ONE, MANY};

  /**
   * {@code LEQ[i][j]}: {@code ALL[i]} is at or below {@code ALL[j]} in the ADR's lattice. Written out, not derived from
   * the enum's order, which puts NONE before ONE though they are not comparable.
   */
  private static final boolean[][] LEQ = {
    // NEVER  NONE   ONE    AT_MOST_ONE MANY
    {true, true, true, true, true}, // NEVER
    {false, true, false, true, true}, // NONE
    {false, false, true, true, true}, // ONE
    {false, false, false, true, true}, // AT_MOST_ONE
    {false, false, false, false, true} // MANY
  };

  /** A binary rule of the analysis, as the code under test computes it. */
  @FunctionalInterface
  private interface Rule {
    Cardinality apply(Cardinality a, Cardinality b);
  }

  /** A rule's name, the code that computes it and the table the design's text gives for it, rows a, columns b. */
  private record Table(String name, Rule rule, Cardinality[][] expected) {}

  /**
   * The five tables. join: the least upper bound. seq(a, b), "each result of a runs b": a when a is NEVER or NONE; for
   * b NEVER, NEVER when a is ONE and NONE otherwise; for b NONE, NONE; else MANY if either side is MANY, ONE if both are
   * ONE, and AT_MOST_ONE otherwise. comma: NEVER for a NEVER left side, the right side for a NONE one, MANY otherwise.
   * alt: NEVER gives NEVER, NONE the right side, ONE or AT_MOST_ONE join(ONE, right), MANY MANY. try: MANY for a MANY
   * body, join(body, handler) otherwise.
   */
  private static List<Table> tables() {
    return List.of(
        new Table(
            "join",
            Cardinality::join,
            new Cardinality[][] {
              {NEVER, NONE, ONE, AT_MOST_ONE, MANY},
              {NONE, NONE, AT_MOST_ONE, AT_MOST_ONE, MANY},
              {ONE, AT_MOST_ONE, ONE, AT_MOST_ONE, MANY},
              {AT_MOST_ONE, AT_MOST_ONE, AT_MOST_ONE, AT_MOST_ONE, MANY},
              {MANY, MANY, MANY, MANY, MANY}
            }),
        new Table(
            "seq",
            Cardinality::seq,
            new Cardinality[][] {
              {NEVER, NEVER, NEVER, NEVER, NEVER},
              {NONE, NONE, NONE, NONE, NONE},
              {NEVER, NONE, ONE, AT_MOST_ONE, MANY},
              {NONE, NONE, AT_MOST_ONE, AT_MOST_ONE, MANY},
              {NONE, NONE, MANY, MANY, MANY}
            }),
        new Table(
            "comma",
            CardinalityAnalysis::comma,
            new Cardinality[][] {
              {NEVER, NEVER, NEVER, NEVER, NEVER},
              {NEVER, NONE, ONE, AT_MOST_ONE, MANY},
              {MANY, MANY, MANY, MANY, MANY},
              {MANY, MANY, MANY, MANY, MANY},
              {MANY, MANY, MANY, MANY, MANY}
            }),
        new Table(
            "alt",
            CardinalityAnalysis::alt,
            new Cardinality[][] {
              {NEVER, NEVER, NEVER, NEVER, NEVER},
              {NEVER, NONE, ONE, AT_MOST_ONE, MANY},
              {ONE, AT_MOST_ONE, ONE, AT_MOST_ONE, MANY},
              {ONE, AT_MOST_ONE, ONE, AT_MOST_ONE, MANY},
              {MANY, MANY, MANY, MANY, MANY}
            }),
        new Table(
            "try",
            CardinalityAnalysis::tryCatch,
            new Cardinality[][] {
              {NEVER, NONE, ONE, AT_MOST_ONE, MANY},
              {NONE, NONE, AT_MOST_ONE, AT_MOST_ONE, MANY},
              {ONE, AT_MOST_ONE, ONE, AT_MOST_ONE, MANY},
              {AT_MOST_ONE, AT_MOST_ONE, AT_MOST_ONE, AT_MOST_ONE, MANY},
              {MANY, MANY, MANY, MANY, MANY}
            }));
  }

  private static boolean leq(Cardinality a, Cardinality b) {
    return LEQ[index(a)][index(b)];
  }

  private static int index(Cardinality c) {
    for (int i = 0; i < ALL.length; i++) {
      if (ALL[i] == c) {
        return i;
      }
    }
    throw new IllegalArgumentException("not a lattice value: " + c);
  }

  /**
   * Each rule, for every a at or below a' and every b at or below b', gives rule(a, b) at or below rule(a', b'); and
   * seq(seq(a, b), c) is seq(a, seq(b, c)) for all 125 triples. A broken pair is listed, so one run shows them all.
   */
  @Test
  void latticeTablesAreMonotone() {
    List<String> broken = new ArrayList<>();
    for (Table t : tables()) {
      for (Cardinality a : ALL) {
        for (Cardinality a2 : ALL) {
          for (Cardinality b : ALL) {
            for (Cardinality b2 : ALL) {
              if (leq(a, a2) && leq(b, b2)) {
                Cardinality low = t.rule().apply(a, b);
                Cardinality high = t.rule().apply(a2, b2);
                if (!leq(low, high)) {
                  broken.add(t.name() + "(" + a + ", " + b + ") = " + low + " is not at or below " + t.name() + "(" + a2 + ", " + b2 + ") = " + high);
                }
              }
            }
          }
        }
      }
    }
    for (Cardinality a : ALL) {
      for (Cardinality b : ALL) {
        for (Cardinality c : ALL) {
          Cardinality left = Cardinality.seq(Cardinality.seq(a, b), c);
          Cardinality right = Cardinality.seq(a, Cardinality.seq(b, c));
          if (left != right) {
            broken.add("seq(seq(" + a + ", " + b + "), " + c + ") = " + left + " but seq(" + a + ", seq(" + b + ", " + c + ")) = " + right);
          }
        }
      }
    }
    assertThat(broken).as("monotonicity and seq's associativity").isEmpty();
  }

  /**
   * Witness for {@link #latticeTablesAreMonotone}, which a constant rule would pass: each rule gives the design's value
   * on each of its 25 inputs.
   */
  @Test
  void latticeTablesFollowTheRules() {
    List<String> wrong = new ArrayList<>();
    for (Table t : tables()) {
      for (int i = 0; i < ALL.length; i++) {
        for (int j = 0; j < ALL.length; j++) {
          Cardinality got = t.rule().apply(ALL[i], ALL[j]);
          if (got != t.expected()[i][j]) {
            wrong.add(t.name() + "(" + ALL[i] + ", " + ALL[j] + ") = " + got + ", the design gives " + t.expected()[i][j]);
          }
        }
      }
    }
    assertThat(wrong).as("the rules' tables").isEmpty();
  }
}
