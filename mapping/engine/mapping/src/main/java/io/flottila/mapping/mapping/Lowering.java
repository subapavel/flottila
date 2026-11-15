package io.flottila.mapping.mapping;

import io.flottila.mapping.ir.AtomicToQueue;
import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AttributeConstructor;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.InputFormat;
import io.flottila.mapping.ir.IrType;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Lift;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetRepeat;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.mapping.internal.FieldPathText;
import io.flottila.mapping.structure.Field;
import io.flottila.mapping.structure.FieldPath;
import io.flottila.mapping.structure.JsonRoot;
import io.flottila.mapping.structure.Occurrence;
import io.flottila.mapping.structure.Refusal;
import io.flottila.mapping.structure.Structure;
import io.flottila.mapping.xdm.TableText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * Validation stage 3 and lowering of a mapping model to IR (ADR-0006, ADR-0011, ADR-0022; #26). A
 * target field mapping becomes {@code TargetSlots} over the queue its {@code result} gives, in its
 * parent's element: the root's, created once, or a structure field's (ADR-0006 since #52). One
 * whose {@code result} gives a single value, a generating function, becomes {@code TargetRepeat} of
 * {@code max(1, minOccurs)} copies at the target field mapping, the value in a leaf's text or, on a
 * structure field, the {@code TargetRepeat}'s value (ADR-0006 since #83). One whose {@code result}
 * gives a generating block, the queue of a function the catalogue marks generating, such as {@code
 * copyValue}, becomes that {@code TargetRepeat} with the block as its value and its value variable
 * {@code value<d>} in a leaf's text or an attribute's value (#217, F-46). An unmapped structure
 * field whose effective occurrence is {@code n..n} with {@code n > 0} becomes {@code TargetRepeat}
 * of n copies with no value, whatever is mapped below it, an unmapped optional field is left out
 * with its subtree (ADR-0006 "Target fields"; #63), and any other unmapped field is refused. The
 * slot-number variable of a field at depth d is {@code slot<d>}, a mapped field's value variable
 * {@code value<d>}, and a field reads its parent's slot number, the root's {@code root} or the
 * parent's {@code slot<d - 1>}. A leaf's element holds its attributes and then its value, a
 * structure field's its children in target field order, attributes first; an attribute is a leaf,
 * whose {@code AttributeConstructor} holds its value (ADR-0006 since #102).
 *
 * <p>A box lowers to a queue or a single value (ADR-0006 since #83). A source-field box gives its
 * {@code SourceQueue}, of a leaf or of a structure field (ADR-0008; #63), or of an attribute, in
 * its element's context by default (ADR-0007, #102), or of a JSON property, with the depths of the
 * array properties on its path; a JSON root makes the document's input JSON, any other root XML
 * (ADR-0008 "JSON as a source at run time", #98). A standard function box whose function the
 * catalogue holds gives, by its execution kind, a {@code FunCall} over its inputs' queues, a single
 * value as {@code AtomicToQueue}, and then its params as {@code Literal}s for a context or queue
 * function. For a single-value function it gives the atomic {@code FunCall} over its inputs and
 * then the params when every input is a single value, as for {@code constant}, which has none;
 * otherwise a {@code Lift} over its queue inputs whose body is that {@code FunCall} over their slot
 * variables and the single values (ADR-0006, ADR-0025). A generating block is the queue it is at a
 * context or queue function's input (#217). A param the box leaves out is its catalogue default,
 * and a table param's {@code Literal} is its {@code TableText} (ADR-0026, #83). A box that feeds
 * several inputs is lowered once per use.
 *
 * <p>Stage 3 reports, each at its authoring id:
 *
 * <ul>
 *   <li>a source or target field path the structure lacks below every same-step sibling on it:
 *       {@code orphaned-reference};
 *   <li>a source or target field path through a refused field (ADR-0008 Shape (#92)), with the
 *       refusal nearest the root: its reason's code, {@code ambiguous-field-path}, or, on a target
 *       path only, {@code interleaved-target-group} (#94) or {@code abstract-target-element} (#95),
 *       which a source path passes through; at {@code /targetFieldMappings/<field path>}, once per
 *       path, an unmapped refused target field with {@code minOccurs > 0} under a parent that is
 *       created: the same;
 *   <li>a source field that is the root (#62, #63, #102), or a target field that is the root, or
 *       that stands under an unmapped parent which is neither {@code n..n} with {@code n > 0} nor
 *       optional, so that PO requires it to be mapped (#63): {@code unsupported-field}; at {@code
 *       /target}, an unmapped field that would be created beyond the recursion cap (ADR-0008):
 *       {@code unsupported-field}; at {@code /targetFieldMappings/<field path>}, an unmapped
 *       structure field with {@code 0 < minOccurs < maxOccurs} under a parent that is created, as
 *       PO requires its mapping: {@code unsupported-field};
 *   <li>a {@code result} of {@code null}: {@code unconnected-target-field};
 *   <li>at {@code /targetFieldMappings/<field path>}, a target leaf with {@code minOccurs > 0} that
 *       has no target field mapping and stands under a parent that is created, the root, a mapped
 *       field or an unmapped {@code n..n} one (ADR-0006, #65): {@code unmapped-mandatory-leaf};
 *   <li>a UDF box, or a function box whose function the catalogue does not hold yet: {@code
 *       unsupported-box-kind}; an unresolved box: {@code unresolved-function};
 *   <li>a function box against its catalogue entry: more inputs than the entry has, {@code
 *       wrong-arity}; an input slot of the entry that is {@code null} or absent, {@code
 *       unconnected-input}; a param key the entry lacks, {@code unknown-parameter}; a table for a
 *       string param or a string for a table param, {@code wrong-parameter-type}; a string outside
 *       the values of a param that lists them, or a pattern the formatting core cannot translate
 *       for a param with a syntax (ADR-0026, #74), {@code invalid-parameter-value};
 *   <li>a single-value function box, once its inputs lower, per input that a generating block
 *       feeds: {@code unsupported-generating-input} (#217, F-46).
 * </ul>
 *
 * <p>Only the boxes {@code result} reaches are checked: a box it does not reach is not, and the
 * inputs of a function box refused at the box are not either. A refused target field's boxes are
 * not checked, and after a function box's first refused input its later inputs are not. One pass
 * reports all of these (#91): the target walk, which applies the unmapped rules, runs whether or
 * not every target field mapping lowers, and a mapped field whose mapping does not lower counts as
 * mapped in it.
 */
public final class Lowering {

  /** The variable that holds the root's slot number. */
  private static final String ROOT_SLOT = "root";

  /** The variable, before its depth, that holds a slot's number in its {@code TargetSlots}. */
  private static final String SLOT = "slot";

  /** The variable, before its depth, that holds a slot's value in its {@code TargetSlots}. */
  private static final String VALUE = "value";

  /** The depth of a field directly under the root, whose parent's slot number is the root's. */
  private static final int UNDER_ROOT = 2;

  /** The most times a recursive path may repeat one type (ADR-0008). */
  private static final int RECURSION_CAP = 16;

  private static final QueueType QUEUE = new QueueType();

  /** A target field mapping that lowers: its field, and the queue or single value it gives. */
  private record Link(Field field, AuthoringId mapping, Expr value) {}

  /**
   * The fields a field path names, from the root down: at each step the first same-step sibling
   * below which the rest of the path stands (#92).
   */
  private record Named(List<Field> chain) {

    /** The path of the field at {@code depth} on the chain, the root's at 1. */
    FieldPath path(int depth) {
      return new FieldPath(chain.subList(0, depth).stream().map(Field::step).toList());
    }

    FieldPath path() {
      return path(chain.size());
    }

    Field field() {
      return chain.getLast();
    }

    /**
     * The refused field on the chain nearest the root, if any (ADR-0008 Shape (#92)); on a source
     * path, a refusal that holds in a target only refuses nothing (#94, #95).
     */
    Optional<RefusedField> refused(boolean inTarget) {
      return IntStream.rangeClosed(1, chain.size())
          .filter(
              depth ->
                  chain
                      .get(depth - 1)
                      .refusal()
                      .filter(refusal -> inTarget || !refusal.reason().targetOnly())
                      .isPresent())
          .mapToObj(
              depth -> new RefusedField(path(depth), chain.get(depth - 1).refusal().orElseThrow()))
          .findFirst();
    }
  }

  /** A refused field on a named path: its path and its refusal. */
  private record RefusedField(FieldPath path, Refusal refusal) {}

  private final MappingModel model;
  private final Structure source;
  private final Structure target;
  private final List<Diagnostic> found = new ArrayList<>();

  /** The target fields that have a target field mapping, whether or not it lowers. */
  private final Set<FieldPath> mapped = new HashSet<>();

  /** Whether the target walk met an unmapped field beyond the recursion cap, which stops it. */
  private boolean endless;

  /** The prefix of each target namespace, in the order first met (ADR-0022). */
  private final Map<String, String> prefixes = new HashMap<>();

  private Lowering(MappingModel model, Structure source, Structure target) {
    this.model = model;
    this.source = source;
    this.target = target;
  }

  /**
   * Lowers {@code model} over the structures its source and target refer to; the model has both
   * (ADR-0029: the caller refuses one without first). A model whose target is a JSON root is
   * refused with {@code json-target-unsupported} alone (ADR-0008, #97).
   *
   * @return the IR, which the IR verifier has accepted, or every stage-3 diagnostic
   */
  public static LowerResult lower(MappingModel model, Structure source, Structure target) {
    return new Lowering(model, source, target).lower();
  }

  /**
   * The occurrence of the target field of each target field mapping of {@code model} whose path
   * names a field of {@code target}, by the mapping's authoring id: the min and max a test run's
   * trace shows for a generating function's field, which its {@code TargetRepeat} does not hold
   * (ADR-0006 as amended by #83, GLOSSARY "Trace").
   */
  public static Map<AuthoringId, Occurrence> occurrences(MappingModel model, Structure target) {
    Map<AuthoringId, Occurrence> occurrences = new HashMap<>();
    model
        .targetFieldMappings()
        .forEach(
            (path, mapping) ->
                resolve(target, path)
                    .ifPresent(named -> occurrences.put(mapping.id(), named.field().occurrence())));
    return Map.copyOf(occurrences);
  }

  /**
   * The {@code unreachable-box} warnings of a model that passed stages 1 and 2 (ADR-0011,
   * ADR-0013): one at each box that its target field mapping's {@code result} does not reach
   * through the boxes' inputs, so at every box of a target field mapping whose {@code result} is
   * {@code null}. Lowering neither checks nor lowers such a box, and a warning never refuses the
   * model. The order is the caller's to fix.
   */
  public static List<Diagnostic> unreachable(MappingModel model) {
    List<Diagnostic> warnings = new ArrayList<>();
    for (TargetFieldMapping mapping : model.targetFieldMappings().values()) {
      Set<AuthoringId> reached = new HashSet<>();
      Deque<AuthoringId> pending = new ArrayDeque<>();
      mapping.result().ifPresent(pending::push);
      while (!pending.isEmpty()) {
        AuthoringId id = pending.pop();
        // a box that feeds several inputs is followed once
        if (reached.add(id)) {
          inputs(mapping.boxes().get(id)).forEach(input -> input.ifPresent(pending::push));
        }
      }
      for (AuthoringId box : mapping.boxes().keySet()) {
        if (!reached.contains(box)) {
          warnings.add(
              new Diagnostic(
                  Severity.WARNING,
                  MappingDiagnostics.UNREACHABLE_BOX,
                  new AtAuthoringId(box),
                  "the result of the target field mapping "
                      + mapping.id().value()
                      + " does not reach this box, so compile ignores it"));
        }
      }
    }
    return List.copyOf(warnings);
  }

  /** The input slots of {@code box}; a source-field box has none. */
  private static List<Optional<AuthoringId>> inputs(Box box) {
    return switch (box) {
      case SourceFieldBox _ -> List.of();
      case StandardFunctionBox f -> f.inputs();
      case UdfBox u -> u.inputs();
      case UnresolvedFunctionBox x -> x.inputs();
    };
  }

  private LowerResult lower() {
    if (model.target().orElseThrow().root() instanceof JsonRoot) {
      // ADR-0008 "JSON as a target": nothing is lowered onto a target that cannot be written
      return new Refused(
          List.of(
              new Diagnostic(
                  Severity.ERROR,
                  MappingDiagnostics.JSON_TARGET_UNSUPPORTED,
                  new AtPointer("/target"),
                  "the target is a JSON root: a JSON Schema loads as a target, but JSON output"
                      + " comes after milestone 1")));
    }
    // the fields that are mapped, for the parent check and the target walk
    for (String path : model.targetFieldMappings().keySet()) {
      resolve(target, path).ifPresent(named -> mapped.add(named.path()));
    }
    Map<FieldPath, Link> links = new HashMap<>();
    model.targetFieldMappings().forEach((path, mapping) -> link(path, mapping, links));
    FieldPath.Step root = target.root().step();
    // the root's namespace is met first, so it is ns0
    String rootPrefix = prefix(root.namespace());
    // the walk goes on past a target field mapping that did not lower, so that one compile reports
    // every stage-3 diagnostic (#91)
    List<Expr> content = children(target.rootPath(), List.of(target.root()), links);
    if (!found.isEmpty()) {
      // a target field mapping that does not lower, an unmapped mandatory leaf, an unmapped
      // structure field that needs a mapping or an unmapped field beyond the recursion cap
      return new Refused(found);
    }
    // the input format follows the source's root selector, never the input (ADR-0008, #98)
    InputFormat input =
        model.source().orElseThrow().root() instanceof JsonRoot
            ? InputFormat.JSON
            : InputFormat.XML;
    TargetDocument document =
        new TargetDocument(
            rootPrefix, new Name(root.namespace(), root.localName()), ROOT_SLOT, content, input);
    // the IR is re-checked after lowering (ADR-0006); a refusal here is a bug of this class
    IrVerifier.verify(document);
    return new Lowered(document);
  }

  /**
   * Adds the link of the target field at {@code path} to {@code links}, or reports why it does not
   * lower: its field first, then its {@code result}.
   */
  private void link(String path, TargetFieldMapping mapping, Map<FieldPath, Link> links) {
    targetField(path, mapping.id())
        .ifPresent(
            named ->
                queue(mapping)
                    .ifPresent(
                        queue ->
                            links.put(named.path(), new Link(named.field(), mapping.id(), queue))));
  }

  /**
   * The target element or attribute (#102) below the root that {@code text} names, or empty once
   * the reason it names none is reported at the target field mapping {@code at}. Its parents on the
   * named chain are walked from the root down: a mapped parent and an unmapped {@code n..n} one
   * ({@code n > 0}) are created, an unmapped optional one is left out with the field in its
   * subtree, which is accepted, and an unmapped one with {@code 0 < min < max} is refused, because
   * PO requires its mapping (#2 §2).
   */
  private Optional<Named> targetField(String text, AuthoringId at) {
    return named(target, text, at, true)
        .flatMap(
            named -> {
              FieldPath path = named.path();
              if (path.steps().size() < UNDER_ROOT) {
                report(
                    MappingDiagnostics.UNSUPPORTED_FIELD,
                    at,
                    text
                        + " is not a target field this engine maps yet: an element or attribute"
                        + " below the root");
                return Optional.empty();
              }
              for (FieldPath parent : parents(path)) {
                Occurrence occurrence = named.chain().get(parent.steps().size() - 1).occurrence();
                if (mapped.contains(parent) || repeated(occurrence)) {
                  continue;
                }
                if (occurrence.min() == 0) {
                  // left out with its subtree, so the field is never created
                  break;
                }
                report(
                    MappingDiagnostics.UNSUPPORTED_FIELD,
                    at,
                    text
                        + " stands under the unmapped "
                        + parent
                        + " ("
                        + occurrence
                        + "), which needs a target field mapping of its own");
                return Optional.empty();
              }
              return Optional.of(named);
            });
  }

  /** The fields between the root and {@code path}, both left out, from the root down. */
  private static List<FieldPath> parents(FieldPath path) {
    List<FieldPath.Step> steps = path.steps();
    return IntStream.range(UNDER_ROOT, steps.size())
        .mapToObj(depth -> new FieldPath(steps.subList(0, depth)))
        .toList();
  }

  /** Whether an unmapped field of {@code occurrence} is created n times: {@code n..n}, n > 0. */
  private static boolean repeated(Occurrence occurrence) {
    return occurrence.min() > 0 && occurrence.min() == occurrence.max();
  }

  /**
   * The source field below the root that {@code text} names, an element, a leaf or a structure
   * field, at any depth (#62, #63), or an attribute (#102), or empty once the reason it names none
   * is reported at the box {@code at}.
   */
  private Optional<Named> sourceField(String text, AuthoringId at) {
    return named(source, text, at, false)
        .flatMap(
            named -> {
              if (named.chain().size() < UNDER_ROOT) {
                report(
                    MappingDiagnostics.UNSUPPORTED_FIELD,
                    at,
                    text
                        + " is not a source field this engine maps yet: an element or attribute"
                        + " below the root");
                return Optional.empty();
              }
              return Optional.of(named);
            });
  }

  /**
   * The fields {@code text} names in {@code structure}, or empty once the reason it names none is
   * reported at {@code at}: one check for target fields and source fields. A path the structure
   * lacks is {@code orphaned-reference}, and a path through a refused field is refused with the
   * refusal nearest the root (ADR-0008 Shape (#92)), counting a refusal that holds in a target only
   * when {@code inTarget} (#94, #95).
   */
  private Optional<Named> named(
      Structure structure, String text, AuthoringId at, boolean inTarget) {
    Optional<Named> named = resolve(structure, text);
    if (named.isEmpty()) {
      report(MappingDiagnostics.ORPHANED_REFERENCE, at, "the structure has no field " + text);
      return Optional.empty();
    }
    Optional<RefusedField> refused = named.get().refused(inTarget);
    if (refused.isPresent()) {
      found.add(refusal(refused.get().path(), refused.get().refusal(), new AtAuthoringId(at)));
      return Optional.empty();
    }
    return named;
  }

  /**
   * The fields {@code text} names, matched step by step against the steps of {@code structure},
   * which spell each step one way (ADR-0007); empty when there is no such field. Same-step siblings
   * share one path, so a step is matched below each of them in turn: a path that only the second
   * holds resolves through it, and through its refusal if it carries one (ADR-0008 Shape (#92)).
   */
  private static Optional<Named> resolve(Structure structure, String text) {
    List<String> steps = FieldPathText.steps(text);
    if (!steps.get(1).equals(structure.root().step().toString())) {
      return Optional.empty();
    }
    return held(structure, List.of(structure.root()), steps, 2, new HashSet<>()).map(Named::new);
  }

  /** A structure type below which the steps from {@code step} on stand under no field. */
  private record Unheld(String type, int step) {}

  /**
   * {@code chain} continued by the fields of {@code steps} from {@code step} on below its last
   * field, the first same-step sibling below which the rest of the steps stand at each step; empty
   * when no sibling holds them. Whether they stand depends only on the last field's type, so a type
   * and step that failed once are in {@code failed} and never tried again: each is tried at most
   * once, where trying every chain of siblings is exponential in the path's length (#89).
   */
  private static Optional<List<Field>> held(
      Structure structure, List<Field> chain, List<String> steps, int step, Set<Unheld> failed) {
    if (step == steps.size()) {
      return Optional.of(chain);
    }
    Optional<String> type = chain.getLast().type();
    if (type.isEmpty() || failed.contains(new Unheld(type.get(), step))) {
      return Optional.empty();
    }
    for (Field child : structure.types().get(type.get()).fields()) {
      if (child.step().toString().equals(steps.get(step))) {
        Optional<List<Field>> below =
            held(structure, append(chain, child), steps, step + 1, failed);
        if (below.isPresent()) {
          return below;
        }
      }
    }
    failed.add(new Unheld(type.get(), step));
    return Optional.empty();
  }

  /** {@code chain} with {@code field} after its last field. */
  private static List<Field> append(List<Field> chain, Field field) {
    List<Field> longer = new ArrayList<>(chain);
    longer.add(field);
    return longer;
  }

  /**
   * The child fields of {@code field} in {@code structure}, each same-step sibling with its own
   * field, which {@link Structure#field} does not give (#92), attributes first; none for a field
   * without a type, such as a leaf without attributes.
   */
  private static List<Field> childFields(Structure structure, Field field) {
    return field.type().map(id -> structure.types().get(id).fields()).orElse(List.of());
  }

  /**
   * The diagnostic of a field a mapping uses or must create through the refused field at {@code
   * path}, at {@code location}: its reason's code, and a message that names the declaration that
   * causes it (ADR-0008 "Refused when a mapping uses the field").
   */
  private static Diagnostic refusal(FieldPath path, Refusal refusal, Location location) {
    return switch (refusal.reason()) {
      case AMBIGUOUS_FIELD_PATH ->
          new Diagnostic(
              Severity.ERROR,
              MappingDiagnostics.AMBIGUOUS_FIELD_PATH,
              location,
              path
                  + " is declared again under its parent at "
                  + refusal.file()
                  + " "
                  + refusal.line()
                  + ":"
                  + refusal.column()
                  + "; a field path cannot tell the siblings apart");
      case INTERLEAVED_TARGET_GROUP ->
          new Diagnostic(
              Severity.ERROR,
              MappingDiagnostics.INTERLEAVED_TARGET_GROUP,
              location,
              path
                  + " stands in a group of two or more particles that repeats, declared at "
                  + refusal.file()
                  + " "
                  + refusal.line()
                  + ":"
                  + refusal.column()
                  + "; streamed output cannot interleave its fields");
      case ABSTRACT_TARGET_ELEMENT ->
          new Diagnostic(
              Severity.ERROR,
              MappingDiagnostics.ABSTRACT_TARGET_ELEMENT,
              location,
              path
                  + " is declared abstract at "
                  + refusal.file()
                  + " "
                  + refusal.line()
                  + ":"
                  + refusal.column()
                  + "; a target cannot write an abstract element or an element of an abstract"
                  + " type");
    };
  }

  /** The queue {@code mapping}'s {@code result} gives, or empty once its refusal is reported. */
  private Optional<Expr> queue(TargetFieldMapping mapping) {
    if (mapping.result().isEmpty()) {
      report(
          MappingDiagnostics.UNCONNECTED_TARGET_FIELD,
          mapping.id(),
          "the target field mapping's result is not connected");
      return Optional.empty();
    }
    return queue(mapping, mapping.result().get());
  }

  /** The queue box {@code id} of {@code mapping} gives, or empty once its refusal is reported. */
  private Optional<Expr> queue(TargetFieldMapping mapping, AuthoringId id) {
    return switch (mapping.boxes().get(id)) {
      case SourceFieldBox box ->
          sourceField(box.field(), id)
              .map(
                  named ->
                      new SourceQueue(
                          names(named.path()),
                          contextDepth(box, named.path()),
                          named.field().valueType().isEmpty(),
                          attribute(named.path().steps().getLast()),
                          arrays(named),
                          id));
      case StandardFunctionBox box -> call(mapping, id, box);
      case UdfBox box -> unsupportedBox(id, "the UDF " + box.udf());
      case UnresolvedFunctionBox box -> {
        report(
            MappingDiagnostics.UNRESOLVED_FUNCTION,
            id,
            "the function " + box.name() + " is neither in the function catalogue nor a UDF");
        yield Optional.empty();
      }
    };
  }

  private Optional<Expr> unsupportedBox(AuthoringId id, String what) {
    report(MappingDiagnostics.UNSUPPORTED_BOX_KIND, id, what + " cannot be compiled yet");
    return Optional.empty();
  }

  /**
   * The call of {@code box}'s function over its inputs' values and then its params, or empty once
   * its refusals are reported. Every defect of the box against its catalogue entry is reported at
   * the box before any input is lowered; a generating block at a single-value function's input,
   * once they are (#217). Each param is a {@code Literal} at the box: the string the box gives, or
   * a table's {@code TableText} (ADR-0026, #83), or the entry's default when the box leaves the
   * param out (ADR-0025).
   */
  private Optional<Expr> call(TargetFieldMapping mapping, AuthoringId id, StandardFunctionBox box) {
    Optional<FunctionCatalogue.Entry> catalogued = FunctionCatalogue.entry(box.fn());
    if (catalogued.isEmpty()) {
      report(
          MappingDiagnostics.UNSUPPORTED_BOX_KIND,
          id,
          "the function " + box.fn() + " is not in the catalogue yet, so it cannot be compiled");
      return Optional.empty();
    }
    FunctionCatalogue.Entry entry = catalogued.get();
    List<String> inputs = entry.inputs();
    List<Optional<AuthoringId>> slots = box.inputs();
    int reported = found.size();
    if (slots.size() > inputs.size()) {
      report(
          MappingDiagnostics.WRONG_ARITY,
          id,
          box.fn() + " has the inputs " + inputs + ", and the box gives it " + slots.size());
    }
    for (int i = 0; i < inputs.size(); i++) {
      if (i >= slots.size() || slots.get(i).isEmpty()) {
        report(
            MappingDiagnostics.UNCONNECTED_INPUT,
            id,
            "the input " + inputs.get(i) + " of " + box.fn() + " is not connected");
      }
    }
    for (String key : new TreeSet<>(box.params().keySet())) {
      param(id, box, entry, key);
    }
    List<Expr> params = new ArrayList<>();
    for (FunctionCatalogue.Param param : entry.params()) {
      // the box's value, or the default when the box leaves the param out; a value of the other
      // kind is reported above as wrong-parameter-type, so the call is not lowered
      ParamValue value = box.params().getOrDefault(param.name(), param.fallback());
      params.add(
          new Literal(
              switch (value) {
                case Text(String text) -> text;
                case Table(Map<String, String> rows) -> TableText.write(rows);
              },
              id));
    }
    if (found.size() > reported) {
      return Optional.empty();
    }
    List<Expr> values = new ArrayList<>();
    for (Optional<AuthoringId> slot : slots) {
      Optional<Expr> arg = queue(mapping, slot.orElseThrow());
      if (arg.isEmpty()) {
        return Optional.empty();
      }
      values.add(arg.get());
    }
    return switch (entry.executionKind()) {
      case SINGLE ->
          takesNoGeneratingBlock(id, box.fn(), inputs, values)
              ? Optional.of(singleValueCall(box.fn(), values, params, id))
              : Optional.empty();
      case CONTEXT, QUEUE -> Optional.of(contextCall(box.fn(), values, params, id));
    };
  }

  /**
   * Whether no input of the single-value function box {@code id} is a generating block; else each
   * such input is reported at the box as {@code unsupported-generating-input}: the block is one
   * value for every parent occurrence, which an input that takes one value per slot cannot read
   * (ADR-0006, F-46). SAP PO accepts that graph, so it is the deviation the catalogue records.
   *
   * @param inputs the names of {@code fn}'s inputs, in order
   * @param values the lowered values of its inputs, in the same order
   */
  private boolean takesNoGeneratingBlock(
      AuthoringId id, String fn, List<String> inputs, List<Expr> values) {
    boolean none = true;
    for (int i = 0; i < values.size(); i++) {
      if (generatingBlock(values.get(i))) {
        none = false;
        report(
            MappingDiagnostics.UNSUPPORTED_GENERATING_INPUT,
            id,
            "the input "
                + inputs.get(i)
                + " of "
                + fn
                + " reads the result of the function box "
                + values.get(i).origin().value()
                + ", one value for every parent occurrence, which a function of single values"
                + " cannot take");
      }
    }
    return none;
  }

  /**
   * Whether {@code value}, a box's lowered value, is a single value, an atomic {@code FunCall}
   * (ADR-0006 since #83), rather than a queue.
   */
  private static boolean single(Expr value) {
    return value instanceof FunCall c && c.function().resultType() instanceof AtomicType;
  }

  /**
   * Whether {@code value}, a box's lowered value, is a generating block: a {@code FunCall} whose
   * catalogue entry is generating, which only a queue function's is (ADR-0006, F-46), such as
   * {@code copyValue}'s. Every {@code FunCall} lowering writes is of a catalogue function.
   */
  private static boolean generatingBlock(Expr value) {
    return value instanceof FunCall c
        && FunctionCatalogue.entry(c.function().name()).orElseThrow().generating();
  }

  /**
   * The {@code FunCall} of context or queue function {@code fn}: its inputs, each a queue and a
   * single value as {@code AtomicToQueue} at the value's box (ADR-0006 since #83), then its params.
   */
  private static FunCall contextCall(
      String fn, List<Expr> inputs, List<Expr> params, AuthoringId id) {
    List<Expr> args = new ArrayList<>();
    for (Expr input : inputs) {
      args.add(single(input) ? new AtomicToQueue(input, input.origin()) : input);
    }
    args.addAll(params);
    List<IrType> types = new ArrayList<>(Collections.nCopies(inputs.size(), QUEUE));
    types.addAll(Collections.nCopies(params.size(), AtomicType.STRING));
    return new FunCall(new FunctionRef(fn, types, QUEUE), args, id);
  }

  /**
   * The call of single-value function {@code fn} (ADR-0006 since #51 and #83). Over inputs that are
   * all single values, none for {@code constant}, it is the atomic {@code FunCall} over them and
   * then the params. Otherwise it is the {@code Lift} over its queue inputs, one slot variable
   * {@code in1} … per queue, whose body is that {@code FunCall} with each queue input's slot
   * variable in its place, so a single value applies to every slot of every context. Each node
   * carries the box's id.
   */
  private static Expr singleValueCall(
      String fn, List<Expr> inputs, List<Expr> params, AuthoringId id) {
    List<Expr> queues = new ArrayList<>();
    List<String> slotVars = new ArrayList<>();
    List<Expr> args = new ArrayList<>();
    for (Expr input : inputs) {
      if (single(input)) {
        args.add(input);
      } else {
        queues.add(input);
        String slotVar = "in" + queues.size();
        slotVars.add(slotVar);
        args.add(new VarRef(slotVar, AtomicType.STRING, id));
      }
    }
    args.addAll(params);
    FunctionRef function =
        new FunctionRef(fn, Collections.nCopies(args.size(), AtomicType.STRING), AtomicType.STRING);
    FunCall call = new FunCall(function, args, id);
    return queues.isEmpty() ? call : new Lift(queues, slotVars, call, id);
  }

  /**
   * Reports what is wrong with the param {@code key} of {@code box} against {@code entry}, at the
   * box: a key the entry lacks, {@code unknown-parameter}; a table for a string param or a string
   * for a table param (ADR-0026, #83), {@code wrong-parameter-type}; a string outside the values of
   * a param that lists them, or a pattern the formatting core cannot translate for a param with a
   * syntax (ADR-0026, #74), {@code invalid-parameter-value}, naming the element the core cannot
   * translate. A table for a table param, and a string the param takes, any string when it has
   * neither values nor a syntax, are reported as nothing.
   */
  private void param(
      AuthoringId id, StandardFunctionBox box, FunctionCatalogue.Entry entry, String key) {
    Optional<FunctionCatalogue.Param> param = entry.param(key);
    if (param.isEmpty()) {
      report(MappingDiagnostics.UNKNOWN_PARAMETER, id, box.fn() + " has no parameter " + key);
      return;
    }
    switch (box.params().get(key)) {
      case Table _ when !param.get().table() ->
          report(
              MappingDiagnostics.WRONG_PARAMETER_TYPE,
              id,
              "the parameter " + key + " of " + box.fn() + " is a string, not a table");
      // any table of strings
      case Table _ -> {}
      case Text _ when param.get().table() ->
          report(
              MappingDiagnostics.WRONG_PARAMETER_TYPE,
              id,
              "the parameter " + key + " of " + box.fn() + " is a table, not a string");
      case Text(String value) when !param.get().takes(value) ->
          report(
              MappingDiagnostics.INVALID_PARAMETER_VALUE,
              id,
              "the parameter "
                  + key
                  + " of "
                  + box.fn()
                  + " is one of "
                  + param.get().values().orElseThrow()
                  + ", not \""
                  + value
                  + "\"");
      case Text(String value) ->
          param
              .get()
              .untranslatable(value)
              .ifPresent(
                  element ->
                      report(
                          MappingDiagnostics.INVALID_PARAMETER_VALUE,
                          id,
                          "the parameter " + key + " of " + box.fn() + ": " + element));
    }
  }

  private static List<Name> names(FieldPath path) {
    List<Name> names = new ArrayList<>();
    for (FieldPath.Step step : path.steps()) {
      names.add(new Name(step.namespace(), step.localName()));
    }
    return names;
  }

  /** Whether {@code step} is an attribute's (ADR-0007: its kind); a JSON property is none. */
  private static boolean attribute(FieldPath.Step step) {
    return switch (step.kind()) {
      case ATTRIBUTE -> true;
      case ELEMENT, JSON_PROPERTY -> false;
    };
  }

  /**
   * The depths of the fields on {@code named}'s chain that are JSON array properties, the root
   * being 1 (#98): the fields with the array marker, whatever their max (#118). An element or
   * attribute never carries the marker (ADR-0008).
   */
  private static List<Integer> arrays(Named named) {
    List<Integer> arrays = new ArrayList<>();
    for (int depth = 1; depth <= named.chain().size(); depth++) {
      if (named.chain().get(depth - 1).array()) {
        arrays.add(depth);
      }
    }
    return arrays;
  }

  /**
   * The context's steps from the root down; when the box names none, the field's parent, which is
   * an attribute's element (ADR-0007).
   */
  private static int contextDepth(SourceFieldBox box, FieldPath field) {
    return box.context()
        .map(context -> FieldPathText.steps(context).size() - 1)
        .orElse(field.steps().size() - 1);
  }

  /**
   * The content of {@code parent}, in target field order: the {@code TargetSlots} of each mapped
   * child field, and the {@code TargetRepeat} of each unmapped child structure field that is {@code
   * n..n} with {@code n > 0}. A mapped child whose target field mapping does not lower, which is
   * refused at that mapping, gives nothing, and the walk goes on below it as below a mapped child,
   * so that one compile reports every stage-3 diagnostic (#91). An unmapped optional child is left
   * out with its subtree. An unmapped child leaf with {@code minOccurs > 0} would be missing from
   * every occurrence of {@code parent}, so it is refused at its absent target field mapping with
   * {@code unmapped-mandatory-leaf} (ADR-0006), and the walk goes on. An unmapped child structure
   * field with {@code 0 < min < max} needs a mapping in PO, so it is refused there with {@code
   * unsupported-field}, and the walk goes on without entering it. An unmapped {@code n..n}
   * structure field whose type its path already repeats {@link #RECURSION_CAP} times would be
   * created without end, so it is refused at the model's {@code /target} with {@code
   * unsupported-field} (ADR-0008), and that refusal stops the walk.
   *
   * <p>A refused child field, read with its own occurrence from {@code parent}'s type, comes before
   * all of these and is never entered (ADR-0008 Shape (#92)): one a target field mapping names is
   * refused at that mapping, an unmapped one with {@code minOccurs > 0} is refused once per path at
   * its absent target field mapping with its reason's code, instead of {@code
   * unmapped-mandatory-leaf} or {@code unsupported-field}, and an unmapped optional one is left
   * out.
   *
   * <p>{@code chain} holds the fields from the root down to {@code parent}, the last one the field
   * whose own child fields the walk reads, so it goes below each same-step sibling it creates
   * (#92).
   */
  private List<Expr> children(FieldPath parent, List<Field> chain, Map<FieldPath, Link> links) {
    List<Expr> content = new ArrayList<>();
    // the refused paths reported at their pointer under this parent
    Set<FieldPath> refused = new HashSet<>();
    for (Field field : childFields(target, chain.getLast())) {
      if (endless) {
        break;
      }
      FieldPath child = parent.child(field.step());
      List<Field> below = append(chain, field);
      Link link = links.get(child);
      if (field.refusal().isPresent()) {
        // never entered: a mapping that names it is refused at the mapping, and an optional one
        // is left out
        if (!mapped.contains(child) && field.occurrence().min() > 0 && refused.add(child)) {
          found.add(
              refusal(
                  child,
                  field.refusal().get(),
                  new AtPointer("/targetFieldMappings/" + pointerSegment(child))));
        }
      } else if (link != null) {
        content.add(
            single(link.value()) || generatingBlock(link.value())
                ? generated(child, below, link, links)
                : slots(child, below, link, links));
      } else if (mapped.contains(child)) {
        // its target field mapping is refused already; a leaf's children are its attributes
        children(child, below, links);
      } else if (field.valueType().isPresent()) {
        if (field.occurrence().min() > 0) {
          found.add(
              new Diagnostic(
                  Severity.ERROR,
                  MappingDiagnostics.UNMAPPED_MANDATORY_LEAF,
                  new AtPointer("/targetFieldMappings/" + pointerSegment(child)),
                  "the target leaf "
                      + child
                      + " ("
                      + field.occurrence()
                      + ") has no target field mapping, so the output would lack it"));
        }
      } else if (repeated(field.occurrence())) {
        // an attribute is a leaf (ADR-0008), so only an element gets here
        if (repeats(below) > RECURSION_CAP) {
          endless = true;
          found.add(
              new Diagnostic(
                  Severity.ERROR,
                  MappingDiagnostics.UNSUPPORTED_FIELD,
                  new AtPointer("/target"),
                  "the unmapped "
                      + child
                      + " ("
                      + field.occurrence()
                      + ") repeats its type more than "
                      + RECURSION_CAP
                      + " times on its path, so it would be created without end"));
        } else {
          content.add(repeat(child, below, links));
        }
      } else if (field.occurrence().min() > 0) {
        // 0 < min < max: a mapped field below is refused at its own mapping too (targetField)
        found.add(
            new Diagnostic(
                Severity.ERROR,
                MappingDiagnostics.UNSUPPORTED_FIELD,
                new AtPointer("/targetFieldMappings/" + pointerSegment(child)),
                "the unmapped "
                    + child
                    + " ("
                    + field.occurrence()
                    + ") needs a target field mapping of its own"));
      }
    }
    return content;
  }

  /** {@code path}'s text as one RFC 6901 pointer segment: {@code ~} and {@code /} escaped. */
  private static String pointerSegment(FieldPath path) {
    return path.toString().replace("~", "~0").replace("/", "~1");
  }

  /** How many fields of {@code chain}, the root and its last field included, have its type. */
  private static long repeats(List<Field> chain) {
    Field field = chain.getLast();
    return chain.stream().filter(f -> f.type().equals(field.type())).count();
  }

  /**
   * The {@code TargetRepeat} of the unmapped structure field at {@code path}, the last of {@code
   * chain}, {@code n..n}: n copies of its element, which holds its children. No authoring element
   * creates it, so its nodes carry its field path as their origin, which no authoring id can spell
   * (ADR-0010). Its prefix is taken before its children's (ADR-0022).
   */
  private TargetRepeat repeat(FieldPath path, List<Field> chain, Map<FieldPath, Link> links) {
    Field field = chain.getLast();
    int depth = path.steps().size();
    AuthoringId origin = new AuthoringId(path.toString());
    FieldPath.Step step = field.step();
    String prefix = prefix(step.namespace());
    return new TargetRepeat(
        field.occurrence().min(),
        new VarRef(parentSlot(depth), AtomicType.INTEGER, origin),
        SLOT + depth,
        new ElementConstructor(
            prefix,
            new Name(step.namespace(), step.localName()),
            children(path, chain, links),
            origin),
        origin);
  }

  /** The parent's slot-number variable of a field at {@code depth}. */
  private static String parentSlot(int depth) {
    return depth == UNDER_ROOT ? ROOT_SLOT : SLOT + (depth - 1);
  }

  /**
   * The {@code TargetSlots} of the mapped field at {@code path}, whose target field mapping gives a
   * queue: its variables by its depth, its parent's slot number, its effective occurrence, 0..1 or
   * 1..1 for an attribute, and its element or attribute, which holds the slot's value for a leaf.
   */
  private TargetSlots slots(
      FieldPath path, List<Field> chain, Link link, Map<FieldPath, Link> links) {
    int depth = path.steps().size();
    String valueVar = VALUE + depth;
    AuthoringId origin = link.mapping();
    Occurrence occurrence = link.field().occurrence();
    return new TargetSlots(
        link.value(),
        new VarRef(parentSlot(depth), AtomicType.INTEGER, origin),
        occurrence.min(),
        occurrence.max(),
        SLOT + depth,
        valueVar,
        constructor(path, chain, link, new VarRef(valueVar, AtomicType.STRING, origin), links),
        origin);
  }

  /**
   * The {@code TargetRepeat} of the mapped field at {@code path}, whose target field mapping is a
   * generating function, a single value or a generating block (ADR-0006 "generating function" since
   * #83, F-46): {@code max(1, minOccurs)} copies of its element or attribute per parent occurrence,
   * whatever its {@code maxOccurs}, so one copy of an attribute, its slot-number variable by its
   * depth, and the target field mapping as the origin. A single value is computed once per copy: a
   * leaf's element holds it as its text, an attribute as its value, and a structure field, which
   * has none, keeps it as the {@code TargetRepeat}'s value, its element holding its children only
   * (ADR-0006 as amended by #83 and #102). A generating block is the {@code TargetRepeat}'s value
   * on every field, with its value variable {@code value<d>} by its depth, which a leaf's text and
   * an attribute's value read; a structure field's element holds its children only (#217).
   */
  private TargetRepeat generated(
      FieldPath path, List<Field> chain, Link link, Map<FieldPath, Link> links) {
    int depth = path.steps().size();
    AuthoringId origin = link.mapping();
    Optional<Expr> value;
    Optional<String> valueVar;
    Expr leafValue;
    if (generatingBlock(link.value())) {
      value = Optional.of(link.value());
      valueVar = Optional.of(VALUE + depth);
      leafValue = new VarRef(valueVar.get(), AtomicType.STRING, origin);
    } else {
      value = link.field().valueType().isPresent() ? Optional.empty() : Optional.of(link.value());
      valueVar = Optional.empty();
      leafValue = link.value();
    }
    return new TargetRepeat(
        Math.max(1, link.field().occurrence().min()),
        new VarRef(parentSlot(depth), AtomicType.INTEGER, origin),
        SLOT + depth,
        value,
        valueVar,
        constructor(path, chain, link, leafValue, links),
        origin);
  }

  /**
   * The attribute or element of the mapped field at {@code path}, the last of {@code chain}. An
   * attribute's value is {@code leafValue}. An element holds its children in target field order,
   * its attributes first, which are all a leaf has, and then a leaf's text, {@code leafValue}
   * (ADR-0006 since #102). Its prefix is taken before its children's, so prefixes follow the walk:
   * an element, its attributes, then its child elements (ADR-0022).
   */
  private Expr constructor(
      FieldPath path, List<Field> chain, Link link, Expr leafValue, Map<FieldPath, Link> links) {
    Field field = link.field();
    FieldPath.Step step = field.step();
    AuthoringId origin = link.mapping();
    String prefix = prefix(step.namespace());
    Name name = new Name(step.namespace(), step.localName());
    if (attribute(step)) {
      return new AttributeConstructor(prefix, name, leafValue, origin);
    }
    List<Expr> content = children(path, chain, links);
    if (field.valueType().isPresent()) {
      content.add(new TextConstructor(leafValue, origin));
    }
    return new ElementConstructor(prefix, name, content, origin);
  }

  /** {@code ns0}, {@code ns1} … per namespace in the order met; none for no namespace. */
  private String prefix(String namespace) {
    if (namespace.isEmpty()) {
      return "";
    }
    return prefixes.computeIfAbsent(namespace, ns -> "ns" + prefixes.size());
  }

  private void report(String code, AuthoringId at, String message) {
    found.add(new Diagnostic(Severity.ERROR, code, new AtAuthoringId(at), message));
  }
}
