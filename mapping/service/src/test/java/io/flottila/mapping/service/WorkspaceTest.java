package io.flottila.mapping.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.flottila.mapping.api.StructureResult;
import io.flottila.mapping.api.Structures;
import io.flottila.mapping.mapping.AtFilePosition;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import io.flottila.mapping.structure.importer.ResolvedFile;
import io.flottila.mapping.structure.importer.UnresolvedReferenceException;
import io.flottila.mapping.structure.importer.UnresolvedReferenceException.Reason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The workspace is the security boundary (ADR-0024, glossary "Workspace path", "Workspace escape"):
 * an id, a base directory or a bundle reference that leaves it is a {@link WorkspacePathException}
 * and never a read or a write.
 *
 * <p>Each test lays out {@code tmp/ws} (the workspace) next to {@code tmp/outside}, whose files no
 * call may read or change. A mapping model file's content is its id, so a read shows which file it
 * read.
 */
class WorkspaceTest {

  private static final byte[] OUTSIDE = "outside: never read, never written".getBytes(UTF_8);
  private static final byte[] NEW = "{\"written\": true}".getBytes(UTF_8);

  @TempDir Path tmp;

  /** The workspace's real path. */
  private Path root;

  private Path outside;
  private Workspace workspace;

  @BeforeEach
  void layOut() throws IOException {
    root = Files.createDirectory(tmp.resolve("ws")).toRealPath();
    outside = Files.createDirectory(tmp.resolve("outside")).toRealPath();
    Files.write(outside.resolve("evil.mapping.json"), OUTSIDE);
    Files.write(outside.resolve("evil.xsd"), OUTSIDE);
    // what "../evil" would read from the workspace
    Files.write(tmp.resolve("evil.mapping.json"), OUTSIDE);
    mapping("a/b");
    workspace = new Workspace(tmp.resolve("ws"));
  }

  @Test
  void refusesDotDot() throws Exception {
    mapping("x");
    Map<String, String> before = besideTheWorkspace();

    // ids: no segment starts with a dot, so ".." never appears, not even where it stays inside
    for (String id :
        List.of(
            "..", "../evil", "../outside/evil", "a/..", "a/../x", "a/../../evil", ".x", "a/.b")) {
      assertRefused(() -> workspace.read(id), id);
      assertRefused(() -> workspace.save(id, NEW), id);
      assertRefused(() -> workspace.create(id, NEW), id);
    }
    for (String base : List.of("..", "../outside", "a/..", "a/../a", ".", "a/.")) {
      assertRefused(() -> workspace.directory(base), base);
    }
    // bundle references: ".." only as a leading run, and never above the root
    Path a = root.resolve("a");
    for (String ref : List.of("a/../x.xsd", "./x.xsd", "../../outside/evil.xsd", "../../x.xsd")) {
      assertRefused(() -> checkBundle(a, List.of(ref)), ref);
    }
    assertRefused(() -> checkBundle(root, List.of("../outside/evil.xsd")), "from root");
    // one bad reference refuses the bundle
    assertRefused(() -> checkBundle(a, List.of("b.xsd", "../../outside/evil.xsd")), "second ref");

    assertThat(besideTheWorkspace()).isEqualTo(before);
    // the same shapes inside the workspace are workspace paths
    assertThat(workspace.read("a/b")).asString(UTF_8).isEqualTo("a/b");
    assertThat(workspace.read("x")).asString(UTF_8).isEqualTo("x");
    assertThat(workspace.directory("a")).isEqualTo(root.resolve("a"));
    assertThatNoException().isThrownBy(() -> checkBundle(a, List.of("../x.xsd", "b.xsd")));
  }

  @Test
  void refusesAbsolute() throws Exception {
    Map<String, String> before = besideTheWorkspace();
    String insideAbsolute = root.resolve("a/b").toString();

    // a leading "/" is refused even where the path lies inside the workspace
    for (String id : List.of("/a/b", insideAbsolute, outside.resolve("evil").toString(), "/")) {
      assertRefused(() -> workspace.read(id), id);
      assertRefused(() -> workspace.save(id, NEW), id);
      assertRefused(() -> workspace.create(id, NEW), id);
    }
    for (String base : List.of("/", root.toString(), outside.toString(), "/a")) {
      assertRefused(() -> workspace.directory(base), base);
    }
    for (String ref :
        List.of(outside.resolve("evil.xsd").toString(), root.resolve("a/x.xsd").toString())) {
      assertRefused(() -> checkBundle(root, List.of(ref)), ref);
    }

    assertThat(besideTheWorkspace()).isEqualTo(before);
    assertThat(workspace.read("a/b")).asString(UTF_8).isEqualTo("a/b");
  }

  @Test
  void refusesPercentEncoded() throws Exception {
    // files whose names are the encoded text exist: only the refusal keeps them from a read
    mapping("%2e%2e");
    mapping("a%2Fb");
    Files.createDirectories(root.resolve("%2e%2e"));
    Map<String, String> before = besideTheWorkspace();

    // the text is already decoded once (ADR-0024): what is left of a "%" is never decoded again
    for (String id : List.of("%2e%2e", "%2e%2e%2fevil", "%252e%252e", "a%2Fb", "a%2fb", "%")) {
      assertRefused(() -> workspace.read(id), id);
      assertRefused(() -> workspace.save(id, NEW), id);
      assertRefused(() -> workspace.create(id, NEW), id);
    }
    for (String base : List.of("%2e%2e", "a%2F", "%252e")) {
      assertRefused(() -> workspace.directory(base), base);
    }
    for (String ref : List.of("%2e%2e/outside/evil.xsd", "a%2Fb.xsd", "%252e%252e/x.xsd")) {
      assertRefused(() -> checkBundle(root, List.of(ref)), ref);
    }

    assertThat(besideTheWorkspace()).isEqualTo(before);
    assertThat(workspace.read("a/b")).asString(UTF_8).isEqualTo("a/b");
  }

  @Test
  void refusesNul() throws Exception {
    Map<String, String> before = besideTheWorkspace();

    for (String id : List.of("a\0", "a/b\0", "\0", "a\0/../../evil")) {
      assertRefused(() -> workspace.read(id), printable(id));
      assertRefused(() -> workspace.save(id, NEW), printable(id));
      assertRefused(() -> workspace.create(id, NEW), printable(id));
    }
    for (String base : List.of("a\0", "\0")) {
      assertRefused(() -> workspace.directory(base), printable(base));
    }
    for (String ref : List.of("x.xsd\0", "\0../outside/evil.xsd")) {
      assertRefused(() -> checkBundle(root, List.of(ref)), printable(ref));
    }

    assertThat(besideTheWorkspace()).isEqualTo(before);
    assertThat(workspace.read("a/b")).asString(UTF_8).isEqualTo("a/b");
  }

