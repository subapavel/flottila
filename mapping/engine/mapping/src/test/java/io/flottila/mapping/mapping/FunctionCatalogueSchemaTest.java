package io.flottila.mapping.mapping;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.erosb.jsonsKema.IJsonObject;
import com.github.erosb.jsonsKema.IJsonString;
import com.github.erosb.jsonsKema.IJsonValue;
import com.github.erosb.jsonsKema.JsonObject;
import com.github.erosb.jsonsKema.Schema;
import com.github.erosb.jsonsKema.SchemaLoader;
import com.github.erosb.jsonsKema.ValidationFailure;
import com.github.erosb.jsonsKema.Validator;
import io.flottila.mapping.mapping.JsonTree.Obj;
import io.flottila.mapping.mapping.JsonTree.Str;
import io.flottila.mapping.mapping.internal.StrictJsonReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The published catalogue's contract (ADR-0026): {@code contract/function-catalogue.schema.json},
 * JSON Schema 2020-12, closed as in ADR-0011. json-sKema validates as {@code SchemaStage} calls it:
 * the strict reader's tree into {@code SchemaLoader}, then a fresh {@code Validator.forSchema} per
 * document. json-sKema 0.32.0 checks only the first member of a {@code patternProperties} map
 * (F-8), so every entry of the packed catalogue is validated as a catalogue of its own as well, and
 * every witness is a catalogue of one entry that differs from an accepted one in one place.
 */
class FunctionCatalogueSchemaTest {

  static final String SCHEMA = "contract/function-catalogue.schema.json";

  /** The contract, read as {@code SchemaStage} reads the mapping-model schema. */
  private static Schema schema() throws Exception {
    Path file = Fixtures.repoRoot().resolve(SCHEMA);
    assertThat(file).as("the catalogue's contract").isRegularFile();
    byte[] bytes = Files.readAllBytes(file);
    assertThat(JsonTree.parse(Fixtures.strictUtf8(bytes)))
        .as("the contract is JSON Schema 2020-12")
        .isInstanceOfSatisfying(
            Obj.class,
            o ->
                assertThat(o.members().get("$schema"))
                    .isEqualTo(new Str("https://json-schema.org/draft/2020-12/schema")));
    return new SchemaLoader(StrictJsonReader.read(bytes)).load();
  }

  /** The failure of {@code document} against {@code schema}, null when it is valid. */
  private static ValidationFailure validate(Schema schema, IJsonValue document) {
    return Validator.forSchema(schema).validate(document);
  }

  private static ValidationFailure validate(Schema schema, String catalogue) throws Exception {
    return validate(schema, StrictJsonReader.read(catalogue.getBytes(UTF_8)));
  }

  @Test
  void thePackedCatalogueMeetsTheContract() throws Exception {
    Schema schema = schema();
    IJsonValue packed = StrictJsonReader.read(FunctionCatalogueTest.catalogue());
    assertThat(packed).isInstanceOf(IJsonObject.class);
    Map<IJsonString, IJsonValue> entries = ((IJsonObject) packed).getProperties();
    // witness: spec 2's functions are in it, so every entry below is one to check
    assertThat(entries.keySet().stream().map(IJsonString::getValue))
        .contains("removeContexts", "replaceValue", "useOneAsMany");

    assertThat(validate(schema, packed)).as("the packed catalogue").isNull();
    List<String> refused = new ArrayList<>();
    entries.forEach(
        (name, entry) -> {
          ValidationFailure failure = validate(schema, new JsonObject(Map.of(name, entry)));
          if (failure != null) {
            refused.add(name.getValue() + ": " + failure);
          }
        });
    assertThat(refused).as("entries the contract refuses, each on its own").isEmpty();
  }

  // --- witnesses: one-entry catalogues, each from the members below with one change

  private static final String KIND = "\"executionKind\": \"context\"";
  private static final String FAMILY = "\"family\": \"context\"";
  private static final String EXACT = "\"fidelity\": {\"level\": \"exact\"}";
  private static final String INPUTS = "\"inputs\": [{\"name\": \"queue\"}]";
  private static final String PORTABLE = "\"portable\": true";

