package io.flottila.mapping.oracle;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

/**
 * The verdicts of the oracles (ADR-0018).
 *
 * <ul>
 *   <li>Golden corpus: XMLUnit {@code checkForSimilar()} with {@code
 *       ignoreElementContentWhitespace()}, so an expected file may be indented and carry PO's
 *       prefixes, while whitespace inside a value still counts. {@code ignoreWhitespace} and {@code
 *       normalizeWhitespace} are never used.
 *   <li>Differential oracle: {@code checkForIdentical()}, which compares names, prefixes, URIs,
 *       text and the XML declaration, plus equal {@link #declarations} per element, because XMLUnit
 *       drops {@code xmlns} attributes (F-11). Bytes are not the verdict.
 * </ul>
 */
final class OracleVerdict {

  private OracleVerdict() {}

  static Diff similar(byte[] expected, byte[] actual) {
    return DiffBuilder.compare(Input.fromByteArray(expected))
        .withTest(Input.fromByteArray(actual))
        .checkForSimilar()
        .ignoreElementContentWhitespace()
        .build();
  }

  static Diff identical(byte[] expected, byte[] actual) {
    return DiffBuilder.compare(Input.fromByteArray(expected))
        .withTest(Input.fromByteArray(actual))
        .checkForIdentical()
        .build();
  }

  /**
   * The namespace declarations of {@code document}: per element in document order, the set of
   * {@code prefix=URI} pairs its start tag declares, {@code =URI} for a default namespace.
   */
  static List<Set<String>> declarations(byte[] document) {
    List<Set<String>> declarations = new ArrayList<>();
    try {
      XMLStreamReader reader =
          XMLInputFactory.newDefaultFactory()
              .createXMLStreamReader(new ByteArrayInputStream(document));
      try {
        while (reader.hasNext()) {
          if (reader.next() == XMLStreamConstants.START_ELEMENT) {
            Set<String> declared = new HashSet<>();
            for (int k = 0; k < reader.getNamespaceCount(); k++) {
              // the reader gives null for a default namespace (XMLStreamReader)
              String prefix = reader.getNamespacePrefix(k);
              declared.add((prefix == null ? "" : prefix) + "=" + reader.getNamespaceURI(k));
            }
            declarations.add(declared);
          }
        }
      } finally {
        reader.close();
      }
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException("no well-formed document: " + e.getMessage(), e);
    }
    return declarations;
  }
}
