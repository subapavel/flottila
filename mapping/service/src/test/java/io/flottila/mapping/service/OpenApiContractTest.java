package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The drift test of the committed contract (ADR-0015, ADR-0024). smallrye-openapi writes {@code
 * contract/openapi.json} when the application is built, which a {@code @QuarkusTest} does, and the
 * test reads that file. Unions and required lists are compared with the service's compiled classes
 * (found by simple name under {@code target/classes}) and with the shapes the #28 design lists.
 */
@QuarkusTest
class OpenApiContractTest {

  private static final String SCHEMAS = "#/components/schemas/";

  /** The unions and their kinds (ADR-0024, "Results"). */
  private static final Map<String, Map<String, String>> UNIONS =
      Map.of(
          "LocationDto",
              Map.of(
                  "pointer", "PointerLocation",
                  "authoring-id", "AuthoringIdLocation",
                  "file-position", "FilePositionLocation",
                  // ADR-0015 {side, fieldPath} (#105)
                  "field-path", "FieldPathLocation"),
          "TestResult",
              Map.of("output", "TestOutput", "refused", "TestRefused", "error", "TestError"),
          "StructureResponse", Map.of("loaded", "StructureLoaded", "refused", "StructureRefused"),
          // ADR-0015 "Trace" since #53: an entry by kind; since #83 an atomic value too
          "TraceEntryDto",
              Map.of(
                  "queue", "TraceQueueDto",
                  "occurrences", "TraceOccurrencesDto",
                  "atomic", "TraceAtomicDto"));

  /**
   * Properties and, after the bar, the required ones (#28 design, "Endpoints", "Shared shapes").
   */
  private static final Map<String, String> RECORDS =
      Map.ofEntries(
          // ADR-0029 (#153): a summary lists the package, the description and the root names; a
          // new mapping needs only its id
          Map.entry(
              "MappingSummary", "description id name package source target | id name package"),
          Map.entry("CreateMappingRequest", "description id source target | id"),
          Map.entry("StructureChoice", "bundle root | bundle root"),
          Map.entry("TestRequest", "input model | input model"),
          Map.entry("StructureRequest", "base bundle root | base bundle root"),
          // since #53 output and error carry the trace; refused never ran, so it has none
          Map.entry("TestOutput", "diagnostics kind output trace | diagnostics kind output trace"),
          Map.entry("TestRefused", "diagnostics kind | diagnostics kind"),
          Map.entry("TestError", "diagnostics error kind trace | diagnostics error kind trace"),
          // ADR-0015 "Trace" since #53; max is absent when unbounded
          Map.entry(
              "TraceQueueDto",
              "capped contextCount contexts kind slotCount"
                  + " | capped contextCount contexts kind slotCount"),
          Map.entry("TraceOccurrencesDto", "capped kind max min parents | capped kind min parents"),
          // ADR-0015 {atomic: {type, value}} since #83
          Map.entry("TraceAtomicDto", "kind type value | kind type value"),
          Map.entry("ParentSlotDto", "created parentSlot slots | created parentSlot slots"),
          Map.entry("SlotStatusDto", "slot status | slot status"),
          // ADR-0015 since #52 (O2): authoringId, absent for flottila:TIMEOUT
          Map.entry("RuntimeErrorDto", "authoringId code message | code message"),
          Map.entry("StructureLoaded", "fingerprint kind root types | fingerprint kind root types"),
          Map.entry("StructureRefused", "diagnostics kind | diagnostics kind"),
          // ADR-0015, ADR-0027: compile answers its diagnostics alone (#105)
          Map.entry("CompileResponse", "diagnostics | diagnostics"),
          Map.entry(
              "FieldDto",
              "kind localName max min namespace nillable refusal step type valueType"
                  + " | kind localName min namespace nillable step"),
          // ADR-0027 "Refused fields on the wire" (#116)
          Map.entry("RefusalDto", "code column file line side | code column file line side"),
          Map.entry(
              "DiagnosticDto", "code location message severity | code location message severity"),
          Map.entry("PointerLocation", "kind pointer | kind pointer"),
          Map.entry("AuthoringIdLocation", "authoringId kind | authoringId kind"),
          Map.entry("FilePositionLocation", "column file kind line | column file kind line"),
          Map.entry("FieldPathLocation", "fieldPath kind side | fieldPath kind side"));

