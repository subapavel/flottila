package io.flottila.mapping.mapping.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.mapping.Box;
import io.flottila.mapping.mapping.BoxPosition;
import io.flottila.mapping.mapping.Layout;
import io.flottila.mapping.mapping.MappingModel;
import io.flottila.mapping.mapping.ParamValue;
import io.flottila.mapping.mapping.SourceFieldBox;
import io.flottila.mapping.mapping.StandardFunctionBox;
import io.flottila.mapping.mapping.StructureRef;
import io.flottila.mapping.mapping.Table;
import io.flottila.mapping.mapping.TargetFieldMapping;
import io.flottila.mapping.mapping.Text;
import io.flottila.mapping.mapping.UdfBox;
import io.flottila.mapping.mapping.UdfDeclaration;
import io.flottila.mapping.mapping.UnresolvedFunctionBox;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.RootSelector;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.WsdlRoot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * The one writer of the canonical form (ADR-0010). It walks the records into a tree of sorted
 * objects and prints that tree as {@code JSON.stringify(value, null, 2)} does: keys by UTF-16 code
 * unit, LF, a final LF, UTF-8 without a byte order mark. Members equal to their default and empty
 * collections are left out; {@code result: null}, an unconnected input and an empty table stay.
 */
public final class CanonicalWriter {

  private CanonicalWriter() {}

  /** The canonical bytes of {@code model}. */
  public static byte[] write(MappingModel model) {
    StringBuilder out = new StringBuilder();
    print(model(model), "", out);
    return out.append('\n').toString().getBytes(UTF_8);
  }

  /** The tree the writer prints: JSON values whose objects keep their keys sorted. */
  private sealed interface Node {}

  private record ObjectNode(SortedMap<String, Node> members) implements Node {
    ObjectNode() {
      this(new TreeMap<>());
    }

    ObjectNode put(String name, Node value) {
      members.put(name, value);
      return this;
    }

    ObjectNode put(String name, String value) {
      return put(name, new StringNode(value));
    }

    /** Puts {@code value} unless it has no member: an empty object is left out. */
    ObjectNode putUnlessEmpty(String name, ObjectNode value) {
      return value.members().isEmpty() ? this : put(name, value);
    }

    /** Puts an array of {@code items} unless there is none: an empty array is left out. */
    ObjectNode putUnlessEmpty(String name, List<Node> items) {
      return items.isEmpty() ? this : put(name, new ArrayNode(items));
    }
  }

  private record ArrayNode(List<Node> items) implements Node {}

  private record StringNode(String value) implements Node {}

  private record NumberNode(int value) implements Node {}

  private record NullNode() implements Node {}

  private static ObjectNode model(MappingModel model) {
    ObjectNode mappings = new ObjectNode();
    model.targetFieldMappings().forEach((path, m) -> mappings.put(path, targetFieldMapping(m)));
    ObjectNode udfs = new ObjectNode();
    model.udfs().forEach((name, udf) -> udfs.put(name, udf(udf)));
    ObjectNode root =
        new ObjectNode()
            .put("formatVersion", new NumberNode(model.formatVersion()))
            .putUnlessEmpty("targetFieldMappings", mappings)
            .putUnlessEmpty("udfs", udfs);
    // ADR-0029: no description is left out (the record holds no empty one), and so is a
    // structure not chosen yet
    model.description().ifPresent(description -> root.put("description", description));
    model.source().ifPresent(source -> root.put("source", structureRef(source)));
    model.target().ifPresent(target -> root.put("target", structureRef(target)));
    return root;
  }

  private static ObjectNode structureRef(StructureRef ref) {
    List<Node> files = new ArrayList<>();
    ref.bundle().files().stream().sorted().forEach(f -> files.add(new StringNode(f)));
    return new ObjectNode()
        .put("bundle", new ArrayNode(files))
        .put("fingerprint", ref.fingerprint())
        .put("root", root(ref.root()));
  }

  private static ObjectNode root(RootSelector root) {
    return switch (root) {
      case ElementRoot e -> new ObjectNode().put("element", e.element());
      case WsdlRoot w -> new ObjectNode().put("wsdl", wsdl(w.wsdl()));
      case JsonRoot _ -> new ObjectNode().put("json", "$");
    };
  }

  private static ObjectNode wsdl(WsdlOperation operation) {
    ObjectNode wsdl =
        new ObjectNode()
            .put("direction", lowerCase(operation.direction()))
            .put("interface", operation.interfaceName())
            .put("operation", operation.operation());
    operation.fault().ifPresent(fault -> wsdl.put("fault", fault));
    return wsdl;
  }

  private static ObjectNode targetFieldMapping(TargetFieldMapping mapping) {
    ObjectNode boxes = new ObjectNode();
    mapping.boxes().forEach((id, box) -> boxes.put(id.value(), box(box)));
    return new ObjectNode()
        .put("boxes", boxes)
        .put("id", mapping.id().value())
        .putUnlessEmpty("layout", layout(mapping.layout()))
        .put("result", reference(mapping.result()));
  }

