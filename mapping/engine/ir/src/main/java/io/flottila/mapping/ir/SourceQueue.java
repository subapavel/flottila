package io.flottila.mapping.ir;

import java.util.List;

/**
 * The queue of one source field in one context (ADR-0004, ADR-0006 as refined by #26), filled by
 * the projection: {@code po:source-queue(array:join(context ! [field ! string()]))} for a leaf
 * field, and {@code po:source-queue(array:join(context ! [field ! '']))} for a structure field, one
 * {@code ""} per occurrence (ADR-0008; #63). {@code po:source-queue} makes a queue whose context
 * never occurs one empty context (ADR-0004 as amended, F-17). An attribute's queue holds its value
 * per occurrence, and its field's last step reads {@code @name} (ADR-0006 since #102). A JSON
 * field's steps are the root {@code $} and then property names, without a namespace, and each item
 * of an array property is one occurrence of it (ADR-0008 "JSON as a source at run time", #98).
 *
 * @param field the field's steps from the root down: elements, and last the attribute for an
 *     attribute; or the JSON root and properties
 * @param contextDepth the context field as the number of steps from the root down to it; the root
 *     is 1, and the context is a proper ancestor of the field
 * @param structureField whether the field is a structure field, whose queue holds {@code ""} per
 *     occurrence instead of its text
 * @param attribute whether the field's last step is an attribute, never a structure field
 * @param arrays the depths of the field's steps that are JSON array properties, the root being 1,
 *     ascending; empty for an XML field
 * @param origin the source-field box
 */
public record SourceQueue(
    List<Name> field,
    int contextDepth,
    boolean structureField,
    boolean attribute,
    List<Integer> arrays,
    AuthoringId origin)
    implements Expr {

  public SourceQueue {
    field = List.copyOf(field);
    arrays = List.copyOf(arrays);
  }

  /** The queue of an XML field: an element, or an attribute when {@code attribute}. */
  public SourceQueue(
      List<Name> field,
      int contextDepth,
      boolean structureField,
      boolean attribute,
      AuthoringId origin) {
    this(field, contextDepth, structureField, attribute, List.of(), origin);
  }

  /** The queue of an element field. */
  public SourceQueue(
      List<Name> field, int contextDepth, boolean structureField, AuthoringId origin) {
    this(field, contextDepth, structureField, false, origin);
  }

  /** The queue of a leaf field, which holds its text per occurrence. */
  public SourceQueue(List<Name> field, int contextDepth, AuthoringId origin) {
    this(field, contextDepth, false, origin);
  }
}
