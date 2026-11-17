package io.github.subapavel.jqj.oracle.analysis;

import io.github.subapavel.jqj.query.compile.Executable;
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
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only IR that watches one node n of a program from outside (#112 design, "cardinalityBoundsHold"): n becomes
 * {@code Bind(ENTER -> t, Comma(Try(Pipe(n, MARK(t)), RAISED(t)), END(t)))}, built from query-core's own node kinds and
 * four test operations. ENTER starts an {@link Activation}, the token in slot t; MARK passes each result of n on and
 * notes it, and notes the step count when that result's emit has returned (its {@link Many#next}); RAISED notes an error
 * of n's own (a downstream error passes the Try wrapped, ADR-0039) and raises it again with the same value; END, after
 * the Comma's one step, notes the step count when n ended normally. So a run of the wrapped program is the plain run
 * plus those notes, and "nothing runs after the result" (F-56) is {@code END - next - nestedEnds = 1}: the Comma's step
 * and no step of n's own. In a recursive body n's result can come from an activation of n nested in this one, whose
 * Comma steps to its END after this activation's {@code next}: that step is the probe's, not n's, and {@link
 * Activation#nestedEnds} counts it (#117: a copy makes a recursive body AT_MOST_ONE).
 */
final class BoundProbe {

  /** One activation of the watched node: what MARK, RAISED and END saw of it. */
  static final class Activation {
    /** n's results, each one MARK passed on. */
    int results;

    /** The step count when the first result's emit returned, or -1 while none has returned. */
    long next = -1;

    /** Whether a result came after an earlier result's emit had returned. */
    boolean resultAfterResult;

    /** Whether n raised an error of its own. */
    boolean raised;

    /** Whether that error came after a result's emit had returned. */
    boolean raisedAfterResult;

    /** The step count when n ended normally and END ran, or -1 when it did not. */
    long end = -1;

    /** When ENTER made this activation, in the probe's order of ENTER and first {@code next} notes. */
    long entered;

    /** When the first result's emit returned, in the same order, or -1 while none has returned. */
    long nextOrder = -1;

    /**
     * The activations entered before this one's {@link #next} that ended normally after it and before this one's END,
     * so nested in it: each took its Comma's step to END, counted in {@code end - next} and not a step of n's.
     */
    int nestedEnds;
  }

  /** A run's results, each as compact JSON, and how it ended: null, {@code error <json>} or a termination. */
  record Outcome(List<String> results, String ending) {}

  /** An outcome and the steps the run took. */
  record Ran(Outcome outcome, long steps) {}

  /** Every activation of this probe's runs, in the order ENTER made them. */
  final List<Activation> activations = new ArrayList<>();

  /** The activations whose first result's emit has returned and that have not ended normally. */
  private final List<Activation> marked = new ArrayList<>();

  /** The last order ENTER or a first {@code next} note took. */
  private long order;

  private final Operation enter =
      new Operation(
          "_test_enter",
          (Fn0)
              (state, in) -> {
                Activation a = new Activation();
                a.entered = ++order;
                activations.add(a);
                return a;
              });

  private final Operation mark =
      new Operation(
          "_test_mark",
          new Many() {
            @Override
            public long first(RunState state, Object in, Object[] args) {
              Activation a = (Activation) args[0];
              if (a.next >= 0) {
                a.resultAfterResult = true;
              }
              a.results++;
              return 0;
            }

            @Override
            public long next(RunState state, Object in, Object[] args, long cursor) {
              Activation a = (Activation) args[0];
              if (a.next < 0) {
                a.next = state.steps();
                a.nextOrder = ++order;
                marked.add(a);
              }
              return Many.END;
            }

            @Override
            public Object value(RunState state, Object in, Object[] args, long cursor) {
              return in;
            }
          });

  private final Operation raised =
      new Operation(
          "_test_raised",
          (Fn1)
              (state, in, token) -> {
                Activation a = (Activation) token;
                a.raised = true;
                if (a.next >= 0) {
                  a.raisedAfterResult = true;
                }
                throw new FilterException(in);
              });

  private final Operation end =
      new Operation(
          "_test_end",
          (Fn1)
              (state, in, token) -> {
                Activation b = (Activation) token;
                marked.remove(b);
                // b ends while a is marked and was entered before a's first next: b runs inside a. An a that b holds has
                // ended already, or raised or broke out and never ends, so its count is never read.
                for (Activation a : marked) {
                  if (b.entered < a.nextOrder) {
                    a.nestedEnds++;
                  }
                }
                b.end = state.steps();
                return CallForm.NONE;
              });

  /** {@code n} watched through the token in slot {@code t}, a slot no node of n's body uses. */
  Node wrap(Node n, int t) {
    Source s = n.source();
    Node marked = new Pipe(s, n, new Intrinsic(s, mark, List.of(new Var(s, t))));
    Node body = new Comma(s, new Try(s, marked, new Intrinsic(s, raised, List.of(new Var(s, t)))), new Intrinsic(s, end, List.of(new Var(s, t))));
    return new Bind(s, new Intrinsic(s, enter, List.of()), t, body);
  }

  /** {@code program} with the node {@code n} of the body {@code function} (-1 for main) watched. */
  Program wrapped(Program program, int function, Node n) {
    if (function < 0) {
      Node main = program.main();
      return new Program(replace(main, n, wrap(n, freshSlot(main, 0))), program.functions());
    }
    List<Function> functions = new ArrayList<>(program.functions());
    Function f = functions.get(function);
    functions.set(function, new Function(f.source(), f.closures(), f.values(), replace(f.body(), n, wrap(n, freshSlot(f.body(), f.values())))));
    return new Program(program.main(), functions);
  }

  /**
   * Runs {@code executable} on {@code input} with the step budget {@code steps} and nothing else limited, in a run state
   * of its own: its results, its ending and the steps it took.
   */
  static Ran run(Executable executable, Object input, long steps) {
    RunState state = new RunState();
    state.begin(steps, Long.MAX_VALUE, Long.MAX_VALUE);
    List<String> results = new ArrayList<>();
    String ending = null;
    try {
      executable.run(
          state,
          input,
          v -> {
            results.add(((JqValue) v).toJson());
            return true;
          });
    } catch (FilterException e) {
      ending = "error " + ((JqValue) e.value()).toJson();
    } catch (Termination t) {
      ending = "termination " + t.cause() + " " + t.budget();
    } finally {
      state.end();
    }
    return new Ran(new Outcome(results, ending), state.steps());
  }

  /** One more than the largest slot {@code root} names, at least {@code values}: its value parameters are slots too. */
  static int freshSlot(Node root, int values) {
    int largest = values - 1;
    for (Node n : preOrder(root)) {
      largest = Math.max(largest, largestSlot(n));
    }
    return largest + 1;
  }

  private static int largestSlot(Node n) {
    return switch (n) {
      case Bind b -> b.slot();
      case Binds b -> b.slots().stream().mapToInt(Integer::intValue).max().orElse(-1);
      case AltBind a -> Math.max(a.slot(), a.slots().stream().mapToInt(Integer::intValue).max().orElse(-1));
      case Label l -> l.slot();
      case Reduce r -> r.slot();
      case Foreach f -> f.slot();
      case Advance a -> a.slot();
      case Var v -> v.slot();
      case Break b -> b.slot();
      // The kinds that name no slot; a ParamCall names a closure parameter, which is no slot.
      case Identity _, Const _, Pipe _, Comma _, Intrinsic _, Collect _, If _, Alt _, Try _, Call _, ParamCall _ -> -1;
    };
  }

  /** {@code root}'s nodes in pre-order, a node before its parts in their order: the freeze order (GLOSSARY "Freeze id"). */
  static List<Node> preOrder(Node root) {
    List<Node> out = new ArrayList<>();
    collect(root, out);
    return out;
  }

  private static void collect(Node node, List<Node> out) {
    out.add(node);
    for (Node part : parts(node)) {
      collect(part, out);
    }
  }

  /** The parts of {@code node} in their order: the order of its record's components, each list in its order. */
  static List<Node> parts(Node node) {
    return switch (node) {
      case Identity _, Const _, Var _, Break _, ParamCall _ -> List.of();
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

  /** {@code node} with {@code target}, found by identity, replaced by {@code replacement}; unchanged parts are kept. */
  static Node replace(Node node, Node target, Node replacement) {
    if (node == target) {
      return replacement;
    }
    List<Node> parts = parts(node);
    List<Node> replaced = new ArrayList<>(parts.size());
    boolean changed = false;
    for (Node part : parts) {
      Node r = replace(part, target, replacement);
      changed |= r != part;
      replaced.add(r);
    }
    return changed ? withParts(node, replaced) : node;
  }

  /** A node of {@code node}'s kind and fields with the parts {@code p}, in {@link #parts}' order. */
  private static Node withParts(Node node, List<Node> p) {
    Source s = node.source();
    return switch (node) {
      case Identity _, Const _, Var _, Break _, ParamCall _ -> node;
      case Pipe _ -> new Pipe(s, p.get(0), p.get(1));
      case Comma _ -> new Comma(s, p.get(0), p.get(1));
      case Bind b -> new Bind(s, p.get(0), b.slot(), p.get(1));
      case Binds b -> new Binds(s, p.subList(0, p.size() - 1), b.slots(), p.getLast());
      case Intrinsic i -> new Intrinsic(s, i.op(), p);
      case Collect c -> new Collect(s, p.get(0), c.collector());
      case If i -> new If(s, p.get(0), p.get(1), p.get(2), i.truth());
      case Alt a -> new Alt(s, p.get(0), p.get(1), a.truth());
      case AltBind a -> {
        List<Alternative> alternatives = new ArrayList<>();
        for (int k = 0; k < a.alternatives().size(); k++) {
          alternatives.add(new Alternative(p.get(1 + k), a.alternatives().get(k).from()));
        }
        yield new AltBind(s, p.get(0), a.slot(), alternatives, a.slots(), p.getLast());
      }
      case Try _ -> new Try(s, p.get(0), p.get(1));
      case Label l -> new Label(s, l.slot(), p.get(0));
      case Reduce r -> new Reduce(s, p.get(0), r.slot(), p.get(1));
      case Foreach f -> new Foreach(s, p.get(0), f.slot(), p.get(1));
      case Advance a -> new Advance(s, a.slot(), p.get(0));
      case Call c -> new Call(s, c.fn(), p.subList(0, c.closures().size()), p.subList(c.closures().size(), p.size()));
    };
  }
}
