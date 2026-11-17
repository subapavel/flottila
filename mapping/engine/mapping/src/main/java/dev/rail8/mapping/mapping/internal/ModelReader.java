package dev.rail8.mapping.mapping.internal;

import com.github.erosb.jsonsKema.IJsonArray;
import com.github.erosb.jsonsKema.IJsonNumber;
import com.github.erosb.jsonsKema.IJsonObject;
import com.github.erosb.jsonsKema.IJsonString;
import com.github.erosb.jsonsKema.IJsonValue;
import dev.rail8.mapping.ir.AuthoringId;
import dev.rail8.mapping.mapping.Box;
import dev.rail8.mapping.mapping.BoxPosition;
import dev.rail8.mapping.mapping.ExecutionKind;
import dev.rail8.mapping.mapping.Layout;
import dev.rail8.mapping.mapping.MappingModel;
import dev.rail8.mapping.mapping.Origin;
import dev.rail8.mapping.mapping.ParamValue;
import dev.rail8.mapping.mapping.SourceFieldBox;
import dev.rail8.mapping.mapping.StandardFunctionBox;
import dev.rail8.mapping.mapping.StructureRef;
import dev.rail8.mapping.mapping.Table;
import dev.rail8.mapping.mapping.TargetFieldMapping;
import dev.rail8.mapping.mapping.Text;
import dev.rail8.mapping.mapping.UdfBox;
import dev.rail8.mapping.mapping.UdfDeclaration;
import dev.rail8.mapping.mapping.UdfParamType;
import dev.rail8.mapping.mapping.UnresolvedFunctionBox;
import dev.rail8.mapping.structure.ElementRoot;
import dev.rail8.mapping.structure.JsonRoot;
import dev.rail8.mapping.structure.RootSelector;
import dev.rail8.mapping.structure.SchemaBundle;
import dev.rail8.mapping.structure.WsdlDirection;
import dev.rail8.mapping.structure.WsdlOperation;
import dev.rail8.mapping.structure.WsdlRoot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Binds the records by hand over json-sKema's tree, since the engine does not use Jackson. It runs
 * after stage 1, so every member it reads has the shape the schema gives it, and an absent member
 * is one the schema lets go.
 */
public final class ModelReader {

  private ModelReader() {}

  /** The model of a document that passed stage 1. */
  public static MappingModel read(IJsonValue document) {
    IJsonObject root = (IJsonObject) document;
    return new MappingModel(
        SchemaStage.FORMAT_VERSION,
        optionalText(root, "description"),
        Optional.ofNullable(root.get("source")).map(ModelReader::structureRef),
        Optional.ofNullable(root.get("target")).map(ModelReader::structureRef),
        map(root.get("targetFieldMappings"), Function.identity(), ModelReader::targetFieldMapping),
        map(root.get("udfs"), Function.identity(), ModelReader::udf));
  }

  private static StructureRef structureRef(IJsonValue value) {
    IJsonObject ref = (IJsonObject) value;
    return new StructureRef(
        new SchemaBundle(strings(ref.get("bundle"))),
        root((IJsonObject) ref.get("root")),
        text(ref, "fingerprint"));
  }

  private static RootSelector root(IJsonObject root) {
    if (root.get("element") instanceof IJsonString element) {
      return new ElementRoot(element.getValue());
    }
    if (root.get("wsdl") instanceof IJsonObject wsdl) {
      return new WsdlRoot(
          new WsdlOperation(
              text(wsdl, "interface"),
              text(wsdl, "operation"),
              WsdlDirection.valueOf(upperCase(text(wsdl, "direction"))),
              optionalText(wsdl, "fault")));
    }
    return new JsonRoot();
  }

  private static TargetFieldMapping targetFieldMapping(IJsonValue value) {
    IJsonObject mapping = (IJsonObject) value;
    return new TargetFieldMapping(
        new AuthoringId(text(mapping, "id")),
        reference(mapping.get("result")),
        map(mapping.get("boxes"), AuthoringId::new, ModelReader::box),
        layout(mapping.get("layout")));
  }

