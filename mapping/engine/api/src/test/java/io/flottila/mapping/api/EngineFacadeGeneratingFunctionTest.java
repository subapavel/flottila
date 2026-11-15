package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generating functions at the engine facade (#83; ADR-0006 "generating function" as amended by #83;
 * GLOSSARY "Generating function"): a function graph with no source field, {@code constant} or
 * {@code concat} of two constants, gives {@code max(1, minOccurs)} occurrences of its target field
 * per parent occurrence, whatever {@code maxOccurs} is, its children numbered {@code (p - 1) *
 * copies + j}. Inside a single-value function its value applies to every slot of every context; at
 * a context function's input it is one context with one value.
 *
 * <p>The counts are documented: "If minOccurs = 0 the value of the function is generated once.
 * Otherwise, the value of the function is generated as often as is specified by minOccurs", "even
 * if maxOccurs = unbounded" (SAP, Mapping Fields Within a Context, #2 §1.1). The rest are assumed
 * readings with rows in {@code obs-constant}. Expected outputs are worked out by hand from those
 * rules. Source: the observation kit's {@code src-orders.xsd}; targets: {@code tgt-list.xsd},
 * {@code tgt-gen.xsd} and the small schemas below.
 */
class EngineFacadeGeneratingFunctionTest {

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final String FINGERPRINT = "sha256:" + "0".repeat(64);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final int LIMIT = 10_000;
  private static final long UNBOUNDED = Long.MAX_VALUE;

  /** Two optional leaves under the root: {@code Opt} 0..1 and {@code Many} 0..unbounded. */
  private static final String OPTIONAL_XSD =
      """
      <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
        <xs:element name="Out">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="Opt" type="xs:string" minOccurs="0" maxOccurs="1"/>
              <xs:element name="Many" type="xs:string" minOccurs="0" maxOccurs="unbounded"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:schema>
      """;

  /**
   * Leaves under the root with {@code minOccurs} 1, 2 and 3 against {@code maxOccurs} equal, above
   * and unbounded: {@code One} 1..1, {@code Two} 2..3, {@code TwoUp} 2..unbounded, {@code Three}
   * 3..3.
   */
  private static final String MINIMUM_XSD =
      """
      <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
        <xs:element name="Out">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="One" type="xs:string" minOccurs="1" maxOccurs="1"/>
              <xs:element name="Two" type="xs:string" minOccurs="2" maxOccurs="3"/>
              <xs:element name="TwoUp" type="xs:string" minOccurs="2" maxOccurs="unbounded"/>
              <xs:element name="Three" type="xs:string" minOccurs="3" maxOccurs="3"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:schema>
      """;

  /** {@code Out/Grp} 0..unbounded holding the leaf {@code L} 2..3. */
  private static final String GROUPS_XSD =
      """
      <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
        <xs:element name="Out">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="Grp" minOccurs="0" maxOccurs="unbounded">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="L" type="xs:string" minOccurs="2" maxOccurs="3"/>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:schema>
      """;

  private final EngineFacade facade = new EngineFacade();

  // --- models and inputs

  private static Path observations() {
    return CorpusStructures.directory("observations");
  }

  /** {@code base} with the kit's source schema and {@code xsd} as {@code target.xsd}. */
  private static Path withTarget(Path base, String xsd) throws Exception {
    Files.copy(observations().resolve("src-orders.xsd"), base.resolve("src-orders.xsd"));
    Files.writeString(base.resolve("target.xsd"), xsd, UTF_8);
    return base;
  }

