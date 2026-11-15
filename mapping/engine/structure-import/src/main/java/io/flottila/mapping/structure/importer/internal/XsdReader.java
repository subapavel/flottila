package io.flottila.mapping.structure.importer.internal;

import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldKind;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.Occurrence;
import io.flottila.mapping.structure.Refusal;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.StructureType;
import io.flottila.mapping.structure.ValueType;
import io.flottila.mapping.structure.importer.StructureLoadException;
import io.flottila.mapping.structure.importer.internal.SchemaFile.Particle;
import io.flottila.mapping.structure.importer.internal.SchemaFile.Position;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import org.apache.ws.commons.schema.XmlSchemaAll;
import org.apache.ws.commons.schema.XmlSchemaAny;
import org.apache.ws.commons.schema.XmlSchemaAttribute;
import org.apache.ws.commons.schema.XmlSchemaAttributeGroupRef;
import org.apache.ws.commons.schema.XmlSchemaChoice;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.XmlSchemaComplexContent;
import org.apache.ws.commons.schema.XmlSchemaComplexContentExtension;
import org.apache.ws.commons.schema.XmlSchemaComplexContentRestriction;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaContent;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaGroupRef;
import org.apache.ws.commons.schema.XmlSchemaParticle;
import org.apache.ws.commons.schema.XmlSchemaSequence;
import org.apache.ws.commons.schema.XmlSchemaSimpleContent;
import org.apache.ws.commons.schema.XmlSchemaSimpleContentExtension;
import org.apache.ws.commons.schema.XmlSchemaSimpleContentRestriction;
import org.apache.ws.commons.schema.XmlSchemaSimpleType;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeRestriction;
import org.apache.ws.commons.schema.XmlSchemaType;
import org.apache.ws.commons.schema.XmlSchemaUse;
import org.xml.sax.InputSource;

/**
 * Reads the XSD files of a schema bundle into one structure, with Apache XmlSchema (ADR-0008). The
 * SAX pass ({@link XsdSubsetCheck}) and the checks across the bundle ({@link BundleCheck}) run
 * first and give every load error its position; XmlSchema reads only a bundle that has none.
 *
 * <p>Derivation (#95): an extension gives the base content and then its own, and a restriction its
 * own particles; both inherit the base's attributes. An element of a complex type with simple
 * content is a leaf whose attributes are fields of their own. A reference to the head of a
 * substitution group is a choice of its non-abstract members. An abstract element, or an element of
 * an abstract type, carries the refusal {@code abstract-target-element} at the abstract
 * declaration.
 */
public final class XsdReader {

  /** Namespace first, then local name. */
  private static final Comparator<QName> BY_QNAME =
      Comparator.comparing(QName::getNamespaceURI).thenComparing(QName::getLocalPart);

  private final XmlSchemaCollection collection;
  private final Map<String, StructureType> types = new HashMap<>();

  /**
   * The particles of a complex type or a group and the file that declares it, which a refusal among
   * them names (#92, #94).
   *
   * @param particles its content particle, if it has one, or a group's model group
   */
  private record Declared(String file, List<Particle> particles) {}

  /** An element field and where it is declared: the file and the end of its start tag. */
  private record Placed(Field field, String file, Position at) {}

  /**
   * How a complex type is derived (#95): its base, null when it is not derived, its own attribute
   * declarations and attribute group references, and whether it is a restriction.
   */
  private record Derivation(QName base, List<?> attributes, boolean restriction) {}

  /** The particles of each global element's anonymous complex type, across the bundle. */
  private final Map<QName, Declared> elementParticles = new HashMap<>();

  /** The particles of each named complex type, across the bundle. */
  private final Map<QName, Declared> complexTypeParticles = new HashMap<>();

  /** The model group of each named group, across the bundle (#94). */
  private final Map<QName, Declared> groupParticles = new HashMap<>();

  /** The refusal of each abstract global element, at its declaration (#95). */
  private final Map<QName, Refusal> abstractElements = new HashMap<>();

  /** The refusal of each abstract named complex type, at its declaration (#95). */
  private final Map<QName, Refusal> abstractTypes = new HashMap<>();

