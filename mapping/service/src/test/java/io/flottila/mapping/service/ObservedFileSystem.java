package io.flottila.mapping.service;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The default file system, observed: a test double at the file system port. {@link Workspace} does
 * all its I/O through the provider of the {@link Path} it is made with, so a workspace over {@link
 * #path} runs on the real files while this records the POSIX mode of every existing file at two
 * moments: when it is opened, to read or to write, and just before it is moved into place. {@link
 * #beforeMove} runs at the second moment, so a test can change the target in between (remove a
 * link) without a race.
 */
final class ObservedFileSystem extends FileSystem {

  /** A file's mode at one moment: {@code "open <name> rwx------"} or {@code "move <name> ..."}. */
  record Moment(String what, Path file, String mode) {}

  /** Runs just before a move, with the real source and target paths. */
  interface BeforeMove {
    void run(Path source, Path target) throws IOException;
  }

  private final FileSystem real = FileSystems.getDefault();
  private final Provider provider = new Provider();
  private final List<Moment> moments = new ArrayList<>();
  private BeforeMove beforeMove = (source, target) -> {};

  /** {@code file} of the default file system, as a path of this one. */
  Path path(Path file) {
    return wrap(file);
  }

  /** The modes seen, in order. */
  List<Moment> moments() {
    return List.copyOf(moments);
  }

  void beforeMove(BeforeMove action) {
    beforeMove = action;
  }

  private void observe(String what, Path file) throws IOException {
    moments.add(
        new Moment(
            what,
            file,
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))));
  }

  // --- paths: a proxy over the default file system's path, so every Path method behaves as there

  private Path wrap(Path file) {
    return (Path)
        Proxy.newProxyInstance(
            Path.class.getClassLoader(),
            new Class<?>[] {Path.class},
            (proxy, method, args) -> invoke(file, method, args));
  }

  private Object invoke(Path file, Method method, Object[] args) throws Throwable {
    if (method.getName().equals("getFileSystem")) {
      return this;
    }
    Object[] unwrapped = args == null ? new Object[0] : args.clone();
    for (int i = 0; i < unwrapped.length; i++) {
      unwrapped[i] = unwrapped[i] instanceof Path p ? unwrap(p) : unwrapped[i];
    }
    try {
      Object result = method.invoke(file, unwrapped);
      return result instanceof Path p && p.getFileSystem() == real ? wrap(p) : result;
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private Path unwrap(Path file) {
    if (file.getFileSystem() == real) {
      return file;
    }
    // the real path behind a proxy: its toString is the real path's
    return real.getPath(file.toString());
  }

  // --- the file system

  @Override
  public FileSystemProvider provider() {
    return provider;
  }

  @Override
  public void close() {
    throw new UnsupportedOperationException("the observed default file system stays open");
  }

  @Override
  public boolean isOpen() {
    return true;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getSeparator() {
    return real.getSeparator();
  }

  @Override
  public Iterable<Path> getRootDirectories() {
    List<Path> roots = new ArrayList<>();
    real.getRootDirectories().forEach(r -> roots.add(wrap(r)));
    return roots;
  }

  @Override
  public Iterable<FileStore> getFileStores() {
    return real.getFileStores();
  }

  @Override
  public Set<String> supportedFileAttributeViews() {
    return real.supportedFileAttributeViews();
  }

  @Override
  public Path getPath(String first, String... more) {
    return wrap(real.getPath(first, more));
  }

  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    return real.getPathMatcher(syntaxAndPattern);
  }

  @Override
  public UserPrincipalLookupService getUserPrincipalLookupService() {
    return real.getUserPrincipalLookupService();
  }

  @Override
  public WatchService newWatchService() {
    throw new UnsupportedOperationException("not observed");
  }

  /** Every operation on the real path; opens and moves are observed first. */
  private final class Provider extends FileSystemProvider {

    @Override
    public String getScheme() {
      return "observed";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
      throw new UnsupportedOperationException("not observed");
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
      return ObservedFileSystem.this;
    }

    @Override
    public Path getPath(URI uri) {
      return wrap(Path.of(uri));
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
      Path file = unwrap(path);
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        observe("open", file);
      }
      return Files.newOutputStream(file, options);
    }

    @Override
    public SeekableByteChannel newByteChannel(
        Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      // the channel path: Files.readAllBytes, Files.newInputStream and Files.createTempFile
      Path file = unwrap(path);
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        observe("open", file);
      }
      return Files.newByteChannel(file, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
        Path dir, DirectoryStream.Filter<? super Path> filter) {
      throw new UnsupportedOperationException("not observed");
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
      Files.createDirectory(unwrap(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
      Files.delete(unwrap(path));
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
      Files.copy(unwrap(source), unwrap(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
      observe("move", unwrap(source));
      beforeMove.run(unwrap(source), unwrap(target));
      Files.move(unwrap(source), unwrap(target), options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
      return Files.isSameFile(unwrap(path), unwrap(path2));
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
      return Files.isHidden(unwrap(path));
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
      return Files.getFileStore(unwrap(path));
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
      unwrap(path).getFileSystem().provider().checkAccess(unwrap(path), modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(
        Path path, Class<V> type, LinkOption... options) {
      return Files.getFileAttributeView(unwrap(path), type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(
        Path path, Class<A> type, LinkOption... options) throws IOException {
      return Files.readAttributes(unwrap(path), type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
        throws IOException {
      return Files.readAttributes(unwrap(path), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
        throws IOException {
      Files.setAttribute(unwrap(path), attribute, value, options);
    }
  }
}