  /** A string param that takes any string. */
  private static final String STRING_PARAM =
      "{\"name\": \"order\", \"type\": \"string\", \"default\": \"ascending\"}";

  /** A string param that takes only its values. */
  private static final String VALUES_PARAM =
      "{\"name\": \"order\", \"type\": \"string\", \"default\": \"ascending\","
          + " \"values\": [\"ascending\", \"descending\"]}";

  /** A string param that takes the number patterns lowering translates (ADR-0026, #74). */
  private static final String PATTERN_PARAM =
      "{\"name\": \"pattern\", \"type\": \"string\", \"default\": \"0\","
          + " \"syntax\": \"numberPattern\"}";

  /** A table param, whose keys are source values. */
  private static final String TABLE_PARAM =
      "{\"name\": \"mapping\", \"type\": \"table\", \"default\": {\"DE\": \"Germany\", \"x y\": \"\"}}";

  /** The catalogue {@code {"<id>": {<members>}}}. */
  private static String catalogue(String id, String... members) {
    return "{\"" + id + "\": {" + String.join(", ", members) + "}}";
  }

  /** The members of the accepted function {@code sortLike}, {@code fidelity} as given. */
  private static String withFidelity(String fidelity) {
    return catalogue("sortLike", KIND, FAMILY, "\"fidelity\": " + fidelity, INPUTS, PORTABLE);
  }

  /** The accepted function {@code sortLike} with {@code params} as its params. */
  private static String withParams(String params) {
    return catalogue("sortLike", KIND, FAMILY, EXACT, INPUTS, "\"params\": " + params, PORTABLE);
  }

  private static String deviation(int length) {
    return "{\"level\": \"deviation\", \"text\": \"" + "x".repeat(length) + "\"}";
  }

  private static Arguments refused(String why, String catalogue) {
    return Arguments.of(why, catalogue, false);
  }

  private static Arguments accepted(String why, String catalogue) {
    return Arguments.of(why, catalogue, true);
  }

