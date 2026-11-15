package io.github.subapavel.jqj.query.analysis;

import static io.github.subapavel.jqj.query.analysis.Cardinality.AT_MOST_ONE;
import static io.github.subapavel.jqj.query.analysis.Cardinality.MANY;
import static io.github.subapavel.jqj.query.analysis.Cardinality.NEVER;
import static io.github.subapavel.jqj.query.analysis.Cardinality.NONE;
import static io.github.subapavel.jqj.query.analysis.Cardinality.ONE;
import static io.github.subapavel.jqj.query.analysis.Cardinality.join;
import static io.github.subapavel.jqj.query.analysis.Cardinality.seq;

import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Many;
import java.util.Arrays;
import java.util.List;

/**
 * Cardinality for every node and every function (ADR-0007 and its #112 amendment, F-56): a least fixpoint per recursive
 * group, the groups callee-first and the main tree last. Each group starts from NEVER and runs its bodies until no
 * function's value changes; a function's value is its body root's. A body is read in descending freeze id, so each
 * part is done before its node, with no recursion. The rules:
 *
 * <ul>
 *   <li>Identity, Const and Var are ONE; an Intrinsic is MANY in the Many call form and AT_MOST_ONE in a one-result
 *       form; a Break is NEVER; an AltBind is MANY (a downstream error re-enters it, ADR-0038); a ParamCall is MANY; a
 *       Call takes its callee's value.
 *   <li>Pipe, Bind and Binds are {@link Cardinality#seq} of their parts in order; If is {@code seq(cond, join(then,
 *       else))}; Foreach is {@code seq(init, each)}; an Advance is its update's.
 *   <li>Collect is ONE, or NEVER when its body is; Reduce is {@code seq(init, ONE)}, or {@code seq(init, NEVER)} when
 *       its each is NEVER, since then no run of each ends and the Reduce emits nothing after its first init result.
 *   <li>Comma, Alt and Try are {@link #comma}, {@link #alt} and {@link #tryCatch}; Label is {@code join(body, NONE)}.
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
      case Intrinsic i -> i.op().form() instanceof Many ? MANY : AT_MOST_ONE;
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

  /** {@code try body catch handler}: MANY for a MANY body, which may raise after a result; {@code join(body, handler)} otherwise. */
  static Cardinality tryCatch(Cardinality body, Cardinality handler) {
    return body == MANY ? MANY : join(body, handler);
  }
}
