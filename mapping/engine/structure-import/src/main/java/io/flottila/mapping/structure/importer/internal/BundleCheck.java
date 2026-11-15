package io.flottila.mapping.structure.importer.internal;

import io.flottila.mapping.structure.importer.StructureImportDiagnostics;
import io.flottila.mapping.structure.importer.StructureLoadException;
import io.flottila.mapping.structure.importer.internal.SchemaFile.AttributeOwner;
import io.flottila.mapping.structure.importer.internal.SchemaFile.Definition;
import io.flottila.mapping.structure.importer.internal.SchemaFile.FileReference;
import io.flottila.mapping.structure.importer.internal.SchemaFile.GlobalElement;
import io.flottila.mapping.structure.importer.internal.SchemaFile.NameUse;
import io.flottila.mapping.structure.importer.internal.SchemaFile.Position;
import io.flottila.mapping.structure.importer.internal.SchemaFile.SimpleType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

/**
 * The checks across the files of a schema bundle (ADR-0008 as amended 2026-11-05), over what {@link
 * XsdSubsetCheck} read from each.
 *
 * <ul>
 *   <li>The bundle is a closed set. An {@code xs:import}'s or {@code xs:include}'s {@code
 *       schemaLocation} is mapped to a bundle file by text, relative to the importing file's bundle
 *       name, with no file-system call (ADR-0014 "Reference resolver shape (#90)"). An import whose
 *       location names no bundle file, an absolute path or a URL among them, or that has no
 *       location, falls back to a bundle file with its namespace, or one whose namespace is unknown
 *       because it was not read as far as its {@code xs:schema}. Otherwise the reference is {@code
 *       schema-reference-outside-bundle}. A {@code wsdl:import} or {@code wsdl:include} follows the
 *       same rule, and an import may also fall back to a WSDL of its namespace (#96).
 *   <li>Names are checked in every file, whatever load errors other files have: every name a file
 *       uses, a WSDL 1.1 message and an attribute reference's global attribute (#89 review) among
 *       them, must be declared by a bundle file, and a type must not derive from itself. The base
 *       of {@code xs:complexContent} is a complex type with element content, that of {@code
 *       xs:simpleContent} a simple type or a complex type with simple content, and no element is in
 *       its own substitution group (#95). A name that may be declared where nothing was read is not
 *       reported, so one load error gives no second: the name of a refused global declaration, any
 *       name in the namespace of a file that stopped being well-formed or of a reference outside
 *       the bundle, and any name at all once a file was not read as far as its {@code xs:schema}. A
 *       named group or attribute group must not refer to itself other than through an element
 *       (#94). No complex type has two attributes of one name ({@link AttributeCheck}, #89 review).
 *   <li>The root must be a global element of a complex type, never of an anonymous simple type. A
 *       missing root, or a WSDL operation's message that leads to none, is reported only when the
 *       bundle has no other load error.
 * </ul>
 */
final class BundleCheck {

  private BundleCheck() {}

  /**
   * @param root the root element; null when none is found, for a WSDL operation (#96)
   * @param missing the message of {@code schema-root-not-found} when the bundle declares no root
   */
  static void check(
      List<String> bundle,
      List<SchemaFile> files,
      QName root,
      String missing,
      List<StructureLoadException> errors) {
    // a file not read as far as its xs:schema adds null: its namespace is unknown. An xs:import
    // falls back to a schema's namespace, a wsdl:import also to a WSDL's (#96).
    Set<String> namespaces = new HashSet<>();
    Set<String> withWsdl = new HashSet<>();
    for (SchemaFile file : files) {
      withWsdl.add(file.targetNamespace);
      if (!file.wsdl) {
        namespaces.add(file.targetNamespace);
      }
    }
    // the namespaces of the files the references outside the bundle stand for
    Set<String> unreachable = new HashSet<>();
    for (SchemaFile file : files) {
      Set<String> candidates = file.wsdl ? withWsdl : namespaces;
      for (FileReference reference : file.references) {
        boolean listed =
            reference.location() != null
                && bundle.contains(located(file.name, reference.location()));
        // an include has no namespace to fall back to; an import falls back to a file of its
        // namespace, or to one whose namespace is unknown
        boolean fallback =
            reference.namespace() != null
                && (candidates.contains(reference.namespace()) || candidates.contains(null));
        if (!listed && !fallback) {
          errors.add(outside(file.name, reference));
          // an included file has the including file's namespace
          unreachable.add(
              reference.namespace() == null ? file.targetNamespace : reference.namespace());
        }
      }
    }
    names(files, root, missing, unreachable, errors);
  }

