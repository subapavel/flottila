package dev.rail8.jq.query.analysis;

/**
 * How many results a node can give for one input (ADR-0007 and its #112 amendment, F-56, GLOSSARY "Cardinality"). An
 * error or a break is not a result, and {@link #ONE} and {@link #AT_MOST_ONE} also promise that nothing runs after the
 * result. The lattice is {@link #NEVER} below {@link #NONE} and {@link #ONE}, both below {@link #AT_MOST_ONE}, below
 * {@link #MANY}; NONE and ONE are not comparable, so the constants' order is not the lattice's.
 */
public enum Cardinality {
  /** No result and no normal end: a Break, or a body that only recurses. The fixpoint's start; a fact keeps it as NONE. */
  NEVER,
  /** No result. */
  NONE,
  /** Exactly one result, unless an error or a break ends the node, and nothing runs after it. */
  ONE,
  /** One result at most, and nothing runs after it. */
  AT_MOST_ONE,
  /** Any number of results; the safe value. */
  MANY;

  /** The constants by their ordinal. */
  private static final Cardinality[] BY_ORDINAL = values();

  /** The least value at or above both {@code a} and {@code b}. */
  public static Cardinality join(Cardinality a, Cardinality b) {
    if (a == NONE && b == ONE || a == ONE && b == NONE) {
      return AT_MOST_ONE;
    }
    // Every other pair is comparable, and on comparable values the constants' order is the lattice's.
    return BY_ORDINAL[Math.max(a.ordinal(), b.ordinal())];
  }

  /**
   * "Each result of {@code a} runs {@code b}": {@code a} when it is NEVER or NONE; for {@code b} NEVER, NEVER when
   * {@code a} is ONE and NONE otherwise; for {@code b} NONE, NONE; otherwise MANY when either is MANY, ONE when both are
   * ONE, and AT_MOST_ONE.
   */
  public static Cardinality seq(Cardinality a, Cardinality b) {
    if (a == NEVER || a == NONE) {
      return a;
    }
    if (b == NEVER) {
      return a == ONE ? NEVER : NONE;
    }
    if (b == NONE) {
      return NONE;
    }
    if (a == MANY || b == MANY) {
      return MANY;
    }
    return a == ONE && b == ONE ? ONE : AT_MOST_ONE;
  }
}
