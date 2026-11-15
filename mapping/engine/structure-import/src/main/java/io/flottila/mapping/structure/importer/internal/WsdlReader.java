package io.flottila.mapping.structure.importer.internal;

import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.importer.StructureLoadException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Reads a WSDL 1.1 or 2.0 bundle into the structure under one operation's input, output or fault
 * (#96, ADR-0008 "JSON Schema and WSDL"): the element of its message. Our own code reads the WSDL
 * ({@link WsdlCheck}) and hands every inline schema, over the {@code java.xml} DOM, to the XSD
 * reader; WSDL4J and Woden are not used (ADR-0008 "Considered options"). The bundle is closed as
 * for XSD: a {@code wsdl:import} or {@code wsdl:include} names a bundle file by its location, or an
 * import falls back to the bundle file of its namespace.
 */
public final class WsdlReader {

  private static final QName XSD_SCHEMA = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "schema");

  private WsdlReader() {}

  /** The root element, or why there is none. */
  private record Root(QName element, String missing) {}

  /**
   * @param files the bundle's files, in bundle order
   * @param contents the bytes of each file the host's resolver read, by its name in {@code files}
   * @param errors the load errors so far, of files the resolver did not read; the read adds its own
   * @param interfaceName the selector's portType or interface
   * @param selector the operation, its direction and its fault
   * @throws StructureLoadException with every load error, files in bundle order and each file in
   *     document order
   */
  public static Structure read(
      List<String> files,
      Map<String, byte[]> contents,
      List<StructureLoadException> errors,
      QName interfaceName,
      WsdlOperation selector)
      throws StructureLoadException {
    List<SchemaFile> schemas = new ArrayList<>();
    List<Consumer<XmlSchemaCollection>> reads = new ArrayList<>();
    Map<QName, WsdlCheck.Interface> interfaces = new HashMap<>();
    Map<QName, QName> messages = new HashMap<>();
    for (String file : files) {
      byte[] content = contents.get(file);
      if (content == null) {
        // a file the resolver did not read declares what is unknown, in a namespace unknown
        schemas.add(new SchemaFile(file));
        continue;
      }
      WsdlCheck check = WsdlCheck.check(file, content, errors);
      schemas.addAll(check.schemas);
      interfaces.putAll(check.interfaces);
      messages.putAll(check.messages);
      // a WSDL that stopped has no DOM; its read never runs beside its load error
      reads.add(check.dom == null ? XsdReader.document(file, content) : inline(file, check.dom));
    }
    Root root = root(interfaceName, selector, interfaces, messages);
    return XsdReader.read(files, schemas, errors, root.element(), root.missing(), reads);
  }

  /** The element under the selector, by its interface, operation, direction and fault. */
  private static Root root(
      QName name,
      WsdlOperation selector,
      Map<QName, WsdlCheck.Interface> interfaces,
      Map<QName, QName> messages) {
    WsdlCheck.Interface found = interfaces.get(name);
    if (found == null) {
      return new Root(
          null,
          "no portType or interface "
              + BundleCheck.eqName(name)
              + " is declared in the schema bundle");
    }
    Map<String, QName> operation = found.operations.get(selector.operation());
    if (operation == null) {
      return new Root(
          null,
          "the interface "
              + BundleCheck.eqName(name)
              + " declares no operation "
              + selector.operation());
    }
    String key =
        switch (selector.direction()) {
          case INPUT -> "input";
          case OUTPUT -> "output";
          case FAULT -> "fault " + selector.fault().orElseThrow();
        };
    QName use = operation.get(key);
    if (use == null) {
      return new Root(
          null,
          "the operation "
              + selector.operation()
              + " of "
              + BundleCheck.eqName(name)
              + " has no "
              + key);
    }
    // WSDL 1.1 names a message, of one part or none; WSDL 2.0 names the element itself
    return new Root(
        found.wsdl11 ? messages.get(use) : use,
        "the message " + BundleCheck.eqName(use) + " has no part with an element");
  }

  /** XmlSchema's read of each inline schema of a WSDL's {@code types}, in document order. */
  private static Consumer<XmlSchemaCollection> inline(String file, Document dom) {
    return collection -> {
      Element description = dom.getDocumentElement();
      QName types = new QName(description.getNamespaceURI(), "types");
      int n = 0;
      for (Node child = description.getFirstChild();
          child != null;
          child = child.getNextSibling()) {
        if (child instanceof Element element && types.equals(name(element))) {
          for (Node schema = element.getFirstChild();
              schema != null;
              schema = schema.getNextSibling()) {
            if (schema instanceof Element inline && XSD_SCHEMA.equals(name(inline))) {
              // in-scope prefixes of the WSDL are found up the DOM; the system id tells apart two
              // inline schemas of one target namespace
              collection.read(inline, file + "#" + ++n);
            }
          }
        }
      }
    };
  }

  private static QName name(Element element) {
    return new QName(element.getNamespaceURI(), element.getLocalName());
  }
}