  /**
   * The bundle name {@code location} stands for, written in the file {@code file}, by text: its
   * segments appended to the directory of {@code file}, {@code .} dropped and {@code ..} taking off
   * the segment before it, if there is one that is not {@code ..} itself. An absolute path keeps an
   * empty segment and a URL its scheme, so neither is ever a bundle name (ADR-0024).
   */
  static String located(String file, String location) {
    List<String> segments = new ArrayList<>(List.of(file.split("/", -1)));
    segments.removeLast();
    for (String segment : location.split("/", -1)) {
      if (segment.equals("..") && !segments.isEmpty() && !segments.getLast().equals("..")) {
        segments.removeLast();
      } else if (!segment.equals(".")) {
        segments.add(segment);
      }
    }
    return String.join("/", segments);
  }

  private static StructureLoadException outside(String file, FileReference reference) {
    String message;
    if (reference.location() == null) {
      message =
          "no file of the schema bundle has the namespace \""
              + reference.namespace()
              + "\" this "
              + reference.element()
              + " names";
    } else {
      message =
          "the "
              + reference.attribute()
              + " \""
              + reference.location()
              + "\" of this "
              + reference.element()
              + " names no file of the schema bundle";
      if (reference.namespace() != null) {
        message += ", and no bundle file has its namespace \"" + reference.namespace() + "\"";
      }
    }
    return error(
        StructureImportDiagnostics.SCHEMA_REFERENCE_OUTSIDE_BUNDLE, file, reference.at(), message);
  }

