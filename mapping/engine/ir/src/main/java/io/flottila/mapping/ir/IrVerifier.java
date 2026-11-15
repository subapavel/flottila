package io.flottila.mapping.ir;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Re-checks IR after lowering and after every optimiser pass (ADR-0006). A refusal is a compiler
 * bug, not a user error, so it throws. A node's type is derived: a {@link SourceQueue}, a {@link
 * Lift} and an {@link AtomicToQueue} are queues, a {@link Literal} a string, a {@link VarRef} has
 * the type it stores and a {@link FunCall} its {@link FunctionRef}'s result; content gives no
 * value. It checks:
 *
 * <ul>
 *   <li>every {@link VarRef} reads a variable in scope with the type it is bound with: the root's
 *       slot number ({@code INTEGER}) in the document's content, a {@link TargetSlots}' slot number
 *       ({@code INTEGER}) and value ({@code STRING}) only in its body (#52), and a {@link Lift}'s
 *       slot variables ({@code STRING}) only in its body, which sees nothing else;
 *   <li>a {@link SourceQueue}'s context is a proper ancestor of its field, and an attribute's queue
 *       is not a structure field's (#102);
 *   <li>a {@link TargetSlots}' {@code min} is at most its {@code max}, its queue is a queue and its
 *       parent slot an {@code INTEGER};
 *   <li>a {@link TargetRepeat} has at least one copy and an {@code INTEGER} parent slot, and binds
 *       its slot number ({@code INTEGER}) only in its body (#63); its value, when present, is in
 *       the parent's scope, so it cannot read its own slot number or value variable, and is a
 *       {@code STRING} (#83) or a {@code QUEUE}, a generating block, which alone has a value
 *       variable ({@code STRING}), bound only in its body (#217);
 *   <li>a {@link FunCall} has one argument per parameter type of its {@link FunctionRef}, each of
 *       that type; a context function's (one whose result is a queue) atomic argument is a param,
 *       and a param is a {@link Literal} (ADR-0025);
 *   <li>an {@link AtomicToQueue}'s value is a {@code STRING} (#83);
 *   <li>a {@link Lift} has at least one input, each a queue, one slot variable per input, all
 *       distinct and none named {@code in}, which the export binds (ADR-0025), and a {@code STRING}
 *       body (ADR-0006 since #51);
 *   <li>a {@link TextConstructor}'s value is a {@code STRING}, and the document's content, an
 *       {@link ElementConstructor}'s content and the body of a {@link TargetSlots} (#51) and of a
 *       {@link TargetRepeat} (#63) are content;
 *   <li>an {@link AttributeConstructor} is the body of an attribute item, a {@link TargetSlots}
 *       with {@code max <= 1} or a 1-copy {@link TargetRepeat}, and stands nowhere else; its value
 *       is a {@code STRING}; in the document's content and in each {@link ElementConstructor}'s,
 *       the attribute items come before all other content, each of a name of its own (ADR-0006
 *       since #102).
 * </ul>
 */
public final class IrVerifier {

  private static final QueueType QUEUE = new QueueType();

  /** The inline function of an exported {@code Lift} takes its slot's values as {@code $in}. */
  private static final String RESERVED_SLOT_VAR = "in";

  private IrVerifier() {}

  /**
   * Checks {@code document}.
   *
   * @throws IllegalArgumentException naming the first defect found and its origin
   */
  public static void verify(TargetDocument document) {
    contents(document.content(), Map.of(document.rootSlot(), AtomicType.INTEGER));
  }

  /**
   * Checks {@code expr}; the type of the value it gives, or empty for content, which gives none.
   */
  private static Optional<IrType> check(Expr expr, Map<String, AtomicType> scope) {
    return switch (expr) {
      case SourceQueue q -> {
        if (q.contextDepth() < 1 || q.contextDepth() >= q.field().size()) {
          throw refused(q, "context depth " + q.contextDepth() + " is no proper ancestor");
        }
        if (q.attribute() && q.structureField()) {
          throw refused(q, "an attribute is no structure field");
        }
        yield Optional.of(QUEUE);
      }
      case VarRef v -> {
        if (scope.get(v.name()) != v.type()) {
          throw refused(v, v.name() + " is not in scope as " + v.type());
        }
        yield Optional.of(v.type());
      }
      case Literal _ -> Optional.of(AtomicType.STRING);
      case FunCall c -> {
        call(c, scope);
        yield Optional.of(c.function().resultType());
      }
      case Lift l -> {
        lift(l, scope);
        yield Optional.of(QUEUE);
      }
      case TargetSlots s -> {
        content(s.body(), body(s, scope));
        yield Optional.empty();
      }
      case TargetRepeat r -> {
        content(r.body(), body(r, scope));
        yield Optional.empty();
      }
      case ElementConstructor e -> {
        contents(e.content(), scope);
        yield Optional.empty();
      }
      case AttributeConstructor a ->
          throw refused(a, "an attribute stands only as the body of an attribute item");
      case TextConstructor t -> {
        value(t, t.value(), AtomicType.STRING, scope, "its value");
        yield Optional.empty();
      }
      case AtomicToQueue a -> {
        value(a, a.value(), AtomicType.STRING, scope, "its value");
        yield Optional.of(QUEUE);
      }
    };
  }

  /**
   * Checks {@code s}'s bounds, queue and parent slot in {@code scope}; the scope of its body, which
   * adds its slot number and its value.
   */
  private static Map<String, AtomicType> body(TargetSlots s, Map<String, AtomicType> scope) {
    if (s.min() > s.max()) {
      throw refused(s, "min " + s.min() + " is above max " + s.max());
    }
    value(s, s.queue(), QUEUE, scope, "its queue");
    value(s, s.parentSlot(), AtomicType.INTEGER, scope, "its parent slot");
    Map<String, AtomicType> body = new HashMap<>(scope);
    body.put(s.slotVar(), AtomicType.INTEGER);
    body.put(s.valueVar(), AtomicType.STRING);
    return body;
  }

  /**
   * Checks {@code r}'s copies, parent slot, value and value variable in {@code scope}; the scope of
   * its body, which adds its slot number and, for a generating block, its value variable.
   */
  private static Map<String, AtomicType> body(TargetRepeat r, Map<String, AtomicType> scope) {
    if (r.copies() < 1) {
      throw refused(r, "it has " + r.copies() + " copies, not at least 1");
    }
    value(r, r.parentSlot(), AtomicType.INTEGER, scope, "its parent slot");
    Map<String, AtomicType> body = new HashMap<>(scope);
    body.put(r.slotVar(), AtomicType.INTEGER);
    if (r.value().isEmpty()) {
      if (r.valueVar().isPresent()) {
        throw refused(r, "it binds the value variable " + r.valueVar().get() + " without a value");
      }
      return body;
    }
    Expr value = r.value().get();
    Optional<IrType> type = check(value, scope);
    if (type.equals(Optional.of(QUEUE))) {
      // a generating block (#217): its first slot is bound in the body only
      String valueVar =
          r.valueVar()
              .orElseThrow(
                  () ->
                      refused(
                          r,
                          "its value is no STRING but a QUEUE, which needs a value variable: "
                              + value));
      body.put(valueVar, AtomicType.STRING);
    } else if (!type.equals(Optional.of(AtomicType.STRING))) {
      throw refused(r, "its value is no STRING and no QUEUE: " + value);
    } else if (r.valueVar().isPresent()) {
      throw refused(r, "its value is a STRING, which binds no value variable");
    }
    return body;
  }

  /**
   * Checks the document's or an element's {@code content}: its attribute items, each of a name of
   * its own, and then the rest, in which no attribute item stands (ADR-0006 since #102).
   */
  private static void contents(List<Expr> content, Map<String, AtomicType> scope) {
    Set<Name> attributes = new HashSet<>();
    boolean afterAttributes = false;
    for (Expr expr : content) {
      Optional<Name> attribute = attributeItem(expr, scope);
      if (attribute.isEmpty()) {
        content(expr, scope);
        afterAttributes = true;
      } else if (afterAttributes) {
        throw refused(expr, "an attribute item stands after other content");
      } else if (!attributes.add(attribute.get())) {
        throw refused(expr, "its element carries the attribute " + attribute.get() + " twice");
      }
    }
  }

  /**
   * The attribute's name if {@code expr} is an attribute item, a {@link TargetSlots} or {@link
   * TargetRepeat} whose body is an {@link AttributeConstructor}, once the item is checked: at most
   * one occurrence, {@code max <= 1} or one copy, and the attribute's value a {@code STRING} in the
   * body's scope. Empty for any other {@code expr}, which is not checked here.
   */
  private static Optional<Name> attributeItem(Expr expr, Map<String, AtomicType> scope) {
    AttributeConstructor attribute;
    Map<String, AtomicType> body;
    if (expr instanceof TargetSlots s && s.body() instanceof AttributeConstructor a) {
      if (s.max() > 1) {
        throw refused(s, "an attribute item has max " + s.max() + ", not at most 1");
      }
      attribute = a;
      body = body(s, scope);
    } else if (expr instanceof TargetRepeat r && r.body() instanceof AttributeConstructor a) {
      if (r.copies() > 1) {
        throw refused(r, "an attribute item has " + r.copies() + " copies, not 1");
      }
      attribute = a;
      body = body(r, scope);
    } else {
      return Optional.empty();
    }
    // one check for both kinds of item
    value(attribute, attribute.value(), AtomicType.STRING, body, "its value");
    return Optional.of(attribute.name());
  }

  /** Checks {@code expr}, which stands where content stands. */
  private static void content(Expr expr, Map<String, AtomicType> scope) {
    if (check(expr, scope).isPresent()) {
      throw refused(expr, "it is a value where content stands");
    }
  }

  /**
   * Checks {@code expr}, which stands where {@code at} takes a value of {@code type}, named {@code
   * what}.
   */
  private static void value(
      Expr at, Expr expr, IrType type, Map<String, AtomicType> scope, String what) {
    if (!check(expr, scope).equals(Optional.of(type))) {
      throw refused(at, what + " is no " + type + ": " + expr);
    }
  }

  private static void call(FunCall c, Map<String, AtomicType> scope) {
    FunctionRef function = c.function();
    List<IrType> types = function.paramTypes();
    if (c.args().size() != types.size()) {
      throw refused(
          c, function.name() + " takes " + types.size() + " args, not " + c.args().size());
    }
    boolean contextFunction = function.resultType() instanceof QueueType;
    for (int i = 0; i < types.size(); i++) {
      Expr arg = c.args().get(i);
      IrType type = types.get(i);
      value(c, arg, type, scope, function.name() + "'s arg");
      if (contextFunction && type instanceof AtomicType && !(arg instanceof Literal)) {
        throw refused(c, function.name() + " takes a param as a Literal, not " + arg);
      }
    }
  }

  /**
   * Checks {@code l}'s inputs in {@code scope}, and its body in a scope of its slot variables only.
   */
  private static void lift(Lift l, Map<String, AtomicType> scope) {
    List<Expr> queues = l.queues();
    if (queues.isEmpty()) {
      throw refused(l, "it has no input");
    }
    if (l.slotVars().size() != queues.size()) {
      throw refused(
          l, "it has " + queues.size() + " inputs and " + l.slotVars().size() + " slot variables");
    }
    Map<String, AtomicType> slots = new HashMap<>();
    for (int k = 0; k < queues.size(); k++) {
      value(l, queues.get(k), QUEUE, scope, "its input");
      String slotVar = l.slotVars().get(k);
      if (slotVar.equals(RESERVED_SLOT_VAR)) {
        throw refused(l, "its slot variable in is reserved: the export binds $in (ADR-0025)");
      }
      if (slots.put(slotVar, AtomicType.STRING) != null) {
        throw refused(l, "it binds the slot variable " + slotVar + " twice");
      }
    }
    value(l, l.body(), AtomicType.STRING, slots, "its body");
  }

  private static IllegalArgumentException refused(Expr expr, String why) {
    return new IllegalArgumentException(
        expr.getClass().getSimpleName() + " of " + expr.origin().value() + ": " + why);
  }
}
