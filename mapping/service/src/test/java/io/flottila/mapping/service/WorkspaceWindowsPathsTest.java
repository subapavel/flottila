package io.flottila.mapping.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ids and the paths in a problem detail are {@code /}-separated on every OS (ADR-0024, #44). The
 * workspace runs here over an in-memory file system with Windows path semantics, whose separator is
 * {@code \}, so a relative {@link Path}'s {@code toString()} on the wire shows up on Linux too.
 */
class WorkspaceWindowsPathsTest {

  private FileSystem windows;
  private Path root;
  private Workspace workspace;

  @BeforeEach
  void layOut() throws IOException {
    windows = Jimfs.newFileSystem(Configuration.windows());
    root = Files.createDirectories(windows.getPath("C:\\work\\ws"));
    workspace = new Workspace(root);
  }

  @AfterEach
  void close() throws IOException {
    windows.close();
  }

  @Test
  void listsAnIdWithSlashes() throws Exception {
    mapping("mappings", "order-id-copy");

    assertThat(workspace.list()).containsExactly("mappings/order-id-copy");
  }

  @Test
  void walksANestedDirectory() throws Exception {
    mapping("a", "b", "x");
    mapping("top");

    assertThat(workspace.list()).containsExactly("a/b/x", "top");
  }

  @Test
  void aProblemDetailNamesTheRefusedPathWithSlashes() throws Exception {
    Path outside = Files.createDirectories(windows.getPath("C:\\work\\outside"));
    Path a = Files.createDirectory(root.resolve("a"));
    Files.createSymbolicLink(a.resolve("out"), outside);

    assertThatExceptionOfType(WorkspacePathException.class)
        .isThrownBy(() -> workspace.directory("a/out"))
        .withMessage("not a workspace path: \"a/out\"");
  }

  /** Writes the mapping model file of the id of {@code segments}. */
  private void mapping(String... segments) throws IOException {
    Path file = root;
    for (int i = 0; i < segments.length - 1; i++) {
      file = file.resolve(segments[i]);
    }
    Files.createDirectories(file);
    Files.writeString(file.resolve(segments[segments.length - 1] + ".mapping.json"), "{}", UTF_8);
  }
}
