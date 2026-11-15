package io.flottila.mapping.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test double itself: a witness built on {@link ObservedFileSystem} is only as good as the
 * opens it sees, so a read through the channel path, the one {@link Files#readAllBytes} and {@link
 * Files#newInputStream} take, is observed too (CODING_STANDARDS, a negative case's witness).
 */
class ObservedFileSystemTest {

  @TempDir Path tmp;

  @Test
  void aReadThroughReadAllBytesIsObservedWithTheFilesMode() throws Exception {
    assumeTrue(
        Files.getFileStore(tmp).supportsFileAttributeView(PosixFileAttributeView.class),
        "the file system has POSIX permissions");
    Path file = tmp.toRealPath().resolve("m.mapping.json");
    Files.write(file, "m".getBytes(UTF_8));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
    ObservedFileSystem observed = new ObservedFileSystem();

    byte[] read = Files.readAllBytes(observed.path(file));

    assertThat(read).asString(UTF_8).isEqualTo("m");
    assertThat(observed.moments())
        .containsExactly(new ObservedFileSystem.Moment("open", file, "rw-r-----"));
  }
}
