package io.flottila.mapping.service;

import io.flottila.mapping.structure.importer.ReferenceResolver;
import io.flottila.mapping.structure.importer.ResolvedFile;
import io.flottila.mapping.structure.importer.UnresolvedReferenceException;
import io.flottila.mapping.structure.importer.UnresolvedReferenceException.Reason;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;

/**
 * The workspace: the only class that resolves paths (ADR-0024). Ids, base directories and bundle
 * references pass the pattern, the normalised prefix and the real-path prefix, in that order. The
 * engine reads bundle files through its {@link #resolver} (ADR-0014).
 */
@ApplicationScoped
@Startup
public class Workspace {

  private static final String SEGMENT = "[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}";

  /** A mapping id (ADR-0024); its last segment must not be reserved as well. */
  private static final Pattern ID = Pattern.compile(SEGMENT + "(/" + SEGMENT + "){0,15}");

  /** A base directory: an id, or {@code ""} for the root. */
  private static final Pattern BASE = Pattern.compile("(" + ID.pattern() + ")?");

  private static final Pattern BUNDLE =
      Pattern.compile(
          "(\\.\\./){0,16}[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

  /** A package reference, {@code name@version:/path} (ADR-0024 since the spec-4 grilling). */
  private static final Pattern PACKAGE =
      Pattern.compile(
          "[A-Za-z0-9_-][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.+-]*:/[A-Za-z0-9_-][A-Za-z0-9._-]*"
              + "(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

  /** Last segments of the action paths, so routing never depends on the router. */
  private static final Set<String> RESERVED = Set.of("test", "compile", "refresh", "udfs");

  private static final String SUFFIX = ".mapping.json";

  /**
   * The mode of a created mapping model file on a POSIX file store (0644), and of a saved one that
   * is no regular file.
   */
  private static final Set<PosixFilePermission> CREATED =
      Set.copyOf(PosixFilePermissions.fromString("rw-r--r--"));

  /**
   * Writes of one file are serialised by its stripe, so two creates of one id cannot both pass the
   * move's existence check; the number of stripes is fixed.
   */
  private static final int STRIPES = 64;

  private final Path root;
  private final Object[] locks = new Object[STRIPES];

  /**
   * The workspace of {@code flottila.mapping.workspace}, made at startup. The property has no
   * default, so a service without one does not start.
   *
   * @param config the service's configuration
   * @throws IOException if the directory does not exist
   */
  @Inject
  Workspace(Config config) throws IOException {
    this(config.getValue("flottila.mapping.workspace", Path.class));
  }

  /**
   * A workspace over {@code directory}; its real path, resolved once here, is the root.
   *
   * @param directory the configured {@code flottila.mapping.workspace}
   * @throws IOException if the directory does not exist
   */
  public Workspace(Path directory) throws IOException {
    root = directory.toRealPath();
    for (int i = 0; i < STRIPES; i++) {
      locks[i] = new Object();
    }
  }

  /**
   * The ids of the workspace's mapping model files, sorted by id. The walk follows no link and
   * prunes every directory whose path is no base. It skips an entry whose attributes it cannot read
   * and a directory it cannot open; an I/O error while it reads a directory it did open fails the
   * listing. It keeps a file only if its id passes the pattern and its real path resolves and lies
   * inside the workspace, so a dangling link and a link loop are skipped too.
   */
  public List<String> list() throws IOException {
    List<String> ids = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return BASE.matcher(relative(dir)).matches()
                ? FileVisitResult.CONTINUE
                : FileVisitResult.SKIP_SUBTREE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            String relative = relative(file);
            if (relative.endsWith(SUFFIX)) {
              String id = relative.substring(0, relative.length() - SUFFIX.length());
              if (isId(id) && insideByRealPath(file)) {
                ids.add(id);
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
    ids.sort(null);
    return ids;
  }

  /** The bytes of the mapping model file of {@code id}. */
  public byte[] read(String id) throws IOException, WorkspacePathException {
    Path file = existing(id);
    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      return in.readAllBytes();
    }
  }

  /**
   * Steps 1 to 3 for the mapping model file of {@code id}, which must exist. The PUT endpoint
   * checks its id with it before it reads the body.
   *
   * @return the file's real path
   * @throws NoSuchFileException if there is no mapping model file of {@code id}
   */
  public Path existing(String id) throws IOException, WorkspacePathException {
    return real(mappingModelFile(id), id);
  }

  /**
   * Replaces the mapping model file of {@code id} atomically. Through a link it writes the link's
   * target and keeps the link.
   */
  public void save(String id, byte[] canonical) throws IOException, WorkspacePathException {
    Path file = existing(id);
    write(
        file,
        canonical,
        keptMode(file),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Writes the mapping model file of a new {@code id}, of mode {@code rw-r--r--}; never replaces
   * one, and never reads the mode of what is in the way.
   */
  public void create(String id, byte[] canonical) throws IOException, WorkspacePathException {
    Path file = mappingModelFile(id);
    write(real(file.getParent(), id).resolve(file.getFileName()), canonical, CREATED);
  }

  /**
   * The real path of the directory that holds, or would hold, the mapping model file of {@code id}:
   * the directory its bundle references are relative to.
   */
  public Path mappingDirectory(String id) throws IOException, WorkspacePathException {
    return real(mappingModelFile(id).getParent(), id);
  }

  /** The real path of the directory {@code base} names; {@code ""} is the root. */
  public Path directory(String base) throws IOException, WorkspacePathException {
    return real(inside(root, matching(base, BASE)), base);
  }

  /**
   * The reference resolver of the bundle references relative to {@code directory} (ADR-0014
   * "Reference resolver shape", ADR-0024). It decides each reference on its own, in this order:
   *
   * <ol>
   *   <li>a package reference is refused as {@code PACKAGE_REFERENCE_UNSUPPORTED} from its text
   *       alone, before any file system call;
   *   <li>any other reference passes steps 1 to 3 as a bundle reference, or it is refused with a
   *       {@link InvalidWorkspacePathException} before any file is opened;
   *   <li>the checked real path is read; any I/O error, a directory or a missing file among them,
   *       is {@code UNREADABLE}.
   * </ol>
   *
   * @param directory a real directory of the workspace, as {@link #directory} or {@link
   *     #mappingDirectory} return it
   */
  public ReferenceResolver resolver(Path directory) {
    return reference -> {
      if (PACKAGE.matcher(reference).matches()) {
        throw new UnresolvedReferenceException(Reason.PACKAGE_REFERENCE_UNSUPPORTED, reference);
      }
      Path file;
      try {
        file = bundleFile(directory, reference);
      } catch (WorkspacePathException refused) {
        throw new InvalidWorkspacePathException(refused);
      }
      try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        return new ResolvedFile(reference, in.readAllBytes());
      } catch (IOException unreadable) {
        throw new UnresolvedReferenceException(Reason.UNREADABLE, reference);
      }
    };
  }

  /**
   * Steps 1 to 3 for a bundle reference from {@code directory}: the real path of its file. A file
   * inside the workspace that is missing passes, and the path is then its nearest existing
   * ancestor's: a directory, whose read fails, so the engine reports the file and no name that was
   * missing at the check is ever opened. That ancestor must lie inside the workspace by its real
   * path. An ancestor whose real path does not resolve, a dangling link or a link loop, is refused
   * as well: the check fails closed, because the link's target might appear outside later
   * (ADR-0024).
   */
  private Path bundleFile(Path directory, String reference) throws WorkspacePathException {
    Path existing = inside(directory, matching(reference, BUNDLE));
    while (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
    }
    try {
      return real(existing, reference);
    } catch (IOException noRealPath) {
      throw new WorkspacePathException(reference);
    }
  }

  private static boolean isId(String id) {
    return ID.matcher(id).matches() && !RESERVED.contains(id.substring(id.lastIndexOf('/') + 1));
  }

  /** Steps 1 and 2 for an id: the path of its mapping model file, not yet resolved on disk. */
  private Path mappingModelFile(String id) throws WorkspacePathException {
    if (!isId(id)) {
      throw new WorkspacePathException(id);
    }
    return inside(root, id + SUFFIX);
  }

  /** Step 1: the pattern, on the decoded text, before any file system call. */
  private static String matching(String path, Pattern pattern) throws WorkspacePathException {
    if (!pattern.matcher(path).matches()) {
      throw new WorkspacePathException(path);
    }
    return path;
  }

  /** Step 2: the normalised path from {@code from} lies under the root. */
  private Path inside(Path from, String relative) throws WorkspacePathException {
    Path path = from.resolve(relative).normalize();
    if (!path.startsWith(root)) {
      throw new WorkspacePathException(relative);
    }
    return path;
  }

  /**
   * Step 3: the real path of an existing file or directory lies under the root.
   *
   * @param spelled the id, base or bundle reference that names {@code path}, as the request spelled
   *     it: the refusal names it
   */
  private Path real(Path path, String spelled) throws IOException, WorkspacePathException {
    Path real = path.toRealPath();
    if (!real.startsWith(root)) {
      throw new WorkspacePathException(spelled);
    }
    return real;
  }

  /**
   * {@code path} relative to the root, its name elements joined with {@code /} on every OS
   * (ADR-0024 workspace path); {@code ""} for the root itself.
   */
  private String relative(Path path) {
    StringJoiner joined = new StringJoiner("/");
    for (Path name : root.relativize(path)) {
      joined.add(name.toString());
    }
    return joined.toString();
  }

  /** False also when the real path does not resolve: a dangling link or a link loop. */
  private boolean insideByRealPath(Path file) {
    try {
      return file.toRealPath().startsWith(root);
    } catch (IOException noRealPath) {
      return false;
    }
  }

  /**
   * Writes a new temporary file with a unique name in the real parent of {@code target}, then moves
   * it onto {@code target} with {@code options}, under the target's lock. No file that existed
   * before the call is opened, so a {@code .<name>.tmp} left beside the target stays as it is. On a
   * file store with POSIX permissions the temporary file is created with {@code mode}, which the
   * umask can only narrow, and set to it before any content is written, so no file on the way is
   * more open than the final one. Elsewhere it gets the file system's default. It never outlives
   * the call.
   */
  private void write(
      Path target, byte[] bytes, Set<PosixFilePermission> mode, CopyOption... options)
      throws IOException {
    synchronized (locks[Math.floorMod(target.hashCode(), STRIPES)]) {
      Path directory = target.getParent();
      String prefix = "." + target.getFileName() + ".";
      boolean posix = posix(directory);
      Path temp =
          posix
              ? Files.createTempFile(
                  directory, prefix, ".tmp", PosixFilePermissions.asFileAttribute(mode))
              : Files.createTempFile(directory, prefix, ".tmp");
      try {
        if (posix) {
          Files.setPosixFilePermissions(temp, mode);
        }
        Files.write(temp, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        Files.move(temp, target, options);
      } finally {
        Files.deleteIfExists(temp);
      }
    }
  }

  /**
   * The mode a save keeps: that of {@code file} when it is a regular file on a file store with
   * POSIX permissions, otherwise {@code rw-r--r--}. {@code file} is a real path, so a save through
   * a link keeps its target's mode, never the link's own.
   */
  private static Set<PosixFilePermission> keptMode(Path file) throws IOException {
    return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && posix(file.getParent())
        ? Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
        : CREATED;
  }

  private static boolean posix(Path directory) throws IOException {
    return Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class);
  }
}
