package io.flottila.mapping.ir;

import java.util.List;

/**
 * The IR entry (ADR-0006 as refined by #26): the target root, created once with slot number 1, and
 * its content. It has no origin, because no authoring element creates the root.
 *
 * @param prefix the prefix of the root's namespace (ADR-0022); {@code ""} for a root without one
 * @param root the root element's name
 * @param rootSlot the {@code INTEGER} variable that holds the root's slot number in {@code content}
 * @param content the root's content, in target field order
 * @param input the format the input message is read in, which the source's root selector gives
 *     (ADR-0008 "JSON as a source at run time", #98)
 */
public record TargetDocument(
    String prefix, Name root, String rootSlot, List<Expr> content, InputFormat input) {

  public TargetDocument {
    content = List.copyOf(content);
  }

  /** The document of a mapping whose input is XML. */
  public TargetDocument(String prefix, Name root, String rootSlot, List<Expr> content) {
    this(prefix, root, rootSlot, content, InputFormat.XML);
  }
}