  private static void names(
      List<SchemaFile> files,
      QName root,
      String missing,
      Set<String> unreachable,
      List<StructureLoadException> errors) {
    Map<QName, GlobalElement> elements = new HashMap<>();
    Set<QName> complexTypes = new HashSet<>();
    Map<QName, SimpleType> simpleTypes = new HashMap<>();
    Set<QName> messages = new HashSet<>();
    Map<QName, Definition> groups = new HashMap<>();
    Map<QName, Definition> attributeGroups = new HashMap<>();
    Set<QName> refused = new HashSet<>();
    Set<QName> simpleContent = new HashSet<>();
    Set<QName> attributes = new HashSet<>();
    Map<QName, AttributeOwner> typeAttributes = new HashMap<>();
    Map<QName, AttributeOwner> groupAttributes = new HashMap<>();
    // the base of every type that derives, simple or complex, and the head of every element in a
    // substitution group (#95)
    Map<QName, QName> bases = new HashMap<>();
    Map<QName, QName> heads = new HashMap<>();
    // the namespaces of files that stopped, were never read or are outside the bundle, null for
    // one never read as far as its xs:schema: what they declare is unknown
    Set<String> unknown = new HashSet<>(unreachable);
    for (SchemaFile file : files) {
      elements.putAll(file.elements);
      complexTypes.addAll(file.complexTypes);
      simpleTypes.putAll(file.simpleTypes);
      messages.addAll(file.messages);
      groups.putAll(file.groups);
      attributeGroups.putAll(file.attributeGroups);
      refused.addAll(file.refused);
      simpleContent.addAll(file.simpleContentTypes);
      attributes.addAll(file.attributes);
      typeAttributes.putAll(file.typeAttributes);
      groupAttributes.putAll(file.groupAttributes);
      file.simpleTypes.forEach((name, type) -> bases.put(name, type.base()));
      file.derivations.forEach((name, base) -> bases.put(name, base.name()));
      file.substitutions.forEach((name, head) -> heads.put(name, head.name()));
      if (file.stopped || file.targetNamespace == null) {
        unknown.add(file.targetNamespace);
      }
    }
    Set<QName> elementContent = new HashSet<>(complexTypes);
    elementContent.removeAll(simpleContent);
    Set<QName> simpleValued = new HashSet<>(simpleTypes.keySet());
    simpleValued.addAll(simpleContent);
    // a name that a refused declaration, or a file whose declarations are unknown, may declare is
    // never a second error
    Predicate<QName> undeclared =
        name ->
            !refused.contains(name)
                && !unknown.contains(name.getNamespaceURI())
                && !unknown.contains(null);
    for (SchemaFile file : files) {
      for (NameUse use : file.elementRefs) {
        if (!elements.containsKey(use.name()) && undeclared.test(use.name())) {
          errors.add(invalid(file, use, "the element " + eqName(use.name()) + " is declared"));
        }
      }
      for (NameUse use : file.attributeRefs) {
        if (!attributes.contains(use.name()) && undeclared.test(use.name())) {
          errors.add(invalid(file, use, "the attribute " + eqName(use.name()) + " is declared"));
        }
      }
      for (NameUse use : file.messageRefs) {
        if (!messages.contains(use.name()) && undeclared.test(use.name())) {
          errors.add(invalid(file, use, "the message " + eqName(use.name()) + " is declared"));
        }
      }
      for (NameUse use : file.elementTypes) {
        if (!complexTypes.contains(use.name())
            && !simpleTypes.containsKey(use.name())
            && undeclared.test(use.name())) {
          errors.add(invalid(file, use, "the type " + eqName(use.name()) + " is declared"));
        }
      }
      for (NameUse use : file.simpleTypeRefs) {
        if (!simpleTypes.containsKey(use.name()) && undeclared.test(use.name())) {
          errors.add(
              complexTypes.contains(use.name())
                  ? error(
                      StructureImportDiagnostics.SCHEMA_INVALID,
                      file.name,
                      use.at(),
                      "the type "
                          + eqName(use.name())
                          + " is a complex type, where a simple type is needed")
                  : invalid(file, use, "the simple type " + eqName(use.name()) + " is declared"));
        }
      }
      for (NameUse use : file.elementContentBases) {
        if (!elementContent.contains(use.name()) && undeclared.test(use.name())) {
          errors.add(
              invalid(
                  file,
                  use,
                  "the complex type " + eqName(use.name()) + " with element content is declared"));
        }
      }
      for (NameUse use : file.simpleContentBases) {
        if (!simpleValued.contains(use.name()) && undeclared.test(use.name())) {
          errors.add(
              invalid(
                  file,
                  use,
                  "the simple type or complex type with simple content "
                      + eqName(use.name())
                      + " is declared"));
        }
      }
      for (Map.Entry<QName, SimpleType> type : file.simpleTypes.entrySet()) {
        if (cyclic(type.getKey(), bases)) {
          errors.add(
              error(
                  StructureImportDiagnostics.SCHEMA_INVALID,
                  file.name,
                  type.getValue().at(),
                  "the simple type " + eqName(type.getKey()) + " derives from itself"));
        }
      }
      for (Map.Entry<QName, NameUse> type : file.derivations.entrySet()) {
        if (cyclic(type.getKey(), bases)) {
          errors.add(
              error(
                  StructureImportDiagnostics.SCHEMA_INVALID,
                  file.name,
                  type.getValue().at(),
                  "the complex type " + eqName(type.getKey()) + " derives from itself"));
        }
      }
      for (Map.Entry<QName, NameUse> element : file.substitutions.entrySet()) {
        if (cyclic(element.getKey(), heads)) {
          errors.add(
              error(
                  StructureImportDiagnostics.SCHEMA_INVALID,
                  file.name,
                  element.getValue().at(),
                  "the element " + eqName(element.getKey()) + " is in its own substitution group"));
        }
      }
      definitions(file, file.groupRefs, file.groups, groups, "group", undeclared, errors);
      definitions(
          file,
          file.attributeGroupRefs,
          file.attributeGroups,
          attributeGroups,
          "attribute group",
          undeclared,
          errors);
    }
    AttributeCheck.check(files, typeAttributes, groupAttributes, errors);
    // a null root, a WSDL operation's that is not found, is no key
    GlobalElement rootElement = elements.get(root);
    if (rootElement == null) {
      // the root selector picks among the global elements of a bundle that loads: beside another
      // load error it is not looked up (and then every file has its xs:schema or WSDL tag)
      if (!errors.isEmpty()) {
        return;
      }
      SchemaFile first = files.getFirst();
      errors.add(
          error(
              StructureImportDiagnostics.SCHEMA_ROOT_NOT_FOUND,
              first.name,
              first.schemaTag,
              missing));
    } else if (rootElement.simple()
        || rootElement.type() != null
            && (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(rootElement.type().getNamespaceURI())
                || simpleTypes.containsKey(rootElement.type()))) {
      errors.add(
          error(
              StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED,
              rootElement.file(),
              rootElement.at(),
              "the root element "
                  + eqName(root)
                  + " has a simple type; a root element has a complex type"));
    }
  }

