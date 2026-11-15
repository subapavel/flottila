package io.flottila.mapping.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.ConfigProvider;

/** The {@link TempWorkspaceResource}'s directories, as a {@code @QuarkusTest} sees them. */
final class TempWorkspace {

  private TempWorkspace() {}

  /** The workspace directory the service runs over. */
  static Path root() {
    return Path.of(
        ConfigProvider.getConfig().getValue(TempWorkspaceResource.WORKSPACE, String.class));
  }

  /** The sibling directory no request may read or write. */
  static Path outside() {
    return root().resolveSibling("outside");
  }

  /** Writes {@code bytes} to {@code relative} in the workspace, creating its directories. */
  static void write(String relative, byte[] bytes) {
    try {
      Path file = root().resolve(relative);
      Files.createDirectories(file.getParent());
      Files.write(file, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void mkdirs(String relative) {
    try {
      Files.createDirectories(root().resolve(relative));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] read(String relative) {
    try {
      return Files.readAllBytes(root().resolve(relative));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static boolean exists(String relative) {
    return Files.exists(root().resolve(relative));
  }

  /**
   * Every entry beside the workspace (in {@code outside/} and next to it), with the hex of each
   * regular file's bytes; the workspace itself is left out. Links are not followed.
   */
  static Map<String, String> besideTheWorkspace() {
    Path temp = root().getParent();
    Path workspace = root();
    Map<String, String> entries = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(temp)) {
      for (Path p : walk.filter(p -> !p.startsWith(workspace)).toList()) {
        String content =
            Files.isRegularFile(p) && !Files.isSymbolicLink(p)
                ? HexFormat.of().formatHex(Files.readAllBytes(p))
                : Files.isDirectory(p) ? "directory" : "other";
        entries.put(temp.relativize(p).toString(), content);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return entries;
  }
}
