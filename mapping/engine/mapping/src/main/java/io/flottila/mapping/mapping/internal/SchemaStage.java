package io.flottila.mapping.mapping.internal;

import com.github.erosb.jsonsKema.IJsonNumber;
import com.github.erosb.jsonsKema.IJsonObject;
import com.github.erosb.jsonsKema.IJsonString;
import com.github.erosb.jsonsKema.IJsonValue;
import com.github.erosb.jsonsKema.JsonBoolean;
import com.github.erosb.jsonsKema.JsonObject;
import com.github.erosb.jsonsKema.JsonPointer;
import com.github.erosb.jsonsKema.JsonString;
import com.github.erosb.jsonsKema.Keyword;
import com.github.erosb.jsonsKema.OneOfValidationFailure;
import com.github.erosb.jsonsKema.Schema;
import com.github.erosb.jsonsKema.SchemaLoader;
import com.github.erosb.jsonsKema.ValidationFailure;
import com.github.erosb.jsonsKema.Validator;
import io.flottila.mapping.mapping.AtPointer;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.MalformedJsonException;
import io.flottila.mapping.mapping.MappingDiagnostics;
import io.flottila.mapping.mapping.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 1 of loading (ADR-0011): json-sKema validates the document against the contract's
 * mapping-model schema, packed into this module's jar. Each failure becomes a diagnostic at the
 * JSON pointer of the instance, its code by the failing keyword ("Stage 1 and 2 codes").
 */
public final class SchemaStage {

  /** The format this engine reads and writes. */
  public static final int FORMAT_VERSION = 1;

  /** Where the contract holds the {@code oneOf} of the box kinds. */
  private static final JsonPointer BOX_KINDS = new JsonPointer("$defs", "box", "oneOf");

  /** Where the contract holds the {@code oneOf} of a parameter value: a string or a table. */
  private static final JsonPointer PARAM_VALUE_KINDS =
      new JsonPointer("$defs", "paramValue", "oneOf");

  private static final Schemas SCHEMAS = Schemas.load();

  private SchemaStage() {}

  /**
   * The stage-1 diagnostics of {@code document}, none when it is valid. A newer {@code
   * formatVersion} is refused with that one diagnostic and nothing else is validated.
   */
  public static List<Diagnostic> check(IJsonValue document) {
    if (isNewer(document)) {
      return List.of(
          new Diagnostic(
              Severity.ERROR,
              MappingDiagnostics.FORMAT_VERSION_UNSUPPORTED,
              new AtPointer("/formatVersion"),
              "formatVersion is newer than " + FORMAT_VERSION + ", the newest this engine reads"));
    }
    // json-sKema 0.32.0 keeps only the first member's result of a patternProperties map (it takes
    // firstOrNull of the members' results, observed), so a later member's failure is lost (F-8).
    // The schemas see every map value as true, and each member is validated on its own, once.
    Findings found = new Findings();
    found.add(SCHEMAS.document(), document);
    for (IJsonValue mapping : members(document, "targetFieldMappings")) {
      found.add(SCHEMAS.targetFieldMapping(), mapping);
      for (IJsonValue position : members(member(mapping, "layout"), "boxes")) {
        found.add(SCHEMAS.boxPosition(), position);
      }
      for (IJsonValue box : members(mapping, "boxes")) {
        found.add(SCHEMAS.box(box), box);
        for (IJsonValue param : members(box, "params")) {
          found.add(SCHEMAS.paramValue(), param);
        }
      }
    }
    for (IJsonValue udf : members(document, "udfs")) {
      found.add(SCHEMAS.udfDeclaration(), udf);
      for (IJsonValue param : members(udf, "params")) {
        found.add(SCHEMAS.udfParamType(), param);
      }
    }
    return found.diagnostics();
  }

  private static boolean isNewer(IJsonValue document) {
    return document instanceof IJsonObject root
        && root.get("formatVersion") instanceof IJsonNumber version
        && ((BigDecimal) version.getValue()).compareTo(BigDecimal.valueOf(FORMAT_VERSION)) > 0;
  }