  static Stream<Arguments> witnesses() {
    String id64 = "a" + "B1".repeat(31) + "c";
    return Stream.of(
        accepted(
            "a function without params",
            catalogue("sortLike", KIND, FAMILY, EXACT, INPUTS, PORTABLE)),
        accepted("a string param that takes any string", withParams("[" + STRING_PARAM + "]")),
        accepted("a string param that takes only its values", withParams("[" + VALUES_PARAM + "]")),
        accepted(
            "a table param, whose keys are source values", withParams("[" + TABLE_PARAM + "]")),
        accepted(
            "a string param in the syntax numberPattern (ADR-0026, #74)",
            withParams("[" + PATTERN_PARAM + "]")),
        accepted(
            "a string param in the syntax datePattern (ADR-0026, #80)",
            withParams("[" + PATTERN_PARAM.replace("numberPattern", "datePattern") + "]")),
        refused(
            "a string param with a syntax and values",
            withParams("[" + PATTERN_PARAM.replace("}", ", \"values\": [\"0\", \"0.00\"]}") + "]")),
        refused(
            "a syntax that is none",
            withParams("[" + PATTERN_PARAM.replace("numberPattern", "regex") + "]")),
        refused(
            "a table param with a syntax",
            withParams(
                "[" + TABLE_PARAM.replaceFirst("}$", ", \"syntax\": \"numberPattern\"}") + "]")),
        accepted(
            "a string param and a table param",
            withParams("[" + VALUES_PARAM + ", " + TABLE_PARAM + "]")),
        accepted(
            "no inputs, as constant has",
            catalogue(
                "constant",
                "\"executionKind\": \"single\"",
                "\"family\": \"constant\"",
                EXACT,
                "\"params\": [{\"name\": \"value\", \"type\": \"string\", \"default\": \"\"}]",
                PORTABLE)),
        accepted("an assumed function", withFidelity("{\"level\": \"assumed\"}")),
        accepted(
            "a deviation with a text",
            withFidelity("{\"level\": \"deviation\", \"text\": \"Rounds half to even.\"}")),
        accepted("a deviation with a text of 1 character", withFidelity(deviation(1))),
        accepted("a deviation with a text of 200 characters", withFidelity(deviation(200))),
        accepted("an id of one letter", catalogue("a", KIND, FAMILY, EXACT, INPUTS, PORTABLE)),
        accepted("an id of 64 characters", catalogue(id64, KIND, FAMILY, EXACT, INPUTS, PORTABLE)),
        refused(
            "a snake_case id", catalogue("use_one_as_many", KIND, FAMILY, EXACT, INPUTS, PORTABLE)),
        refused(
            "a PascalCase id", catalogue("UseOneAsMany", KIND, FAMILY, EXACT, INPUTS, PORTABLE)),
        refused(
            "an id that starts with a digit",
            catalogue("1sort", KIND, FAMILY, EXACT, INPUTS, PORTABLE)),
        refused(
            "an id of 65 characters", catalogue(id64 + "d", KIND, FAMILY, EXACT, INPUTS, PORTABLE)),
        refused("no family", catalogue("sortLike", KIND, EXACT, INPUTS, PORTABLE)),
        refused(
            "a PascalCase family",
            catalogue("sortLike", KIND, "\"family\": \"Context\"", EXACT, INPUTS, PORTABLE)),
        refused("no fidelity", catalogue("sortLike", KIND, FAMILY, INPUTS, PORTABLE)),
        refused("\"fidelity\": \"exact\"", withFidelity("\"exact\"")),
        refused("a level that is no fidelity", withFidelity("{\"level\": \"observed\"}")),
        refused("a deviation without its text", withFidelity("{\"level\": \"deviation\"}")),
        refused("a deviation with an empty text", withFidelity(deviation(0))),
        refused("a deviation with a text of 201 characters", withFidelity(deviation(201))),
        refused(
            "an exact fidelity with a text",
            withFidelity("{\"level\": \"exact\", \"text\": \"Rounds half to even.\"}")),
        refused(
            "an assumed fidelity with a text",
            withFidelity("{\"level\": \"assumed\", \"text\": \"Rounds half to even.\"}")),
        refused(
            "a poName member",
            catalogue(
                "sortLike", KIND, FAMILY, EXACT, INPUTS, PORTABLE, "\"poName\": \"SortLike\"")),
        refused(
            "an execution kind that is none",
            catalogue("sortLike", "\"executionKind\": \"node\"", FAMILY, EXACT, INPUTS, PORTABLE)),
        // a generating function, whose queue result is one value for every parent (#217, F-46)
        accepted(
            "a generating queue function",
            catalogue(
                "copyLike",
                "\"executionKind\": \"queue\"",
                "\"family\": \"constant\"",
                EXACT,
                INPUTS,
                "\"generating\": true",
                PORTABLE)),
        refused(
            "\"generating\": false",
            catalogue(
                "copyLike",
                "\"executionKind\": \"queue\"",
                "\"family\": \"constant\"",
                EXACT,
                INPUTS,
                "\"generating\": false",
                PORTABLE)),
        refused(
            "\"generating\": \"true\"",
            catalogue(
                "copyLike",
                "\"executionKind\": \"queue\"",
                "\"family\": \"constant\"",
                EXACT,
                INPUTS,
                "\"generating\": \"true\"",
                PORTABLE)),
        refused("no portable", catalogue("sortLike", KIND, FAMILY, EXACT, INPUTS)),
        refused(
            "inputs: []", catalogue("sortLike", KIND, FAMILY, EXACT, "\"inputs\": []", PORTABLE)),
        refused(
            "an input name with an underscore",
            catalogue(
                "sortLike",
                KIND,
                FAMILY,
                EXACT,
                "\"inputs\": [{\"name\": \"a_queue\"}]",
                PORTABLE)),
        refused("params: []", withParams("[]")),
        refused(
            "a param without a default",
            withParams("[" + VALUES_PARAM.replace("\"default\": \"ascending\", ", "") + "]")),
        refused(
            "a param name with a dot",
            withParams("[" + STRING_PARAM.replace("\"order\"", "\"sort.order\"") + "]")),
        refused(
            "a param type that is none",
            withParams("[" + STRING_PARAM.replace("\"string\"", "\"number\"") + "]")),
        refused(
            "a table default on a string param",
            withParams("[" + STRING_PARAM.replace("\"ascending\"", "{\"a\": \"b\"}") + "]")),
        refused(
            "values on a table param",
            withParams("[" + TABLE_PARAM.replace("}}", "}, \"values\": [\"DE\"]}") + "]")),
        refused(
            "a string default on a table param",
            withParams(
                "[" + TABLE_PARAM.replace("{\"DE\": \"Germany\", \"x y\": \"\"}", "\"DE\"") + "]")),
        refused(
            "a table row that is no string",
            withParams("[" + TABLE_PARAM.replace("\"Germany\"", "1") + "]")));
  }

