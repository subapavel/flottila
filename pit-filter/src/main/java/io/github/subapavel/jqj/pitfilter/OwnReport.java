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

  /**
   * The statuses {@code scripts/ratchet.java} counts as killed ({@code mutants()}: KILLED, TIMED_OUT, MEMORY_ERROR).
   * Every other status is still alive there, so an oracle kill of it can change the union.
   */
  static final Set<String> KILLED = Set.of("KILLED", "TIMED_OUT", "MEMORY_ERROR");

  /** The text fields of the key; the sixth, indexes, is one {@code <index>} element or more. */
  private static final List<String> FIELDS =
      List.of("mutatedClass", "mutatedMethod", "methodDescription", "lineNumber", "mutator");

  private OwnReport() {}

  /**
   * The mutants whose status in the report is not in {@link #KILLED}.
   *
   * @throws IllegalStateException if the report is missing or not well-formed XML, or a mutation in it lacks its status
   *     or a key field, or has a line number or index that is not a number
   */
  static Set<MutantKey> survivors(Path report) {
    try (InputStream in = Files.newInputStream(report)) {
      XMLStreamReader xml = XMLInputFactory.newDefaultFactory().createXMLStreamReader(in);
      try {
        return survivors(xml);
      } finally {
        xml.close();
      }
    } catch (IOException | XMLStreamException | NumberFormatException e) {
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
      } else if (event == XMLStreamConstants.END_ELEMENT && xml.getLocalName().equals("mutation")) {
        if (status == null || !fields.keySet().containsAll(FIELDS) || indexes.isEmpty()) {
          throw new XMLStreamException("a mutation without its status, one of " + FIELDS + " or an index",
              xml.getLocation());
        }
        MutantKey key = new MutantKey(fields.get("mutatedClass"), fields.get("mutatedMethod"),
            fields.get("methodDescription"), Integer.parseInt(fields.get("lineNumber")), fields.get("mutator"), indexes);
        if (!KILLED.contains(status)) {
          out.add(key);
        }
      }
    }
    return out;
  }
}
