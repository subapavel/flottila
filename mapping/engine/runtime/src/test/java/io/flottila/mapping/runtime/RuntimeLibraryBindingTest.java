package io.flottila.mapping.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AtomicType;
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
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The interpreter binds a call by its catalogue name once, when it compiles (ADR-0025), and a
 * {@code Literal} is its constant value (ADR-0006). The IR is built by hand, one target field
 * {@code Note} under the root over {@code Note} in the root's context.
 */
class RuntimeLibraryBindingTest {

  /** The time every run of this class starts at; no function here reads it. */
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-04T12:00:00Z");

  private static final String NS = "urn:flottila:corpus:skeleton";
  private static final Name ORDER = new Name(NS, "Order");
  private static final Name NOTE = new Name("", "Note");
  private static final AuthoringId T1 = new AuthoringId("t1");
  private static final QueueType QUEUE = new QueueType();

  /** {@code <Note>{text}</Note>} per slot of {@code queue}'s context 1. */
  private static TargetDocument notes(Expr queue, Expr text) {
    return new TargetDocument(
        "ns0",
        ORDER,
        "root",
        List.of(
            new TargetSlots(
                queue,
                new VarRef("root", AtomicType.INTEGER, T1),
                0,
                Long.MAX_VALUE,
                "slot",
                "v",
                new ElementConstructor("", NOTE, List.of(new TextConstructor(text, T1)), T1),
                T1)));
  }

  private static SourceQueue sourceNotes() {
    return new SourceQueue(List.of(ORDER, NOTE), 1, new AuthoringId("a1"));
  }

