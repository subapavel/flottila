package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.INPUT;
import static dev.rail8.mapping.studio.Fixtures.NS;
import static dev.rail8.mapping.studio.Fixtures.ORDER;
import static dev.rail8.mapping.studio.Fixtures.bytes;
import static dev.rail8.mapping.studio.Fixtures.corpusMapping;
import static dev.rail8.mapping.studio.Fixtures.notesThrough;
import static dev.rail8.mapping.studio.Fixtures.workingCopy;
import static dev.rail8.mapping.studio.Wire.createRequest;
import static dev.rail8.mapping.studio.Wire.structureRequest;
import static dev.rail8.mapping.studio.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The studio's answers are the browser's contract, {@code contract/openapi.json}, which the mapping
 * service's build writes. Its types are checked against the contract's schemas, and a set of
 * answers reaches every status the studio gives, every kind of every union, every problem type and
 * every member of every schema an answer uses.
 */
class StudioContractTest {

  /** The listing of mappings is the node's: the studio lists nothing. */
  private static final String LISTING = "get /api/mappings";

  /** The schema of a listed mapping, the listing's. */
  private static final String SUMMARY = "MappingSummary";

  /** The schemas of the browser's request bodies, which no answer uses. */
  private static final Set<String> REQUESTS =
      Set.of("CreateMappingRequest", "StructureChoice", "StructureRequest", "TestRequest");

  private static final String ORDER_XSD = "../structures/skeleton/order.xsd";

  private final Contract contract = Contract.load();

  @Test
  void everyOperationOfTheContractIsAStudioOperationOrTheListing() {
    Set<String> operations = new TreeSet<>();
    contract
        .openapi()
        .path("paths")
        .properties()
        .forEach(
            path ->
                Wire.names(path.getValue())
                    .forEach(method -> operations.add(method + " " + path.getKey())));
    Set<String> expected = new TreeSet<>(Contract.OPERATIONS.values());
    expected.add(LISTING);

    assertThat(operations).isEqualTo(expected);
    assertThat(Studio.OPERATIONS)
        .containsExactlyInAnyOrderElementsOf(
            Contract.OPERATIONS.keySet().stream().map(op -> op.split(" ")[0]).distinct().toList());
  }

  @Test
  void everyObjectSchemaIsAStudioTypeOfItsName() throws Exception {
    Map<String, Class<?>> types = studioTypes();
    List<String> missing = new ArrayList<>();
    for (String name : Wire.names(contract.schemas())) {
      JsonNode schema = contract.schemas().path(name);
      boolean object = "object".equals(schema.path("type").textValue()) || schema.has("oneOf");
      if (object && !name.equals(SUMMARY) && !types.containsKey(name)) {
        missing.add(name);
      }
    }

    assertThat(missing).as("object schemas without a studio type").isEmpty();
  }

  /**
   * A union's {@code oneOf} is its sealed type's permitted records, picked by their {@code KIND}.
   */
  @Test
  void everyUnionIsASealedTypeOfExactlyItsKinds() throws Exception {
    Map<String, Class<?>> types = studioTypes();
    int unions = 0;
    for (String name : Wire.names(contract.schemas())) {
      JsonNode schema = contract.schemas().path(name);
      if (!schema.has("oneOf")) {
        continue;
      }
      unions++;
      Class<?> type = types.get(name);
      assertThat(type.isSealed()).as("%s is sealed", name).isTrue();
      assertThat(schema.path("discriminator").path("propertyName").textValue())
          .as(name)
          .isEqualTo("kind");
      Map<String, String> byKind = new TreeMap<>();
      for (Class<?> member : type.getPermittedSubclasses()) {
        String kind = (String) member.getDeclaredField("KIND").get(null);
        byKind.put(kind, Contract.SCHEMAS + member.getSimpleName());
        assertThat(
                contract
                    .schemas()
                    .path(member.getSimpleName())
                    .path("properties")
                    .path("kind")
                    .path("const")
                    .textValue())
            .as("%s.kind", member.getSimpleName())
            .isEqualTo(kind);
      }
      Map<String, String> mapping = new TreeMap<>();
      schema
          .path("discriminator")
          .path("mapping")
          .properties()
          .forEach(e -> mapping.put(e.getKey(), e.getValue().textValue()));
      Set<String> oneOf = new TreeSet<>();
      schema.path("oneOf").forEach(m -> oneOf.add(m.path("$ref").textValue()));

      assertThat(mapping).as("the discriminator of %s", name).isEqualTo(byKind);
      assertThat(oneOf).as("the oneOf of %s", name).isEqualTo(new TreeSet<>(byKind.values()));
    }
    assertThat(unions).as("the unions").isEqualTo(4);
  }

