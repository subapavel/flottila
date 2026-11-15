// Writes the packed function catalogue into contract/ (ADR-0027). Single-file program, JDK only. The
// service's build runs it on the service's class path, so the file holds exactly the bytes that
// GET /api/catalogue serves (CatalogueResourceTest checks it):
//
//   java -classpath <class path> service/src/build/WriteCatalogue.java <resource> <output>
//
// A resource missing from the class path fails the build.
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class WriteCatalogue {

  private WriteCatalogue() {}

  public static void main(String[] args) throws IOException {
    try (InputStream in =
        Objects.requireNonNull(
            WriteCatalogue.class.getClassLoader().getResourceAsStream(args[0]),
            () -> "write-catalogue: no " + args[0] + " on the class path")) {
      Files.write(Path.of(args[1]), in.readAllBytes());
    }
  }
}