  @Test
  void refusesWindowsSeparatorAndDrive() throws Exception {
    // on this file system "\" and ":" are file name characters: such files exist here
    mapping("a\\b");
    mapping("c:a");
    Map<String, String> before = besideTheWorkspace();

    for (String id :
        List.of("a\\b", "..\\evil", "..\\outside\\evil", "C:", "C:/a/b", "c:a", "a/b:c")) {
      assertRefused(() -> workspace.read(id), id);
      assertRefused(() -> workspace.save(id, NEW), id);
      assertRefused(() -> workspace.create(id, NEW), id);
    }
    for (String base : List.of("a\\", "C:", "C:\\", "..\\outside")) {
      assertRefused(() -> workspace.directory(base), base);
    }
    for (String ref :
        List.of("..\\..\\outside\\evil.xsd", "C:\\evil.xsd", "c:evil.xsd", "a:b.xsd")) {
      assertRefused(() -> checkBundle(root, List.of(ref)), ref);
    }

    assertThat(besideTheWorkspace()).isEqualTo(before);
    assertThat(workspace.read("a/b")).asString(UTF_8).isEqualTo("a/b");
  }

  @Test
  void refusesReservedLastSegment() throws Exception {
    // the action paths (ADR-0024); such files exist, and no id names them
    for (String id : List.of("test", "a/test", "a/compile", "a/refresh", "a/udfs")) {
      mapping(id);
    }
    mapping("test/a");
    mapping("a/tests");
    Map<String, String> before = besideTheWorkspace();
    Map<String, String> workspaceBefore = tree(root);

    for (String id : List.of("test", "a/test", "a/compile", "a/refresh", "a/udfs")) {
      assertRefused(() -> workspace.read(id), id);
      assertRefused(() -> workspace.save(id, NEW), id);
      assertRefused(() -> workspace.create(id, NEW), id);
    }
    assertRefused(() -> workspace.create("a/b/test", NEW), "a/b/test");

    assertThat(tree(root)).isEqualTo(workspaceBefore);
    assertThat(besideTheWorkspace()).isEqualTo(before);
    // reserved only as the last segment, and only as the whole segment
    assertThat(workspace.read("test/a")).asString(UTF_8).isEqualTo("test/a");
    assertThat(workspace.read("a/tests")).asString(UTF_8).isEqualTo("a/tests");
  }

  @Test
  void refusesSymlinkFileDirectoryAndBundlePointingOut() throws Exception {
    Path links = Files.createDirectory(root.resolve("links"));
    Files.createSymbolicLink(
        links.resolve("out.mapping.json"), Path.of("../../outside/evil.mapping.json"));
    Files.createSymbolicLink(
        links.resolve("abs.mapping.json"), outside.resolve("evil.mapping.json"));
    Files.createSymbolicLink(links.resolve("outdir"), Path.of("../../outside"));
    Files.createSymbolicLink(links.resolve("out.xsd"), Path.of("../../outside/evil.xsd"));
    // dangling: a write that followed it would create outside/ghost.mapping.json
    Files.createSymbolicLink(
        links.resolve("ghost.mapping.json"), Path.of("../../outside/ghost.mapping.json"));
    Map<String, String> before = besideTheWorkspace();

    // a file link: the last component
    for (String id : List.of("links/out", "links/abs")) {
      assertRefused(() -> workspace.read(id), id);
      assertRefused(() -> workspace.save(id, NEW), id);
    }
    // a directory link: a component before the last, which NOFOLLOW_LINKS alone does not guard
    assertRefused(() -> workspace.read("links/outdir/evil"), "links/outdir/evil");
    assertRefused(() -> workspace.save("links/outdir/evil", NEW), "links/outdir/evil");
    assertRefused(() -> workspace.create("links/outdir/new", NEW), "links/outdir/new");
    assertRefused(() -> workspace.directory("links/outdir"), "links/outdir");
    // a bundle reference through either kind of link
    assertRefused(() -> checkBundle(root, List.of("links/out.xsd")), "links/out.xsd");
    assertRefused(() -> checkBundle(root, List.of("links/outdir/evil.xsd")), "outdir/evil.xsd");
    assertRefused(() -> checkBundle(links, List.of("outdir/evil.xsd")), "from links");
    // a dangling link out: whatever the refusal, never a write through it
    assertThatThrownBy(() -> workspace.save("links/ghost", NEW))
        .isInstanceOfAny(WorkspacePathException.class, NoSuchFileException.class);
    assertThatThrownBy(() -> workspace.create("links/ghost", NEW))
        .isInstanceOfAny(
            WorkspacePathException.class,
            NoSuchFileException.class,
            FileAlreadyExistsException.class);

    assertThat(besideTheWorkspace()).isEqualTo(before);
    assertThat(Files.readAllBytes(outside.resolve("evil.mapping.json"))).isEqualTo(OUTSIDE);
  }

  /**
   * The problem detail names what the request spelled (#54): the id, never its mapping model file
   * or the directory that holds it, and the bundle reference, never the workspace path of the file
   * it reaches.
   */
  @Test
  void aRefusalNamesThePathAsTheRequestSpelledIt() throws Exception {
    Path links = Files.createDirectory(root.resolve("links"));
    Files.createSymbolicLink(
        links.resolve("out.mapping.json"), Path.of("../../outside/evil.mapping.json"));
    Files.createSymbolicLink(links.resolve("outdir"), Path.of("../../outside"));

    assertRefusedAs(() -> workspace.read("links/out"), "links/out");
    assertRefusedAs(() -> workspace.existing("links/out"), "links/out");
    assertRefusedAs(() -> workspace.save("links/out", NEW), "links/out");
    assertRefusedAs(() -> workspace.read("links/outdir/evil"), "links/outdir/evil");
    assertRefusedAs(() -> workspace.create("links/outdir/new", NEW), "links/outdir/new");
    assertRefusedAs(() -> workspace.mappingDirectory("links/outdir/new"), "links/outdir/new");
    assertRefusedAs(() -> workspace.directory("links/outdir"), "links/outdir");
    assertRefusedAs(() -> checkBundle(links, List.of("outdir/evil.xsd")), "outdir/evil.xsd");
  }