  /**
   * A record's members are its schema's, and the required ones are those it never leaves out: an
   * answer's member marked to be left out when null, or a request's member that may be missing, is
   * the only optional kind.
   */
  @Test
  void everyRecordHasTheMembersAndTheRequiredListOfItsSchema() throws Exception {
    int records = 0;
    for (Class<?> type : studioTypes().values()) {
      JsonNode schema = contract.schemas().path(type.getSimpleName());
      if (!type.isRecord() || schema.isMissingNode()) {
        continue;
      }
      records++;
      Set<String> members = new TreeSet<>();
      Set<String> required = new TreeSet<>();
      for (RecordComponent c : type.getRecordComponents()) {
        Method accessor = c.getAccessor();
        JsonProperty named = accessor.getAnnotation(JsonProperty.class);
        String member = named == null || named.value().isEmpty() ? c.getName() : named.value();
        members.add(member);
        JsonInclude include = accessor.getAnnotation(JsonInclude.class);
        JsonSetter setter = accessor.getAnnotation(JsonSetter.class);
        boolean leftOut = include != null && include.value() == JsonInclude.Include.NON_NULL;
        boolean mayBeMissing = setter != null && setter.nulls() == Nulls.SET;
        if (!leftOut && !mayBeMissing) {
          required.add(member);
        }
      }

      assertThat(new TreeSet<>(Wire.names(schema.path("properties"))))
          .as("the members of %s", type.getSimpleName())
          .isEqualTo(members);
      Set<String> listed = new TreeSet<>();
      schema.path("required").forEach(r -> listed.add(r.textValue()));
      assertThat(listed).as("the required members of %s", type.getSimpleName()).isEqualTo(required);
    }
    assertThat(records).as("the studio's records among the schemas").isEqualTo(26);
  }

  /** A request's required member is marked so, and Jackson names it missing. */
  @Test
  void everyRequiredMemberOfARequestIsMarkedRequired() throws Exception {
    for (String name : REQUESTS) {
      Class<?> type = studioTypes().get(name);
      for (RecordComponent c : type.getRecordComponents()) {
        JsonProperty named = c.getAccessor().getAnnotation(JsonProperty.class);
        JsonSetter setter = c.getAccessor().getAnnotation(JsonSetter.class);
        boolean mayBeMissing = setter != null && setter.nulls() == Nulls.SET;
        assertThat(mayBeMissing || named != null && named.required())
            .as("%s.%s is required or may be missing", name, c.getName())
            .isTrue();
      }
    }
  }