  /** The elements whose substitution group each head is, across the bundle (#95). */
  private final Map<QName, List<QName>> members = new HashMap<>();

  /**
   * The id of each local element's anonymous complex type read so far, by its declaration (#94): an
   * element declared in a group is read at every use of the group, and every use shares its type.
   */
  private final Map<XmlSchemaElement, String> anonymousIds = new IdentityHashMap<>();

  private XsdReader(XmlSchemaCollection collection, List<SchemaFile> schemas) {
    this.collection = collection;
    // a refusal names the file that declares the type, whichever file declares the root (ADR-0008)
    for (SchemaFile schema : schemas) {
      schema.elementParticles.forEach(
          (name, particles) -> elementParticles.put(name, new Declared(schema.name, particles)));
      schema.complexTypeParticles.forEach(
          (name, particles) ->
              complexTypeParticles.put(name, new Declared(schema.name, particles)));
      schema.groupParticles.forEach(
          (name, particles) -> groupParticles.put(name, new Declared(schema.name, particles)));
      // an abstract declaration's refusal stands in its declaring file (ADR-0008)
      schema.abstractElements.forEach(
          (name, at) -> abstractElements.put(name, abstractTarget(schema.name, at)));
      schema.abstractTypes.forEach(
          (name, at) -> abstractTypes.put(name, abstractTarget(schema.name, at)));
      schema.substitutions.forEach(
          (member, head) ->
              members.computeIfAbsent(head.name(), h -> new ArrayList<>()).add(member));
    }
  }

  private static Refusal abstractTarget(String file, Position at) {
    return new Refusal(Refusal.Reason.ABSTRACT_TARGET_ELEMENT, file, at.line(), at.column());
  }

  /**
   * @param files the bundle's files, in bundle order
   * @param contents the bytes of each file the host's resolver read, by its name in {@code files}
   * @param errors the load errors so far, of files the resolver did not read; the read adds its own
   * @throws StructureLoadException with every load error, files in bundle order and each file in
   *     document order
   */
  public static Structure read(
      List<String> files,
      Map<String, byte[]> contents,
      List<StructureLoadException> errors,
      QName root)
      throws StructureLoadException {
    List<SchemaFile> schemas = new ArrayList<>();
    List<Consumer<XmlSchemaCollection>> reads = new ArrayList<>();
    for (String file : files) {
      byte[] content = contents.get(file);
      // a file the resolver did not read declares what is unknown, in a namespace unknown
      schemas.add(
          content == null ? new SchemaFile(file) : XsdSubsetCheck.check(file, content, errors));
      reads.add(document(file, content));
    }
    return read(
        files,
        schemas,
        errors,
        root,
        "the schema bundle declares no global element " + BundleCheck.eqName(root),
        reads);
  }

  /** XmlSchema's read of a whole XSD file. */
  static Consumer<XmlSchemaCollection> document(String file, byte[] content) {
    return collection -> {
      InputSource source = new InputSource(new ByteArrayInputStream(content));
      // the bundle name tells apart two files of one target namespace, such as an include's
      source.setSystemId(file);
      collection.read(source);
    };
  }

  /**
   * Checks the bundle across its files, then reads it with XmlSchema when it has no load error.
   *
   * @param schemas what the SAX passes read, every file's in bundle order
   * @param root the root element, or null when none is found
   * @param missing the message of {@code schema-root-not-found}
   * @param reads XmlSchema's read of each file, in bundle order; run only when there is no error
   */
  static Structure read(
      List<String> files,
      List<SchemaFile> schemas,
      List<StructureLoadException> errors,
      QName root,
      String missing,
      List<Consumer<XmlSchemaCollection>> reads)
      throws StructureLoadException {
    BundleCheck.check(files, schemas, root, missing, errors);
    if (!errors.isEmpty()) {
      List<StructureLoadException> ordered = new ArrayList<>(errors);
      ordered.sort(
          Comparator.comparingInt((StructureLoadException e) -> files.indexOf(e.file()))
              .thenComparingInt(StructureLoadException::line)
              .thenComparingInt(StructureLoadException::column));
      throw new StructureLoadException(ordered);
    }

    XmlSchemaCollection collection = new XmlSchemaCollection();
    // XmlSchema opens no file: its resolver declines every schemaLocation (ADR-0014 "Reference
    // resolver shape"), and a declined one is looked up among the schemas already read. Every
    // bundle file is read below, so every name resolves across the collection.
    collection.setSchemaResolver((namespace, schemaLocation, baseUri) -> null);
    reads.forEach(read -> read.accept(collection));
    XmlSchemaElement element = collection.getElementByQName(root);
    // a global element is always qualified; XmlSchema's getWireName() drops its namespace
    // (observed)
    FieldPath.Step rootStep = step(FieldKind.ELEMENT, element.getQName());
    XsdReader reader = new XsdReader(collection, schemas);
    Field rootField =
        reader.field(rootStep, new Occurrence(1, 1), element, "/" + rootStep, Optional.empty());
    return new Structure(rootField, reader.types);
  }

