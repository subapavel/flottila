package dev.rail8.jq.query.analysis;

import static dev.rail8.jq.query.analysis.Cardinality.AT_MOST_ONE;
import static dev.rail8.jq.query.analysis.Cardinality.MANY;
import static dev.rail8.jq.query.analysis.Cardinality.NEVER;
import static dev.rail8.jq.query.analysis.Cardinality.NONE;
import static dev.rail8.jq.query.analysis.Cardinality.ONE;
import static dev.rail8.jq.query.analysis.Cardinality.join;
import static dev.rail8.jq.query.analysis.Cardinality.seq;

import dev.rail8.jq.query.ir.Advance;
import dev.rail8.jq.query.ir.Alt;
import dev.rail8.jq.query.ir.AltBind;
import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Binds;
import dev.rail8.jq.query.ir.Break;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Collect;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Foreach;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.If;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Label;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.ParamCall;
import dev.rail8.jq.query.ir.PathOf;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Reduce;
import dev.rail8.jq.query.ir.Try;
import dev.rail8.jq.query.ir.Update;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.Many;
import java.util.Arrays;
import java.util.List;

/**
 * Cardinality for every node and every function: a least fixpoint per recursive group, the groups callee-first and the
 * main tree last. Each group starts from NEVER and runs its bodies until no function's value changes; a function's
 * value is its body root's. A body is read in descending freeze id, so each part is done before its node, with no
 * recursion. The rules:
 *
 * <ul>
 *   <li>Identity, Const and Var are ONE; an Intrinsic is MANY in the Many call form and AT_MOST_ONE in a one-result
 *       form; a Break is NEVER; an AltBind is MANY (a downstream error re-enters it); a ParamCall is MANY; a
 *       Call takes its callee's value.
 *   <li>Pipe, Bind and Binds are {@link Cardinality#seq} of their parts in order; If is {@code seq(cond, join(then,
 *       else))}; Foreach is {@code seq(init, each)}; an Advance is its update's.
 *   <li>Collect is ONE, or NEVER when its body is; Reduce is {@code seq(init, ONE)}, or {@code seq(init, NEVER)} when
 *       its each is NEVER, since then no run of each ends and the Reduce emits nothing after its first init result.
 *   <li>Comma, Alt and Try are {@link #comma}, {@link #alt} and {@link #tryCatch}; Label is {@code join(body, NONE)}.
 *   <li>A PathOf is its body's: one path per result. An Update is ONE, or NEVER when its lhs is: it emits once, after
 *       its lhs ran out. Its rhs runs only for an lhs result, of which there may be none, so it does not count.
 * </ul>
 */
final class CardinalityAnalysis {
  private final Numbering numbering;

  /** The value of each function so far, by its place: NEVER until its group runs. */
  private final Cardinality[] functions;

  private CardinalityAnalysis(Numbering numbering) {
    this.numbering = numbering;
    this.functions = new Cardinality[numbering.functions()];
    Arrays.fill(functions, NEVER);
  }

  /**
   * The cardinality of each node of the program {@code numbering} numbers, by body, the main tree's first and then each
   * function's by place, and by freeze id; NEVER stored as NONE.
   */
  static Cardinality[][] of(Numbering numbering, CallGraph graph) {
    CardinalityAnalysis analysis = new CardinalityAnalysis(numbering);
    Cardinality[][] cards = new Cardinality[numbering.functions() + 1][];
    for (int g = 0; g < graph.groups(); g++) {
      boolean changed;
      do {
        changed = false;
        for (int place : graph.members(g)) {
          cards[place + 1] = analysis.body(place);
          if (cards[place + 1][0] != analysis.functions[place]) {
            analysis.functions[place] = cards[place + 1][0];
            changed = true;
          }
        }
      } while (changed);
    }
    cards[0] = analysis.body(-1);
    for (Cardinality[] body : cards) {
      for (int id = 0; id < body.length; id++) {
        if (body[id] == NEVER) {
          body[id] = NONE;
        }
      }
    }
    return cards;
  }

