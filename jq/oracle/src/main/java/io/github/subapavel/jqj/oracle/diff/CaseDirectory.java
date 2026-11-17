package io.github.subapavel.jqj.oracle.diff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** A fresh directory holding a case's files; {@link #close} deletes it with everything in it (ADR-0020). */
public final class CaseDirectory implements AutoCloseable {

  private final Path path;

  private CaseDirectory(Path path) {
    this.path = path;
  }

  /**
   * A new directory under {@code parent} with {@code files} (name to content) written into it. If a file cannot be
   * written, the directory is deleted before the exception leaves.
   */
  public static CaseDirectory of(Map<String, byte[]> files, Path parent) {
    CaseDirectory dir;
    try {
      dir = new CaseDirectory(Files.createTempDirectory(parent, "jqj-case-"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    boolean written = false;
    try {
      for (Map.Entry<String, byte[]> f : files.entrySet()) Files.write(dir.path.resolve(f.getKey()), f.getValue());
      written = true;
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      if (!written) dir.close();
    }
  }

  public Path path() {
    return path;
  }

  @Override
  public void close() {
    try (Stream<Path> walk = Files.walk(path)) {
      List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
      for (Path p : all) Files.delete(p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
