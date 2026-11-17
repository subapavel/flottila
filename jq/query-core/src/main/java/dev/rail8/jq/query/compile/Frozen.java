package dev.rail8.jq.query.compile;

import dev.rail8.jq.query.analysis.CallGraph;
import dev.rail8.jq.query.analysis.Numbering;
import dev.rail8.jq.query.ir.Program;

/**
 * The program an {@link Executable} runs, as freeze left it: the specialised program, its {@link Numbering} and
 * {@link CallGraph}, which the analyses and {@code explain()} read, the original of each function by its place, and the
 * specialisation counts. Under {@link CompileMode#CONSERVATIVE} the program is the one compiled, every function its own
 * original, and both counts 0.
 *
 * @param program the specialised program: the originals at their places, then the copies in the order their keys were
 *     built
 * @param numbering the freeze ids of {@code program}'s bodies
 * @param graph the recursive groups of {@code program}
 * @param origin by function place, the place of the original function it copies, its own place for an original; the
 *     caller does not change the array
 * @param specialised the nodes the built copies hold, counted when each key was built, which the specialisation cap
 *     bounds
 * @param refused the (group, argument tuple) keys that did not fit the cap
 */
public record Frozen(Program program, Numbering numbering, CallGraph graph, int[] origin, int specialised, int refused) {}