  /** The cardinality of each node of the body {@code body}, -1 for the main tree, by freeze id. */
  private Cardinality[] body(int body) {
    List<Node> nodes = numbering.nodes(body);
    Cardinality[] card = new Cardinality[nodes.size()];
    for (int id = card.length - 1; id >= 0; id--) {
      card[id] = rule(nodes.get(id), card);
    }
    return card;
  }

  /** The cardinality of {@code node}, its parts' in {@code card} by freeze id. */
  private Cardinality rule(Node node, Cardinality[] card) {
    // One arm per kind: javac compiles a case of two patterns to a second dispatch no node can take.
    return switch (node) {
      case Identity _ -> ONE;
      case Const _ -> ONE;
      case Var _ -> ONE;
      case Intrinsic i -> form(i.op().form());
      case Break _ -> NEVER;
      case AltBind _ -> MANY;
      case ParamCall _ -> MANY;
      case Call c -> functions[c.fn()];
      case Pipe p -> seq(card[id(p.left())], card[id(p.right())]);
      case Bind b -> seq(card[id(b.value())], card[id(b.body())]);
      case Binds b -> {
        Cardinality chain = ONE;
        for (Node value : b.values()) {
          chain = seq(chain, card[id(value)]);
        }
        yield seq(chain, card[id(b.body())]);
      }
      case If i -> seq(card[id(i.cond())], join(card[id(i.then())], card[id(i.otherwise())]));
      case Foreach f -> seq(card[id(f.init())], card[id(f.each())]);
      case Advance a -> card[id(a.update())];
      case Collect c -> card[id(c.body())] == NEVER ? NEVER : ONE;
      case Reduce r -> seq(card[id(r.init())], card[id(r.each())] == NEVER ? NEVER : ONE);
      case Comma c -> comma(card[id(c.left())], card[id(c.right())]);
      case Alt a -> alt(card[id(a.left())], card[id(a.right())]);
      case Try t -> tryCatch(card[id(t.body())], card[id(t.handler())]);
      case Label l -> join(card[id(l.body())], NONE);
      case PathOf p -> card[id(p.body())];
      case Update u -> card[id(u.lhs())] == NEVER ? NEVER : ONE;
    };
  }

  /** An Intrinsic's value by its call form: AT_MOST_ONE for each one-result form, MANY for Many. */
  private static Cardinality form(CallForm form) {
    // One arm per form, as for the node kinds above.
    return switch (form) {
      case Fn0 _ -> AT_MOST_ONE;
      case Fn1 _ -> AT_MOST_ONE;
      case Fn2 _ -> AT_MOST_ONE;
      case FnN _ -> AT_MOST_ONE;
      case Many _ -> MANY;
    };
  }

  private int id(Node part) {
    return numbering.id(part);
  }

  /** {@code left, right}: NEVER after a NEVER left side, the right side's after a NONE one, and MANY after a result. */
  static Cardinality comma(Cardinality left, Cardinality right) {
    // Tests, not a switch over the enum: its default arm is a branch no value takes.
    if (left == NEVER) {
      return NEVER;
    }
    return left == NONE ? right : MANY;
  }

  /**
   * {@code left // right}: NEVER after a NEVER left side, the right side's after a NONE one, {@code join(ONE, right)}
   * after a ONE or AT_MOST_ONE one (its result passes or the right side runs), and MANY after a MANY one.
   */
  static Cardinality alt(Cardinality left, Cardinality right) {
    if (left == NEVER || left == MANY) {
      return left;
    }
    return left == NONE ? right : join(ONE, right);
  }

  /**
   * {@code try body catch handler}: {@code join(body, handler)}. So a MANY body, which may raise after a result, gives
   * MANY, the top, as the rule says, with no test of its own.
   */
  static Cardinality tryCatch(Cardinality body, Cardinality handler) {
    return join(body, handler);
  }
}
