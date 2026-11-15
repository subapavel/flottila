package io.flottila.mapping.mapping.internal;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.mapping.AtAuthoringId;
import io.flottila.mapping.mapping.Box;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.MappingDiagnostics;
import io.flottila.mapping.mapping.MappingModel;
import io.flottila.mapping.mapping.Severity;
import io.flottila.mapping.mapping.SourceFieldBox;
import io.flottila.mapping.mapping.StandardFunctionBox;
import io.flottila.mapping.mapping.TargetFieldMapping;
import io.flottila.mapping.mapping.UdfBox;
import io.flottila.mapping.mapping.UnresolvedFunctionBox;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 2 of loading, the model check (ADR-0011): ids unique across the mapping, references that
 * resolve inside their target field mapping, an acyclic graph, contexts that are proper ancestors
 * of their field, and declared UDFs. It needs no structure; it works on the path text.
 */
public final class ModelCheck {

  private final MappingModel model;
  private final Set<AuthoringId> allBoxes = new HashSet<>();
  private final List<Diagnostic> found = new ArrayList<>();

  private ModelCheck(MappingModel model) {
    this.model = model;
  }

  /** The stage-2 diagnostics of {@code model}, none when it passes. */
  public static List<Diagnostic> check(MappingModel model) {
    ModelCheck check = new ModelCheck(model);
    check.duplicates();
    model.targetFieldMappings().values().forEach(check::targetFieldMapping);
    return List.copyOf(check.found);
  }

  /** Target field mappings and boxes share one id space; a repeated id is reported once. */
  private void duplicates() {
    Map<AuthoringId, Integer> uses = new HashMap<>();
    for (TargetFieldMapping mapping : model.targetFieldMappings().values()) {
      uses.merge(mapping.id(), 1, Integer::sum);
      for (AuthoringId box : mapping.boxes().keySet()) {
        uses.merge(box, 1, Integer::sum);
        allBoxes.add(box);
      }
    }
    uses.forEach(
        (id, count) -> {
          if (count > 1) {
            add(
                MappingDiagnostics.DUPLICATE_AUTHORING_ID,
                id,
                "the id " + id.value() + " is used " + count + " times");
          }
        });
  }

  private void targetFieldMapping(TargetFieldMapping mapping) {
    mapping.result().ifPresent(result -> reference(mapping, mapping.id(), "result", result));
    mapping
        .boxes()
        .forEach(
            (id, box) -> {
              List<Optional<AuthoringId>> inputs = inputs(box);
              for (int k = 0; k < inputs.size(); k++) {
                String what = "input " + (k + 1);
                inputs.get(k).ifPresent(input -> reference(mapping, id, what, input));
              }
              switch (box) {
                case SourceFieldBox s ->
                    s.context()
                        .filter(context -> !FieldPathText.isProperAncestor(context, s.field()))
                        .ifPresent(
                            context ->
                                add(
                                    MappingDiagnostics.CONTEXT_NOT_ANCESTOR,
                                    id,
                                    "the context "
                                        + context
                                        + " is not a proper ancestor of "
                                        + s.field()));
                case UdfBox u when !model.udfs().containsKey(u.udf()) ->
                    add(
                        MappingDiagnostics.UNDECLARED_UDF,
                        id,
                        "the UDF " + u.udf() + " is not declared in udfs");
                case StandardFunctionBox _, UdfBox _, UnresolvedFunctionBox _ -> {
                  // no check of its own
                }
              }
            });
    new Cycles(mapping).report();
  }

  /** A reference by {@code consumer} must name a box of its own target field mapping. */
  private void reference(
      TargetFieldMapping mapping, AuthoringId consumer, String what, AuthoringId target) {
    if (mapping.boxes().containsKey(target)) {
      return;
    }
    if (allBoxes.contains(target)) {
      add(
          MappingDiagnostics.CROSS_TARGET_FIELD_REFERENCE,
          consumer,
          what + " names " + target.value() + ", a box of another target field mapping");
    } else {
      add(
          MappingDiagnostics.UNKNOWN_BOX_REFERENCE,
          consumer,
          what + " names " + target.value() + ", which is no box of this target field mapping");
    }
  }

