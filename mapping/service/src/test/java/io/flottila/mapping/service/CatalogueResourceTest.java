package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.assertBytes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.erosb.jsonsKema.IJsonObject;
import com.github.erosb.jsonsKema.IJsonValue;
import com.github.erosb.jsonsKema.JsonObject;
import com.github.erosb.jsonsKema.JsonParser;
import com.github.erosb.jsonsKema.Schema;
import com.github.erosb.jsonsKema.SchemaLoader;
import com.github.erosb.jsonsKema.ValidationFailure;
import com.github.erosb.jsonsKema.Validator;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/catalogue} (ADR-0026, ADR-0015): the packed function catalogue's bytes as {@code
 * application/json}, with no ETag, since the catalogue changes only with the build. The expected
 * bytes are the resource engine/mapping packs, read from the class path, never the service's
 * answer.
 */
@QuarkusTest
class CatalogueResourceTest {

  /** Where engine/mapping packs the catalogue (ADR-0025). */
  private static final String RESOURCE = "io/flottila/mapping/mapping/function-catalogue.json";

  /** The catalogue's contract, in {@code contract/} (ADR-0026). */
  private static final String SCHEMA = "function-catalogue.schema.json";

  /** The packed catalogue as the build writes it into {@code contract/} (ADR-0027). */
  private static final String CONTRACT_FILE = "function-catalogue.json";

  @TestHTTPResource URI root;

  private static byte[] packed() throws IOException {
    try (InputStream in =
        CatalogueResourceTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      assertThat(in).as("the resource %s", RESOURCE).isNotNull();
      return in.readAllBytes();
    }
  }

  @Test
  void getAnswersThePackedCatalogueByteForByte() throws IOException {
    byte[] packed = packed();
    // witness: it is the catalogue, keyed by function id, with spec 2's functions
    assertThat(Wire.read(packed).has("useOneAsMany")).as("useOneAsMany in %s", RESOURCE).isTrue();

    Http.Reply reply = new Http(root).get("api/catalogue");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.contentType()).startsWith("application/json");
    assertBytes(reply.body(), packed);
    assertThat(reply.header("ETag")).as("the ETag of the catalogue").isEmpty();
  }

  /**
   * The packed catalogue is contract (ADR-0027): the build writes {@code
   * contract/function-catalogue.json}, and it holds exactly the bytes {@code GET /api/catalogue}
   * serves, so the UI reads the palette without a Java build.
   */
  @Test
  void theContractFileHoldsTheServedBytes() throws IOException {
    byte[] committed =
        Files.readAllBytes(Fixtures.repositoryRoot().resolve("contract").resolve(CONTRACT_FILE));

    Http.Reply reply = new Http(root).get("api/catalogue");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertBytes(committed, reply.body());
  }

  /**
   * The served catalogue is an instance of the contract's {@code function-catalogue.schema.json}
   * (ADR-0026), validated with json-sKema as {@code FunctionCatalogueSchemaTest} does: as a whole,
   * and each entry as a catalogue of its own, since json-sKema 0.32.0 checks only the first member
   * of a {@code patternProperties} map (F-8).
   */
  @Test
  void theServedCatalogueMeetsTheContract() throws IOException {
    Path file = Fixtures.repositoryRoot().resolve("contract").resolve(SCHEMA);
    Schema schema = new SchemaLoader(new JsonParser(Files.readString(file, UTF_8)).parse()).load();
    // witness: the contract refuses an entry without its members
    assertThat(Validator.forSchema(schema).validate(new JsonParser("{\"sort\": {}}").parse()))
        .as("{\"sort\": {}} against %s", SCHEMA)
        .isNotNull();

    Http.Reply reply = new Http(root).get("api/catalogue");
    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    IJsonValue served = new JsonParser(new String(reply.body(), UTF_8)).parse();

    assertThat(Validator.forSchema(schema).validate(served)).as("the served catalogue").isNull();
    List<String> refused = new ArrayList<>();
    ((IJsonObject) served)
        .getProperties()
        .forEach(
            (name, entry) -> {
              ValidationFailure failure =
                  Validator.forSchema(schema).validate(new JsonObject(Map.of(name, entry)));
              if (failure != null) {
                refused.add(name.getValue() + ": " + failure);
              }
            });
    assertThat(((IJsonObject) served).getProperties()).as("the served entries").isNotEmpty();
    assertThat(refused).as("entries the contract refuses, each on its own").isEmpty();
  }
}