  /**
   * The answers below reach every status each operation gives, every kind of every union, every
   * problem type the browser gets and every member of every schema an answer uses. No engine
   * diagnostic is at a field path yet, so that location is written directly.
   */
  @Test
  void theAnswersCoverTheContract() {
    Calls calls = Calls.studio().checkedBy(contract);
    Workspace mappings = Workspace.mappings();
    Workspace skeleton = Workspace.corpus("structures/skeleton");

    calls.catalogue();

    calls.stored(corpusMapping());
    calls.stored(bytes("{"));
    calls.sent(corpusMapping());
    calls.sent(bytes("{"));
    calls.sent(bytes("{\"formatVersion\": 1, \"extra\": 1}"));

    calls.post("create", createRequest("m", ORDER_XSD, ORDER), mappings);
    calls.post("create", "{\"id\": \"m\", \"description\": \"d\"}", mappings);
    calls.post("create", createRequest("m", "missing.xsd", ORDER), mappings);
    calls.post("create", "{}", mappings);

    calls.post("structure", structureRequest("", "order.xsd", ORDER), skeleton);
    calls.post(
        "structure",
        structureRequest("", "same-step.xsd", "Doc"),
        Workspace.corpus("structures/refused"));
    calls.post(
        "structure",
        structureRequest("", "malformed.xsd", ORDER),
        Workspace.corpus("structures/skeleton/test-fixtures"));
    calls.post(
        "structure",
        structureRequest("", "evil.xsd", ORDER),
        Workspace.empty().escaping("evil.xsd"));

    calls.post(
        "compile",
        workingCopy("\"a1\"")
            .replace(
                "\"boxes\": {",
                "\"boxes\": {\"f9\": {\"kind\": \"function\", \"fn\": \"trim\", \"inputs\":"
                    + " [\"a1\"]}, "),
        mappings);
    calls.post("compile", "{", mappings);

    calls.post("test", testRequest(createIfNotes(), notes("1", "false", "true")), mappings);
    calls.post("test", testRequest(constantNote(), INPUT), mappings);
    calls.post(
        "test",
        testRequest(workingCopy("\"a1\""), "<src:Order xmlns:src=\"" + NS + "\"/>"),
        mappings);
    calls
        .timeout(Duration.ZERO)
        .post("test", testRequest(workingCopy("\"a1\""), manyNotes()), mappings);
    calls.post("test", testRequest(workingCopy("null"), INPUT), mappings);
    calls.post("test", testRequest(workingCopy("\"a1\""), "<src:Order"), mappings);
    calls.post("test", "{\"model\": {}}", mappings);

    calls.post("refresh", corpusMapping(), mappings);
    calls.post("refresh", "{\"formatVersion\": 2}", mappings);
    calls.post("refresh", "{", mappings);

    assertThat(
            contract.violations(
                "DiagnosticDto",
                Json.write(
                    new DiagnosticDto(
                        "warning",
                        "unreachable-box",
                        "a message",
                        new FieldPathLocation("target", "/" + ORDER + "/OrderId")))))
        .isEmpty();

    assertThat(contract.answered)
        .containsExactlyInAnyOrder(
            "catalogue 200",
            "canonical stored 200",
            "canonical stored 422",
            "canonical sent 200",
            "canonical sent 400",
            "canonical sent 422",
            "create 201",
            "create 400",
            "create 422",
            "structure 200",
            "structure 400",
            "compile 200",
            "compile 400",
            "test 200",
            "test 400",
            "refresh 200",
            "refresh 400",
            "refresh 422");
    assertThat(contract.kinds).isEqualTo(unionKinds());
    assertThat(contract.problems)
        .containsExactlyInAnyOrder(
            Wire.PROBLEM_TYPE + "invalid-workspace-path",
            Wire.PROBLEM_TYPE + "malformed-json",
            Wire.PROBLEM_TYPE + "invalid-request",
            Wire.PROBLEM_TYPE + "malformed-input",
            Wire.PROBLEM_TYPE + "model-refused");
    Map<String, Set<String>> everyMember = new TreeMap<>();
    for (String name : Wire.names(contract.schemas())) {
      JsonNode properties = contract.schemas().path(name).path("properties");
      if (!properties.isMissingNode() && !REQUESTS.contains(name) && !name.equals(SUMMARY)) {
        everyMember.put(name, new TreeSet<>(Wire.names(properties)));
      }
    }
    assertThat(contract.members).isEqualTo(everyMember);
  }

