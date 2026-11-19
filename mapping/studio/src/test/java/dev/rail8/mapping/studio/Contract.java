package dev.rail8.mapping.studio;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.erosb.jsonsKema.IJsonObject;
import com.github.erosb.jsonsKema.IJsonValue;
import com.github.erosb.jsonsKema.JsonObject;
import com.github.erosb.jsonsKema.JsonParser;
import com.github.erosb.jsonsKema.Schema;
import com.github.erosb.jsonsKema.SchemaLoader;
import com.github.erosb.jsonsKema.ValidationFailure;
import com.github.erosb.jsonsKema.Validator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The browser's contract, {@code contract/openapi.json}, as a strict check of the studio's answers.
 * An answer meets it when the operation's contract lists its status, with {@code application/json}
 * below 400 and {@code application/problem+json} from 400, and its JSON is an instance of that
 * status's schema: an object has every required member and no member its schema does not name,
 * unless {@code additionalProperties} gives the schema of the others; a union's member is the one
 * its discriminator picks; an {@code int32} or {@code int64} fits; a {@code uri} is absolute; a
 * mapping model and the catalogue meet their JSON Schemas, checked with json-sKema, the catalogue
 * also entry by entry, since json-sKema checks only the first member of a {@code patternProperties}
 * map. A schema keyword the check does not know fails it, so the contract cannot grow past what is
 * checked.
 *
 * <p>Each check notes what it reached: the statuses of each operation, the members of each named
 * schema, the kinds of each union and the problem types. A test reads them to see whether a set of
 * answers covers the contract.
 */
final class Contract {

  static final String SCHEMAS = "#/components/schemas/";

  /** The keywords the check knows. */
  static final Set<String> KEYWORDS =
      Set.of(
          "$ref",
          "additionalProperties",
          "const",
          "discriminator",
          "enum",
          "format",
          "items",
          "oneOf",
          "properties",
          "required",
          "type");

  /** The operation of the contract each studio operation answers for, as method and path. */
  static final Map<String, String> OPERATIONS =
      Map.of(
          "catalogue", "get /api/catalogue",
          "canonical stored", "get /api/mappings/{id}",
          "canonical sent", "put /api/mappings/{id}",
          "create", "post /api/mappings",
          "structure", "post /api/structures",
          "compile", "post /api/mappings/{id}/compile",
          "test", "post /api/mappings/{id}/test",
          "refresh", "post /api/mappings/{id}/refresh");

  private static final String CATALOGUE = "./function-catalogue.schema.json";

  private static final Contract SHARED = load();

  private final JsonNode openapi;
  private final JsonNode schemas;
  private final Map<String, Schema> external = new TreeMap<>();

  /** {@code <operation> <status>} of each answer checked. */
  final Set<String> answered = new TreeSet<>();

  /** The members each named schema had in the instances checked. */
  final Map<String, Set<String>> members = new TreeMap<>();

  /** {@code <union> <kind>} of each union member checked. */
  final Set<String> kinds = new TreeSet<>();

  /** The problem types of the answers checked. */
  final Set<String> problems = new TreeSet<>();

  private Contract(JsonNode openapi) {
    this.openapi = openapi;
    this.schemas = openapi.path("components").path("schemas");
  }

  /** The contract as the tests share it; what it reached is not read. */
  static Contract shared() {
    return SHARED;
  }

  /** The contract, read from {@code contract/}, which has reached nothing yet. */
  static Contract load() {
    Contract contract = new Contract(Wire.read(read("openapi.json")));
    for (String file : List.of("./mapping-model.schema.json", CATALOGUE)) {
      contract.external.put(
          file, new SchemaLoader(new JsonParser(new String(read(file), UTF_8)).parse()).load());
    }
    return contract;
  }

