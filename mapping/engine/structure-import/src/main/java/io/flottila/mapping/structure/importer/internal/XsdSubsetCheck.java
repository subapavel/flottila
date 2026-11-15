package io.flottila.mapping.structure.importer.internal;

import io.flottila.mapping.structure.importer.StructureImportDiagnostics;
import io.flottila.mapping.structure.importer.StructureLoadException;
import io.flottila.mapping.structure.importer.internal.SchemaFile.AttributeOwner;
import io.flottila.mapping.structure.importer.internal.SchemaFile.AttributeUse;
import io.flottila.mapping.structure.importer.internal.SchemaFile.Definition;
import io.flottila.mapping.structure.importer.internal.SchemaFile.FileReference;
import io.flottila.mapping.structure.importer.internal.SchemaFile.GlobalElement;
import io.flottila.mapping.structure.importer.internal.SchemaFile.NameUse;
import io.flottila.mapping.structure.importer.internal.SchemaFile.Particle;
import io.flottila.mapping.structure.importer.internal.SchemaFile.Position;
import io.flottila.mapping.structure.importer.internal.SchemaFile.SimpleType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.NamespaceSupport;

/**
 * Reads one XSD file with the JDK's SAX parser before XmlSchema sees it. It refuses every construct
 * outside the XSD the reader reads where it stands, and records what {@link BundleCheck} needs.
 * XmlSchema 2.3.3 keeps no line or column on its objects, so this pass gives every load error its
 * position (ADR-0008), and every refused field the position of the declaration that causes its
 * refusal (#92). A refused construct's content is not read; a file that is not well-formed stops at
 * the parser's error, and every refusal before it stays.
 *
 * <p>The XSD read (#93) is one {@code xs:schema} ({@code targetNamespace}, {@code
 * elementFormDefault}, {@code attributeFormDefault}) of {@code xs:import}s, {@code xs:include}s,
 * global {@code xs:element}s and {@code xs:attribute}s, and named {@code xs:complexType}s and
 * {@code xs:simpleType}s; and, since #94, named {@code xs:group}s and {@code xs:attributeGroup}s.
 *
 * <ul>
 *   <li>A complex type, named or anonymous, holds an optional content particle first: an {@code
 *       xs:sequence}, {@code xs:choice} or {@code xs:all}, or an {@code xs:group} reference ({@code
 *       ref}, {@code minOccurs}, {@code maxOccurs}). Then it holds {@code xs:attribute}s ({@code
 *       name}, {@code type}, {@code use}, {@code form}, {@code default}, {@code fixed}), attribute
 *       references ({@code ref}, {@code use}, {@code default}, {@code fixed}; #89 review) and
 *       {@code xs:attributeGroup} references ({@code ref}). An anonymous one below the root holds
 *       at least one of them. A named one may be {@code abstract} (#95). An attribute, local or
 *       global, has a {@code type} or one anonymous {@code xs:simpleType} (#89 review).
 *   <li>Instead, a complex type may hold only an {@code xs:complexContent} with one {@code
 *       xs:extension} or {@code xs:restriction} of a named complex type ({@code base}), which holds
 *       what a complex type holds; or only an {@code xs:simpleContent} with one {@code
 *       xs:extension} or {@code xs:restriction} of a simple type or of a complex type with simple
 *       content ({@code base}), which holds what an attribute group holds, and a restriction facets
 *       too (#95). An attribute's {@code use} may be {@code prohibited}, and then it needs no
 *       {@code type} and has no {@code default} or {@code fixed}.
 *   <li>A sequence or a choice ({@code minOccurs}, {@code maxOccurs}) holds local {@code
 *       xs:element}s, element references ({@code ref}, {@code minOccurs}, {@code maxOccurs}),
 *       sequences, choices, group references and {@code xs:any}. An {@code xs:all} ({@code
 *       minOccurs}, {@code maxOccurs}) holds local elements and element references.
 *   <li>A named group ({@code name}) holds one sequence, choice or all. A named attribute group
 *       ({@code name}) holds attributes and attribute group references.
 *   <li>A global or local element has a {@code type}, or one anonymous {@code xs:complexType} or
 *       {@code xs:simpleType} (#89 review), or is a global one in a substitution group, which then
 *       has its head's type (#95). A local one is a leaf or structure field ({@code name}, {@code
 *       minOccurs}, {@code maxOccurs}, {@code form}, {@code nillable}, {@code default}, {@code
 *       fixed}). A global one may be {@code abstract} and name the head of its {@code
 *       substitutionGroup} (#95).
 *   <li>A simple type, named or anonymous (#89 review), is an {@code xs:restriction} of a {@code
 *       base}, an {@code xs:list} or an {@code xs:union}.
 * </ul>
 *
 * <p>Accepted with no effect, their content unread (ADR-0008): {@code xs:annotation} anywhere,
 * {@code xs:any}, {@code xs:anyAttribute}, facets, identity constraints, and the content of a list
 * or union. Accepted with no effect since the #89 review: {@code id} on every element, {@code
 * version}, {@code blockDefault} and {@code finalDefault} on the schema, {@code block} and {@code
 * final} where XSD 1.0 allows them, {@code mixed} on a complex type and on its {@code
 * xs:complexContent}, {@code nillable} on an element of an anonymous complex type, and attributes
 * in other namespaces than XSD's and the versioning one, of which a {@code vc:minVersion} below
 * 1.1.
 *
 * <p>Refused when a schema loads, wherever they stand (ADR-0008, #95): {@code xs:redefine} and
 * {@code xs:override}, each named as such; {@code vc:minVersion} 1.1 or above on any element, named
 * as XSD 1.1; and the elements only XSD 1.1 has, as elements outside the subset.
 */
