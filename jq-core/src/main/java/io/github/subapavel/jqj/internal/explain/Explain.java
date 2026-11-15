package io.github.subapavel.jqj.internal.explain;

import io.github.subapavel.jqj.query.analysis.CallGraph;
import io.github.subapavel.jqj.query.analysis.Facts;
import io.github.subapavel.jqj.query.analysis.Numbering;
import io.github.subapavel.jqj.query.compile.Executable;
import io.github.subapavel.jqj.query.compile.Frozen;
import io.github.subapavel.jqj.query.compile.Nesting;
import io.github.subapavel.jqj.query.compile.StackUnits;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Source;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The text of {@code JqFilter.explain()} (ADR-0016, #108, ADR-0045 #112 and #117, ADR-0007 #115, ADR-0047 #116): the
 * main tree, then each function of the frozen program by its place, one line per node. Unstable and outside semver.
 * The program, the ids and the groups are query-core's ({@link Frozen}, {@link Numbering}, {@link CallGraph}); the
 * printer keeps none of its own.
 *
 * <ul>
 *   <li>A node line is {@code <function> <id> <kind> <start>..<end>}, then its facts as {@code name=value}. The function
 *       is {@code main} or {@code fn<place>}; the id is the node's freeze id, which numbers the body's nodes from 0 in
 *       pre-order, a node before its parts in their order; the span is the node's source.
 *   <li>A Call shows {@code call=RECURSIVE} when the caller is a function and the callee is in its recursive group
 *       (GLOSSARY "Recursive group"), and {@code call=PLAIN} for every other call, each call from main among them; then
 *       {@code callee=fn<place>}. A ParamCall shows {@code param=<j>}.
 *   <li>Every node line shows its cardinality, {@code card=<NONE|ONE|AT_MOST_ONE|MANY>} (ADR-0007 #112), then whether
 *       it may break out, {@code breaks=<true|false>} (ADR-0007 #115), then its run form, {@code form=<push|value>}
 *       (ADR-0047 #116).
 *   <li>Each function's lines follow its function line, {@code function fn<place> <name>/<arity> <start>..<end>
 *       closures=<n> values=<m> emitDepth=<d>}; a copy's line shows {@code copyOf=fn<o>}, the original it was made
 *       from, before its {@code emitDepth}, and has the original's name.
 *   <li>The stack units (ADR-0046 #111 amendment): a Call shows {@code units=<w>}, its whole {@code w}, and a ParamCall
 *       {@code units=<u>}, its descent and rise; a function line and the root line of each closure argument show
 *       {@code emitDepth=<d>}, its leaf-emit depth; and a closing line {@code stack units filter callFreeDepth=<D_f>
 *       starterUnits=<T> bytesPerUnit=<b> reserve=<bytes> specialised=<spent> refused=<keys>} ends the text, the last
 *       two the nodes the built copies hold and the keys refused (ADR-0045 #117), both 0 in the conservative compile.
 * </ul>
 */
public final class Explain {
  private static final String MAIN = "main";

  private Explain() {}

  /**
   * The text of the program {@code executable} froze ({@link Executable#frozen()}), whose original function at place i
   * jq names {@code names.get(i)}, with its facts, run forms and frames (ADR-0046 #111 amendment); a copy has its
   * original's name.
   */
  public static String of(Executable executable, List<String> names) {
    Frozen frozen = executable.frozen();
    Nesting nesting = executable.nesting();
    Numbering numbering = frozen.numbering();
    CallGraph graph = frozen.graph();
    List<Function> functions = frozen.program().functions();
    int[] origin = frozen.origin();
    StringBuilder text = new StringBuilder();
    body(text, MAIN, -1, numbering, graph, executable);
    for (int place = 0; place < functions.size(); place++) {
      Function f = functions.get(place);
      text.append("function ").append(function(place)).append(' ').append(names.get(origin[place])).append(' ').append(span(f.source()));
      text.append(" closures=").append(f.closures()).append(" values=").append(f.values());
      if (origin[place] != place) {
        text.append(" copyOf=").append(function(origin[place]));
      }
      text.append(" emitDepth=").append(nesting.emitDepth(place)).append('\n');
      body(text, function(place), place, numbering, graph, executable);
    }
    text.append("stack units filter callFreeDepth=").append(nesting.callFreeDepth()).append(" starterUnits=").append(nesting.starterUnits());
    text.append(" bytesPerUnit=").append(StackUnits.bytesPerUnit()).append(" reserve=").append(StackUnits.reserve());
    text.append(" specialised=").append(frozen.specialised()).append(" refused=").append(frozen.refused()).append('\n');
    return text.toString();
  }

  /** The lines of the body of function {@code place}, or of main for -1, by freeze id. */
  private static void body(StringBuilder text, String name, int place, Numbering numbering, CallGraph graph, Executable executable) {
    Facts facts = executable.facts();
    Nesting nesting = executable.nesting();
    List<Node> nodes = numbering.nodes(place);
    // The closure arguments of the Calls met so far: a Call comes before its parts in pre-order.
    Set<Node> closures = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int id = 0; id < nodes.size(); id++) {
      Node node = nodes.get(id);
      text.append(name).append(' ').append(id).append(' ').append(node.getClass().getSimpleName());
      text.append(' ').append(span(node.source()));
      if (node instanceof Call c) {
        boolean recursive = place >= 0 && graph.group(c.fn()) == graph.group(place);
        text.append(" call=").append(recursive ? "RECURSIVE" : "PLAIN").append(" callee=").append(function(c.fn()));
        text.append(" units=").append(nesting.units(c));
        closures.addAll(c.closures());
      } else if (node instanceof ParamCall p) {
        text.append(" param=").append(p.param()).append(" units=").append(nesting.units(p));
      }
      if (closures.contains(node)) {
        text.append(" emitDepth=").append(nesting.emitDepth(node));
      }
      text.append(" card=").append(facts.cardinality(place, id));
      text.append(" breaks=").append(facts.mayBreakOut(place, id));
      text.append(" form=").append(executable.form(place, id).name().toLowerCase(Locale.ROOT)).append('\n');
    }
  }

  private static String function(int place) {
    return "fn" + place;
  }

  private static String span(Source source) {
    return source.start() + ".." + source.end();
  }
}