  @Test
  void everyOneOfEqualsThePermittedSubclasses() throws Exception {
    JsonNode schemas = schemas();
    Map<String, Class<?>> classes = serviceClasses();

    assertThat(names(schemas)).containsAll(UNIONS.keySet());
    for (Map.Entry<String, Map<String, String>> union : UNIONS.entrySet()) {
      JsonNode schema = schemas.path(union.getKey());
      Set<String> refs = new TreeSet<>();
      for (String subtype : union.getValue().values()) {
        refs.add(SCHEMAS + subtype);
      }
      assertThat(oneOf(schema)).as(union.getKey()).isEqualTo(refs);
      assertThat(schema.path("discriminator").path("propertyName").textValue())
          .as(union.getKey())
          .isEqualTo("kind");
      Map<String, String> mapping = new TreeMap<>();
      schema
          .path("discriminator")
          .path("mapping")
          .properties()
          .forEach(e -> mapping.put(e.getKey(), e.getValue().textValue()));
      Map<String, String> expected = new TreeMap<>();
      union.getValue().forEach((kind, subtype) -> expected.put(kind, SCHEMAS + subtype));
      assertThat(mapping).as(union.getKey()).isEqualTo(expected);
      union
          .getValue()
          .forEach(
              (kind, subtype) ->
                  assertThat(
                          schemas
                              .path(subtype)
                              .path("properties")
                              .path("kind")
                              .path("const")
                              .textValue())
                      .as(subtype + ".kind")
                      .isEqualTo(kind));
    }
    // every sealed service type that is a schema is a oneOf of exactly its permitted subclasses
    int sealed = 0;
    for (Class<?> type : classes.values()) {
      if (type.isSealed() && schemas.has(type.getSimpleName())) {
        sealed++;
        Set<String> permitted = new TreeSet<>();
        for (Class<?> sub : type.getPermittedSubclasses()) {
          permitted.add(SCHEMAS + sub.getSimpleName());
        }
        assertThat(oneOf(schemas.path(type.getSimpleName())))
            .as(type.getName())
            .isEqualTo(permitted);
      }
    }
    assertThat(sealed)
        .as("sealed service types among the schemas")
        .isGreaterThanOrEqualTo(UNIONS.size());
  }

  @Test
  void requiredEqualsTheNonOptionalComponents() throws Exception {
    JsonNode schemas = schemas();
    Map<String, Class<?>> classes = serviceClasses();

    for (Map.Entry<String, String> entry : RECORDS.entrySet()) {
      String[] shape = entry.getValue().split(" \\| ");
      JsonNode schema = schemas.path(entry.getKey());
      assertThat(schema.isObject()).as("schema %s", entry.getKey()).isTrue();
      assertThat(names(schema.path("properties")))
          .as(entry.getKey() + " properties")
          .isEqualTo(words(shape[0]));
      assertThat(strings(schema.path("required")))
          .as(entry.getKey() + " required")
          .isEqualTo(words(shape[1]));
    }
    // every service record that is a schema: required = the components that are no Optional, by
    // their member name (a @JsonProperty value, as for "package", a Java keyword (#153))
    int records = 0;
    for (Class<?> type : classes.values()) {
      if (type.isRecord() && schemas.has(type.getSimpleName())) {
        records++;
        Set<String> required = new TreeSet<>();
        for (RecordComponent c : type.getRecordComponents()) {
          if (c.getType() != Optional.class) {
            JsonProperty named = c.getAccessor().getAnnotation(JsonProperty.class);
            required.add(named == null || named.value().isEmpty() ? c.getName() : named.value());
          }
        }
        assertThat(strings(schemas.path(type.getSimpleName()).path("required")))
            .as(type.getName())
            .isEqualTo(required);
      }
    }
    assertThat(records)
        .as("service records among the schemas")
        .isGreaterThanOrEqualTo(RECORDS.size());
  }