  /**
   * The field of a local element, or of {@code declaration}, a branch of an element reference,
   * inside the type or group {@code owner}, declared at {@code at} in {@code file}, with its
   * effective occurrence.
   */
  private Field field(
      String owner,
      XmlSchemaElement particle,
      XmlSchemaElement declaration,
      String file,
      Particle at,
      Occurrence occurrence) {
    // getWireName() applies the form: elementFormDefault="qualified" gives the namespace
    FieldPath.Step step =
        step(FieldKind.ELEMENT, particle.isRef() ? declaration.getQName() : particle.getWireName());
    String base = declaration.isTopLevel() ? "/" + step : owner + "/" + step;
    return field(
        step, occurrence, declaration, base, Optional.of(new Declared(file, at.content())));
  }

  /**
   * The global elements an element reference to {@code head} stands for: the head, or, when it
   * heads a substitution group, its non-abstract elements, transitively and across namespaces, the
   * head first and then by QName (ADR-0008), each a branch of a choice. An abstract head without a
   * non-abstract member stays, refused in a target.
   */
  private List<XmlSchemaElement> branches(QName head) {
    List<QName> substitutes = new ArrayList<>();
    addMembers(head, substitutes);
    substitutes.sort(BY_QNAME);
    List<QName> branches = new ArrayList<>(List.of(head));
    branches.addAll(substitutes);
    branches.removeIf(abstractElements::containsKey);
    if (branches.isEmpty()) {
      branches.add(head);
    }
    return branches.stream().map(collection::getElementByQName).toList();
  }

  /**
   * Adds the elements whose substitution group {@code head} is to {@code found}, transitively. The
   * bundle check has refused an element in its own substitution group, so this ends.
   */
  private void addMembers(QName head, List<QName> found) {
    for (QName member : members.getOrDefault(head, List.of())) {
      found.add(member);
      addMembers(member, found);
    }
  }

  /**
   * The field of an element declaration. A leaf names a simple type, or a complex type with simple
   * content, whose attributes are its child fields; a structure field has a named complex type or
   * an anonymous one.
   *
   * @param base the id of a local element's anonymous complex type, before any {@code #n}: its
   *     owner's id and its step; not read for a global element, whose anonymous type's id is its
   *     field path
   * @param local the particles of a local element's anonymous complex type, and the file that
   *     declares it; not read for a global element, whose type's particles are looked up by name
   */
  private Field field(
      FieldPath.Step step,
      Occurrence occurrence,
      XmlSchemaElement declaration,
      String base,
      Optional<Declared> local) {
    XmlSchemaElement typed = typed(declaration);
    QName typeName = typed.getSchemaTypeName();
    Optional<Refusal> refusal = abstractTarget(declaration, typeName);
    // an anonymous type is complex or simple (#89 review)
    XmlSchemaComplexType complexType =
        typeName == null
            ? typed.getSchemaType() instanceof XmlSchemaComplexType anonymous ? anonymous : null
            : complex(typeName);
    if (complexType == null) {
      return new Field(
          step,
          occurrence,
          Optional.of(valueType(typeName, typed.getSchemaType())),
          declaration.isNillable(),
          Optional.empty(),
          refusal);
    }
    String id;
    if (typeName != null) {
      id = shared(BundleCheck.eqName(typeName), complexType, complexTypeParticles.get(typeName));
    } else if (typed.isTopLevel()) {
      // a global element's anonymous type, its own or its head's, has that element's field path
      id =
          shared(
              "/" + step(FieldKind.ELEMENT, typed.getQName()),
              complexType,
              elementParticles.get(typed.getQName()));
    } else {
      id = local(base, typed, complexType, local.orElseThrow());
    }
    // simple content makes a leaf, whose attributes are fields of their own (ADR-0006 since #102)
    Optional<ValueType> valueType = simpleContent(complexType);
    return new Field(
        step,
        occurrence,
        valueType,
        valueType.isPresent() && declaration.isNillable(),
        Optional.of(id),
        refusal);
  }