  /**
   * Whether following {@code bases} from {@code name} comes back to it: a type that derives from
   * itself, or an element in its own substitution group.
   */
  private static boolean cyclic(QName name, Map<QName, QName> bases) {
    Set<QName> seen = new HashSet<>();
    QName base = bases.get(name);
    while (bases.containsKey(base) && seen.add(base)) {
      base = bases.get(base);
    }
    return seen.contains(name);
  }

  /**
   * The checks of one kind of named group in {@code file} (#94): each of its references {@code
   * uses} names one of {@code all}, the bundle's, unless a declaration that was not read may
   * declare it; and none of its own {@code declared} refers to itself, directly or through others,
   * other than through an element, because inlining it would never end.
   *
   * @param what the kind as messages name it, {@code group}
   */
  private static void definitions(
      SchemaFile file,
      List<NameUse> uses,
      Map<QName, Definition> declared,
      Map<QName, Definition> all,
      String what,
      Predicate<QName> undeclared,
      List<StructureLoadException> errors) {
    for (NameUse use : uses) {
      if (!all.containsKey(use.name()) && undeclared.test(use.name())) {
        errors.add(invalid(file, use, "the " + what + " " + eqName(use.name()) + " is declared"));
      }
    }
    for (Map.Entry<QName, Definition> definition : declared.entrySet()) {
      Set<QName> reached = new HashSet<>();
      Deque<QName> next = new ArrayDeque<>(definition.getValue().uses());
      while (!next.isEmpty()) {
        QName name = next.pop();
        Definition used = all.get(name);
        // a name no file declares is reported above, or may be declared where nothing was read
        if (used != null && reached.add(name)) {
          next.addAll(used.uses());
        }
      }
      if (reached.contains(definition.getKey())) {
        errors.add(
            error(
                StructureImportDiagnostics.SCHEMA_INVALID,
                file.name,
                definition.getValue().at(),
                "the " + what + " " + eqName(definition.getKey()) + " refers to itself"));
      }
    }
  }

  private static StructureLoadException invalid(SchemaFile file, NameUse use, String what) {
    return error(
        StructureImportDiagnostics.SCHEMA_INVALID,
        file.name,
        use.at(),
        what + " in no file of the schema bundle");
  }

  private static StructureLoadException error(
      String code, String file, Position at, String message) {
    return new StructureLoadException(code, file, at.line(), at.column(), message);
  }

  /** {@code Q{ns}local}, the EQName of field paths (ADR-0007). */
  static String eqName(QName name) {
    return "Q{" + name.getNamespaceURI() + "}" + name.getLocalPart();
  }
}