  /** The values of the object {@code name} of {@code value}; none when there is no such object. */
  private static Collection<IJsonValue> members(IJsonValue value, String name) {
    return member(value, name) instanceof IJsonObject map
        ? map.getProperties().values()
        : List.of();
  }

  /** The member {@code name} of {@code value}, or {@code null} when it has none or is no object. */
  private static IJsonValue member(IJsonValue value, String name) {
    return value instanceof IJsonObject object ? object.get(name) : null;
  }

  /** The RFC 6901 text of a pointer. */
  private static String text(JsonPointer pointer) {
    StringBuilder out = new StringBuilder();
    for (String segment : pointer.getSegments()) {
      out.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
    }
    return out.toString();
  }

  private static JsonPointer pointerOf(ValidationFailure failure) {
    return failure.getInstance().getLocation().getPointer();
  }

  /** The diagnostics of one document, each code at each pointer once. */
  private static final class Findings {

    private record Key(String pointer, String code) {}

    private final Map<Key, Diagnostic> found = new LinkedHashMap<>();

    /**
     * One validator per schema, reused value after value within this document. A validator keeps
     * the instance it is validating in a field, so it is never shared between documents.
     */
    private final Map<Schema, Validator> validators = new IdentityHashMap<>();

    void add(Schema schema, IJsonValue value) {
      ValidationFailure failure =
          validators.computeIfAbsent(schema, Validator::forSchema).validate(value);
      if (failure != null) {
        translate(failure);
      }
    }

    List<Diagnostic> diagnostics() {
      return List.copyOf(found.values());
    }

    private void translate(ValidationFailure failure) {
      if (failure instanceof OneOfValidationFailure oneOf) {
        JsonPointer schema = oneOf.getSchema().getLocation().getPointer();
        if (BOX_KINDS.equals(schema)) {
          // only a box whose kind names no branch meets the oneOf (Schemas.box)
          put(
              MappingDiagnostics.UNKNOWN_BOX_KIND,
              text(pointerOf(oneOf).plus("kind")),
              "kind is none of source, function, udf and unresolved");
        } else if (PARAM_VALUE_KINDS.equals(schema)) {
          paramValue(oneOf);
        } else {
          leaf(oneOf); // the root selector: oneOf has no code of its own
        }
      } else if (failure.getCauses().isEmpty()) {
        leaf(failure);
      } else {
        failure.getCauses().forEach(this::translate);
      }
    }

    /**
     * A parameter value that is neither a string nor a table. An object fails the string branch at
     * the value and the table branch only at its rows, so the table branch's failures are reported;
     * any other value fails both branches at the value, and is the wrong type.
     */
    private void paramValue(OneOfValidationFailure oneOf) {
      JsonPointer value = pointerOf(oneOf);
      List<ValidationFailure> table =
          oneOf.getCauses().stream()
              .filter(
                  branch -> branch.flatten().stream().noneMatch(f -> value.equals(pointerOf(f))))
              .toList();
      if (table.isEmpty()) {
        put(MappingDiagnostics.WRONG_TYPE, text(value), "a parameter value is a string or a table");
      } else {
        table.forEach(this::translate);
      }
    }

    private void leaf(ValidationFailure failure) {
      Keyword keyword = failure.getKeyword();
      String code =
          switch (keyword) {
            case FALSE -> MappingDiagnostics.UNKNOWN_MEMBER; // additionalProperties: false
            case REQUIRED -> MappingDiagnostics.MISSING_MEMBER;
            case TYPE -> MappingDiagnostics.WRONG_TYPE;
            case PATTERN -> MappingDiagnostics.PATTERN_MISMATCH;
            // ADR-0029 adds minimum, maximum and maxLength
            case ENUM,
                CONST,
                MIN_ITEMS,
                MIN_PROPERTIES,
                UNIQUE_ITEMS,
                MINIMUM,
                MAXIMUM,
                MAX_LENGTH ->
                MappingDiagnostics.INVALID_VALUE;
            default -> MappingDiagnostics.SCHEMA_VIOLATION; // fails closed on any other keyword
          };
      String message =
          keyword == Keyword.FALSE ? "not a member here in formatVersion 1" : failure.getMessage();
      put(code, text(pointerOf(failure)), message);
    }