  /** The check refuses what the contract does not allow, so a passing answer means something. */
  @Test
  void theCheckRefusesAnAnswerTheContractDoesNotAllow() {
    String problem =
        "{\"type\": \"urn:rail8:mapping:problem:malformed-json\", \"title\": \"t\", \"status\": 400,"
            + " \"detail\": \"d\"";

    assertThat(contract.violations("compile", 400, problem + "}")).isEmpty();
    assertThat(contract.violations("compile", 400, problem + ", \"extra\": 1}"))
        .containsExactly("$: extra is no member of Problem");
    assertThat(contract.violations("compile", 400, "{\"title\": \"t\", \"status\": 400}"))
        .containsExactly("$: type is missing", "$: detail is missing");
    assertThat(contract.violations("compile", 422, problem + "}"))
        .singleElement()
        .asString()
        .startsWith("the contract of compile has no 422");
    assertThat(contract.violations("compile", 400, problem.replace("400,", "\"400\",") + "}"))
        .containsExactly("$.status: \"400\" is no \"integer\"");
    assertThat(contract.violations("test", 200, "{\"kind\": \"other\"}"))
        .containsExactly("$: TestResult has no member of kind other");
    assertThat(
            contract.violations(
                "test", 200, "{\"kind\": \"refused\", \"diagnostics\": [], \"trace\": {}}"))
        .containsExactly("$: trace is no member of TestRefused");
    assertThat(contract.violations("canonical sent", 200, "{\"formatVersion\": 7}"))
        .singleElement()
        .asString()
        .startsWith("$: not an instance of ./mapping-model.schema.json");
  }

  /** The studio's types: its classes, nested ones too, by simple name. */
  private static Map<String, Class<?>> studioTypes() throws IOException, URISyntaxException {
    Path classes =
        Path.of(Studio.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path dir = classes.resolve(Studio.class.getPackageName().replace('.', '/'));
    Map<String, Class<?>> types = new TreeMap<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (name.endsWith(".class")) {
          String binary = Studio.class.getPackageName() + "." + name.replace(".class", "");
          try {
            Class<?> type = Class.forName(binary);
            types.put(type.getSimpleName(), type);
          } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
          }
        }
      }
    }
    return types;
  }

  /** Every {@code <union> <kind>} of the contract. */
  private Set<String> unionKinds() {
    Set<String> kinds = new TreeSet<>();
    for (String name : Wire.names(contract.schemas())) {
      contract
          .schemas()
          .path(name)
          .path("discriminator")
          .path("mapping")
          .fieldNames()
          .forEachRemaining(kind -> kinds.add(name + " " + kind));
    }
    return kinds;
  }

  /** {@code Note <- c1}, {@code c1} createIf over the source Notes. */
  private static String createIfNotes() {
    return notesThrough(
        "c1",
        """
        "c1": {"kind": "function", "fn": "createIf", "inputs": ["a2"]},
        "a2": {"kind": "source", "field": "NOTE"}""");
  }

  /** {@code Note <- c1}, the constant {@code x}. */
  private static String constantNote() {
    return notesThrough(
        "c1",
        """
        "c1": {"kind": "function", "fn": "constant", "params": {"value": "x"}}""");
  }

  /** OrderId 4711 and the Notes {@code notes}. */
  private static String notes(String... notes) {
    StringBuilder input =
        new StringBuilder("<src:Order xmlns:src=\"" + NS + "\"><OrderId>4711</OrderId>");
    for (String note : notes) {
      input.append("<Note>").append(note).append("</Note>");
    }
    return input.append("</src:Order>").toString();
  }

  /** 200,000 Notes: their parse alone takes far longer than no time at all. */
  private static String manyNotes() {
    return "<src:Order xmlns:src=\""
        + NS
        + "\"><OrderId>4711</OrderId>"
        + "<Note>n</Note>".repeat(200_000)
        + "</src:Order>";
  }
}
