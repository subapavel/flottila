package io.flottila.mapping.structure.importer;

/**
 * The host's port through which the engine reads every schema file (ADR-0014 "Reference resolver
 * shape"). The engine opens no file in any other way. The host binds a resolver to the base its
 * bundle references are relative to.
 */
@FunctionalInterface
public interface ReferenceResolver {

  /**
   * The file a schema bundle lists.
   *
   * @param reference a reference the schema bundle lists, as the bundle spells it: relative to the
   *     mapping file, or a package reference ({@code name@version:/path})
   * @return the file's bytes, named as the bundle spells it
   * @throws UnresolvedReferenceException when the host cannot read the file, or reads no package
   *     reference; the engine reports it as a load error of its own
   */
  ResolvedFile resolve(String reference) throws UnresolvedReferenceException;
}
