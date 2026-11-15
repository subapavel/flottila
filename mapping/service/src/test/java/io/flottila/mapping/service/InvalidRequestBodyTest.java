package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.FINGERPRINT;
import static io.flottila.mapping.service.Fixtures.INPUT;
import static io.flottila.mapping.service.Fixtures.NEW_MAPPING;
import static io.flottila.mapping.service.Fixtures.ORDER;
import static io.flottila.mapping.service.Fixtures.OUTPUT;
import static io.flottila.mapping.service.Fixtures.assertBytes;
import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Fixtures.corpusMapping;
import static io.flottila.mapping.service.Fixtures.unwired;
import static io.flottila.mapping.service.Fixtures.workingCopy;
import static io.flottila.mapping.service.Wire.assertProblem;
import static io.flottila.mapping.service.Wire.createRequest;
import static io.flottila.mapping.service.Wire.described;
import static io.flottila.mapping.service.Wire.structureRequest;
import static io.flottila.mapping.service.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.mapping.AtAuthoringId;
import io.flottila.mapping.mapping.AtFilePosition;
import io.flottila.mapping.mapping.AtPointer;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.Refused;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A request body that is not of its endpoint's shape is 400 {@code invalid-request}, never a 500
 * and never another slug (ADR-0024, "Service boundary"; #28 rulings). Every case is one edit of a
 * valid request, the template, which {@link #theTemplatesAreValidRequests} shows the service
 * accepts: so each case is refused for its edit alone.
 *
 * <p>The edits, for every endpoint that binds a body: a missing member, a null member, an empty
 * body, an unknown member, a member of the wrong type and, where a root selector is a member, a
 * root that is none of the model schema's three shapes ({@code
 * contract/mapping-model.schema.json#/$defs/rootSelector}). A number where the contract says string
 * is the wrong type too: Jackson's scalar coercion would make it a string.
 *
 * <p>{@code PUT {id}} binds no request: its body is the model, read by the engine. A body that is
 * no JSON document is {@code malformed-json}; a JSON document that is no model is refused by stage
 * 1 with 422 {@code model-refused} (#28 design, "PUT"). The test run's {@code model} is a working
 * copy, so a root shape inside it is stage 1's diagnostic and a 200 (ADR-0015).
 */
@QuarkusTest
class InvalidRequestBodyTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The id of every create case: it must never be created. */
  private static final String CREATE_ID = "invalid-create/order";

  private static final String BUNDLE = "../structures/skeleton/order.xsd";
  private static final String TEST_ID = "invalid-test/order";
  private static final String SAVE_ID = "invalid-save/order";

  @TestHTTPResource URI root;

  @BeforeEach
  void layOut() {
    TempWorkspace.mkdirs("invalid-create");
    TempWorkspace.write(TEST_ID + ".mapping.json", corpusMapping());
    TempWorkspace.write(SAVE_ID + ".mapping.json", unwired());
  }

  // --- the templates ---

  private static ObjectNode createTemplate() {
    return parse(createRequest(CREATE_ID, BUNDLE, ORDER));
  }

  private static ObjectNode structureTemplate() {
    return parse(structureRequest("structures/skeleton", "order.xsd", ORDER));
  }

  private static ObjectNode testTemplate() {
    return parse(testRequest(workingCopy("\"a1\""), INPUT));
  }

  @Test
  void theTemplatesAreValidRequests() {
    // the witness of every case below: one edit away from each of these, the service says yes
    ObjectNode create = createTemplate();
    create.put("id", "invalid-create/accepted");
    Http.Reply created = post("api/mappings", serialised(create));
    assertThat(created.status()).as("%s", created).isEqualTo(201);

    Http.Reply loaded = post("api/structures", serialised(structureTemplate()));
    assertThat(loaded.status()).as("%s", loaded).isEqualTo(200);
    assertThat(loaded.json().path("kind").textValue()).isEqualTo("loaded");
    assertThat(loaded.json().path("fingerprint").textValue()).isEqualTo(FINGERPRINT);

    Http.Reply run = post("api/mappings/" + TEST_ID + "/test", serialised(testTemplate()));
    assertThat(run.status()).as("%s", run).isEqualTo(200);
    assertThat(run.json().path("kind").textValue()).isEqualTo("output");
    assertThat(run.json().path("output").textValue()).isEqualTo(OUTPUT);

    Http.Reply saved = new Http(root).put("api/mappings/" + SAVE_ID, JSON_TYPE, corpusMapping());
    assertThat(saved.status()).as("%s", saved).isEqualTo(200);
    assertBytes(saved.body(), corpusMapping());
  }

  // --- POST /api/mappings ---

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCreateBodies")
  void createRefusesAnInvalidBodyAsInvalidRequest(byte[] body) {
    assertProblem(post("api/mappings", body), 400, "invalid-request");
    assertThat(TempWorkspace.exists(CREATE_ID + ".mapping.json")).as("never created").isFalse();
  }

  static Stream<Named<byte[]>> invalidCreateBodies() {
    ObjectNode t = createTemplate();
    List<Named<byte[]>> cases = new ArrayList<>();
    cases.addAll(
        List.of(
            edit("missing id", t, r -> r.remove("id")),
            edit("missing source.bundle", t, r -> object(r, "source").remove("bundle")),
            edit("missing source.root", t, r -> object(r, "source").remove("root")),
            edit("missing target.root", t, r -> object(r, "target").remove("root")),
            edit("null id", t, r -> r.putNull("id")),
            edit("null source.bundle", t, r -> object(r, "source").putNull("bundle")),
            edit("null source.root", t, r -> object(r, "source").putNull("root")),
            edit("null entry in source.bundle", t, r -> bundle(object(r, "source")).addNull()),
            Named.of("an empty body", new byte[0]),
            Named.of("the body null", bytes("null")),
            edit("unknown member at the top", t, r -> r.put("extra", 1)),
            edit("unknown member in source", t, r -> object(r, "source").put("extra", 1)),
            edit("wrong type: id a number", t, r -> r.put("id", 5)),
            edit("wrong type: id a boolean", t, r -> r.put("id", true)),
            edit("wrong type: id a float", t, r -> r.put("id", 1.5)),
            edit("wrong type: id an object", t, r -> r.putObject("id")),
            edit("wrong type: description a number", t, r -> r.put("description", 5)),
            edit("wrong type: description an object", t, r -> r.putObject("description")),
            edit("wrong type: source an array", t, r -> r.putArray("source")),
            edit(
                "wrong type: source.bundle a string",
                t,
                r -> object(r, "source").put("bundle", BUNDLE)),
            Named.of("wrong type: the body an array", bytes("[]"))));
    for (Named<String> shape : invalidRoots()) {
      cases.add(
          edit(
              "invalid root shape in source: " + shape.getName(),
              t,
              r -> object(r, "source").set("root", tree(shape.getPayload()))));
    }
    return cases.stream();
  }

  /**
   * A create names a structure only once it is chosen (ADR-0029): the owner changed this on
   * 2026-11-09 ("I would pick them after creation, on the Signature tab", #153), so a missing or
   * null source or target is no longer an invalid request. Each case is one edit of the template
   * under an id of its own; the side it names is read and stored with its fingerprint, as {@code
   * NEW_MAPPING} has it, and the other side is absent.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("createsWithoutStructures")
  void createWithoutStructuresIs201(String what, byte[] body, String id, ObjectNode expected) {
    Http.Reply reply = post("api/mappings", body);

    assertThat(reply.status()).as("%s", reply).isEqualTo(201);
    assertThat(reply.json()).isEqualTo(expected);
    assertBytes(TempWorkspace.read(id + ".mapping.json"), reply.body());
  }

  static Stream<Arguments> createsWithoutStructures() {
    ObjectNode t = createTemplate();
    return Stream.of(
        created("missing source", t, r -> r.remove("source"), m -> m.remove("source")),
        created("missing target", t, r -> r.remove("target"), m -> m.remove("target")),
        created("null source", t, r -> r.putNull("source"), m -> m.remove("source")),
        created("null target", t, r -> r.putNull("target"), m -> m.remove("target")),
        // a null description is none, as a missing one (ADR-0029)
        created("null description", t, r -> r.putNull("description"), m -> {}),
        created(
            "missing source and target",
            t,
            r -> r.remove(List.of("source", "target")),
            m -> m.remove(List.of("source", "target"))));
  }

  /**
   * The case {@code name}: {@code edit} of {@code template} under an id named after it, and {@code
   * NEW_MAPPING} after {@code expected} as the model it creates.
   */
  private static Arguments created(
      String name, ObjectNode template, Consumer<ObjectNode> edit, Consumer<ObjectNode> expected) {
    String id = "invalid-create/" + name.replace(' ', '-');
    ObjectNode request =
        edited(
            template,
            r -> {
              r.put("id", id);
              edit.accept(r);
            });
    return Arguments.of(name, serialised(request), id, edited(parse(NEW_MAPPING), expected));
  }

  // --- POST /api/structures ---

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidStructureBodies")
  void structuresRefuseAnInvalidBodyAsInvalidRequest(byte[] body) {
    assertProblem(post("api/structures", body), 400, "invalid-request");
  }

  static Stream<Named<byte[]>> invalidStructureBodies() {
    ObjectNode t = structureTemplate();
    List<Named<byte[]>> cases = new ArrayList<>();
    cases.addAll(
        List.of(
            edit("missing base", t, r -> r.remove("base")),
            edit("missing bundle", t, r -> r.remove("bundle")),
            edit("missing root", t, r -> r.remove("root")),
            edit("null base", t, r -> r.putNull("base")),
            edit("null bundle", t, r -> r.putNull("bundle")),
            edit("null root", t, r -> r.putNull("root")),
            edit("null entry in bundle", t, r -> bundle(r).addNull()),
            Named.of("an empty body", new byte[0]),
            Named.of("the body null", bytes("null")),
            edit("unknown member", t, r -> r.put("extra", 1)),
            edit("wrong type: base a number", t, r -> r.put("base", 0)),
            edit("wrong type: base a boolean", t, r -> r.put("base", true)),
            edit("wrong type: base a float", t, r -> r.put("base", 1.5)),
            edit("wrong type: base an object", t, r -> r.putObject("base")),
            edit("wrong type: bundle a string", t, r -> r.put("bundle", "order.xsd")),
            Named.of("wrong type: the body an array", bytes("[]"))));
    for (Named<String> shape : invalidRoots()) {
      cases.add(
          edit(
              "invalid root shape: " + shape.getName(),
              t,
              r -> r.set("root", tree(shape.getPayload()))));
    }
    return cases.stream();
  }

  /**
   * Roots that are none of the model schema's three shapes: {@code elementRoot}, {@code wsdlRoot}
   * with its {@code wsdlOperation}, and {@code jsonRoot}, each with {@code additionalProperties:
   * false}.
   */
  static List<Named<String>> invalidRoots() {
    String operation = "\"interface\": \"OrderService\", \"operation\": \"submit\"";
    return List.of(
        Named.of("no member", "{}"),
        Named.of("element and json at once", "{\"element\": \"" + ORDER + "\", \"json\": \"$\"}"),
        Named.of("element a number", "{\"element\": 5}"),
        Named.of("json other than $", "{\"json\": \"#\"}"),
        Named.of("an unknown kind", "{\"xpath\": \"/Order\"}"),
        Named.of("wsdl without direction", "{\"wsdl\": {" + operation + "}}"),
        Named.of(
            "wsdl direction outside the enum",
            "{\"wsdl\": {" + operation + ", \"direction\": \"FAULT\"}}"),
        Named.of(
            "wsdl with an unknown member",
            "{\"wsdl\": {" + operation + ", \"direction\": \"input\", \"port\": \"p\"}}"),
        Named.of(
            "wsdl fault a number",
            "{\"wsdl\": {" + operation + ", \"direction\": \"fault\", \"fault\": 5}}"),
        Named.of(
            "wsdl interface a number",
            "{\"wsdl\": {\"interface\": 5, \"operation\": \"submit\", \"direction\": \"input\"}}"),
        Named.of(
            "wsdl operation a number",
            "{\"wsdl\": {\"interface\": \"OrderService\", \"operation\": 5,"
                + " \"direction\": \"input\"}}"),
        Named.of(
            "wsdl with a fault but three members: the size check",
            "{\"wsdl\": {\"interface\": \"OrderService\", \"direction\": \"fault\","
                + " \"fault\": \"Rejected\"}}"),
        Named.of("wsdl a string", "{\"wsdl\": \"OrderService.submit\"}"),
        Named.of("a string", Wire.quoted(ORDER)),
        Named.of("an array", "[]"));
  }

  // --- POST /api/mappings/{id}/test ---

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidTestBodies")
  void testRunRefusesAnInvalidBodyAsInvalidRequest(byte[] body) {
    assertProblem(post("api/mappings/" + TEST_ID + "/test", body), 400, "invalid-request");
  }

  static Stream<Named<byte[]>> invalidTestBodies() {
    ObjectNode t = testTemplate();
    return Stream.of(
        edit("missing model", t, r -> r.remove("model")),
        edit("missing input", t, r -> r.remove("input")),
        edit("null model", t, r -> r.putNull("model")),
        edit("null input", t, r -> r.putNull("input")),
        Named.of("an empty body", new byte[0]),
        Named.of("the body null", bytes("null")),
        edit("unknown member", t, r -> r.put("extra", 1)),
        edit("wrong type: input a number", t, r -> r.put("input", 5)),
        edit("wrong type: input a boolean", t, r -> r.put("input", true)),
        edit("wrong type: input a float", t, r -> r.put("input", 1.5)),
        edit("wrong type: input an object", t, r -> r.putObject("input")),
        Named.of("wrong type: the body an array", bytes("[]")));
  }

  /**
   * The problem's detail tells a missing member from a null one (ADR-0024, "Service boundary"): the
   * client learns which edit to make. Each pair is one member of one endpoint, removed or set to
   * null in the same template.
   */
  @Test
  void aMissingMemberIsNamedMissingAndANullMemberNull() {
    Map<String, ObjectNode[]> pairs = new LinkedHashMap<>();
    // every required member of every request record (#153: each is @JsonProperty(required = true))
    pairs.put("api/mappings id", pair(createTemplate(), "id"));
    pairs.put("api/mappings source.bundle", pair(createTemplate(), "source", "bundle"));
    pairs.put("api/mappings source.root", pair(createTemplate(), "source", "root"));
    pairs.put("api/structures base", pair(structureTemplate(), "base"));
    pairs.put("api/structures bundle", pair(structureTemplate(), "bundle"));
    pairs.put("api/structures root", pair(structureTemplate(), "root"));
    pairs.put("api/mappings/" + TEST_ID + "/test model", pair(testTemplate(), "model"));
    pairs.put("api/mappings/" + TEST_ID + "/test input", pair(testTemplate(), "input"));

    pairs.forEach(
        (where, pair) -> {
          String path = where.substring(0, where.indexOf(' '));
          String missing =
              assertProblem(post(path, serialised(pair[0])), 400, "invalid-request")
                  .path("detail")
                  .textValue();
          String isNull =
              assertProblem(post(path, serialised(pair[1])), 400, "invalid-request")
                  .path("detail")
                  .textValue();

          assertThat(missing)
              .as("missing %s", where)
              .containsIgnoringCase("missing")
              .doesNotContainIgnoringCase("null");
          assertThat(isNull)
              .as("null %s", where)
              .containsIgnoringCase("null")
              .doesNotContainIgnoringCase("missing");
        });
  }

  /** {@code template} without {@code member}, and with {@code member} set to null. */
  private static ObjectNode[] pair(ObjectNode template, String member) {
    return new ObjectNode[] {
      edited(template, r -> r.remove(member)), edited(template, r -> r.putNull(member))
    };
  }

  /** {@code template} without {@code member} of its object {@code in}, and with it set to null. */
  private static ObjectNode[] pair(ObjectNode template, String in, String member) {
    return new ObjectNode[] {
      edited(template, r -> object(r, in).remove(member)),
      edited(template, r -> object(r, in).putNull(member))
    };
  }

  /** A root shape inside the working copy is the model's: stage 1 refuses it with a 200. */
  @Test
  void testRunAnswersAnInvalidRootInTheModelAsAStage1Refusal() {
    ObjectNode request = testTemplate();
    object(object(request, "model"), "source")
        .set("root", parse("{\"element\": \"" + ORDER + "\", \"json\": \"$\"}"));

    Http.Reply reply = post("api/mappings/" + TEST_ID + "/test", serialised(request));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    assertThat(reply.json().path("kind").textValue()).isEqualTo("refused");
    // MappingModelSchemaStageTest.loadFailsClosedWithSchemaViolationOnAKeywordWithoutACode
    assertThat(described(reply.json().path("diagnostics")))
        .containsExactly("error schema-violation at pointer /source/root");
  }

  // --- PUT /api/mappings/{id} ---

  /**
   * The expected diagnostics are those of {@code MappingModelSchemaStageTest} for the same edit; a
   * null member has no case there, so the engine facade is its oracle.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedModels")
  void saveRefusesAMalformedModelAsTheEngineDoes(
      String name, byte[] body, int status, String slug, List<String> diagnostics)
      throws Exception {
    List<String> expected = diagnostics == null ? engineRefusal(body) : diagnostics;

    JsonNode problem =
        assertProblem(new Http(root).put("api/mappings/" + SAVE_ID, JSON_TYPE, body), status, slug);

    if (status == 422) {
      assertThat(described(problem.path("diagnostics"))).isNotEmpty().isEqualTo(expected);
    }
    assertBytes(TempWorkspace.read(SAVE_ID + ".mapping.json"), unwired());
  }

  static Stream<Arguments> malformedModels() {
    ObjectNode m = parse(new String(corpusMapping(), java.nio.charset.StandardCharsets.UTF_8));
    return Stream.of(
        Arguments.of(
            "an empty body: no JSON document", new byte[0], 400, "malformed-json", List.of()),
        Arguments.of(
            "missing source.fingerprint",
            serialised(edited(m, r -> object(r, "source").remove("fingerprint"))),
            422,
            "model-refused",
            List.of("error missing-member at pointer /source")),
        Arguments.of(
            "null source.fingerprint",
            serialised(edited(m, r -> object(r, "source").putNull("fingerprint"))),
            422,
            "model-refused",
            null),
        Arguments.of(
            "null source",
            serialised(edited(m, r -> r.putNull("source"))),
            422,
            "model-refused",
            null),
        Arguments.of(
            "unknown member in source",
            serialised(edited(m, r -> object(r, "source").put("hash", "x"))),
            422,
            "model-refused",
            List.of("error unknown-member at pointer /source/hash")),
        Arguments.of(
            "wrong type: a number in source.bundle",
            serialised(edited(m, r -> bundle(object(r, "source")).add(3))),
            422,
            "model-refused",
            List.of("error wrong-type at pointer /source/bundle/1")),
        Arguments.of(
            "invalid root shape: element and json at once",
            serialised(
                edited(
                    m,
                    r ->
                        object(r, "source")
                            .set(
                                "root",
                                parse("{\"element\": \"" + ORDER + "\", \"json\": \"$\"}")))),
            422,
            "model-refused",
            List.of("error schema-violation at pointer /source/root")));
  }

  // --- helpers ---

  private static final String JSON_TYPE = "application/json";

  private Http.Reply post(String path, byte[] body) {
    return new Http(root).post(path, JSON_TYPE, body);
  }

  /** The engine's own refusal of {@code model}, described as {@link Wire#described} does. */
  private static List<String> engineRefusal(byte[] model) throws Exception {
    if (!(new EngineFacade().load(model) instanceof Refused(List<Diagnostic> diagnostics))) {
      throw new AssertionError("the engine loads the case: it is no malformed model");
    }
    List<String> described = new ArrayList<>();
    for (Diagnostic d : diagnostics) {
      String at =
          switch (d.location()) {
            case AtPointer(String pointer) -> "pointer " + pointer;
            case AtAuthoringId(var id) -> "id " + id.value();
            case AtFilePosition p -> "file " + p.file() + " " + p.line() + ":" + p.column();
          };
      described.add(d.severity().name().toLowerCase(Locale.ROOT) + " " + d.code() + " at " + at);
    }
    return described;
  }

  private static Named<byte[]> edit(String name, ObjectNode template, Consumer<ObjectNode> edit) {
    return Named.of(name, serialised(edited(template, edit)));
  }

  private static ObjectNode edited(ObjectNode template, Consumer<ObjectNode> edit) {
    ObjectNode copy = template.deepCopy();
    edit.accept(copy);
    return copy;
  }

  private static ObjectNode object(ObjectNode node, String member) {
    return (ObjectNode) node.get(member);
  }

  private static ArrayNode bundle(ObjectNode structure) {
    return (ArrayNode) structure.get("bundle");
  }

  private static ObjectNode parse(String json) {
    return (ObjectNode) tree(json);
  }

  /** Any JSON value: a root shape may be a string or an array. */
  private static JsonNode tree(String json) {
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new AssertionError(e);
    }
  }

  private static byte[] serialised(JsonNode node) {
    try {
      return JSON.writeValueAsBytes(node);
    } catch (JsonProcessingException e) {
      throw new AssertionError(e);
    }
  }
}
