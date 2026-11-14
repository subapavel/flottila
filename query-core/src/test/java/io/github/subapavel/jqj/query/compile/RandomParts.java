package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Collector;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import io.github.subapavel.jqj.query.run.Truth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Random hand-made IR for the seeded properties of ADR-0043 ({@link BindsPropertyTest}, {@link IfTailPropertyTest}),
 * over a toy format whose values are strings and lists (founding §14) and whose truth holds for every value but
 * {@link #NO}. A {@link Part} is a recipe that builds fresh nodes at each call, in one of two forms. The tail form
 * builds each {@link Lets} as one Binds and each {@link Cond} as an If on its condition. The push form builds each Lets
 * as the chain of nested Bind nodes it means, the first value outermost, and each If on its condition inside a Label
 * that no Break names: a Label is no tail emitter, so that condition runs in push form, and a Label ticks and charges
 * nothing of its own (ADR-0039). So the push form holds no Binds and no If on a tail condition, and the two forms
 * differ only in how they run.
 *
 * <p>The tail emitters of ADR-0043, decided by kind alone, are {@link Id}, {@link Con}, {@link Read}, the one-result
 * Intrinsics {@link Tag}, {@link Nothing}, {@link Raise} and {@link Slots}, and a {@link Seq}, {@link Cond}, {@link
 * Let} or {@link Lets} made of them. The other parts are {@link Both} (a Comma), {@link Over} (a Many, which may raise
 * after its last value), {@link Gather} (a Collect), {@link Fence} (a Label) and {@link Leave} (a Break).
 */
final class RandomParts {
  static final Source AT = new Source(0, 1);

  /** The slot of the Label around every root: the Break of a Collect that breaks out names it. */
  static final int ROOT = 0;

  /** The one value the toy truth does not hold for. */
  static final Object NO = "no";

  static final Truth TRUTH = v -> !NO.equals(v);

  /** The input of every run. */
  static final Object IN = "in";

  private static final Collector LIST = (elements, length) -> List.of(Arrays.copyOf(elements, length));

  private static final List<Object> CONSTANTS = List.of("a", "b", "c", NO);

  private RandomParts() {}

  /** How parts build: in the tail or the push form, the push form's Labels taking slots from {@code free} up. */
  static final class Form {
    private final boolean push;
    private int next;

    private Form(boolean push, int free) {
      this.push = push;
      this.next = free;
    }

    /** The tail form: each Lets one Binds, each If on its condition. */
    static Form tail(int free) {
      return new Form(false, free);
    }

    /** The push form: each Lets nested Binds, each If's condition inside a Label of a slot from {@code free} up. */
    static Form push(int free) {
      return new Form(true, free);
    }
  }

  /** A recipe for one node: each build makes fresh nodes, as the verifier needs a tree. */
  sealed interface Part {
    Node build(Form form);
  }

  /** {@code .} */
  record Id() implements Part {
    @Override
    public Node build(Form form) {
      return new Identity(AT);
    }
  }

  /** A Const of {@code value}. */
  record Con(Object value) implements Part {
    @Override
    public Node build(Form form) {
      return new Const(AT, value);
    }
  }

  /** A Var of {@code slot}. */
  record Read(int slot) implements Part {
    @Override
    public Node build(Form form) {
      return new Var(AT, slot);
    }
  }

  /** An Fn1 giving {@code [name, a]}, {@code a} the value of {@code slot}, or the input when it is -1. */
  record Tag(String name, int slot) implements Part {
    @Override
    public Node build(Form form) {
      Fn1 tag = (state, in, a) -> List.of(name, a);
      return new Intrinsic(AT, new Operation(name, tag), List.of(argument(slot)));
    }
  }

  /** An Fn0 with no result. */
  record Nothing() implements Part {
    @Override
    public Node build(Form form) {
      Fn0 none = (state, in) -> CallForm.NONE;
      return new Intrinsic(AT, new Operation("nothing", none), List.of());
    }
  }

  /** An Fn1 raising {@code [name, a]}, {@code a} as for {@link Tag}. */
  record Raise(String name, int slot) implements Part {
    @Override
    public Node build(Form form) {
      Fn1 raise =
          (state, in, a) -> {
            throw new FilterException(List.of(name, a));
          };
      return new Intrinsic(AT, new Operation(name, raise), List.of(argument(slot)));
    }
  }

  /** An FnN giving {@code ["slots", its input, [the values of slots]]}. */
  record Slots(List<Integer> slots) implements Part {
    @Override
    public Node build(Form form) {
      FnN all = (state, in, args) -> List.of("slots", in, List.of(args));
      List<Node> args = new ArrayList<>();
      for (int slot : slots) {
        args.add(new Var(AT, slot));
      }
      return new Intrinsic(AT, new Operation("slots", all), args);
    }
  }

  /** {@code left | right} */
  record Seq(Part left, Part right) implements Part {
    @Override
    public Node build(Form form) {
      return new Pipe(AT, left.build(form), right.build(form));
    }
  }

  /** {@code if cond then then else otherwise end}; the push form puts the condition inside a Label. */
  record Cond(Part cond, Part then, Part otherwise) implements Part {
    @Override
    public Node build(Form form) {
      Node c = form.push ? new Label(AT, form.next++, cond.build(form)) : cond.build(form);
      return new If(AT, c, then.build(form), otherwise.build(form), TRUTH);
    }
  }

  /** {@code value as $slot | body} */
  record Let(Part value, int slot, Part body) implements Part {
    @Override
    public Node build(Form form) {
      return new Bind(AT, value.build(form), slot, body.build(form));
    }
  }

  /** One Binds in the tail form; in the push form the nested Binds it means, {@code values[0]} outermost. */
  record Lets(List<Part> values, List<Integer> slots, Part body) implements Part {
    @Override
    public Node build(Form form) {
      if (!form.push) {
        List<Node> built = new ArrayList<>();
        for (Part value : values) {
          built.add(value.build(form));
        }
        return new Binds(AT, built, slots, body.build(form));
      }
      Node nested = body.build(form);
      for (int k = values.size() - 1; k >= 0; k--) {
        nested = new Bind(AT, values.get(k).build(form), slots.get(k), nested);
      }
      return nested;
    }
  }

  /** {@code left, right}: a Comma, one tick on entering the right. */
  record Both(Part left, Part right) implements Part {
    @Override
    public Node build(Form form) {
      return new Comma(AT, left.build(form), right.build(form));
    }
  }

  /** A Many over {@code values}, one tick each; when {@code raises}, it raises {@code ["over", n]} after the last. */
  record Over(List<Object> values, boolean raises) implements Part {
    @Override
    public Node build(Form form) {
      Many many =
          new Many() {
            @Override
            public long first(RunState state, Object in, Object[] args) {
              return at(0);
            }

            @Override
            public long next(RunState state, Object in, Object[] args, long cursor) {
              return at(cursor + 1);
            }

            @Override
            public Object value(RunState state, Object in, Object[] args, long cursor) {
              return values.get((int) cursor);
            }

            private long at(long cursor) {
              if (cursor < values.size()) {
                return cursor;
              }
              if (raises) {
                throw new FilterException(List.of("over", values.size()));
              }
              return Many.END;
            }
          };
      return new Intrinsic(AT, new Operation("over", many), List.of());
    }
  }

  /** {@code [body]} */
  record Gather(Part body) implements Part {
    @Override
    public Node build(Form form) {
      return new Collect(AT, body.build(form), LIST);
    }
  }

  /** {@code label $slot | body} */
  record Fence(int slot, Part body) implements Part {
    @Override
    public Node build(Form form) {
      return new Label(AT, slot, body.build(form));
    }
  }

  /** {@code break $slot} */
  record Leave(int slot) implements Part {
    @Override
    public Node build(Form form) {
      return new Break(AT, slot);
    }
  }

  private static Node argument(int slot) {
    return slot < 0 ? new Identity(AT) : new Var(AT, slot);
  }

  /** Random parts from one seed; slot {@link #ROOT} is the root Label's, and every part's own slots come after it. */
  static final class Generator {
    private final SplittableRandom random;
    private int next = ROOT + 1;

    Generator(long seed) {
      this.random = new SplittableRandom(seed);
    }

    /** A slot no part took yet. */
    int fresh() {
      return next++;
    }

    /** The first slot no part took: where a Form's Labels start. */
    int free() {
      return next;
    }

    /** A random int in {@code [0, bound)}. */
    int below(int bound) {
      return random.nextInt(bound);
    }

    Con constant() {
      return new Con(CONSTANTS.get(below(CONSTANTS.size())));
    }

    /** A tail emitter of at most {@code depth} levels of compound parts, reading only slots in {@code scope}. */
    Part tail(int depth, List<Integer> scope) {
      if (depth > 0 && below(3) == 0) {
        return compound(depth, scope);
      }
      int pick = below(40);
      if (pick < 6) return new Id();
      if (pick < 14) return constant();
      if (pick < 24) return scope.isEmpty() ? constant() : new Read(pick(scope));
      if (pick < 38) return new Tag("t" + below(3), argument(scope));
      return pick == 38 ? new Nothing() : new Raise("e" + below(3), argument(scope));
    }

    /** A Seq, Cond, Let or Lets made of tail emitters. */
    Part compound(int depth, List<Integer> scope) {
      return switch (below(4)) {
        case 0 -> new Seq(tail(depth - 1, scope), tail(depth - 1, scope));
        case 1 -> new Cond(tail(depth - 1, scope), tail(depth - 1, scope), tail(depth - 1, scope));
        case 2 -> let(depth, scope, true);
        default -> lets(depth, scope, true);
      };
    }

    /**
     * A part of one of the kinds the property draws from (#92): a tail emitter, a Comma, a raising part, an empty part, a
     * Many, a Collect whose body breaks (out to the root Label, or to a Label inside it), or a compound of any parts.
     */
    Part part(List<Integer> scope) {
      int pick = below(40);
      if (pick < 16) return tail(2, scope);
      if (pick < 26) return new Both(any(1, scope), any(1, scope));
      if (pick < 28) return below(2) == 0 ? new Raise("e" + below(3), argument(scope)) : new Over(values(1), true);
      if (pick < 30) return below(2) == 0 ? new Nothing() : new Over(List.of(), false);
      if (pick < 34) return new Over(values(1), false);
      // A Collect whose body breaks out to the root Label after its results, so nothing after it runs.
      if (pick < 35) return new Gather(new Both(any(1, scope), new Leave(ROOT)));
      if (pick < 37) return keptBreak(1, scope);
      return switch (below(4)) {
        case 0 -> new Cond(any(1, scope), any(1, scope), any(1, scope));
        case 1 -> new Seq(any(1, scope), any(1, scope));
        case 2 -> let(2, scope, false);
        default -> lets(2, scope, false);
      };
    }

    /** A part inside another: as {@link #part}, with no break out of the root, raising and empty parts rarer. */
    Part any(int depth, List<Integer> scope) {
      if (depth <= 0) {
        int pick = below(20);
        if (pick < 16) return tail(0, scope);
        return pick < 19 ? new Over(values(1), false) : new Over(values(0), true);
      }
      int pick = below(30);
      if (pick < 15) return tail(depth, scope);
      if (pick < 20) return new Both(any(depth - 1, scope), any(depth - 1, scope));
      if (pick < 21) return below(2) == 0 ? new Raise("e" + below(3), argument(scope)) : new Over(values(1), true);
      if (pick < 22) return below(2) == 0 ? new Nothing() : new Over(List.of(), false);
      if (pick < 24) return new Over(values(1), false);
      if (pick < 25) return keptBreak(depth, scope);
      return switch (pick) {
        case 25 -> new Cond(any(depth - 1, scope), any(depth - 1, scope), any(depth - 1, scope));
        case 26 -> new Seq(any(depth - 1, scope), any(depth - 1, scope));
        case 27 -> let(depth, scope, false);
        default -> lets(depth, scope, false);
      };
    }

    /** A Collect whose body breaks to a Label inside it, so the Collect emits what came before the break. */
    private Part keptBreak(int depth, List<Integer> scope) {
      int label = fresh();
      return new Gather(new Fence(label, new Both(any(depth - 1, scope), new Both(new Leave(label), any(depth - 1, scope)))));
    }

    /**
     * A Lets of {@code min} to {@code max} values, each a {@link #part} reading the slots before it, and a body that runs
     * a {@link #part} and then gives all its slots through {@link Slots}.
     */
    Lets top(List<Integer> scope, int min, int max) {
      List<Part> values = new ArrayList<>();
      List<Integer> slots = new ArrayList<>();
      List<Integer> inner = new ArrayList<>(scope);
      for (int k = min + below(max - min + 1); k > 0; k--) {
        values.add(part(inner));
        int slot = fresh();
        slots.add(slot);
        inner.add(slot);
      }
      return new Lets(List.copyOf(values), List.copyOf(slots), new Seq(part(inner), new Slots(List.copyOf(inner))));
    }

    private Let let(int depth, List<Integer> scope, boolean tail) {
      Part value = tail ? tail(depth - 1, scope) : any(depth - 1, scope);
      int slot = fresh();
      List<Integer> inner = new ArrayList<>(scope);
      inner.add(slot);
      return new Let(value, slot, tail ? tail(depth - 1, inner) : any(depth - 1, inner));
    }

    /** A Lets of two or three values, each reading the slots before it. */
    private Lets lets(int depth, List<Integer> scope, boolean tail) {
      List<Part> values = new ArrayList<>();
      List<Integer> slots = new ArrayList<>();
      List<Integer> inner = new ArrayList<>(scope);
      for (int k = 2 + below(2); k > 0; k--) {
        values.add(tail ? tail(depth - 1, inner) : any(depth - 1, inner));
        int slot = fresh();
        slots.add(slot);
        inner.add(slot);
      }
      return new Lets(List.copyOf(values), List.copyOf(slots), tail ? tail(depth - 1, inner) : any(depth - 1, inner));
    }

    private int pick(List<Integer> scope) {
      return scope.get(below(scope.size()));
    }

    /** -1 for the input, or a slot in {@code scope}. */
    private int argument(List<Integer> scope) {
      return scope.isEmpty() || below(3) == 0 ? -1 : pick(scope);
    }

    /** {@code min} to {@code min + 2} constants. */
    private List<Object> values(int min) {
      List<Object> values = new ArrayList<>();
      for (int k = min + below(3); k > 0; k--) {
        values.add(CONSTANTS.get(below(CONSTANTS.size())));
      }
      return List.copyOf(values);
    }
  }

  /**
   * How one run ended: its results, its answer (null when it threw), what it threw ({@code ["error", value]}, {@code
   * ["termination", budget]}, or {@code "crash: "} and the exception for anything else, a failed compile included),
   * and its steps and bytes built.
   */
  record Outcome(List<Object> results, Boolean answer, Object thrown, long steps, long built) {
    boolean crashed() {
      return thrown instanceof String;
    }

    /** One of: crash, termination, error, stop, results (two or more, run out), one, none. */
    String kind() {
      if (thrown instanceof String) return "crash";
      if (thrown instanceof List<?> list) return (String) list.getFirst();
      if (!answer) return "stop";
      return results.size() >= 2 ? "results" : results.size() == 1 ? "one" : "none";
    }
  }

  /** Compiles {@code root} in {@code mode} and runs it on {@link #IN} with a step budget of {@code steps}, the sink stopping at its {@code stopAt}th result. */
  static Outcome outcome(Node root, CompileMode mode, long steps, int stopAt) {
    RunState state = new RunState();
    state.begin(steps, Long.MAX_VALUE, Long.MAX_VALUE);
    List<Object> emitted = new ArrayList<>();
    Boolean answer = null;
    Object thrown = null;
    try {
      answer =
          Compiler.compile(root, mode)
              .run(
                  state,
                  IN,
                  v -> {
                    emitted.add(v);
                    return emitted.size() < stopAt;
                  });
    } catch (FilterException e) {
      thrown = List.of("error", e.value());
    } catch (Termination t) {
      thrown = List.of("termination", String.valueOf(t.budget()));
    } catch (RuntimeException e) {
      thrown = "crash: " + e;
    }
    return new Outcome(List.copyOf(emitted), answer, thrown, state.steps(), state.built());
  }

  /** How many of {@code outcomes} are of each {@link Outcome#kind}. */
  static Map<String, Integer> kinds(List<Outcome> outcomes) {
    Map<String, Integer> kinds = new TreeMap<>();
    for (Outcome o : outcomes) {
      kinds.merge(o.kind(), 1, Integer::sum);
    }
    return kinds;
  }
}
