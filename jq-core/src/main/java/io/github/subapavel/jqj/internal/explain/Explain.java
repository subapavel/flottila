package io.github.subapavel.jqj.internal.explain;

import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.compile.Nesting;
import io.github.subapavel.jqj.query.compile.StackUnits;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The text of {@code JqFilter.explain()} (ADR-0016, #108): the main tree, then each function of the program by its
 * place, one line per node. Unstable and outside semver.
 *
 * <ul>
 *   <li>A node line is {@code <function> <id> <kind> <start>..<end>}, then its facts as {@code name=value}. The function
 *       is {@code main} or {@code fn<place>}; the id numbers the body's nodes from 0 in pre-order, a node before its
 *       parts in their order; the span is the node's source.
 *   <li>A Call shows {@code call=RECURSIVE} when the caller is a function and the callee is in its recursive group,
 *       which the call graph's strongly connected components give (GLOSSARY "Recursive group"), and {@code call=PLAIN}
 *       for every other call, each call from main among them; then {@code callee=fn<place>}. A ParamCall shows
 *       {@code param=<j>}.
 *   <li>Each function's lines follow its function line, {@code function fn<place> <name>/<arity> <start>..<end>
 *       closures=<n> values=<m> emitDepth=<d>}.
 *   <li>The stack units (ADR-0046 #111 amendment): a Call shows {@code units=<w>}, its whole {@code w}, and a ParamCall
 *       {@code units=<u>}, its descent and rise; a function line and the root line of each closure argument show
 *       {@code emitDepth=<d>}, its leaf-emit depth; and a closing line {@code stack units filter callFreeDepth=<D_f>
 *       starterUnits=<T> bytesPerUnit=<b> reserve=<bytes>} ends the text.
 * </ul>
 */
public final class Explain {
  private static final String MAIN = "main";

  private Explain() {}

  /**
   * The text of {@code program}, whose function at place i jq names {@code names.get(i)}, and whose frames
   * {@code nesting} gives (ADR-0046 #111 amendment).
   */
  public static String of(Program program, List<String> names, Nesting nesting) {
    List<Function> functions = program.functions();
    int[] group = groups(functions);
    StringBuilder text = new StringBuilder();
    body(text, MAIN, -1, program.main(), group, nesting);
    for (int place = 0; place < functions.size(); place++) {
      Function f = functions.get(place);
      text.append("function ").append(function(place)).append(' ').append(names.get(place)).append(' ').append(span(f.source()));
      text.append(" closures=").append(f.closures()).append(" values=").append(f.values());
      text.append(" emitDepth=").append(nesting.emitDepth(place)).append('\n');
      body(text, function(place), place, f.body(), group, nesting);
    }
    text.append("stack units filter callFreeDepth=").append(nesting.callFreeDepth()).append(" starterUnits=").append(nesting.starterUnits());
    text.append(" bytesPerUnit=").append(StackUnits.bytesPerUnit()).append(" reserve=").append(StackUnits.reserve()).append('\n');
    return text.toString();
  }

  /** The lines of the body {@code root} of function {@code place}, or of main for -1. */
  private static void body(StringBuilder text, String name, int place, Node root, int[] group, Nesting nesting) {
    int[] id = {0};
    // The closure arguments of the Calls met so far: a Call comes before its parts in pre-order.
    Set<Node> closures = Collections.newSetFromMap(new IdentityHashMap<>());
    preOrder(
        root,
        node -> {
          text.append(name).append(' ').append(id[0]++).append(' ').append(node.getClass().getSimpleName());
          text.append(' ').append(span(node.source()));
          if (node instanceof Call c) {
            boolean recursive = place >= 0 && group[c.fn()] == group[place];
            text.append(" call=").append(recursive ? "RECURSIVE" : "PLAIN").append(" callee=").append(function(c.fn()));
            text.append(" units=").append(nesting.units(c));
            closures.addAll(c.closures());
          } else if (node instanceof ParamCall p) {
            text.append(" param=").append(p.param()).append(" units=").append(nesting.units(p));
          }
          if (closures.contains(node)) {
            text.append(" emitDepth=").append(nesting.emitDepth(node));
          }
          text.append('\n');
        });
  }

  private static String function(int place) {
    return "fn" + place;
  }

  private static String span(Source source) {
    return source.start() + ".." + source.end();
  }

  /** Visits {@code root} and its parts in pre-order, from a stack of its own, so a deep tree takes no deep recursion. */
  private static void preOrder(Node root, Consumer<Node> visit) {
    Deque<Node> pending = new ArrayDeque<>();
    pending.push(root);
    while (!pending.isEmpty()) {
      Node node = pending.pop();
      visit.accept(node);
      List<Node> parts = parts(node);
      for (int k = parts.size() - 1; k >= 0; k--) {
        pending.push(parts.get(k));
      }
    }
  }

  /** The parts of {@code node} in their order: the order of its record's components, each list in its order. */
  private static List<Node> parts(Node node) {
    return switch (node) {
      case Identity _ -> List.of();
      case Const _ -> List.of();
      case Var _ -> List.of();
      case Break _ -> List.of();
      case ParamCall _ -> List.of();
      case Pipe p -> List.of(p.left(), p.right());
      case Comma c -> List.of(c.left(), c.right());
      case Bind b -> List.of(b.value(), b.body());
      case Binds b -> {
        List<Node> parts = new ArrayList<>(b.values());
        parts.add(b.body());
        yield parts;
      }
      case Intrinsic i -> i.args();
      case Collect c -> List.of(c.body());
      case If i -> List.of(i.cond(), i.then(), i.otherwise());
      case Alt a -> List.of(a.left(), a.right());
      case AltBind a -> {
        List<Node> parts = new ArrayList<>();
        parts.add(a.value());
        for (Alternative alternative : a.alternatives()) {
          parts.add(alternative.match());
        }
        parts.add(a.body());
        yield parts;
      }
      case Try t -> List.of(t.body(), t.handler());
      case Label l -> List.of(l.body());
      case Reduce r -> List.of(r.init(), r.each());
      case Foreach f -> List.of(f.init(), f.each());
      case Advance a -> List.of(a.update());
      case Call c -> {
        List<Node> parts = new ArrayList<>(c.closures());
        parts.addAll(c.values());
        yield parts;
      }
    };
  }

  /**
   * Each function's recursive group: a number that the functions of one strongly connected component of the call graph
   * share, an edge from a function to the callee of each Call in its body. Tarjan's algorithm, its depth-first search on
   * a stack of its own.
   */
  private static int[] groups(List<Function> functions) {
    int n = functions.size();
    int[][] callees = new int[n][];
    for (int f = 0; f < n; f++) {
      List<Integer> called = new ArrayList<>();
      preOrder(functions.get(f).body(), node -> {
        if (node instanceof Call c) {
          called.add(c.fn());
        }
      });
      callees[f] = called.stream().mapToInt(Integer::intValue).toArray();
    }
    int[] index = new int[n];
    int[] low = new int[n];
    int[] next = new int[n];
    int[] group = new int[n];
    boolean[] open = new boolean[n];
    Arrays.fill(index, -1);
    Deque<Integer> component = new ArrayDeque<>();
    Deque<Integer> search = new ArrayDeque<>();
    int visited = 0;
    int groups = 0;
    for (int start = 0; start < n; start++) {
      if (index[start] >= 0) {
        continue;
      }
      index[start] = low[start] = visited++;
      component.push(start);
      open[start] = true;
      search.push(start);
      while (!search.isEmpty()) {
        int v = search.peek();
        if (next[v] < callees[v].length) {
          int w = callees[v][next[v]++];
          if (index[w] < 0) {
            index[w] = low[w] = visited++;
            component.push(w);
            open[w] = true;
            search.push(w);
          } else if (open[w]) {
            low[v] = Math.min(low[v], index[w]);
          }
        } else {
          search.pop();
          if (!search.isEmpty()) {
            int u = search.peek();
            low[u] = Math.min(low[u], low[v]);
          }
          if (low[v] == index[v]) {
            int w;
            do {
              w = component.pop();
              open[w] = false;
              group[w] = groups;
            } while (w != v);
            groups++;
          }
        }
      }
    }
    return group;
  }
}
