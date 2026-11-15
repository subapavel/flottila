package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.ContextFunctions;
import io.flottila.mapping.xdm.ContextFunctions.Comparison;
import java.util.List;
import java.util.Map;

/**
 * The function family {@code context} (ADR-0025 "Families", F-15), spec 2's eleven functions: each
 * catalogue name to its binder. A queue function binds to its algebra in {@link ContextFunctions},
 * its params parsed here, into the node; a data error of its algebra stops the run with the
 * function's one code at its box (ADR-0025 since #52). The binders are methods, not lambdas of the
 * maps, so that PIT mutates them: it leaves out what only a static initializer runs.
 */
final class ContextFamily {

  /** The family's queue functions. */
  static final Map<String, QueueLibrary.Binder> QUEUE =
      Map.ofEntries(
          Map.entry("collapseContexts", ContextFamily::collapseContexts),
          Map.entry("createIf", ContextFamily::createIf),
          Map.entry("exists", ContextFamily::exists),
          Map.entry("formatByExample", ContextFamily::formatByExample),
          Map.entry("mapWithDefault", ContextFamily::mapWithDefault),
          Map.entry("removeContexts", ContextFamily::removeContexts),
          Map.entry("replaceValue", ContextFamily::replaceValue),
          Map.entry("sort", ContextFamily::sort),
          Map.entry("sortByKey", ContextFamily::sortByKey),
          Map.entry("splitByValue", ContextFamily::splitByValue),
          Map.entry("useOneAsMany", ContextFamily::useOneAsMany));

  /**
   * The family's single-value functions: none since #216, as replaceValue reads SUPPRESS (F-45).
   */
  static final Map<String, SingleValueLibrary.Binder> VALUE = Map.of();

  private ContextFamily() {}

  static Node.OfQueue collapseContexts(
      AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue input = inputs.getFirst();
    return frame -> ContextFunctions.collapseContexts(input.eval(frame));
  }

  static Node.OfQueue createIf(AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue condition = inputs.getFirst();
    return QueueLibrary.raising(
        RuntimeErrors.CREATEIF, box, frame -> ContextFunctions.createIf(condition.eval(frame)));
  }

  static Node.OfQueue exists(AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue input = inputs.getFirst();
    return frame -> ContextFunctions.exists(input.eval(frame));
  }

  static Node.OfQueue formatByExample(
      AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue values = inputs.get(0);
    Node.OfQueue example = inputs.get(1);
    return QueueLibrary.raising(
        RuntimeErrors.FORMATBYEXAMPLE,
        box,
        frame -> ContextFunctions.formatByExample(values.eval(frame), example.eval(frame)));
  }

  static Node.OfQueue mapWithDefault(
      AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue input = inputs.getFirst();
    String defaultValue = params.getFirst();
    return frame -> ContextFunctions.mapWithDefault(input.eval(frame), defaultValue);
  }

  static Node.OfQueue removeContexts(
      AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue input = inputs.getFirst();
    return frame -> ContextFunctions.removeContexts(input.eval(frame));
  }

  static Node.OfQueue sort(AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue input = inputs.getFirst();
    Comparison comparison = comparison("sort", params.get(0));
    boolean descending = descending("sort", params.get(1));
    return QueueLibrary.raising(
        RuntimeErrors.SORT,
        box,
        frame -> ContextFunctions.sort(input.eval(frame), comparison, descending));
  }

  static Node.OfQueue sortByKey(AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue keys = inputs.get(0);
    Node.OfQueue values = inputs.get(1);
    Comparison comparison = comparison("sortByKey", params.get(0));
    boolean descending = descending("sortByKey", params.get(1));
    return QueueLibrary.raising(
        RuntimeErrors.SORTBYKEY,
        box,
        frame ->
            ContextFunctions.sortByKey(
                keys.eval(frame), values.eval(frame), comparison, descending));
  }

  /** {@code splitByValue} over its input, its mode parsed once. */
  static Node.OfQueue splitByValue(
      AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue input = inputs.getFirst();
    String mode = params.getFirst();
    return switch (mode) {
      case "eachValue" -> frame -> ContextFunctions.splitByEachValue(input.eval(frame));
      case "valueChange" -> frame -> ContextFunctions.splitByValueChange(input.eval(frame));
      case "emptyValue" -> frame -> ContextFunctions.splitByEmptyValue(input.eval(frame));
      default -> throw new IllegalArgumentException("splitByValue has no mode " + mode);
    };
  }

  static Node.OfQueue useOneAsMany(
      AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue value = inputs.get(0);
    Node.OfQueue occurrences = inputs.get(1);
    Node.OfQueue contexts = inputs.get(2);
    return QueueLibrary.raising(
        RuntimeErrors.USEONEASMANY,
        box,
        frame ->
            ContextFunctions.useOneAsMany(
                value.eval(frame), occurrences.eval(frame), contexts.eval(frame)));
  }

  /**
   * {@code replaceValue(value, replacement)}: the replacement at every slot of its input, SUPPRESS
   * included (F-45). The input is evaluated first, so a data error in it is raised at its box.
   */
  static Node.OfQueue replaceValue(
      AuthoringId box, List<Node.OfQueue> inputs, List<String> params) {
    Node.OfQueue value = inputs.getFirst();
    String replacement = params.getFirst();
    return frame -> ContextFunctions.replaceValue(value.eval(frame), replacement);
  }

  /** The {@code comparison} param of {@code function}, a sort. */
  private static Comparison comparison(String function, String comparison) {
    return switch (comparison) {
      case "lexicographic" -> Comparison.LEXICOGRAPHIC;
      case "caseInsensitive" -> Comparison.CASE_INSENSITIVE;
      case "numeric" -> Comparison.NUMERIC;
      default -> throw new IllegalArgumentException(function + " has no comparison " + comparison);
    };
  }

  /** Whether the {@code order} param of {@code function}, a sort, is descending. */
  private static boolean descending(String function, String order) {
    return switch (order) {
      case "ascending" -> false;
      case "descending" -> true;
      default -> throw new IllegalArgumentException(function + " has no order " + order);
    };
  }
}
