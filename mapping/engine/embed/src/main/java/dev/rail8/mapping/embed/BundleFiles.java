package dev.rail8.mapping.embed;

import java.io.IOException;

/**
 * The host's port through which {@link Mapping#compile} reads every schema file a mapping's schema
 * bundles list. The engine opens no file in any other way.
 */
@FunctionalInterface
public interface BundleFiles {

  /**
   * The bytes of the file {@code reference} names.
   *
   * @param reference a reference a schema bundle lists, parsed from the mapping file's spelling
   * @return the file's bytes
   * @throws IOException when the host cannot read the file; the compile refuses the mapping with
   *     {@code schema-unreadable} at that reference. An unchecked exception passes through the
   *     compile unchanged, so the host's own refusal keeps its words.
   */
  byte[] read(BundleReference reference) throws IOException;
}
