package io.flottila.mapping.structure.importer.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.erosb.jsonsKema.AggregateSchemaLoadingException;
import com.github.erosb.jsonsKema.AllOfSchema;
import com.github.erosb.jsonsKema.AnyOfSchema;
import com.github.erosb.jsonsKema.CompositeSchema;
import com.github.erosb.jsonsKema.FormatSchema;
import com.github.erosb.jsonsKema.IJsonArray;
import com.github.erosb.jsonsKema.IJsonObject;
import com.github.erosb.jsonsKema.IJsonString;
import com.github.erosb.jsonsKema.IJsonValue;
import com.github.erosb.jsonsKema.ItemsSchema;
import com.github.erosb.jsonsKema.JsonArray;
import com.github.erosb.jsonsKema.JsonObject;
import com.github.erosb.jsonsKema.JsonParseException;
import com.github.erosb.jsonsKema.JsonParser;
import com.github.erosb.jsonsKema.JsonString;
import com.github.erosb.jsonsKema.JsonTypeMismatchException;
import com.github.erosb.jsonsKema.MaxItemsSchema;
import com.github.erosb.jsonsKema.MinItemsSchema;
import com.github.erosb.jsonsKema.MultiTypeSchema;
import com.github.erosb.jsonsKema.OneOfSchema;
import com.github.erosb.jsonsKema.PrepopulatedSchemaClient;
import com.github.erosb.jsonsKema.RefResolutionException;
import com.github.erosb.jsonsKema.ReferenceSchema;
import com.github.erosb.jsonsKema.RequiredSchema;
import com.github.erosb.jsonsKema.Schema;
import com.github.erosb.jsonsKema.SchemaClient;
import com.github.erosb.jsonsKema.SchemaLoader;
import com.github.erosb.jsonsKema.SchemaLoaderConfig;
import com.github.erosb.jsonsKema.SchemaLoadingException;
import com.github.erosb.jsonsKema.SourceLocation;
import com.github.erosb.jsonsKema.TextLocation;
import com.github.erosb.jsonsKema.TypeSchema;
import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldKind;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.Occurrence;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.structure.StructureType;
import io.flottila.mapping.structure.ValueType;
import io.flottila.mapping.structure.importer.StructureImportDiagnostics;
import io.flottila.mapping.structure.importer.StructureLoadException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads a JSON Schema 2020-12 bundle into a structure, with json-sKema 0.32.0 (ADR-0008 "JSON
 * Schema and WSDL", #97). The first bundle file is the root schema.
 *
 * <p>Each file is read through the host's resolver and parsed with its positions. A relative {@code
 * $ref} to another file is mapped to a bundle file by text, against the referring file's bundle
 * reference and whatever an {@code $id} says, before that file is read; an absolute one names the
 * bundle file whose root {@code $id} it is (ADR-0008, the closed bundle). Every {@code $ref} is
 * then rewritten to its file's URI and a JSON pointer, and json-sKema loads the files from memory:
 * it reads no file and no URL, only its own copy of the 2020-12 meta-schema. One read reports every
 * load error, files in bundle order and each file in document order (ADR-0008); a keyword refused
 * before the load is dropped for it. The loaded schema is walked into fields:
 *
 * <ul>
 *   <li>an object's properties keep their declared order: its own {@code properties} first, then
 *       those of its {@code $ref}, {@code allOf}, {@code oneOf} and {@code anyOf} in keyword order;
 *       a property is mandatory when it is {@code required} and declared outside {@code oneOf} and
 *       {@code anyOf};
 *   <li>a property declared twice is one field, refused when the two declarations give two shapes;
 *   <li>an object reached by {@code $ref} is a named structure type, whose id is its file and JSON
 *       pointer, so it is shared and a recursive one is never expanded;
 *   <li>an array property is one repeated field with {@code minItems} and {@code maxItems}, marked
 *       as an array property whatever its {@code maxItems} (#118);
 *   <li>the value type comes from {@code type} and {@code format}, and is otherwise {@code
 *       xs:string}; a type list with {@code "null"} marks the field nillable.
 * </ul>
 */
public final class JsonSchemaReader {

  /** The {@code $schema} of draft 2020-12, the one draft read (ADR-0008). */
  private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

  /** The largest {@code minItems} or {@code maxItems}: 18 digits, as XSD's occurrence counts. */
  private static final BigDecimal MOST_ITEMS = new BigDecimal("999999999999999999");

  /** The start of an absolute URI: its scheme (RFC 3986 §3.1). */
  private static final Pattern ABSOLUTE = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:");

  /** The characters of a URI path: unreserved, sub-delims, {@code :}, {@code @} and {@code /}. */
  private static final String PATH_CHARACTERS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/";

  /**
   * The formats that give a value type: {@code date}, {@code time} and the date-time one. Their
   * names are the value types' names in lower case with a hyphen, which keeps a code-shaped literal
   * out of the class ({@code DiagnosticCatalogueTest}).
   */
  private static final Map<String, ValueType> FORMATS =
      Stream.of(ValueType.DATE, ValueType.TIME, ValueType.DATE_TIME)
          .collect(
              Collectors.toMap(t -> t.name().toLowerCase(Locale.ROOT).replace('_', '-'), t -> t));

  /** The bundle's files, in bundle order. */
  private final List<String> files;

  /** Every load error so far, in the order found. */
  private final List<StructureLoadException> errors;

  /** The code and position of each load error so far, so that each is reported once. */
  private final Set<String> reported = new HashSet<>();

  /** The bundle's references by their normalised text. */
  private final Map<String, String> listed = new HashMap<>();

  /** How many {@code ..} segments the bundle's references start with, at most. */
  private final int up;

  /**
   * Each file json-sKema loads, parsed, by the URI it knows it by: a file that is not well-formed,
   * holds no object or is of another draft stops there (ADR-0008) and is not loaded.
   */
  private final Map<URI, IJsonValue> documents = new LinkedHashMap<>();

  /** The files of another draft, which stop after their scan. */
  private final Set<String> stopped = new HashSet<>();

  /** The bundle reference of each file parsed, by its URI. */
  private final Map<URI, String> references = new HashMap<>();

  /**
   * What json-sKema gets instead of a keyword's value: a {@code $ref}'s file URI and pointer, or
   * nothing for a keyword that is dropped because its error is reported already.
   */
  private final Map<IJsonValue, Optional<String>> rewrites = new IdentityHashMap<>();

  /** The bundle reference of each file read that has a root {@code $id}, by that {@code $id}. */
  private final Map<String, String> rootIds = new HashMap<>();

  /** The JSON pointer of each {@code $anchor} of each file read, by file and anchor name. */
  private final Map<String, Map<String, String>> anchors = new HashMap<>();

  /** Every {@code $ref} of the files read, in the order read. */
  private final List<Ref> refs = new ArrayList<>();

  private final Map<String, StructureType> types = new HashMap<>();

  /** The ids of the structure types begun, so that a recursive type ends. */
  private final Set<String> begun = new HashSet<>();

  private JsonSchemaReader(List<String> files, List<StructureLoadException> errors) {
    this.files = files;
    this.errors = new ArrayList<>(errors);
    int most = 0;
    for (String file : files) {
      String text = normalised(file);
      listed.put(text, file);
      most = Math.max(most, leadingUps(text));
    }
    this.up = most;
  }

  /**
   * @param files the bundle's files, in bundle order; the first is the root schema
   * @param contents the bytes of each file the host's resolver read, by its name in {@code files}
   * @param errors the load errors so far, of files the resolver did not read; the read adds its own
   * @throws StructureLoadException with every load error, files in bundle order and each file in
   *     document order (ADR-0008)
   */
  public static Structure read(
      List<String> files, Map<String, byte[]> contents, List<StructureLoadException> errors)
      throws StructureLoadException {
    return new JsonSchemaReader(files, errors).read(contents);
  }

  /**
   * Every file is read and checked, then json-sKema loads the files that do not stop with their
   * refused keywords dropped, then the loaded schema is walked into fields. json-sKema loads a
   * whole schema or none, so the walk's refusals are found only when nothing before it failed.
   */
  private Structure read(Map<String, byte[]> contents) throws StructureLoadException {
    for (String file : files) {
      byte[] content = contents.get(file);
      // a file the resolver did not read has its error already
      if (content != null) {
        readFile(file, content);
      }
    }
    for (Ref ref : refs) {
      rewrites.put(ref.value(), target(ref));
    }
    URI rootUri = uri(files.getFirst());
    Optional<Schema> loaded =
        documents.containsKey(rootUri) ? load(rootUri) : Optional.<Schema>empty();
    if (loaded.isPresent() && errors.isEmpty()) {
      Schema root = loaded.get();
      Shape shape = shape(root);
      if (shape.array() || shape.type().isEmpty()) {
        report(
            StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED,
            root.getLocation(),
            "the root schema is not an object; a JSON root is an object (ADR-0008)");
      }
      if (errors.isEmpty()) {
        FieldPath.Step dollar = new FieldPath.Step(FieldKind.JSON_PROPERTY, "", "$");
        return new Structure(
            new Field(dollar, new Occurrence(1, 1), Optional.empty(), false, shape.type()), types);
      }
    }
    List<StructureLoadException> ordered = new ArrayList<>(errors);
    ordered.sort(
        Comparator.comparingInt((StructureLoadException e) -> files.indexOf(e.file()))
            .thenComparingInt(StructureLoadException::line)
            .thenComparingInt(StructureLoadException::column));
    throw new StructureLoadException(ordered);
  }

  /**
   * Reads, parses and scans one bundle file. A file that is not UTF-8, not well-formed or holds no
   * object stops at that error.
   */
  private void readFile(String reference, byte[] content) {
    String text;
    try {
      text =
          UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(content))
              .toString();
    } catch (CharacterCodingException e) {
      errors.add(
          new StructureLoadException(
              StructureImportDiagnostics.SCHEMA_NOT_WELL_FORMED,
              reference,
              1,
              1,
              "the schema file is not UTF-8"));
      return;
    }
    URI uri = uri(reference);
    IJsonValue json;
    try {
      json = new JsonParser(text, uri).parse();
    } catch (JsonParseException e) {
      // json-sKema's message names its URI for the file, so the engine words its own
      TextLocation at = e.getLocation();
      errors.add(
          new StructureLoadException(
              StructureImportDiagnostics.SCHEMA_NOT_WELL_FORMED,
              reference,
              at.getLineNumber(),
              at.getPosition(),
              "the schema file is not well-formed JSON here"));
      return;
    }
    references.put(uri, reference);
    if (!(json instanceof IJsonObject object)) {
      report(
          StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED,
          json.getLocation(),
          "the schema file does not hold a JSON object; a JSON root is an object (ADR-0008)");
      return;
    }
    if (object.get("$id") instanceof IJsonString id) {
      String first = rootIds.putIfAbsent(id.getValue(), reference);
      if (first != null) {
        // an absolute $ref names the one bundle file whose root $id it is (ADR-0008)
        report(
            StructureImportDiagnostics.SCHEMA_INVALID,
            keyOf(object, "$id").getLocation(),
            "the root $id \"" + id.getValue() + "\" is the root $id of " + first + " too");
      }
    }
    scan(reference, json);
    if (!stopped.contains(reference)) {
      documents.put(uri, json);
    }
  }

  /**
   * Checks the keywords of a file in document order: {@code $schema} names draft 2020-12, there is
   * no {@code $dynamicRef}, and a relative {@code $ref} to another file names a bundle file.
   * Records the {@code $anchor}s and the {@code $ref}s.
   */
  private void scan(String reference, IJsonValue value) {
    if (value instanceof IJsonObject object) {
      for (Map.Entry<IJsonString, IJsonValue> member : object.getProperties().entrySet()) {
        IJsonString key = member.getKey();
        // a keyword's value is a string; a property of that name holds a schema, which is scanned
        if (member.getValue() instanceof IJsonString text) {
          keyword(reference, object, key, text);
        } else {
          scan(reference, member.getValue());
        }
      }
    } else if (value instanceof IJsonArray array) {
      for (IJsonValue element : array.getElements()) {
        scan(reference, element);
      }
    }
  }

  private void keyword(String reference, IJsonObject schema, IJsonString key, IJsonString value) {
    switch (key.getValue()) {
      case "$schema" -> {
        if (!value.getValue().equals(DRAFT_2020_12)) {
          report(
              StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED,
              key.getLocation(),
              "the schema is not JSON Schema draft 2020-12, the one draft read (ADR-0008): "
                  + value.getValue());
          stopped.add(reference);
        }
      }
      case "$dynamicRef" -> {
        report(
            StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED,
            key.getLocation(),
            "$dynamicRef is not read (ADR-0008)");
        rewrites.put(value, Optional.empty());
      }
      case "$anchor" ->
          anchors
              .computeIfAbsent(reference, r -> new HashMap<>())
              .put(value.getValue(), encoded(pointer(schema.getLocation())));
      case "$ref" -> ref(reference, key, value);
      default -> {}
    }
  }

  /**
   * Maps a relative {@code $ref} to the bundle file it names, by text against the referring file's
   * bundle reference, whatever an {@code $id} says (ADR-0008, the closed bundle). An absolute one
   * names a file by its root {@code $id}, known once every file is read.
   */
  private void ref(String reference, IJsonString key, IJsonString value) {
    String ref = value.getValue();
    int hash = ref.indexOf('#');
    String path = hash < 0 ? ref : ref.substring(0, hash);
    String fragment = hash < 0 ? "" : ref.substring(hash + 1);
    if (ABSOLUTE.matcher(path).lookingAt()) {
      refs.add(new Ref(key.getLocation(), value, path, Optional.empty(), fragment));
      return;
    }
    String target = reference;
    if (!path.isEmpty()) {
      String named = normalised(reference.substring(0, reference.lastIndexOf('/') + 1) + path);
      target = listed.get(named);
      if (target == null) {
        report(
            StructureImportDiagnostics.SCHEMA_REFERENCE_OUTSIDE_BUNDLE,
            key.getLocation(),
            "the $ref \""
                + ref
                + "\" names \""
                + named
                + "\", which the schema bundle does not list");
        rewrites.put(value, Optional.empty());
        return;
      }
    }
    refs.add(new Ref(key.getLocation(), value, path, Optional.of(target), fragment));
  }

  /**
   * The URI a {@code $ref} is rewritten to before json-sKema loads it: its bundle file's URI and a
   * JSON pointer from that file's root, so that no {@code $id} changes what it names. An anchor is
   * replaced by its pointer. Empty when the {@code $ref} is dropped: it names no bundle file, a
   * file that stopped at its own error, or no anchor.
   */
  private Optional<String> target(Ref ref) {
    String file = ref.file().orElse(rootIds.get(ref.path()));
    if (file == null) {
      report(
          StructureImportDiagnostics.SCHEMA_REFERENCE_OUTSIDE_BUNDLE,
          ref.at(),
          "the $ref \""
              + ref.value().getValue()
              + "\" names \""
              + ref.path()
              + "\", which no file of the schema bundle has as its root $id");
      return Optional.empty();
    }
    if (!documents.containsKey(uri(file))) {
      return Optional.empty();
    }
    String pointer = ref.fragment();
    // a fragment that is no JSON pointer names an anchor
    if (!pointer.isEmpty() && !pointer.startsWith("/")) {
      pointer = anchors.getOrDefault(file, Map.of()).get(pointer);
      if (pointer == null) {
        report(
            StructureImportDiagnostics.SCHEMA_INVALID,
            ref.at(),
            "the $ref names the anchor \"" + ref.fragment() + "\", which no $anchor declares");
        return Optional.empty();
      }
    }
    return Optional.of(uri(file) + "#" + pointer);
  }

  /**
   * Loads the root schema with json-sKema, from the files that do not stop, with their {@code
   * $ref}s rewritten and their refused keywords dropped. Empty when json-sKema finds the schema
   * invalid: each of its problems is reported.
   */
  private Optional<Schema> load(URI root) {
    Map<URI, IJsonValue> loaded = new HashMap<>();
    documents.forEach((uri, json) -> loaded.put(uri, rewritten(json)));
    List<SchemaLoadingException> problems;
    try {
      return Optional.of(
          new SchemaLoader(loaded.get(root), new SchemaLoaderConfig(new BundleClient(loaded), root))
              .load());
    } catch (AggregateSchemaLoadingException all) {
      problems = all.getCauses();
    } catch (RefResolutionException | JsonTypeMismatchException one) {
      // one problem is thrown alone, two or more together
      problems = List.of(one);
    }
    for (SchemaLoadingException problem : problems) {
      // the two problems json-sKema reports for a schema read from memory; another passes through
      try {
        throw problem;
      } catch (RefResolutionException e) {
        invalid(e.getRef().getLocation());
      } catch (JsonTypeMismatchException e) {
        invalid(e.getLocation());
      }
    }
    return Optional.empty();
  }

  private void invalid(SourceLocation at) {
    report(
        StructureImportDiagnostics.SCHEMA_INVALID,
        at,
        "the schema is not valid JSON Schema 2020-12 here");
  }

  /**
   * A parsed file with each keyword value in {@link #rewrites} replaced at its position, or dropped
   * with its key.
   */
  private IJsonValue rewritten(IJsonValue value) {
    if (value instanceof IJsonObject object) {
      Map<IJsonString, IJsonValue> members = new LinkedHashMap<>();
      for (Map.Entry<IJsonString, IJsonValue> member : object.getProperties().entrySet()) {
        Optional<String> rewrite = rewrites.get(member.getValue());
        if (rewrite == null) {
          members.put(member.getKey(), rewritten(member.getValue()));
        } else if (rewrite.isPresent()) {
          members.put(
              member.getKey(), new JsonString(rewrite.get(), member.getValue().getLocation()));
        }
      }
      return new JsonObject(members, object.getLocation());
    }
    if (value instanceof IJsonArray array) {
      return new JsonArray(
          array.getElements().stream().map(this::rewritten).toList(), array.getLocation());
    }
    return value;
  }

  /** Adds a load error, unless one of that code is reported at that position already. */
  private void report(String code, SourceLocation at, String message) {
    StructureLoadException error = refusal(code, at, message);
    if (reported.add(code + " " + error.file() + " " + error.line() + ":" + error.column())) {
      errors.add(error);
    }
  }

  /** The JSON pointer of a location, from its file's root. */
  private static String pointer(SourceLocation at) {
    StringBuilder pointer = new StringBuilder();
    for (String segment : at.getPointer().getSegments()) {
      pointer.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
    }
    return pointer.toString();
  }

  /** What a property's schema gives, as its field before the property's own occurrence. */
  private Shape shape(Schema schema) {
    Facts facts = new Facts();
    gather(schema, facts);
    boolean nillable = facts.types.remove("null");
    if (facts.types.equals(Set.of("array")) || facts.types.isEmpty() && facts.items != null) {
      Shape item =
          facts.items == null
              ? new Shape(Optional.of(ValueType.STRING), Optional.empty(), Optional.empty(), false)
              : shape(facts.items.getItemsSchema());
      if (item.array()) {
        report(
            StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED,
            facts.items.getLocation(),
            "an array of arrays is not read (ADR-0008)");
      }
      long minItems = facts.minItems;
      if (minItems > facts.maxItems) {
        // as XSD refuses a minOccurs above its maxOccurs (XsdSubsetCheck)
        report(
            StructureImportDiagnostics.SCHEMA_INVALID,
            facts.minItemsAt,
            "minItems " + minItems + " is greater than maxItems " + facts.maxItems);
        // the load fails at that error; the occurrence never has its minimum above its maximum
        minItems = facts.maxItems;
      }
      return new Shape(
          item.valueType(),
          item.type(),
          Optional.of(new Occurrence(minItems, facts.maxItems)),
          nillable);
    }
    if (facts.types.equals(Set.of("object"))
        || facts.types.isEmpty() && !facts.declarations.isEmpty()) {
      // a type or a property comes from a keyword, so the schema is a JSON object, which json-sKema
      // loads as a composite schema; a boolean schema has no keyword
      return new Shape(
          Optional.empty(),
          Optional.of(type((CompositeSchema) schema)),
          Optional.empty(),
          nillable);
    }
    // a union of types, or none, is a string; a format applies to a string
    String type = facts.types.size() == 1 ? facts.types.getFirst() : "string";
    ValueType valueType =
        switch (type) {
          case "integer" -> ValueType.INTEGER;
          case "number" -> ValueType.DECIMAL;
          case "boolean" -> ValueType.BOOLEAN;
          default -> FORMATS.getOrDefault(facts.format, ValueType.STRING);
        };
    return new Shape(Optional.of(valueType), Optional.empty(), Optional.empty(), nillable);
  }

  /**
   * The id of the structure type of an object schema, its fields added to {@link #types} the first
   * time. A schema that is a {@code $ref} and nothing else has the type of the schema it names.
   */
  private String type(CompositeSchema schema) {
    CompositeSchema named = schema;
    while (named.getPropertySchemas().isEmpty()
        && named.getSubschemas().size() == 1
        && named.getSubschemas().iterator().next() instanceof ReferenceSchema ref) {
      // the $ref is all the schema says, so the schema it names is the object schema
      named = (CompositeSchema) ref.getReferredSchema();
    }
    SourceLocation at = named.getLocation();
    String typeId = references.get(at.getDocumentSource()) + "#" + pointer(at);
    if (begun.add(typeId)) {
      Facts facts = new Facts();
      gather(named, facts);
      List<Field> fields = new ArrayList<>();
      for (Map.Entry<String, List<Declaration>> property : facts.declarations.entrySet()) {
        fields.add(field(property.getKey(), property.getValue(), facts.required));
      }
      types.put(typeId, new StructureType(typeId, fields));
    }
    return typeId;
  }

  /** One field for every declaration of a property, which must all give one shape. */
  private Field field(String name, List<Declaration> declarations, Set<String> required) {
    Shape shape = shape(declarations.getFirst().schema());
    for (Declaration declaration : declarations.subList(1, declarations.size())) {
      if (!shape(declaration.schema()).equals(shape)) {
        report(
            StructureImportDiagnostics.SCHEMA_CONSTRUCT_UNSUPPORTED,
            declaration.schema().getLocation(),
            "the property \"" + name + "\" is declared again with another shape (ADR-0008)");
      }
    }
    boolean mandatory =
        required.contains(name) && declarations.stream().anyMatch(d -> !d.optional());
    long min = mandatory ? 1 : 0;
    Occurrence occurrence =
        shape
            .items()
            .map(items -> new Occurrence(min * items.min(), items.max()))
            .orElse(new Occurrence(min, 1));
    return new Field(
        new FieldPath.Step(FieldKind.JSON_PROPERTY, "", name),
        occurrence,
        shape.valueType(),
        shape.nillable(),
        shape.type(),
        Optional.empty(),
        shape.array());
  }

  /**
   * Gathers what a schema says of an instance: its own keywords and those of its {@code $ref} and
   * {@code allOf}; of {@code oneOf} and {@code anyOf}, the properties only, each optional. Every
   * other keyword is ignored, {@code additionalProperties}, {@code patternProperties}, {@code
   * if}/{@code then}/{@code else} and {@code not} among them (ADR-0008).
   */
  private void gather(Schema schema, Facts facts) {
    if (!facts.seen.add(schema)) {
      return;
    }
    switch (schema) {
      case CompositeSchema composite -> {
        facts.declare(composite, false);
        for (Schema keyword : composite.getSubschemas()) {
          gather(keyword, facts);
        }
      }
      case ReferenceSchema ref -> gather(ref.getReferredSchema(), facts);
      case AllOfSchema allOf -> {
        for (Schema branch : allOf.getSubschemas()) {
          gather(branch, facts);
        }
      }
      case OneOfSchema oneOf -> optional(oneOf.getSubschemas(), facts);
      case AnyOfSchema anyOf -> optional(anyOf.getSubschemas(), facts);
      case TypeSchema type -> facts.types.add(type.getType().getValue());
      case MultiTypeSchema list -> {
        for (IJsonValue type : list.getTypes().getElements()) {
          if (type instanceof IJsonString name) {
            facts.types.add(name.getValue());
          } else {
            report(
                StructureImportDiagnostics.SCHEMA_INVALID,
                type.getLocation(),
                "a type list holds type names, and this is no string");
          }
        }
      }
      case FormatSchema format -> facts.format = format.getFormat();
      case ItemsSchema items -> facts.items = items;
      case MinItemsSchema min -> {
        facts.minItems = count(min.getMinItems(), min.getLocation(), "minItems", 0);
        facts.minItemsAt = min.getLocation();
      }
      case MaxItemsSchema max ->
          facts.maxItems =
              count(max.getMaxItems(), max.getLocation(), "maxItems", Occurrence.UNBOUNDED);
      case RequiredSchema required -> facts.required.addAll(required.getRequiredProperties());
      default -> {}
    }
  }

  /**
   * A {@code minItems} or {@code maxItems} value as a count: a non-negative integer of at most 18
   * digits, as an XSD {@code minOccurs} or {@code maxOccurs} (XsdSubsetCheck). json-sKema gives any
   * JSON number there: an {@code Integer}, a {@code BigInteger} beyond it, a {@code Double} or a
   * {@code BigDecimal} (javap, 0.32.0). Any other value is {@code schema-invalid} at its keyword,
   * and {@code otherwise} stands in for it, so that no occurrence wraps or turns negative.
   */
  private long count(Number value, SourceLocation at, String keyword, long otherwise) {
    BigDecimal count = new BigDecimal(value.toString());
    if (count.signum() < 0
        || count.stripTrailingZeros().scale() > 0
        || count.compareTo(MOST_ITEMS) > 0) {
      report(
          StructureImportDiagnostics.SCHEMA_INVALID,
          at,
          keyword + " " + value + " is not a non-negative integer of at most 18 digits");
      return otherwise;
    }
    return count.longValueExact();
  }

  /** The key {@code name} of {@code object}, which has it: a key carries its own position. */
  private static IJsonString keyOf(IJsonObject object, String name) {
    return object.getProperties().keySet().stream()
        .filter(key -> key.getValue().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /** Gathers the properties of the branches of a {@code oneOf} or {@code anyOf}, each optional. */
  private static void optional(List<Schema> branches, Facts facts) {
    for (Schema branch : branches) {
      if (!facts.seen.add(branch)) {
        continue;
      }
      switch (branch) {
        case CompositeSchema composite -> {
          facts.declare(composite, true);
          optional(List.copyOf(composite.getSubschemas()), facts);
        }
        case ReferenceSchema ref -> optional(List.of(ref.getReferredSchema()), facts);
        case AllOfSchema allOf -> optional(allOf.getSubschemas(), facts);
        case OneOfSchema oneOf -> optional(oneOf.getSubschemas(), facts);
        case AnyOfSchema anyOf -> optional(anyOf.getSubschemas(), facts);
        default -> {}
      }
    }
  }

  private StructureLoadException refusal(String code, SourceLocation at, String message) {
    return new StructureLoadException(
        code,
        references.get(at.getDocumentSource()),
        at.getLineNumber(),
        at.getPosition(),
        message);
  }

  /**
   * The URI json-sKema knows a bundle file by: its normalised reference under as many padding
   * segments as the bundle's references climb, so that no URI starts with a {@code ..} that URI
   * resolution would drop, and percent-encoded where a URI needs it.
   */
  private URI uri(String reference) {
    String text = normalised(reference);
    int climbs = leadingUps(text);
    return URI.create("mem:/" + encoded("_/".repeat(up - climbs) + text.substring(3 * climbs)));
  }

  /**
   * A URI path or fragment, its bytes percent-encoded unless they are a path character (RFC 3986
   * §3.3): a byte above 0x7F is negative, so it is never one.
   */
  private static String encoded(String text) {
    StringBuilder encoded = new StringBuilder();
    for (byte b : text.getBytes(UTF_8)) {
      if (PATH_CHARACTERS.indexOf(b) >= 0) {
        encoded.append((char) b);
      } else {
        encoded.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
      }
    }
    return encoded.toString();
  }

  /** A reference with its {@code .} segments dropped and each {@code ..} folded into its parent. */
  private static String normalised(String reference) {
    Deque<String> segments = new ArrayDeque<>();
    for (String segment : reference.split("/", -1)) {
      if (segment.equals("..") && !segments.isEmpty() && !segments.getLast().equals("..")) {
        segments.removeLast();
      } else if (!segment.equals(".")) {
        segments.addLast(segment);
      }
    }
    return String.join("/", segments);
  }

  private static int leadingUps(String normalised) {
    int climbs = 0;
    while (normalised.startsWith("../", 3 * climbs)) {
      climbs++;
    }
    return climbs;
  }

  /**
   * A property schema's field, before the property's own occurrence: an array property's {@code
   * items} carries {@code minItems} and {@code maxItems}.
   */
  private record Shape(
      Optional<ValueType> valueType,
      Optional<String> type,
      Optional<Occurrence> items,
      boolean nillable) {

    /** Whether the schema is an array property's, whatever its {@code maxItems} (#118). */
    boolean array() {
      return items.isPresent();
    }
  }

  /** One declaration of a property: in a {@code oneOf} or {@code anyOf} branch, it is optional. */
  private record Declaration(Schema schema, boolean optional) {}

  /**
   * A {@code $ref}: its keyword's position, its value, the file part of its text, the bundle file
   * that part names (empty for an absolute one, found by its root {@code $id}) and its fragment.
   */
  private record Ref(
      SourceLocation at, IJsonString value, String path, Optional<String> file, String fragment) {}

  /** What one schema says of an instance, gathered over its keywords. */
  private static final class Facts {
    final LinkedHashSet<String> types = new LinkedHashSet<>();
    String format;
    ItemsSchema items;
    long minItems;

    /** The position of the {@code minItems} gathered, if any. */
    SourceLocation minItemsAt;

    long maxItems = Occurrence.UNBOUNDED;
    final Set<String> required = new HashSet<>();
    final Map<String, List<Declaration>> declarations = new LinkedHashMap<>();

    /** The schemas gathered, by identity: json-sKema's schemas compare by value, and recurse. */
    final Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    void declare(CompositeSchema schema, boolean optional) {
      schema
          .getPropertySchemas()
          .forEach(
              (name, property) ->
                  declarations
                      .computeIfAbsent(name, n -> new ArrayList<>())
                      .add(new Declaration(property, optional)));
    }
  }

  /**
   * Gives json-sKema the bundle files, parsed and with their {@code $ref}s rewritten, and its own
   * copy of the 2020-12 meta-schema; it reads no file and no URL.
   */
  private static final class BundleClient implements SchemaClient {

    private final Map<URI, IJsonValue> files;
    private final SchemaClient metaSchema = new PrepopulatedSchemaClient(this, Map.of());

    BundleClient(Map<URI, IJsonValue> files) {
      this.files = files;
    }

    @Override
    public IJsonValue getParsed(URI uri) {
      IJsonValue file = files.get(uri);
      return file != null ? file : metaSchema.getParsed(uri);
    }

    /**
     * The meta-schema client's fallback, for a URI that is no 2020-12 meta-schema. json-sKema asks
     * for no such URI: every {@code $ref} names a bundle file once rewritten, {@code $schema} names
     * 2020-12, and {@code $dynamicRef} is refused before the load.
     */
    @Override
    public InputStream get(URI uri) {
      throw new IllegalStateException("json-sKema asked for " + uri + ", no bundle file");
    }
  }
}
