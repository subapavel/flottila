package io.flottila.mapping.api;

import java.nio.file.Files;
import java.nio.file.Path;

/** The schema directories of {@code corpus/structures}, found from where surefire runs. */
final class CorpusStructures {

  private CorpusStructures() {}

  /** {@code corpus/structures/<name>} of the repository that holds the working directory. */
  static Path directory(String name) {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      Path candidate = p.resolve("corpus/structures").resolve(name);
      if (Files.isRegularFile(p.resolve("mvnw")) && Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
  }
}