  @Test
  void followsSymlinkInside() throws Exception {
    mapping("real/m");
    Files.write(root.resolve("real/s.xsd"), OUTSIDE);
    Files.createSymbolicLink(root.resolve("alias.mapping.json"), Path.of("real/m.mapping.json"));
    Files.createSymbolicLink(
        root.resolve("abs-alias.mapping.json"), root.resolve("real/m.mapping.json"));
    Files.createSymbolicLink(root.resolve("linked"), Path.of("real"));
    Files.createSymbolicLink(root.resolve("s-alias.xsd"), Path.of("real/s.xsd"));

    assertThat(workspace.read("alias")).asString(UTF_8).isEqualTo("real/m");
    assertThat(workspace.read("abs-alias")).asString(UTF_8).isEqualTo("real/m");
    assertThat(workspace.read("linked/m")).asString(UTF_8).isEqualTo("real/m");
    assertThat(workspace.directory("linked")).isEqualTo(root.resolve("real"));
    assertThatNoException()
        .isThrownBy(
            () -> checkBundle(root, List.of("linked/s.xsd", "s-alias.xsd", "linked/missing.xsd")));
    assertThatNoException().isThrownBy(() -> checkBundle(root.resolve("linked"), List.of("s.xsd")));

    // a save through a link writes in the real parent: the link stays, its target changes
    workspace.save("alias", NEW);
    assertThat(Files.isSymbolicLink(root.resolve("alias.mapping.json"))).isTrue();
    assertThat(Files.readAllBytes(root.resolve("real/m.mapping.json"))).isEqualTo(NEW);
    assertThat(workspace.read("linked/m")).isEqualTo(NEW);
  }

  @Test
  void patternRefusalTouchesNoFileSystem() throws Exception {
    // the root was resolved when the workspace was made; now it is gone
    deleteTree(root);
    assertThat(Files.exists(root)).isFalse();

    for (String id : List.of("../evil", "a\\b", "%2e%2e", "a/test", "/abs", ".x", "a\0")) {
      assertRefused(() -> workspace.read(id), printable(id));
      assertRefused(() -> workspace.save(id, NEW), printable(id));
      assertRefused(() -> workspace.create(id, NEW), printable(id));
    }
    for (String base : List.of("..", "/", "a\\b", "%2e")) {
      assertRefused(() -> workspace.directory(base), base);
    }
    for (String ref : List.of("/etc/passwd", "a\\b.xsd", "a/../x.xsd", "%2e%2e/x.xsd")) {
      assertRefused(() -> checkBundle(root, List.of(ref)), ref);
    }

    // the witness: a workspace path does reach the (missing) file system, so it is no refusal
    assertThatExceptionOfType(NoSuchFileException.class).isThrownBy(() -> workspace.read("a/b"));
    // and nothing was made again: not the root, not a file next to it
    try (Stream<Path> left = Files.list(tmp)) {
      assertThat(left.map(p -> p.getFileName().toString()).sorted().toList())
          .containsExactly("evil.mapping.json", "outside");
    }
  }

  @Test
  void listPrunesDotDirectoriesAndFollowsNoLink() throws Exception {
    mapping("m");
    mapping("b/c");
    mapping("b-c");
    // never mappings: a dot segment, a name the pattern refuses, a reserved last segment
    mapping(".git/x");
    mapping("b/.hidden/y");
    mapping(".dot");
    mapping("bad name");
    mapping("sp ace/z");
    mapping("d/test");
    mapping("b/%2e");
    Files.write(root.resolve("notes.txt"), OUTSIDE);
    Files.write(root.resolve("m.mapping.json.bak"), OUTSIDE);
    Files.write(outside.resolve("o.mapping.json"), OUTSIDE);
    // links: a directory link out and one inside are not walked; a file link out fails step 3;
    // a file link inside passes it
    Files.createSymbolicLink(root.resolve("outdir"), Path.of("../outside"));
    Files.createSymbolicLink(root.resolve("inlink"), Path.of("b"));
    Files.createSymbolicLink(
        root.resolve("out.mapping.json"), Path.of("../outside/evil.mapping.json"));
    Files.createSymbolicLink(root.resolve("in.mapping.json"), Path.of("b/c.mapping.json"));

    // sorted by id as text: '-' sorts before '/', so "b-c" comes before "b/c"
    assertThat(workspace.list()).containsExactly("a/b", "b-c", "b/c", "in", "m");
  }

  /**
   * Step 2 on its own (ADR-0024): {@code outside/back} is a link to the workspace, so the real path
   * of {@code a/../../outside/back/x.xsd} is the workspace's {@code x.xsd} and step 3 alone would
   * accept the reference. Its normalised path leaves the workspace, so it is refused.
   */
  @Test
  void refusesABundleRefWhoseNormalisedPathLeavesTheWorkspaceThoughItsRealPathIsInside()
      throws Exception {
    Files.write(root.resolve("x.xsd"), NEW);
    Files.createSymbolicLink(outside.resolve("back"), Path.of("../ws"));
    Path a = root.resolve("a");
    String ref = "../../outside/back/x.xsd";
    // the witness: step 3 alone accepts the reference, and so does the pattern
    assertThat(a.resolve(ref).toRealPath()).isEqualTo(root.resolve("x.xsd"));

    assertRefused(() -> checkBundle(a, List.of(ref)), ref);
    // the same file named by a reference that stays inside is a workspace path
    assertThatNoException().isThrownBy(() -> checkBundle(a, List.of("../x.xsd")));
  }

  /**
   * A bundle reference to a dangling link is refused, wherever the link points: the check fails
   * closed, because the target might appear outside the workspace later (#28 rulings). A missing
   * file that is no link still passes, so the engine reports it.
   */
  @Test
  void refusesABundleRefThroughADanglingLink() throws Exception {
    Path links = Files.createDirectory(root.resolve("links"));
    Files.createSymbolicLink(links.resolve("ghost-out.xsd"), Path.of("../../outside/ghost.xsd"));
    Files.createSymbolicLink(links.resolve("ghost-in.xsd"), Path.of("../missing.xsd"));
    Files.createSymbolicLink(links.resolve("ghostdir"), Path.of("../../outside/no-such-dir"));
    Map<String, String> before = besideTheWorkspace();

    for (String ref :
        List.of("links/ghost-out.xsd", "links/ghost-in.xsd", "links/ghostdir/x.xsd")) {
      assertRefused(() -> checkBundle(root, List.of(ref)), ref);
    }
    assertRefused(() -> checkBundle(links, List.of("ghost-in.xsd")), "from links");

    // the witness: a missing file that is no link is passed on to the engine
    assertThatNoException()
        .isThrownBy(() -> checkBundle(root, List.of("links/missing.xsd", "missing.xsd")));
    assertThat(besideTheWorkspace()).isEqualTo(before);
  }

  /** A dangling link in the listing is skipped, wherever it points (#28 rulings). */
  @Test
  void listSkipsADanglingLink() throws Exception {
    mapping("m");
    Files.createSymbolicLink(
        root.resolve("ghost-out.mapping.json"), Path.of("../outside/ghost.mapping.json"));
    Files.createSymbolicLink(
        root.resolve("ghost-in.mapping.json"), Path.of("missing.mapping.json"));
    Files.createDirectory(root.resolve("d"));
    Files.createSymbolicLink(
        root.resolve("d/ghost.mapping.json"), Path.of("../no-such-dir/x.mapping.json"));

    assertThat(workspace.list()).containsExactly("a/b", "m");
  }