  /** A target field mapping's layout; an absent one has no position. */
  private static Layout layout(IJsonValue value) {
    if (!(value instanceof IJsonObject layout)) {
      return new Layout(Optional.empty(), Map.of());
    }
    return new Layout(
        Optional.ofNullable(layout.get("target")).map(ModelReader::position),
        map(layout.get("boxes"), AuthoringId::new, ModelReader::position));
  }

  /** A box position; stage 1 has checked that both coordinates are integers in range. */
  private static BoxPosition position(IJsonValue value) {
    IJsonObject position = (IJsonObject) value;
    return new BoxPosition(coordinate(position, "x"), coordinate(position, "y"));
  }

  private static int coordinate(IJsonObject position, String name) {
    return ((IJsonNumber) position.get(name)).getValue().intValue();
  }

  private static Box box(IJsonValue value) {
    IJsonObject box = (IJsonObject) value;
    return switch (text(box, "kind")) {
      case "source" -> sourceFieldBox(text(box, "field"), optionalText(box, "context"));
      case "function" -> new StandardFunctionBox(text(box, "fn"), inputs(box), params(box));
      case "udf" -> new UdfBox(text(box, "udf"), inputs(box), params(box));
      // stage 1 lets no other kind through
      default ->
          new UnresolvedFunctionBox(
              text(box, "name"),
              Origin.valueOf(upperCase(text(box, "origin"))),
              inputs(box),
              params(box));
    };
  }

  /**
   * The parent is a source box's default context: a {@code context} naming it is dropped, as the
   * writer leaves it out, so that a loaded model equals the same model loaded after a save.
   */
  private static SourceFieldBox sourceFieldBox(String field, Optional<String> context) {
    return new SourceFieldBox(
        field, context.filter(given -> !FieldPathText.isParent(given, field)));
  }

  private static List<Optional<AuthoringId>> inputs(IJsonObject box) {
    List<Optional<AuthoringId>> inputs = new ArrayList<>();
    if (box.get("inputs") instanceof IJsonArray slots) {
      slots.getElements().forEach(slot -> inputs.add(reference(slot)));
    }
    return inputs;
  }

  private static Map<String, ParamValue> params(IJsonObject box) {
    return map(box.get("params"), Function.identity(), ModelReader::param);
  }

  private static ParamValue param(IJsonValue value) {
    if (value instanceof IJsonString text) {
      return new Text(text.getValue());
    }
    return new Table(map(value, Function.identity(), v -> ((IJsonString) v).getValue()));
  }

  private static UdfDeclaration udf(IJsonValue value) {
    IJsonObject udf = (IJsonObject) value;
    return new UdfDeclaration(
        ExecutionKind.valueOf(upperCase(text(udf, "executionKind"))),
        strings(udf.get("inputs")),
        map(
            udf.get("params"),
            Function.identity(),
            type -> UdfParamType.valueOf(upperCase(((IJsonString) type).getValue()))),
        text(udf, "source"));
  }

  /** A box id, or empty for {@code null}. */
  private static Optional<AuthoringId> reference(IJsonValue value) {
    return value instanceof IJsonString id
        ? Optional.of(new AuthoringId(id.getValue()))
        : Optional.empty();
  }

  /** An object's members, keys and values bound; an absent object is an empty map. */
  private static <K, V> Map<K, V> map(
      IJsonValue value, Function<String, K> key, Function<IJsonValue, V> bind) {
    Map<K, V> out = new LinkedHashMap<>();
    if (value instanceof IJsonObject object) {
      object.getProperties().forEach((k, v) -> out.put(key.apply(k.getValue()), bind.apply(v)));
    }
    return out;
  }

  /** An array of strings; an absent array is an empty list. */
  private static List<String> strings(IJsonValue value) {
    List<String> out = new ArrayList<>();
    if (value instanceof IJsonArray array) {
      array.getElements().forEach(item -> out.add(((IJsonString) item).getValue()));
    }
    return out;
  }

  private static String text(IJsonObject object, String name) {
    return ((IJsonString) object.get(name)).getValue();
  }

  private static Optional<String> optionalText(IJsonObject object, String name) {
    return object.get(name) instanceof IJsonString text
        ? Optional.of(text.getValue())
        : Optional.empty();
  }

  private static String upperCase(String value) {
    return value.toUpperCase(Locale.ROOT);
  }
}
