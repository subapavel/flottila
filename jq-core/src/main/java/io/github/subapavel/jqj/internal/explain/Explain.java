package io.github.subapavel.jqj.internal.explain;

import io.github.subapavel.jqj.query.analysis.CallGraph;
import io.github.subapavel.jqj.query.analysis.Facts;
import io.github.subapavel.jqj.query.analysis.Numbering;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Source;
import java.util.List;

/**
 * The text of {@code JqFilter.explain()} (ADR-0016, #108, ADR-0045 #112): the main tree, then each function of the
 * program by its place, one line per node. Unstable and outside semver. The ids and the groups are query-core's
 * ({@link Numbering}, {@link CallGraph}); the printer keeps none of its own.
 *
 * <ul>
 *   <li>A node line is {@code <function> <id> <kind> <start>..<end>}, then its facts as {@code name=value}. The function
 *       is {@code main} or {@code fn<place>}; the id is the node's freeze id, which numbers the body's nodes from 0 in
 *       pre-order, a node before its parts in their order; the span is the node's source.
 *   <li>A Call shows {@code call=RECURSIVE} when the caller is a function and the callee is in its recursive group
 *       (GLOSSARY "Recursive group"), and {@code call=PLAIN} for every other call, each call from main among them; then
 *       {@code callee=fn<place>}. A ParamCall shows {@code param=<j>}.
 *   <li>Every node line ends with its cardinality, {@code card=<NONE|ONE|AT_MOST_ONE|MANY>} (ADR-0007 #112).
 *   <li>Each function's lines follow its function line, {@code function fn<place> <name>/<arity> <start>..<end>
 *       closures=<n> values=<m>}.
 * </ul>
 */
public final class Explain {
  private static final String MAIN = "main";

  private Explain() {}

  /** The text of {@code program}, whose function at place i jq names {@code names.get(i)}, with its {@code facts}. */
  public static String of(Program program, List<String> names, Facts facts) {
    Numbering numbering = Numbering.of(program);
    CallGraph graph = CallGraph.of(program, numbering);
    List<Function> functions = program.functions();
    StringBuilder text = new StringBuilder();
    body(text, MAIN, -1, numbering, graph, facts);
    for (int place = 0; place < functions.size(); place++) {
      Function f = functions.get(place);
      text.append("function ").append(function(place)).append(' ').append(names.get(place)).append(' ').append(span(f.source()));
      text.append(" closures=").append(f.closures()).append(" values=").append(f.values()).append('\n');
      body(text, function(place), place, numbering, graph, facts);
    }
    return text.toString();
  }

  /** The lines of the body of function {@code place}, or of main for -1, by freeze id. */
  private static void body(StringBuilder text, String name, int place, Numbering numbering, CallGraph graph, Facts facts) {
    List<Node> nodes = numbering.nodes(place);
    for (int id = 0; id < nodes.size(); id++) {
      Node node = nodes.get(id);
      text.append(name).append(' ').append(id).append(' ').append(node.getClass().getSimpleName());
      text.append(' ').append(span(node.source()));
      if (node instanceof Call c) {
        boolean recursive = place >= 0 && graph.group(c.fn()) == graph.group(place);
        text.append(" call=").append(recursive ? "RECURSIVE" : "PLAIN").append(" callee=").append(function(c.fn()));
      } else if (node instanceof ParamCall p) {
        text.append(" param=").append(p.param());
      }
      text.append(" card=").append(facts.cardinality(place, id)).append('\n');
    }
  }

  private static String function(int place) {
    return "fn" + place;
  }

  private static String span(Source source) {
    return source.start() + ".." + source.end();
  }
}
