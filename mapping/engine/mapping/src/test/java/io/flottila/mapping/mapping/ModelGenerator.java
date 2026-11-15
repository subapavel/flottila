package io.flottila.mapping.mapping;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.RootSelector;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.WsdlDirection;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.WsdlRoot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.random.RandomGenerator;

/**
 * Seeded generator of valid mapping models in canonical normal form: bundles sorted and distinct,
 * no {@code context} equal to the field's parent, ids unique across the mapping, every reference
 * inside its target field mapping and pointing at an earlier box, so the graph is acyclic. Strings
 * mix every escape class of {@code JSON.stringify} with lone surrogates and U+FEFF.
 */
final class ModelGenerator {

  private static final String ID_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
  private static final String CROCKFORD = "0123456789abcdefghjkmnpqrstvwxyz";
  private static final String NAME_START = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_";
  private static final String NAME_REST = NAME_START + "0123456789";
  private static final String HEX = "0123456789abcdef";
  private static final List<String> TEXT_PIECES =
      List.of(
          "a", "Z", "0", " ", "\"", "\\", "/", "\b", "\f", "\n", "\r", "\t", "\u0000", "\u0001",
          "\u001f", "\u007f", "é", "﻿", " ", " ", "😀", "\ud83d", "\ude00", "￿", "{}", "null", "1",
          "#", "~", "%");
  private static final List<String> BUNDLE_POOL =
      List.of(
          "../structures/order.xsd",
          "../structures/order-types.xsd",
          "B.xsd",
          "a.xsd",
          "common-schemas@1.4.0:/order/order.xsd",
          "schemas/été.xsd");
  private static final List<String> NAMESPACES =
      List.of("", "Q{urn:flottila:gen}", "Q{http://example.com/gen/v1}");

  private final RandomGenerator random;
  private final Set<String> usedIds = new HashSet<>();

  ModelGenerator(long seed) {
    this.random = new SplittableRandom(seed);
  }

  MappingModel model() {
    Map<String, UdfDeclaration> udfs = udfs();
    Map<String, TargetFieldMapping> mappings = new LinkedHashMap<>();
    int count = random.nextInt(5);
    for (int k = 0; k < count; k++) {
      mappings.put(targetPath(k), targetFieldMapping(List.copyOf(udfs.keySet())));
    }
    // ADR-0029: a description, and either structure not chosen yet
    Optional<String> description =
        random.nextInt(3) == 0 ? Optional.empty() : Optional.of(description());
    Optional<StructureRef> source =
        random.nextInt(4) == 0 ? Optional.empty() : Optional.of(structureRef("Order"));
    Optional<StructureRef> target =
        random.nextInt(4) == 0 ? Optional.empty() : Optional.of(structureRef("Invoice"));
    return new MappingModel(1, description, source, target, mappings, udfs);
  }

  /** A non-empty description, an empty one is not written; sometimes at its 4000 code points. */
  private String description() {
    if (random.nextInt(8) == 0) {
      return "😀".repeat(3999) + "é";
    }
    String text = text();
    return text.isEmpty() ? "é" : text;
  }

  /**
   * A layout of {@code boxes} (ADR-0029): no position, some or all, each coordinate at a limit, at
   * 0 or anywhere between.
   */
  private Layout layout(Set<AuthoringId> boxes) {
    Optional<BoxPosition> target =
        random.nextBoolean() ? Optional.empty() : Optional.of(position());
    Map<AuthoringId, BoxPosition> positions = new LinkedHashMap<>();
    for (AuthoringId box : boxes) {
      if (random.nextBoolean()) {
        positions.put(box, position());
      }
    }
    return new Layout(target, positions);
  }

  private BoxPosition position() {
    return new BoxPosition(coordinate(), coordinate());
  }

  private int coordinate() {
    return switch (random.nextInt(5)) {
      case 0 -> -1_000_000;
      case 1 -> 1_000_000;
      case 2 -> 0;
      default -> random.nextInt(-1_000_000, 1_000_001);
    };
  }

  private StructureRef structureRef(String rootName) {
    Set<String> files = new TreeSet<>();
    int count = 1 + random.nextInt(3);
    while (files.size() < count) {
      files.add(pick(BUNDLE_POOL));
    }
    StringBuilder fingerprint = new StringBuilder("sha256:");
    for (int k = 0; k < 64; k++) {
      fingerprint.append(HEX.charAt(random.nextInt(HEX.length())));
    }
    return new StructureRef(
        new SchemaBundle(List.copyOf(files)), root(rootName), fingerprint.toString());
  }

  private RootSelector root(String name) {
    return switch (random.nextInt(3)) {
      case 0 -> new ElementRoot(pick(NAMESPACES) + name);
      case 1 -> {
        WsdlDirection direction = WsdlDirection.values()[random.nextInt(3)];
        Optional<String> fault =
            direction == WsdlDirection.FAULT ? Optional.of(name + "Fault") : Optional.empty();
        yield new WsdlRoot(
            new WsdlOperation(pick(NAMESPACES) + name + "Port", "send" + name, direction, fault));
      }
      default -> new JsonRoot();
    };
  }

  private String targetPath(int index) {
    String ns = pick(NAMESPACES);
    return switch (random.nextInt(4)) {
      case 0 -> "/" + ns + "Invoice/" + ns + "F" + index;
      case 1 -> "/" + ns + "Invoice/" + ns + "Line/@a" + index;
      case 2 -> "/$/invoice/f" + index;
      default -> "/" + ns + "Invoice/" + ns + "Line/" + ns + "F" + index;
    };
  }

