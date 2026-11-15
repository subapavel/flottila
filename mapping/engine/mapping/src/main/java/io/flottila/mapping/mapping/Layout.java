package io.flottila.mapping.mapping;

import io.flottila.mapping.ir.AuthoringId;
import java.util.Map;
import java.util.Optional;

/**
 * The positions of one target field mapping's boxes and its target, as the studio laid them out
 * (ADR-0029). The engine ignores it; a layout without any position is written as no layout.
 *
 * @param target the target's position, or empty when it has none
 * @param boxes the boxes' positions, keyed by authoring id; a box without a position has no entry
 */
public record Layout(Optional<BoxPosition> target, Map<AuthoringId, BoxPosition> boxes) {

  public Layout {
    boxes = Map.copyOf(boxes);
  }
}
