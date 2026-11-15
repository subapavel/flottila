package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AtomicToQueue;
import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AttributeConstructor;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.Lift;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetRepeat;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.xdm.Lifting;
import io.flottila.mapping.xdm.QueueBlock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 0, the closure-compiled interpreter (ADR-0021). Compiling turns the IR once into a tree of
 * executable nodes over a frame: variables become slots, source queues become indexes into the
 * projection plan, a context or single-value function is bound by its name once per call site
 * (ADR-0025), and nothing is looked up by name while a mapping runs. A traced compile reports every
 * source queue, context function call and {@code Lift} it evaluates to the run's tracer, at its
 * box, every single-value call but a {@code Lift}'s body as the box's single value (#83), each
 * {@code TargetSlots}' occurrences per parent slot, at its target field mapping, and each {@code
 * TargetRepeat}'s copies per parent slot, at its origin (#83); an untraced compile has no hook at
 * all.
 */
public final class Interpreter {

  private final boolean traced;

  /** The projection plan: each distinct (field, context) pair and its queue index. */
  private final Map<Projection.Queue, Integer> plan = new LinkedHashMap<>();

  /** The number of {@code INTEGER} frame slots allocated so far. */
  private int integerSlotCount;

  /** The number of {@code STRING} frame slots allocated so far. */
  private int stringSlotCount;

  /**
   * The number of {@code TargetSlots} and generating blocks so far, each with a frame slot for its
   * block.
   */
  private int targetQueueCount;

  private Interpreter(boolean traced) {
    this.traced = traced;
  }

  /**
   * Compiles {@code document}, which the IR verifier has accepted.
   *
   * @param traced whether the nodes report to the run's tracer
   */
  public static ExecutableMapping compile(TargetDocument document, boolean traced) {
    Interpreter compiler = new Interpreter(traced);
    int rootSlot = compiler.integerSlotCount++;
    Map<String, Integer> scope = Map.of(document.rootSlot(), rootSlot);
    Name root = document.root();
    Node.OfContent element =
        new Node.Element(
            document.prefix(),
            root.namespace(),
            root.localName(),
            compiler.contents(document.content(), scope));
    return new ExecutableMapping(
        Projection.of(document.input(), List.copyOf(compiler.plan.keySet())),
        rootSlot,
        element,
        compiler.integerSlotCount,
        compiler.stringSlotCount,
        compiler.targetQueueCount);
  }

  /**
   * The node of {@code expr}. Its kind follows the type of {@code expr}, which the verifier checked
   * against the place {@code expr} stands in, so the callers' casts hold.
   *
   * @param scope the slot of each variable in scope
   */
  private Node compile(Expr expr, Map<String, Integer> scope) {
    return switch (expr) {
      case SourceQueue q -> queue(q);
      case VarRef v -> variable(v, scope.get(v.name()));
      case Literal l -> new Node.Literal(l.value());
      case FunCall c ->
          switch (c.function().resultType()) {
            case QueueType _ -> call(c, scope);
            case AtomicType _ -> tracedValue(c.origin(), singleValueCall(c, scope));
          };
      case Lift l -> lift(l, scope);
      case TargetSlots s -> slots(s, scope);
      case TargetRepeat r -> repeat(r, scope);
      case ElementConstructor e ->
          new Node.Element(
              e.prefix(), e.name().namespace(), e.name().localName(), contents(e.content(), scope));
      case AttributeConstructor a ->
          new Node.Attribute(
              a.prefix(),
              a.name().namespace(),
              a.name().localName(),
              (Node.OfString) compile(a.value(), scope));
      case TextConstructor t -> text((Node.OfString) compile(t.value(), scope));
      case AtomicToQueue a -> atomicToQueue((Node.OfString) compile(a.value(), scope));
    };
  }

  private Node.OfContent[] contents(List<Expr> content, Map<String, Integer> scope) {
    List<Node.OfContent> nodes = new ArrayList<>();
    for (Expr expr : content) {
      nodes.add((Node.OfContent) compile(expr, scope));
    }
    return nodes.toArray(Node.OfContent[]::new);
  }

