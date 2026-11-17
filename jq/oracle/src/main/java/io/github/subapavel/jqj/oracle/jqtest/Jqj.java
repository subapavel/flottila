package io.github.subapavel.jqj.oracle.jqtest;

import java.util.function.Predicate;

/**
 * What the jqtest runner needs of jqj. The oracle's tests implement it over the public API (ADR-0020); the oracle
 * module does not read jq-core, so the runner sees only this. Every method throws {@link JqjException} for an
 * exception of jqj's and lets any other exception through.
 */
public interface Jqj {

  /** Compiles a program. */
  Filter compile(String program);

  /** One JSON text read with jqj's reader. */
  JvValue read(String json);

  /** One JSON text read with jqj's reader, written with jqj's writer and read again. */
  JvValue reread(String json);

  /** A compiled program. */
  interface Filter {
    /** Reads {@code input} with jqj's reader and runs on it; each result goes to {@code sink} until it answers false. */
    void run(String input, Predicate<JvValue> sink);
  }
}
