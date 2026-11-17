package dev.rail8.jq.query.analysis;

import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Program;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * The recursive groups of a program (ADR-0045 and its #112 amendment, GLOSSARY "Recursive group"): the strongly
 * connected components of its call graph, an edge from a function to the callee of each Call in its body, the Calls in
 * the closures it writes among them. The main tree is no function and is in no group. Groups are numbered callee-first:
 * every Call in a group's bodies names a function of that group or of a group with a smaller number, so an analysis
 * that takes the groups in their order finds each callee done, and runs the main tree last (ADR-0007 #112).
 */
public final class CallGraph {
  /** The group of each function, by its place. */
  private final int[] group;

  /** The functions of each group, by place, in the order the search closed them. */
  private final List<int[]> members;

  private CallGraph(int[] group, List<int[]> members) {
    this.group = group;
    this.members = members;
  }

  /** The groups of {@code program}, whose bodies {@code numbering} numbers: Tarjan's algorithm, on a stack of its own. */
  public static CallGraph of(Program program, Numbering numbering) {
    int n = program.functions().size();
    int[][] callees = new int[n][];
    for (int f = 0; f < n; f++) {
      callees[f] = numbering.nodes(f).stream().filter(Call.class::isInstance).mapToInt(c -> ((Call) c).fn()).toArray();
    }
    int[] index = new int[n];
    int[] low = new int[n];
    int[] next = new int[n];
    int[] group = new int[n];
    boolean[] open = new boolean[n];
    Arrays.fill(index, -1);
    List<int[]> members = new ArrayList<>();
    Deque<Integer> component = new ArrayDeque<>();
    Deque<Integer> search = new ArrayDeque<>();
    int visited = 0;
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
            // v's component closes after every component it reaches: callee-first.
            List<Integer> closed = new ArrayList<>();
            int w;
            do {
              w = component.pop();
              open[w] = false;
              group[w] = members.size();
              closed.add(w);
            } while (w != v);
            members.add(closed.stream().mapToInt(Integer::intValue).toArray());
          }
        }
      }
    }
    return new CallGraph(group, List.copyOf(members));
  }

  /** The group of the function at {@code place}. */
  public int group(int place) {
    return group[place];
  }

  /** The number of groups. */
  int groups() {
    return members.size();
  }

  /** The places of the functions of group {@code g}; the caller does not change the array. */
  int[] members(int g) {
    return members.get(g);
  }
}