  private TargetFieldMapping targetFieldMapping(List<String> udfNames) {
    AuthoringId id = new AuthoringId(newId());
    Map<AuthoringId, Box> boxes = new LinkedHashMap<>();
    List<AuthoringId> earlier = new ArrayList<>();
    int count = 1 + random.nextInt(6);
    for (int k = 0; k < count; k++) {
      AuthoringId boxId = new AuthoringId(newId());
      boxes.put(boxId, box(earlier, udfNames));
      earlier.add(boxId);
    }
    Optional<AuthoringId> result =
        random.nextInt(4) == 0 ? Optional.empty() : Optional.of(pick(earlier));
    return new TargetFieldMapping(id, result, boxes, layout(boxes.keySet()));
  }

  private Box box(List<AuthoringId> earlier, List<String> udfNames) {
    int kind = random.nextInt(10);
    if (kind < 4) {
      return sourceFieldBox();
    }
    if (kind < 7) {
      return new StandardFunctionBox(name(), inputs(earlier), params());
    }
    if (kind < 8 && !udfNames.isEmpty()) {
      return new UdfBox(pick(udfNames), inputs(earlier), params());
    }
    Origin origin = Origin.values()[random.nextInt(Origin.values().length)];
    return new UnresolvedFunctionBox(name(), origin, inputs(earlier), params());
  }

  private SourceFieldBox sourceFieldBox() {
    String ns = pick(NAMESPACES);
    List<String> steps = new ArrayList<>(List.of(ns + "Order"));
    int depth = 1 + random.nextInt(4);
    for (int k = 1; k < depth; k++) {
      steps.add(ns + pick(List.of("Item", "Line", "Header", "Price")));
    }
    if (random.nextBoolean()) {
      steps.add("@" + pick(List.of("id", "type", "currency")));
    }
    String field = "/" + String.join("/", steps);
    // a context is a proper ancestor other than the parent, or absent (the parent is the default)
    Optional<String> context = Optional.empty();
    if (steps.size() >= 3 && random.nextBoolean()) {
      int length = 1 + random.nextInt(steps.size() - 2);
      context = Optional.of("/" + String.join("/", steps.subList(0, length)));
    }
    return new SourceFieldBox(field, context);
  }

  private List<Optional<AuthoringId>> inputs(List<AuthoringId> earlier) {
    List<Optional<AuthoringId>> inputs = new ArrayList<>();
    int count = random.nextInt(4);
    for (int k = 0; k < count; k++) {
      inputs.add(
          earlier.isEmpty() || random.nextInt(4) == 0
              ? Optional.empty()
              : Optional.of(pick(earlier)));
    }
    return inputs;
  }

  private Map<String, ParamValue> params() {
    Map<String, ParamValue> params = new LinkedHashMap<>();
    int count = random.nextInt(3);
    while (params.size() < count) {
      params.put(name(), random.nextInt(3) == 0 ? table() : new Text(text()));
    }
    return params;
  }

  private Table table() {
    Map<String, String> rows = new LinkedHashMap<>();
    int count = random.nextInt(4);
    while (rows.size() < count) {
      rows.put(text(), text());
    }
    return new Table(rows);
  }

  private Map<String, UdfDeclaration> udfs() {
    Map<String, UdfDeclaration> udfs = new LinkedHashMap<>();
    int count = random.nextInt(4);
    while (udfs.size() < count) {
      String name = name();
      Set<String> inputs = new HashSet<>();
      List<String> inputNames = new ArrayList<>();
      int inputCount = random.nextInt(4);
      while (inputNames.size() < inputCount) {
        String input = name();
        if (inputs.add(input)) {
          inputNames.add(input);
        }
      }
      Map<String, UdfParamType> params = new LinkedHashMap<>();
      int paramCount = random.nextInt(3);
      while (params.size() < paramCount) {
        params.put(name(), UdfParamType.values()[random.nextInt(UdfParamType.values().length)]);
      }
      ExecutionKind kind = ExecutionKind.values()[random.nextInt(ExecutionKind.values().length)];
      udfs.put(name, new UdfDeclaration(kind, inputNames, params, "../udfs/gen/Gen.java#" + name));
    }
    return udfs;
  }

  /** An id matching {@code ^[A-Za-z0-9_-]{1,64}$}: mostly editor-like, sometimes at a limit. */
  private String newId() {
    while (true) {
      String id =
          switch (random.nextInt(6)) {
            case 0 -> chars(ID_CHARS, 1);
            case 1 -> chars(ID_CHARS, 64);
            case 2 -> chars(ID_CHARS, 1 + random.nextInt(64));
            default -> chars(CROCKFORD, 10);
          };
      if (usedIds.add(id)) {
        return id;
      }
    }
  }

  /** A name matching {@code ^[A-Za-z_][A-Za-z0-9_]{0,63}$}. */
  private String name() {
    int rest = random.nextInt(8) == 0 ? 63 : random.nextInt(10);
    return chars(NAME_START, 1) + chars(NAME_REST, rest);
  }

  private String text() {
    StringBuilder out = new StringBuilder();
    int count = random.nextInt(7);
    for (int k = 0; k < count; k++) {
      out.append(pick(TEXT_PIECES));
    }
    return out.toString();
  }

  private String chars(String alphabet, int length) {
    StringBuilder out = new StringBuilder(length);
    for (int k = 0; k < length; k++) {
      out.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return out.toString();
  }

  private <T> T pick(List<T> from) {
    return from.get(random.nextInt(from.size()));
  }
}
