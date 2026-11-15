package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.api.CompileOptions;
import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.api.CompiledMapping;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.api.ExportResult;
import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.runtime.Interpreter;
import io.flottila.mapping.runtime.MappingRunException;
import io.flottila.mapping.runtime.Tracer;
import io.flottila.mapping.xslt.export.XsltExporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;

/**
 * The export and our runtime agree outside the corpus (#27 design): Saxon-HE runs the exported
 * stylesheet, our runtime runs the same mapping as deployed, and {@link SaxonRunner#assertAgree}
 * judges. The empty-source and wrong-root inputs raise {@code po:MINOCCURS} since #52; the corpus
 * case target/minOccurs is theirs. This class is no corpus or differential class, so the ratchet
 * does not read its tests as case ids (scripts/ratchet.java).
 */
class ExportAgreementTest {

  private static final String NS = "urn:flottila:corpus:skeleton";
  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

  @TempDir Path dir;

  /** The corpus's field link {@code OrderId <- OrderId}. */
  private static Path fieldLink() {
    return CaseFiles.corpus().resolve("mappings/order-id-copy.mapping.json");
  }

  /** Ours: the field link compiled as deployed, run on {@code input}. */
  private static byte[] ours(byte[] input) throws Exception {
    EngineFacade facade = new EngineFacade();
    CompileResult result =
        facade.compile(
            Files.readAllBytes(fieldLink()),
            DirectoryResolver.over(fieldLink().getParent()),
            CompileOptions.DEPLOY);
    if (!(result instanceof CompileResult.Compiled(CompiledMapping compiled))) {
      throw new AssertionError("order-id-copy: expected Compiled, got " + result);
    }
    return facade.run(compiled, input);
  }

  /** Saxon's: the field link exported through the facade, run on {@code input}. */
  private byte[] saxon(byte[] input) throws Exception {
    ExportResult.Exported exported =
        SaxonRunner.exported(
            new EngineFacade()
                .export(
                    Files.readAllBytes(fieldLink()),
                    DirectoryResolver.over(fieldLink().getParent())),
            "order-id-copy");
    return SaxonRunner.run(exported.stylesheet(), exported.helperModule(), input, dir);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }

  // --- the empty source and wrong roots

  /**
   * An empty source, and InterpreterTest's wrong roots, where the context field, the source root,
   * is not there either: each queue is one empty context (ADR-0004 as amended, F-17).
   */
  static Stream<Arguments> emptySources() {
    return Stream.of(
        Arguments.of("an empty source", "<s:Order xmlns:s=\"" + NS + "\"/>"),
        Arguments.of(
            "another local name",
            "<s:Invoice xmlns:s=\"" + NS + "\"><OrderId>1</OrderId></s:Invoice>"),
        Arguments.of(
            "the root's local name in another namespace",
            "<o:Order xmlns:o=\"urn:other\"><OrderId>1</OrderId></o:Order>"),
        Arguments.of(
            "the root's local name without a namespace", "<Order><OrderId>1</OrderId></Order>"));
  }

  /**
   * OrderId is 1..1, so no OrderId raises {@code po:MINOCCURS} on both sides (ADR-0006 since #52):
   * ours at the target field mapping t1, Saxon's {@code po:at-least} with the same QName. It
   * replaces spec 1's tests, which wrote the root without the field on both sides.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("emptySources")
  void anEmptySourceRaisesMinOccursOnBothSides(String why, String input) throws Exception {
    assertThatThrownBy(() -> ours(utf8(input)))
        .as(why)
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo("po:MINOCCURS");
              assertThat(e.authoringId()).isEqualTo(new AuthoringId("t1"));
            });
    ExportResult.Exported exported =
        SaxonRunner.exported(
            new EngineFacade()
                .export(
                    Files.readAllBytes(fieldLink()),
                    DirectoryResolver.over(fieldLink().getParent())),
            "order-id-copy");

    SaxonRunner.assertSameError(
        SaxonRunner.raised(exported.stylesheet(), exported.helperModule(), utf8(input), dir, why),
        "po:MINOCCURS",
        why);
  }

  // --- generated values (ADR-0018: a seeded generator, the seed in the case name, no shrinking)

  /** In every value, in a shuffled order: what an escape, a reference or a parser could change. */
  private static final List<String> REQUIRED = List.of("\r", "&", "<", ">", "]]>", "\t", "𐍈");