    private void put(String code, String pointer, String message) {
      found.putIfAbsent(
          new Key(pointer, code),
          new Diagnostic(Severity.ERROR, code, new AtPointer(pointer), message));
    }
  }

  /**
   * The contract's schema and the schemas of the map values, all with every {@code
   * patternProperties} value opened to {@code true}: a map checks its keys, and its values are
   * validated one by one.
   *
   * @param boxKinds the branch of the box {@code oneOf} for each {@code kind}
   * @param anyBox the box {@code oneOf}, for a box whose kind names no branch
   */
  private record Schemas(
      Schema document,
      Schema targetFieldMapping,
      Schema boxPosition,
      Map<String, Schema> boxKinds,
      Schema anyBox,
      Schema paramValue,
      Schema udfDeclaration,
      Schema udfParamType) {

    static Schemas load() {
      IJsonObject contract;
      try (InputStream in = SchemaStage.class.getResourceAsStream("mapping-model.schema.json")) {
        contract = openMapValues((IJsonObject) StrictJsonReader.read(in.readAllBytes()));
      } catch (IOException | MalformedJsonException e) {
        throw new IllegalStateException("the packed mapping-model schema cannot be read", e);
      }
      return new Schemas(
          new SchemaLoader(contract).load(),
          definition(contract, "targetFieldMapping"),
          definition(contract, "boxPosition"),
          Map.of(
              "source", definition(contract, "sourceFieldBox"),
              "function", definition(contract, "standardFunctionBox"),
              "udf", definition(contract, "udfBox"),
              "unresolved", definition(contract, "unresolvedFunctionBox")),
          definition(contract, "box"),
          definition(contract, "paramValue"),
          definition(contract, "udfDeclaration"),
          definition(contract, "udfParamType"));
    }

    /**
     * The schema for {@code box}: the branch its {@code kind} names, which a valid box of that kind
     * alone satisfies, so the box is validated against one branch rather than four.
     */
    Schema box(IJsonValue box) {
      return box instanceof IJsonObject object && object.get("kind") instanceof IJsonString kind
          ? boxKinds.getOrDefault(kind.getValue(), anyBox)
          : anyBox;
    }

    /**
     * {@code schema} with the value schema of every {@code patternProperties} replaced by {@code
     * true}, locations kept. The contract has no {@code patternProperties} inside an array.
     */
    private static IJsonObject openMapValues(IJsonObject schema) {
      Map<IJsonString, IJsonValue> members = new LinkedHashMap<>();
      schema
          .getProperties()
          .forEach(
              (key, value) -> {
                if (!(value instanceof IJsonObject object)) {
                  members.put(key, value);
                } else if (key.getValue().equals("patternProperties")) {
                  Map<IJsonString, IJsonValue> open = new LinkedHashMap<>();
                  object
                      .getProperties()
                      .forEach((p, s) -> open.put(p, new JsonBoolean(true, s.getLocation())));
                  members.put(key, new JsonObject(open, object.getLocation()));
                } else {
                  members.put(key, openMapValues(object));
                }
              });
      return new JsonObject(members, schema.getLocation());
    }

    /**
     * A schema whose root refers to one of the contract's {@code $defs}. It carries the contract's
     * {@code $defs} itself: json-sKema 0.32.0 resolves a {@code $ref} into the contract's {@code
     * urn:} id to a URI it cannot parse (observed).
     */
    private static Schema definition(IJsonObject contract, String name) {
      Map<IJsonString, IJsonValue> root = new LinkedHashMap<>();
      root.put(new JsonString("$schema"), contract.get("$schema"));
      root.put(new JsonString("$defs"), contract.get("$defs"));
      root.put(new JsonString("$ref"), new JsonString("#/$defs/" + name));
      return new SchemaLoader(new JsonObject(root)).load();
    }
  }
}
