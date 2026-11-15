package io.flottila.mapping.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.flottila.mapping.mapping.MappingModel;
import io.flottila.mapping.mapping.StructureRef;
import io.flottila.mapping.structure.ElementRoot;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.WsdlRoot;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A mapping of the workspace (ADR-0014, ADR-0029). A member the stored model does not have, or
 * every one of them when the stored file is refused, is absent.
 *
 * @param id the path of its file in the workspace without {@code .mapping.json}
 * @param name the last segment of the id
 * @param packageName {@code package} on the wire, a Java keyword: the id's folder, {@code ""} at
 *     the workspace root
 * @param description the mapping's description
 * @param source the source root's name: an element's local name, a WSDL root's operation, or the
 *     last segment of a JSON root's first bundle file
 * @param target the target root's name, as the source's
 */
public record MappingSummary(
    @Schema(required = true) String id,
    @Schema(required = true) String name,
    @Schema(required = true) @JsonProperty("package") String packageName,
    @Schema(nullable = false) Optional<String> description,
    @Schema(nullable = false) Optional<String> source,
    @Schema(nullable = false) Optional<String> target) {

  /** The summary of the mapping {@code id}, whose stored model is {@code stored} if it loads. */
  static MappingSummary of(String id, Optional<MappingModel> stored) {
    int slash = id.lastIndexOf('/');
    return new MappingSummary(
        id,
        id.substring(slash + 1),
        // no slash: the empty folder of the root
        id.substring(0, Math.max(slash, 0)),
        stored.flatMap(MappingModel::description),
        stored.flatMap(MappingModel::source).map(MappingSummary::rootName),
        stored.flatMap(MappingModel::target).map(MappingSummary::rootName));
  }

  private static String rootName(StructureRef side) {
    return switch (side.root()) {
      // an EQName, Q{namespace}local, or a bare local name
      case ElementRoot element -> element.element().substring(element.element().indexOf('}') + 1);
      case WsdlRoot wsdl -> wsdl.wsdl().operation();
      case JsonRoot _ -> {
        String file = side.bundle().files().getFirst();
        yield file.substring(file.lastIndexOf('/') + 1);
      }
    };
  }
}
