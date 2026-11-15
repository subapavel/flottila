package io.flottila.mapping.structure.importer;

import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.RootSelector;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.WsdlRoot;
import io.flottila.mapping.structure.importer.internal.JsonSchemaReader;
import io.flottila.mapping.structure.importer.internal.WsdlReader;
import io.flottila.mapping.structure.importer.internal.XsdReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

/**
 * Reads a schema bundle into a structure (ADR-0008). An element root reads XSD bundles of one or
 * more files, linked by {@code xs:import} and {@code xs:include}, with global elements, element
 * references and named types (#93); choices, alls and groups (#94); and derivation and substitution
 * groups (#95). A WSDL root reads WSDL 1.1 and 2.0 bundles under an operation's message (#96). The
 * JSON root reads JSON Schema 2020-12 (#97). It reads every file the bundle lists through the
 * host's {@link ReferenceResolver}, and no other file, and opens no file itself (ADR-0014
 * "Reference resolver shape").
 */
public final class StructureReader {

  private final ReferenceResolver resolver;

  /**
   * @param resolver the host's resolver, bound to the base the bundle's references are relative to
   */
  public StructureReader(ReferenceResolver resolver) {
    this.resolver = resolver;
  }

  /**
   * Reads the structure under {@code root}. An unchecked exception from the resolver passes through
   * unchanged, at once.
   *
   * @throws StructureLoadException with every load error of the bundle ({@link
   *     StructureLoadException#errors}): a file that cannot be read or is not well-formed, a
   *     construct the reader does not read, a reference outside the bundle, a name no bundle file
   *     declares, or no such root
   */
  public Structure read(SchemaBundle bundle, RootSelector root) throws StructureLoadException {
    List<String> files = bundle.files().stream().distinct().toList();
    if (files.isEmpty()) {
      throw new StructureLoadException(
          StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED,
          "",
          1,
          1,
          "a schema bundle lists at least one file");
    }
    BundleReader reader =
        switch (root) {
          case ElementRoot(String name) ->
              (all, contents, errors) -> XsdReader.read(all, contents, errors, eqName(name));
          case WsdlRoot(WsdlOperation operation) ->
              (all, contents, errors) ->
                  WsdlReader.read(
                      all, contents, errors, eqName(operation.interfaceName()), operation);
          case JsonRoot json -> JsonSchemaReader::read;
        };
    // each file the bundle lists is read once, in bundle order, and no other file (ADR-0008)
    List<StructureLoadException> errors = new ArrayList<>();
    Map<String, byte[]> contents = new LinkedHashMap<>();
    for (String file : files) {
      try {
        // a load error inside the file names the reference asked for, whatever the host names it
        contents.put(file, resolver.resolve(file).content());
      } catch (UnresolvedReferenceException e) {
        // the engine's code and message, at the reference as the bundle spells it
        String code =
            switch (e.reason()) {
              case UNREADABLE -> StructureImportDiagnostics.SCHEMA_UNREADABLE;
              case PACKAGE_REFERENCE_UNSUPPORTED ->
                  StructureImportDiagnostics.PACKAGE_REFERENCE_UNSUPPORTED;
            };
        errors.add(
            new StructureLoadException(
                code, file, 1, 1, UnresolvedReferenceException.message(e.reason(), file)));
      }
    }
    return reader.read(files, contents, errors);
  }

  /** The reader of a root's schema language, over the bytes of the bundle's files. */
  @FunctionalInterface
  private interface BundleReader {

    /**
     * @param files the bundle's files, in bundle order
     * @param contents the bytes of each file the host's resolver read
     * @param errors the load errors of the files the resolver did not read
     */
    Structure read(
        List<String> files, Map<String, byte[]> contents, List<StructureLoadException> errors)
        throws StructureLoadException;
  }

  /** {@code Q{ns}local} or a bare local name. Anything else is a name no schema declares. */
  private static QName eqName(String text) {
    int end = text.indexOf('}');
    if (text.startsWith("Q{") && end > 0) {
      return new QName(text.substring(2, end), text.substring(end + 1));
    }
    return new QName("", text);
  }
}