final class XsdSubsetCheck extends DefaultHandler {

  static final String UNSUPPORTED = StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED;
  static final String INVALID = StructureImportDiagnostics.SCHEMA_INVALID;

  private static final Pattern COUNT = Pattern.compile("[0-9]{1,18}");
  private static final Pattern BOOLEAN = Pattern.compile("true|false");
  private static final String TRUE = "true";
  private static final Pattern USE = Pattern.compile("optional|required|prohibited");
  private static final Pattern FORM = Pattern.compile("qualified|unqualified");

  // one string: FamilyLocalityTest reads a quoted standard function name, such as the facet
  // length's, as a mention of the function outside its family
  private static final Set<String> FACETS =
      Set.of(
          ("minExclusive minInclusive maxExclusive maxInclusive totalDigits fractionDigits length"
                  + " minLength maxLength enumeration whiteSpace pattern")
              .split(" "));

  private static final Set<String> IDENTITY_CONSTRAINTS = Set.of("unique", "key", "keyref");

  /** The roles of an element that is its parent's first child other than an annotation. */
  private static final Set<Role> FIRST =
      EnumSet.of(
          Role.COMPLEX_TYPE,
          Role.STRUCTURE_TYPE,
          Role.ANONYMOUS_SIMPLE_TYPE,
          Role.COMPLEX_CONTENT,
          Role.SIMPLE_CONTENT,
          Role.COMPLEX_DERIVATION,
          Role.SIMPLE_EXTENSION,
          Role.SIMPLE_RESTRICTION);

  /**
   * Why each XSD element ADR-0008 names as refused when a schema loads is refused, by its local
   * name. An element only XSD 1.1 has is refused as any element outside the subset, which names it.
   */
  private static final Map<String, String> REFUSED_AT_LOAD =
      Map.of(
          "redefine",
          "xs:redefine is refused when a schema loads (ADR-0008)",
          "override",
          "xs:override is refused when a schema loads (ADR-0008)");

  /** The namespace of {@code vc:minVersion} (XSD 1.1 Part 1, 4.2.1). */
  private static final String VERSIONING = "http://www.w3.org/2007/XMLSchema-versioning";

  private static final Pattern DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?");

  private static final BigDecimal XSD_11 = new BigDecimal("1.1");

  /**
   * Where an XSD element may stand, and the attributes it may carry besides {@code id}, which every
   * one may carry with no effect (#89 review).
   */
  private enum Role {
    DOCUMENT(Set.of(), Set.of()),
    SCHEMA(
        Set.of(
            "targetNamespace",
            "elementFormDefault",
            "attributeFormDefault",
            "version",
            "blockDefault",
            "finalDefault"),
        Set.of()),
    IMPORT(Set.of("namespace", "schemaLocation"), Set.of()),
    INCLUDE(Set.of("schemaLocation"), Set.of("schemaLocation")),
    // a global element with an anonymous type, and one with a type
    GLOBAL(
        Set.of(
            "name",
            "abstract",
            "substitutionGroup",
            "nillable",
            "default",
            "fixed",
            "block",
            "final"),
        Set.of("name")),
    GLOBAL_TYPED(
        Set.of(
            "name",
            "type",
            "nillable",
            "default",
            "fixed",
            "abstract",
            "substitutionGroup",
            "block",
            "final"),
        Set.of("name")),
    // a global attribute has a type or an anonymous simple type (#89 review)
    GLOBAL_ATTRIBUTE(Set.of("name", "type", "default", "fixed"), Set.of("name")),
    NAMED_COMPLEX_TYPE(Set.of("name", "abstract", "mixed", "block", "final"), Set.of("name")),
    COMPLEX_TYPE(Set.of("mixed"), Set.of()),
    // a complex type's xs:complexContent or xs:simpleContent, and its derivation (#95)
    COMPLEX_CONTENT(Set.of("mixed"), Set.of()),
    SIMPLE_CONTENT(Set.of(), Set.of()),
    // an xs:extension or xs:restriction of complex content
    COMPLEX_DERIVATION(Set.of("base"), Set.of("base")),
    // an xs:extension or xs:restriction of simple content
    SIMPLE_EXTENSION(Set.of("base"), Set.of("base")),
    SIMPLE_RESTRICTION(Set.of("base"), Set.of("base")),
    // an xs:sequence or an xs:choice: each holds the same particles (#94)
    SEQUENCE(Set.of("minOccurs", "maxOccurs"), Set.of()),
    ALL(Set.of("minOccurs", "maxOccurs"), Set.of()),
    // a named group and a reference to one (#94)
    GROUP(Set.of("name"), Set.of("name")),
    GROUP_REF(Set.of("ref", "minOccurs", "maxOccurs"), Set.of("ref")),
    ATTRIBUTE_GROUP(Set.of("name"), Set.of("name")),
    ATTRIBUTE_GROUP_REF(Set.of("ref"), Set.of("ref")),
    // a local element is a leaf only when it has a type (child), so a leaf never lacks one
    LEAF(
        Set.of(
            "name",
            "type",
            "minOccurs",
            "maxOccurs",
            "nillable",
            "form",
            "default",
            "fixed",
            "block"),
        Set.of("name")),
    ELEMENT_REF(Set.of("ref", "minOccurs", "maxOccurs"), Set.of("ref")),
    // a local element without a type: of an anonymous complex type, or of an anonymous simple
    // type, which makes it a leaf (#89 review)
    STRUCTURE(
        Set.of("name", "minOccurs", "maxOccurs", "form", "nillable", "default", "fixed", "block"),
        Set.of("name")),
    STRUCTURE_TYPE(Set.of("mixed"), Set.of()),
    // an attribute has a type or an anonymous simple type (#89 review)
    ATTRIBUTE(Set.of("name", "type", "use", "form", "default", "fixed"), Set.of("name")),
    // use="prohibited", which makes no field and needs no type (#95)
    PROHIBITED_ATTRIBUTE(Set.of("name", "type", "use", "form"), Set.of("name")),
    // a reference to a global attribute, a field named by its QName (#89 review)
    ATTRIBUTE_REF(Set.of("ref", "use", "default", "fixed"), Set.of("ref")),
    NAMED_SIMPLE_TYPE(Set.of("name", "final"), Set.of("name")),
    RESTRICTION(Set.of("base"), Set.of("base")),
    // an element's or an attribute's own simple type, and its restriction (#89 review)
    ANONYMOUS_SIMPLE_TYPE(Set.of(), Set.of()),
    ANONYMOUS_RESTRICTION(Set.of("base"), Set.of("base")),
    // read with no effect: its attributes and content are not read
    NO_EFFECT(Set.of(), Set.of());

