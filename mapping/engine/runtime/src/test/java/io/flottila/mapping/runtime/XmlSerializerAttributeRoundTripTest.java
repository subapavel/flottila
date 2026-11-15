package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.serializer.XmlSerializer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import javax.xml.parsers.SAXParserFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The serializer's attribute values against a SAX parser (#102, ADR-0022): any value is read back
 * as written, with no character lost, normalised or merged. It stands here, not beside {@code
 * XmlSerializerTest}, because {@code engine/serializer} does not read {@code java.xml} and this
 * module does, and reads the serializer.
 */
class XmlSerializerAttributeRoundTripTest {

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /** Markup, white space, references, BMP and astral characters, and nothing. */
  private static final String[] PIECES = {
    "\"", "&", "<", ">", "'", "\r", "\n", "\t", "\r\n", " ", "  ", "]]>", "&amp;", "&#9;", "a",
    "Zz9", "é", "€", "𝄞", "😀", "�", ""
  };

  /** A value of up to 12 pieces. */
  private static String value(RandomGenerator random) {
    StringBuilder value = new StringBuilder();
    int pieces = random.nextInt(13);
    for (int k = 0; k < pieces; k++) {
      value.append(PIECES[random.nextInt(PIECES.length)]);
    }
    return value.toString();
  }

  /** The document {@code <v a="value"/>} as the serializer writes it. */
  private static byte[] written(String value) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    XmlSerializer serializer = new XmlSerializer(out);
    serializer.startDocument();
    serializer.startElement("", "", "v");
    serializer.attribute("", "", "a", value);
    serializer.endElement();
    serializer.endDocument();
    return out.toByteArray();
  }

  /** The value of attribute {@code a} of the element {@code v} of {@code document}, by SAX. */
  private static String parsedAttribute(byte[] document) throws Exception {
    StringBuilder value = new StringBuilder();
    SAXParserFactory.newDefaultNSInstance()
        .newSAXParser()
        .parse(
            new ByteArrayInputStream(document),
            new DefaultHandler() {
              @Override
              public void startElement(
                  String uri, String localName, String qName, Attributes attributes) {
                if (localName.equals("v")) {
                  value.append(attributes.getValue("", "a"));
                }
              }
            });
    return value.toString();
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void attributeValueRoundTripsThroughSax(long seed) throws Exception {
    String value = value(new SplittableRandom(seed));

    assertThat(parsedAttribute(written(value)))
        .as("seed %d, value %s", seed, value)
        .isEqualTo(value);
  }
}