  /**
   * {@code list()} skips a subdirectory it cannot read and never fails the whole list (#28
   * rulings). The tests may run as root, whom {@code chmod 000} does not lock out (Linux {@code
   * CAP_DAC_OVERRIDE}). A path of {@code PATH_MAX} (4096) bytes or more cannot be read by any
   * process, root included: so the workspace root here is 4040 bytes long, and the directory below
   * it has a 60-character name, which the id pattern accepts. Java opens no path relative to a
   * directory, so {@code mkdir} and {@code rmdir} make and remove it from inside the root.
   */
  @Test
  void listSkipsADirectoryItCannotRead() throws Exception {
    Path deep = tmp.toRealPath().resolve("deep");
    while (deep.toString().length() < 4040) {
      deep = deep.resolve("d".repeat(Math.min(200, 4040 - deep.toString().length() - 1)));
    }
    Path longRoot = Files.createDirectories(deep).toRealPath();
    assertThat(longRoot.toString()).hasSize(4040);
    String name = "unreadable-" + "u".repeat(49);
    Files.write(longRoot.resolve("m.mapping.json"), NEW);
    Files.createDirectory(longRoot.resolve("b"));
    Files.write(longRoot.resolve("b/c.mapping.json"), NEW);
    Workspace longWorkspace = new Workspace(longRoot);
    run(longRoot, "mkdir", name);
    try {
      // the witness: this process cannot read the directory, not even its attributes
      assertThatExceptionOfType(FileSystemException.class)
          .isThrownBy(
              () ->
                  Files.readAttributes(
                      longRoot.resolve(name),
                      BasicFileAttributes.class,
                      LinkOption.NOFOLLOW_LINKS));

      assertThat(longWorkspace.list()).containsExactly("b/c", "m");
    } finally {
      run(longRoot, "rmdir", name);
    }
  }

  /**
   * A pre-existing {@code .<name>.tmp}, here a hard link to a file outside, is never written
   * through: {@code NOFOLLOW_LINKS} does not see a hard link (#28 rulings: the temporary file has a
   * unique name and is created new).
   */
  @Test
  void saveAndCreateNeverWriteThroughAPreExistingTemporaryFile() throws Exception {
    Path a = root.resolve("a");
    Files.createLink(a.resolve(".b.mapping.json.tmp"), outside.resolve("evil.mapping.json"));
    Files.createLink(a.resolve(".new.mapping.json.tmp"), outside.resolve("evil.xsd"));
    Map<String, String> before = besideTheWorkspace();

    workspace.save("a/b", NEW);
    workspace.create("a/new", NEW);

    assertThat(besideTheWorkspace()).isEqualTo(before);
    assertThat(Files.readAllBytes(outside.resolve("evil.mapping.json"))).isEqualTo(OUTSIDE);
    assertThat(Files.readAllBytes(outside.resolve("evil.xsd"))).isEqualTo(OUTSIDE);
    assertThat(workspace.read("a/b")).isEqualTo(NEW);
    assertThat(workspace.read("a/new")).isEqualTo(NEW);
  }

  /**
   * Saves still succeed next to a pre-existing {@code .<name>.tmp} of any kind, and leave it as it
   * was: an existing file is never reused (#28 rulings).
   */
  @Test
  void saveSucceedsBesideAPreExistingTemporaryFileAndLeavesIt() throws Exception {
    byte[] stale = "a stale temporary file".getBytes(UTF_8);
    mapping("c");
    mapping("d");
    Files.createSymbolicLink(root.resolve(".c.mapping.json.tmp"), Path.of("../outside/evil.xsd"));
    Files.write(root.resolve(".d.mapping.json.tmp"), stale);
    Files.write(root.resolve("a/.e.mapping.json.tmp"), stale);
    Map<String, String> before = besideTheWorkspace();

    workspace.save("c", NEW);
    workspace.save("d", NEW);
    workspace.create("a/e", NEW);

    assertThat(workspace.read("c")).isEqualTo(NEW);
    assertThat(workspace.read("d")).isEqualTo(NEW);
    assertThat(workspace.read("a/e")).isEqualTo(NEW);
    assertThat(Files.readSymbolicLink(root.resolve(".c.mapping.json.tmp")))
        .isEqualTo(Path.of("../outside/evil.xsd"));
    assertThat(Files.readAllBytes(root.resolve(".d.mapping.json.tmp"))).isEqualTo(stale);
    assertThat(Files.readAllBytes(root.resolve("a/.e.mapping.json.tmp"))).isEqualTo(stale);
    assertThat(besideTheWorkspace()).isEqualTo(before);
  }

  /**
   * A link that points at itself has no real path ({@code ELOOP}): the listing skips it and lists
   * the rest (#28 round 3).
   */
  @Test
  void listSkipsALinkLoop() throws Exception {
    mapping("m");
    Files.createSymbolicLink(root.resolve("loop.mapping.json"), Path.of("loop.mapping.json"));
    Files.createDirectory(root.resolve("d"));
    Files.createSymbolicLink(root.resolve("d/x.mapping.json"), Path.of("x.mapping.json"));
    // the witness: the links are there, and have no real path
    assertThatExceptionOfType(FileSystemException.class)
        .isThrownBy(() -> root.resolve("loop.mapping.json").toRealPath());

    assertThat(workspace.list()).containsExactly("a/b", "m");
  }

  /**
   * A bundle reference to or through a link loop is refused: like a dangling link it has no real
   * path, so the check fails closed (#28 round 3). The engine never sees it.
   */
  @Test
  void refusesABundleRefToALinkLoop() throws Exception {
    Path links = Files.createDirectory(root.resolve("links"));
    Files.createSymbolicLink(links.resolve("loop.xsd"), Path.of("loop.xsd"));
    Files.createSymbolicLink(links.resolve("loopdir"), Path.of("loopdir"));
    Map<String, String> before = besideTheWorkspace();

    for (String ref : List.of("links/loop.xsd", "links/loopdir/x.xsd")) {
      assertRefused(() -> checkBundle(root, List.of(ref)), ref);
    }
    assertRefused(() -> checkBundle(links, List.of("loop.xsd")), "from links");
    assertThat(besideTheWorkspace()).isEqualTo(before);
  }