  private static List<Optional<AuthoringId>> inputs(Box box) {
    return switch (box) {
      case SourceFieldBox _ -> List.of();
      case StandardFunctionBox f -> f.inputs();
      case UdfBox u -> u.inputs();
      case UnresolvedFunctionBox x -> x.inputs();
    };
  }

  private void add(String code, AuthoringId at, String message) {
    found.add(new Diagnostic(Severity.ERROR, code, new AtAuthoringId(at), message));
  }

  /**
   * The cycles of one target field mapping's graph, by Tarjan's strongly connected components. A
   * component of two or more boxes, or one box that feeds itself, is reported once, at its smallest
   * id.
   */
  private final class Cycles {

    /** The boxes by number. */
    private final List<AuthoringId> ids;

    /** For each box, the numbers of the boxes of this target field mapping it reads. */
    private final int[][] reads;

    /** Tarjan's numbers: the order of discovery, -1 before it, and the lowest number reached. */
    private final int[] index;

    private final int[] low;
    private final boolean[] onStack;

    /** The boxes of the components not yet closed, in discovery order. */
    private final int[] stack;

    private int stacked;
    private int discovered;

    /** The search path: a box, and how many of its inputs are followed. */
    private final int[] pathBox;

    private final int[] pathNext;

    Cycles(TargetFieldMapping mapping) {
      ids = List.copyOf(mapping.boxes().keySet());
      int n = ids.size();
      Map<AuthoringId, Integer> numbers = new HashMap<>();
      for (int k = 0; k < n; k++) {
        numbers.put(ids.get(k), k);
      }
      reads = new int[n][];
      for (int k = 0; k < n; k++) {
        reads[k] =
            inputs(mapping.boxes().get(ids.get(k))).stream()
                .flatMap(Optional::stream)
                .filter(numbers::containsKey)
                .mapToInt(numbers::get)
                .toArray();
      }
      index = new int[n];
      Arrays.fill(index, -1);
      low = new int[n];
      onStack = new boolean[n];
      stack = new int[n];
      pathBox = new int[n];
      pathNext = new int[n];
    }

    void report() {
      for (int box = 0; box < ids.size(); box++) {
        if (index[box] < 0) {
          search(box);
        }
      }
    }

    /**
     * Tarjan's depth-first search from {@code root}, on an explicit path instead of the call stack:
     * a chain of boxes is as deep as it is long.
     */
    private void search(int root) {
      int depth = 0;
      depth = enter(root, depth);
      while (depth > 0) {
        int box = pathBox[depth - 1];
        if (pathNext[depth - 1] < reads[box].length) {
          int input = reads[box][pathNext[depth - 1]++];
          if (index[input] < 0) {
            depth = enter(input, depth);
          } else if (onStack[input]) {
            low[box] = Math.min(low[box], index[input]);
          }
        } else {
          depth--;
          if (low[box] == index[box]) {
            component(box);
          }
          if (depth > 0) {
            int parent = pathBox[depth - 1];
            low[parent] = Math.min(low[parent], low[box]);
          }
        }
      }
    }

    /** Numbers {@code box}, stacks it and puts it on the path; the path's new depth. */
    private int enter(int box, int depth) {
      index[box] = discovered;
      low[box] = discovered++;
      stack[stacked++] = box;
      onStack[box] = true;
      pathBox[depth] = box;
      pathNext[depth] = 0;
      return depth + 1;
    }

    /** Pops the component whose root is {@code box} and reports it if it is a cycle. */
    private void component(int box) {
      List<AuthoringId> component = new ArrayList<>();
      int member;
      do {
        member = stack[--stacked];
        onStack[member] = false;
        component.add(ids.get(member));
      } while (member != box);
      if (component.size() > 1 || Arrays.stream(reads[box]).anyMatch(input -> input == box)) {
        AuthoringId smallest = Collections.min(component, Comparator.comparing(AuthoringId::value));
        add(
            MappingDiagnostics.BOX_CYCLE,
            smallest,
            "the boxes "
                + component.stream()
                    .map(AuthoringId::value)
                    .sorted()
                    .collect(Collectors.joining(", "))
                + " form a cycle");
      }
    }
  }
}