  /**
   * The refusal of an abstract global element, or else of an element of an abstract named type,
   * each at its abstract declaration (#95). A local element is never abstract.
   */
  private Optional<Refusal> abstractTarget(XmlSchemaElement declaration, QName typeName) {
    Refusal own = declaration.isTopLevel() ? abstractElements.get(declaration.getQName()) : null;
    if (own != null || typeName == null) {
      return Optional.ofNullable(own);
    }
    return Optional.ofNullable(abstractTypes.get(typeName));
  }

  /**
   * The declaration whose type {@code declaration} has: itself, or for a substitution-group member
   * with neither a type nor an anonymous one, its head's, transitively (XSD 1.1 Part 1, 3.3.2.1).
   * The subset check refuses any other element without a type, and the bundle check an element in
   * its own substitution group, so this ends.
   */
  private XmlSchemaElement typed(XmlSchemaElement declaration) {
    XmlSchemaElement typed = declaration;
    while (typed.getSchemaTypeName() == null && typed.getSchemaType() == null) {
      typed = collection.getElementByQName(typed.getSubstitutionGroup());
    }
    return typed;
  }

  /** The named complex type {@code name}, or null for a simple type, built-in or named. */
  private XmlSchemaComplexType complex(QName name) {
    return !XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(name.getNamespaceURI())
            && collection.getTypeByQName(name) instanceof XmlSchemaComplexType named
        ? named
        : null;
  }

  /**
   * The value type of a complex type with simple content, by the built-in ancestry of its base;
   * empty for a complex type with element content. A restriction keeps its base's value type: its
   * facets are not validated (ADR-0004).
   */
  private Optional<ValueType> simpleContent(XmlSchemaComplexType complexType) {
    if (!(complexType.getContentModel() instanceof XmlSchemaSimpleContent)) {
      return Optional.empty();
    }
    QName base = derivation(complexType).base();
    XmlSchemaComplexType complexBase = complex(base);
    return complexBase == null ? Optional.of(valueType(base)) : simpleContent(complexBase);
  }

  /**
   * The id of a type every use shares: a named complex type ({@code Q{ns}local}) or a global
   * element's anonymous one (its field path). It is reserved before its fields are read, so a
   * recursive type refers to itself and is never expanded (ADR-0008). Every use shares its refusals
   * too, at their positions in the file that declares it (#92).
   */
  private String shared(String id, XmlSchemaComplexType complexType, Declared declared) {
    if (!types.containsKey(id)) {
      types.put(id, null);
      types.put(id, new StructureType(id, fields(id, complexType, declared)));
    }
    return id;
  }

  /**
   * The id of a local element's anonymous complex type: {@code base}, with {@code #2}, {@code #3} …
   * for each further sibling structure field with the same step (ADR-0007 refuses those only when a
   * mapping uses them, so they load as refused fields, #92). It is reserved before its fields are
   * read, and a declaration read again, in a group used twice or that refers to itself through the
   * element, gets the id it got first, so the recursion is never expanded (#94).
   */
  private String local(
      String base,
      XmlSchemaElement declaration,
      XmlSchemaComplexType complexType,
      Declared declared) {
    String known = anonymousIds.get(declaration);
    if (known != null) {
      return known;
    }
    String id = base;
    for (int n = 2; types.containsKey(id); n++) {
      id = base + "#" + n;
    }
    anonymousIds.put(declaration, id);
    types.put(id, null);
    types.put(id, new StructureType(id, fields(base, complexType, declared)));
    return id;
  }