  @Test
  void aFunctionTheRuntimeLibraryLacksIsACompilerBug() {
    FunCall unknown =
        new FunCall(
            new FunctionRef("noSuchFunction", List.of(QUEUE), QUEUE),
            List.of(sourceNotes()),
            new AuthoringId("f1"));
    TargetDocument document = notes(unknown, new VarRef("v", AtomicType.STRING, T1));
    // witness: the IR is well-formed, and binding is what refuses it
    IrVerifier.verify(document);

    assertThatThrownBy(() -> Interpreter.compile(document, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("noSuchFunction");
  }

  static Stream<Arguments> modes() {
    return Stream.of(
        Arguments.of("eachValue", "<Note>x</Note>"),
        Arguments.of("valueChange", "<Note>x</Note><Note>x</Note>"),
        Arguments.of("emptyValue", "<Note>x</Note><Note>x</Note><Note>y</Note>"));
  }

  /**
   * The mode param selects the function the call binds. {@code Note} x, x, y in the root's context
   * is one context, and the root's slot reads context 1 of the call's queue.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("modes")
  void theModeParamSelectsTheSplit(String mode, String notes) throws Exception {
    AuthoringId box = new AuthoringId("f1");
    FunCall split =
        new FunCall(
            new FunctionRef("splitByValue", List.of(QUEUE, AtomicType.STRING), QUEUE),
            List.of(sourceNotes(), new Literal(mode, box)),
            box);
    TargetDocument document = notes(split, new VarRef("v", AtomicType.STRING, T1));
    IrVerifier.verify(document);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String input =
        "<s:Order xmlns:s=\"" + NS + "\"><Note>x</Note><Note>x</Note><Note>y</Note></s:Order>";

    Interpreter.compile(document, false)
        .run(new ByteArrayInputStream(input.getBytes(UTF_8)), out, new Tracer(), NOW);

    assertThat(out.toString(UTF_8))
        .isEqualTo(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ns0:Order xmlns:ns0=\""
                + NS
                + "\">"
                + notes
                + "</ns0:Order>");
  }

  /**
   * A param is parsed once, when the call binds, so a value lowering would refuse is caught there.
   */
  @Test
  void aParamValueTheFunctionLacksIsACompilerBug() {
    AuthoringId box = new AuthoringId("f1");
    FunCall split =
        new FunCall(
            new FunctionRef("splitByValue", List.of(QUEUE, AtomicType.STRING), QUEUE),
            List.of(sourceNotes(), new Literal("everyOtherValue", box)),
            box);
    TargetDocument document = notes(split, new VarRef("v", AtomicType.STRING, T1));
    // witness: the IR is well-formed, and binding is what refuses it
    IrVerifier.verify(document);

    assertThatThrownBy(() -> Interpreter.compile(document, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("everyOtherValue");
  }

  /** {@code Order/<steps>} read in the context {@code depth} steps from the root. */
  private static SourceQueue source(int depth, String box, String... steps) {
    List<Name> names = new ArrayList<>(List.of(ORDER));
    for (String step : steps) {
      names.add(new Name("", step));
    }
    return new SourceQueue(names, depth, new AuthoringId(box));
  }

  /** Two Ids in the root's context, Code in Item's: A | B, and Name in Item's: x1, x2 | y. */
  private static final String ITEMS =
      "<s:Order xmlns:s=\""
          + NS
          + "\"><Id>A</Id><Id>B</Id><Item><Code>A</Code><Name>x1</Name><Name>x2</Name></Item>"
          + "<Item><Code>B</Code><Name>y</Name></Item></s:Order>";

  /** The output of {@link #notes} over {@code queue} for the input {@link #ITEMS}. */
  private static String runOverItems(FunCall queue) throws Exception {
    TargetDocument document = notes(queue, new VarRef("v", AtomicType.STRING, T1));
    IrVerifier.verify(document);
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    Interpreter.compile(document, false)
        .run(new ByteArrayInputStream(ITEMS.getBytes(UTF_8)), out, new Tracer(), NOW);

    return out.toString(UTF_8);
  }

  private static String notesOf(String... values) {
    StringBuilder notes = new StringBuilder();
    for (String value : values) {
      notes.append("<Note>").append(value).append("</Note>");
    }
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ns0:Order xmlns:ns0=\""
        + NS
        + "\">"
        + notes
        + "</ns0:Order>";
  }

  @Test
  void collapseContextsIsBoundByName() throws Exception {
    FunCall collapse =
        new FunCall(
            new FunctionRef("collapseContexts", List.of(QUEUE), QUEUE),
            List.of(source(2, "a1", "Item", "Name")),
            new AuthoringId("f1"));

    // Name in Item's context is x1, x2 | y; the root's one slot reads the collapsed context
    assertThat(runOverItems(collapse)).isEqualTo(notesOf("x1", "y"));
  }

  /**
   * The args bind in catalogue order, values then example (#58): Name in the root's context, x1,
   * x2, y, cut at the context changes of Name in Item's, x1, x2 | y, so the root's one slot reads
   * x1, x2. The args swapped would give x1, x2, y in one context.
   */
  @Test
  void formatByExampleIsBoundByNameWithItsArgsInOrder() throws Exception {
    FunCall formatByExample =
        new FunCall(
            new FunctionRef("formatByExample", List.of(QUEUE, QUEUE), QUEUE),
            List.of(source(1, "a1", "Item", "Name"), source(2, "a2", "Item", "Name")),
            new AuthoringId("f1"));

    assertThat(runOverItems(formatByExample)).isEqualTo(notesOf("x1", "x2"));
  }

  /**
   * The args bind in catalogue order: value, occurrences, contexts. Code in Item's context has one
   * value per context of Name in Item's, as useOneAsMany needs (F-48).
   */
  @Test
  void useOneAsManyIsBoundByNameWithItsArgsInOrder() throws Exception {
    FunCall useOneAsMany =
        new FunCall(
            new FunctionRef("useOneAsMany", List.of(QUEUE, QUEUE, QUEUE), QUEUE),
            List.of(
                source(2, "a1", "Item", "Code"),
                source(2, "a2", "Item", "Name"),
                source(1, "a3", "Item", "Name")),
            new AuthoringId("f1"));

    // A once per Name of the first Item, B once per Name of the second, in one context
    assertThat(runOverItems(useOneAsMany)).isEqualTo(notesOf("A", "A", "B"));
  }

  /**
   * ADR-0026 (F-19): a data error names the box that raised it, its message starting with {@code
   * the function box <id>: }, as the export's does. Name in the root's context, x1, x2, y, is one
   * context for the two of Name in Item's: po:USEONEASMANY at u7 (F-48). Inside removeContexts at
   * r1 the error is still u7's, and named once.
   */
  @Test
  void aDataErrorNamesItsBox() {
    FunCall useOneAsMany =
        new FunCall(
            new FunctionRef("useOneAsMany", List.of(QUEUE, QUEUE, QUEUE), QUEUE),
            List.of(
                source(1, "a1", "Item", "Name"),
                source(2, "a2", "Item", "Name"),
                source(1, "a3", "Item", "Name")),
            new AuthoringId("u7"));
    FunCall around =
        new FunCall(
            new FunctionRef("removeContexts", List.of(QUEUE), QUEUE),
            List.of(useOneAsMany),
            new AuthoringId("r1"));
    String message =
        "the function box u7: useOneAsMany needs as many contexts in its first input as in its"
            + " second: the first has 1 contexts, the second 2";

    for (FunCall call : List.of(useOneAsMany, around)) {
      assertThatThrownBy(() -> runOverItems(call))
          .as("%s", call.function().name())
          .isInstanceOfSatisfying(
              MappingRunException.class,
              e -> {
                assertThat(e.code()).isEqualTo("po:USEONEASMANY");
                assertThat(e.authoringId()).isEqualTo(new AuthoringId("u7"));
              })
          .hasMessage(message);
    }
  }

  @Test
  void aLiteralIsItsValueInEverySlot() throws Exception {
    TargetDocument document = notes(sourceNotes(), new Literal("fixed", T1));
    IrVerifier.verify(document);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String input = "<s:Order xmlns:s=\"" + NS + "\"><Note>x</Note><Note>y</Note></s:Order>";

    Interpreter.compile(document, false)
        .run(new ByteArrayInputStream(input.getBytes(UTF_8)), out, new Tracer(), NOW);

    assertThat(out.toString(UTF_8))
        .isEqualTo(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ns0:Order xmlns:ns0=\""
                + NS
                + "\"><Note>fixed</Note><Note>fixed</Note></ns0:Order>");
  }

  // --- sort and sortByKey (#59): the params comparison, then order, each parsed once

  private static final FunctionRef SORT =
      new FunctionRef("sort", List.of(QUEUE, AtomicType.STRING, AtomicType.STRING), QUEUE);
  private static final FunctionRef SORT_BY_KEY =
      new FunctionRef(
          "sortByKey", List.of(QUEUE, QUEUE, AtomicType.STRING, AtomicType.STRING), QUEUE);

  /** The output of {@link #notes} over {@code queue} for {@code Order} with {@code children}. */
  private static String run(FunCall queue, String children) throws Exception {
    TargetDocument document = notes(queue, new VarRef("v", AtomicType.STRING, T1));
    IrVerifier.verify(document);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String input = "<s:Order xmlns:s=\"" + NS + "\">" + children + "</s:Order>";

    Interpreter.compile(document, false)
        .run(new ByteArrayInputStream(input.getBytes(UTF_8)), out, new Tracer(), NOW);

    return out.toString(UTF_8);
  }

  private static String noteElements(String... values) {
    StringBuilder notes = new StringBuilder();
    for (String value : values) {
      notes.append("<Note>").append(value).append("</Note>");
    }
    return notes.toString();
  }

  private static FunCall sort(String comparison, String order) {
    AuthoringId box = new AuthoringId("f1");
    return new FunCall(
        SORT, List.of(sourceNotes(), new Literal(comparison, box), new Literal(order, box)), box);
  }

  static Stream<Arguments> sorts() {
    String[] text = {"b", "B", "10", "9", "a"};
    String[] numbers = {"10", "9", "-1"};
    return Stream.of(
        Arguments.of("lexicographic", "ascending", text, new String[] {"10", "9", "B", "a", "b"}),
        Arguments.of("lexicographic", "descending", text, new String[] {"b", "a", "B", "9", "10"}),
        // b and B are equal case-insensitively and keep their input order
        Arguments.of("caseInsensitive", "ascending", text, new String[] {"10", "9", "a", "b", "B"}),
        Arguments.of("numeric", "ascending", numbers, new String[] {"-1", "9", "10"}),
        Arguments.of("numeric", "descending", numbers, new String[] {"10", "9", "-1"}));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("sorts")
  void theParamsOfSortSelectItsComparisonAndOrder(
      String comparison, String order, String[] notes, String[] sorted) throws Exception {
    assertThat(run(sort(comparison, order), noteElements(notes))).isEqualTo(notesOf(sorted));
  }

  /**
   * The keys bind first: Id 2, 1 orders Note x, y as y, x, where the other way round gives 2, 1.
   */
  @Test
  void sortByKeyIsBoundByNameWithItsKeysFirst() throws Exception {
    AuthoringId box = new AuthoringId("f1");
    List<Expr> params = List.of(new Literal("numeric", box), new Literal("ascending", box));
    List<Expr> keysThenValues = new ArrayList<>(List.of(source(1, "a2", "Id"), sourceNotes()));
    keysThenValues.addAll(params);
    String children = "<Id>2</Id><Id>1</Id>" + noteElements("x", "y");

    assertThat(run(new FunCall(SORT_BY_KEY, keysThenValues, box), children))
        .isEqualTo(notesOf("y", "x"));
    // witness: with Note as the keys, x before y, the Ids keep their order
    assertThat(
            run(
                new FunCall(
                    SORT_BY_KEY,
                    List.of(
                        sourceNotes(),
                        source(1, "a2", "Id"),
                        new Literal("lexicographic", box),
                        new Literal("ascending", box)),
                    box),
                children))
        .isEqualTo(notesOf("2", "1"));
  }

  static Stream<Arguments> paramValuesTheSortsLack() {
    AuthoringId box = new AuthoringId("f1");
    return Stream.of(
        Arguments.of(sort("caseSensitive", "ascending"), "caseSensitive"),
        Arguments.of(sort("lexicographic", "desc"), "desc"),
        Arguments.of(
            new FunCall(
                SORT_BY_KEY,
                List.of(
                    sourceNotes(),
                    sourceNotes(),
                    new Literal("numerical", box),
                    new Literal("ascending", box)),
                box),
            "numerical"),
        Arguments.of(
            new FunCall(
                SORT_BY_KEY,
                List.of(
                    sourceNotes(),
                    sourceNotes(),
                    new Literal("numeric", box),
                    new Literal("Descending", box)),
                box),
            "Descending"));
  }

  /** A param is parsed once, when the call binds, so a value lowering would refuse is caught. */
  @ParameterizedTest(name = "{1}")
  @MethodSource("paramValuesTheSortsLack")
  void aSortParamValueTheFunctionLacksIsACompilerBug(FunCall call, String value) {
    TargetDocument document = notes(call, new VarRef("v", AtomicType.STRING, T1));
    // witness: the IR is well-formed, and binding is what refuses it
    IrVerifier.verify(document);

    assertThatThrownBy(() -> Interpreter.compile(document, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(call.function().name())
        .hasMessageContaining(value);
  }

  // --- exists and mapWithDefault (#57): per context of their input, mapWithDefault's param is the
  // default; removeContexts puts every context under the root's one slot

  /** Name in Item's context: x1, x2 | (empty) | y. */
  private static final String NAMES =
      "<Item><Name>x1</Name><Name>x2</Name></Item><Item/><Item><Name>y</Name></Item>";

  private static FunCall removeContexts(Expr queue) {
    return new FunCall(
        new FunctionRef("removeContexts", List.of(QUEUE), QUEUE),
        List.of(queue),
        new AuthoringId("r1"));
  }

  /** One true per Name and one false for the empty Item's context (F-48). */
  @Test
  void existsIsBoundByName() throws Exception {
    FunCall exists =
        new FunCall(
            new FunctionRef("exists", List.of(QUEUE), QUEUE),
            List.of(source(2, "a1", "Item", "Name")),
            new AuthoringId("f1"));

    assertThat(run(removeContexts(exists), NAMES))
        .isEqualTo(notesOf("true", "true", "false", "true"));
  }

  /** The param binds as the default: the empty Item's context gets it. */
  @Test
  void mapWithDefaultIsBoundByNameWithItsDefault() throws Exception {
    AuthoringId box = new AuthoringId("f1");
    FunCall mapWithDefault =
        new FunCall(
            new FunctionRef("mapWithDefault", List.of(QUEUE, AtomicType.STRING), QUEUE),
            List.of(source(2, "a1", "Item", "Name"), new Literal("none", box)),
            box);

    assertThat(run(removeContexts(mapWithDefault), NAMES))
        .isEqualTo(notesOf("x1", "x2", "none", "y"));
  }

  /**
   * replaceValue is a queue function since #216 (F-45): its param binds as the replacement at every
   * slot, and the empty Item's context stays empty, so it adds no Note.
   */
  @Test
  void replaceValueIsBoundByNameWithItsReplacement() throws Exception {
    AuthoringId box = new AuthoringId("f1");
    FunCall replaceValue =
        new FunCall(
            new FunctionRef("replaceValue", List.of(QUEUE, AtomicType.STRING), QUEUE),
            List.of(source(2, "a1", "Item", "Name"), new Literal("r", box)),
            box);

    assertThat(run(removeContexts(replaceValue), NAMES)).isEqualTo(notesOf("r", "r", "r"));
  }
}
