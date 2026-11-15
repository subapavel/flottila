package io.flottila.mapping.structure.importer.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;

/**
 * What {@link XsdSubsetCheck} reads from one schema, or {@link WsdlCheck} from the WSDL parts of a
 * WSDL file, for the checks across the bundle: its target namespace, its references to other files,
 * the global names it declares and the names it uses, each with its position. A WSDL file is one
 * WSDL part and one schema per inline schema, all with the file's name. For {@link XsdReader} a
 * schema also keeps where each particle of a complex type or a group is declared (#92, #94).
 */
final class SchemaFile {

  /** A position in the file: just after a start tag, where SAX reports it. */
  record Position(int line, int column) {}

  /**
   * An {@code xs:import} or {@code xs:include}, or a {@code wsdl:import} or {@code wsdl:include}.
   *
   * @param namespace the namespace an import names, {@code ""} for none; null for an include
   * @param location the location as written, or null
   * @param element the element as messages name it, {@code xs:import}
   * @param attribute the attribute that holds the location, {@code schemaLocation}
   */
  record FileReference(
      String namespace, String location, Position at, String element, String attribute) {}

  /**
   * A global element.
   *
   * @param type its {@code type}, or null for an anonymous type
   * @param simple whether its anonymous type is a simple type (#89 review)
   */
  record GlobalElement(String file, QName type, boolean simple, Position at) {

    GlobalElement(String file, QName type, Position at) {
      this(file, type, false, at);
    }

    GlobalElement withAnonymousSimpleType() {
      return new GlobalElement(file, type, true, at);
    }
  }

  /**
   * A named simple type.
   *
   * @param base the base of its restriction, or null for a list or a union
   * @param at its restriction's position, or the type's own for a list or a union
   */
  record SimpleType(QName base, Position at) {}

  /** A name the file uses, other than a built-in type. */
  record NameUse(QName name, Position at) {}

  /**
   * A particle of a complex type or a group (#92, #94): a local element or an element reference, a
   * model group ({@code xs:sequence}, {@code xs:choice}, {@code xs:all}), or a group reference. An
   * {@code xs:any} is no particle here.
   *
   * @param at where its start tag ends
   * @param content the particles it holds, in document order: a model group's members, or the one
   *     content particle of a local element's anonymous complex type, if it has one; none for an
   *     element reference, a typed element or a group reference
   */
  record Particle(Position at, List<Particle> content) {}

  /**
   * A named group or attribute group (#94).
   *
   * @param at its start tag's end
   * @param uses the groups, or attribute groups, it refers to other than through an element
   */
  record Definition(Position at, Set<QName> uses) {}

  /**
   * An attribute declaration or reference, or an attribute group reference, of a complex type or an
   * attribute group, in document order (#89 review).
   *
   * @param name the attribute's name, as its field's step names it, or the attribute group's
   * @param group whether it is an attribute group reference
   * @param prohibited whether it has {@code use="prohibited"}
   */
  record AttributeUse(QName name, Position at, boolean group, boolean prohibited) {}

  /**
   * The attribute uses a complex type, named or anonymous, or an attribute group declares, for the
   * check that no complex type has two attributes of one name (#89 review; XSD 1.1 Part 1, 3.4.6.1
   * ct-props-correct.4).
   */
  static final class AttributeOwner {

    /** The file that declares it, where its uses stand. */
    final String file;

    final List<AttributeUse> uses = new ArrayList<>();

    /** The base of a complex type's derivation; null when it does not derive. */
    QName base;

    /** Whether the derivation is a restriction, whose own attributes replace its base's. */
    boolean restriction;

    AttributeOwner(String file) {
      this.file = file;
    }
  }

  /** The file as the schema bundle spells it. */
  final String name;

  /**
   * Whether it is the WSDL part of a WSDL file: its target namespace is a WSDL one, which an {@code
   * xs:import} never falls back to.
   */
  final boolean wsdl;

