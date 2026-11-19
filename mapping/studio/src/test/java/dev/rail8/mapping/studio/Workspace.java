package dev.rail8.mapping.studio;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bundle files a test's calls can send, by reference, as the node finds them for a request: the
 * files a test wrote, then the corpus's, each reference read against one directory of the corpus. A
 * reference a test names as escaping, or one that leaves the corpus, is no workspace path.
 */
final class Workspace {

  private final Path corpus = Fixtures.repositoryRoot().resolve("corpus").normalize();
  private final Path directory;
  private final Map<String, byte[]> written = new HashMap<>();
  private final Set<String> escaping = new HashSet<>();

  private Workspace(Path directory) {
    this.directory = directory;
  }

  /** No file at all: every reference is unreadable. */
  static Workspace empty() {
    return new Workspace(null);
  }

  /**
   * The corpus, a reference read against {@code directory}, a directory of the corpus: {@code
   * mappings} for a mapping model's bundle.
   */
  static Workspace corpus(String directory) {
    return new Workspace(Path.of(directory));
  }

  /** The corpus as a mapping model in {@code corpus/mappings/} reads it. */
  static Workspace mappings() {
    return corpus("mappings");
  }

  /** Adds the file {@code content} at {@code reference}, before the corpus's. */
  Workspace with(String reference, byte[] content) {
    written.put(reference, content);
    return this;
  }

  /** Adds the file {@code content} as UTF-8 at {@code reference}. */
  Workspace with(String reference, String content) {
    return with(reference, content.getBytes(UTF_8));
  }

  /** Names {@code reference} as no workspace path. */
  Workspace escaping(String reference) {
    escaping.add(reference);
    return this;
  }

  /** Whether {@code reference} is no workspace path. */
  boolean escapes(String reference) {
    if (escaping.contains(reference)) {
      return true;
    }
    return directory != null && !written.containsKey(reference) && !inCorpus(reference);
  }

  /** The file at {@code reference}, or empty when there is none or it is no workspace path. */
  Optional<byte[]> file(String reference) {
    if (written.containsKey(reference)) {
      return Optional.of(written.get(reference));
    }
    if (directory == null || escapes(reference)) {
      return Optional.empty();
    }
    Path file = corpus.resolve(directory).resolve(reference).normalize();
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private boolean inCorpus(String reference) {
    return corpus.resolve(directory).resolve(reference).normalize().startsWith(corpus);
  }
}
