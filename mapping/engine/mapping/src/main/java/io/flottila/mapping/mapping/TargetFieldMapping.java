package io.flottila.mapping.mapping;

import io.flottila.mapping.ir.AuthoringId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The function graph that produces the values of one target field (ADR-0010).
 *
 * @param id the target field mapping's authoring id
 * @param result the box whose output feeds the field, or empty while it is not wired (F-2)
 * @param boxes the boxes of this target field mapping, keyed by authoring id
 * @param layout the positions of its target and boxes (ADR-0029); a position whose box is not one
 *     of {@code boxes} is dropped, so no target field mapping holds a stale one
 */
public record TargetFieldMapping(
    AuthoringId id, Optional<AuthoringId> result, Map<AuthoringId, Box> boxes, Layout layout) {

  public TargetFieldMapping {
    boxes = Map.copyOf(boxes);
    Map<AuthoringId, BoxPosition> own = new HashMap<>(layout.boxes());
    own.keySet().retainAll(boxes.keySet());
    layout = new Layout(layout.target(), own);
  }
}