  /**
   * Its target namespace, {@code ""} for none; null until its {@code xs:schema} is read, and for a
   * file the host's resolver did not read.
   */
  String targetNamespace;

  /** Its {@code xs:schema}, or its WSDL document element, start tag; null until it is read. */
  Position schemaTag;

  /** Whether it stopped being well-formed: what it declares after that is unknown. */
  boolean stopped;

  /** Global names whose declarations are refused: declared, but not read. */
  final Set<QName> refused = new HashSet<>();

  final List<FileReference> references = new ArrayList<>();
  final Map<QName, GlobalElement> elements = new LinkedHashMap<>();
  final Set<QName> complexTypes = new HashSet<>();
  final Map<QName, SimpleType> simpleTypes = new LinkedHashMap<>();

  /** Element references. */
  final List<NameUse> elementRefs = new ArrayList<>();

  /** Types of elements: a complex or a simple type. */
  final List<NameUse> elementTypes = new ArrayList<>();

  /** Types of attributes and bases of restrictions: a simple type. */
  final List<NameUse> simpleTypeRefs = new ArrayList<>();

  /** WSDL 1.1 messages. */
  final Set<QName> messages = new HashSet<>();

  /** WSDL 1.1 message references of operations. */
  final List<NameUse> messageRefs = new ArrayList<>();

  /** Named groups (#94). */
  final Map<QName, Definition> groups = new HashMap<>();

  /** Named attribute groups (#94). */
  final Map<QName, Definition> attributeGroups = new HashMap<>();

  /** Group references (#94). */
  final List<NameUse> groupRefs = new ArrayList<>();

  /** Attribute group references (#94). */
  final List<NameUse> attributeGroupRefs = new ArrayList<>();

  /** The content particle of each global element's anonymous complex type, if it has one. */
  final Map<QName, List<Particle>> elementParticles = new HashMap<>();

  /** The content particle of each named complex type, if it has one. */
  final Map<QName, List<Particle>> complexTypeParticles = new HashMap<>();

  /** The model group of each named group (#94). */
  final Map<QName, List<Particle>> groupParticles = new HashMap<>();

  /** The bases of {@code xs:complexContent} derivations: a complex type with element content. */
  final List<NameUse> elementContentBases = new ArrayList<>();

  /**
   * The bases of {@code xs:simpleContent} extensions other than built-in types: a simple type or a
   * complex type with simple content.
   */
  final List<NameUse> simpleContentBases = new ArrayList<>();

  /** The named complex types with simple content. */
  final Set<QName> simpleContentTypes = new HashSet<>();

  /** The base of each named complex type that derives, at its derivation (#95). */
  final Map<QName, NameUse> derivations = new HashMap<>();

  /** The head of each global element in a substitution group, at the element (#95). */
  final Map<QName, NameUse> substitutions = new HashMap<>();

  /** Where each abstract global element is declared (#95). */
  final Map<QName, Position> abstractElements = new HashMap<>();

  /** Where each abstract named complex type is declared (#95). */
  final Map<QName, Position> abstractTypes = new HashMap<>();

  /** Whether its {@code attributeFormDefault} is {@code qualified} (#89 review). */
  boolean attributesQualified;

  /** Global attributes (#89 review). */
  final Set<QName> attributes = new HashSet<>();

  /** Attribute references (#89 review). */
  final List<NameUse> attributeRefs = new ArrayList<>();

  /** The attribute uses of each named complex type (#89 review). */
  final Map<QName, AttributeOwner> typeAttributes = new HashMap<>();

  /** The attribute uses of each anonymous complex type (#89 review). */
  final List<AttributeOwner> anonymousTypeAttributes = new ArrayList<>();

  /** The attribute uses of each named attribute group (#89 review). */
  final Map<QName, AttributeOwner> groupAttributes = new HashMap<>();

  SchemaFile(String name) {
    this(name, false);
  }

  SchemaFile(String name, boolean wsdl) {
    this.name = name;
    this.wsdl = wsdl;
  }
}