  private Node.OfQueue queue(SourceQueue q) {
    int index =
        plan.computeIfAbsent(
            new Projection.Queue(
                q.field(), q.contextDepth(), q.structureField(), q.attribute(), q.arrays()),
            k -> plan.size());
    return traced(q.origin(), frame -> frame.queues[index]);
  }

  /**
   * A call of a context function: its inputs' nodes and its params' strings, bound by the
   * function's name. A {@code Literal} argument is a param, which the IR verifier allows only as a
   * {@code Literal}; any other argument is an input.
   */
  private Node.OfQueue call(FunCall c, Map<String, Integer> scope) {
    List<Node.OfQueue> inputs = new ArrayList<>();
    List<String> params = new ArrayList<>();
    for (Expr arg : c.args()) {
      if (arg instanceof Literal param) {
        params.add(param.value());
      } else {
        inputs.add((Node.OfQueue) compile(arg, scope));
      }
    }
    return traced(c.origin(), QueueLibrary.bind(c.function().name(), c.origin(), inputs, params));
  }

  /**
   * A call of a single-value function, its args' string nodes bound by the function's name. The
   * node is not traced: a call that is a box's single value is traced around it, at its box, and a
   * call that is a {@code Lift}'s body is the {@code Lift}'s, which traces its queue (ADR-0006).
   */
  private Node.OfString singleValueCall(FunCall c, Map<String, Integer> scope) {
    List<Node.OfString> args = new ArrayList<>();
    for (Expr arg : c.args()) {
      args.add((Node.OfString) compile(arg, scope));
    }
    return SingleValueLibrary.bind(c.function().name(), c.origin(), args);
  }

  /**
   * A {@code Lift} (ADR-0006): its inputs' nodes in {@code scope}, and its body's node in a scope
   * of its slot variables only, each a string frame slot of its own. Per slot, the slot's values go
   * into those frame slots and the body reads them there.
   */
  private Node.OfQueue lift(Lift l, Map<String, Integer> scope) {
    Node.OfQueue[] inputs = new Node.OfQueue[l.queues().size()];
    int[] slotVars = new int[inputs.length];
    Map<String, Integer> slots = new HashMap<>();
    for (int k = 0; k < inputs.length; k++) {
      inputs[k] = (Node.OfQueue) compile(l.queues().get(k), scope);
      slotVars[k] = stringSlotCount++;
      slots.put(l.slotVars().get(k), slotVars[k]);
    }
    // the body's call is the Lift's own box, which the Lift traces as a queue
    Node.OfString body =
        l.body() instanceof FunCall c
            ? singleValueCall(c, slots)
            : (Node.OfString) compile(l.body(), slots);
    return traced(
        l.origin(),
        frame -> {
          QueueBlock[] blocks = new QueueBlock[inputs.length];
          for (int k = 0; k < blocks.length; k++) {
            blocks[k] = inputs[k].eval(frame);
          }
          return Lifting.lift(
              Arrays.asList(blocks),
              values -> {
                for (int k = 0; k < slotVars.length; k++) {
                  frame.strings[slotVars[k]] = values[k];
                }
                return body.eval(frame);
              });
        });
  }

  /** {@code node}, which reports each queue it gives at {@code box} when the compile is traced. */
  private Node.OfQueue traced(AuthoringId box, Node.OfQueue node) {
    if (!traced) {
      return node;
    }
    return frame -> {
      QueueBlock block = node.eval(frame);
      frame.tracer.value(box, block);
      return block;
    };
  }

  /**
   * {@code node}, a box's single value, which reports each value it gives at {@code box} when the
   * compile is traced (ADR-0015 {@code {atomic}}, #83).
   */
  private Node.OfString tracedValue(AuthoringId box, Node.OfString node) {
    if (!traced) {
      return node;
    }
    return frame -> {
      String value = node.eval(frame);
      frame.tracer.atomic(box, value);
      return value;
    };
  }

  /**
   * An {@code AtomicToQueue} (ADR-0006 since #83): one context holding its value. It is not traced;
   * its value is, at its own box.
   */
  private static Node.OfQueue atomicToQueue(Node.OfString value) {
    return frame -> QueueBlock.of(new String[] {value.eval(frame)}, new int[] {0, 1});
  }

