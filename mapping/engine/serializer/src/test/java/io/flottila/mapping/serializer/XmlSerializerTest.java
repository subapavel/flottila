package io.flottila.mapping.serializer;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The XML serializer's bytes (ADR-0022): the declaration, no indentation, no trailing newline,
 * UTF-8; a prefix bound on the first element that needs it and never a default namespace; {@code
 * &}, {@code <}, {@code >} and CR escaped in text. Expected bytes are literals.
 */
class XmlSerializerTest {

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

  @FunctionalInterface
  interface Body {
    void write(XmlSerializer serializer) throws Exception;
  }

  /** The bytes of a whole document whose root and content {@code body} writes. */
  private static byte[] document(Body body) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    XmlSerializer serializer = new XmlSerializer(out);
    serializer.startDocument();
    body.write(serializer);
    serializer.endDocument();
    return out.toByteArray();
  }

  private static void assertAscii(String expected, byte[] actual) {
    assertThat(new String(actual, UTF_8)).isEqualTo(expected);
    assertThat(actual).isEqualTo(expected.getBytes(US_ASCII));
  }

  @Test
  void writesTheDeclarationThenTheRootWithoutWhitespace() throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("ns0", "urn:a", "Order");
              s.startElement("", "", "OrderId");
              s.text("4711");
              s.endElement();
              s.startElement("", "", "Note");
              s.text("x");
              s.endElement();
              s.endElement();
            });

    assertAscii(
        DECLARATION
            + "<ns0:Order xmlns:ns0=\"urn:a\"><OrderId>4711</OrderId><Note>x</Note></ns0:Order>",
        bytes);
  }

  @Test
  void declaresAPrefixAtFirstUseAndNeverADefaultNamespace() throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("ns0", "urn:a", "A");
              s.startElement("ns1", "urn:b", "B");
              s.startElement("ns1", "urn:b", "C");
              s.text("x");
              s.endElement();
              s.endElement();
              // the first B's binding went out of scope with it
              s.startElement("ns1", "urn:b", "B");
              s.endElement();
              s.startElement("", "", "D");
              s.text("y");
              s.endElement();
              s.startElement("ns0", "urn:a", "E");
              s.text("z");
              s.endElement();
              s.endElement();
            });

    assertAscii(
        DECLARATION
            + "<ns0:A xmlns:ns0=\"urn:a\">"
            + "<ns1:B xmlns:ns1=\"urn:b\"><ns1:C>x</ns1:C></ns1:B>"
            + "<ns1:B xmlns:ns1=\"urn:b\"/>"
            + "<D>y</D>"
            + "<ns0:E>z</ns0:E>"
            + "</ns0:A>",
        bytes);
  }

  /**
   * A prefix in scope bound to another namespace is declared again, and the innermost binding of a
   * prefix decides (Namespaces in XML 1.0, section 6.1). Lowering gives each namespace one prefix,
   * so this is the serializer's own contract.
   */
  @Test
  void aPrefixBoundToAnotherNamespaceIsDeclaredAgainAndTheInnermostBindingDecides()
      throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("ns0", "urn:a", "A");
              // ns0 is bound to urn:a here, so urn:b needs its own declaration
              s.startElement("ns0", "urn:b", "B");
              // bound to urn:b by B
              s.startElement("ns0", "urn:b", "C");
              s.text("x");
              s.endElement();
              // the innermost ns0 is B's urn:b, not A's urn:a
              s.startElement("ns0", "urn:a", "D");
              s.endElement();
              s.endElement();
              // B's binding went out of scope with it: A's urn:a holds again
              s.startElement("ns0", "urn:a", "E");
              s.endElement();
              s.endElement();
            });

    assertAscii(
        DECLARATION
            + "<ns0:A xmlns:ns0=\"urn:a\">"
            + "<ns0:B xmlns:ns0=\"urn:b\"><ns0:C>x</ns0:C><ns0:D xmlns:ns0=\"urn:a\"/></ns0:B>"
            + "<ns0:E/>"
            + "</ns0:A>",
        bytes);
  }

  @Test
  void refusesANamespaceWithoutAPrefix() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    XmlSerializer serializer = new XmlSerializer(out);
    serializer.startDocument();

    // writing it would need a default namespace or an invented prefix, and ADR-0022 allows neither
    assertThatThrownBy(() -> serializer.startElement("", "urn:a", "Order"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEmptyElementIsSelfClosing() throws Exception {
    byte[] rootOnly =
        document(
            s -> {
              s.startElement("ns0", "urn:a", "Order");
              s.endElement();
            });
    byte[] emptyChildren =
        document(
            s -> {
              s.startElement("ns0", "urn:a", "Order");
              s.startElement("", "", "OrderId");
              s.endElement();
              s.startElement("", "", "Note");
              s.text("");
              s.endElement();
              s.endElement();
            });

    assertAscii(DECLARATION + "<ns0:Order xmlns:ns0=\"urn:a\"/>", rootOnly);
    assertAscii(
        DECLARATION + "<ns0:Order xmlns:ns0=\"urn:a\"><OrderId/><Note/></ns0:Order>",
        emptyChildren);
  }

  @Test
  void escapesMarkupAndCarriageReturnInText() throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("", "", "v");
              s.text("a&b<c>d\re\"f'g\n\th]]>i");
              s.endElement();
            });

    // quotes, LF and tab are written as they are
    assertAscii(DECLARATION + "<v>a&amp;b&lt;c&gt;d&#13;e\"f'g\n\th]]&gt;i</v>", bytes);
  }

  // --- attributes (#102, ADR-0022)

  static Stream<Arguments> closedStartTags() {
    return Stream.of(
        Arguments.of("before any element", (Body) s -> {}),
        Arguments.of(
            "after the element's text",
            (Body)
                s -> {
                  s.startElement("", "", "v");
                  s.text("x");
                }),
        Arguments.of(
            "after a child element",
            (Body)
                s -> {
                  s.startElement("", "", "v");
                  s.startElement("", "", "c");
                  s.endElement();
                }),
        Arguments.of(
            "after the element ended",
            (Body)
                s -> {
                  s.startElement("", "", "v");
                  s.endElement();
                }));
  }

  /** An attribute is written only while its element's start tag is open (#102). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("closedStartTags")
  void attributeAfterContentIsRefused(String why, Body before) throws Exception {
    // witness: right after the start tag, the attribute is written
    assertAscii(
        DECLARATION + "<v a=\"1\">x</v>",
        document(
            s -> {
              s.startElement("", "", "v");
              s.attribute("", "", "a", "1");
              s.text("x");
              s.endElement();
            }));
    XmlSerializer serializer = new XmlSerializer(new ByteArrayOutputStream());
    serializer.startDocument();
    before.write(serializer);

    assertThatThrownBy(() -> serializer.attribute("", "", "a", "1"))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * An attribute's prefix is declared on the element that carries it when it is unbound there, and
   * the binding holds for that element's descendants and ends with it (ADR-0022 since #102). A
   * declaration comes before the attribute that needs it, as an element's comes after its name.
   */
  @Test
  void attributeBindingNotRedeclaredInChild() throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("ns0", "urn:a", "A");
              // bound by A itself
              s.attribute("ns0", "urn:a", "code", "1");
              s.attribute("", "", "plain", "p");
              s.startElement("", "", "B");
              // unbound: B declares it
              s.attribute("ns1", "urn:b", "k", "2");
              // bound by B's attribute: neither C nor its attribute declares it again
              s.startElement("ns1", "urn:b", "C");
              s.attribute("ns1", "urn:b", "k", "3");
              s.endElement();
              s.endElement();
              // B's binding ended with B
              s.startElement("", "", "D");
              s.attribute("ns1", "urn:b", "k", "4");
              s.endElement();
              s.endElement();
            });

    assertAscii(
        DECLARATION
            + "<ns0:A xmlns:ns0=\"urn:a\" ns0:code=\"1\" plain=\"p\">"
            + "<B xmlns:ns1=\"urn:b\" ns1:k=\"2\"><ns1:C ns1:k=\"3\"/></B>"
            + "<D xmlns:ns1=\"urn:b\" ns1:k=\"4\"/>"
            + "</ns0:A>",
        bytes);
  }

  /**
   * In an attribute value {@code &}, {@code <}, {@code >} and {@code "} are escaped, and CR, LF and
   * TAB are written {@code &#13;}, {@code &#10;} and {@code &#9;} (ADR-0022 since #102), which a
   * parser would otherwise normalise to spaces; the apostrophe stays.
   */
  @Test
  void escapesMarkupQuoteAndWhitespaceInAnAttributeValue() throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("", "", "v");
              s.attribute("", "", "a", "a&b<c>d\"e'f\rg\nh\ti");
              s.endElement();
            });

    assertAscii(DECLARATION + "<v a=\"a&amp;b&lt;c&gt;d&quot;e'f&#13;g&#10;h&#9;i\"/>", bytes);
  }

  // attributeValueRoundTripsThroughSax lives in engine/runtime
  // (XmlSerializerAttributeRoundTripTest):
  // this module does not read java.xml, so its tests cannot reach a SAX parser

  @Test
  void writesNonAsciiAsUtf8() throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("", "", "v");
              s.text("Grüße € 𝄞");
              s.endElement();
            });

    // G r ü ß e SP € SP U+1D11E, encoded by hand: no character references, no CESU-8 surrogates
    byte[] text = HexFormat.of().parseHex("4772c3bcc39f6520e282ac20f09d849e");
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.writeBytes((DECLARATION + "<v>").getBytes(US_ASCII));
    expected.writeBytes(text);
    expected.writeBytes("</v>".getBytes(US_ASCII));
    assertThat(bytes).isEqualTo(expected.toByteArray());
  }

  /** The bytes of a document {@code <v>…</v>} whose text is {@code hex}, encoded by hand. */
  private static byte[] textDocument(String hex) {
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.writeBytes((DECLARATION + "<v>").getBytes(US_ASCII));
    expected.writeBytes(HexFormat.of().parseHex(hex));
    expected.writeBytes("</v>".getBytes(US_ASCII));
    return expected.toByteArray();
  }

  /** The first and last character of each UTF-8 length are encoded in that length (RFC 3629). */
  @Test
  void writesTheEdgesOfEachUtf8Length() throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("", "", "v");
              s.text("\u007F\u0080߿ࠀ￿𐀀􏿿");
              s.endElement();
            });

    // U+007F, U+0080, U+07FF, U+0800, U+FFFF, U+10000, U+10FFFF, encoded by hand
    assertThat(bytes)
        .isEqualTo(textDocument("7f" + "c280dfbf" + "e0a080efbfbf" + "f0908080f48fbfbf"));
  }

  /**
   * A surrogate pair split between two texts of one element is one character, as the JDK's
   * OutputStreamWriter, which the serializer wrote through before #194, joins it (observed).
   */
  @Test
  void aSurrogatePairSplitBetweenTwoTextsIsOneCharacter() throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("", "", "v");
              s.text("a\uD834");
              s.text("\uDD1Eb");
              s.endElement();
            });

    // a, U+1D11E, b
    assertThat(bytes).isEqualTo(textDocument("61f09d849e62"));
  }

  /**
   * A lone surrogate, which no UTF-8 encodes, is written {@code ?}, as the JDK's OutputStreamWriter
   * replaces it (observed before #194): a high one before a character that is no low surrogate,
   * that character then written as it is, a high one before markup, and a low one alone.
   */
  @Test
  void aLoneSurrogateIsWrittenAsAQuestionMark() throws Exception {
    byte[] bytes =
        document(
            s -> {
              s.startElement("", "", "v");
              s.attribute("", "", "a", "\uDD1E\uD834");
              s.text("a\uD834ü\uDD1Eb\uD834");
              s.endElement();
            });

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.writeBytes((DECLARATION + "<v a=\"??\">a?").getBytes(US_ASCII));
    expected.writeBytes(HexFormat.of().parseHex("c3bc"));
    expected.writeBytes("?b?</v>".getBytes(US_ASCII));
    assertThat(bytes).isEqualTo(expected.toByteArray());
  }

  /**
   * A document longer than any buffer is written whole, its multi-byte characters across every
   * buffer boundary included; the expected bytes are the JDK's own UTF-8 encoding of the text.
   */
  @Test
  void aLongDocumentIsWrittenWhole() throws Exception {
    String text = "aé€𝄞".repeat(20_000);
    byte[] bytes =
        document(
            s -> {
              s.startElement("", "", "v");
              s.text(text);
              s.endElement();
            });

    assertThat(bytes).isEqualTo((DECLARATION + "<v>" + text + "</v>").getBytes(UTF_8));
  }

  /**
   * The serializer buffers its bytes (#194): a document shorter than its buffer reaches the output
   * stream in one write, at {@code endDocument}, not one write per name or character.
   */
  @Test
  void aShortDocumentReachesTheStreamInOneWrite() throws Exception {
    List<Integer> writes = new ArrayList<>();
    ByteArrayOutputStream bytes =
        new ByteArrayOutputStream() {
          @Override
          public void write(byte[] b, int off, int len) {
            writes.add(len);
            super.write(b, off, len);
          }
        };
    XmlSerializer serializer = new XmlSerializer(bytes);
    serializer.startDocument();
    serializer.startElement("ns0", "urn:a", "Order");
    serializer.text("Grüße");
    serializer.endElement();
    assertThat(writes).isEmpty();

    serializer.endDocument();

    assertThat(writes).containsExactly(bytes.size());
    assertThat(bytes.toString(UTF_8))
        .isEqualTo(DECLARATION + "<ns0:Order xmlns:ns0=\"urn:a\">Grüße</ns0:Order>");
  }

  /** {@code endDocument} flushes the output stream, so a buffering stream holds nothing back. */
  @Test
  void endDocumentFlushesTheOutputStream() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    XmlSerializer serializer = new XmlSerializer(new BufferedOutputStream(bytes, 1 << 16));
    serializer.startDocument();
    serializer.startElement("", "", "v");
    serializer.endElement();

    serializer.endDocument();

    assertAscii(DECLARATION + "<v/>", bytes.toByteArray());
  }
}
