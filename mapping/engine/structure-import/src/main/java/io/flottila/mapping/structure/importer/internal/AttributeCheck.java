package io.flottila.mapping.structure.importer.internal;

import io.flottila.mapping.structure.importer.StructureImportDiagnostics;
import io.flottila.mapping.structure.importer.StructureLoadException;
import io.flottila.mapping.structure.importer.internal.SchemaFile.AttributeOwner;
import io.flottila.mapping.structure.importer.internal.SchemaFile.AttributeUse;
import io.flottila.mapping.structure.importer.internal.SchemaFile.Position;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;

/**
 * The check that no complex type, named or anonymous, has two attributes of one name (#89 review;
 * XSD 1.1 Part 1, 3.4.6.1 ct-props-correct.4), over what {@link XsdSubsetCheck} read from each
 * file. A type's attributes are its base's first, then its own, each attribute group reference's
 * inlined (XSD 1.1 Part 1, 3.4.2): an extension's own attribute of a name its base has is a second
 * declaration, a restriction's own replaces the base's, and {@code use="prohibited"} declares none.
 * A second declaration is {@code schema-invalid} at its own start tag, in the file that declares
 * it, once however many types reach it.
 */
final class AttributeCheck {

  private final Map<QName, AttributeOwner> types;
  private final Map<QName, AttributeOwner> groups;
  private final List<StructureLoadException> errors;

  /** The owners being expanded: a derivation or group that reaches itself stops there. */
  private final Set<AttributeOwner> visiting = new HashSet<>();

  /** The second declarations reported so far, by file and position. */
  private final Set<Map.Entry<String, Position>> reported = new HashSet<>();

  private AttributeCheck(
      Map<QName, AttributeOwner> types,
      Map<QName, AttributeOwner> groups,
      List<StructureLoadException> errors) {
    this.types = types;
    this.groups = groups;
    this.errors = errors;
  }

  /**
   * Adds an error for each second declaration of an attribute in a complex type of {@code files}.
   *
   * @param types the attribute uses of every named complex type of the bundle
   * @param groups the attribute uses of every named attribute group of the bundle
   */
  static void check(
      List<SchemaFile> files,
      Map<QName, AttributeOwner> types,
      Map<QName, AttributeOwner> groups,
      List<StructureLoadException> errors) {
    AttributeCheck check = new AttributeCheck(types, groups, errors);
    for (SchemaFile file : files) {
      file.typeAttributes.values().forEach(check::names);
      file.anonymousTypeAttributes.forEach(check::names);
    }
  }

  /**
   * The attribute names of a complex type, its base's among them. A derivation that reaches itself,
   * and a base no bundle file declares as a complex type, each reported apart or a simple type,
   * give none.
   */
  private Set<QName> names(AttributeOwner type) {
    Set<QName> names = new HashSet<>();
    if (!visiting.add(type)) {
      return names;
    }
    AttributeOwner base = type.base == null ? null : types.get(type.base);
    if (base != null) {
      names.addAll(names(base));
    }
    add(type.file, type.uses, type.restriction, names, new HashSet<>());
    visiting.remove(type);
    return names;
  }

  /**
   * Adds the names of {@code uses}, declared in {@code file}, to {@code names}, and reports each
   * that is a second declaration.
   *
   * @param restriction whether the type restricts its base, whose names it then replaces
   * @param own the names the type declares itself so far, through attribute groups too
   */
  private void add(
      String file, List<AttributeUse> uses, boolean restriction, Set<QName> names, Set<QName> own) {
    for (AttributeUse use : uses) {
      if (use.group()) {
        // a group no bundle file declares is reported apart, and one that reaches itself too
        AttributeOwner group = groups.get(use.name());
        if (group != null && visiting.add(group)) {
          add(group.file, group.uses, restriction, names, own);
          visiting.remove(group);
        }
      } else if (!use.prohibited()) {
        boolean second = !own.add(use.name()) || !restriction && names.contains(use.name());
        names.add(use.name());
        if (second && reported.add(Map.entry(file, use.at()))) {
          errors.add(
              new StructureLoadException(
                  StructureImportDiagnostics.SCHEMA_INVALID,
                  file,
                  use.at().line(),
                  use.at().column(),
                  "the attribute "
                      + BundleCheck.eqName(use.name())
                      + " is declared a second time in one complex type"));
        }
      }
    }
  }
}