  private static Node variable(VarRef v, int slot) {
    return switch (v.type()) {
      case INTEGER -> (Node.OfInteger) frame -> frame.integers[slot];
      case STRING -> (Node.OfString) frame -> frame.strings[slot];
    };
  }

  /**
   * A {@code TargetSlots} (ADR-0006 since #52): its queue and parent slot in {@code scope}, and its
   * body in {@code scope} with its slot-number variable, an {@code INTEGER} frame slot of its own,
   * and its value variable, a {@code STRING} one. A traced compile reports its occurrences per
   * parent slot (since #53).
   */
  private Node.OfContent slots(TargetSlots s, Map<String, Integer> scope) {
    Node.OfQueue queue = (Node.OfQueue) compile(s.queue(), scope);
    Node.OfInteger parentSlot = (Node.OfInteger) compile(s.parentSlot(), scope);
    int queueIndex = targetQueueCount++;
    int slotNumberIndex = integerSlotCount++;
    int valueIndex = stringSlotCount++;
    Map<String, Integer> body = new HashMap<>(scope);
    body.put(s.slotVar(), slotNumberIndex);
    body.put(s.valueVar(), valueIndex);
    Node.Slots slots =
        new Node.Slots(
            queue,
            queueIndex,
            parentSlot,
            s.min(),
            s.max(),
            slotNumberIndex,
            valueIndex,
            (Node.OfContent) compile(s.body(), body),
            s.origin());
    return traced ? slots.traced() : slots;
  }

  /**
   * A {@code TargetRepeat} (ADR-0006; #63): its parent slot in {@code scope}, and its body in
   * {@code scope} with its slot-number variable, an {@code INTEGER} frame slot of its own. A traced
   * compile reports its copies per parent slot (#83). A generating block, its value when it has a
   * value variable, is a queue in {@code scope} with a frame slot for its block, and its value
   * variable a {@code STRING} frame slot of its own in the body's scope (#217). A generating
   * structure field's single value, in {@code scope}, is evaluated by each copy before its body and
   * dropped, in every compile, so its boxes are traced and its data error is raised (ADR-0006 as
   * amended by #83); without a value the copy is its body alone.
   */
  private Node.OfContent repeat(TargetRepeat r, Map<String, Integer> scope) {
    Node.OfInteger parentSlot = (Node.OfInteger) compile(r.parentSlot(), scope);
    int slotNumberIndex = integerSlotCount++;
    Map<String, Integer> bodyScope = new HashMap<>(scope);
    bodyScope.put(r.slotVar(), slotNumberIndex);
    Node.Repeat repeat;
    if (r.valueVar().isPresent()) {
      Node.OfQueue block = (Node.OfQueue) compile(r.value().orElseThrow(), scope);
      int queueIndex = targetQueueCount++;
      int valueIndex = stringSlotCount++;
      bodyScope.put(r.valueVar().get(), valueIndex);
      Node.OfContent body = (Node.OfContent) compile(r.body(), bodyScope);
      repeat =
          new Node.Repeat(
              r.copies(),
              parentSlot,
              slotNumberIndex,
              block,
              queueIndex,
              valueIndex,
              body,
              r.origin());
    } else {
      Node.OfContent body = (Node.OfContent) compile(r.body(), bodyScope);
      if (r.value().isPresent()) {
        body = evaluated((Node.OfString) compile(r.value().get(), scope), body);
      }
      repeat = new Node.Repeat(r.copies(), parentSlot, slotNumberIndex, body, r.origin());
    }
    return traced ? repeat.traced() : repeat;
  }

  /** {@code body}, after {@code value} is evaluated and its result dropped. */
  private static Node.OfContent evaluated(Node.OfString value, Node.OfContent body) {
    return frame -> {
      value.eval(frame);
      body.write(frame);
    };
  }

  private static Node.OfContent text(Node.OfString value) {
    return frame -> frame.out.text(value.eval(frame));
  }
}