  /**
   * The fields of a complex type: attributes first, then its element fields, which {@code
   * declared}'s particles declare.
   */
  private List<Field> fields(String owner, XmlSchemaComplexType complexType, Declared declared) {
    List<Field> fields = new ArrayList<>(attributes(complexType).values());
    List<Placed> elements = new ArrayList<>();
    elements(owner, complexType, declared, elements);
    fields.addAll(refused(elements));
    return fields;
  }

  /**
   * How {@code complexType} is derived. The subset check allows no other content than an extension
   * or restriction of complex or simple content, so the last case is a simple-content restriction.
   */
  private static Derivation derivation(XmlSchemaComplexType complexType) {
    XmlSchemaContent content =
        complexType.getContentModel() == null ? null : complexType.getContentModel().getContent();
    return switch (content) {
      case null -> new Derivation(null, complexType.getAttributes(), false);
      case XmlSchemaComplexContentExtension extension ->
          new Derivation(extension.getBaseTypeName(), extension.getAttributes(), false);
      case XmlSchemaComplexContentRestriction restriction ->
          new Derivation(restriction.getBaseTypeName(), restriction.getAttributes(), true);
      case XmlSchemaSimpleContentExtension extension ->
          new Derivation(extension.getBaseTypeName(), extension.getAttributes(), false);
      default -> {
        XmlSchemaSimpleContentRestriction restriction = (XmlSchemaSimpleContentRestriction) content;
        yield new Derivation(restriction.getBaseTypeName(), restriction.getAttributes(), true);
      }
    };
  }

  /**
   * The attribute fields of a complex type, by step in output order. A derived type has its base's
   * first, a simple type's none (XSD 1.1 Part 1, 3.4.2): an extension adds its own after them; a
   * restriction's own declaration of one replaces it where it stands, and its {@code
   * use="prohibited"} drops it (#95). A prohibited attribute is no field in any type, and in an
   * extension it drops nothing.
   */
  private Map<FieldPath.Step, Field> attributes(XmlSchemaComplexType complexType) {
    Derivation derivation = derivation(complexType);
    XmlSchemaComplexType complexBase =
        derivation.base() == null ? null : complex(derivation.base());
    Map<FieldPath.Step, Field> attributes =
        complexBase == null ? new LinkedHashMap<>() : attributes(complexBase);
    attributes(derivation.attributes(), derivation.restriction(), attributes);
    return attributes;
  }

  /**
   * Adds the attribute fields of {@code members}, attributes and attribute group references, to
   * {@code attributes} in document order, each reference's attribute group inlined (ADR-0008, #94),
   * and drops a prohibited one from a {@code restriction}. An attribute reference is a field named
   * by the referenced QName, with the global attribute's type and its own use (#89 review).
   */
  private void attributes(
      List<?> members, boolean restriction, Map<FieldPath.Step, Field> attributes) {
    for (Object member : members) {
      if (member instanceof XmlSchemaAttributeGroupRef reference) {
        attributes(reference.getRef().getTarget().getAttributes(), restriction, attributes);
        continue;
      }
      XmlSchemaAttribute attribute = (XmlSchemaAttribute) member;
      // a reference's wire name is the referenced QName (observed, XmlSchema 2.3.3)
      FieldPath.Step step = step(FieldKind.ATTRIBUTE, attribute.getWireName());
      if (attribute.getUse() != XmlSchemaUse.PROHIBITED) {
        boolean required = attribute.getUse() == XmlSchemaUse.REQUIRED;
        XmlSchemaAttribute declared =
            attribute.isRef() ? attribute.getRef().getTarget() : attribute;
        attributes.put(
            step,
            new Field(
                step,
                new Occurrence(required ? 1 : 0, 1),
                Optional.of(valueType(declared.getSchemaTypeName(), declared.getSchemaType())),
                false,
                Optional.empty()));
      } else if (restriction) {
        attributes.remove(step);
      }
    }
  }