  /**
   * ADR-0015 "Trace" since #53: output and error carry the trace, an object of entries by authoring
   * id; a queue's contexts are arrays of strings or null, null for SUPPRESS; a slot's status is
   * created, suppressed or truncated. A {@code $ref} into the contract directory is followed (the
   * fallback of O2), as is one to a component.
   */
  @Test
  void theTraceIsAnObjectOfEntriesWhoseContextsHoldStringsOrNull() throws IOException {
    JsonNode schemas = schemas();

    for (String result : List.of("TestOutput", "TestError")) {
      JsonNode trace = resolved(schemas.path(result).path("properties").path("trace"));
      assertThat(trace.path("type").textValue()).as("%s.trace", result).isEqualTo("object");
      assertThat(trace.path("additionalProperties").path("$ref").textValue())
          .as("%s.trace entries", result)
          .isEqualTo(SCHEMAS + "TraceEntryDto");
    }
    JsonNode contexts = resolved(schemas.path("TraceQueueDto").path("properties").path("contexts"));
    assertThat(contexts.path("type").textValue()).as("contexts").isEqualTo("array");
    JsonNode context = resolved(contexts.path("items"));
    assertThat(context.path("type").textValue()).as("a context").isEqualTo("array");
    JsonNode value = resolved(context.path("items"));
    assertThat(strings(value.path("type")))
        .as("a value: %s", value)
        .isEqualTo(words("null string"));
    JsonNode status = resolved(schemas.path("SlotStatusDto").path("properties").path("status"));
    assertThat(strings(status.path("enum")))
        .as("a slot status: %s", status)
        .isEqualTo(words("created suppressed truncated"));
    JsonNode parents =
        resolved(schemas.path("TraceOccurrencesDto").path("properties").path("parents"));
    assertThat(parents.path("items").path("$ref").textValue())
        .as("parents")
        .isEqualTo(SCHEMAS + "ParentSlotDto");
    JsonNode slots = resolved(schemas.path("ParentSlotDto").path("properties").path("slots"));
    assertThat(slots.path("items").path("$ref").textValue())
        .as("slots")
        .isEqualTo(SCHEMAS + "SlotStatusDto");
  }

  /**
   * {@code schema}, or what its {@code $ref} names: a component, or a file in the contract
   * directory.
   */
  private static JsonNode resolved(JsonNode schema) throws IOException {
    String ref = schema.path("$ref").textValue();
    if (ref == null) {
      return schema;
    }
    if (ref.startsWith(SCHEMAS)) {
      return resolved(schemas().path(ref.substring(SCHEMAS.length())));
    }
    assertThat(ref).as("a relative ref").startsWith("./");
    return resolved(Wire.read(Files.readAllBytes(contractDirectory().resolve(ref.substring(2)))));
  }

  @Test
  void hasNoServersEntry() throws IOException {
    assertThat(names(contract())).contains("openapi", "paths").doesNotContain("servers");
  }

  @Test
  void everyRelativeRefNamesAFileInContract() throws IOException {
    JsonNode contract = contract();
    List<String> refs = new ArrayList<>();
    collectRefs(contract, refs);
    List<String> relative = refs.stream().filter(r -> r.startsWith("./")).toList();

    // the design's three: the root selector, the test run's model and the save's body
    JsonNode schemas = contract.path("components").path("schemas");
    assertThat(
            schemas
                .path("StructureChoice")
                .path("properties")
                .path("root")
                .path("$ref")
                .textValue())
        .isEqualTo("./mapping-model.schema.json#/$defs/rootSelector");
    assertThat(
            schemas.path("TestRequest").path("properties").path("model").path("$ref").textValue())
        .isEqualTo("./mapping-model.schema.json");
    assertThat(
            contract
                .path("paths")
                .path("/api/mappings/{id}")
                .path("put")
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("$ref")
                .textValue())
        .isEqualTo("./mapping-model.schema.json");
    assertThat(relative).isNotEmpty();
    for (String ref : relative) {
      String[] parts = ref.substring(2).split("#", 2);
      Path file = contractDirectory().resolve(parts[0]);
      assertThat(file).as(ref).isRegularFile().hasParent(contractDirectory());
      if (parts.length == 2) {
        JsonNode target = Wire.read(Files.readAllBytes(file)).at(parts[1]);
        assertThat(target.isMissingNode()).as("%s resolves", ref).isFalse();
      }
    }
  }

