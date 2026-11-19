package dev.rail8.mapping.studio;

/**
 * The result of a test run: output, the diagnostics that refuse the working copy, or the runtime
 * error that stopped the run. Output and error carry the run's trace; a refused working copy never
 * ran, so it has none.
 */
sealed interface TestResult permits TestOutput, TestRefused, TestError {}
