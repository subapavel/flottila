package io.flottila.mapping.ir;

import static io.flottila.mapping.ir.FieldLinkIr.A1;
import static io.flottila.mapping.ir.FieldLinkIr.A2;
import static io.flottila.mapping.ir.FieldLinkIr.NOTE;
import static io.flottila.mapping.ir.FieldLinkIr.ORDER;
import static io.flottila.mapping.ir.FieldLinkIr.ORDER_ID;
import static io.flottila.mapping.ir.FieldLinkIr.T1;
import static io.flottila.mapping.ir.FieldLinkIr.T2;
import static io.flottila.mapping.ir.FieldLinkIr.document;
import static io.flottila.mapping.ir.FieldLinkIr.fieldLink;
import static io.flottila.mapping.ir.FieldLinkIr.orderIdLink;
import static io.flottila.mapping.ir.FieldLinkIr.queue;
import static io.flottila.mapping.ir.FieldLinkIr.rootSlot;
import static io.flottila.mapping.ir.FieldLinkIr.slots;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The IR verifier (ADR-0006): it re-checks lowered IR, and a refusal is a compiler bug, so it
 * throws. Every refused document differs from an accepted one of {@link
 * #acceptsTheLoweredFieldLink} in the one defect it names. The exception type is not fixed by #26;
 * it is an illegal argument or an illegal state, never an incidental failure.
 */
class IrVerifierTest {

  private static final long UNBOUNDED = Long.MAX_VALUE;

  @Test
  void acceptsTheLoweredFieldLink() {
    assertThatCode(() -> IrVerifier.verify(fieldLink())).doesNotThrowAnyException();
    // the root alone, as an empty source gives it
    assertThatCode(() -> IrVerifier.verify(document())).doesNotThrowAnyException();
    // two siblings, each with its own value in scope; min 0 and an unbounded max
    assertThatCode(
            () ->
                IrVerifier.verify(
                    document(
                        orderIdLink(),
                        slots(
                            NOTE,
                            queue(NOTE, 1, A2),
                            rootSlot(),
                            0,
                            UNBOUNDED,
                            "w",
                            new VarRef("w", AtomicType.STRING, T2),
                            T2))))
        .doesNotThrowAnyException();
  }

  static Stream<Arguments> badVarRefs() {
    return Stream.of(
        Arguments.of(
            "a value that is never bound",
            document(
                slots(
                    ORDER_ID,
                    queue(ORDER_ID, 1, A1),
                    rootSlot(),
                    1,
                    1,
                    "v",
                    new VarRef("w", AtomicType.STRING, T1),
                    T1))),
        Arguments.of(
            "the slot value read as an integer",
            document(
                slots(
                    ORDER_ID,
                    queue(ORDER_ID, 1, A1),
                    rootSlot(),
                    1,
                    1,
                    "v",
                    new VarRef("v", AtomicType.INTEGER, T1),
                    T1))),
        Arguments.of(
            "the root slot number read as a string",
            document(
                slots(
                    ORDER_ID,
                    queue(ORDER_ID, 1, A1),
                    new VarRef(FieldLinkIr.ROOT_SLOT, AtomicType.STRING, T1),
                    1,
                    1,
                    "v",
                    new VarRef("v", AtomicType.STRING, T1),
                    T1))),
        Arguments.of(
            "a parent slot that is never bound",
            document(
                slots(
                    ORDER_ID,
                    queue(ORDER_ID, 1, A1),
                    new VarRef("parent", AtomicType.INTEGER, T1),
                    1,
                    1,
                    "v",
                    new VarRef("v", AtomicType.STRING, T1),
                    T1))),
        Arguments.of(
            "the slot value used outside its body, as its own parent slot",
            document(
                slots(
                    ORDER_ID,
                    queue(ORDER_ID, 1, A1),
                    new VarRef("v", AtomicType.INTEGER, T1),
                    1,
                    1,
                    "v",
                    new VarRef("v", AtomicType.STRING, T1),
                    T1))),
        Arguments.of(
            "a sibling's slot value, out of its scope",
            document(
                orderIdLink(),
                slots(
                    NOTE,
                    queue(NOTE, 1, A2),
                    rootSlot(),
                    0,
                    UNBOUNDED,
                    "w",
                    new VarRef("v", AtomicType.STRING, T2),
                    T2))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badVarRefs")
  void refusesAnUnboundOrMistypedVarRef(String why, TargetDocument document) {
    assertRefused(document);
  }

  /**
   * {@code OrderId} has two steps; only depth 1, its parent {@code Order}, is a proper ancestor.
   */
  @ParameterizedTest(name = "context depth {0}")
  @ValueSource(ints = {-1, 0, 2, 3})
  void refusesAContextThatIsNotAProperAncestor(int contextDepth) {
    assertRefused(
        document(
            slots(
                ORDER_ID,
                queue(ORDER_ID, contextDepth, A1),
                rootSlot(),
                1,
                1,
                "v",
                new VarRef("v", AtomicType.STRING, T1),
                T1)));
  }

  @ParameterizedTest(name = "min {0} max {1}")
  @MethodSource("minAboveMax")
  void refusesMinAboveMax(long min, long max) {
    assertRefused(
        document(
            slots(
                ORDER_ID,
                queue(ORDER_ID, 1, A1),
                rootSlot(),
                min,
                max,
                "v",
                new VarRef("v", AtomicType.STRING, T1),
                T1)));
  }

  static Stream<Arguments> minAboveMax() {
    return Stream.of(Arguments.of(2L, 1L), Arguments.of(1L, 0L));
  }

  // --- function calls (#50, ADR-0025): a FunCall's args match its FunctionRef in number and type,
  // and a param, an atomic argument of a context function, is a Literal. Lowering fills every
  // default, so the IR is total. A node's type is derived: a call's is its FunctionRef's result.

  private static final QueueType QUEUE = new QueueType();
  private static final AuthoringId R1 = new AuthoringId("r1");

  /** removeContexts: one queue input, no param. */
  private static final FunctionRef REMOVE_CONTEXTS =
      new FunctionRef("removeContexts", List.of(QUEUE), QUEUE);

  /** A context function with one queue input and one string param, as splitByValue's mode. */
  private static final FunctionRef SPLIT_BY_VALUE =
      new FunctionRef("splitByValue", List.of(QUEUE, AtomicType.STRING), QUEUE);

  /** A single-value function of one string, as trim: an atomic call. */
  private static final FunctionRef TRIM =
      new FunctionRef("trim", List.of(AtomicType.STRING), AtomicType.STRING);

  private static FunCall call(FunctionRef function, Expr... args) {
    return new FunCall(function, List.of(args), R1);
  }

  private static Literal literal(String value) {
    return new Literal(value, R1);
  }

  private static SourceQueue orderIds() {
    return queue(ORDER_ID, 1, A1);
  }

  /** The field link with {@code queue} as its target field's queue. */
  private static TargetDocument over(Expr queue) {
    return document(
        slots(ORDER_ID, queue, rootSlot(), 1, 1, "v", new VarRef("v", AtomicType.STRING, T1), T1));
  }

  /**
   * {@code Item} per slot of its queue, its value bound to {@code item}, and inside it {@code Name}
   * per slot of {@code splitByValue(Name, param)}: there {@code item} is a string in scope.
   */
  private static TargetDocument nested(Expr param) {
    Name item = new Name("", "Item");
    Name name = new Name("", "Name");
    TargetSlots names =
        slots(
            name,
            call(SPLIT_BY_VALUE, new SourceQueue(List.of(ORDER, item, name), 2, A2), param),
            rootSlot(),
            0,
            UNBOUNDED,
            "name",
            new VarRef("name", AtomicType.STRING, T2),
            T2);
    return document(
        new TargetSlots(
            queue(item, 1, A1),
            rootSlot(),
            0,
            UNBOUNDED,
            "slot",
            "item",
            new ElementConstructor("", item, List.of(names), T1),
            T1));
  }

  static void assertAccepted(TargetDocument document) {
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  static Stream<Arguments> wrongArities() {
    return Stream.of(
        Arguments.of("removeContexts without its input", over(call(REMOVE_CONTEXTS))),
        Arguments.of(
            "removeContexts with a second input",
            over(call(REMOVE_CONTEXTS, orderIds(), orderIds()))),
        Arguments.of(
            "a param left out, which lowering fills with its default",
            over(call(SPLIT_BY_VALUE, orderIds()))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("wrongArities")
  void aFunCallWithTheWrongArityIsRefused(String why, TargetDocument document) {
    // witness: each function with exactly its args is accepted
    assertAccepted(over(call(REMOVE_CONTEXTS, orderIds())));
    assertAccepted(over(call(SPLIT_BY_VALUE, orderIds(), literal("each"))));

    assertRefused(document);
  }

  static Stream<Arguments> wrongArgTypes() {
    return Stream.of(
        Arguments.of("a literal for a queue", over(call(REMOVE_CONTEXTS, literal("a")))),
        Arguments.of("the root's slot number for a queue", over(call(REMOVE_CONTEXTS, rootSlot()))),
        Arguments.of(
            "an atomic call for a queue", over(call(REMOVE_CONTEXTS, call(TRIM, literal(" a "))))),
        Arguments.of(
            "a queue call for a string param",
            over(call(SPLIT_BY_VALUE, orderIds(), call(REMOVE_CONTEXTS, orderIds())))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("wrongArgTypes")
  void aFunCallArgOfTheWrongTypeIsRefused(String why, TargetDocument document) {
    // witness: a call over a call, whose type is its FunctionRef's result, a queue
    assertAccepted(over(call(REMOVE_CONTEXTS, call(REMOVE_CONTEXTS, orderIds()))));
    assertAccepted(over(call(SPLIT_BY_VALUE, orderIds(), literal("each"))));

    assertRefused(document);
  }

  static Stream<Arguments> paramsThatAreNoLiterals() {
    return Stream.of(
        Arguments.of(
            "an atomic call",
            over(call(SPLIT_BY_VALUE, orderIds(), call(TRIM, literal(" each "))))),
        Arguments.of(
            "a string variable in scope", nested(new VarRef("item", AtomicType.STRING, T2))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("paramsThatAreNoLiterals")
  void aParamThatIsNoLiteralIsRefused(String why, TargetDocument document) {
    // witness: the same documents with a Literal in the param's place are accepted
    assertAccepted(over(call(SPLIT_BY_VALUE, orderIds(), literal("each"))));
    assertAccepted(nested(literal("each")));

    assertRefused(document);
  }

  static Stream<Arguments> atomicQueues() {
    return Stream.of(
        Arguments.of("a literal", over(literal("a"))),
        Arguments.of("the root's slot number", over(rootSlot())),
        Arguments.of("an atomic call", over(call(TRIM, literal(" a ")))));
  }

  /** A target field's occurrences are the slots of a queue, never of an atomic value. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("atomicQueues")
  void targetSlotsOverAnAtomicIsRefused(String why, TargetDocument document) {
    // witness: a queue call is a queue to create slots from
    assertAccepted(over(call(REMOVE_CONTEXTS, orderIds())));

    assertRefused(document);
  }

  // --- AtomicToQueue (ADR-0006 as amended by #83): a single value at a context or queue input is
  // AtomicToQueue(value, origin), one context with one value, typed QUEUE over a STRING value

  private static final AuthoringId Q1 = new AuthoringId("q1");

  /** constant: no input, one string param, an atomic call. */
  private static final FunctionRef CONSTANT =
      new FunctionRef("constant", List.of(AtomicType.STRING), AtomicType.STRING);

  /** count: one queue input, no param. */
  private static final FunctionRef COUNT = new FunctionRef("count", List.of(QUEUE), QUEUE);

  /** A constant box's value at count's input: the atomic call, or its Literal once folded. */
  @Test
  void atomicToQueueTakesAStringAndIsAQueue() {
    assertAccepted(over(call(COUNT, new AtomicToQueue(call(CONSTANT, literal("x")), Q1))));
    assertAccepted(over(call(COUNT, new AtomicToQueue(literal("x"), Q1))));
    // a context function's param stays a Literal: AtomicToQueue is an input, not a param
    assertAccepted(
        over(call(SPLIT_BY_VALUE, new AtomicToQueue(literal("x"), Q1), literal("eachValue"))));
  }

  static Stream<Arguments> atomicToQueueOfNoString() {
    return Stream.of(
        Arguments.of("over a source queue", over(call(COUNT, new AtomicToQueue(orderIds(), Q1)))),
        Arguments.of(
            "over a queue call",
            over(call(COUNT, new AtomicToQueue(call(REMOVE_CONTEXTS, orderIds()), Q1)))),
        Arguments.of(
            "over an INTEGER, the root's slot number",
            over(call(COUNT, new AtomicToQueue(rootSlot(), Q1)))));
  }

  /** The refusal names the AtomicToQueue and its origin, not the call around it. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("atomicToQueueOfNoString")
  void atomicToQueueOfNoStringIsRefused(String why, TargetDocument document) {
    // witness: the same call over AtomicToQueue of a string is accepted
    assertAccepted(over(call(COUNT, new AtomicToQueue(literal("x"), Q1))));

    assertThatThrownBy(() -> IrVerifier.verify(document))
        .as(why)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AtomicToQueue of q1");
  }

  /** AtomicToQueue is a queue, so it is no STRING where one belongs: a text, a single call. */
  @Test
  void atomicToQueueIsNoString() {
    assertRefused(
        document(
            slots(
                ORDER_ID,
                orderIds(),
                rootSlot(),
                1,
                1,
                "v",
                new AtomicToQueue(literal("x"), Q1),
                T1)));
    assertRefused(
        over(
            new Lift(
                List.of(orderIds()),
                List.of("in1"),
                call(TRIM, new AtomicToQueue(literal("x"), Q1)),
                R1)));
    // witness: the Literal itself is a STRING in both places
    assertAccepted(document(slots(ORDER_ID, orderIds(), rootSlot(), 1, 1, "v", literal("x"), T1)));
    assertAccepted(
        over(new Lift(List.of(orderIds()), List.of("in1"), call(TRIM, literal("x")), R1)));
  }

  // --- slot variables (ADR-0006 since #52): a TargetSlots binds its occurrence's slot number,
  // an INTEGER, to slotVar in its body only, where it is the children's parentSlot

  private static final Name ITEM = new Name("", "Item");
  private static final Name NAME = new Name("", "Name");
  private static final AuthoringId T3 = new AuthoringId("t3");

  private static VarRef integer(String name, AuthoringId at) {
    return new VarRef(name, AtomicType.INTEGER, at);
  }

  /** {@code Name} per slot of context {@code parentSlot} of Name in Item's context. */
  private static TargetSlots names(Expr parentSlot, Expr text) {
    return new TargetSlots(
        new SourceQueue(List.of(ORDER, ITEM, NAME), 2, A2),
        parentSlot,
        0,
        UNBOUNDED,
        "nameSlot",
        "name",
        new ElementConstructor("", NAME, List.of(new TextConstructor(text, T2)), T2),
        T2);
  }

  /** {@code Item} per slot of its queue, slot number {@code itemSlot}, with {@code content}. */
  private static TargetSlots items(Expr parentSlot, Expr... content) {
    return new TargetSlots(
        queue(ITEM, 1, A1),
        parentSlot,
        0,
        UNBOUNDED,
        "itemSlot",
        "item",
        new ElementConstructor("", ITEM, List.of(content), T1),
        T1);
  }

  /** Item, and Name in each Item's context: the nesting lowering gives a field at depth 3. */
  private static TargetDocument itemsWithNames() {
    return document(
        items(
            rootSlot(), names(integer("itemSlot", T2), new VarRef("name", AtomicType.STRING, T2))));
  }

  static Stream<Arguments> misusedSlotVars() {
    VarRef name = new VarRef("name", AtomicType.STRING, T2);
    return Stream.of(
        Arguments.of(
            "the slot number read as a string in the body",
            document(
                items(
                    rootSlot(),
                    names(
                        integer("itemSlot", T2), new VarRef("itemSlot", AtomicType.STRING, T2))))),
        Arguments.of(
            "the parent's value, a string, as the child's parentSlot",
            document(items(rootSlot(), names(new VarRef("item", AtomicType.STRING, T2), name)))),
        Arguments.of(
            "the slot number as its own parentSlot", document(items(integer("itemSlot", T1)))),
        Arguments.of(
            "a sibling's slot number, out of its body",
            document(items(rootSlot()), names(integer("itemSlot", T2), name))),
        Arguments.of(
            "a child's slot number in its parent's body, beside the child",
            document(
                items(
                    rootSlot(),
                    names(integer("itemSlot", T2), name),
                    names(integer("nameSlot", T3), name)))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("misusedSlotVars")
  void slotVarIsAnIntegerOnlyInTheBody(String why, TargetDocument document) {
    // witness: the child reads its parent's slot number as an INTEGER inside the parent's body
    assertAccepted(itemsWithNames());

    assertRefused(document);
  }

  // --- attributes (ADR-0006 since #102): an attribute item is a TargetSlots with max <= 1, or a
  // 1-copy TargetRepeat, whose body is an AttributeConstructor; the items come first in their
  // element's content, carry distinct names, and an AttributeConstructor stands nowhere else

  private static final Name CODE = new Name("", "code");
  private static final Name LANG = new Name("urn:b", "lang");
  private static final AuthoringId T4 = new AuthoringId("t4");

  private static AttributeConstructor attribute(Name name, String valueVar, AuthoringId at) {
    String prefix = name.namespace().isEmpty() ? "" : "ns1";
    return new AttributeConstructor(prefix, name, new VarRef(valueVar, AtomicType.STRING, at), at);
  }

  /** {@code name} per slot of {@code queue} in the parent slot, at most {@code max}. */
  private static TargetSlots attributeSlots(
      Name name, Expr queue, Expr parentSlot, long min, long max, AuthoringId at) {
    String valueVar = "a" + at.value();
    return new TargetSlots(
        queue, parentSlot, min, max, "s" + at.value(), valueVar, attribute(name, valueVar, at), at);
  }

  /** {@code code} 0..1 under the root, from the attribute queue {@code /Order/@code}. */
  private static TargetSlots codeItem() {
    return attributeSlots(
        CODE, new SourceQueue(List.of(ORDER, CODE), 1, false, true, A1), rootSlot(), 0, 1, T3);
  }

  /** A generating attribute: one copy, its value an atomic call. */
  private static TargetRepeat generatedLang(long copies) {
    return new TargetRepeat(
        copies,
        rootSlot(),
        "g",
        new AttributeConstructor("ns1", LANG, call(TRIM, literal(" en ")), T4),
        T4);
  }

  @Test
  void acceptsAttributeItemsBeforeOtherContent() {
    // under the root, before OrderId; a TargetSlots and a 1-copy TargetRepeat, 0..1 and 1..1
    assertAccepted(document(codeItem(), generatedLang(1), orderIdLink()));
    assertAccepted(
        document(
            attributeSlots(
                CODE,
                new SourceQueue(List.of(ORDER, CODE), 1, false, true, A1),
                rootSlot(),
                1,
                1,
                T3),
            orderIdLink()));
    // in an element's content, before its text: a simple-content leaf
    assertAccepted(
        document(
            new TargetSlots(
                queue(NOTE, 1, A2),
                rootSlot(),
                0,
                UNBOUNDED,
                "noteSlot",
                "note",
                new ElementConstructor(
                    "",
                    NOTE,
                    List.of(
                        attributeSlots(
                            CODE,
                            new SourceQueue(List.of(ORDER, NOTE, CODE), 2, false, true, A1),
                            integer("noteSlot", T3),
                            0,
                            1,
                            T3),
                        new TextConstructor(new VarRef("note", AtomicType.STRING, T2), T2)),
                    T2),
                T2)));
  }

  static Stream<Arguments> attributeItemsAfterContent() {
    VarRef note = new VarRef("note", AtomicType.STRING, T2);
    return Stream.of(
        Arguments.of("after an element in the document", document(orderIdLink(), codeItem())),
        Arguments.of(
            "a generated one after an element in the document",
            document(orderIdLink(), generatedLang(1))),
        Arguments.of(
            "after the text of a simple-content leaf",
            document(
                new TargetSlots(
                    queue(NOTE, 1, A2),
                    rootSlot(),
                    0,
                    UNBOUNDED,
                    "noteSlot",
                    "note",
                    new ElementConstructor(
                        "",
                        NOTE,
                        List.of(
                            new TextConstructor(note, T2),
                            attributeSlots(
                                CODE,
                                new SourceQueue(List.of(ORDER, NOTE, CODE), 2, false, true, A1),
                                integer("noteSlot", T3),
                                0,
                                1,
                                T3)),
                        T2),
                    T2))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("attributeItemsAfterContent")
  void refusesAttributeItemAfterContent(String why, TargetDocument document) {
    // witness: the same items with the attribute first
    assertAccepted(document(codeItem(), generatedLang(1), orderIdLink()));

    assertRefused(document);
  }

  static Stream<Arguments> attributeItemsAboveMaxOne() {
    SourceQueue codes = new SourceQueue(List.of(ORDER, CODE), 1, false, true, A1);
    return Stream.of(
        Arguments.of(
            "TargetSlots max 2", document(attributeSlots(CODE, codes, rootSlot(), 0, 2, T3))),
        Arguments.of(
            "TargetSlots unbounded",
            document(attributeSlots(CODE, codes, rootSlot(), 0, UNBOUNDED, T3))),
        Arguments.of("TargetRepeat of 2 copies", document(generatedLang(2))));
  }

  /** An element carries an attribute once: max 1 is the boundary. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("attributeItemsAboveMaxOne")
  void refusesAttributeSlotsAboveMaxOne(String why, TargetDocument document) {
    // witness: max 1 and one copy are accepted
    assertAccepted(document(codeItem(), generatedLang(1)));

    assertRefused(document);
  }

  @Test
  void refusesTwoAttributeItemsOfOneName() {
    SourceQueue codes = new SourceQueue(List.of(ORDER, CODE), 1, false, true, A1);
    // witness: two names, code and lang
    assertAccepted(document(codeItem(), generatedLang(1)));

    assertRefused(
        document(codeItem(), attributeSlots(CODE, codes, rootSlot(), 0, 1, T4), orderIdLink()));
  }

  static Stream<Arguments> strayAttributeConstructors() {
    AttributeConstructor code = new AttributeConstructor("", CODE, literal("x"), T3);
    return Stream.of(
        Arguments.of("bare in the document's content", document(code)),
        Arguments.of(
            "bare in an element's content",
            document(
                new TargetRepeat(
                    1, rootSlot(), "g", new ElementConstructor("", NOTE, List.of(code), T2), T2))),
        Arguments.of(
            "inside the element of a TargetSlots",
            document(
                new TargetSlots(
                    queue(NOTE, 1, A2),
                    rootSlot(),
                    0,
                    1,
                    "noteSlot",
                    "note",
                    new ElementConstructor("", NOTE, List.of(code), T2),
                    T2))),
        Arguments.of(
            "as a text's value",
            document(slots(ORDER_ID, queue(ORDER_ID, 1, A1), rootSlot(), 1, 1, "v", code, T1))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("strayAttributeConstructors")
  void refusesStrayAttributeConstructor(String why, TargetDocument document) {
    // witness: the same constructor as the body of a 1-copy TargetRepeat
    assertAccepted(
        document(
            new TargetRepeat(
                1, rootSlot(), "g", new AttributeConstructor("", CODE, literal("x"), T3), T3)));

    assertRefused(document);
  }

  /** An attribute's value is a STRING, as a text's is. */
  @Test
  void refusesAnAttributeValueThatIsNoString() {
    // witness: a Literal value
    assertAccepted(
        document(
            new TargetRepeat(
                1, rootSlot(), "g", new AttributeConstructor("", CODE, literal("x"), T3), T3)));

    assertRefused(
        document(
            new TargetRepeat(
                1,
                rootSlot(),
                "g",
                new AttributeConstructor("", CODE, queue(ORDER_ID, 1, A1), T3),
                T3)));
  }

  /** An attribute has a value and no occurrences of its own, so it is never a structure field. */
  @Test
  void refusesAttributeStructureQueue() {
    // witness: an attribute's queue into a target leaf, and the queue of a structure element
    assertAccepted(
        document(
            slots(
                NOTE,
                new SourceQueue(List.of(ORDER, CODE), 1, false, true, A2),
                rootSlot(),
                0,
                UNBOUNDED,
                "w",
                new VarRef("w", AtomicType.STRING, T2),
                T2)));
    assertAccepted(
        document(
            slots(
                NOTE,
                new SourceQueue(List.of(ORDER, NOTE), 1, true, false, A2),
                rootSlot(),
                0,
                UNBOUNDED,
                "w",
                new VarRef("w", AtomicType.STRING, T2),
                T2)));

    assertRefused(
        document(
            slots(
                NOTE,
                new SourceQueue(List.of(ORDER, CODE), 1, true, true, A2),
                rootSlot(),
                0,
                UNBOUNDED,
                "w",
                new VarRef("w", AtomicType.STRING, T2),
                T2)));
  }

  static void assertRefused(TargetDocument document) {
    assertThatThrownBy(() -> IrVerifier.verify(document))
        .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
  }

  // --- a generating block (#217, F-46): a TargetRepeat whose value is a QUEUE, the block, read in
  // the parent's scope, binds its value variable, a STRING, in its body only

  private static final AuthoringId F1 = new AuthoringId("f1");

  private static final FunctionRef COPY_VALUE =
      new FunctionRef("copyValue", List.of(QUEUE, AtomicType.STRING), QUEUE);

  /** copyValue of OrderId at position 1, at f1. */
  private static FunCall block() {
    return new FunCall(COPY_VALUE, List.of(orderIds(), new Literal("1", F1)), F1);
  }

  /** A generating repeat of 2 copies under {@code parentSlot}: {@code value} as its value. */
  private static TargetRepeat generating(
      Expr parentSlot, Optional<Expr> value, Optional<String> valueVar, Expr body) {
    return new TargetRepeat(2, parentSlot, "g", value, valueVar, body, T3);
  }

  /** {@code Note} holding {@code text}. */
  private static ElementConstructor note(Expr text) {
    return new ElementConstructor("", NOTE, List.of(new TextConstructor(text, T3)), T3);
  }

  private static VarRef string(String name) {
    return new VarRef(name, AtomicType.STRING, T3);
  }

  @Test
  void aGeneratingBlockBindsItsValueVariableInItsBodyOnly() {
    Optional<Expr> block = Optional.of(block());
    Optional<String> v = Optional.of("v");
    ElementConstructor empty = new ElementConstructor("", NOTE, List.of(), T3);
    // a leaf reads it; a 1-copy attribute reads it; a structure field's child reads it
    assertAccepted(document(generating(rootSlot(), block, v, note(string("v")))));
    assertAccepted(
        document(
            new TargetRepeat(
                1,
                rootSlot(),
                "g",
                block,
                v,
                new AttributeConstructor("", CODE, string("v"), T3),
                T3),
            orderIdLink()));
    assertAccepted(
        document(
            generating(
                rootSlot(),
                block,
                v,
                new ElementConstructor(
                    "", ITEM, List.of(names(integer("g", T2), string("v"))), T3))));
    // the block reads the parent's scope: Item's value, inside Item
    assertAccepted(
        document(
            items(
                rootSlot(),
                generating(
                    integer("itemSlot", T3),
                    Optional.of(
                        new FunCall(
                            COPY_VALUE,
                            List.of(new AtomicToQueue(string("item"), Q1), new Literal("0", F1)),
                            F1)),
                    v,
                    note(string("v"))))));

    // a sibling reads it out of the body
    assertRefused(document(generating(rootSlot(), block, v, note(string("v"))), note(string("v"))));
    // it is a STRING, so no child's parent slot
    assertRefused(
        document(
            generating(
                rootSlot(),
                block,
                v,
                new ElementConstructor(
                    "", ITEM, List.of(names(integer("v", T2), string("v"))), T3))));
    // the block reads its own value variable, and its own slot number: neither is in its scope
    assertRefused(
        document(
            generating(
                rootSlot(),
                Optional.of(
                    new FunCall(
                        COPY_VALUE,
                        List.of(new AtomicToQueue(string("v"), Q1), new Literal("0", F1)),
                        F1)),
                v,
                note(string("v")))));
    // witness: a block of the same shape that reads the root's slot number, which is in its scope
    assertAccepted(document(generating(rootSlot(), slotText(rootSlot()), v, empty)));
    assertRefused(document(generating(rootSlot(), slotText(integer("g", F1)), v, empty)));
  }

  /** A function of one INTEGER; the verifier checks a FunctionRef's types only. */
  private static final FunctionRef SLOT_TEXT =
      new FunctionRef("slotText", List.of(AtomicType.INTEGER), AtomicType.STRING);

  /** A block that reads {@code slot}: {@code slotText(slot)} as a queue. */
  private static Optional<Expr> slotText(VarRef slot) {
    return Optional.of(new AtomicToQueue(new FunCall(SLOT_TEXT, List.of(slot), F1), Q1));
  }

  @Test
  void aQueueValueNeedsAValueVariableAndAStringValueHasNone() {
    Optional<String> v = Optional.of("v");
    ElementConstructor empty = new ElementConstructor("", NOTE, List.of(), T3);
    // witnesses: a block with its variable, a STRING value without one, and neither
    assertAccepted(document(generating(rootSlot(), Optional.of(block()), v, note(string("v")))));
    assertAccepted(
        document(
            generating(
                rootSlot(), Optional.of(call(CONSTANT, literal("x"))), Optional.empty(), empty)));
    assertAccepted(document(generating(rootSlot(), Optional.empty(), Optional.empty(), empty)));

    // a block without a value variable
    assertRefused(document(generating(rootSlot(), Optional.of(block()), Optional.empty(), empty)));
    // a STRING value, a Literal or a call, with one; the body does not read it, so only the value
    // variable itself is refused
    assertRefused(document(generating(rootSlot(), Optional.of(literal("x")), v, empty)));
    assertRefused(
        document(generating(rootSlot(), Optional.of(call(CONSTANT, literal("x"))), v, empty)));
    // a value variable without a value, which the body does not read either
    assertRefused(document(generating(rootSlot(), Optional.empty(), v, empty)));
    // an INTEGER value
    assertRefused(document(generating(rootSlot(), Optional.of(rootSlot()), v, empty)));
  }
}
