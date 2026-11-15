package io.flottila.mapping.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.mapping.AtPointer;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.LoadResult;
import io.flottila.mapping.mapping.Loaded;
import io.flottila.mapping.mapping.Refused;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The model schema's WSDL selector (#96, ADR-0008 "JSON Schema and WSDL", ADR-0011): {@code
 * interface} is an EQName, {@code operation} and {@code fault} are NCNames, and {@code fault} is
 * present exactly when {@code direction} is {@code fault}. A model that breaks it is refused at
 * load, at its root selector; {@code formatVersion} stays 1.
 */
class WsdlSelectorSchemaTest {

  private static final String FINGERPRINT = "sha256:" + "ab".repeat(32);

  private static byte[] model(String wsdl) {
    return ("""
        {"formatVersion": 1,
         "source": {"bundle": ["service.wsdl"], "fingerprint": "%1$s", "root": {"wsdl": %2$s}},
         "target": {"bundle": ["out.xsd"], "fingerprint": "%1$s", "root": {"element": "Out"}}}
        """)
        .formatted(FINGERPRINT, wsdl)
        .getBytes(UTF_8);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "{\"interface\": \"OrderPort\", \"operation\": \"Submit\", \"direction\": \"input\"}",
        "{\"interface\": \"Q{urn:a}OrderPort\", \"operation\": \"Submit\", \"direction\":"
            + " \"output\"}",
        "{\"interface\": \"Q{}Port.v2\", \"operation\": \"_submit-1\", \"direction\": \"fault\","
            + " \"fault\": \"Rejected\"}",
        "{\"interface\": \"Q{urn:a}Bestellungsdienst\", \"operation\": \"übermitteln\","
            + " \"direction\": \"input\"}"
      })
  void a_selector_of_the_tightened_schema_loads(String wsdl) throws Exception {
    LoadResult result = new EngineFacade().load(model(wsdl));

    assertThat(result).as("%s", result).isInstanceOf(Loaded.class);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "{\"interface\": \"OrderPort\", \"operation\": \"Submit\", \"direction\": \"input\","
            + " \"fault\": \"Rejected\"}",
        "{\"interface\": \"OrderPort\", \"operation\": \"Submit\", \"direction\": \"output\","
            + " \"fault\": \"Rejected\"}",
        "{\"interface\": \"OrderPort\", \"operation\": \"Submit\", \"direction\": \"fault\"}",
        "{\"interface\": \"Q{urn:a OrderPort\", \"operation\": \"Submit\", \"direction\":"
            + " \"input\"}",
        "{\"interface\": \"po:OrderPort\", \"operation\": \"Submit\", \"direction\": \"input\"}",
        "{\"interface\": \"\", \"operation\": \"Submit\", \"direction\": \"input\"}",
        "{\"interface\": \"OrderPort\", \"operation\": \"1Submit\", \"direction\": \"input\"}",
        "{\"interface\": \"OrderPort\", \"operation\": \"po:Submit\", \"direction\": \"input\"}",
        "{\"interface\": \"OrderPort\", \"operation\": \"Submit\", \"direction\": \"fault\","
            + " \"fault\": \"Rejected Order\"}"
      })
  void a_selector_the_tightened_schema_forbids_is_refused_at_the_root(String wsdl)
      throws Exception {
    LoadResult result = new EngineFacade().load(model(wsdl));

    assertThat(result)
        .isInstanceOfSatisfying(
            Refused.class,
            refused ->
                assertThat(refused.diagnostics())
                    .extracting(d -> d.code() + " at " + location(d))
                    .containsExactly("schema-violation at /source/root"));
  }

  private static String location(Diagnostic diagnostic) {
    return ((AtPointer) diagnostic.location()).pointer();
  }
}
