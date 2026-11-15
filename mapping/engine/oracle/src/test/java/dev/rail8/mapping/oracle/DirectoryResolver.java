package dev.rail8.mapping.oracle;

import dev.rail8.mapping.structure.importer.ReferenceResolver;
import dev.rail8.mapping.structure.importer.ResolvedFile;
import dev.rail8.mapping.structure.importer.UnresolvedReferenceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A test-scope reference resolver over the files of one directory of {@code corpus/} (ADR-0014
 * "Reference resolver shape"): a reference is read relative to the directory, such as a mapping
 * file's, and a file it cannot read is {@code UNREADABLE}. Production code ships no file-system
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