  private static byte[] read(String file) {
    Path contract = Fixtures.repositoryRoot().resolve("contract");
    try {
      return Files.readAllBytes(contract.resolve(file).normalize());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The whole contract. */
  JsonNode openapi() {
    return openapi;
  }

  /** The named schemas. */
  JsonNode schemas() {
    return schemas;
  }

  /**
   * How the answer of {@code status} and {@code json} to {@code operation}, a key of {@link
   * #OPERATIONS}, breaks the contract; empty when it meets it.
   */
  synchronized List<String> violations(String operation, int status, String json) {
    List<String> violations = new ArrayList<>();
    String[] route = OPERATIONS.get(operation).split(" ");
    JsonNode content =
        openapi
            .path("paths")
            .path(route[1])
            .path(route[0])
            .path("responses")
            .path(Integer.toString(status))
            .path("content");
    String type = status < 400 ? "application/json" : "application/problem+json";
    if (!Wire.names(content).equals(List.of(type))) {
      violations.add(
          "the contract of " + operation + " has no " + status + " of " + type + ": " + content);
      return violations;
    }
    answered.add(operation + " " + status);
    JsonNode value = Wire.read(json.getBytes(UTF_8));
    if (status >= 400 && value.path("type").isTextual()) {
      problems.add(value.path("type").textValue());
    }
    check(value, content.path(type).path("schema"), "$", violations);
    return violations;
  }

  /** How {@code json} breaks the named schema {@code name}; empty when it meets it. */
  synchronized List<String> violations(String name, String json) {
    List<String> violations = new ArrayList<>();
    check(Wire.read(json.getBytes(UTF_8)), ref(SCHEMAS + name), "$", violations);
    return violations;
  }

  private static JsonNode ref(String ref) {
    return Wire.read(("{\"$ref\": " + Wire.quoted(ref) + "}").getBytes(UTF_8));
  }

  private void check(JsonNode value, JsonNode schema, String at, List<String> violations) {
    check(value, schema, null, at, violations);
  }

  /** Checks {@code value} against {@code schema}, the schema {@code name} or one inside it. */
  private void check(
      JsonNode value, JsonNode schema, String name, String at, List<String> violations) {
    for (String keyword : Wire.names(schema)) {
      if (!KEYWORDS.contains(keyword)) {
        violations.add(at + ": the check does not know the keyword " + keyword);
      }
    }
    if (schema.has("$ref")) {
      if (schema.size() != 1) {
        violations.add(at + ": a $ref with other keywords: " + schema);
      }
      checkRef(value, schema.path("$ref").textValue(), at, violations);
      return;
    }
    if (schema.has("type") && !typed(value, schema.path("type"))) {
      violations.add(at + ": " + value + " is no " + schema.path("type"));
      return;
    }
    if (schema.has("const") && !schema.path("const").equals(value)) {
      violations.add(at + ": " + value + " is not " + schema.path("const"));
    }
    if (schema.has("enum") && !contains(schema.path("enum"), value)) {
      violations.add(at + ": " + value + " is none of " + schema.path("enum"));
    }
    if (schema.has("format")) {
      checkFormat(value, schema.path("format").textValue(), at, violations);
    }
    if (schema.has("oneOf")) {
      checkUnion(value, schema, name, at, violations);
      return;
    }
    if (value.isObject()) {
      checkObject(value, schema, name, at, violations);
    }
    if (value.isArray() && schema.has("items")) {
      int i = 0;
      for (JsonNode item : value) {
        check(item, schema.path("items"), at + "[" + i++ + "]", violations);
      }
    }
  }

  private void checkRef(JsonNode value, String ref, String at, List<String> violations) {
    if (ref.startsWith(SCHEMAS)) {
      String name = ref.substring(SCHEMAS.length());
      if (!schemas.has(name)) {
        violations.add(at + ": no schema " + ref);
        return;
      }
      check(value, schemas.path(name), name, at, violations);
      return;
    }
    Schema schema = external.get(ref);
    if (schema == null) {
      violations.add(at + ": no schema " + ref);
      return;
    }
    IJsonValue instance = new JsonParser(value.toString()).parse();
    ValidationFailure failure = Validator.forSchema(schema).validate(instance);
    if (failure != null) {
      violations.add(at + ": not an instance of " + ref + ": " + failure);
    }
    if (ref.equals(CATALOGUE) && instance instanceof IJsonObject catalogue) {
      catalogue
          .getProperties()
          .forEach(
              (entry, function) -> {
                ValidationFailure refused =
                    Validator.forSchema(schema).validate(new JsonObject(Map.of(entry, function)));
                if (refused != null) {
                  violations.add(at + "." + entry.getValue() + ": " + refused);
                }
              });
    }
  }

  private static boolean typed(JsonNode value, JsonNode type) {
    if (type.isArray()) {
      for (JsonNode one : type) {
        if (typed(value, one)) {
          return true;
        }
      }
      return false;
    }
    return switch (type.textValue()) {
      case "string" -> value.isTextual();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "boolean" -> value.isBoolean();
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "null" -> value.isNull();
      default -> throw new IllegalStateException("no JSON type " + type);
    };
  }

  private static boolean contains(JsonNode values, JsonNode value) {
    for (JsonNode one : values) {
      if (one.equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static void checkFormat(
      JsonNode value, String format, String at, List<String> violations) {
    boolean meets =
        switch (format) {
          case "int32" -> value.isIntegralNumber() && value.canConvertToInt();
          case "int64" -> value.isIntegralNumber() && value.canConvertToLong();
          case "uri" -> value.isTextual() && absolute(value.textValue());
          default -> {
            violations.add(at + ": the check does not know the format " + format);
            yield true;
          }
        };
    if (!meets) {
      violations.add(at + ": " + value + " is no " + format);
    }
  }

  private static boolean absolute(String uri) {
    try {
      return new URI(uri).isAbsolute();
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /** A union: the member its discriminator picks, which must be one of its {@code oneOf}. */
  private void checkUnion(
      JsonNode value, JsonNode schema, String name, String at, List<String> violations) {
    String property = schema.path("discriminator").path("propertyName").textValue();
    if (property == null) {
      violations.add(at + ": a oneOf without a discriminator in " + name);
      return;
    }
    String kind = value.path(property).textValue();
    String member =
        schema.path("discriminator").path("mapping").path(kind == null ? "" : kind).textValue();
    Set<String> oneOf = new HashSet<>();
    schema.path("oneOf").forEach(m -> oneOf.add(m.path("$ref").textValue()));
    if (member == null || !oneOf.contains(member)) {
      violations.add(at + ": " + name + " has no member of " + property + " " + kind);
      return;
    }
    kinds.add(name + " " + kind);
    checkRef(value, member, at, violations);
  }

  private void checkObject(
      JsonNode value, JsonNode schema, String name, String at, List<String> violations) {
    JsonNode properties = schema.path("properties");
    for (JsonNode required : schema.path("required")) {
      if (!value.has(required.textValue())) {
        violations.add(at + ": " + required.textValue() + " is missing");
      }
    }
    JsonNode others = schema.path("additionalProperties");
    for (String member : Wire.names(value)) {
      String inner = at + "." + member;
      if (properties.has(member)) {
        if (name != null) {
          members.computeIfAbsent(name, n -> new TreeSet<>()).add(member);
        }
        check(value.path(member), properties.path(member), inner, violations);
      } else if (others.isObject()) {
        check(value.path(member), others, inner, violations);
      } else {
        violations.add(at + ": " + member + " is no member of " + (name == null ? "it" : name));
      }
    }
  }
}