  /** The problem+json media type of every 4xx answer (ADR-0024, "Problems"). */
  private static final String PROBLEM_JSON = "application/problem+json";

  /**
   * The 4xx statuses of each operation (#28 design, "Endpoints"; ADR-0024). A 400 is also {@code
   * invalid-workspace-path} wherever an id, a base or a bundle reaches the workspace; 404 is a
   * missing mapping or directory; 415 is any body that is not {@code application/json}.
   */
  private static final Map<String, Set<String>> PROBLEMS =
      Map.of(
          // ADR-0026: no parameter, no body, nothing to refuse
          "get /api/catalogue", Set.of(),
          "get /api/mappings", Set.of(),
          "post /api/mappings", Set.of("400", "404", "409", "415", "422"),
          "get /api/mappings/{id}", Set.of("400", "404", "422"),
          // ADR-0027: 409 mapping-changed when If-Match differs from the stored file's tag
          "put /api/mappings/{id}", Set.of("400", "404", "409", "415", "422"),
          "post /api/mappings/{id}/test", Set.of("400", "404", "415"),
          // #99: a stage 1 or 2 refusal is 422, as for Save; a structure load error is a 200
          "post /api/mappings/{id}/refresh", Set.of("400", "404", "415", "422"),
          // #105: every stage's diagnostics are a 200 (ADR-0015, ADR-0027)
          "post /api/mappings/{id}/compile", Set.of("400", "404", "415"),
          "post /api/structures", Set.of("400", "404", "415"));

  /** Problems are in the contract, so the ui hand-writes no payload type (#28 rulings, #21). */
  @Test
  void problemSchemasAreInTheContract() throws IOException {
    JsonNode schemas = schemas();

    assertThat(names(schemas)).contains("Problem", "RefusedProblem");
    JsonNode problem = schemas.path("Problem");
    assertThat(names(problem.path("properties"))).isEqualTo(words("detail status title type"));
    assertThat(strings(problem.path("required"))).isEqualTo(words("detail status title type"));
    JsonNode refused = schemas.path("RefusedProblem");
    assertThat(names(refused.path("properties")))
        .isEqualTo(words("detail diagnostics status title type"));
    assertThat(strings(refused.path("required")))
        .isEqualTo(words("detail diagnostics status title type"));
    JsonNode diagnostics = refused.path("properties").path("diagnostics");
    assertThat(diagnostics.path("type").textValue()).isEqualTo("array");
    assertThat(diagnostics.path("items").path("$ref").textValue())
        .isEqualTo(SCHEMAS + "DiagnosticDto");
  }

  /**
   * Every operation declares exactly its 4xx answers, each as {@code application/problem+json} of
   * {@code Problem}, or of {@code RefusedProblem} for 422 {@code model-refused}.
   */
  @Test
  void everyOperationDeclaresItsProblemResponses() throws IOException {
    Map<String, JsonNode> operations = operations(contract());
    assertThat(operations.keySet()).isEqualTo(PROBLEMS.keySet());

    for (Map.Entry<String, Set<String>> entry : PROBLEMS.entrySet()) {
      JsonNode responses = operations.get(entry.getKey()).path("responses");
      Set<String> problems = new TreeSet<>();
      for (String status : names(responses)) {
        if (status.startsWith("4")) {
          problems.add(status);
        }
      }
      assertThat(problems)
          .as("4xx of %s", entry.getKey())
          .isEqualTo(new TreeSet<>(entry.getValue()));
      for (String status : problems) {
        String what = status + " of " + entry.getKey();
        JsonNode content = responses.path(status).path("content");
        assertThat(names(content)).as("media types of %s", what).containsExactly(PROBLEM_JSON);
        assertThat(content.path(PROBLEM_JSON).path("schema").path("$ref").textValue())
            .as("schema of %s", what)
            .isEqualTo(SCHEMAS + (status.equals("422") ? "RefusedProblem" : "Problem"));
      }
    }
  }

