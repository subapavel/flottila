package io.flottila.mapping.structure.importer.planted;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Classes planted under {@code io.flottila.mapping.structure.importer} that open a file the way
 * ArchUnit rule F forbids (ADR-0016, #90): each must fail the rule ({@code
 * ArchitectureTest.ruleF_failsOnAPlantedFileRead}). They live in the service's test sources, so no
 * engine build sees them, and {@code ArchitectureTest}'s import of the engine, which leaves test
 * classes out, never takes them for engine classes. {@link ReadsBytes} is the control: it reads
 * only bytes it is given and must pass the rule.
 */
public final class PlantedFileReads {

  private PlantedFileReads() {}

  /** {@code Files.readAllBytes}. */
  public static final class FilesReadAllBytes {

    private FilesReadAllBytes() {}

    public static byte[] read(Path file) throws IOException {
      return Files.readAllBytes(file);
    }
  }

  /** {@code Path.of}. */
  public static final class PathOf {

    private PathOf() {}

    public static Object schema() {
      return Path.of("schema.xsd");
    }
  }

  /** {@code Paths.get}. */
  public static final class PathsGet {

    private PathsGet() {}

    public static Object schema() {
      return Paths.get("schema.xsd");
    }
  }

  /** {@code new File}. */
  public static final class NewFile {

    private NewFile() {}

    public static Object schema() {
      return new File("schema.xsd");
    }
  }

  /** {@code new FileInputStream}. */
  public static final class NewFileInputStream {

    private NewFileInputStream() {}

    public static InputStream schema() throws IOException {
      return new FileInputStream("schema.xsd");
    }
  }

  /** {@code FileChannel.open}. */
  public static final class FileChannelOpen {

    private FileChannelOpen() {}

    public static Object open(Path file) throws IOException {
      return FileChannel.open(file);
    }
  }

  /** {@code new RandomAccessFile}. */
  public static final class NewRandomAccessFile {

    private NewRandomAccessFile() {}

    public static Object schema() throws IOException {
      return new RandomAccessFile("schema.xsd", "r");
    }
  }

  /**
   * {@code PosixFilePermissions.fromString}: a type of a {@code java.nio.file} subpackage only, so
   * that the rule's subpackage clause has a fixture of its own.
   */
  public static final class PosixPermissionsFromString {

    private PosixPermissionsFromString() {}

    public static Object mode() {
      return PosixFilePermissions.fromString("rw-r--r--");
    }
  }

  /** The control: reads the bytes it is given, as the reader does with a resolved file. */
  public static final class ReadsBytes {

    private ReadsBytes() {}

    public static byte[] read(byte[] content) throws IOException {
      try (InputStream in = new ByteArrayInputStream(content)) {
        return in.readAllBytes();
      }
    }
  }
}