    final Set<String> attributes;
    final Set<String> required;

    Role(Set<String> attributes, Set<String> required) {
      this.attributes = attributes;
      this.required = required;
    }

    /**
     * The role of the XSD element {@code xs:<name>} inside this one, or null if it may not be. An
     * element is a reference when it has a {@code ref}, typed when it has a {@code type}, and
     * otherwise has an anonymous complex type.
     */
    Role child(String name, Attributes attributes) {
      if (this == DOCUMENT) {
        return name.equals("schema") ? SCHEMA : null;
      }
      if (name.equals("annotation")) {
        return NO_EFFECT;
      }
      boolean typed = attributes.getValue("", "type") != null;
      return switch (this) {
        case SCHEMA ->
            switch (name) {
              case "import" -> IMPORT;
              case "include" -> INCLUDE;
              case "element" -> typed ? GLOBAL_TYPED : GLOBAL;
              case "attribute" -> GLOBAL_ATTRIBUTE;
              case "complexType" -> NAMED_COMPLEX_TYPE;
              case "simpleType" -> NAMED_SIMPLE_TYPE;
              case "group" -> GROUP;
              case "attributeGroup" -> ATTRIBUTE_GROUP;
              default -> null;
            };
        case GLOBAL -> name.equals("complexType") ? COMPLEX_TYPE : simpleType(name);
        case GLOBAL_TYPED, LEAF -> identityConstraint(name);
        // beside a type, the subset check refuses it (#89 review)
        case GLOBAL_ATTRIBUTE, ATTRIBUTE ->
            name.equals("simpleType") ? ANONYMOUS_SIMPLE_TYPE : null;
        // a content particle, then what an attribute group holds
        case NAMED_COMPLEX_TYPE, COMPLEX_TYPE, STRUCTURE_TYPE ->
            switch (name) {
              case "complexContent" -> COMPLEX_CONTENT;
              case "simpleContent" -> SIMPLE_CONTENT;
              default -> content(name, attributes);
            };
        case COMPLEX_CONTENT ->
            name.equals("extension") || name.equals("restriction") ? COMPLEX_DERIVATION : null;
        case SIMPLE_CONTENT ->
            switch (name) {
              case "extension" -> SIMPLE_EXTENSION;
              case "restriction" -> SIMPLE_RESTRICTION;
              default -> null;
            };
        case COMPLEX_DERIVATION -> content(name, attributes);
        case SIMPLE_EXTENSION -> attribute(name, attributes);
        // its facets are not validated (ADR-0004)
        case SIMPLE_RESTRICTION -> FACETS.contains(name) ? NO_EFFECT : attribute(name, attributes);
        case SEQUENCE ->
            switch (name) {
              case "element" ->
                  attributes.getValue("", "ref") != null ? ELEMENT_REF : typed ? LEAF : STRUCTURE;
              case "sequence", "choice" -> SEQUENCE;
              case "group" -> GROUP_REF;
              case "any" -> NO_EFFECT;
              default -> null;
            };
        case ALL -> name.equals("element") ? SEQUENCE.child(name, attributes) : null;
        case GROUP ->
            switch (name) {
              case "sequence", "choice" -> SEQUENCE;
              case "all" -> ALL;
              default -> null;
            };
        case ATTRIBUTE_GROUP -> attribute(name, attributes);
        case STRUCTURE -> name.equals("complexType") ? STRUCTURE_TYPE : simpleType(name);
        case NAMED_SIMPLE_TYPE, ANONYMOUS_SIMPLE_TYPE ->
            switch (name) {
              case "restriction" -> this == NAMED_SIMPLE_TYPE ? RESTRICTION : ANONYMOUS_RESTRICTION;
              case "list", "union" -> NO_EFFECT;
              default -> null;
            };
        case RESTRICTION, ANONYMOUS_RESTRICTION -> FACETS.contains(name) ? NO_EFFECT : null;
        case DOCUMENT,
            IMPORT,
            INCLUDE,
            ELEMENT_REF,
            PROHIBITED_ATTRIBUTE,
            ATTRIBUTE_REF,
            GROUP_REF,
            ATTRIBUTE_GROUP_REF,
            NO_EFFECT ->
            null;
      };
    }

