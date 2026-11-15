package io.flottila.mapping.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.RootSelector;
import io.flottila.mapping.structure.WsdlDirection;
import io.flottila.mapping.structure.WsdlOperation;
import io.flottila.mapping.structure.WsdlRoot;
import java.util.Locale;
import java.util.Optional;

/**
 * The service's one translator from root-selector JSON ({@code
 * mapping-model.schema.json#/$defs/rootSelector}) to the engine's {@link RootSelector}. Whether the
 * engine reads that root is the engine's diagnostic, not this translator's.
 */
final class RootSelectorJson {

  private RootSelectorJson() {}

  /**
   * @throws InvalidRequestException unless {@code root} is exactly one of the schema's three shapes
   */
  static RootSelector read(JsonNode root) throws InvalidRequestException {
    if (root.size() == 1) {
      if (root.path("element").isTextual()) {
        return new ElementRoot(root.path("element").textValue());
      }
      if ("$".equals(root.path("json").textValue())) {
        return new JsonRoot();
      }
      Optional<WsdlOperation> wsdl = wsdl(root.path("wsdl"));
      if (wsdl.isPresent()) {
        return new WsdlRoot(wsdl.get());
      }
    }
    throw new InvalidRequestException(
        "a root selector is {\"element\": <EQName>}, {\"wsdl\": {\"interface\", \"operation\","
            + " \"direction\", \"fault\" exactly when direction is fault}} or {\"json\": \"$\"}");
  }

  /**
   * The operation of {@code {"interface", "operation", "direction", "fault"?}}, all strings, with a
   * fault exactly when the direction is {@code fault} (ADR-0008, #96). Whether the names are an
   * EQName and NCNames is the engine's: one that is not names no interface or operation.
   */
  private static Optional<WsdlOperation> wsdl(JsonNode wsdl) {
    JsonNode fault = wsdl.path("fault");
    int members = fault.isTextual() ? 4 : 3;
    for (WsdlDirection direction : WsdlDirection.values()) {
      if (wsdl.size() == members
          && wsdl.path("interface").isTextual()
          && wsdl.path("operation").isTextual()
          && direction.name().toLowerCase(Locale.ROOT).equals(wsdl.path("direction").textValue())
          && fault.isTextual() == (direction == WsdlDirection.FAULT)) {
        return Optional.of(
            new WsdlOperation(
                wsdl.path("interface").textValue(),
                wsdl.path("operation").textValue(),
                direction,
                Optional.ofNullable(fault.textValue())));
      }
    }
    return Optional.empty();
  }
}
