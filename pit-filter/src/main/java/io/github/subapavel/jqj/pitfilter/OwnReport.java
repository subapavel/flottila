package io.github.subapavel.jqj.pitfilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** A module's own PIT report ({@code target/pit-reports/mutations.xml}, PIT 1.30.2's XML format). */
final class OwnReport {

  private OwnReport() {}

  /**
   * The mutants the report gives the status SURVIVED or NO_COVERAGE.
   *
   * @throws IllegalStateException if the report is missing or is not well-formed XML
   */
  static Set<MutantKey> survivors(Path report) {
    try (InputStream in = Files.newInputStream(report)) {
      XMLStreamReader xml = XMLInputFactory.newDefaultFactory().createXMLStreamReader(in);
      try {
        return survivors(xml);
      } finally {
        xml.close();
      }
    } catch (IOException | XMLStreamException e) {
      throw new IllegalStateException("OWN_SURVIVORS cannot read the own PIT report " + report + ": " + e, e);
    }
  }

  private static Set<MutantKey> survivors(XMLStreamReader xml) throws XMLStreamException {
    Set<MutantKey> out = new HashSet<>();
    String status = null;
    Map<String, String> fields = new HashMap<>();
    List<Integer> indexes = new ArrayList<>();
    while (xml.hasNext()) {
      int event = xml.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        // sourceFile, blocks, killingTest, description and the other elements are not part of the key.
        switch (xml.getLocalName()) {
          case "mutation" -> {
            status = xml.getAttributeValue(null, "status");
            fields = new HashMap<>();
            indexes = new ArrayList<>();
          }
          case "mutatedClass", "mutatedMethod", "methodDescription", "lineNumber", "mutator" ->
              fields.put(xml.getLocalName(), xml.getElementText());
          case "index" -> indexes.add(Integer.valueOf(xml.getElementText()));
        }
      } else if (event == XMLStreamConstants.END_ELEMENT && xml.getLocalName().equals("mutation")
          && ("SURVIVED".equals(status) || "NO_COVERAGE".equals(status))) {
        out.add(new MutantKey(fields.get("mutatedClass"), fields.get("mutatedMethod"), fields.get("methodDescription"),
            Integer.parseInt(fields.get("lineNumber")), fields.get("mutator"), indexes));
      }
    }
    return out;
  }
}
