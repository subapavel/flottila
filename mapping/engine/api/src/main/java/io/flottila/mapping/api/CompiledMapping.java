package io.flottila.mapping.api;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.runtime.ExecutableMapping;
import io.flottila.mapping.structure.Occurrence;
import java.util.Map;

/**
 * A compiled mapping, opaque to the facade's callers: only {@link EngineFacade#run} uses it. It is
 * immutable and may run any number of messages.
 */
public final class CompiledMapping {

  private final ExecutableMapping executable;
  private final Map<AuthoringId, Occurrence> occurrences;

  /**
   * @param occurrences the occurrence of each target field mapping's field, by its authoring id,
   *     unmodifiable
   */
  CompiledMapping(ExecutableMapping executable, Map<AuthoringId, Occurrence> occurrences) {
    this.executable = executable;
    this.occurrences = occurrences;
  }

  ExecutableMapping executable() {
    return executable;
  }

  /** The occurrence of each target field mapping's field, by its authoring id (#83). */
  Map<AuthoringId, Occurrence> occurrences() {
    return occurrences;
  }
}
