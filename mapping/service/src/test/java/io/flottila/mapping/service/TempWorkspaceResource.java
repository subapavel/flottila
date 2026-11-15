package io.flottila.mapping.service;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/**
 * The one temporary workspace every {@code @QuarkusTest} runs over (scope {@code GLOBAL}). It holds
 * copies of the corpus's {@code order.xsd} and {@code order-id-copy.mapping.json} at their corpus
 * paths, and a sibling {@code outside/} with a valid schema and mapping that no request may read,
 * plus symlinks in {@code links/} pointing into it:
 *
 * <pre>
 * temp/
 *   workspace/                          flottila.mapping.workspace
 *     structures/skeleton/order.xsd
 *     structures/outside-copy/evil.xsd  a copy of outside/evil.xsd: the controls read it here
 *     mappings/order-id-copy.mapping.json
 *     links/out.xsd            -> ../../outside/evil.xsd
 *     links/outdir             -> ../../outside
 *     links/out.mapping.json   -> ../../outside/evil.mapping.json
 *     links/ghost.xsd          -> ../../outside/ghost.xsd    dangling
 *     links/ghostdir           -> ../../outside/no-such-dir  dangling
 *     links/loop.xsd           -> loop.xsd                   a loop
 *   outside/evil.xsd, outside/evil.mapping.json
 * </pre>
 *
 * A file system without symlinks fails the start; nothing is skipped. Tests that write use a
 * directory of their own (see {@link TempWorkspace}).
 */
@WithTestResource(value = TempWorkspaceResource.class, scope = TestResourceScope.GLOBAL)
public class TempWorkspaceResource implements QuarkusTestResourceLifecycleManager {

  static final String WORKSPACE = "flottila.mapping.workspace";

  private Path temp;

  @Override
  public Map<String, String> start() {
    try {
      temp = Files.createTempDirectory("flottila-28-");
      Path workspace = Files.createDirectory(temp.resolve("workspace"));
      Path outside = Files.createDirectory(temp.resolve("outside"));
      Path corpus = Fixtures.repositoryRoot().resolve("corpus");
      copy(corpus.resolve("structures/skeleton/order.xsd"), workspace, "structures/skeleton");
      copy(corpus.resolve("mappings/order-id-copy.mapping.json"), workspace, "mappings");
      write(outside.resolve("evil.xsd"), resource("/outside/evil.xsd"));
      write(outside.resolve("evil.mapping.json"), resource("/outside/evil.mapping.json"));
      write(workspace.resolve("structures/outside-copy/evil.xsd"), resource("/outside/evil.xsd"));
      Path links = Files.createDirectory(workspace.resolve("links"));
      Files.createSymbolicLink(links.resolve("out.xsd"), Path.of("../../outside/evil.xsd"));
      Files.createSymbolicLink(links.resolve("outdir"), Path.of("../../outside"));
      Files.createSymbolicLink(
          links.resolve("out.mapping.json"), Path.of("../../outside/evil.mapping.json"));
      Files.createSymbolicLink(links.resolve("ghost.xsd"), Path.of("../../outside/ghost.xsd"));
      Files.createSymbolicLink(links.resolve("ghostdir"), Path.of("../../outside/no-such-dir"));
      Files.createSymbolicLink(links.resolve("loop.xsd"), Path.of("loop.xsd"));
      return Map.of(WORKSPACE, workspace.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot lay out the temporary workspace", e);
    }
  }

  @Override
  public void stop() {
    if (temp == null) {
      return;
    }
    try {
      // without FOLLOW_LINKS: a link is deleted, never what it points at
      Files.walkFileTree(
          temp,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
              if (e != null) {
                throw e;
              }
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException("cannot delete the temporary workspace " + temp, e);
    }
  }

  private static void copy(Path file, Path workspace, String directory) throws IOException {
    Path target = Files.createDirectories(workspace.resolve(directory));
    Files.copy(file, target.resolve(file.getFileName()));
  }

  private static void write(Path file, byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in = TempWorkspaceResource.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IOException("no test resource " + name);
      }
      return in.readAllBytes();
    }
  }
}
