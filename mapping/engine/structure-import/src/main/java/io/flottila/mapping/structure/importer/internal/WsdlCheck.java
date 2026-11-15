package io.flottila.mapping.structure.importer.internal;

import io.flottila.mapping.structure.importer.StructureImportDiagnostics;
import io.flottila.mapping.structure.importer.StructureLoadException;
import io.flottila.mapping.structure.importer.internal.SchemaFile.FileReference;
import io.flottila.mapping.structure.importer.internal.SchemaFile.NameUse;
import io.flottila.mapping.structure.importer.internal.SchemaFile.Position;
import io.flottila.mapping.structure.importer.internal.XsdSubsetCheck.Refusal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.w3c.dom.Document;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.NamespaceSupport;

/**
 * Reads one file of a WSDL bundle with the JDK's SAX parser (#96, ADR-0008 "JSON Schema and WSDL"),
 * as {@link XsdSubsetCheck} reads an XSD, so that every load error has its position (F-7). A file
 * whose document element is a WSDL 1.1 {@code definitions} or a WSDL 2.0 {@code description} is a
 * WSDL; any other file is an XSD, read whole by {@link XsdSubsetCheck}.
 *
 * <p>Of a WSDL it reads the imports and includes, every inline {@code xs:schema} of its {@code
 * types}, which {@link XsdSubsetCheck} checks at its place in the file, and the parts that lead
 * from an operation to the element of its input, output or fault:
 *
 * <ul>
 *   <li>WSDL 1.1: each {@code message}, with one {@code part} of an {@code element}, and each
 *       {@code portType}'s operations, whose {@code input}, {@code output} and {@code fault} name a
 *       message;
 *   <li>WSDL 2.0: each {@code interface}'s faults and operations, whose {@code input} and {@code
 *       output} name an element and whose {@code infault} and {@code outfault} name a fault of the
 *       interface.
 * </ul>
 *
 * <p>Refused where they stand: an RPC-style {@code part} (one with a {@code type}), a message of
 * several parts, a WSDL 2.0 message content model {@code #any}, {@code #none} or {@code #other} (an
 * absent {@code element} is {@code #other}), an interface that {@code extends} another, and an
 * element without an attribute the reader needs. Everything else, such as documentation, bindings,
 * services and extension elements, has no effect: its content is not read.
 */
final class WsdlCheck extends DefaultHandler {

  static final String WSDL11 = "http://schemas.xmlsoap.org/wsdl/";
  static final String WSDL20 = "http://www.w3.org/ns/wsdl";

