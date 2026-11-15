package io.flottila.mapping.mapping;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.WsdlDirection;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.WsdlRoot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builders for mapping models in tests. Maps keep the order the test writes them in, which is
 * deliberately not the canonical order, so the writer has to sort.
 */
final class Models {

  private Models() {}

  static AuthoringId id(String value) {
    return new AuthoringId(value);
  }

  /** Box references for {@code inputs}; a {@code null} argument is an unconnected input. */
  static List<Optional<AuthoringId>> inputs(String... ids) {
    List<Optional<AuthoringId>> out = new ArrayList<>();
    for (String i : ids) {
      out.add(Optional.ofNullable(i).map(AuthoringId::new));
    }
    return out;
  }

  /** A model with both its structures and no description. */
  static MappingModel model(
      StructureRef source,
      StructureRef target,
      Map<String, TargetFieldMapping> targetFieldMappings,
      Map<String, UdfDeclaration> udfs) {
    return model(
        Optional.empty(), Optional.of(source), Optional.of(target), targetFieldMappings, udfs);
  }

  /** A model as ADR-0029 allows it: a description, and either structure not chosen yet. */
  static MappingModel model(
      Optional<String> description,
      Optional<StructureRef> source,
      Optional<StructureRef> target,
      Map<String, TargetFieldMapping> targetFieldMappings,
      Map<String, UdfDeclaration> udfs) {
    return new MappingModel(1, description, source, target, targetFieldMappings, udfs);
  }

  static StructureRef elementRoot(String element, String fingerprint, String... files) {
    return new StructureRef(bundle(files), new ElementRoot(element), fingerprint);
  }

  static StructureRef wsdlRoot(
      String interfaceName,
      String operation,
      WsdlDirection direction,
      String faultOrNull,
      String fingerprint,
      String... files) {
    return new StructureRef(
        bundle(files),
        new WsdlRoot(
            new WsdlOperation(
                interfaceName, operation, direction, Optional.ofNullable(faultOrNull))),
        fingerprint);
  }

  static StructureRef jsonRoot(String fingerprint, String... files) {
    return new StructureRef(bundle(files), new JsonRoot(), fingerprint);
  }

  static SchemaBundle bundle(String... files) {
    return new SchemaBundle(List.of(files));
  }

  /** Alternating target field path and target field mapping. */
  static Map<String, TargetFieldMapping> targetFieldMappings(Object... pathThenMapping) {
    Map<String, TargetFieldMapping> out = new LinkedHashMap<>();
    for (int k = 0; k < pathThenMapping.length; k += 2) {
      out.put((String) pathThenMapping[k], (TargetFieldMapping) pathThenMapping[k + 1]);
    }
    return out;
  }

  /** A target field mapping without a layout. */
  static TargetFieldMapping targetFieldMapping(
      String id, String resultOrNull, Map<AuthoringId, Box> boxes) {
    return targetFieldMapping(id, resultOrNull, boxes, noLayout());
  }

  static TargetFieldMapping targetFieldMapping(
      String id, String resultOrNull, Map<AuthoringId, Box> boxes, Layout layout) {
    return new TargetFieldMapping(
        id(id), Optional.ofNullable(resultOrNull).map(AuthoringId::new), boxes, layout);
  }

  /** The layout of a target field mapping nobody has arranged: no position at all. */
  static Layout noLayout() {
    return new Layout(Optional.empty(), Map.of());
  }

  /**
   * A layout: the target field's position, or {@code null} when it has none, then alternating box
   * id and position.
   */
  static Layout layout(BoxPosition targetOrNull, Object... idThenPosition) {
    Map<AuthoringId, BoxPosition> boxes = new LinkedHashMap<>();
    for (int k = 0; k < idThenPosition.length; k += 2) {
      boxes.put(id((String) idThenPosition[k]), (BoxPosition) idThenPosition[k + 1]);
    }
    return new Layout(Optional.ofNullable(targetOrNull), boxes);
  }

  static BoxPosition at(int x, int y) {
    return new BoxPosition(x, y);
  }

  /** Alternating authoring id and box. */
  static Map<AuthoringId, Box> boxes(Object... idThenBox) {
    Map<AuthoringId, Box> out = new LinkedHashMap<>();
    for (int k = 0; k < idThenBox.length; k += 2) {
      out.put(id((String) idThenBox[k]), (Box) idThenBox[k + 1]);
    }
    return out;
  }

  static SourceFieldBox source(String field) {
    return new SourceFieldBox(field, Optional.empty());
  }

  static SourceFieldBox source(String field, String context) {
    return new SourceFieldBox(field, Optional.of(context));
  }

  static StandardFunctionBox function(
      String fn, List<Optional<AuthoringId>> inputs, Map<String, ParamValue> params) {
    return new StandardFunctionBox(fn, inputs, params);
  }

  static UdfBox udf(
      String udf, List<Optional<AuthoringId>> inputs, Map<String, ParamValue> params) {
    return new UdfBox(udf, inputs, params);
  }

  static UnresolvedFunctionBox unresolved(
      String name,
      Origin origin,
      List<Optional<AuthoringId>> inputs,
      Map<String, ParamValue> params) {
    return new UnresolvedFunctionBox(name, origin, inputs, params);
  }

  /** Alternating parameter name and value. */
  static Map<String, ParamValue> params(Object... nameThenValue) {
    Map<String, ParamValue> out = new LinkedHashMap<>();
    for (int k = 0; k < nameThenValue.length; k += 2) {
      out.put((String) nameThenValue[k], (ParamValue) nameThenValue[k + 1]);
    }
    return out;
  }

  static Map<String, ParamValue> noParams() {
    return Map.of();
  }

  static Text text(String value) {
    return new Text(value);
  }

  /** A table parameter from alternating key and value. */
  static Table table(String... keyThenValue) {
    Map<String, String> rows = new LinkedHashMap<>();
    for (int k = 0; k < keyThenValue.length; k += 2) {
      rows.put(keyThenValue[k], keyThenValue[k + 1]);
    }
    return new Table(rows);
  }

  /** Alternating UDF name and declaration. */
  static Map<String, UdfDeclaration> udfs(Object... nameThenDeclaration) {
    Map<String, UdfDeclaration> out = new LinkedHashMap<>();
    for (int k = 0; k < nameThenDeclaration.length; k += 2) {
      out.put((String) nameThenDeclaration[k], (UdfDeclaration) nameThenDeclaration[k + 1]);
    }
    return out;
  }

  static UdfDeclaration udfDeclaration(
      ExecutionKind executionKind,
      List<String> inputs,
      Map<String, UdfParamType> params,
      String source) {
    return new UdfDeclaration(executionKind, inputs, params, source);
  }

  /** Alternating UDF parameter name and type. */
  static Map<String, UdfParamType> udfParams(Object... nameThenType) {
    Map<String, UdfParamType> out = new LinkedHashMap<>();
    for (int k = 0; k < nameThenType.length; k += 2) {
      out.put((String) nameThenType[k], (UdfParamType) nameThenType[k + 1]);
    }
    return out;
  }

  static List<String> names(String... names) {
    return Arrays.asList(names);
  }
}
