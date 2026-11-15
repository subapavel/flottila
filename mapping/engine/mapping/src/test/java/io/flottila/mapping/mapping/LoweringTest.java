package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.LoadResults.errorAtId;
import static io.flottila.mapping.mapping.LoadResults.errorAtPointer;
import static io.flottila.mapping.mapping.LoadResults.lowered;
import static io.flottila.mapping.mapping.LoadResults.refused;
import static io.flottila.mapping.mapping.Models.boxes;
import static io.flottila.mapping.mapping.Models.function;
import static io.flottila.mapping.mapping.Models.id;
import static io.flottila.mapping.mapping.Models.inputs;
import static io.flottila.mapping.mapping.Models.model;
import static io.flottila.mapping.mapping.Models.names;
import static io.flottila.mapping.mapping.Models.noParams;
import static io.flottila.mapping.mapping.Models.source;
import static io.flottila.mapping.mapping.Models.targetFieldMapping;
import static io.flottila.mapping.mapping.Models.targetFieldMappings;
import static io.flottila.mapping.mapping.Models.udf;
import static io.flottila.mapping.mapping.Models.udfDeclaration;
import static io.flottila.mapping.mapping.Models.udfs;
import static io.flottila.mapping.mapping.Models.unresolved;
import static io.flottila.mapping.mapping.TestStructures.ADDRESS;
import static io.flottila.mapping.mapping.TestStructures.ORDER;
import static io.flottila.mapping.mapping.TestStructures.SKELETON;
import static io.flottila.mapping.mapping.TestStructures.invoice;
import static io.flottila.mapping.mapping.TestStructures.invoiceWithoutNamespace;
import static io.flottila.mapping.mapping.TestStructures.order;
import static io.flottila.mapping.mapping.TestStructures.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AttributeConstructor;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetRepeat;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.structure.Occurrence;
import io.flottila.mapping.structure.Structure;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Validation stage 3 and lowering of a field link (ADR-0006, ADR-0011, ADR-0022; #26): a source box
 * reached by {@code result} becomes {@code TargetSlots} over its {@code SourceQueue}; anything else
 * stage 3 meets is a design-time error at the authoring id the architect named. A box {@code
 * result} does not reach is not checked. Target field mappings are walked in target field order.
 */
class LoweringTest {

  private static final String ORDER_ID = ORDER + "/OrderId";
  private static final String QUANTITY = ORDER + "/Quantity";
  private static final String NOTE = ORDER + "/Note";
  private static final String CHANNEL = ORDER + "/@channel";
  private static final Name ORDER_NAME = new Name(SKELETON, "Order");

  private static MappingModel orderToOrder(Map<String, TargetFieldMapping> mappings) {
    return model(ref(order()), ref(order()), mappings, Map.of());
  }

  private static LowerResult lower(MappingModel model) {
    return Lowering.lower(model, order(), order());
  }

  /** {@code OrderId <- OrderId}: the skeleton's field link. */
  private static Map<String, TargetFieldMapping> fieldLink() {
    return targetFieldMappings(
        ORDER_ID, targetFieldMapping("t1", "a1", boxes("a1", source(ORDER_ID))));
  }

  @Test
  void aSourceFieldBoxLowersToTargetSlotsOverItsSourceQueue() {
    TargetDocument document = lowered(lower(orderToOrder(fieldLink())));

    assertThat(document.prefix()).isEqualTo("ns0");
    assertThat(document.root()).isEqualTo(ORDER_NAME);
    assertThat(document.content()).hasSize(1).first().isInstanceOf(TargetSlots.class);
    TargetSlots slots = (TargetSlots) document.content().getFirst();
    // the field by its element steps, the context (the parent Order) by its depth
    assertThat(slots.queue())
        .isEqualTo(new SourceQueue(List.of(ORDER_NAME, new Name("", "OrderId")), 1, id("a1")));
    assertThat(slots.parentSlot())
        .isInstanceOfSatisfying(
            VarRef.class,
            parent -> {
              assertThat(parent.name()).isEqualTo(document.rootSlot());
              assertThat(parent.type()).isEqualTo(AtomicType.INTEGER);
            });
    assertThat(slots.origin()).isEqualTo(id("t1"));
    assertThat(slots.body())
        .isInstanceOfSatisfying(
            ElementConstructor.class,
            element -> {
              assertThat(element.prefix()).isEmpty();
              assertThat(element.name()).isEqualTo(new Name("", "OrderId"));
              assertThat(element.origin()).isEqualTo(id("t1"));
              assertThat(element.content())
                  .singleElement()
                  .isInstanceOfSatisfying(
                      TextConstructor.class,
                      text -> {
                        assertThat(text.origin()).isEqualTo(id("t1"));
                        assertThat(text.value())
                            .isInstanceOfSatisfying(
                                VarRef.class,
                                value -> {
                                  assertThat(value.name()).isEqualTo(slots.valueVar());
                                  assertThat(value.type()).isEqualTo(AtomicType.STRING);
                                });
                      });
            });
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();

    // the parent named as the context explicitly is the same queue
    TargetDocument explicit =
        lowered(
            lower(
                orderToOrder(
                    targetFieldMappings(
                        ORDER_ID,
                        targetFieldMapping("t1", "a1", boxes("a1", source(ORDER_ID, ORDER)))))));
    assertThat(((TargetSlots) explicit.content().getFirst()).queue()).isEqualTo(slots.queue());
  }

  @Test
  void aSourceLeafBelowAStructureFieldLowersWithItsContextDepth() {
    String street = ADDRESS + "/Street";
    List<Name> streetNames = List.of(ORDER_NAME, new Name("", "Address"), new Name("", "Street"));

    // #62: a source leaf at any depth; its context is the parent Address by default, depth 2
    TargetDocument byParent =
        lowered(
            lower(
                orderToOrder(
                    withOrderId(
                        NOTE, targetFieldMapping("t1", "a1", boxes("a1", source(street)))))));
    // any proper ancestor may be the context: the root Order is depth 1
    TargetDocument byRoot =
        lowered(
            lower(
                orderToOrder(
                    withOrderId(
                        NOTE,
                        targetFieldMapping("t1", "a1", boxes("a1", source(street, ORDER)))))));

    // Note's slots follow OrderId's
    assertThat(((TargetSlots) byParent.content().getLast()).queue())
        .isEqualTo(new SourceQueue(streetNames, 2, id("a1")));
    assertThat(((TargetSlots) byRoot.content().getLast()).queue())
        .isEqualTo(new SourceQueue(streetNames, 1, id("a1")));
  }

  /**
   * {@code path <- mapping} beside the mandatory {@code OrderId <- a0}, which the target needs
   * (#65).
   */
  private static Map<String, TargetFieldMapping> withOrderId(
      String path, TargetFieldMapping mapping) {
    return targetFieldMappings(
        ORDER_ID, targetFieldMapping("t0", "a0", boxes("a0", source(ORDER_ID))), path, mapping);
  }

  /**
   * Until #63 this was unsupported-field at t1 (#52 design, O1). Street's parent Address is
   * unmapped and optional (0..1), so it is left out with its subtree: the mapping compiles and
   * Street is never created (ADR-0006 "Target fields").
   */
  @Test
  void aTargetLeafBelowAnUnmappedOptionalParentIsLeftOutWithIt() {
    TargetFieldMapping street = targetFieldMapping("t1", "a1", boxes("a1", source(ORDER_ID)));

    assertThat(lowered(lower(orderToOrder(withOrderId(ADDRESS + "/Street", street)))).content())
        .singleElement()
        .extracting(Expr::origin)
        .isEqualTo(id("t0"));
    // without it, the unmapped OrderId (1..1) is the only diagnostic: Street is accepted (#65)
    assertThat(refused(lower(orderToOrder(targetFieldMappings(ADDRESS + "/Street", street)))))
        .containsExactly(errorAtPointer("unmapped-mandatory-leaf", unmapped(ORDER_ID)));
  }

  /**
   * ADR-0008 "JSON as a target": a JSON target loads and shows, but a mapping with one does not
   * compile, whatever it maps. The root selector decides, so the field link that lowers onto the
   * skeleton (a mapping with an XML target) gives this one diagnostic and nothing else.
   */
  @Test
  void aJsonTargetIsJsonTargetUnsupportedAtTarget() {
    StructureRef jsonTarget = Models.jsonRoot(order().fingerprint(), "order.schema.json");

    assertThat(lowered(lower(orderToOrder(fieldLink())))).isNotNull();
    assertThat(refused(lower(model(ref(order()), jsonTarget, fieldLink(), Map.of()))))
        .containsExactly(errorAtPointer("json-target-unsupported", "/target"));
  }

  @Test
  void targetSlotsTakeTheEffectiveOccurrence() {
    TargetDocument document =
        lowered(
            lower(
                orderToOrder(
                    targetFieldMappings(
                        NOTE,
                        targetFieldMapping("t3", "a3", boxes("a3", source(NOTE))),
                        QUANTITY,
                        targetFieldMapping("t2", "a2", boxes("a2", source(QUANTITY))),
                        ORDER_ID,
                        targetFieldMapping("t1", "a1", boxes("a1", source(ORDER_ID)))))));

    // in target field order; Note is 0..unbounded
    assertThat(document.content())
        .map(e -> (TargetSlots) e)
        .extracting(
            s -> ((ElementConstructor) s.body()).name().localName(),
            TargetSlots::min,
            TargetSlots::max)
        .containsExactly(
            tuple("OrderId", 1L, 1L), tuple("Quantity", 0L, 1L), tuple("Note", 0L, Long.MAX_VALUE));
  }

  @Test
  void prefixesFollowTheTargetNamespaces() {
    String invoice = "/Q{urn:t}Invoice";
    MappingModel model =
        model(
            ref(order()),
            ref(invoice()),
            targetFieldMappings(
                invoice + "/Q{urn:u}Other",
                targetFieldMapping("m5", "b5", boxes("b5", source(ORDER_ID))),
                invoice + "/Q{urn:t}Same",
                targetFieldMapping("m4", "b4", boxes("b4", source(ORDER_ID))),
                invoice + "/Q{urn:v}Ref",
                targetFieldMapping("m3", "b3", boxes("b3", source(ORDER_ID))),
                invoice + "/Plain",
                targetFieldMapping("m2", "b2", boxes("b2", source(ORDER_ID))),
                invoice + "/Q{urn:u}Number",
                targetFieldMapping("m1", "b1", boxes("b1", source(ORDER_ID)))),
            Map.of());

    TargetDocument document = lowered(Lowering.lower(model, order(), invoice()));

    // the root's namespace is ns0, each further one ns1, ns2 ... in target field order; none: ""
    assertThat(document.prefix()).isEqualTo("ns0");
    assertThat(document.root()).isEqualTo(new Name("urn:t", "Invoice"));
    assertThat(document.content())
        .map(e -> (ElementConstructor) ((TargetSlots) e).body())
        .extracting(ElementConstructor::prefix, ElementConstructor::name)
        .containsExactly(
            tuple("ns1", new Name("urn:u", "Number")),
            tuple("", new Name("", "Plain")),
            tuple("ns2", new Name("urn:v", "Ref")),
            tuple("ns0", new Name("urn:t", "Same")),
            tuple("ns1", new Name("urn:u", "Other")));
  }

  /**
   * The assumed reading of ADR-0022, pinned (#49): when the target root has no namespace, the root
   * has no prefix and {@code ns0} goes to the first namespace in target field order, here {@code
   * urn:v}; the model lists its target field mappings in another order.
   */
  @Test
  void withoutARootNamespaceNs0IsTheFirstNamespaceInTargetFieldOrder() {
    String invoice = "/Invoice";
    MappingModel model =
        model(
            ref(order()),
            ref(invoiceWithoutNamespace()),
            targetFieldMappings(
                invoice + "/Q{urn:u}Number",
                targetFieldMapping("m3", "b3", boxes("b3", source(ORDER_ID))),
                invoice + "/Q{urn:v}Other",
                targetFieldMapping("m4", "b4", boxes("b4", source(ORDER_ID))),
                invoice + "/Plain",
                targetFieldMapping("m1", "b1", boxes("b1", source(ORDER_ID))),
                invoice + "/Q{urn:v}Ref",
                targetFieldMapping("m2", "b2", boxes("b2", source(ORDER_ID)))),
            Map.of());

    TargetDocument document = lowered(Lowering.lower(model, order(), invoiceWithoutNamespace()));

    assertThat(document.prefix()).isEmpty();
    assertThat(document.root()).isEqualTo(new Name("", "Invoice"));
    assertThat(document.content())
        .map(e -> (ElementConstructor) ((TargetSlots) e).body())
        .extracting(ElementConstructor::prefix, ElementConstructor::name)
        .containsExactly(
            tuple("", new Name("", "Plain")),
            tuple("ns0", new Name("urn:v", "Ref")),
            tuple("ns1", new Name("urn:u", "Number")),
            tuple("ns0", new Name("urn:v", "Other")));
  }

  @Test
  void aReachedFunctionOrUdfBoxIsUnsupportedBoxKindAtTheBox() {
    MappingModel model =
        model(
            ref(order()),
            ref(order()),
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping(
                    "t1",
                    "f1",
                    boxes(
                        "f1",
                        function("rfcLookup", inputs("a1"), noParams()),
                        "a1",
                        source(ORDER_ID))),
                QUANTITY,
                targetFieldMapping(
                    "t2",
                    "u1",
                    boxes("u1", udf("twice", inputs("a2"), noParams()), "a2", source(QUANTITY)))),
            udfs(
                "twice",
                udfDeclaration(
                    ExecutionKind.SINGLE, names("value"), Map.of(), "udfs/Numbers.java#twice")));

    assertThat(refused(lower(model)))
        .containsExactly(
            errorAtId("unsupported-box-kind", "f1"), errorAtId("unsupported-box-kind", "u1"));
  }

  @Test
  void aReachedUnresolvedBoxIsUnresolvedFunctionAtTheBox() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping(
                    "t1",
                    "r1",
                    boxes(
                        "r1",
                        unresolved("FormatNum", Origin.PO, inputs("a1"), noParams()),
                        "a1",
                        source(ORDER_ID)))));

    assertThat(refused(lower(model))).containsExactly(errorAtId("unresolved-function", "r1"));
  }

  @Test
  void anEmptyResultIsUnconnectedTargetFieldAtTheMapping() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID, targetFieldMapping("t1", null, boxes("a1", source(ORDER_ID)))));

    assertThat(refused(lower(model))).containsExactly(errorAtId("unconnected-target-field", "t1"));
  }

  @Test
  void anUnreachableNonSourceBoxIsNotRefused() {
    MappingModel model =
        model(
            ref(order()),
            ref(order()),
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping(
                    "t1",
                    "a1",
                    boxes(
                        "a1",
                        source(ORDER_ID),
                        "f9",
                        function("trim", inputs("a1"), noParams()),
                        "u9",
                        udf("twice", inputs("a1"), noParams()),
                        "r9",
                        unresolved("FormatNum", Origin.PO, inputs("a1"), noParams()),
                        // not checked either: its field does not exist
                        "s9",
                        source(ORDER + "/Missing")))),
            udfs(
                "twice",
                udfDeclaration(
                    ExecutionKind.SINGLE, names("value"), Map.of(), "udfs/Numbers.java#twice")));

    TargetDocument document = lowered(lower(model));

    assertThat(document.content())
        .singleElement()
        .isInstanceOfSatisfying(
            TargetSlots.class,
            slots ->
                assertThat(slots.queue())
                    .isEqualTo(
                        new SourceQueue(
                            List.of(ORDER_NAME, new Name("", "OrderId")), 1, id("a1"))));
  }

  @Test
  void aMissingSourceFieldIsAnOrphanedReference() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t1", "a1", boxes("a1", source(ORDER + "/OrderNumber"))),
                QUANTITY,
                targetFieldMapping(
                    "t2", "a2", boxes("a2", source("/Q{urn:other}Order/Quantity")))));

    assertThat(refused(lower(model)))
        .containsExactly(
            errorAtId("orphaned-reference", "a1"), errorAtId("orphaned-reference", "a2"));
  }

  /**
   * A target field the target structure lacks is orphaned-reference at its target field mapping.
   */
  @Test
  void aMissingTargetFieldIsAnOrphanedReference() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t1", "a1", boxes("a1", source(ORDER_ID))),
                ORDER + "/Missing",
                targetFieldMapping("t2", "a2", boxes("a2", source(QUANTITY)))));

    assertThat(refused(lower(model))).containsExactly(errorAtId("orphaned-reference", "t2"));
  }

  /**
   * Until #102 this was {@code anAttributeFieldIsUnsupportedField}: the source attribute at a1 and
   * the target attribute at t2 were both unsupported-field. Since #102 an attribute below the root
   * is a source field, whose queue sets {@code attribute}, and a target leaf, whose item comes
   * before OrderId (ADR-0006).
   */
  @Test
  void anAttributeIsASourceFieldAndATargetField() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t1", "a1", boxes("a1", source(CHANNEL))),
                CHANNEL,
                targetFieldMapping("t2", "a2", boxes("a2", source(ORDER_ID)))));

    TargetDocument document = lowered(lower(model));

    assertThat(document.content())
        .map(e -> ((TargetSlots) e).body())
        .extracting(Expr::getClass, Expr::origin)
        .containsExactly(
            tuple(AttributeConstructor.class, id("t2")), tuple(ElementConstructor.class, id("t1")));
    assertThat(((TargetSlots) document.content().getLast()).queue())
        .isEqualTo(
            new SourceQueue(
                List.of(ORDER_NAME, new Name("", "channel")), 1, false, true, id("a1")));
  }

  // --- attributes (ADR-0006 since #102), over TestStructures.tagged()

  private static final String DOC = "/Doc";
  private static final String CODE = DOC + "/@code";
  private static final String FLAG = DOC + "/@Q{urn:b}flag";
  private static final String ITEM = DOC + "/Item";
  private static final String NOTE_C = DOC + "/Q{urn:c}Note";
  private static final Name DOC_NAME = new Name("", "Doc");
  private static final Name ITEM_NAME = new Name("", "Item");

  private static LowerResult lowerTagged(Object... pathThenMapping) {
    return Lowering.lower(
        model(
            ref(TestStructures.orders()),
            ref(TestStructures.tagged()),
            targetFieldMappings(pathThenMapping),
            Map.of()),
        TestStructures.orders(),
        TestStructures.tagged());
  }

  private static TargetFieldMapping fromOrders(String id, String field, String context) {
    return targetFieldMapping(id, "s" + id, boxes("s" + id, source(field, context)));
  }

  /** The target attribute item of {@code name} under the root, as lowering gives it at depth 2. */
  private static TargetSlots rootAttribute(
      String prefix, Name name, Expr queue, long min, String mapping) {
    return new TargetSlots(
        queue,
        integer("root", mapping),
        min,
        1,
        "slot2",
        "value2",
        new AttributeConstructor(
            prefix, name, new VarRef("value2", AtomicType.STRING, id(mapping)), id(mapping)),
        id(mapping));
  }

  private static SourceQueue orderIds(String box) {
    return new SourceQueue(List.of(ORDERS_NAME, ORDER_ELEMENT, new Name("", "Id")), 1, id(box));
  }

  /**
   * A source attribute's default context is its element, depth {@code size - 1} (ADR-0007, #2 §1
   * [SAP✓]), and any proper ancestor may be set instead; a root attribute's element is the root.
   */
  @Test
  void sourceAttributeContextIsItsElement() {
    List<Name> itemId = List.of(DOC_NAME, ITEM_NAME, new Name("", "id"));
    MappingModel model =
        model(
            ref(TestStructures.tagged()),
            ref(order()),
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t1", "a1", boxes("a1", source(CODE))),
                NOTE,
                targetFieldMapping("t2", "a2", boxes("a2", source(ITEM + "/@id"))),
                QUANTITY,
                targetFieldMapping("t3", "a3", boxes("a3", source(ITEM + "/@id", DOC)))),
            Map.of());

    TargetDocument document = lowered(Lowering.lower(model, TestStructures.tagged(), order()));

    assertThat(document.content())
        .map(e -> ((TargetSlots) e).queue())
        .containsExactly(
            new SourceQueue(List.of(DOC_NAME, new Name("", "code")), 1, false, true, id("a1")),
            new SourceQueue(itemId, 1, false, true, id("a3")),
            new SourceQueue(itemId, 2, false, true, id("a2")));
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  /**
   * Attribute items come first in their element's content, in target field order, whatever order
   * the model lists its target field mappings in (ADR-0006, ADR-0008).
   */
  @Test
  void targetAttributeItemPrecedesChildElements() {
    TargetDocument document =
        lowered(
            lowerTagged(
                ITEM,
                fromOrders("t1", "/Orders/Order/Id", "/Orders"),
                ITEM + "/Name",
                fromOrders("t2", "/Orders/Order/Tag", "/Orders/Order"),
                ITEM + "/@id",
                fromOrders("t3", "/Orders/Order/Id", "/Orders/Order"),
                FLAG,
                fromOrders("t4", "/Orders/Order/Id", "/Orders"),
                CODE,
                fromOrders("t5", "/Orders/Order/Id", "/Orders")));

    assertThat(document.content())
        .extracting(Expr::origin)
        .containsExactly(id("t5"), id("t4"), id("t1"));
    assertThat(document.content().getFirst())
        .isEqualTo(rootAttribute("", new Name("", "code"), orderIds("st5"), 0, "t5"));
    ElementConstructor item =
        (ElementConstructor) ((TargetSlots) document.content().getLast()).body();
    assertThat(item.content())
        .map(e -> ((TargetSlots) e).body())
        .extracting(Expr::getClass, Expr::origin)
        .containsExactly(
            tuple(AttributeConstructor.class, id("t3")), tuple(ElementConstructor.class, id("t2")));
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  /** A target attribute is 0..1, or 1..1 when {@code use="required"} (ADR-0006 since #102). */
  @Test
  void requiredAttributeSlotsHaveMinOne() {
    TargetDocument document =
        lowered(
            lowerTagged(
                CODE,
                fromOrders("t1", "/Orders/Order/Id", "/Orders"),
                FLAG,
                fromOrders("t2", "/Orders/Order/Id", "/Orders")));

    assertThat(document.content())
        .map(e -> (TargetSlots) e)
        .extracting(TargetSlots::origin, TargetSlots::min, TargetSlots::max)
        .containsExactly(tuple(id("t1"), 0L, 1L), tuple(id("t2"), 1L, 1L));
    // without a mapping, the required flag is an unmapped mandatory leaf, as an element is (#91)
    assertThat(refused(lowerTagged(CODE, fromOrders("t1", "/Orders/Order/Id", "/Orders"))))
        .containsExactly(errorAtPointer("unmapped-mandatory-leaf", unmapped(FLAG)));
  }

  /** A generating function on an attribute is one copy, 0..1 and 1..1 alike (ADR-0006). */
  @Test
  void generatingAttributeIsOneCopy() {
    TargetDocument document =
        lowered(
            lowerTagged(
                CODE,
                targetFieldMapping(
                    "t1",
                    "c1",
                    boxes(
                        "c1",
                        function("constant", inputs(), Models.params("value", Models.text("x"))))),
                FLAG,
                targetFieldMapping(
                    "t2",
                    "c2",
                    boxes(
                        "c2",
                        function(
                            "constant", inputs(), Models.params("value", Models.text("y")))))));

    FunctionRef constant =
        new FunctionRef("constant", List.of(AtomicType.STRING), AtomicType.STRING);
    assertThat(document.content())
        .containsExactly(
            new TargetRepeat(
                1,
                integer("root", "t1"),
                "slot2",
                new AttributeConstructor(
                    "",
                    new Name("", "code"),
                    new FunCall(constant, List.of(new Literal("x", id("c1"))), id("c1")),
                    id("t1")),
                id("t1")),
            new TargetRepeat(
                1,
                integer("root", "t2"),
                "slot2",
                new AttributeConstructor(
                    "ns0",
                    new Name("urn:b", "flag"),
                    new FunCall(constant, List.of(new Literal("y", id("c2"))), id("c2")),
                    id("t2")),
                id("t2")));
  }

  // --- a generating block (#217, F-46): copyValue's result, one value for every parent

  private static final FunctionRef COPY_VALUE =
      new FunctionRef("copyValue", List.of(new QueueType(), AtomicType.STRING), new QueueType());

  private static final String SKU = "/Orders/Order/Line/Sku";

  /** The boxes of copyValue at {@code box} over Sku in context Order, at box s{@code box}. */
  private static Object[] copyValueBoxes(String box, String positionOrNull) {
    return new Object[] {
      "s" + box,
      source(SKU, "/Orders/Order"),
      box,
      function(
          "copyValue",
          inputs("s" + box),
          positionOrNull == null
              ? noParams()
              : Models.params("position", Models.text(positionOrNull)))
    };
  }

  /** The target field mapping {@code id} whose result is copyValue at {@code box}. */
  private static TargetFieldMapping copyValue(String id, String box, String positionOrNull) {
    return targetFieldMapping(id, box, boxes(copyValueBoxes(box, positionOrNull)));
  }

  /** The block copyValue at {@code box} gives over Sku in context Order, at s{@code box}. */
  private static FunCall copyValueBlock(String box, String position) {
    return new FunCall(
        COPY_VALUE,
        List.of(
            new SourceQueue(
                List.of(ORDERS_NAME, ORDER_ELEMENT, LINE, new Name("", "Sku")), 2, id("s" + box)),
            new Literal(position, id(box))),
        id(box));
  }

  /**
   * A generating block is a generating {@code TargetRepeat} over it: the block as its value and
   * value{@code <d>} as its value variable, which a leaf's text and an attribute's value read, and
   * {@code max(1, minOccurs)} copies, as for a generating single value (ADR-0006). A structure
   * field holds its children only. The default position is the catalogue's {@code 0}.
   */
  @Test
  void copyValueOnALeafAttributeAndStructureFieldIsAGeneratingRepeatOverItsBlock() {
    TargetDocument document =
        lowered(
            lowerTagged(
                CODE,
                copyValue("t1", "f1", "1"),
                FLAG,
                fromOrders("t0", "/Orders/Order/Id", "/Orders"),
                ITEM,
                copyValue("t2", "f2", null),
                ITEM + "/Name",
                copyValue("t3", "f3", "2")));

    assertThat(document.content())
        .containsExactly(
            new TargetRepeat(
                1,
                integer("root", "t1"),
                "slot2",
                Optional.of(copyValueBlock("f1", "1")),
                Optional.of("value2"),
                new AttributeConstructor(
                    "",
                    new Name("", "code"),
                    new VarRef("value2", AtomicType.STRING, id("t1")),
                    id("t1")),
                id("t1")),
            rootAttribute("ns0", new Name("urn:b", "flag"), orderIds("st0"), 1, "t0"),
            new TargetRepeat(
                1,
                integer("root", "t2"),
                "slot2",
                Optional.of(copyValueBlock("f2", "0")),
                Optional.of("value2"),
                new ElementConstructor(
                    "",
                    ITEM_NAME,
                    List.of(
                        new TargetRepeat(
                            1,
                            integer("slot2", "t3"),
                            "slot3",
                            Optional.of(copyValueBlock("f3", "2")),
                            Optional.of("value3"),
                            new ElementConstructor(
                                "",
                                new Name("", "Name"),
                                List.of(
                                    new TextConstructor(
                                        new VarRef("value3", AtomicType.STRING, id("t3")),
                                        id("t3"))),
                                id("t3")),
                            id("t3"))),
                    id("t2")),
                id("t2")));
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  /** At a context function's input the block is the queue it is: count reads one context. */
  @Test
  void copyValueAtAContextInputIsItsBlock() {
    Object[] copy = copyValueBoxes("f1", "1");
    TargetDocument document =
        lowered(
            lowerTagged(
                CODE,
                targetFieldMapping(
                    "t1",
                    "f2",
                    boxes(
                        copy[0],
                        copy[1],
                        copy[2],
                        copy[3],
                        "f2",
                        function("count", inputs("f1"), noParams()))),
                FLAG,
                copyValue("t0", "f0", "1")));

    FunctionRef count = new FunctionRef("count", List.of(new QueueType()), new QueueType());
    assertThat(document.content().getFirst())
        .isEqualTo(
            rootAttribute(
                "",
                new Name("", "code"),
                new FunCall(count, List.of(copyValueBlock("f1", "1")), id("f2")),
                0,
                "t1"));
    // witness: the same block at the target field, not at an input, is a generating repeat
    assertThat(document.content().getLast()).isInstanceOf(TargetRepeat.class);
  }

  /**
   * A box that takes a single value cannot read a generating block (the deviation F-46 records):
   * {@code unsupported-generating-input} at the consuming box, whichever input reads it.
   */
  @Test
  void copyValueAtASingleValueInputIsRefused() {
    Object[] copy = copyValueBoxes("f1", "1");
    Map<String, Map<AuthoringId, Box>> graphs =
        Map.of(
            "trim(copyValue)",
            boxes(
                copy[0],
                copy[1],
                copy[2],
                copy[3],
                "f2",
                function("trim", inputs("f1"), noParams())),
            "concat(copyValue, constant)",
            boxes(
                copy[0],
                copy[1],
                copy[2],
                copy[3],
                "c1",
                function("constant", inputs(), Models.params("value", Models.text("x"))),
                "f2",
                function("concat", inputs("f1", "c1"), noParams())),
            "concat(source, copyValue)",
            boxes(
                copy[0],
                copy[1],
                copy[2],
                copy[3],
                "s2",
                source("/Orders/Order/Id", "/Orders"),
                "f2",
                function("concat", inputs("s2", "f1"), noParams())));
    for (Map.Entry<String, Map<AuthoringId, Box>> graph : graphs.entrySet()) {
      assertThat(
              refused(
                  lowerTagged(
                      CODE,
                      targetFieldMapping("t1", "f2", graph.getValue()),
                      FLAG,
                      fromOrders("t0", "/Orders/Order/Id", "/Orders"))))
          .as(graph.getKey())
          .containsExactly(errorAtId("unsupported-generating-input", "f2"));
    }

    // witnesses: the same graphs with Sku itself where copyValue was lower
    for (String input : List.of("trim", "concat-constant", "concat-source")) {
      Map<AuthoringId, Box> boxes =
          switch (input) {
            case "trim" ->
                boxes(copy[0], copy[1], "f2", function("trim", inputs("sf1"), noParams()));
            case "concat-constant" ->
                boxes(
                    copy[0],
                    copy[1],
                    "c1",
                    function("constant", inputs(), Models.params("value", Models.text("x"))),
                    "f2",
                    function("concat", inputs("sf1", "c1"), noParams()));
            default ->
                boxes(
                    copy[0],
                    copy[1],
                    "s2",
                    source("/Orders/Order/Id", "/Orders"),
                    "f2",
                    function("concat", inputs("s2", "sf1"), noParams()));
          };
      assertThat(
              lowerTagged(
                  CODE,
                  targetFieldMapping("t1", "f2", boxes),
                  FLAG,
                  fromOrders("t0", "/Orders/Order/Id", "/Orders")))
          .as(input)
          .isInstanceOf(Lowered.class);
    }
  }

  /**
   * A box refused for a generating input gives no value, so the box that reads it is not lowered
   * and reports nothing of its own, though it reads the block as well.
   */
  @Test
  void aBoxRefusedForAGeneratingInputGivesNoValueToTheBoxThatReadsIt() {
    Object[] copy = copyValueBoxes("f1", "1");
    assertThat(
            refused(
                lowerTagged(
                    CODE,
                    targetFieldMapping(
                        "t1",
                        "f3",
                        boxes(
                            copy[0],
                            copy[1],
                            copy[2],
                            copy[3],
                            "f2",
                            function("trim", inputs("f1"), noParams()),
                            "f3",
                            function("concat", inputs("f2", "f1"), noParams()))),
                    FLAG,
                    fromOrders("t0", "/Orders/Order/Id", "/Orders"))))
        .containsExactly(errorAtId("unsupported-generating-input", "f2"));
  }

  /**
   * An element with a value and attributes is a leaf (GLOSSARY "Leaf field"): its content is its
   * attribute items, under its slot number, then its text (ADR-0006 since #102).
   */
  @Test
  void simpleContentLeafHoldsAttributesThenText() {
    TargetDocument document =
        lowered(
            lowerTagged(
                NOTE_C + "/@lang",
                fromOrders("t2", "/Orders/Order/Tag", "/Orders/Order"),
                NOTE_C,
                fromOrders("t1", "/Orders/Order/Id", "/Orders"),
                FLAG,
                fromOrders("t0", "/Orders/Order/Id", "/Orders")));

    TargetSlots lang =
        new TargetSlots(
            new SourceQueue(List.of(ORDERS_NAME, ORDER_ELEMENT, new Name("", "Tag")), 2, id("st2")),
            integer("slot2", "t2"),
            0,
            1,
            "slot3",
            "value3",
            new AttributeConstructor(
                "",
                new Name("", "lang"),
                new VarRef("value3", AtomicType.STRING, id("t2")),
                id("t2")),
            id("t2"));
    assertThat(((TargetSlots) document.content().getLast()).body())
        .isEqualTo(
            new ElementConstructor(
                "ns1",
                new Name("urn:c", "Note"),
                List.of(
                    lang,
                    new TextConstructor(
                        new VarRef("value2", AtomicType.STRING, id("t1")), id("t1"))),
                id("t1")));
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  /**
   * Prefixes follow the walk (ADR-0022 since #102): an element, then its attributes, then its
   * children. The root Doc has no namespace, so ns0 is the root's flag's urn:b, met before the
   * child Note's urn:c (ns1), which is met before Note's own ref in urn:d (ns2).
   */
  @Test
  void qualifiedAttributePrefixInTargetFieldOrder() {
    TargetDocument document =
        lowered(
            lowerTagged(
                NOTE_C + "/@Q{urn:d}ref",
                fromOrders("t3", "/Orders/Order/Tag", "/Orders/Order"),
                NOTE_C,
                fromOrders("t2", "/Orders/Order/Id", "/Orders"),
                FLAG,
                fromOrders("t1", "/Orders/Order/Id", "/Orders")));

    assertThat(document.prefix()).isEmpty();
    assertThat(document.content().getFirst())
        .isEqualTo(rootAttribute("ns0", new Name("urn:b", "flag"), orderIds("st1"), 1, "t1"));
    ElementConstructor note =
        (ElementConstructor) ((TargetSlots) document.content().getLast()).body();
    assertThat(note.prefix()).isEqualTo("ns1");
    assertThat(((TargetSlots) note.content().getFirst()).body())
        .isEqualTo(
            new AttributeConstructor(
                "ns2",
                new Name("urn:d", "ref"),
                new VarRef("value3", AtomicType.STRING, id("t3")),
                id("t3")));
  }

  /**
   * The root is no field to map, as a source or a target. Since #63 a source structure field lowers
   * to its queue of one {@code ""} per occurrence (a3, ADR-0008), and since #52 the target
   * structure field Address under the root lowers from a source leaf (t4).
   */
  @Test
  void theRootIsUnsupportedFieldAsASourceAndAsATarget() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t1", "a1", boxes("a1", source(ORDER))),
                ORDER,
                targetFieldMapping("t2", "a2", boxes("a2", source(ORDER_ID))),
                QUANTITY,
                targetFieldMapping("t3", "a3", boxes("a3", source(ADDRESS))),
                ADDRESS,
                targetFieldMapping("t4", "a4", boxes("a4", source(ORDER_ID)))));

    // until #63 a3, the source structure field Address, was unsupported-field too
    assertThat(refused(lower(model)))
        .containsExactly(
            errorAtId("unsupported-field", "a1"), errorAtId("unsupported-field", "t2"));

    TargetDocument document =
        lowered(
            lower(
                orderToOrder(
                    targetFieldMappings(
                        QUANTITY,
                        targetFieldMapping("t3", "a3", boxes("a3", source(ADDRESS))),
                        ORDER_ID,
                        targetFieldMapping("t1", "a1", boxes("a1", source(ORDER_ID)))))));
    assertThat(document.content())
        .map(e -> ((TargetSlots) e).queue())
        .containsExactly(
            new SourceQueue(List.of(ORDER_NAME, new Name("", "OrderId")), 1, false, id("a1")),
            new SourceQueue(List.of(ORDER_NAME, new Name("", "Address")), 1, true, id("a3")));
  }

  // --- target fields at any depth (ADR-0006 since #52): a mapped field under mapped ancestors;
  // the slot and value variables of a field at depth d are slot<d> and value<d>, the root's slot
  // number stays root

  private static final String GRP = "/Doc/Grp";
  private static final String ROW = GRP + "/Row";
  private static final String VAL = ROW + "/Val";
  private static final Name ORDERS_NAME = new Name("", "Orders");
  private static final Name ORDER_ELEMENT = new Name("", "Order");
  private static final Name LINE = new Name("", "Line");

  private static LowerResult lowerNested(Map<String, TargetFieldMapping> mappings) {
    return Lowering.lower(
        model(ref(TestStructures.orders()), ref(TestStructures.nested()), mappings, Map.of()),
        TestStructures.orders(),
        TestStructures.nested());
  }

  /** Grp per Order Id in the root's context, Row per Sku in Order's, Val per Qty in Line's. */
  private static Map<String, TargetFieldMapping> nestedMappings(boolean grp, boolean row) {
    Map<String, TargetFieldMapping> mappings = new java.util.LinkedHashMap<>();
    if (grp) {
      mappings.put(
          GRP, targetFieldMapping("t1", "a1", boxes("a1", source("/Orders/Order/Id", "/Orders"))));
    }
    if (row) {
      mappings.put(
          ROW,
          targetFieldMapping(
              "t2", "a2", boxes("a2", source("/Orders/Order/Line/Sku", "/Orders/Order"))));
    }
    mappings.put(
        VAL, targetFieldMapping("t3", "a3", boxes("a3", source("/Orders/Order/Line/Qty"))));
    return mappings;
  }

  private static VarRef integer(String name, String mapping) {
    return new VarRef(name, AtomicType.INTEGER, id(mapping));
  }

  /**
   * {@code Doc/Grp/Row/Val} with every level mapped: each TargetSlots stands in its parent's
   * element, reads its parent's slot number as its parentSlot and takes its field's effective
   * occurrence; a structure field's element holds its mapped children, a leaf's its value.
   */
  @Test
  void aLeafAtDepthFourLowersUnderItsMappedParents() {
    TargetDocument document = lowered(lowerNested(nestedMappings(true, true)));

    TargetSlots val =
        new TargetSlots(
            new SourceQueue(
                List.of(ORDERS_NAME, ORDER_ELEMENT, LINE, new Name("", "Qty")), 3, id("a3")),
            integer("slot3", "t3"),
            0,
            1,
            "slot4",
            "value4",
            new ElementConstructor(
                "",
                new Name("", "Val"),
                List.of(
                    new TextConstructor(
                        new VarRef("value4", AtomicType.STRING, id("t3")), id("t3"))),
                id("t3")),
            id("t3"));
    TargetSlots row =
        new TargetSlots(
            new SourceQueue(
                List.of(ORDERS_NAME, ORDER_ELEMENT, LINE, new Name("", "Sku")), 2, id("a2")),
            integer("slot2", "t2"),
            1,
            2,
            "slot3",
            "value3",
            new ElementConstructor("", new Name("", "Row"), List.of(val), id("t2")),
            id("t2"));
    TargetSlots grp =
        new TargetSlots(
            new SourceQueue(List.of(ORDERS_NAME, ORDER_ELEMENT, new Name("", "Id")), 1, id("a1")),
            integer("root", "t1"),
            0,
            Long.MAX_VALUE,
            "slot2",
            "value2",
            new ElementConstructor("", new Name("", "Grp"), List.of(row), id("t1")),
            id("t1"));
    assertThat(document)
        .isEqualTo(new TargetDocument("", new Name("", "Doc"), "root", List.of(grp)));
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  /**
   * O1 of the #52 design refused a mapped field under any unmapped parent until #63. Since #63 Val
   * under the unmapped Row (1..2) is still unsupported-field at its target field mapping, because
   * PO requires a mapping for {@code 0 < minOccurs < maxOccurs} (#2 §2). Since #91 the same compile
   * goes on to the target walk, which refuses the unmapped Row itself at its absent target field
   * mapping. With Grp unmapped too, Grp (0..unbounded) is optional and left out with Row and Val,
   * so the mapping compiles and creates nothing below the root.
   */
  @Test
  void aMappedFieldUnderAnUnmappedParentNeedingAMappingIsUnsupportedField() {
    // witness: with Row mapped the same Val lowers (aLeafAtDepthFourLowersUnderItsMappedParents)
    assertThat(lowerNested(nestedMappings(true, true))).isInstanceOf(Lowered.class);

    assertThat(refused(lowerNested(nestedMappings(true, false))))
        .containsExactly(
            errorAtPointer("unsupported-field", unmapped(ROW)),
            errorAtId("unsupported-field", "t3"));
    // until #63 this was unsupported-field at t3 too
    assertThat(lowered(lowerNested(nestedMappings(false, false))).content()).isEmpty();
  }

  /**
   * ADR-0006 creates only an unmapped {@code n..n} structure field and leaves out only an unmapped
   * optional one. With Grp alone mapped, the unmapped Row (1..2) has nothing mapped below it, and
   * PO needs a mapping there as when Val is mapped: unsupported-field, at its absent target field
   * mapping, as no authoring element names it. Leaving it out would write each Grp without the Row
   * it needs.
   */
  @Test
  void anUnmappedStructureFieldNeedingAMappingWithNothingBelowIsUnsupportedField() {
    Map<String, TargetFieldMapping> grpOnly = nestedMappings(true, true);
    grpOnly.remove(ROW);
    grpOnly.remove(VAL);
    // witness: with Row mapped too, the same Grp lowers
    assertThat(lowerNested(nestedMappings(true, true))).isInstanceOf(Lowered.class);

    assertThat(refused(lowerNested(grpOnly)))
        .containsExactly(errorAtPointer("unsupported-field", unmapped(ROW)));
  }

  /**
   * #63: the unmapped Pair (2..2) becomes TargetRepeat of 2 copies under the root's slot number,
   * whose slot-number variable at depth 2 is its child V's parentSlot. Its nodes carry its field
   * path, as no authoring element creates it. The unmapped None (0..0) is left out, and so is X
   * below it, mapped or not.
   */
  @Test
  void anUnmappedNByNStructureFieldLowersToTargetRepeat() {
    Map<String, TargetFieldMapping> mappings =
        targetFieldMappings(
            "/Doc/Pair/V",
            targetFieldMapping("t1", "a1", boxes("a1", source("/Orders/Order/Tag"))),
            "/Doc/None/X",
            targetFieldMapping("t2", "a2", boxes("a2", source("/Orders/Order/Id"))));

    TargetDocument document =
        lowered(
            Lowering.lower(
                model(
                    ref(TestStructures.orders()),
                    ref(TestStructures.repeated()),
                    mappings,
                    Map.of()),
                TestStructures.orders(),
                TestStructures.repeated()));

    AuthoringId pair = new AuthoringId("/Doc/Pair");
    TargetSlots v =
        new TargetSlots(
            new SourceQueue(List.of(ORDERS_NAME, ORDER_ELEMENT, new Name("", "Tag")), 2, id("a1")),
            integer("slot2", "t1"),
            0,
            Long.MAX_VALUE,
            "slot3",
            "value3",
            new ElementConstructor(
                "",
                new Name("", "V"),
                List.of(
                    new TextConstructor(
                        new VarRef("value3", AtomicType.STRING, id("t1")), id("t1"))),
                id("t1")),
            id("t1"));
    assertThat(document.content())
        .containsExactly(
            new TargetRepeat(
                2,
                new VarRef("root", AtomicType.INTEGER, pair),
                "slot2",
                new ElementConstructor("", new Name("", "Pair"), List.of(v), pair),
                pair));
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  /**
   * #83, ADR-0006 "generating function": a box with no input, {@code constant}, is an atomic {@code
   * FunCall} over its param as a {@code Literal}, no {@code Lift}. On a target it stands in a
   * {@code TargetRepeat} of {@code max(1, minOccurs)} copies whose origin is the target field
   * mapping, and the element's text is the call itself, so its value is computed once per copy.
   * OrderId is 1..1 and Note 0..unbounded: one copy each. Note's box leaves {@code value} out and
   * gets the default {@code ""}. The IR verifier accepts the document.
   */
  @Test
  void aZeroInputBoxLowersToAnAtomicFunCallUnderTargetRepeat() {
    TargetDocument document =
        lowered(
            lower(
                orderToOrder(
                    targetFieldMappings(
                        ORDER_ID,
                        targetFieldMapping(
                            "t1",
                            "c1",
                            boxes(
                                "c1",
                                function(
                                    "constant",
                                    inputs(),
                                    Models.params("value", Models.text("x"))))),
                        NOTE,
                        targetFieldMapping(
                            "t2",
                            "c2",
                            boxes("c2", function("constant", inputs(), noParams())))))));

    FunctionRef constant =
        new FunctionRef("constant", List.of(AtomicType.STRING), AtomicType.STRING);
    assertThat(document.content())
        .containsExactly(
            new TargetRepeat(
                1,
                new VarRef("root", AtomicType.INTEGER, id("t1")),
                "slot2",
                new ElementConstructor(
                    "",
                    new Name("", "OrderId"),
                    List.of(
                        new TextConstructor(
                            new FunCall(constant, List.of(new Literal("x", id("c1"))), id("c1")),
                            id("t1"))),
                    id("t1")),
                id("t1")),
            new TargetRepeat(
                1,
                new VarRef("root", AtomicType.INTEGER, id("t2")),
                "slot2",
                new ElementConstructor(
                    "",
                    new Name("", "Note"),
                    List.of(
                        new TextConstructor(
                            new FunCall(constant, List.of(new Literal("", id("c2"))), id("c2")),
                            id("t2"))),
                    id("t2")),
                id("t2")));
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  /**
   * #63: an unmapped 1..1 Self whose type requires Self again would be created without end.
   * ADR-0008 lets a recursive path repeat one type at most 16 times, so lowering refuses the 17th
   * Self with unsupported-field. No authoring element names an unmapped field, so the diagnostic
   * stands at the model's target structure, {@code /target}. A regression fails on the timeout, not
   * by hanging the build; the lowering takes milliseconds, and 2 s stays below PIT's own timeout,
   * so a mutant without the cap is killed here rather than timed out.
   */
  @Test
  void anUnmappedTypeThatRequiresItselfIsRefusedAtTheRecursionCap() {
    Map<String, TargetFieldMapping> mappings =
        targetFieldMappings(
            "/Doc/Self/V",
            targetFieldMapping("t1", "a1", boxes("a1", source("/Orders/Order/Id", "/Orders"))));

    LowerResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () ->
                Lowering.lower(
                    model(
                        ref(TestStructures.orders()),
                        ref(TestStructures.selfRequired()),
                        mappings,
                        Map.of()),
                    TestStructures.orders(),
                    TestStructures.selfRequired()));

    assertThat(refused(result))
        .containsExactly(LoadResults.error("unsupported-field", new AtPointer("/target")));
  }

  /** {@code fixDown} with the first {@code downs} Downs mapped, the k-th at /Doc(/Fix/Down)^k. */
  private static LowerResult lowerFixDown(int downs) {
    Map<String, TargetFieldMapping> mappings = new java.util.LinkedHashMap<>();
    for (int k = 1; k <= downs; k++) {
      mappings.put(
          "/Doc" + "/Fix/Down".repeat(k),
          targetFieldMapping("t" + k, "a" + k, boxes("a" + k, source("/Orders/Order/Id"))));
    }
    return Lowering.lower(
        model(ref(TestStructures.orders()), ref(TestStructures.fixDown()), mappings, Map.of()),
        TestStructures.orders(),
        TestStructures.fixDown());
  }

  /**
   * The recursion cap's boundary (ADR-0008, #63): under 15 mapped Downs the deepest unmapped Fix is
   * the 16th field of type /T on its path, which is allowed, so the mapping lowers. Under 16 the
   * deepest Fix is the 17th, refused at /target. The Downs, of the root's type, do not count toward
   * /T, nor does the root.
   */
  @Test
  void theRecursionCapAllowsSixteenRepeatsOfATypeAndRefusesTheSeventeenth() {
    assertThat(lowerFixDown(15)).isInstanceOf(Lowered.class);

    assertThat(refused(lowerFixDown(16)))
        .containsExactly(LoadResults.error("unsupported-field", new AtPointer("/target")));
  }

  /** The pointer of {@code unmapped-mandatory-leaf} at the target field {@code path} (#65). */
  private static String unmapped(String path) {
    return "/targetFieldMappings/" + path.replace("~", "~0").replace("/", "~1");
  }

  private static final String TILDE_LEAF = "/Doc/Q{" + TestStructures.TILDE + "}Tilde";

  /**
   * {@code mappings} from {@link TestStructures#orders()} to {@link TestStructures#mandatory()}.
   */
  private static LowerResult lowerMandatory(Object... pathThenMapping) {
    return Lowering.lower(
        model(
            ref(TestStructures.orders()),
            ref(TestStructures.mandatory()),
            targetFieldMappings(pathThenMapping),
            Map.of()),
        TestStructures.orders(),
        TestStructures.mandatory());
  }

  private static TargetFieldMapping orderId(String id) {
    return targetFieldMapping(id, "s" + id, boxes("s" + id, source("/Orders/Order/Id", "/Orders")));
  }

  /**
   * #65 (ADR-0006 Consequences): an unmapped leaf with {@code minOccurs > 0} is a design-time error
   * at its absent member of {@code targetFieldMappings}, under the root and under the unmapped 1..1
   * Head, which TargetRepeat creates; the walk goes on, so both are reported. The optional A and
   * Free stay silent, and so do Must under the unmapped optional Opt and Need under the unmapped
   * 0..unbounded Item, which are left out with their parents.
   */
  @Test
  void anUnmappedMandatoryLeafUnderACreatedParentIsUnmappedMandatoryLeaf() {
    assertThat(refused(lowerMandatory("/Doc/Free", orderId("t1"))))
        .containsExactly(
            errorAtPointer("unmapped-mandatory-leaf", unmapped("/Doc/Head/Req")),
            errorAtPointer("unmapped-mandatory-leaf", unmapped(TILDE_LEAF)));
    // RFC 6901: ~ is ~0 and / is ~1 in a segment
    assertThat(unmapped(TILDE_LEAF)).isEqualTo("/targetFieldMappings/~1Doc~1Q{urn:a~0b}Tilde");
  }

  /**
   * #65: under a mapped parent, Item, the unmapped Need is reported too, at its own field; once
   * Need, Req and Tilde are mapped the mapping lowers, and Opt is still left out.
   */
  @Test
  void anUnmappedMandatoryLeafUnderAMappedParentIsUnmappedMandatoryLeaf() {
    TargetFieldMapping item =
        targetFieldMapping("t1", "a1", boxes("a1", source("/Orders/Order", "/Orders")));

    assertThat(
            refused(
                lowerMandatory(
                    "/Doc/Head/Req", orderId("t2"), "/Doc/Item", item, TILDE_LEAF, orderId("t3"))))
        .containsExactly(errorAtPointer("unmapped-mandatory-leaf", unmapped("/Doc/Item/Need")));
    assertThat(
            lowered(
                    lowerMandatory(
                        "/Doc/Head/Req",
                        orderId("t2"),
                        "/Doc/Item",
                        item,
                        "/Doc/Item/Need",
                        orderId("t4"),
                        TILDE_LEAF,
                        orderId("t3")))
                .content())
        .extracting(Expr::origin)
        .containsExactly(new AuthoringId("/Doc/Head"), id("t1"), id("t3"));
  }

  // --- one-pass compile (#91, spec #89): lowering goes on past a target field mapping that fails,
  // emits nothing for it, and still applies the unmapped rules

  /** A target field mapping {@code id} whose box {@code s<id>} reads a field the source lacks. */
  private static TargetFieldMapping orphaned(String id) {
    return targetFieldMapping(
        id, "s" + id, boxes("s" + id, source("/Orders/Order/Missing", "/Orders")));
  }

  /**
   * The failing mapping of Free does not stop the walk: the unmapped mandatory Req under the
   * created Head and Tilde under the root are reported in the same compile.
   */
  @Test
  void aFailingTargetFieldMappingDoesNotHideTheUnmappedMandatoryLeaves() {
    // witness: with Free lowering, the same two leaves are reported
    assertThat(refused(lowerMandatory("/Doc/Free", orderId("t1"))))
        .containsExactly(
            errorAtPointer("unmapped-mandatory-leaf", unmapped("/Doc/Head/Req")),
            errorAtPointer("unmapped-mandatory-leaf", unmapped(TILDE_LEAF)));

    assertThat(refused(lowerMandatory("/Doc/Free", orphaned("t1"))))
        .containsExactly(
            errorAtPointer("unmapped-mandatory-leaf", unmapped("/Doc/Head/Req")),
            errorAtPointer("unmapped-mandatory-leaf", unmapped(TILDE_LEAF)),
            errorAtId("orphaned-reference", "st1"));
  }

  /** A mandatory leaf whose target field mapping fails is mapped: only the failure is reported. */
  @Test
  void aMandatoryLeafWhoseMappingFailsIsNotUnmapped() {
    assertThat(refused(lowerMandatory("/Doc/Head/Req", orphaned("t2"), TILDE_LEAF, orderId("t3"))))
        .containsExactly(errorAtId("orphaned-reference", "st2"));
  }

  /**
   * The walk goes on below a structure field whose target field mapping fails, as below a mapped
   * one: the unmapped mandatory Need under the failing Item is reported beside Item's failure.
   */
  @Test
  void theWalkGoesOnBelowAStructureFieldWhoseMappingFails() {
    // witness: with Item lowering, Need is reported alone
    TargetFieldMapping item =
        targetFieldMapping("t1", "a1", boxes("a1", source("/Orders/Order", "/Orders")));
    assertThat(
            refused(
                lowerMandatory(
                    "/Doc/Head/Req", orderId("t2"), "/Doc/Item", item, TILDE_LEAF, orderId("t3"))))
        .containsExactly(errorAtPointer("unmapped-mandatory-leaf", unmapped("/Doc/Item/Need")));

    assertThat(
            refused(
                lowerMandatory(
                    "/Doc/Head/Req",
                    orderId("t2"),
                    "/Doc/Item",
                    orphaned("t1"),
                    TILDE_LEAF,
                    orderId("t3"))))
        .containsExactly(
            errorAtPointer("unmapped-mandatory-leaf", unmapped("/Doc/Item/Need")),
            errorAtId("orphaned-reference", "st1"));
  }

  @Test
  void stage3ReportsEveryDiagnosticSorted() {
    // target field order meets f2, t1, a1; the report is sorted by location
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping(
                    "t3",
                    "f2",
                    boxes(
                        "f2",
                        function("rfcLookup", inputs("a5"), noParams()),
                        "a5",
                        source(ORDER_ID))),
                QUANTITY,
                targetFieldMapping("t1", null, boxes("a6", source(QUANTITY))),
                NOTE,
                targetFieldMapping("t2", "a1", boxes("a1", source(ORDER + "/Missing")))));

    assertThat(refused(lower(model)))
        .containsExactly(
            errorAtId("orphaned-reference", "a1"),
            errorAtId("unsupported-box-kind", "f2"),
            errorAtId("unconnected-target-field", "t1"));
  }

  // --- refused fields (ADR-0008 "Refused when a mapping uses the field" and its Shape (#92))

  /** {@code mappings} from {@link TestStructures#sameStep()} to itself. */
  private static LowerResult lowerSameStep(Object... pathThenMapping) {
    Structure structure = TestStructures.sameStep();
    return Lowering.lower(
        model(ref(structure), ref(structure), targetFieldMappings(pathThenMapping), Map.of()),
        structure,
        structure);
  }

  /**
   * Resolution matches a step below each same-step sibling in turn, and remembers the type and step
   * below which a path's rest failed (#89): a 40-step path through the twin {@code a} of {@link
   * TestStructures#twinSelf()}, ending in a step the structure lacks, is orphaned-reference within
   * the timeout, where trying every chain of siblings takes 2^38 tries. 2 s stays below PIT's own
   * timeout, so a mutant without the memo is killed here rather than timed out.
   */
  @Test
  void aLongPathThroughSameStepSiblingsOfOneTypeResolvesInPolynomialTime() {
    Structure structure = TestStructures.twinSelf();
    String missing = "/Doc" + "/a".repeat(38) + "/Missing";

    LowerResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () ->
                Lowering.lower(
                    model(
                        ref(structure),
                        ref(structure),
                        targetFieldMappings("/Doc/Id", copy("t1", missing)),
                        Map.of()),
                    structure,
                    structure));

    assertThat(refused(result)).containsExactly(errorAtId("orphaned-reference", "st1"));
  }

  /** A leaf holds no field: a path with a step below it is orphaned-reference. */
  @Test
  void aPathBelowALeafIsAnOrphanedReference() {
    Structure structure = TestStructures.twinSelf();

    LowerResult result =
        Lowering.lower(
            model(
                ref(structure),
                ref(structure),
                targetFieldMappings("/Doc/Id", copy("t1", "/Doc/Id/Below")),
                Map.of()),
            structure,
            structure);

    assertThat(refused(result)).containsExactly(errorAtId("orphaned-reference", "st1"));
  }

  /** A target field mapping {@code id} whose result is the source-field box {@code s<id>}. */
  private static TargetFieldMapping copy(String id, String sourceField) {
    return targetFieldMapping(id, "s" + id, boxes("s" + id, source(sourceField)));
  }

  /** The messages of a lowering refusal, in its order. */
  private static List<String> messages(LowerResult result) {
    assertThat(result).isInstanceOf(Refused.class);
    return ((Refused) result).diagnostics().stream().map(Diagnostic::message).toList();
  }

  /**
   * A source-field box on a refused field is ambiguous-field-path at the box, and a target field
   * mapping on one at the mapping, whose message names the schema file, line and column of the
   * step's second declaration.
   */
  @Test
  void aBoxOrTargetFieldMappingOnARefusedFieldIsAmbiguousFieldPathThere() {
    LowerResult source = lowerSameStep("/Doc/Id", copy("t1", "/Doc/Note"));
    LowerResult target = lowerSameStep("/Doc/Note", copy("t1", "/Doc/Id"));

    assertThat(refused(source)).containsExactly(errorAtId("ambiguous-field-path", "st1"));
    assertThat(refused(target)).containsExactly(errorAtId("ambiguous-field-path", "t1"));
    assertThat(messages(source)).hasSize(1);
    assertThat(messages(source).getFirst())
        .contains("/Doc/Note")
        .contains(TestStructures.SAME_STEP_FILE + " 8:65");
    assertThat(messages(target)).isEqualTo(messages(source));
  }

  /**
   * A path through a refused field is refused with the refusal nearest the root: B stands only
   * under the second Part, and is itself refused at 30:60, yet the box and the mapping name Part's
   * refusal at 28:53. A path that no sibling holds is orphaned-reference.
   */
  @Test
  void aPathThroughARefusedFieldIsRefusedWithTheRefusalNearestTheRoot() {
    LowerResult source = lowerSameStep("/Doc/Id", copy("t1", "/Doc/Body/Part/B"));
    LowerResult target = lowerSameStep("/Doc/Body/Part/B", copy("t1", "/Doc/Id"));

    assertThat(refused(source)).containsExactly(errorAtId("ambiguous-field-path", "st1"));
    assertThat(refused(target)).containsExactly(errorAtId("ambiguous-field-path", "t1"));
    for (LowerResult result : List.of(source, target)) {
      assertThat(messages(result)).hasSize(1);
      assertThat(messages(result).getFirst())
          .contains("/Doc/Body/Part ")
          .contains(TestStructures.SAME_STEP_FILE + " 28:53")
          .doesNotContain("30:60");
    }
    assertThat(refused(lowerSameStep("/Doc/Id", copy("t1", "/Doc/Body/Part/C"))))
        .containsExactly(errorAtId("orphaned-reference", "st1"));
  }

  /**
   * The target walk never enters a refused field. One a target field mapping names is reported at
   * that mapping only, and an unmapped one with {@code minOccurs > 0} under a created parent once
   * per path at its pointer, each sibling with its own occurrence: the Refs 0..1, 1..1, 1..1 (two
   * mandatory, one report) and the Parts 1..1 and 0..2 (the first alone mandatory, still reported).
   * No unmapped-mandatory-leaf or unsupported-field stands beside them.
   */
  @Test
  void theTargetWalkReportsAnUnmappedMandatoryRefusedFieldOnceAtItsPointer() {
    assertThat(
            refused(
                lowerSameStep(
                    "/Doc/Head", copy("t1", "/Doc/Head"), "/Doc/Head/Ref", copy("t2", "/Doc/Id"))))
        .containsExactly(errorAtId("ambiguous-field-path", "t2"));

    LowerResult unmapped =
        lowerSameStep("/Doc/Head", copy("t1", "/Doc/Head"), "/Doc/Body", copy("t2", "/Doc/Body"));
    assertThat(refused(unmapped))
        .containsExactly(
            errorAtPointer("ambiguous-field-path", unmapped("/Doc/Body/Part")),
            errorAtPointer("ambiguous-field-path", unmapped("/Doc/Head/Ref")));
    List<String> messages = messages(unmapped);
    assertThat(messages.get(0)).contains("/Doc/Body/Part").contains(" 28:53");
    assertThat(messages.get(1)).contains("/Doc/Head/Ref").contains(" 13:56");
  }

  /**
   * Refused fields that no mapping uses or must create are left out: the optional Notes beside the
   * mapped Code, and the optional Head and Body. The model lowers.
   */
  @Test
  void refusedFieldsThatNoMappingUsesLower() {
    TargetDocument document =
        lowered(
            lowerSameStep("/Doc/Id", copy("t1", "/Doc/Id"), "/Doc/Code", copy("t2", "/Doc/Code")));

    assertThat(document.content()).extracting(Expr::origin).containsExactly(id("t1"), id("t2"));
  }

  /** {@code mappings} from {@link TestStructures#abstractTarget()} to itself. */
  private static LowerResult lowerAbstract(Object... pathThenMapping) {
    Structure structure = TestStructures.abstractTarget();
    return Lowering.lower(
        model(ref(structure), ref(structure), targetFieldMappings(pathThenMapping), Map.of()),
        structure,
        structure);
  }

  /**
   * #95 (ADR-0008 "Refused when a mapping uses the field"): a target field mapping on an abstract
   * element, or on a path through one, is abstract-target-element at the mapping, its message
   * naming the abstract declaration's file, line and column; the refusal nearest the root wins.
   */
  @Test
  void aTargetFieldMappingOnAnAbstractElementIsAbstractTargetElementThere() {
    LowerResult shape = lowerAbstract("/Doc/Shape", copy("t1", "/Doc/Id"));
    LowerResult below = lowerAbstract("/Doc/Shape/Colour", copy("t1", "/Doc/Id"));

    for (LowerResult result : List.of(shape, below)) {
      assertThat(refused(result)).containsExactly(errorAtId("abstract-target-element", "t1"));
      assertThat(messages(result).getFirst())
          .contains("/Doc/Shape ")
          .contains(TestStructures.SHAPES_FILE + " 7:48")
          .doesNotContain("9:51");
    }
  }

  /**
   * An abstract element is refused in a target only: a source-field box reads one, or a field below
   * one, and the model lowers. A field below it that is refused for another reason is refused at
   * the box with that reason.
   */
  @Test
  void aSourceBoxOnAnAbstractElementLowersAndAnotherRefusalBelowItStands() {
    assertThat(lowerAbstract("/Doc/Id", copy("t1", "/Doc/Shape/Size"))).isInstanceOf(Lowered.class);
    assertThat(lowerAbstract("/Doc/Id", copy("t1", "/Doc/Shape"))).isInstanceOf(Lowered.class);

    LowerResult colour = lowerAbstract("/Doc/Id", copy("t1", "/Doc/Shape/Colour"));
    assertThat(refused(colour)).containsExactly(errorAtId("ambiguous-field-path", "st1"));
    assertThat(messages(colour).getFirst()).contains(TestStructures.SHAPES_FILE + " 9:51");
  }

  /**
   * The target walk never enters an abstract element: an unmapped mandatory one under a created
   * parent is abstract-target-element at its pointer, instead of unmapped-mandatory-leaf; an
   * unmapped optional one is left out.
   */
  @Test
  void anUnmappedMandatoryAbstractElementIsAbstractTargetElementAtItsPointer() {
    LowerResult result = lowerAbstract("/Doc/Box", copy("t1", "/Doc/Box"));

    assertThat(refused(result))
        .containsExactly(errorAtPointer("abstract-target-element", unmapped("/Doc/Box/Need")));
    assertThat(messages(result).getFirst())
        .contains("/Doc/Box/Need")
        .contains(TestStructures.SHAPES_FILE + " 21:61");
  }

  // --- a repeated group of two or more particles (#94, ADR-0008 "in a target"): refused only
  // when a mapping must write a field of it

  /** {@code mappings} from {@code source} to {@link TestStructures#interleaved()}. */
  private static LowerResult lowerInterleaved(Structure source, Object... pathThenMapping) {
    Structure target = TestStructures.interleaved();
    return Lowering.lower(
        model(ref(source), ref(target), targetFieldMappings(pathThenMapping), Map.of()),
        source,
        target);
  }

  /**
   * A target field mapping on a field of a repeated group is interleaved-target-group at the
   * mapping, whose message names the schema file, line and column of the repeated group's
   * declaration. The optional B beside it is left out.
   */
  @Test
  void aTargetFieldMappingOnAFieldOfARepeatedGroupIsInterleavedTargetGroupThere() {
    LowerResult result =
        lowerInterleaved(
            TestStructures.interleaved(),
            "/Doc/Id",
            copy("t1", "/Doc/Id"),
            "/Doc/A",
            copy("t2", "/Doc/Id"));

    assertThat(refused(result)).containsExactly(errorAtId("interleaved-target-group", "t2"));
    assertThat(messages(result).getFirst())
        .contains("/Doc/A")
        .contains(TestStructures.INTERLEAVED_FILE + " 20:44");
  }

  /**
   * An unmapped field of a repeated group with {@code minOccurs > 0} must be written, so it is
   * interleaved-target-group at its pointer, instead of unmapped-mandatory-leaf; the optional B is
   * not.
   */
  @Test
  void anUnmappedMandatoryFieldOfARepeatedGroupIsInterleavedTargetGroupAtItsPointer() {
    LowerResult result =
        lowerInterleaved(TestStructures.interleaved(), "/Doc/Id", copy("t1", "/Doc/Id"));

    assertThat(refused(result))
        .containsExactly(errorAtPointer("interleaved-target-group", unmapped("/Doc/A")));
  }

  /**
   * The refusal holds in a target only: a source-field box on a field of a repeated group lowers
   * (ADR-0008 "in a target").
   */
  @Test
  void aSourceBoxOnAFieldOfARepeatedGroupLowers() {
    Structure target = TestStructures.order();
    Structure source = TestStructures.interleaved();
    MappingModel model =
        model(
            ref(source),
            ref(target),
            targetFieldMappings(ORDER + "/OrderId", copy("t1", "/Doc/A")),
            Map.of());

    TargetDocument document = lowered(Lowering.lower(model, source, target));

    TargetSlots orderId = only(document.content(), TargetSlots.class);
    assertThat(orderId.origin()).isEqualTo(id("t1"));
  }

  /** The one expression of {@code content}, which is a {@code type}. */
  private static <T extends Expr> T only(List<Expr> content, Class<T> type) {
    assertThat(content).hasSize(1).first().isInstanceOf(type);
    return type.cast(content.getFirst());
  }

  /**
   * Same-step siblings that carry no refusal, which the reader never gives, do not break lowering
   * (#92): a path only the second Part holds resolves to that Part's field, as a source and as a
   * target. B's TargetSlots stands in the TargetRepeat of the second Part, 1..1, the optional first
   * Part is left out, and B's occurrence is the second Part's B's. A path no sibling holds is
   * orphaned-reference.
   */
  @Test
  void aPathOnlyTheSecondOfUnrefusedSameStepSiblingsHoldsResolvesToIt() {
    Structure structure = TestStructures.unrefusedSiblings();
    MappingModel model =
        model(
            ref(structure),
            ref(structure),
            targetFieldMappings(
                "/Doc/Body",
                copy("t1", "/Doc/Body"),
                "/Doc/Body/Part/B",
                copy("t2", "/Doc/Body/Part/B")),
            Map.of());

    TargetDocument document = lowered(Lowering.lower(model, structure, structure));

    TargetSlots body = only(document.content(), TargetSlots.class);
    TargetRepeat part = only(((ElementConstructor) body.body()).content(), TargetRepeat.class);
    TargetSlots b = only(((ElementConstructor) part.body()).content(), TargetSlots.class);
    assertThat(part.origin()).isEqualTo(new AuthoringId("/Doc/Body/Part"));
    assertThat(part.copies()).isEqualTo(1);
    assertThat(b.origin()).isEqualTo(id("t2"));
    assertThat(b.queue())
        .isEqualTo(
            new SourceQueue(
                List.of(
                    new Name("", "Doc"),
                    new Name("", "Body"),
                    new Name("", "Part"),
                    new Name("", "B")),
                3,
                false,
                id("st2")));
    assertThat(Lowering.occurrences(model, structure))
        .containsEntry(id("t2"), new Occurrence(0, 1));
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();

    MappingModel orphaned =
        model(
            ref(structure),
            ref(structure),
            targetFieldMappings(
                "/Doc/Body",
                copy("t1", "/Doc/Body/Part/C"),
                "/Doc/Body/Part/C",
                copy("t2", "/Doc/Body")),
            Map.of());
    assertThat(refused(Lowering.lower(orphaned, structure, structure)))
        .containsExactly(
            errorAtId("orphaned-reference", "st1"), errorAtId("orphaned-reference", "t2"));
  }
}