    /** The role inside an element without a type, other than an anonymous complex type. */
    private static Role simpleType(String name) {
      return name.equals("simpleType") ? ANONYMOUS_SIMPLE_TYPE : identityConstraint(name);
    }

    private static Role identityConstraint(String name) {
      return IDENTITY_CONSTRAINTS.contains(name) ? NO_EFFECT : null;
    }

    /**
     * The role of a complex type's content, or a complex-content derivation's: a content particle
     * first, then what an attribute group holds (#94).
     */
    private static Role content(String name, Attributes attributes) {
      return switch (name) {
        case "sequence", "choice" -> SEQUENCE;
        case "all" -> ALL;
        case "group" -> GROUP_REF;
        default -> attribute(name, attributes);
      };
    }

    /**
     * The role of what an attribute group holds: an attribute reference (#89 review), else an
     * attribute, one with {@code use="prohibited"} among them (#95); an attribute group reference
     * (#94) or an {@code xs:anyAttribute}.
     */
    private static Role attribute(String name, Attributes attributes) {
      return switch (name) {
        case "attribute" ->
            attributes.getValue("", "ref") != null
                ? ATTRIBUTE_REF
                : "prohibited".equals(attributes.getValue("", "use"))
                    ? PROHIBITED_ATTRIBUTE
                    : ATTRIBUTE;
        case "attributeGroup" -> ATTRIBUTE_GROUP_REF;
        case "anyAttribute" -> NO_EFFECT;
        default -> null;
      };
    }
  }

  /** An open XSD element, where its start tag ends, and its children so far. */
  private static final class Frame {
    final Role role;
    final Position start;

    /** Child elements other than annotations, refused ones among them. */
    int children;

    /** Whether it has an anonymous type, refused or not. */
    boolean anonymousType;

    /** Whether it has a {@code type} attribute. */
    boolean typed;

    /** The name of a global element without a type; null elsewhere (#89 review). */
    QName global;

    /**
     * The attribute uses of the complex type or attribute group it is or stands in (#89 review);
     * null outside any.
     */
    AttributeOwner attributeUses;

    /**
     * Whether it is a global element in a substitution group, which without a type of its own has
     * its head's (#95).
     */
    boolean substitutes;

    /**
     * Where the particles it holds go (#92, #94): its own list for a global element, a named
     * complex type, a named group, a local element of an anonymous type and a model group, its
     * parent's otherwise.
     */
    List<Particle> particles;

    /**
     * The named complex type it is or whose content it derives (#95); null in an anonymous complex
     * type and elsewhere.
     */
    QName complexType;

    /**
     * Whether it holds an {@code xs:complexContent} or {@code xs:simpleContent} that stands where
     * it may; a refused one sets nothing.
     */
    boolean derived;

    /**
     * Where the group or attribute group references it holds go (#94): its own set for a named
     * group or attribute group, whose definition keeps it, and a fresh one for a local element,
     * through which a reference is no use of the enclosing group; its parent's otherwise.
     */
    Set<QName> uses = new HashSet<>();

    Frame(Role role, Position start, List<Particle> particles) {
      this.role = role;
      this.start = start;
      this.particles = particles;
    }
  }

  /** The particle a complex type or a group holds first, once (#94). */
  private static final Set<Role> CONTENT = EnumSet.of(Role.SEQUENCE, Role.ALL, Role.GROUP_REF);

  /** What holds one content particle, first (#94, #95). */
  private static final Set<Role> HOLDS_CONTENT =
      EnumSet.of(
          Role.NAMED_COMPLEX_TYPE,
          Role.COMPLEX_TYPE,
          Role.STRUCTURE_TYPE,
          Role.GROUP,
          Role.COMPLEX_DERIVATION);

  /** A refused construct, at the parser's position or at a start tag read before. */
  static final class Refusal extends Exception {
    @Serial private static final long serialVersionUID = 1L;

    final String code;
    final transient Position at;

    Refusal(String code, String message, Position at) {
      super(message);
      this.code = code;
      this.at = at;
    }
  }

  private final SchemaFile schema;
  private final List<StructureLoadException> errors;
  private final Deque<Frame> open = new ArrayDeque<>();
  private final NamespaceSupport namespaces = new NamespaceSupport();
  private boolean contextPushed;
  private Locator locator;

  /** The depth inside an element whose content is not read; 0 outside any. */
  private int skipped;

  /** The named simple type being read. */
  private QName simpleType;

  private XsdSubsetCheck(SchemaFile schema, List<StructureLoadException> errors) {
    this.schema = schema;
    this.errors = errors;
    // the document has no start tag, and no element particle stands outside a complex type
    open.push(new Frame(Role.DOCUMENT, null, List.of()));
  }