  /**
   * ADR-0026: an id, a family, an input and a param name is camelCase, at most 64 characters; an
   * entry has family, executionKind, inputs?, params?, portable and fidelity, inputs and params
   * with at least one item; fidelity is exact, assumed, or a deviation with a text of 1 to 200
   * characters; a string param's default is a string, a table param's a table of strings.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("witnesses")
  void aWitnessIsRefusedOrAccepted(String why, String catalogue, boolean accepted)
      throws Exception {
    ValidationFailure failure = validate(schema(), catalogue);

    if (accepted) {
      assertThat(failure).as("%s is accepted: %s", why, catalogue).isNull();
    } else {
      assertThat(failure).as("%s is refused: %s", why, catalogue).isNotNull();
    }
  }

  /**
   * The function {@code fixValuesLike}, which has an object of every kind an entry holds; the
   * format argument k is written at the end of the k-th of {@link #OBJECTS}.
   */
  private static final String EVERY_OBJECT =
      """
      {"fixValuesLike": {"executionKind": "single", "family": "conversion",
        "inputs": [{"name": "value"%2$s}],
        "params": [{"name": "mode", "type": "string", "default": "useKey",
                    "values": ["useKey", "useDefault"]%3$s},
                   {"name": "mapping", "type": "table", "default": {"DE": "Germany"}%4$s}],
        "fidelity": {"level": "deviation", "text": "Rounds half to even."%5$s},
        "portable": true%1$s}}
      """;

  private static final List<String> OBJECTS =
      List.of("the entry", "an input", "a string param", "a table param", "the fidelity");

  /**
   * Closed as in ADR-0011: a member the contract does not name is refused in every object of an
   * entry, and at the root, where every member is a function id. A table's keys are source values,
   * so a table default takes any key ("a table param, whose keys are source values"); an exact or
   * assumed fidelity takes no text (the witnesses above).
   */
  @Test
  void theSchemaIsClosed() throws Exception {
    Schema schema = schema();
    String plain = EVERY_OBJECT.formatted("", "", "", "", "");
    // witness: without a member of its own the function is accepted
    assertThat(validate(schema, plain)).as("%s", plain).isNull();

    Map<String, String> withNote = new LinkedHashMap<>();
    for (int k = 0; k < OBJECTS.size(); k++) {
      Object[] ends = {"", "", "", "", ""};
      ends[k] = ", \"note\": \"x\"";
      withNote.put(OBJECTS.get(k), EVERY_OBJECT.formatted(ends));
    }
    withNote.put("the root", "{\"$comment\": \"x\", " + plain.substring(plain.indexOf('{') + 1));
    List<String> open = new ArrayList<>();
    for (Map.Entry<String, String> object : withNote.entrySet()) {
      assertThat(object.getValue()).as("a note in %s", object.getKey()).isNotEqualTo(plain);
      if (validate(schema, object.getValue()) == null) {
        open.add(object.getKey() + ": " + object.getValue());
      }
    }

    assertThat(open).as("objects that take a member the contract does not name").isEmpty();
  }
}
