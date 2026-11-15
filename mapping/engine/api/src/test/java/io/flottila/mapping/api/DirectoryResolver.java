package io.flottila.mapping.api;

import io.flottila.mapping.structure.importer.ReferenceResolver;
import io.flottila.mapping.structure.importer.ResolvedFile;
import io.flottila.mapping.structure.importer.UnresolvedReferenceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A test-scope reference resolver over the files of one directory, such as a directory of {@code
 * corpus/structures} (ADR-0014 "Reference resolver shape"): a reference is read relative to the
 * directory, and a file it cannot read is {@code UNREADABLE}. Production code ships no file-system
 * resolver.
 */
final class DirectoryResolver {

  private DirectoryResolver() {}

  static ReferenceResolver over(Path directory) {
    return reference -> {
      try {
        return new ResolvedFile(reference, Files.readAllBytes(directory.resolve(reference)));
      } catch (IOException e) {
        throw new UnresolvedReferenceException(
            UnresolvedReferenceException.Reason.UNREADABLE, reference);
      }
    };
  }
}
