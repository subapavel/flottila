package dev.rail8.jq.oracle.jqtest;

import java.util.function.Predicate;

/**
 * What the jqtest runner needs of this engine, Rail8's jq. The oracle's tests implement it over the public API; the
 * oracle module does not read jq-core, so the runner sees only this. Every method throws {@link EngineException} for an
 * exception of the engine's and lets any other exception through.
 */
public interface Engine {

  /** Compiles a program. */
  Filter compile(String program);

  /** One JSON text read with the engine's reader. */
  JvValue read(String json);

  /** One JSON text read with the engine's reader, written with its writer and read again. */
  JvValue reread(String json);

  /** A compiled program. */
  interface Filter {
    /**
     * Reads {@code input} with the engine's reader and runs on it; each result goes to {@code sink} until it answers
     * false.
     */
    void run(String input, Predicate<JvValue> sink);
  }
}