  /**
   * A save keeps the mapping model file's POSIX permissions (#28 round 3): what the user set stays
   * set. Skipped only on a file system without POSIX permissions.
   */
  @Test
  void saveKeepsTheMappingModelFilesPermissions() throws Exception {
    assumePosix();
    for (String mode : List.of("rw-r--r--", "rw-r-----")) {
      String id = "mode/" + mode.replace('-', '_');
      mapping(id);
      Path file = root.resolve(id + ".mapping.json");
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));

      workspace.save(id, NEW);

      assertThat(workspace.read(id)).isEqualTo(NEW);
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
          .as("mode of %s after a save", id)
          .isEqualTo(mode);
    }
  }

  /** A created mapping model file is {@code rw-r--r--} (0644; #28 round 3). */
  @Test
  void createMakesAMappingModelFileOfMode0644() throws Exception {
    assumePosix();

    workspace.create("a/created", NEW);

    Path file = root.resolve("a/created.mapping.json");
    assertThat(workspace.read("a/created")).isEqualTo(NEW);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        .isEqualTo("rw-r--r--");
  }

  private void assumePosix() throws IOException {
    assumeTrue(
        Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class),
        "the file system has POSIX permissions");
  }

  /** The links a create may land on at {@code a/x.mapping.json} (#28 round 4). */
  enum LinkTo {
    /** {@code a/b.mapping.json}, which exists. */
    INSIDE("b.mapping.json"),
    OUTSIDE("../../outside/evil.mapping.json"),
    DANGLING("../../outside/ghost.mapping.json");

    final Path target;

    LinkTo(String target) {
      this.target = Path.of(target);
    }
  }

  /**
   * A create whose target is a link is {@code mapping-exists}, as now, and on its way never opens a
   * file more open than {@code rw-r--r--}: the body is never written into a file the group or the
   * world may write (#28 round 4). {@link ObservedFileSystem} records each file's mode when it is
   * opened for writing and just before its move. The witness: a create on a free name is observed
   * too.
   */
  @ParameterizedTest(name = "a link to {0}")
  @EnumSource(LinkTo.class)
  void createOnALinkIsMappingExistsAndNeverOpensAFileMoreOpenThan0644(LinkTo link)
      throws Exception {
    assumePosix();
    Path file = root.resolve("a/x.mapping.json");
    Files.createSymbolicLink(file, link.target);
    Map<String, String> before = besideTheWorkspace();
    ObservedFileSystem observed = new ObservedFileSystem();
    Workspace workspace = new Workspace(observed.path(root));

    assertThatExceptionOfType(FileAlreadyExistsException.class)
        .isThrownBy(() -> workspace.create("a/x", NEW));

    assertNoneMoreOpenThan(observed.moments(), "rw-r--r--");
    assertThat(Files.readSymbolicLink(file)).isEqualTo(link.target);
    assertThat(Files.readAllBytes(root.resolve("a/b.mapping.json")))
        .asString(UTF_8)
        .isEqualTo("a/b");
    assertThat(besideTheWorkspace()).isEqualTo(before);
    // the witness: the observed file system sees a create's temporary file
    workspace.create("a/free", NEW);
    assertThat(observed.moments()).as("moments of a/free").isNotEmpty();
    assertNoneMoreOpenThan(observed.moments(), "rw-r--r--");
  }

  /**
   * The same create, with the link removed after the create looked at the target and before its
   * move: any mapping model file it makes is a regular {@code rw-r--r--} file, and no file on its
   * way is more open (#28 round 4).
   */
  @ParameterizedTest(name = "a link to {0}")
  @EnumSource(LinkTo.class)
  void createOnALinkRemovedBeforeTheMoveMakesNothingMoreOpenThan0644(LinkTo link) throws Exception {
    assumePosix();
    Path file = root.resolve("a/x.mapping.json");
    Files.createSymbolicLink(file, link.target);
    Map<String, String> before = besideTheWorkspace();
    ObservedFileSystem observed = new ObservedFileSystem();
    observed.beforeMove(
        (source, target) -> {
          if (Files.isSymbolicLink(target)) {
            Files.delete(target);
          }
        });
    Workspace workspace = new Workspace(observed.path(root));

    try {
      workspace.create("a/x", NEW);
    } catch (FileAlreadyExistsException refusedBeforeTheMove) {
      // a create may refuse the link before it moves anything: then it made no file
      assertThat(Files.isSymbolicLink(file)).as("the link is left").isTrue();
    }

    assertNoneMoreOpenThan(observed.moments(), "rw-r--r--");
    if (!Files.isSymbolicLink(file)) {
      assertThat(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).isTrue();
      assertThat(Files.readAllBytes(file)).isEqualTo(NEW);
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
          .as("mode of the created file")
          .isEqualTo("rw-r--r--");
    }
    assertThat(besideTheWorkspace()).isEqualTo(before);
  }

  /**
   * A save through a link to a file inside of mode {@code rw-r-----} keeps that mode on the real
   * target, keeps the link a link, and opens no file more open than the target (#28 round 4).
   */
  @Test
  void saveThroughALinkKeepsTheRealTargetsModeAndTheLink() throws Exception {
    assumePosix();
    mapping("real/m");
    Path real = root.resolve("real/m.mapping.json");
    Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rw-r-----"));
    Path alias = root.resolve("alias.mapping.json");
    Files.createSymbolicLink(alias, Path.of("real/m.mapping.json"));
    ObservedFileSystem observed = new ObservedFileSystem();

    new Workspace(observed.path(root)).save("alias", NEW);

    assertThat(Files.isSymbolicLink(alias)).as("the link stays a link").isTrue();
    assertThat(Files.readSymbolicLink(alias)).isEqualTo(Path.of("real/m.mapping.json"));
    assertThat(Files.readAllBytes(real)).isEqualTo(NEW);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(real)))
        .as("mode of the real target")
        .isEqualTo("rw-r-----");
    assertThat(observed.moments()).as("the save was observed").isNotEmpty();
    assertNoneMoreOpenThan(observed.moments(), "rw-r-----");
  }

  /** Every observed mode grants nothing that {@code mode} does not. */
  private static void assertNoneMoreOpenThan(List<ObservedFileSystem.Moment> moments, String mode) {
    for (ObservedFileSystem.Moment moment : moments) {
      assertThat(PosixFilePermissions.fromString(moment.mode()))
          .as("%s %s is %s", moment.what(), moment.file().getFileName(), moment.mode())
          .isSubsetOf(PosixFilePermissions.fromString(mode));
    }
  }

  // --- [P] every accepted path resolves inside the workspace ---

  private static final long SEED = 0x2028_5EEDL;
  private static final int CASES = 900;

  /** ADR-0024, verbatim: the oracle the generated paths are judged by. */
  private static final Pattern ID_PATTERN =
      Pattern.compile(
          "[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}(/[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}){0,15}");

  private static final Pattern BUNDLE_PATTERN =
      Pattern.compile(
          "(\\.\\./){0,16}[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

  private static final Set<String> RESERVED = Set.of("test", "compile", "refresh", "udfs");

  /** The directory generated bundle references are resolved from: two levels below the root. */
  private static final String BUNDLE_DIRECTORY = "a/a";

  enum Kind {
    ID,
    BASE,
    BUNDLE
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("generatedPaths")
  void everyAcceptedPathResolvesInsideTheWorkspace(Kind kind, String path) throws Exception {
    Map<String, String> before = besideTheWorkspace();
    switch (kind) {
      case ID -> {
        if (isId(path)) {
          mapping(path);
          assertThat(workspace.read(path)).asString(UTF_8).isEqualTo(path);
        } else {
          assertRefused(() -> workspace.read(path), printable(path));
        }
      }
      case BASE -> {
        if (isBase(path)) {
          Path directory = Files.createDirectories(root.resolve(path)).toRealPath();
          assertThat(workspace.directory(path)).isEqualTo(directory);
        } else {
          assertRefused(() -> workspace.directory(path), printable(path));
        }
      }
      case BUNDLE -> {
        Path from = Files.createDirectories(root.resolve(BUNDLE_DIRECTORY));
        if (isInsideBundleRef(path)) {
          assertThatNoException().isThrownBy(() -> checkBundle(from, List.of(path)));
        } else {
          assertRefused(() -> checkBundle(from, List.of(path)), printable(path));
        }
      }
    }
    assertThat(besideTheWorkspace()).isEqualTo(before);
  }

  static Stream<Arguments> generatedPaths() {
    SplittableRandom random = new SplittableRandom(SEED);
    List<Arguments> cases = new ArrayList<>();
    int[] accepted = new int[Kind.values().length];
    int[] refused = new int[Kind.values().length];
    int escapesThePatternAllows = 0;
    for (int i = 0; i < CASES; i++) {
      Kind kind = Kind.values()[i % Kind.values().length];
      String path = generate(random);
      boolean accepts =
          switch (kind) {
            case ID -> isId(path);
            case BASE -> isBase(path);
            case BUNDLE -> isInsideBundleRef(path);
          };
      if (accepts) {
        accepted[kind.ordinal()]++;
      } else {
        refused[kind.ordinal()]++;
      }
      if (kind == Kind.BUNDLE && BUNDLE_PATTERN.matcher(path).matches() && !accepts) {
        escapesThePatternAllows++;
      }
      cases.add(
          Arguments.of(
              Named.of("seed " + Long.toHexString(SEED) + " #" + i + " " + kind, kind),
              Named.of(printable(path), path)));
    }
    for (Kind kind : Kind.values()) {
      cases.add(Arguments.of(Named.of("empty " + kind, kind), Named.of("\"\"", "")));
    }
    // the generator must reach every outcome, or the property holds vacuously
    for (Kind kind : Kind.values()) {
      if (accepted[kind.ordinal()] < 20 || refused[kind.ordinal()] < 20) {
        throw new IllegalStateException(
            "seed %x: %s accepted %d, refused %d"
                .formatted(SEED, kind, accepted[kind.ordinal()], refused[kind.ordinal()]));
      }
    }
    if (escapesThePatternAllows < 5) {
      throw new IllegalStateException(
          "seed %x: only %d bundle references climb above the root"
              .formatted(SEED, escapesThePatternAllows));
    }
    return cases.stream();
  }

  /**
   * Segments over {@code a . \ % : NUL}, joined by {@code /}: half of them after a run of one to
   * four {@code ..}, so bundle references climb above the root; sometimes absolute or with "//".
   */
  private static String generate(SplittableRandom random) {
    List<String> segments = new ArrayList<>();
    if (random.nextBoolean()) {
      for (int up = 1 + random.nextInt(4); up > 0; up--) {
        segments.add("..");
      }
    }
    for (int names = 1 + random.nextInt(4); names > 0; names--) {
      segments.add(segment(random));
    }
    StringBuilder path = new StringBuilder(random.nextInt(12) == 0 ? "/" : "");
    for (int s = 0; s < segments.size(); s++) {
      if (s > 0) {
        path.append(random.nextInt(20) == 0 ? "//" : "/");
      }
      path.append(segments.get(s));
    }
    return path.toString();
  }

  private static String segment(SplittableRandom random) {
    char[] alphabet = {'a', '.', '\\', '%', ':', '\0'};
    int pick = random.nextInt(10);
    if (pick < 4) {
      return "a";
    } else if (pick < 5) {
      return "..";
    } else if (pick < 6) {
      return ".";
    }
    StringBuilder segment = new StringBuilder();
    for (int length = 1 + random.nextInt(3); length > 0; length--) {
      // 'a' twice as often, so mixed segments are often names
      int index = random.nextInt(alphabet.length + 1);
      segment.append(index >= alphabet.length ? 'a' : alphabet[index]);
    }
    return segment.toString();
  }

  private static boolean isId(String path) {
    return ID_PATTERN.matcher(path).matches()
        && !RESERVED.contains(path.substring(path.lastIndexOf('/') + 1));
  }

  private static boolean isBase(String path) {
    return path.isEmpty() || ID_PATTERN.matcher(path).matches();
  }

  /** The pattern, then the normalised path from {@link #BUNDLE_DIRECTORY} stays under the root. */
  private static boolean isInsideBundleRef(String path) {
    if (!BUNDLE_PATTERN.matcher(path).matches()) {
      return false;
    }
    Path root = Path.of("/ws");
    return root.resolve(BUNDLE_DIRECTORY).resolve(path).normalize().startsWith(root);
  }

  // --- #90: the workspace's reference resolver (ADR-0014 "Reference resolver shape", ADR-0024)

  /** The root selector the engine is asked for; no case below reaches a schema's content. */
  private static final ElementRoot ANY_ROOT = new ElementRoot("Order");

  /**
   * The escapes {@link #resolverRefusesAnEscapeBeforeAnyRead} sends from {@code a/}: {@code
   * {outside}} stands for the absolute path of {@code outside/}. A missing file outside is an
   * escape, never unreadable.
   */
  static Stream<String> escapes() {
    return Stream.of(
        "../../outside/evil.xsd",
        "{outside}/evil.xsd",
        "links/out.xsd",
        "links/ghost.xsd",
        "links/loop.xsd",
        "../../outside/missing.xsd");
  }

  /**
   * The resolver refuses a workspace escape with an unchecked {@link InvalidWorkspacePathException}
   * that wraps the {@link WorkspacePathException} naming the reference as the bundle spells it, and
   * opens no file on the way: {@link ObservedFileSystem} records every open. The witness: the same
   * resolver then reads a workspace path, and the observer sees that open.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("escapes")
  void resolverRefusesAnEscapeBeforeAnyRead(String row) throws Exception {
    assumePosix();
    Path a = root.resolve("a");
    Path links = Files.createDirectory(a.resolve("links"));
    Files.createSymbolicLink(links.resolve("out.xsd"), Path.of("../../../outside/evil.xsd"));
    Files.createSymbolicLink(links.resolve("ghost.xsd"), Path.of("../../../outside/ghost.xsd"));
    Files.createSymbolicLink(links.resolve("loop.xsd"), Path.of("loop.xsd"));
    Files.write(a.resolve("inside.xsd"), NEW);
    String reference = row.replace("{outside}", outside.toString());
    Map<String, String> before = besideTheWorkspace();
    ObservedFileSystem observed = new ObservedFileSystem();
    ReferenceResolver resolver = new Workspace(observed.path(root)).resolver(observed.path(a));

    assertThatThrownBy(() -> resolver.resolve(reference))
        .as(reference)
        .isInstanceOf(InvalidWorkspacePathException.class)
        .cause()
        .isInstanceOf(WorkspacePathException.class)
        .hasMessage("not a workspace path: \"" + reference + "\"");

    assertThat(observed.moments()).as("files opened for %s", reference).isEmpty();
    assertThat(besideTheWorkspace()).isEqualTo(before);
    ResolvedFile inside = resolver.resolve("inside.xsd");
    assertThat(inside.name()).isEqualTo("inside.xsd");
    assertThat(inside.content()).isEqualTo(NEW);
    assertThat(observed.moments())
        .extracting(moment -> moment.file().getFileName().toString())
        .as("the witness: the observer sees a read")
        .contains("inside.xsd");
  }

  /**
   * A workspace path is read as the bundle spells it, through a link inside as well (ADR-0024). A
   * file that is missing inside, or a directory, is no escape: the engine reports it as {@code
   * schema-unreadable} at the reference.
   */
  @Test
  void resolverReadsAWorkspacePathAndAnswersAMissingOneAsUnreadable() throws Exception {
    Path a = root.resolve("a");
    byte[] shared = "shared schema".getBytes(UTF_8);
    byte[] linked = "linked schema".getBytes(UTF_8);
    Files.write(root.resolve("shared.xsd"), shared);
    Files.createDirectories(root.resolve("real"));
    Files.write(root.resolve("real/s.xsd"), linked);
    Files.createSymbolicLink(a.resolve("s-alias.xsd"), Path.of("../real/s.xsd"));
    Files.createDirectory(a.resolve("sub.xsd"));
    ReferenceResolver resolver = workspace.resolver(a);

    ResolvedFile up = resolver.resolve("../shared.xsd");
    assertThat(up.name()).isEqualTo("../shared.xsd");
    assertThat(up.content()).isEqualTo(shared);
    ResolvedFile alias = resolver.resolve("s-alias.xsd");
    assertThat(alias.name()).isEqualTo("s-alias.xsd");
    assertThat(alias.content()).isEqualTo(linked);
    for (String reference : List.of("missing.xsd", "sub.xsd")) {
      assertThat(described(Structures.load(resolver, bundle(reference), ANY_ROOT)))
          .as(reference)
          .isEqualTo("schema-unreadable at " + reference + " 1:1");
    }
  }

  /**
   * #93 (ADR-0014 "Reference resolver shape (#90)", ADR-0008): a schemaLocation written inside a
   * schema is mapped to a bundle file by text before any read. One that escapes the workspace maps
   * to none, so the load is {@code schema-reference-outside-bundle} at the first such element, and
   * {@link ObservedFileSystem}, which records every open, sees the bundle file read and nothing
   * else.
   */
  @Test
  void aSchemaLocationThatEscapesIsOutsideTheBundleAndNeverRead() throws Exception {
    Path a = root.resolve("a");
    Files.writeString(
        a.resolve("doc.xsd"),
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:a">
          <xs:import namespace="urn:outside" schemaLocation="../../outside/evil.xsd"/>
          <xs:include schemaLocation="../../../etc/passwd"/>
        </xs:schema>
        """);
    Map<String, String> before = besideTheWorkspace();
    ObservedFileSystem observed = new ObservedFileSystem();
    ReferenceResolver resolver = new Workspace(observed.path(root)).resolver(observed.path(a));

    StructureResult result = Structures.load(resolver, bundle("doc.xsd"), ANY_ROOT);

    // just after the xs:import start tag: line 2, column 79
    assertThat(described(result))
        .isEqualTo(
            "schema-reference-outside-bundle at doc.xsd 2:79;"
                + " schema-reference-outside-bundle at doc.xsd 3:53");
    assertThat(observed.moments())
        .extracting(moment -> moment.file().getFileName().toString())
        .as("files opened")
        .containsExactly("doc.xsd");
    assertThat(besideTheWorkspace()).isEqualTo(before);
  }

  /**
   * #97 (ADR-0008): a JSON Schema {@code $ref} to a file the bundle does not list is {@code
   * schema-reference-outside-bundle} at the {@code $ref}, and the unlisted file is never read,
   * although it lies inside the workspace beside the bundle file (StructuresResourceTest sends the
   * same bundle over HTTP). {@link ObservedFileSystem} records every open and sees the bundle file
   * alone.
   */
  @Test
  void aJsonRefToAnUnlistedFileIsOutsideTheBundleAndNeverRead() throws Exception {
    Path corpus = Fixtures.repositoryRoot().resolve("corpus/structures/json-schema/bundle");
    Path a = root.resolve("a");
    Files.createDirectory(a.resolve("defs"));
    for (String file : List.of("order.schema.json", "defs/address.schema.json")) {
      Files.copy(corpus.resolve(file), a.resolve(file));
    }
    ObservedFileSystem observed = new ObservedFileSystem();
    ReferenceResolver resolver = new Workspace(observed.path(root)).resolver(observed.path(a));

    StructureResult result = Structures.load(resolver, bundle("order.schema.json"), new JsonRoot());

    assertThat(described(result))
        .isEqualTo(
            "schema-reference-outside-bundle at order.schema.json 7:16;"
                + " schema-reference-outside-bundle at order.schema.json 8:17");
    assertThat(observed.moments())
        .extracting(moment -> moment.file().getFileName().toString())
        .as("files opened")
        .containsExactly("order.schema.json");
  }

  /** ADR-0024 as amended by the spec-4 grilling, verbatim: the oracle of a package reference. */
  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile(
          "[A-Za-z0-9_-][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.+-]*:/[A-Za-z0-9_-][A-Za-z0-9._-]*"
              + "(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

  /** Enough candidates that both outcomes come up often (103 package references for SEED). */
  private static final int PACKAGE_CASES = 3000;

  /**
   * The resolver refuses a reference as a package reference ({@code package-reference-unsupported}
   * from the engine) exactly when it has the ADR-0024 form, and anything else with an {@code @} as
   * an invalid workspace path. It decides from the text alone: the root is deleted before the first
   * call, so a file-system call would show as {@code schema-unreadable} or a different exception.
   * Candidates are {@code name@version:path} with parts from {@link #generate} and {@link
   * #segment}.
   */
  @Test
  void packageReferencePatternAcceptsExactlyTheAdrForm() throws Exception {
    ReferenceResolver resolver = workspace.resolver(root.resolve("a"));
    deleteTree(root);
    List<String> candidates =
        new ArrayList<>(
            List.of(
                "shared@1.2.0:/xsd/o.xsd",
                "p@1:/x.xsd",
                "p_-.q@2+b.1-rc:/a/b.c",
                "p@1:x.xsd",
                "p@:/x.xsd",
                "@1:/x.xsd",
                "p@1:/../x.xsd",
                "p@1:/a//b.xsd",
                "p@.1:/x.xsd",
                "p@1:/x.xsd/",
                "p@1@2:/x.xsd",
                "p@1:/a\\b.xsd"));
    SplittableRandom random = new SplittableRandom(SEED);
    for (int i = 0; i < PACKAGE_CASES; i++) {
      candidates.add(packageCandidate(random));
    }

    int accepted = 0;
    int refused = 0;
    for (String reference : candidates) {
      if (PACKAGE_PATTERN.matcher(reference).matches()) {
        accepted++;
        assertThat(described(Structures.load(resolver, bundle(reference), ANY_ROOT)))
            .as(printable(reference))
            .isEqualTo("package-reference-unsupported at " + reference + " 1:1");
      } else {
        refused++;
        assertThatThrownBy(() -> Structures.load(resolver, bundle(reference), ANY_ROOT))
            .as(printable(reference))
            .isInstanceOf(InvalidWorkspacePathException.class);
      }
    }
    // both outcomes must come up, or the property holds vacuously
    assertThat(accepted).as("seed %x: package references", SEED).isGreaterThanOrEqualTo(50);
    assertThat(refused).as("seed %x: other references", SEED).isGreaterThanOrEqualTo(50);
  }

  /**
   * {@code name@version:path}: the name and the version are often of the form, else a {@link
   * #segment} or empty; the path is a {@link #generate}d one, mostly after a {@code /}.
   */
  private static String packageCandidate(SplittableRandom random) {
    String name = random.nextInt(3) == 0 ? segment(random) : "shared";
    String version =
        switch (random.nextInt(4)) {
          case 0 -> segment(random);
          case 1 -> "";
          default -> random.nextBoolean() ? "1.2.0" : "2+b.1-rc";
        };
    String path = generate(random);
    return name + "@" + version + ":" + (random.nextInt(4) == 0 ? path : "/" + path);
  }

  private static SchemaBundle bundle(String reference) {
    return new SchemaBundle(List.of(reference));
  }

  /**
   * A refused load's errors, each as {@code code at file line:column}, joined by {@code "; "};
   * anything else fails the test.
   */
  private static String described(StructureResult result) {
    if (!(result instanceof StructureResult.Refused(List<Diagnostic> diagnostics))) {
      throw new AssertionError("expected a refused load, got " + result);
    }
    List<String> described = new ArrayList<>();
    for (Diagnostic diagnostic : diagnostics) {
      if (!(diagnostic.location() instanceof AtFilePosition(String file, int line, int column))) {
        throw new AssertionError("expected a load error at a file position, got " + result);
      }
      described.add(diagnostic.code() + " at " + file + " " + line + ":" + column);
    }
    return String.join("; ", described);
  }

  // --- helpers ---

  /** Writes the mapping model file of {@code id}, whose content is the id. */
  private void mapping(String id) throws IOException {
    Path file = root.resolve(id + ".mapping.json");
    Files.createDirectories(file.getParent());
    Files.write(file, id.getBytes(UTF_8));
  }

  /**
   * Resolves each bundle reference from {@code directory} through the workspace's reference
   * resolver (#90): an escape throws the {@link WorkspacePathException} its {@link
   * InvalidWorkspacePathException} wraps; a file inside that cannot be read, a missing one, is no
   * escape, so the engine reports it ({@code UNREADABLE}).
   */
  private void checkBundle(Path directory, List<String> bundle) throws Exception {
    ReferenceResolver resolver = workspace.resolver(directory);
    for (String reference : bundle) {
      try {
        resolver.resolve(reference);
      } catch (InvalidWorkspacePathException invalid) {
        throw (WorkspacePathException) invalid.getCause();
      } catch (UnresolvedReferenceException unreadable) {
        assertThat(unreadable.reason()).as(reference).isEqualTo(Reason.UNREADABLE);
      }
    }
  }

  private static void assertRefused(ThrowingCallable call, String path) {
    assertThatExceptionOfType(WorkspacePathException.class).as(path).isThrownBy(call);
  }

  private static void assertRefusedAs(ThrowingCallable call, String spelled) {
    assertThatExceptionOfType(WorkspacePathException.class)
        .as(spelled)
        .isThrownBy(call)
        .withMessage("not a workspace path: \"" + spelled + "\"");
  }

  /** Runs {@code command} in {@code directory}, which may be too deep for an absolute path. */
  private static void run(Path directory, String... command) throws Exception {
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    assertThat(process.waitFor()).as("%s: %s", String.join(" ", command), output).isZero();
  }

  private static String printable(String path) {
    return path.replace("\0", "<NUL>");
  }

  /** Every entry of {@code tmp} outside the workspace, with each regular file's bytes. */
  private Map<String, String> besideTheWorkspace() throws IOException {
    Map<String, String> entries = new TreeMap<>();
    for (Map.Entry<String, String> e : tree(tmp).entrySet()) {
      if (!tmp.resolve(e.getKey()).startsWith(tmp.resolve("ws"))) {
        entries.put(e.getKey(), e.getValue());
      }
    }
    return entries;
  }

  /** Every entry under {@code directory}, links not followed, with each regular file's bytes. */
  private static Map<String, String> tree(Path directory) throws IOException {
    Map<String, String> entries = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(directory)) {
      walk.forEach(
          p -> {
            String content;
            try {
              content =
                  Files.isSymbolicLink(p)
                      ? "link " + Files.readSymbolicLink(p)
                      : Files.isDirectory(p)
                          ? "directory"
                          : HexFormat.of().formatHex(Files.readAllBytes(p));
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            entries.put(directory.relativize(p).toString(), content);
          });
    }
    return entries;
  }

  private static void deleteTree(Path directory) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
