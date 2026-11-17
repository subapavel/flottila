package dev.rail8.mapping.embed;

import java.io.Serial;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A mapping that {@link Mapping#compile} refuses, with every problem of the stage that refused it.
 */
public final class MappingRefusedException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * One reason a mapping is refused.
   *
   * @param code the engine's stable code, such as {@code fingerprint-mismatch}
   * @param at where it points: a JSON pointer into the mapping file ({@code ""} is the file
   *     itself), an authoring id such as {@code N1}, or a schema file's position as {@code
   *     file:line:column}
   * @param message what is wrong, in English
   */
  public record Problem(String code, String at, String message) {

    @Override
    public String toString() {
      return code + " at '" + at + "': " + message;
    }
  }

  /** Not serialised: a record of the problems is no part of the exception's serial form. */
  private final transient List<Problem> problems;

  MappingRefusedException(List<Problem> problems) {
    super(
        "the mapping is refused: "
            + problems.stream().map(Problem::toString).collect(Collectors.joining("; ")));
    this.problems = List.copyOf(problems);
  }

  /** At least one problem, in the engine's diagnostic order. */
  public List<Problem> problems() {
    return problems;
  }
}