  /**
   * Checks one file, adding each load error in it to {@code errors}: one per refused construct, and
   * one where the file stops being well-formed XML or has a DOCTYPE.
   *
   * @return what the file declares and refers to, as far as it was read
   */
  static SchemaFile check(String file, byte[] content, List<StructureLoadException> errors) {
    SchemaFile schema = new SchemaFile(file);
    XsdSubsetCheck handler = new XsdSubsetCheck(schema, errors);
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(true);
      // no DTD: no entity and no external file is ever read (ADR-0008: loading is offline)
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.newSAXParser().parse(new InputSource(new ByteArrayInputStream(content)), handler);
    } catch (SAXParseException e) {
      schema.stopped = true;
      errors.add(
          new StructureLoadException(
              StructureImportDiagnostics.SCHEMA_NOT_WELL_FORMED,
              file,
              e.getLineNumber(),
              e.getColumnNumber(),
              e.getMessage()));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      // the JDK parser supports the one feature set above, the input is in memory, and the
      // handler throws no SAXException of its own
      throw new IllegalStateException(e);
    }
    return schema;
  }

  /**
   * A check of one {@code xs:schema} whose events another handler forwards, from the schema's start
   * tag to its end tag, with the prefixes in scope there declared first (#96: a WSDL's inline
   * schema, or a whole XSD file in a WSDL bundle). Its load errors are at {@code locator}'s
   * positions, in the file {@code schema} names.
   */
  static XsdSubsetCheck delegate(
      SchemaFile schema, List<StructureLoadException> errors, Locator locator) {
    XsdSubsetCheck handler = new XsdSubsetCheck(schema, errors);
    handler.setDocumentLocator(locator);
    return handler;
  }

  @Override
  public void setDocumentLocator(Locator locator) {
    this.locator = locator;
  }

  @Override
  public void startPrefixMapping(String prefix, String uri) {
    if (!contextPushed) {
      namespaces.pushContext();
      contextPushed = true;
    }
    namespaces.declarePrefix(prefix, uri);
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes) {
    if (!contextPushed) {
      namespaces.pushContext();
    }
    contextPushed = false;
    if (skipped > 0) {
      skipped++;
      return;
    }
    try {
      Frame frame = start(uri, localName, qName, attributes);
      if (frame.role == Role.NO_EFFECT) {
        skipped = 1;
      } else {
        open.push(frame);
      }
    } catch (Refusal refusal) {
      refuse(refusal);
      skipped = 1;
      // a refused global declaration still declares its name: a use of it is no second error
      String name = attributes.getValue("", "name");
      if (open.element().role == Role.SCHEMA && name != null) {
        schema.refused.add(global(name));
      }
    }
  }

  /**
   * The frame of an element that may stand here, or the refusal of one that may not. A {@link
   * Role#NO_EFFECT} frame is never opened: its content is not read.
   */
  private Frame start(String uri, String localName, String qName, Attributes attributes)
      throws Refusal {
    Position start = position();
    Frame parent = open.element();
    // XSD 1.1 wherever it is asked for, even on an element whose content is not read (ADR-0008)
    String minVersion = attributes.getValue(VERSIONING, "minVersion");
    if (minVersion != null
        && DECIMAL.matcher(minVersion).matches()
        && new BigDecimal(minVersion).compareTo(XSD_11) >= 0) {
      throw refusal(
          UNSUPPORTED,
          "vc:minVersion=\""
              + minVersion
              + "\" asks for XSD 1.1, which is refused when a schema loads (ADR-0008)");
    }
    boolean xsd = XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(uri);
    Role role = xsd ? parent.role.child(localName, attributes) : null;
    // a refused child counts as well, so that its parent is not refused for lacking it; an
    // annotation may come first anywhere
    int earlier = parent.children;
    parent.children += localName.equals("annotation") ? 0 : 1;
    parent.anonymousType |= localName.equals("complexType") || localName.equals("simpleType");
    if (role == null) {
      // xs:redefine and xs:override are named as ADR-0008 refuses them, wherever they stand; any
      // other element, one only XSD 1.1 has among them, as one outside the subset
      String refused = xsd ? REFUSED_AT_LOAD.get(localName) : null;
      throw refusal(
          UNSUPPORTED,
          refused != null ? refused : "<" + qName + "> here is outside the XSD the reader reads");
    }
    if (role == Role.NO_EFFECT) {
      return new Frame(role, start, parent.particles);
    }
    // an attribute has a type or an anonymous simple type, not both (#89 review)
    if (role == Role.ANONYMOUS_SIMPLE_TYPE && parent.typed) {
      throw refusal(
          UNSUPPORTED, "<" + qName + "> beside a type is outside the XSD the reader reads");
    }
    // nothing follows a complex type's xs:complexContent or xs:simpleContent (#95)
    if (parent.derived) {
      throw refusal(
          UNSUPPORTED,
          "<"
              + qName
              + "> after xs:complexContent or xs:simpleContent is outside the XSD the reader"
              + " reads");
    }
    // an element has one xs:complexType; a complex type's xs:complexContent or xs:simpleContent
    // comes once, first, and so does the one derivation of either (#95); a complex type's, a
    // complex-content derivation's or a group's content particle comes once, first (#94)
    if (earlier > 0
        && (FIRST.contains(role)
            || CONTENT.contains(role) && HOLDS_CONTENT.contains(parent.role))) {
      throw refusal(
          UNSUPPORTED, "<" + qName + "> after another child is outside the XSD the reader reads");
    }
    for (int i = 0; i < attributes.getLength(); i++) {
      String local = attributes.getLocalName(i);
      boolean read =
          attributes.getURI(i).isEmpty()
              ? local.equals("id") || role.attributes.contains(local)
              : foreign(attributes.getURI(i), local, attributes.getValue(i));
      if (!read) {
        throw refusal(
            UNSUPPORTED,
            "the attribute "
                + attributes.getQName(i)
                + " of <"
                + qName
                + "> is outside the XSD the reader reads");
      }
    }
    for (String name : role.required) {
      if (attributes.getValue("", name) == null) {
        throw refusal(
            UNSUPPORTED,
            "<" + qName + "> without " + name + " is outside the XSD the reader reads");
      }
    }
    // a particle, an element or a group, is what may carry maxOccurs
    if (role.attributes.contains("maxOccurs")) {
      checkOccurrence(attributes.getValue("", "minOccurs"), attributes.getValue("", "maxOccurs"));
    }
    checkValue(attributes, "nillable", BOOLEAN);
    checkValue(attributes, "abstract", BOOLEAN);
    checkValue(attributes, "use", USE);
    checkValue(attributes, "elementFormDefault", FORM);
    checkValue(attributes, "attributeFormDefault", FORM);
    checkValue(attributes, "form", FORM);
    Frame frame = new Frame(role, start, parent.particles);
    frame.uses = parent.uses;
    frame.typed = attributes.getValue("", "type") != null;
    frame.attributeUses = parent.attributeUses;
    record(role, localName, attributes, frame, parent);
    return frame;
  }

  /**
   * Whether the reader accepts an attribute in the namespace {@code namespace} on an XSD element,
   * with no effect (ADR-0008, #89 review): one of any namespace but XSD's and the versioning one,
   * and of the versioning namespace a decimal {@code vc:minVersion}, which is below 1.1 here: one
   * of 1.1 or above is refused before as XSD 1.1. Any other versioning attribute stays refused.
   */
  private static boolean foreign(String namespace, String local, String value) {
    return switch (namespace) {
      case XMLConstants.W3C_XML_SCHEMA_NS_URI -> false;
      case VERSIONING -> local.equals("minVersion") && DECIMAL.matcher(value).matches();
      default -> true;
    };
  }

  /**
   * Records what {@link BundleCheck} needs of an element that stands where it may, and where a
   * particle is declared for {@link XsdReader} (#92, #94).
   */
  private void record(Role role, String localName, Attributes attributes, Frame frame, Frame parent)
      throws Refusal {
    String name = attributes.getValue("", "name");
    String type = attributes.getValue("", "type");
    Position start = frame.start;
    boolean prohibited = "prohibited".equals(attributes.getValue("", "use"));
    switch (role) {
      case SCHEMA -> {
        String targetNamespace = attributes.getValue("", "targetNamespace");
        schema.targetNamespace = targetNamespace == null ? "" : targetNamespace;
        schema.schemaTag = start;
        schema.attributesQualified =
            "qualified".equals(attributes.getValue("", "attributeFormDefault"));
      }
      case IMPORT -> {
        String namespace = attributes.getValue("", "namespace");
        schema.references.add(
            new FileReference(
                namespace == null ? "" : namespace,
                attributes.getValue("", "schemaLocation"),
                start,
                "xs:import",
                "schemaLocation"));
      }
      case INCLUDE ->
          schema.references.add(
              new FileReference(
                  null,
                  attributes.getValue("", "schemaLocation"),
                  start,
                  "xs:include",
                  "schemaLocation"));
      case GLOBAL -> {
        schema.elements.put(global(name), new GlobalElement(schema.name, null, start));
        frame.particles = new ArrayList<>();
        schema.elementParticles.put(global(name), frame.particles);
        frame.substitutes = member(name, attributes, start);
        frame.global = global(name);
        anonymousType(frame);
      }
      case GLOBAL_TYPED -> {
        schema.elements.put(
            global(name),
            new GlobalElement(schema.name, typeName(type, schema.elementTypes, start), start));
        member(name, attributes, start);
      }
      case LEAF -> {
        typeName(type, schema.elementTypes, start);
        parent.particles.add(new Particle(start, List.of()));
      }
      case GLOBAL_ATTRIBUTE -> {
        schema.attributes.add(global(name));
        simpleTypeName(type, start);
      }
      case ATTRIBUTE, PROHIBITED_ATTRIBUTE -> {
        simpleTypeName(type, start);
        // a local attribute is qualified by its form, or else by its schema's default
        String form = attributes.getValue("", "form");
        boolean qualified = form == null ? schema.attributesQualified : form.equals("qualified");
        QName attribute = new QName(qualified ? schema.targetNamespace : "", name);
        frame.attributeUses.uses.add(new AttributeUse(attribute, start, false, prohibited));
      }
      case ATTRIBUTE_REF -> {
        QName ref = qualified(attributes.getValue("", "ref"));
        schema.attributeRefs.add(new NameUse(ref, start));
        frame.attributeUses.uses.add(new AttributeUse(ref, start, false, prohibited));
      }
      case ELEMENT_REF -> {
        schema.elementRefs.add(new NameUse(qualified(attributes.getValue("", "ref")), start));
        parent.particles.add(new Particle(start, List.of()));
      }
      case STRUCTURE -> {
        // a group reference below it is reached through an element
        frame.uses = new HashSet<>();
        frame.particles = new ArrayList<>();
        parent.particles.add(new Particle(start, frame.particles));
        anonymousType(frame);
      }
      case SEQUENCE, ALL -> {
        frame.particles = new ArrayList<>();
        parent.particles.add(new Particle(start, frame.particles));
      }
      case GROUP -> {
        frame.uses = new HashSet<>();
        schema.groups.put(global(name), new Definition(start, frame.uses));
        frame.particles = new ArrayList<>();
        schema.groupParticles.put(global(name), frame.particles);
      }
      case GROUP_REF -> {
        QName ref = qualified(attributes.getValue("", "ref"));
        schema.groupRefs.add(new NameUse(ref, start));
        frame.uses.add(ref);
        parent.particles.add(new Particle(start, List.of()));
      }
      case ATTRIBUTE_GROUP -> {
        frame.uses = new HashSet<>();
        schema.attributeGroups.put(global(name), new Definition(start, frame.uses));
        frame.attributeUses = new AttributeOwner(schema.name);
        schema.groupAttributes.put(global(name), frame.attributeUses);
      }
      case ATTRIBUTE_GROUP_REF -> {
        QName ref = qualified(attributes.getValue("", "ref"));
        schema.attributeGroupRefs.add(new NameUse(ref, start));
        frame.uses.add(ref);
        frame.attributeUses.uses.add(new AttributeUse(ref, start, true, false));
      }
      case NAMED_COMPLEX_TYPE -> {
        schema.complexTypes.add(global(name));
        frame.particles = new ArrayList<>();
        schema.complexTypeParticles.put(global(name), frame.particles);
        frame.complexType = global(name);
        if (TRUE.equals(attributes.getValue("", "abstract"))) {
          schema.abstractTypes.put(global(name), start);
        }
        frame.attributeUses = new AttributeOwner(schema.name);
        schema.typeAttributes.put(global(name), frame.attributeUses);
      }
      case COMPLEX_CONTENT -> derive(frame, parent);
      case SIMPLE_CONTENT -> {
        derive(frame, parent);
        if (parent.complexType != null) {
          schema.simpleContentTypes.add(parent.complexType);
        }
      }
      case COMPLEX_DERIVATION -> {
        String value = attributes.getValue("", "base");
        QName base = qualified(value);
        // xs:anyType is the one complex built-in type, and a simple one has no element content
        if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(base.getNamespaceURI())) {
          throw refusal(
              UNSUPPORTED,
              "a derivation from the built-in type "
                  + value
                  + " is outside the XSD the reader reads");
        }
        NameUse use = new NameUse(base, start);
        schema.elementContentBases.add(use);
        derives(parent, use);
        inherits(frame, base, localName);
      }
      case SIMPLE_EXTENSION, SIMPLE_RESTRICTION -> {
        QName base = typeName(attributes.getValue("", "base"), schema.simpleContentBases, start);
        derives(parent, new NameUse(base, start));
        inherits(frame, base, localName);
      }
      case NAMED_SIMPLE_TYPE -> {
        simpleType = global(name);
        schema.simpleTypes.put(simpleType, new SimpleType(null, start));
      }
      case RESTRICTION -> {
        QName base = typeName(attributes.getValue("", "base"), schema.simpleTypeRefs, start);
        schema.simpleTypes.put(simpleType, new SimpleType(base, start));
      }
      // a global element of an anonymous simple type is no root (#89 review)
      case ANONYMOUS_SIMPLE_TYPE ->
          schema.elements.computeIfPresent(
              parent.global, (global, element) -> element.withAnonymousSimpleType());
      case ANONYMOUS_RESTRICTION ->
          typeName(attributes.getValue("", "base"), schema.simpleTypeRefs, start);
      // an anonymous complex type's attribute uses are its element's
      case DOCUMENT, COMPLEX_TYPE, STRUCTURE_TYPE, NO_EFFECT -> {
        // nothing to record
      }
    }
  }

  /**
   * Gives an element without a type the attribute uses its anonymous complex type, if it has one,
   * declares (#89 review); an anonymous simple type declares none.
   */
  private void anonymousType(Frame element) {
    element.attributeUses = new AttributeOwner(schema.name);
    schema.anonymousTypeAttributes.add(element.attributeUses);
  }

  /** {@code type}, a simple type's name, when an attribute has one rather than its own type. */
  private void simpleTypeName(String type, Position at) throws Refusal {
    if (type != null) {
      typeName(type, schema.simpleTypeRefs, at);
    }
  }

  /**
   * Records that the complex type whose derivation {@code frame} is, an extension or restriction by
   * {@code localName}, inherits the attribute uses of {@code base} (#89 review).
   */
  private static void inherits(Frame frame, QName base, String localName) {
    frame.attributeUses.base = base;
    frame.attributeUses.restriction = localName.equals("restriction");
  }

  /**
   * Records that the type {@code parent} stands in holds the content {@code frame} derives: nothing
   * may follow it, and its derivation names the type when that is a named one.
   */
  private static void derive(Frame frame, Frame parent) {
    parent.derived = true;
    frame.complexType = parent.complexType;
  }

  /**
   * Records the base of the named complex type whose content {@code content} is, if it is named.
   */
  private void derives(Frame content, NameUse base) {
    if (content.complexType != null) {
      schema.derivations.put(content.complexType, base);
    }
  }

  /**
   * Records whether the global element {@code name} is abstract, and its substitution group;
   * returns whether it has one.
   */
  private boolean member(String name, Attributes attributes, Position at) throws Refusal {
    if (TRUE.equals(attributes.getValue("", "abstract"))) {
      schema.abstractElements.put(global(name), at);
    }
    String head = attributes.getValue("", "substitutionGroup");
    if (head == null) {
      return false;
    }
    NameUse use = new NameUse(qualified(head), at);
    // the head is an element some bundle file declares, as an element reference's is
    schema.elementRefs.add(use);
    schema.substitutions.put(global(name), use);
    return true;
  }

  @Override
  public void endElement(String uri, String localName, String qName) {
    namespaces.popContext();
    if (skipped > 0) {
      skipped--;
      return;
    }
    Frame closed = open.pop();
    // a substitution-group member without a type has its head's (#95)
    if (closed.role == Role.GLOBAL && !closed.anonymousType && !closed.substitutes) {
      refuse(
          refusal(
              UNSUPPORTED,
              "<"
                  + qName
                  + "> without type, xs:complexType or xs:simpleType is outside the XSD the reader reads"));
    }
    // a content without its derivation is refused at its own start tag (#95)
    if (closed.role == Role.COMPLEX_CONTENT && closed.children == 0) {
      refuse(
          new Refusal(
              UNSUPPORTED,
              "<"
                  + qName
                  + "> without xs:extension or xs:restriction is outside the XSD the reader reads",
              closed.start));
    }
    if (closed.role == Role.SIMPLE_CONTENT && closed.children == 0) {
      refuse(
          new Refusal(
              UNSUPPORTED,
              "<"
                  + qName
                  + "> without xs:extension or xs:restriction is outside the XSD the reader reads",
              closed.start));
    }
    // a local element without type is refused at its own start tag
    if (closed.role == Role.STRUCTURE && !closed.anonymousType) {
      refuse(
          new Refusal(
              UNSUPPORTED,
              "<"
                  + qName
                  + "> without type, xs:complexType or xs:simpleType is outside the XSD the"
                  + " reader reads",
              closed.start));
    }
    // an attribute without either is refused at its own start tag (#89 review)
    if ((closed.role == Role.GLOBAL_ATTRIBUTE || closed.role == Role.ATTRIBUTE)
        && !closed.typed
        && !closed.anonymousType) {
      refuse(
          new Refusal(
              UNSUPPORTED,
              "<" + qName + "> without type or xs:simpleType is outside the XSD the reader reads",
              closed.start));
    }
    if (closed.role == Role.STRUCTURE_TYPE && closed.children == 0) {
      refuse(
          new Refusal(
              UNSUPPORTED,
              "an xs:element whose xs:complexType holds neither a content particle nor an"
                  + " attribute is outside the XSD the reader reads",
              open.element().start));
    }
    if (closed.role == Role.GROUP && closed.children == 0) {
      refuse(
          new Refusal(
              UNSUPPORTED,
              "<"
                  + qName
                  + "> without xs:sequence, xs:choice or xs:all is outside the XSD the reader"
                  + " reads",
              closed.start));
    }
  }

  @Override
  public void characters(char[] ch, int start, int length) {
    if (skipped == 0 && !new String(ch, start, length).isBlank()) {
      refuse(refusal(UNSUPPORTED, "text inside a schema is outside the XSD the reader reads"));
    }
  }

  /**
   * The name a {@code type} or {@code base} attribute gives. A built-in type must be a simple type
   * of {@link BuiltInTypes}; any other name is added to {@code uses}, to be found in the bundle.
   */
  private QName typeName(String value, List<NameUse> uses, Position at) throws Refusal {
    QName name = qualified(value);
    if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(name.getNamespaceURI())) {
      if (BuiltInTypes.valueType(name.getLocalPart()).isEmpty()) {
        throw refusal(UNSUPPORTED, "the type " + value + " is not a built-in simple type");
      }
    } else {
      uses.add(new NameUse(name, at));
    }
    return name;
  }

  /** A QName attribute value, its prefix bound by the namespaces in scope. */
  private QName qualified(String value) throws Refusal {
    String[] name = namespaces.processName(value, new String[3], false);
    if (name == null) {
      throw refusal(UNSUPPORTED, "the prefix of " + value + " is not declared");
    }
    return new QName(name[0], name[1]);
  }

  /** A global declaration's name, in the file's target namespace. */
  private QName global(String name) {
    return new QName(schema.targetNamespace, name);
  }

  private void checkOccurrence(String min, String max) throws Refusal {
    String minText = min == null ? "1" : min;
    String maxText = max == null ? "1" : max;
    if (!COUNT.matcher(minText).matches()) {
      throw refusal(INVALID, "minOccurs=\"" + min + "\" is not a non-negative integer");
    }
    if (!maxText.equals("unbounded") && !COUNT.matcher(maxText).matches()) {
      throw refusal(
          INVALID, "maxOccurs=\"" + max + "\" is neither a non-negative integer nor unbounded");
    }
    if (!maxText.equals("unbounded") && Long.parseLong(minText) > Long.parseLong(maxText)) {
      throw refusal(INVALID, "minOccurs=\"" + min + "\" is greater than maxOccurs=\"" + max + "\"");
    }
  }

  private void checkValue(Attributes attributes, String name, Pattern allowed) throws Refusal {
    String value = attributes.getValue("", name);
    if (value != null && !allowed.matcher(value).matches()) {
      throw refusal(UNSUPPORTED, name + "=\"" + value + "\" is outside the XSD the reader reads");
    }
  }

  private Position position() {
    return new Position(locator.getLineNumber(), locator.getColumnNumber());
  }

  private Refusal refusal(String code, String message) {
    return new Refusal(code, message, position());
  }

  private void refuse(Refusal refusal) {
    errors.add(
        new StructureLoadException(
            refusal.code,
            schema.name,
            refusal.at.line(),
            refusal.at.column(),
            refusal.getMessage()));
  }
}