  /**
   * Every operation declares exactly one 2xx, {@code application/json}, of the schema the #28
   * design names: the list's {@code MappingSummary[]}, the model schema for GET, PUT and create,
   * {@code TestResult} for a test run, {@code RefreshResponse} for a refresh (#99), {@code
   * CompileResponse} for a compile (#105) and {@code StructureResponse} for a structure (#28 round
   * 3).
   */
  @Test
  void everyOperationDeclaresTheSchemaOfItsSuccess() throws IOException {
    String model = "./mapping-model.schema.json";
    Map<String, String> successes =
        Map.of(
            // ADR-0026: the packed catalogue, of the contract's catalogue schema
            "get /api/catalogue", "200 ./function-catalogue.schema.json",
            "get /api/mappings", "200 array of " + SCHEMAS + "MappingSummary",
            "post /api/mappings", "201 " + model,
            "get /api/mappings/{id}", "200 " + model,
            "put /api/mappings/{id}", "200 " + model,
            "post /api/mappings/{id}/test", "200 " + SCHEMAS + "TestResult",
            "post /api/mappings/{id}/refresh", "200 " + SCHEMAS + "RefreshResponse",
            "post /api/mappings/{id}/compile", "200 " + SCHEMAS + "CompileResponse",
            "post /api/structures", "200 " + SCHEMAS + "StructureResponse");
    Map<String, JsonNode> operations = operations(contract());
    assertThat(operations.keySet()).isEqualTo(successes.keySet());

    Map<String, String> declared = new TreeMap<>();
    operations.forEach(
        (operation, node) -> {
          List<String> answers = new ArrayList<>();
          for (String status : names(node.path("responses"))) {
            if (status.startsWith("2")) {
              JsonNode content = node.path("responses").path(status).path("content");
              assertThat(names(content))
                  .as("media types of %s %s", status, operation)
                  .containsExactly("application/json");
              JsonNode schema = content.path("application/json").path("schema");
              answers.add(
                  status
                      + " "
                      + (schema.path("type").asText("").equals("array")
                          ? "array of " + schema.path("items").path("$ref").textValue()
                          : schema.path("$ref").textValue()));
            }
          }
          declared.put(operation, String.join(", ", answers));
        });
    assertThat(declared).isEqualTo(new TreeMap<>(successes));
  }

  /**
   * ADR-0026: {@code GET /api/catalogue} answers 200 {@code application/json} whose schema is the
   * contract's {@code function-catalogue.schema.json}, a file of the contract directory, and
   * declares no ETag.
   */
  @Test
  void theCatalogueAnswerRefersToTheCatalogueSchema() throws IOException {
    JsonNode get = contract().path("paths").path("/api/catalogue").path("get");
    assertThat(get.isObject()).as("get /api/catalogue in the contract").isTrue();
    JsonNode ok = get.path("responses").path("200");

    assertThat(names(ok.path("content"))).containsExactly("application/json");
    assertThat(ok.path("content").path("application/json").path("schema").path("$ref").textValue())
        .isEqualTo("./function-catalogue.schema.json");
    assertThat(contractDirectory().resolve("function-catalogue.schema.json")).isRegularFile();
    assertThat(names(ok.path("headers"))).as("headers of the catalogue").doesNotContain("ETag");
  }

  /** A model answer carries its ETag (ADR-0024, "ETag"); a create carries its Location too. */
  @Test
  void modelAnswersDeclareTheirEtagAndCreateItsLocation() throws IOException {
    Map<String, JsonNode> operations = operations(contract());
    Map<String, String> answers =
        Map.of(
            "get /api/mappings/{id}", "200",
            "put /api/mappings/{id}", "200",
            "post /api/mappings", "201");

    answers.forEach(
        (operation, status) ->
            assertThat(
                    operations
                        .get(operation)
                        .path("responses")
                        .path(status)
                        .path("headers")
                        .path("ETag")
                        .path("schema")
                        .path("type")
                        .textValue())
                .as("ETag of %s %s", status, operation)
                .isEqualTo("string"));
    assertThat(
            operations
                .get("post /api/mappings")
                .path("responses")
                .path("201")
                .path("headers")
                .path("Location")
                .path("schema")
                .path("type")
                .textValue())
        .as("Location of 201 post /api/mappings")
        .isEqualTo("string");
  }