  /**
   * A layout (ADR-0029): no {@code boxes} without a box position, and an empty object, left out by
   * the caller, without any position. The record holds no stale position.
   */
  private static ObjectNode layout(Layout layout) {
    ObjectNode boxes = new ObjectNode();
    layout.boxes().forEach((id, position) -> boxes.put(id.value(), position(position)));
    ObjectNode node = new ObjectNode().putUnlessEmpty("boxes", boxes);
    layout.target().ifPresent(target -> node.put("target", position(target)));
    return node;
  }

  private static ObjectNode position(BoxPosition position) {
    return new ObjectNode()
        .put("x", new NumberNode(position.x()))
        .put("y", new NumberNode(position.y()));
  }

  private static ObjectNode box(Box box) {
    return switch (box) {
      case SourceFieldBox s -> {
        ObjectNode node = new ObjectNode().put("field", s.field()).put("kind", "source");
        s.context()
            .filter(context -> !FieldPathText.isParent(context, s.field()))
            .ifPresent(context -> node.put("context", context));
        yield node;
      }
      case StandardFunctionBox f -> call("function", f.inputs(), f.params()).put("fn", f.fn());
      case UdfBox u -> call("udf", u.inputs(), u.params()).put("udf", u.udf());
      case UnresolvedFunctionBox x ->
          call("unresolved", x.inputs(), x.params())
              .put("name", x.name())
              .put("origin", lowerCase(x.origin()));
    };
  }

  /** The members a function, UDF and unresolved box share. */
  private static ObjectNode call(
      String kind, List<Optional<AuthoringId>> inputs, Map<String, ParamValue> params) {
    List<Node> slots = new ArrayList<>();
    inputs.forEach(input -> slots.add(reference(input)));
    ObjectNode values = new ObjectNode();
    params.forEach((name, value) -> values.put(name, param(value)));
    return new ObjectNode()
        .put("kind", kind)
        .putUnlessEmpty("inputs", slots)
        .putUnlessEmpty("params", values);
  }

  private static Node param(ParamValue value) {
    return switch (value) {
      case Text t -> new StringNode(t.value());
      case Table t -> {
        ObjectNode rows = new ObjectNode();
        t.rows().forEach(rows::put);
        yield rows;
      }
    };
  }

  private static ObjectNode udf(UdfDeclaration udf) {
    List<Node> inputs = new ArrayList<>();
    udf.inputs().forEach(input -> inputs.add(new StringNode(input)));
    ObjectNode params = new ObjectNode();
    udf.params().forEach((name, type) -> params.put(name, lowerCase(type)));
    return new ObjectNode()
        .put("executionKind", lowerCase(udf.executionKind()))
        .putUnlessEmpty("inputs", inputs)
        .putUnlessEmpty("params", params)
        .put("source", udf.source());
  }

  /** A box id, or {@code null} for nothing connected. */
  private static Node reference(Optional<AuthoringId> id) {
    return id.<Node>map(i -> new StringNode(i.value())).orElseGet(NullNode::new);
  }

  private static String lowerCase(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private static void print(Node node, String indent, StringBuilder out) {
    switch (node) {
      case ObjectNode o ->
          container(
              '{',
              '}',
              o.members().entrySet(),
              indent,
              out,
              (member, inner) -> {
                quote(member.getKey(), out);
                out.append(": ");
                print(member.getValue(), inner, out);
              });
      case ArrayNode a ->
          container('[', ']', a.items(), indent, out, (item, inner) -> print(item, inner, out));
      case StringNode s -> quote(s.value(), out);
      case NumberNode n -> out.append(n.value());
      case NullNode _ -> out.append("null");
    }
  }

  /** An object or array: empty on one line, otherwise one entry per line, two spaces deeper. */
  private static <T> void container(
      char open,
      char close,
      Collection<T> entries,
      String indent,
      StringBuilder out,
      BiConsumer<T, String> entry) {
    out.append(open);
    if (!entries.isEmpty()) {
      String inner = indent + "  ";
      String separator = "\n";
      for (T e : entries) {
        out.append(separator).append(inner);
        entry.accept(e, inner);
        separator = ",\n";
      }
      out.append('\n').append(indent);
    }
    out.append(close);
  }

  /**
   * A string as {@code JSON.stringify} quotes it (ECMA-262 QuoteJSONString): the two-character
   * escapes, other C0 characters and lone surrogates as lowercase {@code \}{@code u} escapes, and
   * everything else, a surrogate pair included, as it is.
   */
  private static void quote(String value, StringBuilder out) {
    out.append('"');
    for (int k = 0; k < value.length(); k++) {
      char c = value.charAt(k);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (Character.isHighSurrogate(c)
              && k + 1 < value.length()
              && Character.isLowSurrogate(value.charAt(k + 1))) {
            out.append(c).append(value.charAt(++k));
          } else if (c < 0x20 || Character.isSurrogate(c)) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