  /**
   * Adds the element fields of a complex type to {@code elements}, in document order: an extension
   * of complex content gives its base's, read with the base's own particles, and then its own; a
   * restriction gives its own particles (ADR-0008); simple content gives none. The element fields
   * of the base are new fields of {@code owner}.
   */
  private void elements(
      String owner, XmlSchemaComplexType complexType, Declared declared, List<Placed> elements) {
    XmlSchemaParticle content = complexType.getParticle();
    if (complexType.getContentModel() instanceof XmlSchemaComplexContent complexContent) {
      if (complexContent.getContent() instanceof XmlSchemaComplexContentExtension extension) {
        QName base = extension.getBaseTypeName();
        // the bundle check has made sure the base is a complex type with element content that
        // does not derive from itself
        elements(owner, complex(base), complexTypeParticles.get(base), elements);
        content = extension.getParticle();
      } else {
        content = ((XmlSchemaComplexContentRestriction) complexContent.getContent()).getParticle();
      }
    }
    // a complex type of attributes only, or of simple content, has no content particle
    if (content != null) {
      particle(
          owner,
          content,
          declared.file(),
          declared.particles().getFirst(),
          new Occurrence(1, 1),
          Optional.empty(),
          Optional.empty(),
          elements);
    }
  }

  /**
   * Adds the element fields of {@code particle}, declared at {@code at} in {@code file}, to {@code
   * elements} in document order (ADR-0008 "XSD", #94): an element's own field, or an element
   * reference's branches (#95); the members of a sequence, a choice or an all, the branches of a
   * choice as siblings; and a group reference's group, inlined. A field's effective occurrence is
   * the product of its own occurrence and every enclosing particle's, and below a choice of two or
   * more branches its minimum is 0.
   *
   * <p>Streamed output writes all occurrences of one field together, so a group of two or more
   * particles that repeats cannot be written, unless it is a choice of single elements. Every field
   * below such a group carries interleaved-target-group, at the nearest enclosing particle, the
   * group itself among them, whose maxOccurs is above 1.
   *
   * @param owner the id of the type the fields belong to, or a group's EQName inside a group: a
   *     local element's anonymous type is named after it
   * @param enclosing the product of the enclosing particles' occurrences
   * @param repeated the refusal at the nearest enclosing particle whose maxOccurs is above 1, if
   *     any
   * @param interleaved the refusal of every field below, once an enclosing group repeats that
   *     streamed output cannot write
   */
  private void particle(
      String owner,
      XmlSchemaParticle particle,
      String file,
      Particle at,
      Occurrence enclosing,
      Optional<Refusal> repeated,
      Optional<Refusal> interleaved,
      List<Placed> elements) {
    // XmlSchema reads maxOccurs="unbounded" as Long.MAX_VALUE, which is Occurrence.UNBOUNDED
    Occurrence occurrence =
        new Occurrence(
            times(enclosing.min(), particle.getMinOccurs()),
            times(enclosing.max(), particle.getMaxOccurs()));
    if (particle instanceof XmlSchemaElement element) {
      List<XmlSchemaElement> declarations =
          element.isRef() ? branches(element.getRef().getTargetQName()) : List.of(element);
      // a branch of a choice with two or more branches has minimum 0 (ADR-0008)
      Occurrence each = declarations.size() > 1 ? new Occurrence(0, occurrence.max()) : occurrence;
      for (XmlSchemaElement declaration : declarations) {
        Field field = field(owner, element, declaration, file, at, each);
        // interleaved-target-group replaces abstract-target-element, both refused in a target only
        elements.add(new Placed(interleaved.map(field::withRefusal).orElse(field), file, at.at()));
      }
      return;
    }
    Optional<Refusal> here =
        particle.getMaxOccurs() > 1
            ? Optional.of(
                new Refusal(
                    Refusal.Reason.INTERLEAVED_TARGET_GROUP,
                    file,
                    at.at().line(),
                    at.at().column()))
            : repeated;
    if (particle instanceof XmlSchemaGroupRef reference) {
      QName name = reference.getRefName();
      Declared group = groupParticles.get(name);
      particle(
          BundleCheck.eqName(name),
          collection.getGroupByQName(name).getParticle(),
          group.file(),
          group.particles().getFirst(),
          occurrence,
          here,
          interleaved,
          elements);
      return;
    }
    boolean choice = particle instanceof XmlSchemaChoice;
    List<?> members =
        particle instanceof XmlSchemaSequence sequence
            ? sequence.getItems()
            : choice
                ? ((XmlSchemaChoice) particle).getItems()
                : ((XmlSchemaAll) particle).getItems();
    List<XmlSchemaParticle> items = new ArrayList<>();
    for (Object member : members) {
      // an xs:any has no effect (ADR-0008), and the subset check records no particle for it
      if (!(member instanceof XmlSchemaAny)) {
        items.add((XmlSchemaParticle) member);
      }
    }
    boolean interleaves =
        occurrence.max() > 1
            && items.size() > 1
            && !(choice && items.stream().allMatch(XmlSchemaElement.class::isInstance));
    // an xs:any is a branch too
    Occurrence member =
        choice && members.size() > 1 ? new Occurrence(0, occurrence.max()) : occurrence;
    for (int i = 0; i < items.size(); i++) {
      particle(
          owner,
          items.get(i),
          file,
          at.content().get(i),
          member,
          here,
          interleaves ? here : interleaved,
          elements);
    }
  }