  /**
   * No 400 in the contract lacks our {@code Problem}: smallrye's generated "Bad Request" answer is
   * switched off ({@code auto-add-bad-request-response=false}). The witness: the seven operations
   * with a parameter or a body each declare a 400, so the check is never over an empty set.
   */
  @Test
  void noAutoAddedBadRequestWithoutTheProblemSchema() throws IOException {
    int badRequests = 0;
    for (Map.Entry<String, JsonNode> operation : operations(contract()).entrySet()) {
      JsonNode badRequest = operation.getValue().path("responses").path("400");
      if (!badRequest.isMissingNode()) {
        badRequests++;
        assertThat(names(badRequest.path("content")))
            .as("400 of %s", operation.getKey())
            .containsExactly(PROBLEM_JSON);
        assertThat(
                badRequest
                    .path("content")
                    .path(PROBLEM_JSON)
                    .path("schema")
                    .path("$ref")
                    .textValue())
            .as("400 of %s", operation.getKey())
            .isEqualTo(SCHEMAS + "Problem");
      }
    }
    assertThat(badRequests).as("operations that declare a 400").isEqualTo(7);
  }

  // --- helpers ---

  /** Every operation of the contract as {@code "<method> <path>"}. */
  private static Map<String, JsonNode> operations(JsonNode contract) {
    Map<String, JsonNode> operations = new TreeMap<>();
    contract
        .path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        method -> {
                          if (!method.getKey().equals("parameters")) {
                            operations.put(
                                method.getKey() + " " + path.getKey(), method.getValue());
                          }
                        }));
    return operations;
  }

  private static Path contractDirectory() {
    return Fixtures.repositoryRoot().resolve("contract");
  }

  private static JsonNode contract() throws IOException {
    return Wire.read(Files.readAllBytes(contractDirectory().resolve("openapi.json")));
  }

  private static JsonNode schemas() throws IOException {
    return contract().path("components").path("schemas");
  }

  private static Set<String> oneOf(JsonNode schema) {
    Set<String> refs = new TreeSet<>();
    for (JsonNode branch : schema.path("oneOf")) {
      refs.add(branch.path("$ref").textValue());
    }
    return refs;
  }

  private static Set<String> names(JsonNode object) {
    Set<String> names = new TreeSet<>();
    object.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> strings = new TreeSet<>();
    for (JsonNode s : array) {
      strings.add(s.textValue());
    }
    return strings;
  }

  private static Set<String> words(String text) {
    return new TreeSet<>(Arrays.asList(text.trim().split(" +")));
  }

  private static void collectRefs(JsonNode node, List<String> refs) {
    if (node.isObject()) {
      node.properties()
          .forEach(
              e -> {
                if (e.getKey().equals("$ref") && e.getValue().isTextual()) {
                  refs.add(e.getValue().textValue());
                } else {
                  collectRefs(e.getValue(), refs);
                }
              });
    } else if (node.isArray()) {
      node.forEach(child -> collectRefs(child, refs));
    }
  }

  /** The service's compiled classes by simple name: the DTOs the schemas are named after. */
  private static Map<String, Class<?>> serviceClasses() throws IOException, ClassNotFoundException {
    Path classes = Path.of("target", "classes");
    Map<String, Class<?>> found = new HashMap<>();
    List<String> names;
    try (Stream<Path> walk = Files.walk(classes)) {
      names =
          walk.map(p -> classes.relativize(p).toString())
              .filter(p -> p.endsWith(".class") && p.startsWith("io/flottila/mapping/service"))
              .map(p -> p.substring(0, p.length() - ".class".length()).replace('/', '.'))
              .toList();
    }
    ClassLoader loader = OpenApiContractTest.class.getClassLoader();
    for (String name : names) {
      Class<?> type = Class.forName(name, false, loader);
      found.put(type.getSimpleName(), type);
    }
    return found;
  }
}