  /** A model from {@code src-orders.xsd} to {@code target} under {@code root}. */
  private static byte[] model(String target, String root, String targetFieldMappings) {
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["src-orders.xsd"], "fingerprint": "%1$s",
                    "root": {"element": "Orders"}},
         "target": {"bundle": ["%2$s"], "fingerprint": "%1$s", "root": {"element": "%3$s"}},
         "targetFieldMappings": {%4$s}}
        """
        .formatted(FINGERPRINT, target, root, targetFieldMappings)
        .getBytes(UTF_8);
  }

  /** The target field mapping of {@code field}: id {@code id}, result {@code result}, boxes. */
  private static String mapping(String field, String id, String result, String boxes) {
    return """
        "%s": {"id": "%s", "result": "%s", "boxes": {%s}}"""
        .formatted(field, id, result, boxes);
  }

  /** A constant box {@code id} with the value {@code value}. */
  private static String constant(String id, String value) {
    return """
        "%s": {"kind": "function", "fn": "constant", "params": {"value": "%s"}}"""
        .formatted(id, value);
  }

  /** {@code Orders/Order -> Out/Item} (or another target) by its box S0, id T0. */
  private static String orderOnto(String field) {
    return mapping(
        field,
        "T0",
        "S0",
        "\"S0\": {\"kind\": \"source\", \"field\": \"/Orders/Order\", \"context\": \"/Orders\"}");
  }

  private static String orders(String... orders) {
    return "<Orders>" + String.join("", orders) + "</Orders>";
  }

  private static String order(String... content) {
    return "<Order>" + String.join("", content) + "</Order>";
  }

  private static String line(String sku, String qty) {
    return "<Line>"
        + (sku == null ? "" : "<Sku>" + sku + "</Sku>")
        + (qty == null ? "" : "<Qty>" + qty + "</Qty>")
        + "</Line>";
  }

  private CompiledMapping compiled(byte[] model, Path base, CompileOptions options)
      throws Exception {
    CompileResult result =
        facade.compile(Fingerprints.refreshed(model, base), DirectoryResolver.over(base), options);
    if (result instanceof CompileResult.Compiled(CompiledMapping mapping)) {
      return mapping;
    }
    throw new AssertionError("expected Compiled, got " + result);
  }

  /** The output of {@code input}, the same as deployed and as test-run compiled. */
  private String run(byte[] model, Path base, String input) throws Exception {
    String deployed =
        new String(
            facade.run(compiled(model, base, CompileOptions.DEPLOY), input.getBytes(UTF_8)), UTF_8);
    String tested =
        new String(
            facade.run(compiled(model, base, CompileOptions.TEST_RUN), input.getBytes(UTF_8)),
            UTF_8);
    assertThat(tested).as("test-run compiled against deployed").isEqualTo(deployed);
    return deployed;
  }

  private static final String THREE_ORDERS =
      orders(
          order("<Id>o1</Id>", line("a", "1"), line("b", "2")),
          order("<Id>o2</Id>"),
          order("<Id>o3</Id>", line("c", "3")));

  // --- the counts on a target field (documented)

  /**
   * minOccurs 0: one occurrence, for 0..1 and for 0..unbounded, whether the source has orders or
   * none at all: a generating function reads no source field.
   */
  @Test
  void aGeneratingFunctionOnAnOptionalFieldGivesOneOccurrence(@TempDir Path base) throws Exception {
    byte[] model =
        model(
            "target.xsd",
            "Out",
            mapping("/Out/Opt", "T1", "C1", constant("C1", "x"))
                + ", "
                + mapping("/Out/Many", "T2", "C2", constant("C2", "y")));
    Path schemas = withTarget(base, OPTIONAL_XSD);

    for (String input : List.of(THREE_ORDERS, "<Orders/>")) {
      assertThat(run(model, schemas, input))
          .as("input %s", input)
          .isEqualTo(DECLARATION + "<Out><Opt>x</Opt><Many>y</Many></Out>");
    }
  }

  /** minOccurs n > 0: n occurrences, whether maxOccurs is n, above n or unbounded. */
  @Test
  void aGeneratingFunctionGivesMinOccursCopiesWhateverMaxOccursIs(@TempDir Path base)
      throws Exception {
    byte[] model =
        model(
            "target.xsd",
            "Out",
            String.join(
                ", ",
                mapping("/Out/One", "T1", "C1", constant("C1", "1")),
                mapping("/Out/Two", "T2", "C2", constant("C2", "2")),
                mapping("/Out/TwoUp", "T3", "C3", constant("C3", "u")),
                mapping("/Out/Three", "T4", "C4", constant("C4", "3"))));

    assertThat(run(model, withTarget(base, MINIMUM_XSD), THREE_ORDERS))
        .isEqualTo(
            DECLARATION
                + "<Out><One>1</One><Two>2</Two><Two>2</Two><TwoUp>u</TwoUp><TwoUp>u</TwoUp>"
                + "<Three>3</Three><Three>3</Three><Three>3</Three></Out>");
  }

  // --- assumed readings (obs-constant)

  /** Item per Order, V from a constant: every Item, the one without lines too, gets one V. */
  @Test
  void aGeneratingFunctionRepeatsUnderEachParentOccurrence() throws Exception {
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            orderOnto("/Out/Item")
                + ", "
                + mapping("/Out/Item/V", "T1", "C1", constant("C1", "x")));

    assertThat(run(model, observations(), THREE_ORDERS))
        .isEqualTo(
            DECLARATION
                + "<Out><Item><V>x</V></Item><Item><V>x</V></Item><Item><V>x</V></Item></Out>");
  }

  /**
   * concat of Sku (context Order: a, b | (empty) | c) with a constant, either way round: the
   * constant joins every slot of every context, and the empty context of o2 stays empty.
   */
  @Test
  void aConstantInConcatAppliesToEverySlotOfEveryContext() throws Exception {
    String sku =
        "\"S%s\": {\"kind\": \"source\", \"field\": \"/Orders/Order/Line/Sku\","
            + " \"context\": \"/Orders/Order\"}";
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            String.join(
                ", ",
                orderOnto("/Out/Item"),
                mapping(
                    "/Out/Item/V",
                    "T1",
                    "F1",
                    "\"F1\": {\"kind\": \"function\", \"fn\": \"concat\", \"inputs\": [\"S1\","
                        + " \"C1\"]}, "
                        + sku.formatted("1")
                        + ", "
                        + constant("C1", "-x")),
                mapping(
                    "/Out/Item/W",
                    "T2",
                    "F2",
                    "\"F2\": {\"kind\": \"function\", \"fn\": \"concat\", \"inputs\": [\"C2\","
                        + " \"S2\"]}, "
                        + sku.formatted("2")
                        + ", "
                        + constant("C2", "y-"))));

    assertThat(run(model, observations(), THREE_ORDERS))
        .isEqualTo(
            DECLARATION
                + "<Out><Item><V>a-x</V><V>b-x</V><W>y-a</W><W>y-b</W></Item><Item/>"
                + "<Item><V>c-x</V><W>y-c</W></Item></Out>");
  }

  /**
   * concat of two constants reads no source field, so it is a generating function: one V "ab" per
   * Item, and one All directly under the root.
   */
  @Test
  void concatOfTwoConstantsIsAGeneratingFunction() throws Exception {
    String concat =
        "\"F%1$s\": {\"kind\": \"function\", \"fn\": \"concat\","
            + " \"inputs\": [\"A%1$s\", \"B%1$s\"]}, "
            + constant("A%1$s", "a")
            + ", "
            + constant("B%1$s", "b");
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            String.join(
                ", ",
                orderOnto("/Out/Item"),
                mapping("/Out/Item/V", "T1", "F1", concat.formatted("1")),
                mapping("/Out/All", "T2", "F2", concat.formatted("2"))));

    assertThat(run(model, observations(), THREE_ORDERS))
        .isEqualTo(
            DECLARATION
                + "<Out><Item><V>ab</V></Item><Item><V>ab</V></Item><Item><V>ab</V></Item>"
                + "<All>ab</All></Out>");
  }

  /**
   * count over a constant: the constant is one context with one value, so count gives 1 in context
   * 1 only, and only the first Item gets a V. W, from a constant directly, is in every Item. The
   * AtomicToQueue at count's input is not traced (ADR-0006 as amended by #83): the trace holds the
   * boxes and target field mappings only, C1 and C2 as atomic values, N1 as count's queue.
   */
  @Test
  void aConstantIntoAContextFunctionIsOneContextWithOneValue() throws Exception {
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            String.join(
                ", ",
                orderOnto("/Out/Item"),
                mapping(
                    "/Out/Item/V",
                    "T1",
                    "N1",
                    "\"N1\": {\"kind\": \"function\", \"fn\": \"count\", \"inputs\": [\"C1\"]}, "
                        + constant("C1", "x")),
                mapping("/Out/Item/W", "T2", "C2", constant("C2", "w"))));

    assertThat(run(model, observations(), THREE_ORDERS))
        .isEqualTo(
            DECLARATION
                + "<Out><Item><V>1</V><W>w</W></Item><Item><W>w</W></Item>"
                + "<Item><W>w</W></Item></Out>");

    TestRunResult result =
        facade.testRun(
            compiled(model, observations(), CompileOptions.TEST_RUN),
            THREE_ORDERS.getBytes(UTF_8),
            TIMEOUT,
            LIMIT);
    assertThat(result).isInstanceOf(TestRunResult.Output.class);
    Trace trace = ((TestRunResult.Output) result).trace();
    assertThat(trace.entries().keySet())
        .extracting(AuthoringId::value)
        .containsExactlyInAnyOrder("S0", "T0", "C1", "N1", "T1", "C2", "T2");
    assertThat(trace.entries().get(new AuthoringId("C1")))
        .isEqualTo(new Trace.Atomic(AtomicType.STRING, "x"));
    assertThat(trace.entries().get(new AuthoringId("N1")))
        .isEqualTo(new Trace.Queue(List.of(List.of("1")), false, 1, 1));
    assertThat(trace.entries().get(new AuthoringId("C2")))
        .isEqualTo(new Trace.Atomic(AtomicType.STRING, "w"));
  }

  /**
   * tgt-gen: Grp per Order, Gen (2..3) from a constant, V from Qty in the default context Line, so
   * one context per Line: 1 | 2 | 3 | 4. Gen j of Grp p has slot number (p - 1) * 2 + j and reads
   * that context: Grp 1 gets 1, 2 and Grp 2 gets 3, 4, though order 1 has three lines and order 2
   * one. Numbering by j alone would give 1, 2 twice; by order, Grp 2 would get 4.
   */
  @Test
  void onAStructureFieldTheChildrenAreNumberedPerCopy() throws Exception {
    byte[] model =
        model(
            "tgt-gen.xsd",
            "Gens",
            String.join(
                ", ",
                orderOnto("/Gens/Grp"),
                mapping("/Gens/Grp/Gen", "T1", "C1", constant("C1", "g")),
                mapping(
                    "/Gens/Grp/Gen/V",
                    "T2",
                    "S2",
                    "\"S2\": {\"kind\": \"source\", \"field\": \"/Orders/Order/Line/Qty\"}")));
    String input =
        orders(order(line("a", "1"), line("b", "2"), line("c", "3")), order(line("d", "4")));

    assertThat(run(model, observations(), input))
        .isEqualTo(
            DECLARATION
                + "<Gens><Grp><Gen><V>1</V></Gen><Gen><V>2</V></Gen></Grp>"
                + "<Grp><Gen><V>3</V></Gen><Gen><V>4</V></Gen></Grp></Gens>");
  }

  /** A constant box that leaves {@code value} out takes the default {@code ""}: an empty All. */
  @Test
  void aConstantWithoutAValueGivesEmptyText() throws Exception {
    byte[] model =
        model(
            "tgt-list.xsd",
            "Out",
            mapping(
                "/Out/All", "T1", "C1", "\"C1\": {\"kind\": \"function\", \"fn\": \"constant\"}"));

    assertThat(run(model, observations(), THREE_ORDERS))
        .isEqualTo(DECLARATION + "<Out><All/></Out>");
  }

  // --- the trace (ADR-0006 as amended by #83, ADR-0015)

  /**
   * Grp per Order (two orders), L (2..3) from the constant C1: the trace holds L's occurrences per
   * parent slot, all created, two each, numbered (p - 1) * 2 + j, with L's min and max, and C1's
   * value as the atomic entry of ADR-0015 ({@code {atomic: {type, value}}}, the first atomic box,
   * #83), type string and value k. The Order queue and Grp's occurrences are there as for any field
   * link.
   */
  @Test
  void theTraceRecordsTheGeneratingFieldsOccurrencesAndTheConstantsValue(@TempDir Path base)
      throws Exception {
    byte[] model =
        model(
            "target.xsd",
            "Out",
            orderOnto("/Out/Grp") + ", " + mapping("/Out/Grp/L", "T1", "C1", constant("C1", "k")));
    String input = orders(order("<Id>o1</Id>"), order("<Id>o2</Id>"));

    TestRunResult result =
        facade.testRun(
            compiled(model, withTarget(base, GROUPS_XSD), CompileOptions.TEST_RUN),
            input.getBytes(UTF_8),
            TIMEOUT,
            LIMIT);

    assertThat(result).isInstanceOf(TestRunResult.Output.class);
    TestRunResult.Output output = (TestRunResult.Output) result;
    assertThat(new String(output.output(), UTF_8))
        .isEqualTo(
            DECLARATION + "<Out><Grp><L>k</L><L>k</L></Grp><Grp><L>k</L><L>k</L></Grp></Out>");
    Trace trace = output.trace();
    assertThat(trace.entries().keySet())
        .extracting(AuthoringId::value)
        .containsExactlyInAnyOrder("S0", "T0", "C1", "T1");
    assertThat(trace.entries().get(new AuthoringId("T1")))
        .isEqualTo(
            new Trace.Occurrences(
                2,
                3,
                List.of(
                    new Trace.ParentSlot(1, 2, List.of(created(1), created(2))),
                    new Trace.ParentSlot(2, 2, List.of(created(3), created(4)))),
                false));
    assertThat(trace.entries().get(new AuthoringId("C1")))
        .isEqualTo(new Trace.Atomic(AtomicType.STRING, "k"));
    // witness: the field link's own entries, as before #83
    assertThat(trace.entries().get(new AuthoringId("T0")))
        .isEqualTo(
            new Trace.Occurrences(
                0,
                UNBOUNDED,
                List.of(new Trace.ParentSlot(1, 2, List.of(created(1), created(2)))),
                false));
  }

  private static Trace.Slot created(long slot) {
    return new Trace.Slot(slot, Trace.SlotStatus.CREATED);
  }
}