  /** {@code a} times {@code b}, two occurrence bounds; a product beyond a long is unbounded. */
  private static long times(long a, long b) {
    return b != 0 && a > Occurrence.UNBOUNDED / b ? Occurrence.UNBOUNDED : a * b;
  }

  /**
   * {@code elements}, in output order, with each one whose step occurs twice or more refused with
   * ambiguous-field-path at that step's second declaration, in the file that declares it (ADR-0008
   * Shape (#92)); the branches of a substitution group share their reference's declaration. A field
   * carries at most one refusal: ambiguous-field-path, which holds in a source too, replaces
   * interleaved-target-group (#94) and abstract-target-element (#95). Attributes are not looked at:
   * two of one name in one complex type make the schema invalid (XSD 1.1 Part 1,
   * ct-props-correct.4), and {@link AttributeCheck} has refused them (#89 review).
   */
  private static List<Field> refused(List<Placed> elements) {
    Set<FieldPath.Step> seen = new HashSet<>();
    Map<FieldPath.Step, Placed> second = new HashMap<>();
    for (Placed element : elements) {
      FieldPath.Step step = element.field().step();
      if (!seen.add(step)) {
        second.putIfAbsent(step, element);
      }
    }
    List<Field> fields = new ArrayList<>();
    for (Placed element : elements) {
      Field field = element.field();
      Placed at = second.get(field.step());
      fields.add(
          at == null
              ? field
              : field.withRefusal(
                  new Refusal(
                      Refusal.Reason.AMBIGUOUS_FIELD_PATH,
                      at.file(),
                      at.at().line(),
                      at.at().column())));
    }
    return fields;
  }

  /**
   * The value type of a simple type by its built-in ancestry (ADR-0008). A list is one value and a
   * union a string. The bundle check has made sure that a named type is declared and derives from a
   * built-in type of {@link BuiltInTypes}.
   */
  private ValueType valueType(QName type) {
    if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(type.getNamespaceURI())) {
      return BuiltInTypes.valueType(type.getLocalPart()).orElseThrow();
    }
    return valueType((XmlSchemaSimpleType) collection.getTypeByQName(type));
  }

  /**
   * The value type of a simple type, named or anonymous (#89 review), by its built-in ancestry. The
   * subset check allows an anonymous simple type no restriction without a {@code base}.
   */
  private ValueType valueType(XmlSchemaSimpleType simple) {
    return simple.getContent() instanceof XmlSchemaSimpleTypeRestriction restriction
        ? valueType(restriction.getBaseTypeName())
        : ValueType.STRING;
  }

  /**
   * The value type of an element's or attribute's simple type: the one {@code name} names, or else
   * its own, {@code anonymous} (#89 review).
   */
  private ValueType valueType(QName name, XmlSchemaType anonymous) {
    return name != null ? valueType(name) : valueType((XmlSchemaSimpleType) anonymous);
  }

  private static FieldPath.Step step(FieldKind kind, QName name) {
    return new FieldPath.Step(kind, name.getNamespaceURI(), name.getLocalPart());
  }
}