  /** Further pieces, drawn at random. */
  private static final List<String> EXTRA =
      List.of("a", "Z", "7", " ", "\n", "\"", "'", "é", "€", "&amp;", "&#13;", "PO 4711");

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 20);
  }

  /** A value with every required piece, random extras, and leading and trailing spaces. */
  private static String value(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    List<String> pieces = new ArrayList<>(REQUIRED);
    for (int k = random.nextInt(0, 8); k > 0; k--) {
      pieces.add(EXTRA.get(random.nextInt(EXTRA.size())));
    }
    Collections.shuffle(pieces, random);
    return " ".repeat(random.nextInt(1, 3))
        + String.join("", pieces)
        + " ".repeat(random.nextInt(1, 3));
  }

  /** {@code value} as XML text: a CR as a reference, which a parser would otherwise normalise. */
  private static String escaped(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\r", "&#13;");
  }

  /** The text of the first {@code OrderId} of {@code document}. */
  private static String orderId(byte[] document) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    Document parsed = factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));
    return parsed.getElementsByTagNameNS("", "OrderId").item(0).getTextContent();
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void generatedValuesAgree(long seed) throws Exception {
    String value = value(seed);
    // witness: the generator gives what the name promises
    assertThat(value).contains(REQUIRED).startsWith(" ").endsWith(" ");
    assertThat(value.codePoints()).as("a character outside the BMP").contains(0x10348);
    byte[] input =
        utf8("<s:Order xmlns:s=\"" + NS + "\"><OrderId>" + escaped(value) + "</OrderId></s:Order>");

    byte[] ours = ours(input);
    // witness: the value was copied, so the agreement is about it
    assertThat(orderId(ours)).as("seed %d", seed).isEqualTo(value);
    byte[] saxon = saxon(input);

    SaxonRunner.assertAgree(saxon, ours, "seed " + seed);
  }

  // --- a second output namespace, from IR built by hand as lowering gives it

  private static final String SECOND = "urn:flottila:corpus:second";

  /** {@code <prefix:target>{value}</prefix:target>} per slot of the queue of {@code field}. */
  private static TargetSlots slots(
      String field, String prefix, Name target, long max, String mapping, String box) {
    AuthoringId origin = new AuthoringId(mapping);
    return new TargetSlots(
        new SourceQueue(
            List.of(new Name(NS, "Order"), new Name("", field)), 1, new AuthoringId(box)),
        new VarRef("root", AtomicType.INTEGER, origin),
        0,
        max,
        "slot",
        "value",
        new ElementConstructor(
            prefix,
            target,
            List.of(new TextConstructor(new VarRef("value", AtomicType.STRING, origin), origin)),
            origin),
        origin);
  }

  /**
   * Our serializer declares a binding on the first element that needs it (ADR-0022): {@code ns1} on
   * each {@code Note}, which are siblings, and never on the root. Saxon's output must declare it on
   * the same elements, which {@code checkForIdentical} alone does not see (F-11).
   */
  @Test
  void aSecondNamespaceIsDeclaredWhereOurSerializerDeclaresIt() throws Exception {
    TargetDocument document =
        new TargetDocument(
            "ns0",
            new Name(NS, "Order"),
            "root",
            List.of(
                slots("OrderId", "", new Name("", "OrderId"), 1, "t1", "a1"),
                slots("Note", "ns1", new Name(SECOND, "Note"), Long.MAX_VALUE, "t2", "a2")));
    IrVerifier.verify(document);
    byte[] input =
        utf8(
            "<s:Order xmlns:s=\""
                + NS
                + "\"><OrderId>1</OrderId><Note>a</Note><Note>b</Note></s:Order>");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Interpreter.compile(document, false)
        .run(new ByteArrayInputStream(input), out, new Tracer(), CaseFiles.DEFAULT_NOW);
    byte[] ours = out.toByteArray();
    byte[] saxon =
        SaxonRunner.run(XsltExporter.export(document), XsltExporter.helperModule(), input, dir);

    assertThat(new String(ours, UTF_8))
        .isEqualTo(
            DECLARATION
                + "<ns0:Order xmlns:ns0=\""
                + NS
                + "\"><OrderId>1</OrderId>"
                + "<ns1:Note xmlns:ns1=\""
                + SECOND
                + "\">a</ns1:Note>"
                + "<ns1:Note xmlns:ns1=\""
                + SECOND
                + "\">b</ns1:Note>"
                + "</ns0:Order>");
    assertThat(OracleVerdict.declarations(ours))
        .containsExactly(
            Set.of("ns0=" + NS), Set.of(), Set.of("ns1=" + SECOND), Set.of("ns1=" + SECOND));
    SaxonRunner.assertAgree(saxon, ours, "a second namespace");
  }
}
