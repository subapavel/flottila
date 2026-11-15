package io.flottila.mapping.structure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The tree of fields a message may have, held as a finite graph of types (ADR-0008). Fields resolve
 * lazily by field path, so a recursive type is never expanded.
 *
 * @param root the root field
 * @param types the structure types by id
 */
public record Structure(Field root, Map<String, StructureType> types) {

  public Structure {
    types = Map.copyOf(types);
  }

  /** The field path of the root field. */
  public FieldPath rootPath() {
    return FieldPath.of(root.step());
  }

  /** The field at a path, or empty when the structure has no such field. */
  public Optional<Field> field(FieldPath path) {
    List<FieldPath.Step> steps = path.steps();
    if (!steps.getFirst().equals(root.step())) {
      return Optional.empty();
    }
    Field current = root;
    for (FieldPath.Step step : steps.subList(1, steps.size())) {
      Optional<Field> child =
          childFields(current).stream().filter(f -> f.step().equals(step)).findFirst();
      if (child.isEmpty()) {
        return Optional.empty();
      }
      current = child.get();
    }
    return Optional.of(current);
  }

  /**
   * The paths of a field's child fields: attributes first, then elements in output order. A leaf
   * field has none.
   *
   * @throws IllegalArgumentException when the structure has no field at {@code path}
   */
  public List<FieldPath> children(FieldPath path) {
    Field field =
        field(path).orElseThrow(() -> new IllegalArgumentException("no field at " + path));
    List<FieldPath> paths = new ArrayList<>();
    for (Field child : childFields(field)) {
      paths.add(path.child(child.step()));
    }
    return paths;
  }

  /**
   * SHA-256 over a canonical form of what the compiler reads (ADR-0009): the root and every type in
   * id order, each field with its step, effective occurrence, value type and child type, in field
   * order, an array property with its marker (#118), and a refused field with its refusal's reason
   * (#92). Display data, prefixes and whitespace of the schema do not enter it, nor does a
   * refusal's position.
   */
  public String fingerprint() {
    StringBuilder canonical = new StringBuilder("root ").append(canonical(root)).append('\n');
    for (StructureType type : new TreeMap<>(types).values()) {
      canonical.append("type ").append(type.id()).append('\n');
      for (Field field : type.fields()) {
        canonical.append("field ").append(canonical(field)).append('\n');
      }
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // every Java platform implementation supports SHA-256 (MessageDigest javadoc)
      throw new IllegalStateException(e);
    }
  }

  private static String canonical(Field field) {
    return field.step()
        + " "
        + field.occurrence()
        + " "
        + field.valueType().map(ValueType::typeName).orElse("-")
        + " "
        + field.type().orElse("-")
        // a field without the array marker keeps the line it had before #118 (ADR-0009)
        + (field.array() ? " array" : "")
        // a field without a refusal keeps the line it had before #92 (ADR-0009)
        + field.refusal().map(refusal -> " refused:" + refusal.reason().code()).orElse("");
  }

  private List<Field> childFields(Field field) {
    return field.type().map(id -> types.get(id).fields()).orElse(List.of());
  }
}