  private static final QName XSD_SCHEMA = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "schema");
  private static final Set<QName> DOCUMENTS =
      Set.of(new QName(WSDL11, "definitions"), new QName(WSDL20, "description"));
  private static final Set<String> CONTENT_MODELS = Set.of("#any", "#none", "#other");

  /** What a WSDL element is to the reader, and the attributes it must have. */
  private enum Role {
    DOCUMENT,
    DESCRIPTION,
    IMPORT("namespace"),
    INCLUDE("location"),
    TYPES,
    MESSAGE("name"),
    PART("name", "element"),
    INTERFACE("name"),
    INTERFACE_FAULT("name"),
    OPERATION("name"),
    // WSDL 1.1: an input or output, and a fault, that name a message
    MESSAGE_REFERENCE("message"),
    FAULT_MESSAGE_REFERENCE("name", "message"),
    // WSDL 2.0: an input or output that names an element, and an infault or outfault
    ELEMENT_REFERENCE,
    FAULT_REFERENCE("ref");

    final List<String> required;

    Role(String... required) {
      this.required = List.of(required);
    }
  }

  /** The roles of the children of each WSDL element the reader reads; any other has no effect. */
  private static final Map<Role, Map<String, Role>> WSDL11_CHILDREN =
      Map.of(
          Role.DESCRIPTION,
          Map.of(
              "import", Role.IMPORT,
              "types", Role.TYPES,
              "message", Role.MESSAGE,
              "portType", Role.INTERFACE),
          Role.MESSAGE,
          Map.of("part", Role.PART),
          Role.INTERFACE,
          Map.of("operation", Role.OPERATION),
          Role.OPERATION,
          Map.of(
              "input", Role.MESSAGE_REFERENCE,
              "output", Role.MESSAGE_REFERENCE,
              "fault", Role.FAULT_MESSAGE_REFERENCE));

  private static final Map<Role, Map<String, Role>> WSDL20_CHILDREN =
      Map.of(
          Role.DESCRIPTION,
          Map.of(
              "import", Role.IMPORT,
              "include", Role.INCLUDE,
              "types", Role.TYPES,
              "interface", Role.INTERFACE),
          Role.INTERFACE,
          Map.of("operation", Role.OPERATION, "fault", Role.INTERFACE_FAULT),
          Role.OPERATION,
          Map.of(
              "input", Role.ELEMENT_REFERENCE,
              "output", Role.ELEMENT_REFERENCE,
              "infault", Role.FAULT_REFERENCE,
              "outfault", Role.FAULT_REFERENCE));

  /**
   * A portType or interface: its operations by name, and of each operation what it names by {@code
   * input}, {@code output} or {@code fault <name>}: a message in WSDL 1.1, an element in WSDL 2.0.
   */
  static final class Interface {
    final boolean wsdl11;
    final Map<String, Map<String, QName>> operations = new HashMap<>();

    /** WSDL 2.0: the element of each interface fault, null for a refused one. */
    private final Map<QName, QName> faults = new HashMap<>();

    /** WSDL 2.0: the infaults and outfaults, resolved when the interface ends. */
    private final List<FaultReference> references = new ArrayList<>();

    Interface(boolean wsdl11) {
      this.wsdl11 = wsdl11;
    }
  }

  private record FaultReference(Map<String, QName> operation, QName fault, Position at) {}

  /** An open WSDL element the reader reads, and its children so far. */
  private static final class Frame {
    final Role role;
    int children;

    Frame(Role role) {
      this.role = role;
    }
  }

  private final String file;
  private final List<StructureLoadException> errors;

  /** The file's WSDL part, then its inline schemas; or the one XSD; as far as it was read. */
  final List<SchemaFile> schemas = new ArrayList<>();

  /** WSDL 1.1: the element of each message's one part. */
  final Map<QName, QName> messages = new HashMap<>();

  final Map<QName, Interface> interfaces = new HashMap<>();

  /** The DOM of a WSDL that is well-formed, for XmlSchema's read of its inline schemas. */
  Document dom;

  private boolean wsdl;
  private boolean wsdl11;
  private String wsdlNamespace;
  private Map<Role, Map<String, Role>> children;
  private SchemaFile part;

  /** An XSD file, read whole; null for a WSDL. */
  private SchemaFile document;

  private final Deque<Frame> open = new ArrayDeque<>();
  private final NamespaceSupport namespaces = new NamespaceSupport();

  /** The prefix mappings of the next start tag. */
  private final List<String[]> pending = new ArrayList<>();

  private Locator locator;

  /** The depth inside an element whose content is not read; 0 outside any. */
  private int skipped;

  /** The check of the schema being read, and the depth inside it; null outside any. */
  private XsdSubsetCheck inline;

  private int inlineDepth;
  private QName message;
  private Interface current;
  private Map<String, QName> operation;

  private WsdlCheck(String file, List<StructureLoadException> errors) {
    this.file = file;
    this.errors = errors;
    open.push(new Frame(Role.DOCUMENT));
  }

  /**
   * Checks one file, adding each load error in it to {@code errors}.
   *
   * @return what the file declares and refers to, as far as it was read
   */
  static WsdlCheck check(String file, byte[] content, List<StructureLoadException> errors) {
    WsdlCheck handler = new WsdlCheck(file, errors);
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(true);
      // no DTD: no entity and no external file is ever read (ADR-0008: loading is offline)
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.newSAXParser().parse(new InputSource(new ByteArrayInputStream(content)), handler);
      if (handler.wsdl) {
        DocumentBuilderFactory dom = DocumentBuilderFactory.newInstance();
        dom.setNamespaceAware(true);
        dom.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        handler.dom = dom.newDocumentBuilder().parse(new ByteArrayInputStream(content));
      }
    } catch (SAXParseException e) {
      errors.add(
          new StructureLoadException(
              StructureImportDiagnostics.SCHEMA_NOT_WELL_FORMED,
              file,
              e.getLineNumber(),
              e.getColumnNumber(),
              e.getMessage()));
      if (handler.document != null) {
        // an XSD stops as XsdSubsetCheck's own parse stops it
        handler.document.stopped = true;
      } else {
        // a WSDL, or a file stopped before its document element, may declare any name in any
        // namespace: as a file never read
        handler.schemas.add(new SchemaFile(file));
      }
    } catch (ParserConfigurationException | SAXException | IOException e) {
      // the JDK parsers support the feature, the input is in memory, a WSDL the SAX pass read is
      // well-formed, and the handler throws no SAXException of its own
      throw new IllegalStateException(e);
    }
    return handler;
  }

  @Override
  public void setDocumentLocator(Locator locator) {
    this.locator = locator;
  }

  @Override
  public void startPrefixMapping(String prefix, String uri) {
    if (inline == null) {
      pending.add(new String[] {prefix, uri});
    } else {
      inline.startPrefixMapping(prefix, uri);
    }
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes) {
    if (inline != null) {
      inlineDepth++;
      inline.startElement(uri, localName, qName, attributes);
      return;
    }
    namespaces.pushContext();
    for (String[] mapping : pending) {
      namespaces.declarePrefix(mapping[0], mapping[1]);
    }
    pending.clear();
    if (skipped > 0) {
      skipped++;
      return;
    }
    QName name = new QName(uri, localName);
    Frame parent = open.element();
    if (parent.role == Role.DOCUMENT) {
      if (DOCUMENTS.contains(name)) {
        description(uri, attributes);
      } else {
        // under a WSDL root, a file that is no WSDL is an XSD (#96)
        document = new SchemaFile(file);
        delegate(document, uri, localName, qName, attributes);
      }
      return;
    }
    if (parent.role == Role.TYPES && XSD_SCHEMA.equals(name)) {
      delegate(new SchemaFile(file), uri, localName, qName, attributes);
      return;
    }
    Role role =
        uri.equals(wsdlNamespace)
            ? children.getOrDefault(parent.role, Map.of()).get(localName)
            : null;
    if (role == null) {
      skipped = 1;
      return;
    }
    parent.children++;
    try {
      record(role, parent, localName, qName, attributes);
      open.push(new Frame(role));
    } catch (Refusal refusal) {
      errors.add(
          new StructureLoadException(
              refusal.code, file, refusal.at.line(), refusal.at.column(), refusal.getMessage()));
      skipped = 1;
    }
  }

  private void description(String uri, Attributes attributes) {
    wsdl = true;
    wsdl11 = WSDL11.equals(uri);
    wsdlNamespace = uri;
    children = wsdl11 ? WSDL11_CHILDREN : WSDL20_CHILDREN;
    part = new SchemaFile(file, true);
    part.targetNamespace =
        Objects.requireNonNullElse(attributes.getValue("", "targetNamespace"), "");
    part.schemaTag = position();
    schemas.add(part);
    open.push(new Frame(Role.DESCRIPTION));
  }

  /**
   * Hands an {@code xs:schema} to {@link XsdSubsetCheck}: the prefixes in scope, then its events up
   * to its end tag.
   */
  private void delegate(
      SchemaFile schema, String uri, String localName, String qName, Attributes attributes) {
    schemas.add(schema);
    inline = XsdSubsetCheck.delegate(schema, errors, locator);
    inlineDepth = 1;
    Enumeration<String> prefixes = namespaces.getPrefixes();
    while (prefixes.hasMoreElements()) {
      String prefix = prefixes.nextElement();
      inline.startPrefixMapping(prefix, namespaces.getURI(prefix));
    }
    String defaultNamespace = namespaces.getURI("");
    if (defaultNamespace != null) {
      inline.startPrefixMapping("", defaultNamespace);
    }
    inline.startElement(uri, localName, qName, attributes);
  }

  /** Records what an element that stands where it may names, or refuses it. */
  private void record(
      Role role, Frame parent, String localName, String qName, Attributes attributes)
      throws Refusal {
    switch (role) {
      case PART -> {
        if (parent.children > 1) {
          throw refusal(
              "<"
                  + qName
                  + "> after the first part of its message is outside the WSDL the reader"
                  + " reads");
        }
        if (attributes.getValue("", "type") != null) {
          throw refusal(
              "<" + qName + "> with type, an RPC-style part, is outside the WSDL the reader reads");
        }
      }
      case INTERFACE -> {
        if (attributes.getValue("", "extends") != null) {
          throw refusal("<" + qName + "> with extends is outside the WSDL the reader reads");
        }
      }
      default -> {
        // no refusal of its own
      }
    }
    for (String attribute : role.required) {
      if (attributes.getValue("", attribute) == null) {
        throw refusal(
            "<" + qName + "> without " + attribute + " is outside the WSDL the reader reads");
      }
    }
    Position at = position();
    String name = attributes.getValue("", "name");
    switch (role) {
      case IMPORT ->
          part.references.add(
              new FileReference(
                  attributes.getValue("", "namespace"),
                  attributes.getValue("", "location"),
                  at,
                  "wsdl:import",
                  "location"));
      case INCLUDE ->
          part.references.add(
              new FileReference(
                  null, attributes.getValue("", "location"), at, "wsdl:include", "location"));
      case MESSAGE -> {
        message = global(name);
        part.messages.add(message);
      }
      case PART -> messages.put(message, element(attributes.getValue("", "element"), at));
      case INTERFACE -> {
        current = new Interface(wsdl11);
        interfaces.put(global(name), current);
      }
      case INTERFACE_FAULT -> {
        // a refused fault is declared: a reference to it is no second error
        QName fault = global(name);
        current.faults.put(fault, null);
        current.faults.put(fault, contentModel(attributes, qName, at));
      }
      case OPERATION -> {
        operation = new HashMap<>();
        current.operations.put(name, operation);
      }
      case MESSAGE_REFERENCE -> operation.put(localName, message(attributes, at));
      case FAULT_MESSAGE_REFERENCE -> operation.put("fault " + name, message(attributes, at));
      case ELEMENT_REFERENCE -> operation.put(localName, contentModel(attributes, qName, at));
      case FAULT_REFERENCE ->
          current.references.add(
              new FaultReference(operation, qualified(attributes.getValue("", "ref")), at));
      case DOCUMENT, DESCRIPTION, TYPES -> {
        // nothing to record
      }
    }
  }

  /** A WSDL 2.0 {@code element}: a message content model other than an element is refused. */
  private QName contentModel(Attributes attributes, String qName, Position at) throws Refusal {
    String value = Objects.requireNonNullElse(attributes.getValue("", "element"), "#other");
    if (CONTENT_MODELS.contains(value)) {
      throw refusal(
          "<"
              + qName
              + "> of the message content model "
              + value
              + " is outside the WSDL the reader reads");
    }
    return element(value, at);
  }

  /** An element a part or a WSDL 2.0 message names, to be found in the bundle. */
  private QName element(String value, Position at) throws Refusal {
    QName element = qualified(value);
    part.elementRefs.add(new NameUse(element, at));
    return element;
  }

  /** A WSDL 1.1 message an input, output or fault names, to be found in the bundle. */
  private QName message(Attributes attributes, Position at) throws Refusal {
    QName name = qualified(attributes.getValue("", "message"));
    part.messageRefs.add(new NameUse(name, at));
    return name;
  }

  @Override
  public void endElement(String uri, String localName, String qName) {
    if (inline != null) {
      inline.endElement(uri, localName, qName);
      inlineDepth--;
      if (inlineDepth == 0) {
        inline = null;
        namespaces.popContext();
      }
      return;
    }
    namespaces.popContext();
    if (skipped > 0) {
      skipped--;
      return;
    }
    if (open.pop().role == Role.INTERFACE) {
      // an infault or outfault names a fault of its interface, wherever the interface declares it
      for (FaultReference reference : current.references) {
        if (current.faults.containsKey(reference.fault())) {
          reference
              .operation()
              .put(
                  "fault " + reference.fault().getLocalPart(),
                  current.faults.get(reference.fault()));
        } else {
          errors.add(
              new StructureLoadException(
                  StructureImportDiagnostics.SCHEMA_INVALID,
                  file,
                  reference.at().line(),
                  reference.at().column(),
                  "the fault "
                      + BundleCheck.eqName(reference.fault())
                      + " is declared in no fault of this interface"));
        }
      }
    }
  }

  @Override
  public void characters(char[] ch, int start, int length) {
    if (inline != null) {
      inline.characters(ch, start, length);
    }
  }

  /** A QName attribute value, its prefix bound by the namespaces in scope. */
  private QName qualified(String value) throws Refusal {
    String[] name = namespaces.processName(value, new String[3], false);
    if (name == null) {
      throw refusal("the prefix of " + value + " is not declared");
    }
    return new QName(name[0], name[1]);
  }

  /** A WSDL declaration's name, in the WSDL's target namespace. */
  private QName global(String name) {
    return new QName(part.targetNamespace, name);
  }

  private Position position() {
    return new Position(locator.getLineNumber(), locator.getColumnNumber());
  }

  private Refusal refusal(String message) {
    return new Refusal(
        StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED, message, position());
  }
}
