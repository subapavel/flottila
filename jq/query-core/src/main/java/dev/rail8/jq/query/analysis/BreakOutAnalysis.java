package dev.rail8.jq.query.analysis;

import dev.rail8.jq.query.ir.Break;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Label;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.ParamCall;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * May break out for every node and every function: a node may break out when its lowest break target is below its own
 * freeze id. The targets:
 *
 * <ul>
 *   <li>a Break's is the freeze id of the Label that binds its slot in its body, or -1 when no Label there binds it (a
 *       token passed in as a value);
 *   <li>a ParamCall's is -1;
 *   <li>a Call's is -1 when its callee may break out, and otherwise the lowest of its closures' and values';
 *   <li>every other kind takes the lowest of its parts, so Identity, Const and Var, which have none, have no target, and
 *       a Try or an AltBind catches no break.
 * </ul>
 *
 * <p>The groups run callee-first and the main tree last, as for cardinality; a body is read in descending freeze id, so
 * each part is done before its node. A function's value is its body root's. Every function of a group reaches every
 * other through Calls, and a target of -1 passes up to each root, so a group shares one value: its first round reads
 * the group false, and when a root comes out true the whole group is true and a second round gives its nodes the facts
 * of that value; there is no third.
 */
final class BreakOutAnalysis {
  /** No target: above every freeze id. */
  private static final int NONE = Integer.MAX_VALUE;

  private final Numbering numbering;

  /** The value of each function so far, by its place: false until its group runs. */
  private final boolean[] functions;

  private BreakOutAnalysis(Numbering numbering) {
    this.numbering = numbering;
    this.functions = new boolean[numbering.functions()];
  }

  /**
   * The fact of each node of the program {@code numbering} numbers, by body, the main tree's first and then each
   * function's by place, and by freeze id.
   */
  static boolean[][] of(Numbering numbering, CallGraph graph) {
    BreakOutAnalysis analysis = new BreakOutAnalysis(numbering);
    boolean[][] breaks = new boolean[numbering.functions() + 1][];
    for (int g = 0; g < graph.groups(); g++) {
      boolean shared = false;
      for (int place : graph.members(g)) {
        breaks[place + 1] = analysis.body(place);
        shared |= breaks[place + 1][0];
      }
      if (shared) {
        for (int place : graph.members(g)) {
          analysis.functions[place] = true;
        }
        for (int place : graph.members(g)) {
          breaks[place + 1] = analysis.body(place);
        }
      }
    }
    breaks[0] = analysis.body(-1);
    return breaks;
  }

  /** The fact of each node of the body {@code body}, -1 for the main tree, by freeze id. */
  private boolean[] body(int body) {
    List<Node> nodes = numbering.nodes(body);
    // The verifier lets one Label of a body bind a slot, and a Break name only a Label's slot around it or a value
    // parameter, which no Label binds.
    Map<Integer, Integer> labels = new HashMap<>();
    for (int id = 0; id < nodes.size(); id++) {
      if (nodes.get(id) instanceof Label l) {
        labels.put(l.slot(), id);
      }
    }
    int[] low = new int[nodes.size()];
    boolean[] breaks = new boolean[nodes.size()];
    for (int id = low.length - 1; id >= 0; id--) {
      low[id] = target(nodes.get(id), labels, low);
      breaks[id] = low[id] < id;
    }
    return breaks;
  }

  /** The lowest target of {@code node}, its parts' in {@code low} by freeze id. */
  private int target(Node node, Map<Integer, Integer> labels, int[] low) {
    return switch (node) {
      case Break b -> labels.getOrDefault(b.slot(), -1);
      case ParamCall _ -> -1;
      case Call c when functions[c.fn()] -> -1;
      // Every other kind, a Call of a callee that does not break out among them, takes the lowest of its parts, which
      // Numbering.parts lists for each kind in one place.
      default -> {
        int lowest = NONE;
        for (Node part : Numbering.parts(node)) {
          lowest = Math.min(lowest, low[numbering.id(part)]);
        }
        yield lowest;
      }
    };
  }
}
